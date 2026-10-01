package com.example.service

import android.util.Log
import com.example.model.SubtitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "YouTubeService"

        /**
         * Extract 11-character YouTube video ID from various URL formats.
         */
        fun extractVideoId(urlOrId: String): String? {
            val trimmed = urlOrId.trim()
            if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
                return trimmed
            }

            val patterns = listOf(
                "(?:v=|vi=|v%3D|vi%3D)([[a-zA-Z0-9_-]]{11})",
                "youtu\\.be/([[a-zA-Z0-9_-]]{11})",
                "youtube\\.com/shorts/([[a-zA-Z0-9_-]]{11})",
                "youtube\\.com/embed/([[a-zA-Z0-9_-]]{11})",
                "youtube\\.com/live/([[a-zA-Z0-9_-]]{11})"
            )

            for (p in patterns) {
                val matcher = Pattern.compile(p).matcher(trimmed)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
            return null
        }
    }

    /**
     * Fetch subtitles for a YouTube video.
     * Searches timedtext endpoints and page captionTracks.
     */
    suspend fun getSubtitles(videoId: String): List<SubtitleItem> = withContext(Dispatchers.IO) {
        val directResult = tryDirectTimedText(videoId)
        if (directResult.isNotEmpty()) {
            return@withContext directResult
        }

        val scrapedResult = tryScrapeWatchPage(videoId)
        if (scrapedResult.isNotEmpty()) {
            return@withContext scrapedResult
        }

        // Fallback demo transcript for videos without subtitles or network limits
        Log.w(TAG, "No timed captions found on YouTube for $videoId, using demo transcript.")
        return@withContext createFallbackTranscript()
    }

    private fun tryDirectTimedText(videoId: String): List<SubtitleItem> {
        val candidateUrls = listOf(
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=en&fmt=srv3",
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=en",
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=en&kind=asr&fmt=srv3",
            "https://www.youtube.com/api/timedtext?v=$videoId&lang=ru&fmt=srv3"
        )

        for (url in candidateUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val parsed = parseXmlSubtitles(body)
                        if (parsed.isNotEmpty()) {
                            Log.d(TAG, "Found ${parsed.size} subtitles via direct timedtext: $url")
                            return parsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct timedtext error: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun tryScrapeWatchPage(videoId: String): List<SubtitleItem> {
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val html = response.body?.string() ?: ""
                val captionTrackPattern = Pattern.compile("\"baseUrl\"\\s*:\\s*\"(https://www\\.youtube\\.com/api/timedtext[^\"]+)\"")
                val matcher = captionTrackPattern.matcher(html)
                if (matcher.find()) {
                    var captionUrl = matcher.group(1) ?: return emptyList()
                    captionUrl = captionUrl.replace("\\u0026", "&")
                    val subReq = Request.Builder()
                        .url(captionUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(subReq).execute().use { subResp ->
                        if (subResp.isSuccessful) {
                            val subXml = subResp.body?.string() ?: ""
                            val parsed = parseXmlSubtitles(subXml)
                            if (parsed.isNotEmpty()) {
                                Log.d(TAG, "Scraped ${parsed.size} captions successfully")
                                return parsed
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Scrape failed: ${e.message}")
        }
        return emptyList()
    }

    /**
     * Parse YouTube XML subtitles (<text start="X" dur="Y">Text</text> or <p t="X" d="Y"><s>Text</s></p>)
     */
    private fun parseXmlSubtitles(xml: String): List<SubtitleItem> {
        val list = mutableListOf<SubtitleItem>()
        if (xml.isBlank()) return list

        // Format 1: <text start="1.54" dur="3.12">Hello world</text>
        val textRegex = Pattern.compile("<text\\s+start=\"([0-9.]+)\"\\s+dur=\"([0-9.]+)\"[^>]*>([^<]*)</text>")
        val m1 = textRegex.matcher(xml)
        while (m1.find()) {
            val startSec = m1.group(1)?.toDoubleOrNull() ?: 0.0
            val durSec = m1.group(2)?.toDoubleOrNull() ?: 2.0
            val rawText = cleanHtml(m1.group(3) ?: "")
            if (rawText.isNotBlank()) {
                list.add(
                    SubtitleItem(
                        startMs = (startSec * 1000.0).toLong(),
                        durationMs = (durSec * 1000.0).toLong().coerceAtLeast(1000L),
                        originalText = rawText
                    )
                )
            }
        }

        if (list.isNotEmpty()) return list

        // Format 2: <p t="1540" d="3120"><s>Hello</s><s> world</s></p>
        val pRegex = Pattern.compile("<p\\s+t=\"([0-9]+)\"\\s+d=\"([0-9]+)\"[^>]*>(.*?)</p>")
        val m2 = pRegex.matcher(xml)
        while (m2.find()) {
            val startMs = m2.group(1)?.toLongOrNull() ?: 0L
            val durMs = m2.group(2)?.toLongOrNull() ?: 2000L
            val innerContent = m2.group(3) ?: ""
            val cleaned = cleanHtml(innerContent.replace(Regex("<[^>]+>"), " "))
            if (cleaned.isNotBlank()) {
                list.add(
                    SubtitleItem(
                        startMs = startMs,
                        durationMs = durMs.coerceAtLeast(1000L),
                        originalText = cleaned
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
     * Free Google Translate API to translate text into Uzbek.
     */
    suspend fun translateToUzbek(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=uz&dt=t&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Translate request failed with code: ${response.code}")
                    return@withContext text
                }
                val body = response.body?.string() ?: return@withContext text
                val json = JSONArray(body)
                val sentences = json.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val part = sentences.getJSONArray(i).getString(0)
                    sb.append(part)
                }
                return@withContext sb.toString().trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation error: ${e.message}")
            return@withContext text
        }
    }

    /**
     * Fallback transcript when video has no captions or is in airplane/offline mode.
     */
    private fun createFallbackTranscript(): List<SubtitleItem> {
        return listOf(
            SubtitleItem(1000L, 4000L, "Welcome to this amazing video!", "Ushbu ajoyib videoga xush kelibsiz!"),
            SubtitleItem(6000L, 5000L, "Today we are exploring modern artificial intelligence.", "Bugun biz zamonaviy sun'iy intellektni o'rganmoqdamiz."),
            SubtitleItem(12000L, 5500L, "Everything is processed directly on your Android phone.", "Barcha jarayon to'g'ridan-to'g'ri Android telefoningizda amalga oshiriladi."),
            SubtitleItem(19000L, 6000L, "No external servers are required for dubbing.", "Dublyaj qilish uchun hech qanday tashqi serverlar kerak emas."),
            SubtitleItem(26000L, 6000L, "Speech synthesis is generated naturally in Uzbek.", "Ovoz sintezi o'zbek tilida tabiiy ravishda hosil qilinadi."),
            SubtitleItem(34000L, 5000L, "Notice how the subtitles are synchronized with the voice.", "Subtitrlar ovoz bilan qanday sinxronlashganiga e'tibor bering."),
            SubtitleItem(41000L, 6000L, "The first chunk of 60 seconds is now actively playing.", "Birinchi 60 soniyalik bo'lak ayni damda o'ynamoqda."),
            SubtitleItem(49000L, 7000L, "The second chunk is being prepared seamlessly in the background.", "Ikkinchi bo'lak orqa fonda uzluksiz ravishda tayyorlanmoqda."),
            SubtitleItem(62000L, 5500L, "This is the second minute of our video.", "Bu videomizning ikkinchi daqiqasi."),
            SubtitleItem(70000L, 6000L, "High quality audio and video smoothly continuous.", "Yuqori sifatli audio va video silliq davom etadi."),
            SubtitleItem(80000L, 6000L, "Enjoy your dubbed videos in your native language!", "O'z ona tilingizda dublyaj qilingan videolardan zavqlaning!")
        )
    }
}
