package io.github.simllll.panelelevate.dashboard.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * `ha-switch` (48×24 track, 18px thumb, 1px border) in the dark default theme.
 * The whole 40px touch target toggles.
 */
@Composable
fun HaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = HaTheme.colors
    val thumbOffset by animateDpAsState(if (checked) 27.dp else 3.dp, tween(150), label = "thumb")
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier
            .size(width = 48.dp, height = 40.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background((if (checked) c.switchCheckedTrack else c.switchTrack).copy(alpha = alpha), CircleShape)
                .border(1.dp, (if (checked) c.switchCheckedBorder else c.switchBorder).copy(alpha = alpha), CircleShape)
        )
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .shadow(1.dp, CircleShape)
                .background((if (checked) c.switchCheckedThumb else c.switchThumb).copy(alpha = alpha), CircleShape)
        )
    }
}

/** Round icon button (`ha-icon-button`, 48px target, 24px icon). */
@Composable
fun HaIconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HaTheme.colors.primaryText,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier
            .size(size)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = remember(tint) { PressIndication(tint, shape = CircleShape) },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MdiIcon(icon, if (enabled) tint else HaTheme.colors.disabledText, size = iconSize)
    }
}

/** Text button (`ha-button`): [filled] = primary background, otherwise outlined/plain. */
@Composable
fun HaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    filled: Boolean = false,
    outlined: Boolean = !filled,
    color: Color = HaTheme.colors.primary,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val shape = RoundedCornerShape(HaTheme.dimens.radiusMd * 2)
    val t = HaTheme.typography
    val fg = if (filled) HaTheme.colors.textPrimaryColor else HaTheme.colors.primaryText
    Row(
        modifier
            .height(40.dp)
            .then(if (filled) Modifier.background(color, shape) else Modifier)
            .then(if (outlined) Modifier.border(1.dp, HaTheme.colors.outlineHover, shape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = remember(fg) { PressIndication(fg, shape = shape) },
                onClick = onClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            MdiIcon(icon, fg, size = 18.dp)
            Box(Modifier.width(8.dp))
        }
        BasicText(
            text,
            style = t.style(t.sizeM, FontWeight.Medium, t.lineHeightCondensed, color = fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Horizontal slider in the style of `ha-control-slider` (rounded track filled up to the value).
 * [onValueChangeFinished] fires once when the finger lifts — use it for service calls.
 */
@Composable
fun HaSlider(
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    color: Color = HaTheme.colors.primary,
    height: Dp = 48.dp,
    label: ((Float) -> String)? = null,
    track: Brush? = null,
    optimisticTolerance: Float? = null,
    fillHandle: Boolean = false,
    onPreviewChange: ((Float?) -> Unit)? = null,
) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var pending by remember(range) { mutableStateOf<SliderRequest?>(null) }
    val preview = dragValue ?: pending?.value
    val shown = (preview ?: value).coerceIn(range)
    if (onPreviewChange != null) SideEffect { onPreviewChange(preview) }
    val fraction = (shown - range.start) / (range.endInclusive - range.start)
    val latestFinish by rememberUpdatedState(onValueChangeFinished)
    val latestTolerance by rememberUpdatedState(optimisticTolerance)
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(4_000)
            pending = null
        }
    }
    LaunchedEffect(pending, value, optimisticTolerance) {
        val request = pending ?: return@LaunchedEffect
        if (optimisticTolerance != null && abs(value - request.value) <= optimisticTolerance) pending = null
    }
    fun finish(next: Float) {
        if (latestTolerance != null) pending = SliderRequest(next)
        latestFinish(next)
    }
    fun valueAt(x: Float) = range.start + (x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start)
    val shape = RoundedCornerShape(if (fillHandle || track != null) 12.dp else HaTheme.dimens.radiusLg)
    val fillShape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    val handleMinWidth = with(LocalDensity.current) { 14.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .clip(shape)
            .then(if (track == null) Modifier.background(color.copy(alpha = 0.2f)) else Modifier.background(track))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(shown, range) }
            .pointerInput(range) {
                detectTapGestures { finish(valueAt(it.x)) }
            }
            .pointerInput(range) {
                detectHorizontalDragGestures(
                    onDragStart = { dragValue = valueAt(it.x) },
                    onDragEnd = { dragValue?.let(::finish); dragValue = null },
                    onDragCancel = { dragValue = null },
                ) { change, _ -> dragValue = valueAt(change.position.x) }
            }
    ) {
        if (track == null) Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(color, if (fillHandle) fillShape else RoundedCornerShape(0.dp)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (fillHandle && width * fraction >= handleMinWidth) {
                Box(Modifier.padding(end = 6.dp).size(4.dp, 21.dp).background(Color.White, CircleShape))
            }
        } else Box(
            Modifier.align(BiasAlignment(fraction * 2f - 1f, 0f))
                .size(width = 11.dp, height = height)
                .shadow(3.dp, RoundedCornerShape(5.dp))
                .background(Color.White, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 4.dp, height = 21.dp).background(Color(0xFFE8E8E8), CircleShape))
        }
        if (label != null) {
            BasicText(label(shown), Modifier.align(Alignment.Center), style = HaTheme.typography.style(color = HaTheme.colors.primaryText))
        }
    }
}

private class SliderRequest(val value: Float)
