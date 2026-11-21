package com.jbitmstr.pocketcore.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.pocketcore.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Total time the launch overlay stays on screen, in milliseconds. */
private const val SPLASH_HOLD_MS = 1_100L

/**
 * Application root: the node UI, with the animated launch screen drawn above it
 * for the first second of the process. The window background is already black,
 * so there is no white frame before the overlay appears.
 */
@Composable
fun PocketCoreRoot() {
    // Saved across configuration changes so a rotation does not replay the splash.
    var showSplash by rememberSaveable { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(PocketColors.Background)) {
        PocketCoreApp()
        if (showSplash) {
            SplashOverlay(onFinished = { showSplash = false })
        }
    }
}

/**
 * Midnight-navy launch screen: the app's hexagon coin mark on the "#050912" canvas,
 * the product name and a one-line description, easing in and gently pulsing
 * before it fades away.
 */
@Composable
private fun SplashOverlay(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val enter = remember { Animatable(0.86f) }
    val pulse = rememberInfiniteTransition(label = "splash-pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splash-pulse-scale"
    )

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { alpha.animateTo(1f, tween(durationMillis = 300, easing = LinearOutSlowInEasing)) }
            launch { enter.animateTo(1f, tween(durationMillis = 620, easing = LinearOutSlowInEasing)) }
        }
        delay(SPLASH_HOLD_MS - 620L - 240L)
        alpha.animateTo(0f, tween(durationMillis = 240, easing = FastOutLinearInEasing))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(PocketColors.Background)
            // Swallow touches so a tap during the splash cannot reach a button below.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    // 120dp puts the mark at 88dp on a 360dp-wide phone; the
                    // raster tile's own mark fills 73% of the box.
                    .size(120.dp)
                    .graphicsLayer {
                        val s = enter.value * pulseScale
                        scaleX = s
                        scaleY = s
                    }
                    .drawBehind {
                        // Cyan halo under the mark; it breathes with the pulse.
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    PocketColors.Accent.copy(alpha = 0.22f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = size.minDimension * 0.82f
                            ),
                            radius = size.minDimension * 0.82f,
                            center = Offset(center.x, center.y)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // A plain raster drawable: painterResource() cannot decode the
                // <adaptive-icon> XML behind R.mipmap.ic_launcher on API 26+.
                Image(
                    painter = painterResource(R.drawable.splash_icon),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        // Matches the radius baked into splash_icon.png, so the
                        // raster edge and the Compose clip land on the same curve.
                        .clip(RoundedCornerShape(32.dp))
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                text = "POCKETCORE",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 5.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Your mobile Bitcoin full node",
                color = PocketColors.Muted,
                fontSize = 12.sp,
                letterSpacing = 0.4.sp
            )
        }
    }
}
