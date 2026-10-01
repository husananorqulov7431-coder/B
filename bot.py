#!/usr/bin/env python3
"""
Telegram Bot for YouTube Uzbek Dubbing
Token: 8632489493:AAGVkQCVRq4D9mU2NpxrPP-MMx0LOebaMpc
"""

import os
import sys
import logging
import asyncio
from pathlib import Path
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, MessageHandler, filters, ContextTypes
from dubber import YouTubeDubber

logging.basicConfig(
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    level=logging.INFO
)
logger = logging.getLogger(__name__)

BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "8632489493:AAGVkQCVRq4D9mU2NpxrPP-MMx0LOebaMpc")
dubber = YouTubeDubber(output_dir="/tmp/ytdub_bot")

async def start_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    msg = (
        "👋 Assalomu alaykum!\n\n"
        "Men YouTube videolarni o'zbek tiliga avtomatik dublyaj qiluvchi botman.\n\n"
        "🎥 Menga istalgan YouTube video havolasini yuboring (masalan: https://youtu.be/... yoki https://www.youtube.com/watch?v=...),\n"
        "men subtitrlarni olib, o'zbek tiliga tarjima qilaman va Edge-TTS (Madina/Sardor) ovozida dublyaj qilingan audioni yuboraman."
    )
    await update.message.reply_text(msg)

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    text = update.message.text.strip()
    if not ("youtube.com" in text or "youtu.be" in text):
        await update.message.reply_text("⚠️ Iltimos, to'g'ri YouTube video havolasini yuboring.")
        return

    status_msg = await update.message.reply_text("⏳ Video qabul qilindi. Subtitrlar olinmoqda va o'zbek tiliga tarjima qilinmoqda...")

    try:
        loop = asyncio.get_event_loop()
        res = await loop.run_in_executor(None, dubber.dub_video, text)

        if not res.get("success"):
            err = res.get("error", "Subtitrlar topilmadi.")
            await status_msg.edit_text(f"❌ {err}")
            return

        await status_msg.edit_text(f"✅ Dublyaj tayyor! {res.get('subtitles_count', 0)} ta jumla tarjima qilindi. Audio yuklanmoqda...")

        # Send Dubbed Audio File
        dubbed_audio = res.get("dubbed_audio")
        if dubbed_audio and os.path.exists(dubbed_audio):
            await update.message.reply_audio(
                audio=open(dubbed_audio, "rb"),
                title="YouTube Uzbek Dubbing",
                performer="Edge-TTS (Madina)",
                caption="🎙 O'zbek tilidagi to'liq audio dublyaj"
            )

        # Send SRT Subtitles File
        srt_file = res.get("srt_file")
        if srt_file and os.path.exists(srt_file):
            await update.message.reply_document(
                document=open(srt_file, "rb"),
                caption="📝 O'zbekcha subtitrlar fayli (.srt)"
            )

    except Exception as e:
        logger.error(f"Error processing video: {e}", exc_info=True)
        await status_msg.edit_text(f"❌ Xatolik yuz berdi: {str(e)}")

def main():
    print("Starting YouTube Dubber Telegram Bot...")
    app = ApplicationBuilder().token(BOT_TOKEN).build()
    app.add_handler(CommandHandler("start", start_command))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    app.run_polling()

if __name__ == "__main__":
    main()
