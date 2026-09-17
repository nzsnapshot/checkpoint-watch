package nz.personal.checkpointwatch.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.model.ReportType
import nz.personal.checkpointwatch.ui.CwIcons
import nz.personal.checkpointwatch.ui.ToneChip
import nz.personal.checkpointwatch.ui.TypeChip

/** The home screen's own gutter, repeated here because the bar spans the full width when pinned. */
private val GUTTER = 16.dp

/**
 * The row of filters, pinned under the top bar: one chip per type and one for the area.
 *
 * Every chip carries its icon as well as its colour, so the row still reads correctly to someone
 * who cannot tell the red one from the orange one.
 */
@Composable
fun FilterBar(
    hiddenTypes: Set<ReportType>,
    suburbs: List<String>,
    suburbFilter: String?,
    onToggleType: (ReportType) -> Unit,
    onSetSuburb: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val allAreas = stringResource(R.string.filter_all_areas)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = GUTTER, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReportType.entries.forEach { type ->
            TypeChip(
                type = type,
                selected = type !in hiddenTypes,
                onClick = { onToggleType(type) },
            )
        }
        ToneChip(
            label = suburbFilter ?: allAreas,
            icon = CwIcons.Place,
            // The owner's own choice rather than a category, so it carries the app's accent.
            tone = MaterialTheme.colorScheme.primary,
            selected = suburbFilter != null,
            onClick = { sheetOpen = true },
        )
    }

    if (sheetOpen) {
        AreaSheet(
            suburbs = suburbs,
            selected = suburbFilter,
            onSelect = {
                onSetSuburb(it)
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

/** The area picker: one choice at a time, with a search box because the list grows with use. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AreaSheet(
    suburbs: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(suburbs, query) {
        if (query.isBlank()) suburbs else suburbs.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.filter_area_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.filter_search_hint)) },
                leadingIcon = { Icon(CwIcons.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            item(key = "all", contentType = "area") {
                AreaRow(
                    label = stringResource(R.string.filter_all_areas),
                    chosen = selected == null,
                    onClick = { onSelect(null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (matches.isEmpty()) {
                item(key = "none", contentType = "empty") {
                    Text(
                        text = stringResource(R.string.filter_no_areas),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
            items(matches, key = { it }, contentType = { "area" }) { suburb ->
                AreaRow(
                    label = suburb,
                    chosen = suburb.equals(selected, ignoreCase = true),
                    onClick = { onSelect(suburb) },
                )
            }
            item(key = "bottom", contentType = "spacer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AreaRow(label: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (chosen) {
            Icon(
                imageVector = CwIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
