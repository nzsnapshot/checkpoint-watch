package nz.personal.checkpointwatch.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import nz.personal.checkpointwatch.ui.home.HomeCallbacks
import nz.personal.checkpointwatch.ui.home.HomeContent
import nz.personal.checkpointwatch.ui.home.HomeUiState
import nz.personal.checkpointwatch.ui.theme.CheckpointWatchTheme

/** Nothing happens in a preview; every screen state still has to render. */
internal val NoCallbacks = HomeCallbacks(
    onRefresh = {},
    onToggleType = {},
    onSoloType = {},
    onSetSuburb = {},
    onClearFilters = {},
    onOpenSettings = {},
    onOpenPost = { true },
)

@Composable
private fun Preview(state: HomeUiState, dark: Boolean) {
    CheckpointWatchTheme(darkTheme = dark) {
        HomeContent(state = state, callbacks = NoCallbacks)
    }
}

@Preview(name = "Home · dark", showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun HomeDarkPreview() = Preview(SampleData.home, dark = true)

@Preview(name = "Home · light", showBackground = true, backgroundColor = 0xFFF6F7FB)
@Composable
private fun HomeLightPreview() = Preview(SampleData.home, dark = false)

@Preview(name = "Home · dark, 150% text", showBackground = true, backgroundColor = 0xFF0B1220, fontScale = 1.5f)
@Composable
private fun HomeDarkLargeTextPreview() = Preview(SampleData.home, dark = true)

@Preview(name = "Home · light, 150% text", showBackground = true, backgroundColor = 0xFFF6F7FB, fontScale = 1.5f)
@Composable
private fun HomeLightLargeTextPreview() = Preview(SampleData.home, dark = false)

@Preview(name = "Home · scanning", showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun HomeScanningPreview() = Preview(SampleData.scanning, dark = true)

@Preview(name = "Home · quiet", showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun HomeQuietPreview() = Preview(SampleData.quiet, dark = true)

@Preview(name = "Home · first run", showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun HomeFirstRunPreview() = Preview(SampleData.firstRun, dark = true)

@Preview(name = "Home · offline", showBackground = true, backgroundColor = 0xFFF6F7FB)
@Composable
private fun HomeOfflinePreview() = Preview(SampleData.offline, dark = false)

@Preview(name = "Home · page returned nothing", showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun HomeNoPostsPreview() = Preview(SampleData.noPosts, dark = true)

@Preview(name = "Home · nothing reported yet", showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun HomeNothingYetPreview() = Preview(SampleData.nothingYet, dark = true)

@Preview(name = "Home · pulled to refresh", showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun HomePullRefreshingPreview() = Preview(SampleData.pullRefreshing, dark = true)

@Preview(name = "Home · nothing matches the filters", showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun HomeFilteredOutPreview() = Preview(SampleData.filteredOut, dark = true)

@Preview(name = "Home · narrow phone", showBackground = true, backgroundColor = 0xFF0B1220, widthDp = 320)
@Composable
private fun HomeNarrowPreview() = Preview(SampleData.home, dark = true)

@Preview(name = "Home · 200% text", showBackground = true, backgroundColor = 0xFF0B1220, fontScale = 2.0f)
@Composable
private fun HomeHugeTextPreview() = Preview(SampleData.home, dark = true)
