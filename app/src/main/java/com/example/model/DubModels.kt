package com.example.model

import java.io.File

/**
 * Subtitle line with timing and text.
 */
data class SubtitleItem(
    val startMs: Long,
    val durationMs: Long,
    val originalText: String,
    val translatedText: String = ""
) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * Voice selection for Edge-TTS Uzbek voices.
 */
enum class UzbekVoice(val voiceId: String, val displayName: String, val gender: String) {
    MADINA("uz-UZ-MadinaNeural", "Madina", "Ayol"),
    SARDOR("uz-UZ-SardorNeural", "Sardor", "Erkak")
}

/**
 * Status of each 60-second video/audio chunk.
 */
data class ChunkItem(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val subtitles: List<SubtitleItem>,
    val audioFile: File? = null,
    val isReady: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "Navbatda"
) {
    val durationSeconds: Long get() = (endMs - startMs) / 1000L
    val timeLabel: String get() {
        val sMin = (startMs / 60000L)
        val sSec = (startMs % 60000L) / 1000L
        val eMin = (endMs / 60000L)
        val eSec = (endMs % 60000L) / 1000L
        return String.format("%02d:%02d - %02d:%02d", sMin, sSec, eMin, eSec)
    }
}

/**
 * Overall dubbing and player states.
 */
sealed class DubProcessState {
    object Idle : DubProcessState()
    data class FetchingSubtitles(val videoId: String, val progress: Float = 0.1f) : DubProcessState()
    data class Translating(val current: Int, val total: Int, val progress: Float = 0.35f) : DubProcessState()
    data class SynthesizingAudio(val chunkIndex: Int, val progress: Float = 0.7f) : DubProcessState()
    data class ReadyPlaying(val currentChunk: Int, val totalChunks: Int) : DubProcessState()
    data class Error(val message: String) : DubProcessState()
}
