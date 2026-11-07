package com.snapreel.app.ui.viewer

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AspectRatioMode
import com.snapreel.app.player.PageState
import com.snapreel.app.ui.theme.*
import com.snapreel.app.util.thumbnail.videoThumbnailRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags and semantics for [VideoPage] (read by `VideoPageTest`). */
object VideoPageTags {
    const val ROOT = "video_page"
    const val PREVIEW = "video_page_preview"
    const val FAILURE = "video_page_failure"
    const val RETRY = "video_page_retry"

    /** True when the page fills the screen (crop/zoom), false when it fits (letterboxed). */
    val Fill = SemanticsPropertyKey<Boolean>("VideoFill")
}

private var SemanticsPropertyReceiver.videoFill by VideoPageTags.Fill

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    mediaItem: MediaItem,
    pageState: PageState?,
    onRetry: () -> Unit,
    isCurrentPage: Boolean,
    isPlaying: Boolean,
    isMuted: Boolean,
    showControls: Boolean,
    showFileName: Boolean,
    aspectRatioMode: AspectRatioMode = AspectRatioMode.SMART,
    onTap: () -> Unit,
    onDoubleTapLeft: () -> Unit = {},
    onDoubleTapRight: () -> Unit = {},
    onMuteToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSliderDragStart: () -> Unit,
    onSliderDragEnd: (Long) -> Unit,
    onControlsTimeout: () -> Unit,
    isFavorite: Boolean = false,
    onFavoriteToggle: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {}
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current

    // rememberUpdatedState ensures the gesture detector always calls the
    // LATEST version of these callbacks, even though pointerInput
    // only creates the detector once.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnFavoriteToggle by rememberUpdatedState(onFavoriteToggle)
    val currentIsFavorite by rememberUpdatedState(isFavorite)

    // Pinch-to-zoom & Pan state with smooth animation support
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var showHeartAnim by remember { mutableStateOf(false) }

    // Reset zoom when leaving page
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scaleAnim.snapTo(1f)
            offsetXAnim.snapTo(0f)
            offsetYAnim.snapTo(0f)
            onZoomChanged(false)
        }
    }

    LaunchedEffect(scaleAnim.value) {
        onZoomChanged(scaleAnim.value > 1.05f)
    }

    var progress by remember { mutableFloatStateOf(0f) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    val currentTime = remember { mutableLongStateOf(0L) }
    val totalTime = remember { mutableLongStateOf(0L) }

    // Smart Aspect Ratio & Manual Quick-Toggle State
    var manualZoomOverride by remember(mediaItem.uri) { mutableStateOf<Boolean?>(null) }
    // Orientation of the cached preview, used until this page's own player reports a video size.
    var previewLandscape by remember(mediaItem.uri) { mutableStateOf<Boolean?>(null) }
    val player = pageState?.player
    val failure = pageState?.failure
    val isFirstFrameRendered = pageState?.firstFrameRendered == true

    // Apply playback speed to player whenever it changes
    LaunchedEffect(player, playbackSpeed) {
        player?.setPlaybackSpeed(playbackSpeed)
    }
    // This page's own video size, then the preview's intrinsic size, then unknown (fill).
    val isLandscape = pageState?.videoSize
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width > it.height }
        ?: previewLandscape
        ?: false

    // Instant preview and blurred backdrop: the grid's cached thumbnail (memory or disk), never a decode.
    val context = androidx.compose.ui.platform.LocalContext.current
    val previewRequest = remember(mediaItem.uri, mediaItem.size, mediaItem.dateModified) {
        videoThumbnailRequest(context, mediaItem, cacheOnly = true, crossfade = false)
    }

    // Determine final zoom state:
    // SMART: vertical -> zoom/fill, horizontal/landscape -> fit
    val shouldZoom = manualZoomOverride ?: when (aspectRatioMode) {
        AspectRatioMode.SMART -> !isLandscape
        AspectRatioMode.FILL -> true
        AspectRatioMode.FIT -> false
    }

    // Auto-hide controls timer
    // Only ticks when controls are visible AND video is playing (State B)
    LaunchedEffect(showControls, isPlaying, isDraggingSlider) {
        if (showControls && isPlaying && !isDraggingSlider) {
            delay(3000)
            onControlsTimeout()
        }
    }

    // Update progress bar from this page's player position only while controls are visible
    // ponytail: 100ms polling only while showControls == true; switch to frame callback if always-on progress bar added
    LaunchedEffect(isCurrentPage, showControls, isDraggingSlider, player) {
        if (isCurrentPage && showControls && !isDraggingSlider && player != null) {
            while (true) {
                val p = player
                currentTime.longValue = p.currentPosition
                totalTime.longValue = p.duration.coerceAtLeast(0)
                if (totalTime.longValue > 0) {
                    progress = (currentTime.longValue.toFloat() / totalTime.longValue.toFloat()).coerceIn(0f, 1f)
                }
                delay(100)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(VideoPageTags.ROOT)
            .semantics { videoFill = shouldZoom }
            .onSizeChanged { containerSize = it }
            .pointerInput(containerSize) {
                detectTapGestures(
                    onPress = { offset ->
                        val screenWidthPx = containerSize.width.takeIf { it > 0 }?.toFloat() ?: (screenWidth * density)
                        val containerHeightPx = containerSize.height.takeIf { it > 0 }?.toFloat() ?: Float.MAX_VALUE
                        val bottomControlsHeightPx = 120 * density
                        val isEdge = (offset.x < screenWidthPx * 0.25f || offset.x > screenWidthPx * 0.75f) &&
                            offset.y < (containerHeightPx - bottomControlsHeightPx)
                        if (isEdge && scaleAnim.value <= 1.05f) {
                            try {
                                val released = kotlinx.coroutines.withTimeoutOrNull(250L) {
                                    tryAwaitRelease()
                                }
                                if (released == null) {
                                    isSpeedingUp = true
                                    playbackSpeed = 2f
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                    tryAwaitRelease()
                                }
                            } finally {
                                if (isSpeedingUp) {
                                    isSpeedingUp = false
                                    playbackSpeed = 1f
                                }
                            }
                        }
                    },
                    onTap = {
                        // Uses rememberUpdatedState — always fresh
                        currentOnTap()
                    },
                    onDoubleTap = { offset ->
                        if (scaleAnim.value > 1.05f) {
                            // Smoothly animate zoom back to 1x with spring/tween
                            scope.launch {
                                launch { scaleAnim.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
                                launch { offsetXAnim.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
                                launch { offsetYAnim.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
                            }
                        } else {
                            if (!currentIsFavorite) {
                                currentOnFavoriteToggle()
                            }
                            showHeartAnim = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                    }
                )
            }
            // Pinch-to-zoom & boundary-clamped pan gesture
            .pointerInput(containerSize) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // Multi-finger gesture for zoom/pan
                        if (event.changes.size > 1) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()

                            val newScale = (scaleAnim.value * zoom).coerceIn(1f, 5f)
                            val maxX = if (containerSize.width > 0) ((containerSize.width * (newScale - 1f)) / 2f).coerceAtLeast(0f) else 1000f
                            val maxY = if (containerSize.height > 0) ((containerSize.height * (newScale - 1f)) / 2f).coerceAtLeast(0f) else 1000f

                            scope.launch {
                                scaleAnim.snapTo(newScale)
                                if (newScale > 1f) {
                                    val newX = (offsetXAnim.value + pan.x).coerceIn(-maxX, maxX)
                                    val newY = (offsetYAnim.value + pan.y).coerceIn(-maxY, maxY)
                                    offsetXAnim.snapTo(newX)
                                    offsetYAnim.snapTo(newY)
                                } else {
                                    offsetXAnim.snapTo(0f)
                                    offsetYAnim.snapTo(0f)
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        // 1. Frosted Blurred Backdrop for Landscape/Fitted Videos
        if (!shouldZoom) {
            coil3.compose.AsyncImage(
                model = previewRequest,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp)
            )
            // Dark scrim over the blur so the centered video stands out
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            )
        }

        // 2 & 3. Video Player and Instant Preview with Zoom/Pan Transformation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value
                }
        ) {
            // Hardware-Accelerated Video Player: every composed video page has its own view,
            // bound to its pooled player, so a prepared neighbor already shows its first frame.
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = if (shouldZoom) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { playerView ->
                    val targetResizeMode = if (shouldZoom) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    if (playerView.resizeMode != targetResizeMode) {
                        playerView.resizeMode = targetResizeMode
                    }
                    if (playerView.player != player) {
                        playerView.player = player
                    }
                },
                onRelease = { playerView ->
                    playerView.player = null
                },
                modifier = Modifier.fillMaxSize()
            )

            // Instant Preview Thumbnail (Zero Black Screen: Stays visible until ExoPlayer renders first frame)
            AnimatedVisibility(
                visible = !isFirstFrameRendered,
                enter = fadeIn(tween(0)),
                exit = fadeOut(tween(200))
            ) {
                coil3.compose.AsyncImage(
                    model = previewRequest,
                    contentDescription = mediaItem.name,
                    contentScale = if (shouldZoom) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) previewLandscape = size.width > size.height
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(VideoPageTags.PREVIEW)
                )
            }
        }

        // Failure overlay: message + Retry. Swipes pass through to the pager.
        if (failure != null) {
            PlaybackFailureOverlay(
                message = failure.message,
                onRetry = onRetry,
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag(VideoPageTags.FAILURE),
                retryModifier = Modifier.testTag(VideoPageTags.RETRY)
            )
        }

        // Center play icon — visible when video is paused (State C)
        AnimatedVisibility(
            visible = !isPlaying && failure == null,
            enter = scaleIn(initialScale = 0.5f, animationSpec = tween(200)) + fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Double-tap heart animation (TikTok / Instagram style)
        PoppingHeart(
            visible = showHeartAnim,
            onAnimationEnd = { showHeartAnim = false },
            modifier = Modifier.align(Alignment.Center)
        )

        // 2X Speed indicator pill (TikTok style)
        AnimatedVisibility(
            visible = isSpeedingUp,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 18.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.65f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "2X Speed",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Bottom overlay gradient
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            )
        }

        // Bottom controls (slider, time, filename)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                // File name
                if (showFileName) {
                    Text(
                        text = mediaItem.name,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Time display
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentTime.longValue),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatTime(totalTime.longValue),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }

                // Progress Slider
                Slider(
                    value = progress,
                    onValueChange = { newValue ->
                        if (!isDraggingSlider) {
                            isDraggingSlider = true
                            onSliderDragStart()
                        }
                        progress = newValue
                    },
                    onValueChangeFinished = {
                        isDraggingSlider = false
                        if (totalTime.longValue > 0) {
                            val seekTime = (progress * totalTime.longValue).toLong()
                            onSliderDragEnd(seekTime)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Violet400,
                        activeTrackColor = Violet500,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
            }
        }

        // Right Action Column (Favorite, Speed, AspectRatio, Mute)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 76.dp, end = 12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Favorite Button (Heart)
                IconButton(
                    onClick = onFavoriteToggle,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove Favorite" else "Add Favorite",
                        tint = if (isFavorite) Color.Red else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 2. Playback Speed Button (0.5x, 1x, 1.25x, 1.5x, 2x)
                IconButton(
                    onClick = {
                        val next = when (playbackSpeed) {
                            1.0f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2.0f
                            2.0f -> 0.5f
                            else -> 1.0f
                        }
                        playbackSpeed = next
                        player?.setPlaybackSpeed(next)
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = if (playbackSpeed == 1.25f) "1.2x" else if (playbackSpeed == 1.5f) "1.5x" else if (playbackSpeed == 0.5f) "0.5x" else "${playbackSpeed.toInt()}x",
                        color = if (playbackSpeed != 1.0f) Violet400 else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 3. Aspect Ratio Quick-Toggle Button (Fit / Fill)
                IconButton(
                    onClick = { manualZoomOverride = !shouldZoom },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = if (shouldZoom) Icons.Filled.FitScreen else Icons.Filled.Fullscreen,
                        contentDescription = if (shouldZoom) "Fit to Screen" else "Fill Screen",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 4. Mute Button
                IconButton(
                    onClick = onMuteToggle,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
