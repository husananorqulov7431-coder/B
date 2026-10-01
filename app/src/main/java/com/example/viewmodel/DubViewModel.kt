package com.example.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.model.ChunkItem
import com.example.model.DubProcessState
import com.example.model.SubtitleItem
import com.example.model.UzbekVoice
import com.example.service.EdgeTtsEngine
import com.example.service.VideoProcessor
import com.example.service.YouTubeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DubViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DubViewModel"
        const val DEFAULT_SAMPLE_VIDEO_URL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        const val DEFAULT_INPUT_URL = "https://www.youtube.com/watch?v=M7lc1UVf-VE"
    }

    private val youtubeService = YouTubeService()
    val ttsEngine = EdgeTtsEngine(application)
    private val videoProcessor = VideoProcessor(application, ttsEngine)

    // UI States
    private val _urlInput = MutableStateFlow(DEFAULT_INPUT_URL)
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _activeVideoId = MutableStateFlow("M7lc1UVf-VE")
    val activeVideoId: StateFlow<String> = _activeVideoId.asStateFlow()

    private val _selectedVoice = MutableStateFlow(UzbekVoice.MADINA)
    val selectedVoice: StateFlow<UzbekVoice> = _selectedVoice.asStateFlow()

    private val _processState = MutableStateFlow<DubProcessState>(DubProcessState.Idle)
    val processState: StateFlow<DubProcessState> = _processState.asStateFlow()

    private val _overallProgress = MutableStateFlow(0f)
    val overallProgress: StateFlow<Float> = _overallProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow("YouTube havolasini kiriting va 'Boshlash' tugmasini bosing")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _chunks = MutableStateFlow<List<ChunkItem>>(emptyList())
    val chunks: StateFlow<List<ChunkItem>> = _chunks.asStateFlow()

    private val _allSubtitles = MutableStateFlow<List<SubtitleItem>>(emptyList())
    val allSubtitles: StateFlow<List<SubtitleItem>> = _allSubtitles.asStateFlow()

    private val _activeSubtitle = MutableStateFlow<SubtitleItem?>(null)
    val activeSubtitle: StateFlow<SubtitleItem?> = _activeSubtitle.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _originalVideoVolume = MutableStateFlow(0.10f)
    val originalVideoVolume: StateFlow<Float> = _originalVideoVolume.asStateFlow()

    private val _dubVoiceVolume = MutableStateFlow(1.0f)
    val dubVoiceVolume: StateFlow<Float> = _dubVoiceVolume.asStateFlow()

    private val _useYouTubePlayer = MutableStateFlow(true)
    val useYouTubePlayer: StateFlow<Boolean> = _useYouTubePlayer.asStateFlow()

    // ExoPlayer for direct video playback
    var videoPlayer: ExoPlayer? = null
        private set

    private var dubbingJob: Job? = null
    private var syncTimerJob: Job? = null
    private var lastSpokenSubtitleStart: Long = -1L

    init {
        initExoPlayer()
    }

    private fun initExoPlayer() {
        val app = getApplication<Application>()
        videoPlayer = ExoPlayer.Builder(app).build().apply {
            volume = _originalVideoVolume.value
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }
            })
        }
    }

    fun onUrlChange(newUrl: String) {
        _urlInput.value = newUrl
    }

    fun onVoiceChange(voice: UzbekVoice) {
        _selectedVoice.value = voice
    }

    fun togglePlayerType() {
        _useYouTubePlayer.value = !_useYouTubePlayer.value
    }

    fun setOriginalVolume(volume: Float) {
        _originalVideoVolume.value = volume
        videoPlayer?.volume = volume
    }

    fun setDubVolume(volume: Float) {
        _dubVoiceVolume.value = volume
    }

    /**
     * Start dubbing and video playback immediately.
     */
    fun startDubbing() {
        dubbingJob?.cancel()
        syncTimerJob?.cancel()
        lastSpokenSubtitleStart = -1L

        val input = _urlInput.value
        val extractedId = YouTubeService.extractVideoId(input)
        _activeVideoId.value = extractedId

        // Immediate tactile response
        _processState.value = DubProcessState.FetchingSubtitles(extractedId, 0.20f)
        _overallProgress.value = 0.20f
        _statusMessage.value = "1. Video ochilmoqda va subtitrlar yuklanmoqda..."
        _isPlaying.value = true

        // Start ExoPlayer if in direct player mode
        videoPlayer?.let { player ->
            val videoItem = MediaItem.fromUri(Uri.parse(DEFAULT_SAMPLE_VIDEO_URL))
            player.setMediaItem(videoItem)
            player.prepare()
            player.playWhenReady = true
        }

        dubbingJob = viewModelScope.launch {
            try {
                // 1. Fetch Subtitles (fast with timeout)
                val subtitles = youtubeService.getSubtitles(extractedId)

                _overallProgress.value = 0.45f
                _processState.value = DubProcessState.Translating(0, subtitles.size, 0.45f)
                _statusMessage.value = "2. O'zbek tiliga tarjima qilinmoqda (${subtitles.size} ta jumla)..."

                // 2. Translate to Uzbek
                val rawLines = subtitles.map { it.originalText }
                val translatedLines = youtubeService.batchTranslateToUzbek(rawLines)

                val completeList = subtitles.mapIndexed { idx, sub ->
                    val trans = if (sub.translatedText.isNotBlank()) {
                        sub.translatedText
                    } else if (idx < translatedLines.size) {
                        translatedLines[idx]
                    } else {
                        sub.originalText
                    }
                    sub.copy(translatedText = trans)
                }
                _allSubtitles.value = completeList

                // 3. Partition into 60s chunks
                _overallProgress.value = 0.75f
                _processState.value = DubProcessState.SynthesizingAudio(0, 0.75f)
                _statusMessage.value = "3. 1-bo'lak (00:00 - 01:00) tayyorlanmoqda..."

                val initialChunks = videoProcessor.partitionIntoChunks(completeList)
                _chunks.value = initialChunks

                delay(400L) // smooth transition

                // 4. Mark Ready and Start Live Dubbing
                _overallProgress.value = 1.0f
                _processState.value = DubProcessState.ReadyPlaying(0, initialChunks.size)
                _statusMessage.value = "Dublyaj faol! 1-bo'lak ijro etilmoqda"

                // Update first chunk ready status
                if (initialChunks.isNotEmpty()) {
                    val updated = initialChunks.toMutableList()
                    updated[0] = updated[0].copy(isReady = true, statusText = "Ijroda", progress = 1f)
                    _chunks.value = updated
                }

                // Start synchronized timeline tracking
                startSyncTimer()

                // Progressively prepare subsequent chunks in background
                launchProgressiveChunks(initialChunks)

            } catch (e: Exception) {
                Log.e(TAG, "Dubbing error: ${e.message}", e)
                _processState.value = DubProcessState.Error("Xatolik: ${e.message}")
                _statusMessage.value = "Xatolik: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Stop or pause dubbing.
     */
    fun stopDubbing() {
        dubbingJob?.cancel()
        syncTimerJob?.cancel()
        ttsEngine.stopSpeaking()
        videoPlayer?.pause()
        _isPlaying.value = false
        _statusMessage.value = "Dublyaj to'xtatildi"
        _processState.value = DubProcessState.Idle
    }

    /**
     * Real-time timer that tracks elapsed milliseconds and triggers live subtitles & speech.
     */
    private fun startSyncTimer() {
        syncTimerJob?.cancel()
        var currentMs = 0L

        syncTimerJob = viewModelScope.launch {
            while (isActive) {
                if (_isPlaying.value) {
                    currentMs += 250L
                    _currentPositionMs.value = currentMs

                    val sub = videoProcessor.findSubtitleAt(_allSubtitles.value, currentMs)
                    _activeSubtitle.value = sub

                    // Trigger live Uzbek voice when a new subtitle begins
                    if (sub != null && sub.startMs != lastSpokenSubtitleStart) {
                        lastSpokenSubtitleStart = sub.startMs
                        val textToSpeak = sub.translatedText.ifBlank { sub.originalText }
                        if (_dubVoiceVolume.value > 0.05f) {
                            ttsEngine.speakLive(textToSpeak, _selectedVoice.value)
                        }
                    }
                }
                delay(250L)
            }
        }
    }

    /**
     * Updates playback position from external webview or seeker.
     */
    fun onSeekPosition(positionSec: Float) {
        val ms = (positionSec * 1000).toLong()
        _currentPositionMs.value = ms
        videoPlayer?.seekTo(ms)
        val sub = videoProcessor.findSubtitleAt(_allSubtitles.value, ms)
        _activeSubtitle.value = sub
    }

    private fun launchProgressiveChunks(chunksList: List<ChunkItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (i in 1 until chunksList.size) {
                delay(3000L) // emulate background preparation of each 60s segment
                withContext(Dispatchers.Main) {
                    val list = _chunks.value.toMutableList()
                    if (i < list.size) {
                        list[i] = list[i].copy(isReady = true, statusText = "Tayyor", progress = 1f)
                        _chunks.value = list
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        dubbingJob?.cancel()
        syncTimerJob?.cancel()
        ttsEngine.release()
        videoPlayer?.release()
    }
}
