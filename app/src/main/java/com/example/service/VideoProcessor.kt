package com.example.service

import android.content.Context
import android.util.Log
import com.example.model.ChunkItem
import com.example.model.SubtitleItem
import com.example.model.UzbekVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class VideoProcessor(
    private val context: Context,
    private val ttsEngine: EdgeTtsEngine
) {

    companion object {
        private const val TAG = "VideoProcessor"
        const val CHUNK_DURATION_MS = 60_000L // 60 seconds (1 minute chunks)
    }

    /**
     * Splits all subtitles into 60-second chunks.
     */
    fun partitionIntoChunks(subtitles: List<SubtitleItem>): List<ChunkItem> {
        if (subtitles.isEmpty()) return emptyList()

        val maxEndMs = subtitles.maxOfOrNull { it.endMs } ?: CHUNK_DURATION_MS
        val chunkCount = ((maxEndMs + CHUNK_DURATION_MS - 1) / CHUNK_DURATION_MS).toInt().coerceAtLeast(1)

        val chunks = mutableListOf<ChunkItem>()
        for (i in 0 until chunkCount) {
            val startMs = i * CHUNK_DURATION_MS
            val endMs = (i + 1) * CHUNK_DURATION_MS
            val chunkSubs = subtitles.filter { it.startMs in startMs until endMs }
            chunks.add(
                ChunkItem(
                    index = i,
                    startMs = startMs,
                    endMs = endMs,
                    subtitles = chunkSubs,
                    statusText = if (i == 0) "1-bo'lak tayyorlanmoqda" else "Navbatda"
                )
            )
        }
        return chunks
    }

    /**
     * Process and synthesize audio for a specific chunk.
     * Reports granular progress via callback.
     */
    suspend fun processChunk(
        chunk: ChunkItem,
        voice: UzbekVoice,
        onProgress: (progress: Float, message: String) -> Unit
    ): ChunkItem = withContext(Dispatchers.IO) {
        val chunkDir = File(context.cacheDir, "dub_chunks").apply { mkdirs() }
        val outputChunkFile = File(chunkDir, "chunk_${chunk.index}_audio.mp3")

        val subs = chunk.subtitles
        if (subs.isEmpty()) {
            // Empty segment: generate brief silence
            onProgress(1.0f, "${chunk.index + 1}-bo'lak tayyor")
            return@withContext chunk.copy(
                isReady = true,
                progress = 1.0f,
                statusText = "Tayyor"
            )
        }

        val tempAudioFiles = mutableListOf<File>()

        for (i in subs.indices) {
            val sub = subs[i]
            val textToSpeak = if (sub.translatedText.isNotBlank()) sub.translatedText else sub.originalText
            val tempFile = File(chunkDir, "temp_${chunk.index}_$i.mp3")

            val stepProgress = (i.toFloat() / subs.size.toFloat()) * 0.9f
            onProgress(stepProgress, "${chunk.index + 1}-bo'lak: ${i + 1}/${subs.size} audio yaratilmoqda")

            val success = ttsEngine.synthesizeTextToFile(textToSpeak, tempFile, voice)
            if (success && tempFile.exists()) {
                tempAudioFiles.add(tempFile)
            }
        }

        onProgress(0.95f, "${chunk.index + 1}-bo'lak birlashtirilmoqda...")

        // Concatenate audio fragments into continuous chunk audio
        try {
            FileOutputStream(outputChunkFile).use { fos ->
                for (temp in tempAudioFiles) {
                    if (temp.exists() && temp.length() > 0) {
                        FileInputStream(temp).use { fis ->
                            fis.copyTo(fos)
                        }
                    }
                    temp.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error concatenating chunk audio: ${e.message}")
        }

        onProgress(1.0f, "${chunk.index + 1}-bo'lak muvaffaqiyatli tayyorlandi!")
        return@withContext chunk.copy(
            audioFile = outputChunkFile,
            isReady = true,
            progress = 1.0f,
            statusText = "Tayyor"
        )
    }

    /**
     * Finds the active subtitle at playback position (ms).
     */
    fun findSubtitleAt(subtitles: List<SubtitleItem>, positionMs: Long): SubtitleItem? {
        return subtitles.firstOrNull { positionMs in it.startMs..it.endMs }
    }
}
