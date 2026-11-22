package io.github.simllll.panelelevate.dashboard.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.format.parseIso
import io.github.simllll.panelelevate.dashboard.json.asStringList
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.HaButton
import io.github.simllll.panelelevate.dashboard.ui.HaSlider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/** Countdown driven by an existing HA timer and its input helpers. */
internal class CountdownCard(private val config: JsonObject) : CardRenderer {
    override fun gridOptions() = GridOptions(columns = 12, rowsAuto = true)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val timer = ctx.entity(config.string("timer"))
        val duration = ctx.entity(config.string("duration"))
        val profile = ctx.entity(config.string("profile"))
        val running = timer?.state in setOf("active", "paused")
        val action = config.string(if (running) "stop" else "start")
        val scope = rememberCoroutineScope()
        var pressPending by remember { mutableStateOf(false) }
        val theme = HaTheme.colors
        val type = HaTheme.typography
        val minutes = duration?.state?.toDoubleOrNull()
        val available = timer != null && !timer.isUnavailable && !timer.isUnknown && minutes != null

        HaCard(modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(config.string("title") ?: "Countdown", Modifier.weight(1f), style = type.cardHeader.copy(color = theme.primaryText))
                    if (available && action != null && !pressPending) {
                        HaButton(if (running) "Stop" else "Start", {
                            pressPending = true
                            ctx.actions.callService("input_button", "press", jsonOf("entity_id" to action), null)
                            scope.launch { delay(700); pressPending = false }
                        }, icon = if (running) "mdi:stop" else "mdi:play", filled = true)
                    }
                }

                if (!available) {
                    BasicText("Countdown entities unavailable", style = type.style(color = theme.secondaryText))
                } else if (running) {
                    val finish = parseIso(timer.attrString("finishes_at")).takeIf { timer.state == "active" }
                    val now by produceState(System.currentTimeMillis(), finish) {
                        while (finish != null && value < finish) {
                            delay(250)
                            value = System.currentTimeMillis()
                        }
                    }
                    val total = seconds(timer.attrString("duration")).coerceAtLeast(1)
                    val left = (finish?.let { ((it - now + 999) / 1000).toInt() }
                        ?: seconds(timer.attrString("remaining"))).coerceIn(0, total)
                    BasicText(if (timer.state == "paused") "Paused · stop to reset" else "Time remaining", style = type.style(color = theme.secondaryText))
                    Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(12.dp)).background(theme.primary.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(left.toFloat() / total).background(theme.primary))
                        BasicText(clock(left), Modifier.align(Alignment.Center), style = type.style(color = theme.primaryText))
                    }
                } else {
                    val options = profile?.attr("options").asStringList()
                    if (profile != null && options.isNotEmpty() && !profile.isUnavailable) {
                        NativeSelect(profile.state, options) { option ->
                            ctx.actions.callService("input_select", "select_option", jsonOf("entity_id" to profile.entityId, "option" to option), null)
                        }
                    }
                    val min = duration.attrDouble("min") ?: 1.0
                    val max = duration.attrDouble("max") ?: 60.0
                    val step = (duration.attrDouble("step") ?: 1.0).takeIf { it > 0 } ?: 1.0
                    if (max > min) {
                        HaSlider(minutes.toFloat(), { value ->
                            val stepped = (min + ((value - min) / step).roundToInt() * step).coerceIn(min, max)
                            ctx.actions.callService("input_number", "set_value", jsonOf("entity_id" to duration.entityId, "value" to stepped), null)
                        }, range = min.toFloat()..max.toFloat(), label = { "${it.roundToInt()} min" })
                    }
                }
            }
        }
    }

    private fun seconds(value: String?): Int = value.orEmpty().split(':').fold(0) { total, part ->
        total * 60 + (part.toIntOrNull() ?: 0)
    }

    private fun clock(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        val secs = (seconds % 60).toString().padStart(2, '0')
        return if (hours == 0) "$minutes:$secs" else "$hours:${minutes.toString().padStart(2, '0')}:$secs"
    }
}
