package com.snapreel.app.ui.viewer

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.data.preferences.AspectRatioMode
import com.snapreel.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ImagePage(
    mediaItem: MediaItem,
    isCurrentPage: Boolean,
    showControls: Boolean,
    showFileName: Boolean,
    autoAdvance: Boolean,
    autoAdvanceDelay: Int,
    aspectRatioMode: AspectRatioMode = AspectRatioMode.SMART,
    onTap: () -> Unit,
    onAutoAdvance: () -> Unit,
    isFavorite: Boolean = false,
    onFavoriteToggle: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    // Pinch-to-zoom state with animation support
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Auto-advance countdown
    var countdown by remember { mutableIntStateOf(autoAdvanceDelay) }

    // Smart Aspect Ratio & Manual Quick-Toggle State
    var isLandscape by remember(mediaItem.uri) { mutableStateOf(false) }
    var manualZoomOverride by remember(mediaItem.uri) { mutableStateOf<Boolean?>(null) }

    val shouldZoom = manualZoomOverride ?: when (aspectRatioMode) {
        AspectRatioMode.SMART -> !isLandscape
        AspectRatioMode.FILL -> true
        AspectRatioMode.FIT -> false
    }

    // Use rememberUpdatedState so callbacks are always fresh
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnFavoriteToggle by rememberUpdatedState(onFavoriteToggle)
    val currentIsFavorite by rememberUpdatedState(isFavorite)
    var showHeartAnim by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current

    LaunchedEffect(isCurrentPage, autoAdvance) {
        if (isCurrentPage && autoAdvance) {
            countdown = autoAdvanceDelay
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            onAutoAdvance()
        }
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            // Tap gestures: single tap toggles controls, double tap smoothly resets zoom
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = {
                        if (scaleAnim.value > 1.05f) {
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
                        // Only process multi-finger gestures for zoom/pan
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
        // 1. Frosted Blurred Backdrop for Landscape/Fitted Photos
        if (!shouldZoom) {
            AsyncImage(
                model = mediaItem.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            )
        }

        // 2. Foreground Photo
        AsyncImage(
            model = mediaItem.uri,
            contentDescription = mediaItem.name,
            contentScale = if (shouldZoom) ContentScale.Crop else ContentScale.Fit,
            onSuccess = { state ->
                val img = state.result.image
                if (img.width > 0 && img.height > 0) {
                    isLandscape = img.width > img.height
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value
                }
        )

        // Double-tap heart animation (TikTok / Instagram style)
        PoppingHeart(
            visible = showHeartAnim,
            onAnimationEnd = { showHeartAnim = false },
            modifier = Modifier.align(Alignment.Center)
        )

        // Image type indicator (top-left area, below status bar) — hides in immersive
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 56.dp, start = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = Violet400,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Photo",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Right Actions (Favorite & Aspect Ratio)
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
                // Favorite button (Heart)
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

                // Aspect Ratio Quick-Toggle Button (Fit / Fill)
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
            }
        }

        // Bottom info — hides in immersive
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Column {
                if (showFileName) {
                    Text(
                        text = mediaItem.name,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Auto-advance indicator
                if (autoAdvance && isCurrentPage && countdown > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { countdown.toFloat() / autoAdvanceDelay.toFloat() },
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp)),
                            color = Violet400,
                            trackColor = Color.White.copy(alpha = 0.15f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${countdown}s",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
