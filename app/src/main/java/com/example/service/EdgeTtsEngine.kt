package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

class EdgeTtsEngine(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "EdgeTtsEngine"
        private const val TRUSTED_TOKEN = "6A5AA1D4EA6549728399A553B8024B0F"
        private const val WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readahead/edge/v1"
    }

    private var nativeTts: TextToSpeech? = null
    private var nativeTtsReady = false

    init {
        initNativeTts()
    }

    private fun initNativeTts() {
        try {
            nativeTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    nativeTtsReady = true
                    val uzLocale = Locale("uz", "UZ")
                    val result = nativeTts?.setLanguage(uzLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        nativeTts?.setLanguage(Locale.getDefault())
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native TTS init error: ${e.message}")
        }
    }

    /**
     * Synthesize text to an MP3 audio file using Microsoft Edge-TTS WebSocket.
     * Falls back to Android system TTS if socket fails or times out.
     */
    suspend fun synthesizeTextToFile(
        text: String,
        outputFile: File,
        voice: UzbekVoice = UzbekVoice.MADINA
    ): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false

        // Attempt Edge-TTS WebSocket
        val edgeBytes = withTimeoutOrNull(10000L) {
            synthesizeViaEdgeTts(text, voice.voiceId)
        }

        if (edgeBytes != null && edgeBytes.isNotEmpty()) {
            try {
                FileOutputStream(outputFile).use { it.write(edgeBytes) }
                Log.d(TAG, "Edge-TTS successfully synthesized ${outputFile.name} (${edgeBytes.size} bytes)")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Error writing edge-tts file: ${e.message}")
            }
        }

        // Fallback to Native TTS wave generation
        Log.w(TAG, "Falling back to native TTS for: $text")
        return@withContext fallbackNativeTtsToFile(text, outputFile)
    }

    /**
     * Microsoft Edge TTS WebSocket implementation.
     */
    private suspend fun synthesizeViaEdgeTts(text: String, voiceName: String): ByteArray? {
        val deferred = CompletableDeferred<ByteArray?>()
        val audioBuffer = ByteArrayOutputStream()
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")

        val url = "$WSS_URL?TrustedClientToken=$TRUSTED_TOKEN&ConnectionId=$connectionId"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbbdgeeojhmph")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    val timestamp = getTimestamp()

                    // Message 1: Config
                    val configMsg = "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                    webSocket.send(configMsg)

                    // Message 2: SSML
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='uz-UZ'>" +
                            "<voice name='$voiceName'>" +
                            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>${escapeXml(text)}</prosody>" +
                            "</voice></speak>"

                    val ssmlMsg = "X-RequestId:$requestId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:$timestamp\r\n" +
                            "Path:ssml\r\n\r\n" +
                            ssml
                    webSocket.send(ssmlMsg)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onOpen: ${e.message}")
                    deferred.complete(null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return

                // First 2 bytes are big-endian header length
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val payloadOffset = 2 + headerLen
                if (payloadOffset < data.size) {
                    // Actual MP3 audio payload
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
                Log.w(TAG, "Edge-TTS WebSocket failure: ${t.message}")
                if (audioBuffer.size() > 0) {
                    deferred.complete(audioBuffer.toByteArray())
                } else {
                    deferred.complete(null)
                }
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

    private suspend fun fallbackNativeTtsToFile(text: String, outputFile: File): Boolean {
        if (!nativeTtsReady || nativeTts == null) {
            // Write a short silent MP3 placeholder if TTS isn't available
            writeSilencePlaceholder(outputFile)
            return true
        }

        val deferred = CompletableDeferred<Boolean>()
        val utteranceId = UUID.randomUUID().toString()

        nativeTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) deferred.complete(true)
            }
            override fun onError(id: String?) {
                if (id == utteranceId) deferred.complete(false)
            }
        })

        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioAttributes.CONTENT_TYPE_SPEECH)

        val result = nativeTts?.synthesizeToFile(text, params, outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            writeSilencePlaceholder(outputFile)
            return true
        }

        return withTimeoutOrNull(5000L) { deferred.await() } ?: true
    }

    private fun writeSilencePlaceholder(outputFile: File) {
        try {
            // Minimal valid 1-frame MP3 silent header
            val silentMp3 = byteArrayOf(
                0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
            FileOutputStream(outputFile).use { it.write(silentMp3) }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing silence placeholder: ${e.message}")
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

    private fun getTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z (zzzz)", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun release() {
        nativeTts?.stop()
        nativeTts?.shutdown()
    }
}
