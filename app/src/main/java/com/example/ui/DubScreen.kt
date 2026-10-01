package com.example.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DubScreen(
    viewModel: DubViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val urlInput by viewModel.urlInput.collectAsState()
    val activeVideoId by viewModel.activeVideoId.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val processState by viewModel.processState.collectAsState()
    val overallProgress by viewModel.overallProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val chunks by viewModel.chunks.collectAsState()
    val activeSubtitle by viewModel.activeSubtitle.collectAsState()
    val allSubtitles by viewModel.allSubtitles.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val useYouTubePlayer by viewModel.useYouTubePlayer.collectAsState()
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
        // 1. Top Header
        item {
            HeaderBar(
                useYouTube = useYouTubePlayer,
                onTogglePlayer = { viewModel.togglePlayerType() }
            )
        }

        // 2. Video Player (YouTube WebView or ExoPlayer) with Overlay Subtitles
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                if (isPlaying) {
                    if (useYouTubePlayer) {
                        // Real YouTube Embed Player
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    webChromeClient = WebChromeClient()
                                    webViewClient = WebViewClient()
                                    val html = """
                                        <!DOCTYPE html>
                                        <html>
                                        <head>
                                            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                                            <style>
                                                html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
                                                iframe { width:100%; height:100%; border:none; }
                                            </style>
                                        </head>
                                        <body>
                                            <iframe src="https://www.youtube.com/embed/$activeVideoId?autoplay=1&enablejsapi=1&playsinline=1"
                                                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                                                allowfullscreen>
                                            </iframe>
                                        </body>
                                        </html>
                                    """.trimIndent()
                                    loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
                                }
                            },
                            update = { webView ->
                                val html = """
                                    <!DOCTYPE html>
                                    <html>
                                    <head>
                                        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                                        <style>
                                            html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
                                            iframe { width:100%; height:100%; border:none; }
                                        </style>
                                    </head>
                                    <body>
                                        <iframe src="https://www.youtube.com/embed/$activeVideoId?autoplay=1&enablejsapi=1&playsinline=1"
                                            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                                            allowfullscreen>
                                        </iframe>
                                    </body>
                                    </html>
                                """.trimIndent()
                                webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // ExoPlayer Mode
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = viewModel.videoPlayer
                                    useController = true
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    // Placeholder when not playing
                    PlayerPlaceholder(onStart = { viewModel.startDubbing() })
                }

                // Subtitle Overlay (Floating Cinema Box)
                if (activeSubtitle != null) {
                    SubtitleOverlay(
                        subtitle = activeSubtitle!!,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }
        }

        // 3. Progress Card
        item {
            ProgressSection(
                progress = animatedProgress,
                statusMessage = statusMessage,
                processState = processState
            )
        }

        // 4. 60-Second Progressive Chunks Section
        if (chunks.isNotEmpty()) {
            item {
                ChunkQueueSection(chunks = chunks)
            }
        }

        // 5. Input, Controls & Instant Start Button
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

        // 6. Volume Mixer Section
        item {
            VolumeControlSection(
                originalVolume = originalVolume,
                dubVolume = dubVolume,
                onOriginalVolumeChange = { viewModel.setOriginalVolume(it) },
                onDubVolumeChange = { viewModel.setDubVolume(it) }
            )
        }

        // 7. Full Subtitles Transcript Viewer
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
                            if (showSubtitlesList) "Subtitrlar ro'yxatini yashirish" else "O'zbekcha tarjimalar ro'yxati (${allSubtitles.size} ta)"
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
private fun HeaderBar(
    useYouTube: Boolean,
    onTogglePlayer: () -> Unit
) {
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

        // Toggle Player Mode Pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.clickable { onTogglePlayer() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (useYouTube) "YouTube Rejim" else "ExoPlayer Rejim",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PlayerPlaceholder(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSurface)
            .clickable { onStart() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(RedPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Boshlash",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Dublyajni boshlash uchun bosing",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Text(
                text = "YouTube videosi darhol ochiladi va o'zbekcha ovoz yangraydi",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8E99AF),
                textAlign = TextAlign.Center
            )
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
                            text = "${chunk.index + 1}-bo'lak: ${chunk.statusText}",
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
                text = "YouTube havolasi",
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
                QuickSampleChip(label = "MrBeast", sampleUrl = "https://www.youtube.com/watch?v=0e3GPea1Tyg", onSelect = onUrlChange)
                QuickSampleChip(label = "Texnologiya", sampleUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ", onSelect = onUrlChange)
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

            // Main Action Button (Immediate Touch Reaction)
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

            // Original Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Original Video ovozi:",
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
