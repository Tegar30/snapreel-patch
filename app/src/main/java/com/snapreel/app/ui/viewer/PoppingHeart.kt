package com.snapreel.app.ui.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.snapreel.app.ui.theme.HeartRed
import kotlinx.coroutines.delay

/**
 * TikTok / Instagram style burst heart animation triggered on double-tap to like.
 */
@Composable
fun PoppingHeart(
    visible: Boolean,
    onAnimationEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        scale.snapTo(0f)
        alpha.snapTo(1f)
        scale.animateTo(1.35f, tween(160, easing = FastOutSlowInEasing))
        scale.animateTo(1.0f, tween(100, easing = LinearEasing))
        delay(250)
        alpha.animateTo(0f, tween(200, easing = LinearEasing))
        onAnimationEnd()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = HeartRed,
            modifier = Modifier
                .size(110.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
        )
    }
}
