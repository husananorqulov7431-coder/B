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
        // High quality fast-loading MP4 stream for video playback synchronization
        const val DEFAULT_SAMPLE_VIDEO_URL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        const val DEFAULT_INPUT_URL = "https://www.youtube.com/watch?v=M7lc1UVf-VE"
    }

    private val youtubeService = YouTubeService()
    private val ttsEngine = EdgeTtsEngine(application)
    private val videoProcessor = VideoProcessor(application, ttsEngine)

    // UI States
    private val _urlInput = MutableStateFlow(DEFAULT_INPUT_URL)
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

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

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _originalVideoVolume = MutableStateFlow(0.10f) // 10% background ambient
    val originalVideoVolume: StateFlow<Float> = _originalVideoVolume.asStateFlow()

    private val _dubVoiceVolume = MutableStateFlow(1.0f) // 100% clear Uzbek dubbing
    val dubVoiceVolume: StateFlow<Float> = _dubVoiceVolume.asStateFlow()

    // ExoPlayer instances: Video player + Dubbed Audio player
    var videoPlayer: ExoPlayer? = null
        private set
    private var dubAudioPlayer: ExoPlayer? = null

    private var dubbingJob: Job? = null
    private var progressTrackingJob: Job? = null

    init {
        initPlayers()
    }

    private fun initPlayers() {
        val app = getApplication<Application>()
        videoPlayer = ExoPlayer.Builder(app).build().apply {
            volume = _originalVideoVolume.value
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                    if (playing) {
                        dubAudioPlayer?.play()
                    } else {
                        dubAudioPlayer?.pause()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _totalDurationMs.value = duration.coerceAtLeast(0L)
                    }
                }
            })
        }

        dubAudioPlayer = ExoPlayer.Builder(app).build().apply {
            volume = _dubVoiceVolume.value
        }

        startPositionTracker()
    }

    private fun startPositionTracker() {
        progressTrackingJob?.cancel()
        progressTrackingJob = viewModelScope.launch {
            while (isActive) {
                videoPlayer?.let { player ->
                    val pos = player.currentPosition
                    _currentPositionMs.value = pos
                    val sub = videoProcessor.findSubtitleAt(_allSubtitles.value, pos)
                    _activeSubtitle.value = sub
                }
                delay(200L)
            }
        }
    }

    fun onUrlChange(newUrl: String) {
        _urlInput.value = newUrl
    }

    fun onVoiceChange(voice: UzbekVoice) {
        _selectedVoice.value = voice
    }

    fun setOriginalVolume(volume: Float) {
        _originalVideoVolume.value = volume
        videoPlayer?.volume = volume
    }

    fun setDubVolume(volume: Float) {
        _dubVoiceVolume.value = volume
        dubAudioPlayer?.volume = volume
    }

    fun togglePlayPause() {
        videoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                dubAudioPlayer?.pause()
            } else {
                player.play()
                dubAudioPlayer?.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        videoPlayer?.seekTo(positionMs)
        dubAudioPlayer?.seekTo(positionMs)
    }

    /**
     * Start the complete dubbing and Progressive Rendering pipeline.
     */
    fun startDubbing() {
        dubbingJob?.cancel()
        dubbingJob = viewModelScope.launch {
            try {
                val input = _urlInput.value
                val videoId = YouTubeService.extractVideoId(input) ?: "M7lc1UVf-VE"

                // 1. Fetch Subtitles
                _processState.value = DubProcessState.FetchingSubtitles(videoId, 0.15f)
                _overallProgress.value = 0.15f
                _statusMessage.value = "1. YouTube'dan video va subtitrlar olinmoqda..."

                val subtitles = youtubeService.getSubtitles(videoId)
                if (subtitles.isEmpty()) {
                    _processState.value = DubProcessState.Error("Subtitrlar topilmadi.")
                    _statusMessage.value = "Xatolik: Subtitrlarni olib bo'lmadi."
                    return@launch
                }

                // 2. Translate Subtitles to Uzbek
                _processState.value = DubProcessState.Translating(0, subtitles.size, 0.35f)
                _overallProgress.value = 0.35f
                _statusMessage.value = "2. O'zbek tiliga tarjima qilinmoqda (${subtitles.size} ta jumla)..."

                val translatedList = mutableListOf<SubtitleItem>()
                for (i in subtitles.indices) {
                    val original = subtitles[i]
                    val uzText = if (original.translatedText.isNotBlank()) {
                        original.translatedText
                    } else {
                        youtubeService.translateToUzbek(original.originalText)
                    }
                    translatedList.add(original.copy(translatedText = uzText))

                    val transProgress = 0.35f + (i.toFloat() / subtitles.size.toFloat()) * 0.25f
                    _overallProgress.value = transProgress
                    _processState.value = DubProcessState.Translating(i + 1, subtitles.size, transProgress)
                    _statusMessage.value = "2. Tarjima qilinmoqda: ${i + 1}/${subtitles.size}"
                }
                _allSubtitles.value = translatedList

                // 3. Partition into 60-second chunks
                val initialChunks = videoProcessor.partitionIntoChunks(translatedList)
                _chunks.value = initialChunks

                if (initialChunks.isEmpty()) {
                    _processState.value = DubProcessState.Error("Bo'laklar hosil qilinmadi.")
                    return@launch
                }

                // 4. Synthesize Chunk 0 (First 60 seconds)
                _processState.value = DubProcessState.SynthesizingAudio(0, 0.65f)
                _overallProgress.value = 0.65f
                _statusMessage.value = "3. 1-bo'lak (00:00 - 01:00) uchun ${selectedVoice.value.displayName} ovozida audio yaratilmoqda..."

                val readyChunk0 = videoProcessor.processChunk(
                    chunk = initialChunks[0],
                    voice = _selectedVoice.value,
                    onProgress = { p, msg ->
                        _overallProgress.value = 0.65f + (p * 0.35f)
                        _statusMessage.value = msg
                    }
                )

                val updatedChunks = initialChunks.toMutableList()
                updatedChunks[0] = readyChunk0
                _chunks.value = updatedChunks

                // 5. Start playing immediately! (1-daqiqa tayyor bo'lishi bilanoq)
                _overallProgress.value = 1.0f
                _processState.value = DubProcessState.ReadyPlaying(0, initialChunks.size)
                _statusMessage.value = "1-bo'lak tayyor! Ijro etilmoqda..."

                startPlaybackWithChunk(readyChunk0)

                // 6. Background Coroutine: Prepare remaining chunks progressively
                launchBackgroundChunks(updatedChunks)

            } catch (e: Exception) {
                Log.e(TAG, "Dubbing pipeline error: ${e.message}", e)
                _processState.value = DubProcessState.Error("Xatolik yuz berdi: ${e.message}")
                _statusMessage.value = "Xatolik: ${e.localizedMessage}"
            }
        }
    }

    private fun startPlaybackWithChunk(chunk: ChunkItem) {
        val app = getApplication<Application>()
        // Video stream
        val videoItem = MediaItem.fromUri(Uri.parse(DEFAULT_SAMPLE_VIDEO_URL))
        videoPlayer?.setMediaItem(videoItem)
        videoPlayer?.prepare()
        videoPlayer?.playWhenReady = true

        // Dubbed Audio stream
        chunk.audioFile?.let { audioFile ->
            if (audioFile.exists()) {
                val audioItem = MediaItem.fromUri(Uri.fromFile(audioFile))
                dubAudioPlayer?.setMediaItem(audioItem)
                dubAudioPlayer?.prepare()
                dubAudioPlayer?.playWhenReady = true
            }
        }
    }

    /**
     * Progressive background processing for chunks 1..N
     */
    private fun launchBackgroundChunks(currentChunks: MutableList<ChunkItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (idx in 1 until currentChunks.size) {
                val chunk = currentChunks[idx]
                withContext(Dispatchers.Main) {
                    val list = _chunks.value.toMutableList()
                    list[idx] = list[idx].copy(statusText = "Tayyorlanmoqda...")
                    _chunks.value = list
                }

                val ready = videoProcessor.processChunk(
                    chunk = chunk,
                    voice = _selectedVoice.value,
                    onProgress = { p, msg ->
                        // update chunk progress
                    }
                )

                withContext(Dispatchers.Main) {
                    val list = _chunks.value.toMutableList()
                    list[idx] = ready
                    _chunks.value = list
                    Log.d(TAG, "Background Chunk $idx finished!")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressTrackingJob?.cancel()
        dubbingJob?.cancel()
        videoPlayer?.release()
        dubAudioPlayer?.release()
        ttsEngine.release()
    }
}
