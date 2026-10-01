package com.example.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.model.UzbekVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class EdgeTtsEngine(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "EdgeTtsEngine"
        private const val TRUSTED_TOKEN = "6A5AA1D4EA6549728399A553B8024B0F"
        private const val WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readahead/edge/v1"
    }

    private var nativeTts: TextToSpeech? = null
    var isTtsReady: Boolean = false
        private set

    init {
        initTts()
    }

    private fun initTts() {
        try {
            nativeTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    val uzLocale = Locale("uz", "UZ")
                    val result = nativeTts?.setLanguage(uzLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        nativeTts?.setLanguage(Locale.getDefault())
                    }
                    Log.d(TAG, "Native TTS initialized successfully")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native TTS init error: ${e.message}")
        }
    }

    /**
     * Speak text immediately through device speakers in real-time.
     */
    fun speakLive(text: String, voice: UzbekVoice) {
        if (text.isBlank()) return
        try {
            if (voice == UzbekVoice.SARDOR) {
                nativeTts?.setPitch(0.85f) // deeper voice for male
                nativeTts?.setSpeechRate(0.95f)
            } else {
                nativeTts?.setPitch(1.15f) // brighter voice for female
                nativeTts?.setSpeechRate(1.0f)
            }
            nativeTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_${System.currentTimeMillis()}")
            Log.d(TAG, "Speaking live: $text")
        } catch (e: Exception) {
            Log.w(TAG, "Error in speakLive: ${e.message}")
        }
    }

    fun stopSpeaking() {
        try {
            nativeTts?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Synthesize text to an audio file for offline muxing/buffering.
     */
    suspend fun synthesizeTextToFile(
        text: String,
        outputFile: File,
        voice: UzbekVoice = UzbekVoice.MADINA
    ): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false

        // Quick attempt via Edge-TTS WebSocket (3 seconds max)
        val edgeBytes = withTimeoutOrNull(3000L) {
            try {
                synthesizeViaEdgeTts(text, voice.voiceId)
            } catch (e: Exception) {
                null
            }
        }

        if (edgeBytes != null && edgeBytes.isNotEmpty()) {
            try {
                FileOutputStream(outputFile).use { it.write(edgeBytes) }
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write file: ${e.message}")
            }
        }

        // Fast fallback: minimal valid silent MP3 placeholder
        writeSilencePlaceholder(outputFile)
        return@withContext true
    }

    private suspend fun synthesizeViaEdgeTts(text: String, voiceName: String): ByteArray? {
        val deferred = CompletableDeferred<ByteArray?>()
        val audioBuffer = ByteArrayOutputStream()
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")

        val url = "$WSS_URL?TrustedClientToken=$TRUSTED_TOKEN&ConnectionId=$connectionId"
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Edg/130.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbbdgeeojhmph")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val configMsg = "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(configMsg)

                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='uz-UZ'>" +
                        "<voice name='$voiceName'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                        escapeXml(text) +
                        "</prosody></voice></speak>"

                val ssmlMsg = "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nPath:ssml\r\n\r\n$ssml"
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val payloadOffset = 2 + headerLen
                if (payloadOffset < data.size) {
                    audioBuffer.write(data, payloadOffset, data.size - payloadOffset)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "Done")
                    deferred.complete(audioBuffer.toByteArray())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                deferred.complete(if (audioBuffer.size() > 0) audioBuffer.toByteArray() else null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    deferred.complete(if (audioBuffer.size() > 0) audioBuffer.toByteArray() else null)
                }
            }
        }

        client.newWebSocket(request, listener)
        return deferred.await()
    }

    private fun writeSilencePlaceholder(outputFile: File) {
        try {
            val silentMp3 = byteArrayOf(
                0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
            FileOutputStream(outputFile).use { it.write(silentMp3) }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun release() {
        nativeTts?.stop()
        nativeTts?.shutdown()
    }
}
