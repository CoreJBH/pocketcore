package com.jbitmstr.pocketcore.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.pocketcore.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Total time the launch overlay stays on screen, in milliseconds. */
private const val SPLASH_HOLD_MS = 1_100L

/** Time the mark takes to settle at its final size after the overlay appears. */
private const val SPLASH_ENTER_MS = 620L

/** Time the overlay takes to fade out at the end of the hold. */
private const val SPLASH_FADE_MS = 260L

/** The launch tile, and the corner radius baked into splash_icon.png. */
private val TileSize = 120.dp
private val TileRadius = 32.dp

/**
 * Reach of the cyan halo behind the tile, measured from the tile's centre.
 *
 * The halo is drawn by the whole overlay instead of inside the tile's box: a
 * Compose layer is clipped to the bounds of the composable that owns it, and
 * the fade's `graphicsLayer { alpha }` made the column such a layer, so a glow
 * painted in the 120dp box was cut off in a straight line where that box
 * ended. Here the gradient is fully transparent 150dp out from the centre
 * (90dp clear of the tile's edge), so it never meets a straight boundary.
 */
private val GlowRadius: Dp = 150.dp

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
 * the product name and a one-line description, easing in once and then fading
 * away. Nothing keeps moving while it is up: the mark settles at its final size
 * and stays there.
 */
@Composable
private fun SplashOverlay(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val enter = remember { Animatable(0.95f) }

    // Where the tile ends up, in the overlay's own coordinates, so the halo can
    // be drawn by the full-window layer rather than by the tile's 120dp box.
    // Both are resolved in the draw phase: onGloballyPositioned runs for a child
    // before its parent, so reading them here is what makes the order moot.
    var overlayCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var tileCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { alpha.animateTo(1f, tween(durationMillis = 320, easing = LinearOutSlowInEasing)) }
            launch { enter.animateTo(1f, tween(durationMillis = SPLASH_ENTER_MS.toInt(), easing = LinearOutSlowInEasing)) }
        }
        delay(SPLASH_HOLD_MS - SPLASH_ENTER_MS - SPLASH_FADE_MS)
        alpha.animateTo(0f, tween(durationMillis = SPLASH_FADE_MS.toInt(), easing = FastOutLinearInEasing))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(PocketColors.Background)
            .onGloballyPositioned { overlayCoordinates = it }
            .drawBehind {
                // A radial gradient on the window-sized node: its own layer is
                // the screen, so the falloff cannot be cut by a smaller box.
                val fade = alpha.value
                if (fade <= 0.001f) return@drawBehind
                val overlay = overlayCoordinates
                val tile = tileCoordinates
                val centre = if (overlay != null && tile != null && overlay.isAttached && tile.isAttached) {
                    overlay.localPositionOf(tile, Offset(tile.size.width / 2f, tile.size.height / 2f))
                } else {
                    // Before the first placement has been reported: keep the
                    // halo on the window's centre rather than losing it.
                    center
                }
                val radius = GlowRadius.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        0.00f to PocketColors.Accent.copy(alpha = 0.20f * fade),
                        0.30f to PocketColors.Accent.copy(alpha = 0.175f * fade),
                        0.46f to PocketColors.Accent.copy(alpha = 0.108f * fade),
                        0.62f to PocketColors.Accent.copy(alpha = 0.046f * fade),
                        0.82f to PocketColors.Accent.copy(alpha = 0.012f * fade),
                        1.00f to PocketColors.Accent.copy(alpha = 0f),
                        center = centre,
                        radius = radius
                    ),
                    radius = radius,
                    center = centre
                )
            }
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
                    .size(TileSize)
                    .onGloballyPositioned { tileCoordinates = it }
                    .graphicsLayer {
                        // One settle from 95% to full size, then still.
                        scaleX = enter.value
                        scaleY = enter.value
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
                        .clip(RoundedCornerShape(TileRadius))
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
