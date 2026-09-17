package nz.personal.checkpointwatch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import nz.personal.checkpointwatch.ui.CwIcons

/**
 * Which areas are worth a notification. Choosing none means "anywhere", which is the default and
 * is said out loud in the sheet rather than left to be inferred from an empty list.
 *
 * The list is searchable because it grows with every scan — after a few weeks it is every suburb
 * the page has ever mentioned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchedAreasSheet(
    suburbs: List<String>,
    chosen: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(suburbs, query) {
        if (query.isBlank()) suburbs else suburbs.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    val scheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.settings_watched_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_watched_sheet_body),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
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
                    .padding(top = 12.dp, bottom = 4.dp),
            )
            if (chosen.isNotEmpty()) {
                TextButton(onClick = { onChange(emptySet()) }) {
                    Text(stringResource(R.string.settings_watched_all))
                }
            }
        }
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            if (matches.isEmpty()) {
                item(key = "none", contentType = "empty") {
                    Text(
                        text = stringResource(R.string.filter_no_areas),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
            items(matches, key = { it }, contentType = { "area" }) { suburb ->
                val ticked = chosen.any { it.equals(suburb, ignoreCase = true) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .toggleable(
                            value = ticked,
                            role = Role.Checkbox,
                            onValueChange = { on ->
                                onChange(if (on) chosen + suburb else chosen - suburb)
                            },
                        )
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = ticked, onCheckedChange = null)
                    Text(
                        text = suburb,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item(key = "bottom", contentType = "spacer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
