package com.example.service

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.model.UzbekVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nativeTts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private val audioCache = ConcurrentHashMap<String, File>()

    init {
        initNativeTts()
    }

    private fun initNativeTts() {
        try {
            nativeTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val uzLocale = Locale("uz", "UZ")
                    val result = nativeTts?.setLanguage(uzLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        nativeTts?.setLanguage(Locale.getDefault())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native TTS init error: ${e.message}")
        }
    }

    /**
     * Speaks the Uzbek text through device speakers.
     * Uses Edge-TTS natural voice MP3 playback, falling back to Native TTS.
     */
    fun speakLive(text: String, voice: UzbekVoice, volume: Float = 1.0f) {
        if (text.isBlank() || volume <= 0.05f) return

        scope.launch {
            try {
                // Check cache first
                val cacheKey = "${voice.voiceId}_${text.hashCode()}"
                val cachedFile = audioCache[cacheKey]

                if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 100L) {
                    playAudioFile(cachedFile, volume)
                    return@launch
                }

                // Synthesize via Edge-TTS
                val edgeBytes = withTimeoutOrNull(2500L) {
                    synthesizeViaEdgeTts(text, voice.voiceId)
                }

                if (edgeBytes != null && edgeBytes.size > 100) {
                    val targetFile = File(context.cacheDir, "tts_${cacheKey}.mp3")
                    FileOutputStream(targetFile).use { it.write(edgeBytes) }
                    audioCache[cacheKey] = targetFile
                    playAudioFile(targetFile, volume)
                    return@launch
                }
            } catch (e: Exception) {
                Log.w(TAG, "Edge-TTS playback error: ${e.message}")
            }

            // Fallback to Native Android TTS
            withContext(Dispatchers.Main) {
                try {
                    nativeTts?.setPitch(if (voice == UzbekVoice.SARDOR) 0.85f else 1.15f)
                    nativeTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}")
                } catch (e: Exception) {
                    Log.w(TAG, "Native TTS speak error: ${e.message}")
                }
            }
        }
    }

    private suspend fun playAudioFile(file: File, volume: Float) = withContext(Dispatchers.Main) {
        try {
            stopSpeaking()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setVolume(volume, volume)
                prepare()
                start()
                setOnCompletionListener {
                    try {
                        it.release()
                    } catch (e: Exception) {
                        // ignore
                    }
                    if (mediaPlayer == it) mediaPlayer = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer play error: ${e.message}")
        }
    }

    fun stopSpeaking() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            // ignore
        }
        try {
            nativeTts?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Synthesizes audio to file for video rendering.
     */
    suspend fun synthesizeTextToFile(
        text: String,
        outputFile: File,
        voice: UzbekVoice = UzbekVoice.MADINA
    ): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false

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
                Log.w(TAG, "Failed writing file: ${e.message}")
            }
        }

        // Silent placeholder
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

            override fun onMessage(webSocket: WebSocket, textMsg: String) {
                if (textMsg.contains("Path:turn.end")) {
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
        stopSpeaking()
        nativeTts?.shutdown()
    }
}
