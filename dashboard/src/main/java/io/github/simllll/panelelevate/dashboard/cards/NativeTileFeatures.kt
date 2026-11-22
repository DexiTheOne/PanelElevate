package io.github.simllll.panelelevate.dashboard.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.simllll.panelelevate.dashboard.hass.ConnectionState
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.isActive
import io.github.simllll.panelelevate.dashboard.json.asArray
import io.github.simllll.panelelevate.dashboard.json.asDouble
import io.github.simllll.panelelevate.dashboard.json.asInt
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asStringList
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.obj
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.HaButton
import io.github.simllll.panelelevate.dashboard.ui.HaSlider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt
import kotlin.math.ln
import kotlin.math.pow

internal val nativeTileFeatures = setOf(
    "light-brightness", "light-color-temp", "light-color-favorites", "cover-tilt-position",
    "cover-tilt-favorite", "select-options", "target-temperature", "toggle",
)

private val colorModes = setOf("hs", "xy", "rgb", "rgbw", "rgbww")
private val dimmableModes = colorModes + setOf("brightness", "color_temp", "white")
private const val SET_TILT_POSITION = 128

@Composable
internal fun NativeTileFeature(ctx: CardContext, entity: EntityState, config: JsonObject) {
    if (entity.isUnavailable || entity.state == "unknown") return
    val modes = entity.attr("supported_color_modes").asStringList()
    val kelvinRange = colorTemperatureRange(entity)
    fun call(service: String, vararg data: Pair<String, Any?>) {
        ctx.actions.callService(entity.domain, service, jsonOf("entity_id" to entity.entityId, *data), null)
    }
    when (config.string("type")) {
        "light-brightness" -> if (entity.domain == "light" && modes.any { it in dimmableModes }) {
            val value = if (entity.isActive()) (entity.attrDouble("brightness") ?: 0.0).toFloat() / 2.55f else 0f
            LabeledSlider("Brightness", value, "%") { level ->
                if (level.roundToInt() == 0) call("turn_off")
                else call("turn_on", "brightness_pct" to level.roundToInt())
            }
        }
        "light-color-temp" -> if (entity.domain == "light" && "color_temp" in modes && kelvinRange != null) {
            val value = entity.attrDouble("color_temp_kelvin")?.toFloat() ?: (kelvinRange.start + kelvinRange.endInclusive) / 2
            LabeledSlider("Color temperature", value, " K", kelvinRange) {
                call("turn_on", "color_temp_kelvin" to it.roundToInt())
            }
        }
        "light-color-favorites" -> if (entity.domain == "light") {
            val options = registryOptions(ctx, entity)
            val defaults = defaultColors(modes, kelvinRange)
            val favorites = options?.obj("light")?.get("favorite_colors").asArray()?.mapNotNull { it.asObject() } ?: defaults
            ColorFavorites(favorites) { favorite ->
                ctx.actions.callService("light", "turn_on", JsonObject(favorite + ("entity_id" to JsonPrimitive(entity.entityId))), null)
            }
        }
        "cover-tilt-position" -> if (entity.domain == "cover" && entity.supportedFeatures and SET_TILT_POSITION != 0) {
            LabeledSlider("Tilt", entity.attrDouble("current_tilt_position")?.toFloat() ?: 0f, "%") {
                call("set_cover_tilt_position", "tilt_position" to it.roundToInt())
            }
        }
        "cover-tilt-favorite" -> if (entity.domain == "cover" && entity.supportedFeatures and SET_TILT_POSITION != 0) {
            val options = registryOptions(ctx, entity)
            val positions = options?.obj("cover")?.get("favorite_tilt_positions").asArray()
                ?.mapNotNull { it.asInt()?.takeIf { pos -> pos in 0..100 } }
                ?: listOf(0, 25, 50, 75, 100)
            val presets = positions.distinct()
            val scroll = presets.size > 5
            Row(Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { position ->
                    HaButton("$position%", { call("set_cover_tilt_position", "tilt_position" to position) },
                        modifier = if (scroll) Modifier else Modifier.weight(1f),
                        filled = entity.attrDouble("current_tilt_position")?.roundToInt() == position,
                        contentPadding = PaddingValues(horizontal = 8.dp))
                }
            }
        }
        "select-options" -> if (entity.domain in setOf("select", "input_select")) {
            val allowed = config["options"].asStringList()
            val options = entity.attr("options").asStringList().filter { allowed.isEmpty() || it in allowed }
            if (options.isNotEmpty()) {
                NativeSelect(entity.state, options) { option -> call("select_option", "option" to option) }
            }
        }
        "target-temperature" -> if (entity.domain == "climate") {
            entity.attrDouble("temperature")?.let { temperature ->
                val step = entity.attrDouble("target_temp_step") ?: 1.0
                val min = entity.attrDouble("min_temp") ?: temperature - step
                val max = entity.attrDouble("max_temp") ?: temperature + step
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    HaButton("−", { call("set_temperature", "temperature" to (temperature - step).coerceIn(min, max)) })
                    BasicText("${temperature.toBigDecimal().stripTrailingZeros().toPlainString()}°", style = HaTheme.typography.style(color = HaTheme.colors.primaryText))
                    HaButton("+", { call("set_temperature", "temperature" to (temperature + step).coerceIn(min, max)) })
                }
            }
        }
        "toggle" -> HaButton(if (entity.isActive()) "Turn off" else "Turn on", {
            ctx.actions.callService("homeassistant", "toggle", jsonOf("entity_id" to entity.entityId), null)
        })
    }
}

