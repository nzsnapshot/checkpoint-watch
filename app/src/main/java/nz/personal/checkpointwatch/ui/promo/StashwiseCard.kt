package nz.personal.checkpointwatch.ui.promo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.ui.CwIcons
import nz.personal.checkpointwatch.ui.theme.isDarkScheme

/** Above this font scale the card stacks, the same threshold the summary tiles fold at. */
private const val LARGE_FONT_SCALE = 1.3f

/** Where Stashwise lives. No storefront code in the App Store link, so Apple picks the country. */
internal object Stashwise {
    const val WEBSITE_URL = "https://www.stashwise.org"
    const val WEBSITE_LABEL = "www.stashwise.org"
    const val APP_STORE_URL = "https://apps.apple.com/app/stashwise/id6807915061"
}

/**
 * The one advertisement in the app: the developer's other app, on the home screen and in Settings.
 * It is a picture and some words shipped inside the APK — nothing is fetched to show it and nothing
 * is reported when it is tapped. A link only leaves the phone when the owner opens one.
 *
 * The card itself opens [StashwiseSheet], which is where the explanation and the two links live;
 * the card only has to say what the thing is and where it runs.
 */
@Composable
fun StashwiseCard(onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    // With large text the words need the whole width, so the prompt drops under them instead of
    // squeezing the tagline into a column too narrow to finish its sentence.
    val roomy = LocalDensity.current.fontScale < LARGE_FONT_SCALE

    Surface(
        onClick = { sheetOpen = true },
        shape = RoundedCornerShape(16.dp),
        // The same edge rule as every other card: a hairline in light, a lighter fill in dark.
        color = if (scheme.isDarkScheme) scheme.surfaceContainerLow else scheme.surface,
        border = if (scheme.isDarkScheme) null else BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StashwiseIcon(Modifier.size(48.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.stashwise_name),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.stashwise_platform),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = stringResource(R.string.stashwise_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (roomy) 2 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Stashwise.WEBSITE_LABEL,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                )
                if (!roomy) CardAction(Modifier.padding(top = 4.dp))
            }
            if (roomy) CardAction()
        }
    }

    if (sheetOpen) {
        StashwiseSheet(onOpenLink = onOpenLink, onDismiss = { sheetOpen = false })
    }
}

@Composable
private fun CardAction(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.stashwise_card_action),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** What Stashwise is, who can have it today, and the two ways to go and look. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StashwiseSheet(onOpenLink: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        StashwiseSheetContent(onOpenLink = onOpenLink)
    }
}

/** The sheet's insides, on their own so a preview or a screenshot can render them without a window. */
@Composable
internal fun StashwiseSheetContent(onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StashwiseIcon(Modifier.size(64.dp))
            Column {
                Text(
                    text = stringResource(R.string.stashwise_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stashwise_sheet_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(R.string.stashwise_sheet_body),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
        Text(
            text = stringResource(R.string.stashwise_sheet_origin),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        // Said plainly, because this is an Android app advertising something its own owner
        // cannot install on the phone they are reading it on.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.stashwise_sheet_iphone_only),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                modifier = Modifier.padding(12.dp),
            )
        }
        Button(
            onClick = { onOpenLink(Stashwise.APP_STORE_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.stashwise_app_store))
            LinkMark()
        }
        OutlinedButton(
            onClick = { onOpenLink(Stashwise.WEBSITE_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(Stashwise.WEBSITE_LABEL)
            LinkMark()
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LinkMark() {
    Icon(
        imageVector = CwIcons.OpenInNew,
        contentDescription = null,
        modifier = Modifier
            .padding(start = 8.dp)
            .size(16.dp),
    )
}

/** Stashwise's own icon, corners rounded the way a launcher would round them. */
@Composable
private fun StashwiseIcon(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.stashwise_icon),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(percent = 22)),
    )
}
