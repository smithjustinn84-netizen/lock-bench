package com.lockpicking.bench.ui.bench.juice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockpicking.bench.R
import com.lockpicking.bench.ui.theme.BenchColors
import com.lockpicking.bench.ui.theme.BenchType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the banner holds once it has landed, and how long it takes to lift away. */
private const val BANNER_HOLD_MILLIS = 1300L
private const val BANNER_LEAVE_MILLIS = 380

/** The banner slams in from this size. */
private const val BANNER_FROM_SCALE = 2.4f
private const val BANNER_SIZE = 0.8f

/** Where the banner sits, as a share of the screen's height. */
private const val BANNER_HEIGHT_SHARE = 0.86f

/** A dark band behind the banner, so it reads over the bench top. */
private val BannerBand = Brush.horizontalGradient(
  0f to Color.Transparent,
  0.25f to Color(0xB3000000),
  0.75f to Color(0xB3000000),
  1f to Color.Transparent,
)

/** How far up the scene's frame the lock sits, as a share of its height. */
private const val LOCK_HEIGHT_SHARE = 0.42f

/** Where the edge glow starts, as a share of the screen's half-diagonal. */
private const val FLASH_INNER = 0.55f
private const val FLASH_RADIUS = 0.75f

private const val CONFETTI_COUNT = 90
private const val OPEN_SPARK_COUNT = 40
private const val SPREAD_WIDE = 1.1f
private const val CONFETTI_LIFE = 2.2f
private const val OPEN_SPARK_LIFE = 0.8f

private val ConfettiColors = listOf(BenchColors.Brass, BenchColors.BrassLight, BenchColors.Set, BenchColors.Text, BenchColors.Bronze)
private val OpenSparkColors = listOf(BenchColors.BrassLight, Color.White, BenchColors.Brass)

/**
 * Everything the HUD throws over the bench: the coloured edge flash, the confetti and the
 * banner when the shackle lets go. It takes no touches. [sceneInsets] frame the lock between
 * the thumb zones, which is where the burst comes from.
 */
@Composable
fun JuiceOverlay(
  juice: BenchJuice,
  sceneInsets: PaddingValues,
  modifier: Modifier = Modifier,
  /** Off in a blind round, whose results card is the payoff and sits where the banner would. */
  showBanner: Boolean = true,
) {
  val confetti = rememberSparks()
  val direction = LocalLayoutDirection.current
  val density = LocalDensity.current
  BoxWithConstraints(modifier.fillMaxSize()) {
    val left = sceneInsets.calculateStartPadding(direction)
    val right = maxWidth - sceneInsets.calculateEndPadding(direction)
    val top = sceneInsets.calculateTopPadding()
    val lockX = (left + right) / 2f
    val lockY = top + (maxHeight - top) * LOCK_HEIGHT_SHARE
    // Under the lock, on the bench, so the shackle swinging open stays in sight.
    val bannerY = maxHeight * BANNER_HEIGHT_SHARE
    EdgeFlash(juice)

    val opened = juice.opened.intValue
    var seen by remember { mutableIntStateOf(opened) }
    val scale = remember { Animatable(BANNER_FROM_SCALE) }
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(opened) {
      // Only a release that happens while shown: not one replayed by a rebuilt screen.
      if (opened == seen) return@LaunchedEffect
      seen = opened
      with(density) {
        val cx = lockX.toPx()
        val cy = lockY.toPx()
        confetti.burst(
          cx, cy, CONFETTI_COUNT, ConfettiColors, speed = 900.dp.toPx(), sizePx = 9.dp.toPx(),
          kind = SparkKind.Confetti, spread = SPREAD_WIDE, lifeSeconds = CONFETTI_LIFE, gravityPx = 1400.dp.toPx(),
        )
        confetti.burst(
          cx,
          cy,
          OPEN_SPARK_COUNT,
          OpenSparkColors,
          speed = 700.dp.toPx(),
          sizePx = 3.dp.toPx(),
          lifeSeconds = OPEN_SPARK_LIFE,
          gravityPx = 1400.dp.toPx(),
        )
      }
      rise.snapTo(0f)
      scale.snapTo(BANNER_FROM_SCALE)
      alpha.snapTo(0f)
      launch { alpha.animateTo(1f, tween(durationMillis = 120)) }
      scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
      delay(BANNER_HOLD_MILLIS)
      launch { rise.animateTo(1f, tween(BANNER_LEAVE_MILLIS, easing = FastOutLinearInEasing)) }
      alpha.animateTo(0f, tween(BANNER_LEAVE_MILLIS, easing = FastOutLinearInEasing))
    }

    Spacer(
      Modifier.fillMaxSize().drawBehind {
        confetti.frame.intValue
        confetti.draw(this)
      },
    )
    if (!showBanner) return@BoxWithConstraints
    Box(
      Modifier
        .offset(x = lockX - maxWidth / 2f, y = bannerY - maxHeight / 2f)
        .align(Alignment.Center)
        .graphicsLayer {
          this.alpha = alpha.value
          scaleX = scale.value
          scaleY = scale.value
          translationY = -rise.value * 40.dp.toPx()
        }
        .background(BannerBand)
        .padding(horizontal = 48.dp, vertical = 4.dp),
    ) {
      Text(
        stringResource(R.string.juice_unlocked),
        style = BenchType.Banner.copy(
          fontSize = BenchType.Banner.fontSize * BANNER_SIZE,
          brush = Brush.verticalGradient(listOf(BenchColors.BrassLight, BenchColors.Brass, BenchColors.BrassDeep)),
          shadow = Shadow(color = BenchColors.Brass.copy(alpha = 0.8f), blurRadius = 28f),
        ),
      )
    }
  }
}

/** The screen's edges glow in the colour of what just happened, and fade. */
@Composable
private fun EdgeFlash(juice: BenchJuice) {
  Spacer(
    Modifier.fillMaxSize().drawWithCache {
      fun glow(color: Color) = Brush.radialGradient(
        0f to Color.Transparent,
        FLASH_INNER to Color.Transparent,
        1f to color,
        center = Offset(size.width / 2f, size.height / 2f),
        radius = size.maxDimension * FLASH_RADIUS,
      )
      val set = glow(BenchColors.Set)
      val fault = glow(BenchColors.Fault)
      val brass = glow(BenchColors.Brass)
      onDrawBehind {
        val strength = juice.flash.value
        if (strength <= 0f) return@onDrawBehind
        val brush = when (juice.flashColor) {
          BenchColors.Fault -> fault
          BenchColors.Brass -> brass
          else -> set
        }
        drawRect(brush, alpha = strength.coerceIn(0f, 1f))
      }
    },
  )
}