private fun colorTemperatureRange(entity: EntityState): ClosedFloatingPointRange<Float>? {
    val min = entity.attrDouble("min_color_temp_kelvin")?.toFloat() ?: return null
    val max = entity.attrDouble("max_color_temp_kelvin")?.toFloat() ?: return null
    return if (max > min) min..max else null
}

@Composable
private fun registryOptions(ctx: CardContext, entity: EntityState): JsonObject? {
    val connection by ctx.hass.connectionState.collectAsState()
    val options by produceState<JsonObject?>(null, ctx.hass, entity.entityId, connection) {
        if (connection != ConnectionState.Connected) return@produceState
        try {
            value = ctx.hass.command(jsonOf("type" to "config/entity_registry/get", "entity_id" to entity.entityId))
                .asObject()?.obj("options")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Registry permissions can differ from the permissions needed to operate the entity.
        }
    }
    return options
}

@Composable
private fun LabeledSlider(
    name: String,
    value: Float,
    unit: String,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    onChange: (Float) -> Unit,
) {
    HaSlider(value, onChange, range = range, height = 42.dp, label = { "$name · ${it.roundToInt()}$unit" })
}

@Composable
internal fun NativeSelect(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val shape = RoundedCornerShape(12.dp)
    val colors = HaTheme.colors
    val typography = HaTheme.typography
    Box(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = with(density) { it.width.toDp() } }) {
        HaButton("$selected  ▾", { open = true }, Modifier.fillMaxWidth())
        if (open) {
            Popup(SelectMenuPosition, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(
                    Modifier
                        .size(width = anchorWidth.coerceAtLeast(140.dp), height = (options.size * 44).dp.coerceAtMost(220.dp))
                        .shadow(8.dp, shape)
                        .clip(shape)
                        .background(colors.cardBackground)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEach { option ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .clickable { open = false; if (option != selected) onSelect(option) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicText(option, style = typography.style(color = if (option == selected) colors.primary else colors.primaryText))
                        }
                    }
                }
            }
        }
    }
}

private object SelectMenuPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val top = if (anchorBounds.bottom + popupContentSize.height <= windowSize.height) anchorBounds.bottom
        else anchorBounds.top - popupContentSize.height
        return IntOffset(
            left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private val defaultRgb = listOf(
    listOf(127, 172, 255), listOf(215, 150, 255), listOf(255, 158, 243), listOf(255, 110, 84),
)

private fun defaultColors(modes: List<String>, range: ClosedFloatingPointRange<Float>?): List<JsonObject> {
    val temperatures = when {
        "color_temp" in modes && range != null -> (0..3).map { step ->
            jsonOf("color_temp_kelvin" to (range.start + (range.endInclusive - range.start) * step / 3).roundToInt())
        }
        modes.any { it in colorModes } -> listOf(2000, 3500, 5000, 6500).map { jsonOf("rgb_color" to temperatureRgb(it)) }
        else -> emptyList()
    }
    val colors = if (modes.any { it in colorModes }) defaultRgb.map { jsonOf("rgb_color" to it) } else emptyList()
    return temperatures + colors
}

@Composable
private fun ColorFavorites(favorites: List<JsonObject>, onSelect: (JsonObject) -> Unit) {
    if (favorites.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        favorites.forEachIndexed { index, color ->
            val rgb = color["rgb_color"].asArray()?.mapNotNull { it.asInt() }
            val kelvin = color["color_temp_kelvin"].asInt()
            val hs = color["hs_color"].asArray()?.mapNotNull { it.asDouble() }
            val swatch = when {
                rgb?.size == 3 -> Color(rgb[0].coerceIn(0, 255), rgb[1].coerceIn(0, 255), rgb[2].coerceIn(0, 255))
                hs?.size == 2 -> Color.hsv(hs[0].toFloat().coerceIn(0f, 360f), (hs[1] / 100).toFloat().coerceIn(0f, 1f), 1f)
                kelvin != null -> temperatureRgb(kelvin).let { Color(it[0], it[1], it[2]) }
                else -> HaTheme.colors.primary
            }
            val label = kelvin?.let { "$it K" } ?: "Favorite color ${index + 1}"
            Box(Modifier.size(44.dp, 40.dp).clip(RoundedCornerShape(12.dp)).background(swatch)
                .semantics { contentDescription = label }.clickable { onSelect(color) })
        }
    }
}

// Same black-body color approximation used by HA's default favorite swatches.
private fun temperatureRgb(kelvin: Int): List<Int> {
    val t = kelvin.coerceIn(1000, 40000) / 100.0
    val red = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val green = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val blue = when {
        t >= 66 -> 255.0
        t <= 19 -> 0.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    return listOf(red, green, blue).map { it.roundToInt().coerceIn(0, 255) }
}
