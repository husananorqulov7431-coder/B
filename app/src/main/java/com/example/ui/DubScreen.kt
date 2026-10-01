package com.example.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.ChunkItem
import com.example.model.SubtitleItem
import com.example.model.UzbekVoice
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.RedPrimary
import com.example.viewmodel.DubViewModel
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

@Composable
fun DubScreen(
    viewModel: DubViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val urlInput by viewModel.urlInput.collectAsState()
    val activeVideoId by viewModel.activeVideoId.collectAsState()
    val customDubText by viewModel.customDubText.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val overallProgress by viewModel.overallProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val chunks by viewModel.chunks.collectAsState()
    val activeSubtitle by viewModel.activeSubtitle.collectAsState()
    val allSubtitles by viewModel.allSubtitles.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentSec by viewModel.currentPositionSec.collectAsState()
    val durationSec by viewModel.totalDurationSec.collectAsState()
    val originalVolume by viewModel.originalVideoVolume.collectAsState()
    val dubVolume by viewModel.dubVoiceVolume.collectAsState()

    var showSubtitlesList by remember { mutableStateOf(false) }

    val animatedProgress by animateFloatAsState(
        targetValue = overallProgress,
        label = "progress"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // 1. Top Header Bar
        item {
            HeaderBar()
        }

        // 2. Real YouTube Video Player with Live Subtitles Overlay
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                // Official Native YouTube Player View
                AndroidView(
                    factory = { ctx ->
                        YouTubePlayerView(ctx).apply {
                            enableAutomaticInitialization = false
                            lifecycleOwner.lifecycle.addObserver(this)
                            initialize(object : AbstractYouTubePlayerListener() {
                                override fun onReady(youTubePlayer: YouTubePlayer) {
                                    viewModel.onYouTubePlayerReady(youTubePlayer)
                                }

                                override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                    viewModel.onCurrentSecond(second)
                                }

                                override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                                    viewModel.onVideoDuration(duration)
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Subtitle Overlay (Floating Cinema Box)
                if (activeSubtitle != null) {
                    SubtitleOverlay(
                        subtitle = activeSubtitle!!,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // Real Time Indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val curM = (currentSec / 60).toInt()
                val curS = (currentSec % 60).toInt()
                val durM = (durationSec / 60).toInt()
                val durS = (durationSec % 60).toInt()

                Text(
                    text = String.format("%02d:%02d / %02d:%02d", curM, curS, durM, durS),
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberAccent,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (isPlaying) "Video Ijro etilmoqda" else "Kutilmoqda",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPlaying) Color(0xFF00E676) else Color(0xFF8E99AF)
                )
            }
        }

        // 3. Status & Progress Section
        item {
            ProgressSection(
                progress = animatedProgress,
                statusMessage = statusMessage
            )
        }

        // 4. 60-second chunks (if available)
        if (chunks.isNotEmpty()) {
            item {
                ChunkQueueSection(chunks = chunks)
            }
        }

        // 5. YouTube URL Input & Voice Selector
        item {
            InputSection(
                url = urlInput,
                onUrlChange = { viewModel.onUrlChange(it) },
                selectedVoice = selectedVoice,
                onVoiceChange = { viewModel.onVoiceChange(it) },
                isPlaying = isPlaying,
                onPasteClicked = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!clip.isNullOrBlank()) {
                        viewModel.onUrlChange(clip.trim())
                    }
                },
                onStartClicked = {
                    if (isPlaying) {
                        viewModel.stopDubbing()
                    } else {
                        viewModel.startDubbing()
                    }
                }
            )
        }

        // 6. Custom Dubbing Text Input (Instant live dubbing of any speech)
        item {
            CustomDubSection(
                text = customDubText,
                onTextChange = { viewModel.onCustomDubTextChange(it) },
                onDubClicked = { viewModel.dubCustomText() }
            )
        }

        // 7. Volume Mixing Section (YouTube Video vs Uzbek TTS Voice)
        item {
            VolumeControlSection(
                originalVolume = originalVolume,
                dubVolume = dubVolume,
                onOriginalVolumeChange = { viewModel.setOriginalVolume(it) },
                onDubVolumeChange = { viewModel.setDubVolume(it) }
            )
        }

        // 8. Transcript list toggle
        if (allSubtitles.isNotEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Button(
                        onClick = { showSubtitlesList = !showSubtitlesList },
                        modifier = Modifier.fillMaxWidth().testTag("toggle_transcript_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkSurfaceVariant,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (showSubtitlesList) "Subtitrlarni yashirish" else "Olingan subtitrlar ro'yxati (${allSubtitles.size} ta)"
                        )
                    }
                }
            }

            if (showSubtitlesList) {
                items(allSubtitles) { sub ->
                    SubtitleItemCard(sub = sub)
                }
            }
        }
    }
}

