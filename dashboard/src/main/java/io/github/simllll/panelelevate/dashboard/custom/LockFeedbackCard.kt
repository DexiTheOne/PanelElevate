package io.github.simllll.panelelevate.dashboard.custom

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardRenderer
import io.github.simllll.panelelevate.dashboard.image.RemoteImage
import io.github.simllll.panelelevate.dashboard.json.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * `custom:lock-feedback-card` (PanelElevate only): a animated confirmation for a door
 * lock, meant as content of a `browser_mod.popup`, e.g. after pressing the lock's button.
 *
 * - `action`: `lock` (green, shackle snaps shut, then a check badge) or `unlock` (amber, springs open)
 * - `text`: big line below the padlock (optional)
 * - `subtitle`: smaller second line (optional)
 * - `image`: an image or animated GIF (e.g. `/local/gifs/hi.gif`) shown instead of the padlock (optional)
 *
 * The card fills the popup with a gradient in the action's color; pulsing rings radiate from the
 * padlock while it is shown.
 */
class LockFeedbackCard(config: JsonObject) : CardRenderer {
    private val unlock = config.string("action") == "unlock"
    private val text = config.string("text")
    private val subtitle = config.string("subtitle")
    private val image = config.string("image")

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val accent = if (unlock) Color(0xFFFFA726) else Color(0xFF2ECC71)
        val deep = if (unlock) Color(0xFF4A2A00) else Color(0xFF0B3D24)
        val base = Color(0xFF0E1013)
        // The popup body scrolls (unbounded height): size the card from the screen instead,
        // leaving room for the popup header (title + close button) and its padding
        val height = (LocalConfiguration.current.screenHeightDp - 100).coerceIn(240, 520).dp

        val pop = remember { Animatable(0.6f) }
        val shackle = remember { Animatable(if (unlock) 0f else 1f) }
        val badge = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) }
            delay(300)
            shackle.animateTo(if (unlock) 1f else 0f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessLow))
            if (!unlock) badge.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
        }
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
        )

        Box(
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(deep, base)))
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent))),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!image.isNullOrBlank()) {
                    RemoteImage(
                        ctx.images, ctx.hass.absoluteUrl(image),
                        Modifier
                            .fillMaxWidth()
                            .height(height * 0.55f)
                            .scale(pop.value)
                            .shadow(18.dp, RoundedCornerShape(18.dp))
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Padlock(accent, pop.value, shackle.value, badge.value, pulse, Modifier.size(height * 0.52f))
                }
                Spacer(Modifier.height(18.dp))
                if (!text.isNullOrBlank()) {
                    BasicText(
                        text,
                        style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        subtitle,
                        style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp, textAlign = TextAlign.Center),
                    )
                }
            }
        }
    }

    @Composable
    private fun Padlock(accent: Color, pop: Float, shackle: Float, badge: Float, pulse: Float, modifier: Modifier) {
        Canvas(modifier) {
            val c = Offset(size.width / 2, size.height / 2)
            val disc = size.minDimension * 0.30f * pop
            // Ripples radiating outwards
            for (i in 0..2) {
                val t = (pulse + i / 3f) % 1f
                drawCircle(accent.copy(alpha = 0.45f * (1f - t)), disc + (size.minDimension * 0.5f - disc) * t, c,
                    style = Stroke(width = 3.dp.toPx() * (1f - t) + 1f))
            }
            // Glowing disc
            drawCircle(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.75f)), c, disc), disc, c)
            drawCircle(Color.White.copy(alpha = 0.18f), disc, c, style = Stroke(2.dp.toPx()))

            // Padlock (white)
            val bodyW = disc * 0.95f
            val bodyH = disc * 0.72f
            val bodyTop = c.y - bodyH * 0.05f
            val r = bodyW * 0.30f
            val sw = disc * 0.13f
            val legH = bodyH * 0.40f
            val lift = legH * 0.9f * shackle
            val pivot = Offset(c.x + r, bodyTop)
            translate(top = -lift) {
                rotate(-30f * shackle, pivot = pivot) {
                    val arcTop = bodyTop - legH - r
                    drawArc(Color.White, 180f, 180f, false, Offset(c.x - r, arcTop), Size(r * 2, r * 2),
                        style = Stroke(sw, cap = StrokeCap.Round))
                    drawLine(Color.White, Offset(c.x - r, arcTop + r), Offset(c.x - r, bodyTop + sw / 2), sw, StrokeCap.Round)
                    drawLine(Color.White, Offset(c.x + r, arcTop + r), Offset(c.x + r, bodyTop + sw / 2), sw, StrokeCap.Round)
                }
            }
            drawRoundRect(Color.White, Offset(c.x - bodyW / 2, bodyTop), Size(bodyW, bodyH), CornerRadius(bodyW * 0.18f))
            drawCircle(accent, bodyH * 0.12f, Offset(c.x, bodyTop + bodyH * 0.42f))
            drawRoundRect(accent, Offset(c.x - bodyH * 0.045f, bodyTop + bodyH * 0.45f), Size(bodyH * 0.09f, bodyH * 0.26f),
                CornerRadius(bodyH * 0.045f))

            // Check badge (lock only)
            if (badge > 0f) {
                val bc = Offset(c.x + disc * 0.70f, c.y + disc * 0.70f)
                val br = disc * 0.30f * badge
                drawCircle(Color.White, br, bc)
                val p = Path().apply {
                    moveTo(bc.x - br * 0.45f, bc.y + br * 0.02f)
                    lineTo(bc.x - br * 0.10f, bc.y + br * 0.36f)
                    lineTo(bc.x + br * 0.48f, bc.y - br * 0.30f)
                }
                drawPath(p, accent, style = Stroke(br * 0.22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
