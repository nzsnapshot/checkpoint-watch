package nz.personal.checkpointwatch.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import nz.personal.checkpointwatch.ui.settings.SettingsCallbacks
import nz.personal.checkpointwatch.ui.settings.SettingsContent
import nz.personal.checkpointwatch.ui.settings.SettingsUiState
import nz.personal.checkpointwatch.ui.theme.CheckpointWatchTheme

internal val NoSettingsCallbacks = SettingsCallbacks(
    onBack = {},
    onInterval = {},
    onNotifyChange = {},
    onToggleNotifyType = {},
    onToggleWatchedSuburb = {},
    onClearWatchedSuburbs = {},
    onBatterySettings = {},
    onNotificationSettings = {},
)

@Composable
private fun Preview(state: SettingsUiState, dark: Boolean) {
    CheckpointWatchTheme(darkTheme = dark) {
        SettingsContent(state = state, callbacks = NoSettingsCallbacks)
    }
}

@Preview(name = "Settings · dark", showBackground = true, backgroundColor = 0xFF0B1220, heightDp = 1400)
@Composable
private fun SettingsDarkPreview() = Preview(SampleData.settings, dark = true)

@Preview(name = "Settings · light", showBackground = true, backgroundColor = 0xFFF6F7FB, heightDp = 1400)
@Composable
private fun SettingsLightPreview() = Preview(SampleData.settings, dark = false)

@Preview(
    name = "Settings · dark, 150% text",
    showBackground = true,
    backgroundColor = 0xFF0B1220,
    fontScale = 1.5f,
    heightDp = 1800,
)
@Composable
private fun SettingsDarkLargeTextPreview() = Preview(SampleData.settings, dark = true)

@Preview(
    name = "Settings · light, 150% text",
    showBackground = true,
    backgroundColor = 0xFFF6F7FB,
    fontScale = 1.5f,
    heightDp = 1800,
)
@Composable
private fun SettingsLightLargeTextPreview() = Preview(SampleData.settings, dark = false)

@Preview(name = "Settings · everything off", showBackground = true, backgroundColor = 0xFF0B1220, heightDp = 1200)
@Composable
private fun SettingsQuietPreview() = Preview(SampleData.settingsQuiet, dark = true)

@Preview(name = "Settings · notifications refused", showBackground = true, backgroundColor = 0xFF0B1220, heightDp = 1400)
@Composable
private fun SettingsBlockedPreview() = Preview(SampleData.settingsBlocked, dark = true)
