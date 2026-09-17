package nz.personal.checkpointwatch.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.ui.theme.chipOutline

/** How much of a colour a selected chip's container carries. */
private const val CHIP_CONTAINER_ALPHA = 0.16f

private val CHIP_ICON_SIZE = 18.dp

/**
 * One chip language for the whole app: the filter row on the home screen and the
 * "tell me about" toggles in settings are the same control, so they look the same.
 *
 * Selected is the type's own colour at low alpha with a type-coloured icon and an ordinary
 * `onSurface` label — the type says which chip this is, not the accent. Unselected is an outline
 * and nothing else. Amber stays reserved for the app's actual accents (the NEW pill, the switch,
 * links, the scan sweep), so a row of on-by-default filters no longer reads as a row of alerts.
 */
@Composable
fun TypeChip(
    type: ReportType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val style = typeStyle(type)
    ToneChip(
        label = style.label,
        icon = style.icon,
        tone = style.color,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * A chip that *opens* something rather than toggling it — the area filter, which leads to a sheet.
 *
 * It keeps the same shape and tint as the toggles, but not their semantics: built on a `FilterChip`
 * it announced itself as "tick box, not ticked", which is wrong twice over for a control that opens
 * a bottom sheet and whose current value is a place name. The semantics are replaced outright with
 * a button, an action label, and the chosen area as its state.
 *
 * @param state what is chosen now, spoken as the control's state ("All areas", "HENDERSON").
 * @param actionLabel what activating it will do ("Choose area").
 */
@Composable
fun ActionChip(
    label: String,
    description: String,
    state: String,
    actionLabel: String,
    icon: ImageVector,
    tone: Color,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToneChip(
        label = label,
        icon = icon,
        tone = tone,
        selected = active,
        onClick = onClick,
        modifier = modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = description
            stateDescription = state
            onClick(label = actionLabel) {
                onClick()
                true
            }
        },
    )
}

/**
 * The same chip, tinted by an arbitrary [tone] rather than by a report type.
 */
@Composable
fun ToneChip(
    label: String,
    icon: ImageVector,
    tone: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize(),
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(CHIP_ICON_SIZE))
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = scheme.onSurfaceVariant,
            iconColor = scheme.onSurfaceVariant,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = scheme.onSurfaceVariant.copy(alpha = 0.5f),
            disabledLeadingIconColor = scheme.onSurfaceVariant.copy(alpha = 0.5f),
            selectedContainerColor = tone.copy(alpha = CHIP_CONTAINER_ALPHA),
            selectedLabelColor = scheme.onSurface,
            selectedLeadingIconColor = tone,
        ),
        // A selected chip is a filled shape and needs no outline; an unselected one is nothing but
        // its outline, so that outline has to carry 3:1 the way any control boundary does.
        border = if (selected) {
            null
        } else {
            FilterChipDefaults.filterChipBorder(
                enabled = enabled,
                selected = false,
                borderColor = scheme.chipOutline,
                disabledBorderColor = scheme.chipOutline.copy(alpha = 0.4f),
            )
        },
    )
}
