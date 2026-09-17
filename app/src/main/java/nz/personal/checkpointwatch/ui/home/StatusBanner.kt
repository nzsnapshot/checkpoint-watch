package nz.personal.checkpointwatch.ui.home

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nz.personal.checkpointwatch.R
import nz.personal.checkpointwatch.ui.CwIcons
import nz.personal.checkpointwatch.ui.TimeFormat
import nz.personal.checkpointwatch.ui.theme.DarkCrash
import nz.personal.checkpointwatch.ui.theme.LightCrash
import nz.personal.checkpointwatch.ui.theme.isDarkScheme
import java.time.Instant

/** One full pass of the sweep. Slow enough to read as "working", not as "hurry up". */
private const val SWEEP_DURATION_MS = 1500

/** How much of the track the moving band covers. */
private const val SWEEP_BAND_FRACTION = 0.45f

private val TRACK_HEIGHT = 3.dp

/** Above this font scale "Checked 3 min ago" stops fitting beside the message and moves below it. */
private const val STACK_FONT_SCALE = 1.3f

/** Beside the message, the trailing line never takes more of the width than this. */
private val TRAILING_MAX_WIDTH = 132.dp

private val BANNER_ICON_SIZE = 18.dp

/**
 * What the last (or current) scan has to say, in one calm line.
 *
 * Scanning is shown as a slow amber sweep under the text rather than a spinner, because the saved
 * reports underneath stay usable the whole time and a spinner would suggest otherwise. The result
 * cross-fades into place so a glance away and back does not miss a jump.
 */
@Composable
fun StatusBanner(
    banner: BannerUi,
    scanning: Boolean,
    lastChecked: Instant?,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val message = (banner as? BannerUi.Message)?.text
    val kind = (banner as? BannerUi.Message)?.kind
    if (message == null && !scanning) return

    val scheme = MaterialTheme.colorScheme
    val checked = lastChecked?.let { stringResource(R.string.banner_checked, TimeFormat.ago(it, now)) }
    val scanningDescription = stringResource(R.string.cd_banner_scanning)
    val spoken = message ?: scanningDescription
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainer)
            // In light, container and page are both near-white; the hairline is what makes this a
            // panel rather than a stray line of text.
            .border(
                width = 1.dp,
                color = if (scheme.isDarkScheme) Color.Transparent else scheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        val text: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // Only the result belongs in the live region. "Checked 3 min ago" is rewritten by
                // the ticker every minute, and inside the region that would make TalkBack announce
                // the banner again every minute for as long as the screen is open.
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                },
            ) {
                BannerIcon(kind)
                AnimatedContent(
                    targetState = message.orEmpty(),
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "banner-text",
                ) { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }
            }
        }
        val trailing: @Composable () -> Unit = {
            if (checked != null) {
                Text(
                    text = checked,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = if (stacked) TextAlign.Start else TextAlign.End,
                    modifier = if (stacked) Modifier else Modifier.widthIn(max = TRAILING_MAX_WIDTH),
                )
            }
        }

        if (stacked) {
            // At large text "Checked 3 min ago" no longer fits beside the message, so it goes
            // underneath it rather than squeezing the message into one word per line.
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                text()
                trailing()
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) { text() }
                trailing()
            }
        }
        AnimatedVisibility(
            visible = scanning,
            enter = fadeIn(tween(160)) + expandVertically(),
            exit = fadeOut(tween(160)) + shrinkVertically(),
        ) {
            SweepTrack()
        }
    }
}

/**
 * The one-glance version of the message: found something, found nothing, or something went wrong.
 * Purely decorative — the sentence beside it is what the live region announces — and absent while
 * a scan runs, where the sweep already says what is happening.
 */
@Composable
private fun BannerIcon(kind: BannerKind?) {
    val scheme = MaterialTheme.colorScheme
    val icon = when (kind) {
        BannerKind.FOUND -> CwIcons.Radar to scheme.primary
        BannerKind.NONE_NEW -> CwIcons.Check to scheme.onSurfaceVariant
        BannerKind.GAP, BannerKind.FAILED -> CwIcons.Warning to warningTone()
        BannerKind.SCANNING, null -> null
    } ?: return
    Icon(
        imageVector = icon.first,
        contentDescription = null,
        tint = icon.second,
        modifier = Modifier.size(BANNER_ICON_SIZE),
    )
}

/** The crash orange, reused: a scan that failed is a caution, not an error the owner caused. */
@Composable
private fun warningTone(): Color =
    if (MaterialTheme.colorScheme.isDarkScheme) DarkCrash else LightCrash

/**
 * A 3 dp amber band travelling across a dim track.
 *
 * With the system's animator scale at zero ("remove animations" in accessibility settings) the
 * band simply sits still: the track is still there to say a scan is running, but nothing moves.
 */
@Composable
private fun SweepTrack(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = accent.copy(alpha = 0.16f)
    val animate = animationsEnabled()

    val progress = if (animate) {
        val transition = rememberInfiniteTransition(label = "sweep")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(SWEEP_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep-progress",
        ).value
    } else {
        0.5f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT),
    ) {
        drawRect(color = track)
        val band = size.width * SWEEP_BAND_FRACTION
        val start = -band + progress * (size.width + band)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to accent,
                1f to Color.Transparent,
                startX = start,
                endX = start + band,
            ),
        )
    }
}

/**
 * Whether the phone is animating at all. `ANIMATOR_DURATION_SCALE` is the setting behind
 * "remove animations", and is the value Android's own views consult.
 */
@Composable
private fun animationsEnabled(): Boolean {
    // Previews and screenshot tests render one frame; a still band is the honest thing to draw.
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale != 0f
    }
}
