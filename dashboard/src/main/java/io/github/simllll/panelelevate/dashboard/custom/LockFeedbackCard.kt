package io.github.simllll.panelelevate.dashboard.custom

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardRenderer
import io.github.simllll.panelelevate.dashboard.cards.HaCard
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * `custom:lock-feedback-card` (PanelElevate only): short animated confirmation for a door lock,
 * meant as content of a `browser_mod.popup` with a timeout, e.g. after pressing the lock's button.
 *
 * - `action`: `lock` (shackle snaps shut, green) or `unlock` (shackle springs open, amber)
 * - `text`: line below the padlock (optional)
 *
 * The animation runs once each time the card is shown: the shackle moves with a slight bounce
 * while a ring around the padlock fills up.
 */
class LockFeedbackCard(config: JsonObject) : CardRenderer {
    private val unlock = config.string("action") == "unlock"
    private val text = config.string("text")

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val colors = HaTheme.colors
        val accent = if (unlock) colors.warning else colors.success
        // 0 = shackle closed, 1 = open (raised and swung out)
        val shackle = remember { Animatable(if (unlock) 0f else 1f) }
        val ring = remember { Animatable(0f) }
        val pop = remember { Animatable(0.85f) }
        LaunchedEffect(Unit) {
            launch { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
            launch { ring.animateTo(1f, tween(durationMillis = 1100, easing = LinearEasing)) }
            kotlinx.coroutines.delay(250)
            shackle.animateTo(
                if (unlock) 1f else 0f,
                spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow),
            )
        }

        HaCard(modifier) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(132.dp)) {
                        val stroke = 6.dp.toPx()
                        // Ring track + progress
                        val inset = stroke / 2
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        drawArc(accent.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                        drawArc(accent, -90f, 360f * ring.value, false, Offset(inset, inset), arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round))

                        val s = pop.value
                        val cx = size.width / 2
                        val cy = size.height / 2
                        val bodyW = size.width * 0.40f * s
                        val bodyH = size.height * 0.30f * s
                        val bodyTop = cy - bodyH * 0.15f
                        val shackleW = bodyW * 0.62f
                        val shackleStroke = size.width * 0.055f * s
                        val legH = bodyH * 0.55f
                        // Open: raised and swung around the right leg
                        val lift = legH * 0.75f * shackle.value
                        val swing = -28f * shackle.value
                        val rightLegX = cx + shackleW / 2
                        translate(top = -lift) {
                            rotate(swing, pivot = Offset(rightLegX, bodyTop)) {
                                val r = shackleW / 2
                                val arcTop = bodyTop - legH - r
                                drawArc(
                                    accent, 180f, 180f, false,
                                    Offset(cx - r, arcTop), Size(r * 2, r * 2),
                                    style = Stroke(shackleStroke, cap = StrokeCap.Round),
                                )
                                drawLine(accent, Offset(cx - r, arcTop + r), Offset(cx - r, bodyTop + shackleStroke),
                                    shackleStroke, StrokeCap.Round)
                                drawLine(accent, Offset(cx + r, arcTop + r), Offset(cx + r, bodyTop + shackleStroke),
                                    shackleStroke, StrokeCap.Round)
                            }
                        }
                        // Body with keyhole
                        drawRoundRect(accent, Offset(cx - bodyW / 2, bodyTop), Size(bodyW, bodyH),
                            CornerRadius(bodyW * 0.16f))
                        val hole = Color.Black.copy(alpha = 0.35f)
                        drawCircle(hole, bodyH * 0.13f, Offset(cx, bodyTop + bodyH * 0.42f))
                        drawRoundRect(hole, Offset(cx - bodyH * 0.05f, bodyTop + bodyH * 0.45f),
                            Size(bodyH * 0.10f, bodyH * 0.28f), CornerRadius(bodyH * 0.05f))
                    }
                }
                if (!text.isNullOrBlank()) {
                    BasicText(
                        text,
                        style = TextStyle(
                            color = colors.primaryText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }
    }
}
