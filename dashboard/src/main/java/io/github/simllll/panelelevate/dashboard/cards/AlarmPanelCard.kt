package io.github.simllll.panelelevate.dashboard.cards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.asStringList
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.theme.CssColors
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.PressIndication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * `alarm-panel` (hui-alarm-panel-card): header with the name and a state chip (state color,
 * pulsing while arming/pending/triggered; tap = more-info), the action buttons (configured
 * `states` while disarmed, otherwise "disarm"), the masked code field and — for numeric codes —
 * the 3×4 keypad of 56px round buttons with a red clear key. Actions call
 * `alarm_control_panel.alarm_<action>` with the entered code and clear it.
 *
 * Deviations: the action buttons sit below the keypad (above it a "Deaktivieren" button reads
 * like the panel's state). With `auto_submit_length` (number of digits, 0 = off) a full code
 * disarms without pressing the button; if the panel is not disarmed [WRONG_CODE_AFTER_MS]
 * later, the code field shakes and shows "Falscher Code".
 */
class AlarmPanelCard(private val config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")
    /** null = all supported arm modes of [DEFAULT_STATES] (the card's default). */
    private val states: List<String>? = config["states"]?.asStringList()
    private val autoSubmitLength: Int = config["auto_submit_length"].asString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    init {
        require(entityId?.substringBefore('.') == "alarm_control_panel") { "Invalid configuration" }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(entityId)
        if (entity == null) {
            WarningCard("Entität nicht gefunden: $entityId").Content(ctx, modifier)
            return
        }
        var code by remember { mutableStateOf("") }
        // Time of the last auto-submitted code while waiting for the result (0 = not checking)
        var checkingSince by remember { mutableStateOf(0L) }
        var wrongCode by remember { mutableStateOf(false) }
        val shake = remember { Animatable(0f) }
        val codeFormat = entity.attrString("code_format")
        val actions = if (entity.state == "disarmed") states ?: DEFAULT_STATES.filter { supports(entity, it) } else listOf("disarm")
        fun submit(action: String) {
            ctx.actions.callService(
                "alarm_control_panel",
                "alarm_$action",
                jsonOf("entity_id" to entity.entityId, "code" to code.takeIf { it.isNotEmpty() }),
                null,
            )
            code = ""
        }
        LaunchedEffect(entity.state) { if (entity.state == "disarmed") checkingSince = 0L }
        LaunchedEffect(checkingSince) {
            if (checkingSince == 0L) return@LaunchedEffect
            delay(WRONG_CODE_AFTER_MS)
            checkingSince = 0L
            wrongCode = true
            for (x in listOf(14f, -14f, 10f, -10f, 5f, 0f)) shake.animateTo(x, tween(50))
            delay(2_500)
            wrongCode = false
        }
        HaCard(modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CardHeader(config.string("name") ?: entity.friendlyName, trailing = { StateChip(ctx, entity) })
                val status = when {
                    checkingSince != 0L -> "Prüfe …"
                    wrongCode -> "Falscher Code"
                    else -> null
                }
                if (codeFormat != null) CodeField(code, status, wrongCode, Modifier.graphicsLayer { translationX = shake.value * density })
                if (codeFormat == "number") Keypad(
                    code,
                    onKey = { key ->
                        if (checkingSince != 0L) return@Keypad
                        wrongCode = false
                        code += key
                        if (autoSubmitLength > 0 && code.length >= autoSubmitLength && actions == listOf("disarm")) {
                            submit("disarm")
                            checkingSince = System.currentTimeMillis()
                        }
                    },
                    onClear = { code = "" },
                )
                FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    actions.forEach { action ->
                        ActionButton(LABELS[action] ?: action, danger = action == "disarm") { submit(action) }
                    }
                }
            }
        }
    }

    private fun supports(entity: EntityState, action: String): Boolean {
        val feature = FEATURES[action] ?: return true
        return entity.supportedFeatures and feature != 0
    }

    companion object {
        private val DEFAULT_STATES = listOf("arm_home", "arm_away")
        private val FEATURES = mapOf("arm_home" to 1, "arm_away" to 2, "arm_night" to 4, "arm_custom_bypass" to 16, "arm_vacation" to 32)

        /** `ui.card.alarm_control_panel.*` of the German frontend translation. */
        private val LABELS = mapOf(
            "disarm" to "Deaktivieren",
            "arm_home" to "Aktivieren (Zuhause)",
            "arm_away" to "Aktivieren (Abwesend)",
            "arm_night" to "Aktivieren (Nacht)",
            "arm_vacation" to "Aktivieren (Urlaub)",
            "arm_custom_bypass" to "Aktivieren (Mit Bypass)",
        )
        private val PULSING = setOf("triggered", "arming", "pending")
        private val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "")

        /** Auto-submitted code not accepted after this long = wrong (PIN checks may ask the Nuki cloud). */
        const val WRONG_CODE_AFTER_MS = 6_000L
    }

    /** `ha-assist-chip filled`: 32px pill in the state color, white label and state icon. */
    @Composable
    private fun StateChip(ctx: CardContext, entity: EntityState) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val color = CssColors.stateColor(entity) ?: c.stateInactive
        val alpha = if (entity.state in PULSING) {
            val pulse by rememberInfiniteTransition(label = "alarm").animateFloat(
                1f, 0f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "pulse",
            )
            pulse
        } else 1f
        Row(
            Modifier
                .graphicsLayer { this.alpha = alpha }
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(color)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = remember { PressIndication(c.textPrimaryColor) },
                ) { ctx.popups.showMoreInfo(entity.entityId) }
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MdiIcon(ctx.icons.icon(entity), c.textPrimaryColor, size = 18.dp)
            BasicText(
                if (entity.isUnavailable) "Nicht verfügbar" else ctx.formatter.translateState(entity),
                style = t.style(t.sizeM, FontWeight.Medium, t.lineHeightCondensed, color = c.textPrimaryColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    /** `ha-button appearance=filled size=s`: brand (primary) or danger (red). */
    @Composable
    private fun ActionButton(label: String, danger: Boolean, onClick: () -> Unit) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val bg = if (danger) c.error else c.primary
        Box(
            Modifier
                .padding(4.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = remember { PressIndication(Color.White) },
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = t.style(t.sizeM, FontWeight.Medium, t.lineHeightCondensed, color = Color.White), maxLines = 1)
        }
    }

    /**
     * `ha-input type=password`: 150px outlined field labelled "Code", entered digits masked.
     * [status] replaces the dots ("Prüfe …", "Falscher Code"); [error] colors the field red.
     */
    @Composable
    private fun CodeField(code: String, status: String?, error: Boolean, modifier: Modifier) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        Column(
            modifier
                .padding(8.dp)
                .width(150.dp)
                .border(1.dp, if (error) c.error else c.outlineHover, RoundedCornerShape(HaTheme.dimens.radiusMd))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            BasicText("Code", style = t.style(t.sizeS, color = if (error) c.error else c.secondaryText))
            BasicText(
                status ?: "•".repeat(code.length),
                style = if (status != null) t.style(t.sizeM, color = if (error) c.error else c.secondaryText) else t.style(t.sizeL, color = c.primaryText),
                maxLines = 1,
                modifier = Modifier.height(24.dp),
            )
        }
    }

    /** 3 columns, 16px gaps, 12px padding; `ha-control-button` 56px, 28px radius, 24px digits. */
    @Composable
    private fun Keypad(code: String, onKey: (String) -> Unit, onClear: () -> Unit) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            KEYS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { key ->
                        if (key.isEmpty()) {
                            Box(Modifier.size(56.dp))
                            return@forEach
                        }
                        val clear = key == "clear"
                        val enabled = !clear || code.isNotEmpty()
                        val tint = if (clear) CssColors.computeCssColor("red") ?: c.error else c.primaryText
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background((if (clear) tint else c.disabled).copy(alpha = 0.2f))
                                .clickable(
                                    enabled = enabled,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = remember(tint) { PressIndication(tint) },
                                ) { if (clear) onClear() else onKey(key) }
                                .then(if (enabled) Modifier else Modifier.alpha(0.5f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (clear) MdiIcon("mdi:close", tint)
                            else BasicText(key, style = t.style(t.size2xl, FontWeight.Medium, t.lineHeightCondensed, color = tint))
                        }
                    }
                }
            }
        }
    }
}
