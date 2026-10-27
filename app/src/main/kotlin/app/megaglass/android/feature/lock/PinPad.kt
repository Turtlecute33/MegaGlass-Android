package app.megaglass.android.feature.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.megaglass.android.R
import app.megaglass.android.ui.components.Ic
import kotlin.math.roundToInt

/**
 * Dots for the entered digits. Each change of [shakeKey] (when it is not 0) shakes the row
 * and gives haptic feedback for a wrong code. TalkBack reads the count of entered digits.
 */
@Composable
fun PinDots(length: Int, filled: Int, shakeKey: Int, modifier: Modifier = Modifier) {
    val offset = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(shakeKey) {
        if (shakeKey == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        for (x in listOf(24f, -20f, 14f, -8f, 0f)) {
            offset.animateTo(x, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessHigh))
        }
    }
    val description = pluralStringResource(R.plurals.pin_dots_state, length, filled, length)
    Row(
        modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
            .semantics {
                stateDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(length) { i ->
            val on = i < filled
            val size by animateDpAsState(if (on) 18.dp else 14.dp, label = "dot")
            Box(
                Modifier.size(size).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/**
 * Number pad. [extra] is the key left of 0 (for example the biometric button).
 * Keys are large rounded surfaces that react to presses with a shape and colour change.
 */
@Composable
fun NumberPad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val rows = listOf("123", "456", "789")
    Column(modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { d ->
                    DigitKey(d) {
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        onDigit(d)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(KEY), contentAlignment = Alignment.Center) { extra?.invoke() }
            DigitKey('0') {
                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                onDigit('0')
            }
            Box(Modifier.size(KEY), contentAlignment = Alignment.Center) {
                FilledTonalIconButton(onClick = onDelete, modifier = Modifier.size(56.dp)) {
                    Ic(R.drawable.ic_backspace, stringResource(R.string.lock_delete))
                }
            }
        }
    }
}

private val KEY = 76.dp

@Composable
private fun DigitKey(digit: Char, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(KEY),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit.toString(), style = MaterialTheme.typography.headlineMedium)
        }
    }
}
