package nz.personal.checkpointwatch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CheckpointWatchColorScheme = darkColorScheme(
    background = Background,
    onBackground = OnBackground,
    primary = Beacon,
)

@Composable
fun CheckpointWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CheckpointWatchColorScheme,
        typography = AppTypography,
        content = content,
    )
}
