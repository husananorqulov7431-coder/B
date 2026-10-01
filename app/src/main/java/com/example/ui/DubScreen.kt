package com.example.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.media3.ui.PlayerView
import com.example.model.ChunkItem
import com.example.model.DubProcessState
import com.example.model.SubtitleItem
import com.example.model.UzbekVoice
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.RedPrimary
import com.example.viewmodel.DubViewModel

@Composable
fun DubScreen(
    viewModel: DubViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val urlInput by viewModel.urlInput.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val processState by viewModel.processState.collectAsState()
    val overallProgress by viewModel.overallProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val chunks by viewModel.chunks.collectAsState()
    val activeSubtitle by viewModel.activeSubtitle.collectAsState()
    val allSubtitles by viewModel.allSubtitles.collectAsState()
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

        // 2. Video Player Frame with Live Subtitles Overlay
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                if (viewModel.videoPlayer != null && processState is DubProcessState.ReadyPlaying) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = viewModel.videoPlayer
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Empty / Loading Player Graphic
                    PlayerPlaceholder(processState, animatedProgress)
                }

                // Subtitle Overlay (Cinema style at the lower third)
                if (activeSubtitle != null) {
                    SubtitleOverlay(
                        subtitle = activeSubtitle!!,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }

        // 3. Progress Card (Percentage & Current Pipeline Step)
        item {
            ProgressSection(
                progress = animatedProgress,
                statusMessage = statusMessage,
                processState = processState
            )
        }

        // 4. 60-second Chunks Indicator (Progressive Rendering)
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
                onPasteClicked = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!clip.isNullOrBlank()) {
                        viewModel.onUrlChange(clip)
                    }
                },
                onStartClicked = {
                    viewModel.startDubbing()
                },
                isLoading = processState !is DubProcessState.Idle && processState !is DubProcessState.ReadyPlaying && processState !is DubProcessState.Error
            )
        }

        // 6. Volume Control Sliders
        item {
            VolumeControlSection(
                originalVolume = originalVolume,
                dubVolume = dubVolume,
                onOriginalVolumeChange = { viewModel.setOriginalVolume(it) },
                onDubVolumeChange = { viewModel.setDubVolume(it) }
            )
        }

        // 7. Subtitle Drawer toggle
        if (allSubtitles.isNotEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
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
                            if (showSubtitlesList) "Subtitrlar ro'yxatini yashirish" else "Barcha subtitrlarni ko'rish (${allSubtitles.size})"
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
            .padding(horizontal = 20.dp, vertical = 14.dp),
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
                    text = "O'zbekcha AI ovoz & Subtitr",
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
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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
                    text = "Edge-TTS Online",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB0B7C6)
                )
            }
        }
    }
}

@Composable
private fun PlayerPlaceholder(
    state: DubProcessState,
    progress: Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSurface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            when (state) {
                is DubProcessState.Idle -> {
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = Color(0xFF6C758A),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Video tayyor emas",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = "Pastdagi maydonga YouTube havolasini kiritib boshlang",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E99AF),
                        textAlign = TextAlign.Center
                    )
                }
                is DubProcessState.ReadyPlaying -> {
                    // Handled in AndroidView
                }
                is DubProcessState.Error -> {
                    Text(
                        text = "Xatolik!",
                        style = MaterialTheme.typography.titleMedium,
                        color = RedPrimary
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                else -> {
                    // Loading State
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = AmberAccent
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "1-bo'lak tayyorlanmoqda...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Text(
                        text = "1 daqiqa bo'lishi bilan video avtomatik boshlanadi",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E99AF)
                    )
                }
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
        color = Color(0xDC080B11),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFB800))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = subtitle.translatedText.ifBlank { subtitle.originalText },
                color = AmberAccent,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                textAlign = TextAlign.Center
            )
            if (subtitle.translatedText.isNotBlank() && subtitle.originalText != subtitle.translatedText) {
                Text(
                    text = subtitle.originalText,
                    color = Color(0xBBFFFFFF),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
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
    statusMessage: String,
    processState: DubProcessState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (processState) {
                            is DubProcessState.FetchingSubtitles -> Icons.Default.Subtitles
                            is DubProcessState.Translating -> Icons.Default.Translate
                            is DubProcessState.SynthesizingAudio -> Icons.Default.RecordVoiceOver
                            is DubProcessState.ReadyPlaying -> Icons.Default.PlayArrow
                            else -> Icons.Default.Headphones
                        },
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Jarayon holati",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                }

                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AmberAccent
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                ChunkPill(chunk = chunk)
            }
        }
    }
}

@Composable
private fun ChunkPill(chunk: ChunkItem) {
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isReady) Color(0xFF00E676) else AmberAccent)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "${chunk.index + 1}-bo'lak (${chunk.timeLabel})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = chunk.statusText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (isReady) Color(0xFF00E676) else Color(0xFFB0B7C6)
                )
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
    onPasteClicked: () -> Unit,
    onStartClicked: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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

            // Quick Samples Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickSampleChip(label = "TED Darsi", sampleUrl = "https://www.youtube.com/watch?v=M7lc1UVf-VE", onSelect = onUrlChange)
                QuickSampleChip(label = "Texnologiya", sampleUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ", onSelect = onUrlChange)
                QuickSampleChip(label = "Shorts", sampleUrl = "https://www.youtube.com/shorts/5O0v5a7wI2I", onSelect = onUrlChange)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Voice Selector
            Text(
                text = "Dublyaj Ovozi (Edge-TTS):",
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
                                "${voice.displayName} (${voice.gender} ovoz)",
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

            Spacer(modifier = Modifier.height(18.dp))

            // Main Action Button
            Button(
                onClick = onStartClicked,
                enabled = !isLoading && url.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_dubbing_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RedPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF4A1E24),
                    disabledContentColor = Color(0xFF8E99AF)
                )
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isLoading) "Dublyaj tayyorlanmoqda..." else "Boshlash (Dublyaj qilish)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
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
            color = Color(0xFF8E99AF),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
        Column(modifier = Modifier.padding(16.dp)) {
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

            Spacer(modifier = Modifier.height(10.dp))

            // Original Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Original Video:",
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
                    text = "O'zbekcha Dublyaj:",
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
