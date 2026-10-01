package com.example.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ChunkItem
import com.example.model.DubProcessState
import com.example.model.SubtitleItem
import com.example.model.UzbekVoice
import com.example.service.EdgeTtsEngine
import com.example.service.VideoProcessor
import com.example.service.YouTubeService
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DubViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DubViewModel"
        // Verified real YouTube video with subtitles for immediate clean testing
        const val DEFAULT_INPUT_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }

    private val youtubeService = YouTubeService()
    val ttsEngine = EdgeTtsEngine(application)
    private val videoProcessor = VideoProcessor(application, ttsEngine)

    // UI States
    private val _urlInput = MutableStateFlow(DEFAULT_INPUT_URL)
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _activeVideoId = MutableStateFlow("dQw4w9WgXcQ")
    val activeVideoId: StateFlow<String> = _activeVideoId.asStateFlow()

    private val _customDubText = MutableStateFlow("")
    val customDubText: StateFlow<String> = _customDubText.asStateFlow()

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

    private val _currentPositionSec = MutableStateFlow(0f)
    val currentPositionSec: StateFlow<Float> = _currentPositionSec.asStateFlow()

    private val _totalDurationSec = MutableStateFlow(0f)
    val totalDurationSec: StateFlow<Float> = _totalDurationSec.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _originalVideoVolume = MutableStateFlow(0.15f) // 15% ambient
    val originalVideoVolume: StateFlow<Float> = _originalVideoVolume.asStateFlow()

    private val _dubVoiceVolume = MutableStateFlow(1.0f) // 100% clear Uzbek dub
    val dubVoiceVolume: StateFlow<Float> = _dubVoiceVolume.asStateFlow()

    private var youTubePlayerInstance: YouTubePlayer? = null
    private var dubbingJob: Job? = null
    private var lastSpokenSubtitleStart: Long = -1L

    fun onYouTubePlayerReady(player: YouTubePlayer) {
        youTubePlayerInstance = player
        player.setVolume((_originalVideoVolume.value * 100).toInt())
        if (_isPlaying.value) {
            player.loadVideo(_activeVideoId.value, 0f)
        }
    }

    fun onCurrentSecond(second: Float) {
        _currentPositionSec.value = second
        val currentMs = (second * 1000f).toLong()

        // Check if a subtitle matches the current second
        val sub = videoProcessor.findSubtitleAt(_allSubtitles.value, currentMs)
        _activeSubtitle.value = sub

        if (sub != null && sub.startMs != lastSpokenSubtitleStart) {
            lastSpokenSubtitleStart = sub.startMs
            val textToSpeak = sub.translatedText.ifBlank { sub.originalText }
            ttsEngine.speakLive(textToSpeak, _selectedVoice.value, _dubVoiceVolume.value)
        }
    }

    fun onVideoDuration(duration: Float) {
        _totalDurationSec.value = duration
    }

    fun onUrlChange(newUrl: String) {
        _urlInput.value = newUrl
    }

    fun onCustomDubTextChange(text: String) {
        _customDubText.value = text
    }

    fun onVoiceChange(voice: UzbekVoice) {
        _selectedVoice.value = voice
    }

    fun setOriginalVolume(volume: Float) {
        _originalVideoVolume.value = volume
        youTubePlayerInstance?.setVolume((volume * 100).toInt())
    }

    fun setDubVolume(volume: Float) {
        _dubVoiceVolume.value = volume
    }

    /**
     * Start video playback and dubbing.
     */
    fun startDubbing() {
        val input = _urlInput.value.trim()
        val extractedId = YouTubeService.extractVideoId(input)

        if (extractedId == null) {
            _statusMessage.value = "⚠️ YouTube havolasi to'liq emas (ID kamida 11 ta belgi bo'lishi kerak). Masalan: https://youtu.be/dQw4w9WgXcQ"
            _processState.value = DubProcessState.Error("Noto'g'ri YouTube havolasi")
            _isPlaying.value = false
            return
        }

        dubbingJob?.cancel()
        ttsEngine.stopSpeaking()
        lastSpokenSubtitleStart = -1L

        _activeVideoId.value = extractedId
        _isPlaying.value = true
        _overallProgress.value = 0.20f
        _statusMessage.value = "Video ochilmoqda..."
        _processState.value = DubProcessState.FetchingSubtitles(extractedId, 0.20f)

        // Load video in YouTube player
        youTubePlayerInstance?.loadVideo(extractedId, 0f)
        youTubePlayerInstance?.setVolume((_originalVideoVolume.value * 100).toInt())

        dubbingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch real subtitles from YouTube
                val subtitles = youtubeService.getSubtitles(extractedId)

                if (subtitles.isEmpty()) {
                    _overallProgress.value = 1.0f
                    _allSubtitles.value = emptyList()
                    _chunks.value = emptyList()
                    _processState.value = DubProcessState.ReadyPlaying(0, 0)
                    _statusMessage.value = "Video o'ynayapti. Ushbu videoda subtitr topilmadi. Pastdagi maydonga matn yozib dublyaj qilishingiz mumkin."
                    return@launch
                }

                _overallProgress.value = 0.50f
                _processState.value = DubProcessState.Translating(0, subtitles.size, 0.50f)
                _statusMessage.value = "Subtitrlar o'zbek tiliga tarjima qilinmoqda (${subtitles.size} ta jumla)..."

                // 2. Translate real lines to Uzbek
                val rawLines = subtitles.map { it.originalText }
                val translatedLines = youtubeService.batchTranslateToUzbek(rawLines)

                val completeList = subtitles.mapIndexed { idx, sub ->
                    val trans = if (idx < translatedLines.size && translatedLines[idx].isNotBlank()) {
                        translatedLines[idx]
                    } else {
                        sub.originalText
                    }
                    sub.copy(translatedText = trans)
                }
                _allSubtitles.value = completeList

                // 3. Partition into 60s chunks
                _overallProgress.value = 0.85f
                val chunksList = videoProcessor.partitionIntoChunks(completeList)
                _chunks.value = chunksList

                _overallProgress.value = 1.0f
                _processState.value = DubProcessState.ReadyPlaying(0, chunksList.size)
                _statusMessage.value = "Dublyaj faol! ${completeList.size} ta jumla sinxronlashtirildi."

            } catch (e: Exception) {
                Log.e(TAG, "Dubbing error: ${e.message}", e)
                _statusMessage.value = "Xatolik: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Dubs custom user entered text (allows manual dubbing of any video).
     */
    fun dubCustomText() {
        val text = _customDubText.value.trim()
        if (text.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val uzbekText = youtubeService.translateSingleToUzbek(text)
            ttsEngine.speakLive(uzbekText, _selectedVoice.value, _dubVoiceVolume.value)
            _statusMessage.value = "O'zbekcha dublyaj yangramoqda: $uzbekText"
            _activeSubtitle.value = SubtitleItem(0L, 5000L, text, uzbekText)
        }
    }

    fun stopDubbing() {
        dubbingJob?.cancel()
        ttsEngine.stopSpeaking()
        youTubePlayerInstance?.pause()
        _isPlaying.value = false
        _statusMessage.value = "To'xtatildi"
        _processState.value = DubProcessState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        dubbingJob?.cancel()
        ttsEngine.release()
    }
}
