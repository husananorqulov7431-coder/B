package com.example.service

import android.util.Log
import com.example.model.SubtitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "YouTubeService"

        /**
         * Safely extracts exact 11-character YouTube video ID.
         * Returns null if link is invalid or incomplete (e.g. less than 11 characters).
         */
        fun extractVideoId(urlOrId: String): String? {
            val trimmed = urlOrId.trim()
            if (trimmed.isBlank()) return null

            // 1. Direct 11-character ID (alphanumeric, -, _)
            if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                return trimmed
            }

            // 2. youtu.be/XXXXXXXXXXX
            val youtuBeMatcher = Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(trimmed)
            if (youtuBeMatcher != null) {
                return youtuBeMatcher.groupValues[1]
            }

            // 3. youtube.com/watch?v=XXXXXXXXXXX
            val watchMatcher = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(trimmed)
            if (watchMatcher != null) {
                return watchMatcher.groupValues[1]
            }

            // 4. youtube.com/shorts/XXXXXXXXXXX
            val shortsMatcher = Regex("shorts/([a-zA-Z0-9_-]{11})").find(trimmed)
            if (shortsMatcher != null) {
                return shortsMatcher.groupValues[1]
            }

            // 5. youtube.com/embed/XXXXXXXXXXX
            val embedMatcher = Regex("embed/([a-zA-Z0-9_-]{11})").find(trimmed)
            if (embedMatcher != null) {
                return embedMatcher.groupValues[1]
            }

            // 6. Generic search for any 11-character token preceded by slash or equals
            val genericMatcher = Regex("[/=]([a-zA-Z0-9_-]{11})(?:[&?]|\$)").find(trimmed)
            if (genericMatcher != null) {
                return genericMatcher.groupValues[1]
            }

            return null
        }
    }

    /**
     * Fetches genuine captions from YouTube without any fake or pre-baked text.
     */
    suspend fun getSubtitles(videoId: String): List<SubtitleItem> = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(4000L) {
            tryDirectTimedText(videoId)
        }

        if (!result.isNullOrEmpty()) {
            Log.d(TAG, "Found ${result.size} real subtitles for video: $videoId")
            return@withContext result
        }

        // Return empty if video has no captions (no fake dialogue!)
        Log.w(TAG, "No timed subtitles found on YouTube for $videoId")
        return@withContext emptyList()
    }

    private fun tryDirectTimedText(videoId: String): List<SubtitleItem> {
        val candidateUrls = listOf(
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=en&fmt=srv3",
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=en",
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=en&kind=asr",
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=ru&fmt=srv3",
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=ru"
        )

        for (url in candidateUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val parsed = parseXmlSubtitles(body)
                        if (parsed.isNotEmpty()) {
                            return parsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Timedtext error ($url): ${e.message}")
            }
        }
        return emptyList()
    }

    private fun parseXmlSubtitles(xml: String): List<SubtitleItem> {
        val list = mutableListOf<SubtitleItem>()
        if (xml.isBlank()) return list

        val textRegex = Pattern.compile("<text\\s+start=\"([0-9.]+)\"\\s+dur=\"([0-9.]+)\"[^>]*>([^<]*)</text>")
        val m1 = textRegex.matcher(xml)
        while (m1.find()) {
            val startSec = m1.group(1)?.toDoubleOrNull() ?: 0.0
            val durSec = m1.group(2)?.toDoubleOrNull() ?: 2.5
            val rawText = cleanHtml(m1.group(3) ?: "")
            if (rawText.isNotBlank()) {
                list.add(
                    SubtitleItem(
                        startMs = (startSec * 1000.0).toLong(),
                        durationMs = (durSec * 1000.0).toLong().coerceAtLeast(1500L),
                        originalText = rawText
                    )
                )
            }
        }
        return list
    }

    private fun cleanHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("\n", " ")
            .trim()
    }

    /**
     * Translates English/Russian lines into Uzbek in batches using Google Translate API.
     */
    suspend fun batchTranslateToUzbek(lines: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()

        // Batch in groups of 10 lines
        val results = mutableListOf<String>()
        val batches = lines.chunked(10)

        for (batch in batches) {
            val joined = batch.joinToString("\n")
            try {
                val encoded = URLEncoder.encode(joined, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=uz&dt=t&q=$encoded"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONArray(body)
                        val sentences = json.getJSONArray(0)
                        val sb = StringBuilder()
                        for (i in 0 until sentences.length()) {
                            sb.append(sentences.getJSONArray(i).getString(0))
                        }
                        val translated = sb.toString().split("\n").map { it.trim() }
                        if (translated.size == batch.size) {
                            results.addAll(translated)
                            return@use
                        }
                    }
                    results.addAll(batch)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Batch translation error: ${e.message}")
                results.addAll(batch)
            }
        }

        return@withContext results
    }

    /**
     * Translates a single text line into Uzbek.
     */
    suspend fun translateSingleToUzbek(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=uz&dt=t&q=$encoded"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONArray(body)
                    val sentences = json.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until sentences.length()) {
                        sb.append(sentences.getJSONArray(i).getString(0))
                    }
                    return@withContext sb.toString().trim()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Single translate failed: ${e.message}")
        }
        return@withContext text
    }
}
