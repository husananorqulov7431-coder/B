#!/usr/bin/env python3
"""
YouTube Video Uzbek Dubbing Engine
Powered by yt-dlp, clients5 Google Translate, Edge-TTS, and FFmpeg.
"""

import os
import sys
import json
import re
import asyncio
import subprocess
import urllib.request
import urllib.parse
from pathlib import Path
import edge_tts

class YouTubeDubber:
    def __init__(self, output_dir="/tmp/ytdub"):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def extract_subtitles(self, url: str) -> list:
        """Extracts subtitles from YouTube using yt-dlp."""
        sub_prefix = self.output_dir / "sub"
        for f in self.output_dir.glob("sub*"):
            try:
                f.unlink(missing_ok=True)
            except Exception:
                pass

        cmd = [
            "yt-dlp",
            "--write-sub",
            "--write-auto-sub",
            "--sub-lang", "en",
            "--sub-format", "vtt",
            "--skip-download",
            "-o", str(sub_prefix),
            url
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        
        vtt_files = list(self.output_dir.glob("sub*.vtt"))
        if not vtt_files:
            print("No subtitles found by yt-dlp.")
            return []

        target_vtt = vtt_files[0]
        print(f"Parsing subtitles from: {target_vtt}")
        return self.parse_vtt(target_vtt)

    def parse_vtt(self, vtt_path: Path) -> list:
        """Parses WebVTT subtitle lines line by line."""
        subtitles = []
        with open(vtt_path, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()

        current_sub = None
        time_regex = re.compile(r"(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})")

        for line in lines:
            line_clean = line.strip()
            m = time_regex.search(line_clean)
            if m:
                if current_sub and current_sub.get("text"):
                    subtitles.append(current_sub)
                current_sub = {
                    "start": self.time_to_seconds(m.group(1)),
                    "end": self.time_to_seconds(m.group(2)),
                    "text": ""
                }
            elif current_sub and line_clean:
                if line_clean.startswith("WEBVTT") or line_clean.startswith("Kind:") or line_clean.startswith("Language:"):
                    continue
                cleaned = re.sub(r"<[^>]+>", "", line_clean)
                cleaned = re.sub(r"\[.*?\]|\(.*?\)", "", cleaned)
                cleaned = cleaned.replace("♪", "").replace("♫", "").strip()
                if cleaned:
                    if current_sub["text"]:
                        current_sub["text"] += " " + cleaned
                    else:
                        current_sub["text"] = cleaned

        if current_sub and current_sub.get("text"):
            subtitles.append(current_sub)

        return subtitles

    @staticmethod
    def time_to_seconds(t_str: str) -> float:
        parts = t_str.split(":")
        if len(parts) == 3:
            return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])
        elif len(parts) == 2:
            return int(parts[0]) * 60 + float(parts[1])
        return 0.0

    def translate_subtitles(self, subs: list) -> list:
        """Translates subtitle texts into Uzbek using fast reliable Google Translate API."""
        print(f"Translating {len(subs)} subtitle lines into Uzbek...")
        translated_subs = []

        # Batch in groups of 30
        for i in range(0, len(subs), 30):
            chunk = subs[i:i+30]
            raw_texts = [s["text"] for s in chunk]
            try:
                joined = "\n".join(raw_texts)
                url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=uz&q=" + urllib.parse.quote(joined)
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
                res = urllib.request.urlopen(req, timeout=8).read().decode("utf-8")
                data = json.loads(res)
                translated_block = data[0][0]
                uz_lines = translated_block.split("\n")

                for idx, s in enumerate(chunk):
                    s["uz_text"] = uz_lines[idx].strip() if idx < len(uz_lines) and uz_lines[idx].strip() else s["text"]
                    translated_subs.append(s)
            except Exception as e:
                print(f"Translation batch error: {e}")
                for s in chunk:
                    s["uz_text"] = s["text"]
                    translated_subs.append(s)

        return translated_subs

    async def generate_speech_file(self, text: str, out_path: str, voice: str = "uz-UZ-MadinaNeural"):
        """Generates natural speech MP3 via Microsoft Edge TTS."""
        communicate = edge_tts.Communicate(text, voice)
        await communicate.save(out_path)

    def dub_video(self, url: str, voice: str = "uz-UZ-MadinaNeural", max_duration_sec: int = 180) -> dict:
        """Full pipeline: extract, translate, synthesize audio, and output."""
        print(f"--- 1. Subtitrlarni olish: {url} ---")
        subs = self.extract_subtitles(url)
        if not subs:
            return {"error": "Subtitrlar topilmadi. Video avtomatik yoki qo'lda kiritilgan subtitrlarga ega ekanligini tekshiring."}

        # Filter within limit
        subs = [s for s in subs if s["start"] <= max_duration_sec]

        print(f"--- 2. O'zbek tiliga tarjima qilish ({len(subs)} ta jumla) ---")
        translated_subs = self.translate_subtitles(subs)

        print("--- 3. Edge-TTS bilan o'zbekcha audio sintezi ---")
        audio_segments = []
        loop = asyncio.get_event_loop()
        
        for idx, sub in enumerate(translated_subs):
            out_file = self.output_dir / f"seg_{idx:04d}.mp3"
            try:
                loop.run_until_complete(
                    self.generate_speech_file(sub["uz_text"], str(out_file), voice)
                )
                if out_file.exists() and out_file.stat().st_size > 100:
                    audio_segments.append({
                        "file": str(out_file),
                        "start": sub["start"],
                        "uz_text": sub["uz_text"]
                    })
            except Exception as e:
                print(f"TTS error on segment {idx}: {e}")

        # Download original video audio
        print("--- 4. Original YouTube audio oqimi ---")
        orig_audio = self.output_dir / "original.mp3"
        orig_audio.unlink(missing_ok=True)
        subprocess.run([
            "yt-dlp",
            "-x", "--audio-format", "mp3",
            "-o", str(orig_audio),
            url
        ], capture_output=True)

        # Merge segments into full dubbed audio using ffmpeg
        print("--- 5. O'zbekcha audioni sinxron birlashtirish ---")
        final_dubbed_audio = self.output_dir / "dubbed_uzbek.mp3"
        final_dubbed_audio.unlink(missing_ok=True)

        if audio_segments:
            concat_inputs = []
            filter_parts = []
            
            for i, seg in enumerate(audio_segments):
                concat_inputs.extend(["-i", seg["file"]])
                delay_ms = int(seg["start"] * 1000)
                filter_parts.append(f"[{i}]adelay={delay_ms}|{delay_ms}[a{i}];")

            mix_sources = "".join([f"[a{i}]" for i in range(len(audio_segments))])
            filter_complex = "".join(filter_parts) + f"{mix_sources}amix=inputs={len(audio_segments)}:normalize=0[outa]"

            cmd = ["ffmpeg", "-y"] + concat_inputs + ["-filter_complex", filter_complex, "-map", "[outa]", str(final_dubbed_audio)]
            subprocess.run(cmd, capture_output=True)

        # Generate SRT subtitle file
        srt_file = self.output_dir / "uzbek_subtitles.srt"
        self.generate_srt(translated_subs, srt_file)

        return {
            "success": True,
            "subtitles_count": len(translated_subs),
            "dubbed_audio": str(final_dubbed_audio) if final_dubbed_audio.exists() else None,
            "original_audio": str(orig_audio) if orig_audio.exists() else None,
            "srt_file": str(srt_file),
            "samples": translated_subs[:5]
        }

    def generate_srt(self, subs: list, out_path: Path):
        def fmt_time(s):
            h = int(s // 3600)
            m = int((s % 3600) // 60)
            sec = int(s % 60)
            ms = int((s - int(s)) * 1000)
            return f"{h:02d}:{m:02d}:{sec:02d},{ms:03d}"

        with open(out_path, "w", encoding="utf-8") as f:
            for i, sub in enumerate(subs, 1):
                f.write(f"{i}\n")
                f.write(f"{fmt_time(sub['start'])} --> {fmt_time(sub['end'])}\n")
                f.write(f"{sub['uz_text']}\n\n")

if __name__ == "__main__":
    test_url = sys.argv[1] if len(sys.argv) > 1 else "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    voice = sys.argv[2] if len(sys.argv) > 2 else "uz-UZ-MadinaNeural"
    dubber = YouTubeDubber()
    res = dubber.dub_video(test_url, voice=voice)
    print(json.dumps(res, indent=2, ensure_ascii=False))