@Composable
private fun HeaderBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(RedPrimary, Color(0xFF8E0812)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "YouTube Dublyaj",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = "O'zbekcha Ovoz & Subtitr",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberAccent
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E676))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Faol",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SubtitleOverlay(
    subtitle: SubtitleItem,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xEB0A0D14),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x55FFB800))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = subtitle.translatedText.ifBlank { subtitle.originalText },
                color = AmberAccent,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                textAlign = TextAlign.Center
            )
            if (subtitle.translatedText.isNotBlank() && subtitle.originalText != subtitle.translatedText) {
                Text(
                    text = subtitle.originalText,
                    color = Color(0xAAFFFFFF),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProgressSection(
    progress: Float,
    statusMessage: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Dublyaj holati",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )

                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AmberAccent
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = RedPrimary,
                trackColor = DarkSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0B7C6)
            )
        }
    }
}

@Composable
private fun ChunkQueueSection(chunks: List<ChunkItem>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "60 soniyalik bo'laklar (Progressive Rendering):",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF8E99AF),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chunks) { chunk ->
                val isReady = chunk.isReady
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isReady) Color(0xFF1B2C1F) else DarkSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isReady) Color(0xFF00E676) else DarkBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isReady) Color(0xFF00E676) else AmberAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${chunk.index + 1}-bo'lak (${chunk.timeLabel})",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputSection(
    url: String,
    onUrlChange: (String) -> Unit,
    selectedVoice: UzbekVoice,
    onVoiceChange: (UzbekVoice) -> Unit,
    isPlaying: Boolean,
    onPasteClicked: () -> Unit,
    onStartClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "YouTube Video havolasi",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("youtube_url_input"),
                placeholder = {
                    Text(
                        "https://www.youtube.com/watch?v=...",
                        color = Color(0xFF6C758A)
                    )
                },
                trailingIcon = {
                    Row {
                        if (url.isNotEmpty()) {
                            IconButton(onClick = { onUrlChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Tozalash", tint = Color(0xFF8E99AF))
                            }
                        }
                        IconButton(onClick = onPasteClicked) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Joylashtirish", tint = AmberAccent)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RedPrimary,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant
                )
            )

            // Working Real Sample Videos
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickSampleChip(label = "Rick Astley", sampleUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ", onSelect = onUrlChange)
                QuickSampleChip(label = "Steve Jobs", sampleUrl = "https://www.youtube.com/watch?v=UF8uR6Z6KLc", onSelect = onUrlChange)
                QuickSampleChip(label = "MrBeast", sampleUrl = "https://www.youtube.com/watch?v=0e3GPea1Tyg", onSelect = onUrlChange)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Voice Selector
            Text(
                text = "Dublyaj Ovozi:",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                UzbekVoice.values().forEach { voice ->
                    val isSelected = selectedVoice == voice
                    FilterChip(
                        selected = isSelected,
                        onClick = { onVoiceChange(voice) },
                        label = {
                            Text(
                                "${voice.displayName} (${voice.gender})",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RedPrimary,
                            selectedLabelColor = Color.White,
                            containerColor = DarkSurfaceVariant,
                            labelColor = Color(0xFFB0B7C6)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = DarkBorder,
                            selectedBorderColor = RedPrimary,
                            enabled = true,
                            selected = isSelected
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Action Button
            Button(
                onClick = onStartClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_dubbing_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) Color(0xFF8E0812) else RedPrimary,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPlaying) "Dublyajni to'xtatish" else "Boshlash (Dublyaj qilish)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun CustomDubSection(
    text: String,
    onTextChange: (String) -> Unit,
    onDubClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "O'zingiz xohlagan matnni dublyaj qilish (Ixtiyoriy):",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Inglizcha yoki o'zbekcha gap yozing...",
                            color = Color(0xFF6C758A),
                            fontSize = 13.sp
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberAccent,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onDubClicked,
                    enabled = text.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (text.isNotBlank()) AmberAccent else DarkSurfaceVariant)
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Ovoz berish",
                        tint = if (text.isNotBlank()) Color.Black else Color(0xFF8E99AF)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickSampleChip(
    label: String,
    sampleUrl: String,
    onSelect: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.clickable { onSelect(sampleUrl) }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB0B7C6),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun VolumeControlSection(
    originalVolume: Float,
    dubVolume: Float,
    onOriginalVolumeChange: (Float) -> Unit,
    onDubVolumeChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Ovoz balandligi mikseri",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Original YouTube Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Original Video ovozi (0% = to'liq o'chirish):",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E99AF)
                )
                Text(
                    text = "${(originalVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
            Slider(
                value = originalVolume,
                onValueChange = onOriginalVolumeChange,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF8E99AF),
                    inactiveTrackColor = DarkSurfaceVariant
                )
            )

            // Dubbed Voice Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "O'zbekcha Dublyaj ovozi:",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberAccent
                )
                Text(
                    text = "${(dubVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberAccent
                )
            }
            Slider(
                value = dubVolume,
                onValueChange = onDubVolumeChange,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = AmberAccent,
                    activeTrackColor = AmberAccent,
                    inactiveTrackColor = DarkSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun SubtitleItemCard(sub: SubtitleItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black
            ) {
                val sec = sub.startMs / 1000
                Text(
                    text = String.format("%02d:%02d", sec / 60, sec % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberAccent,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sub.translatedText.ifBlank { sub.originalText },
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = Color.White
                )
                if (sub.translatedText.isNotBlank()) {
                    Text(
                        text = sub.originalText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF6C758A)
                    )
                }
            }
        }
    }
}
