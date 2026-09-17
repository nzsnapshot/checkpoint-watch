package nz.personal.checkpointwatch.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nz.personal.checkpointwatch.ui.home.HomeCallbacks
import nz.personal.checkpointwatch.ui.home.HomeContent
import nz.personal.checkpointwatch.ui.home.HomeViewModel
import nz.personal.checkpointwatch.ui.settings.SettingsCallbacks
import nz.personal.checkpointwatch.ui.settings.SettingsContent
import nz.personal.checkpointwatch.ui.settings.SettingsViewModel

private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"

/** Enough movement to say "this is a different place", not enough to be a performance. */
private const val SLIDE_FRACTION = 12

/**
 * The app's two screens. The transitions are a fade with a small slide, which is what predictive
 * back can animate under the user's finger without looking like it is fighting the gesture.
 *
 * @param openHomeSignal increments when the Activity is re-entered from a notification; whatever
 *   is on top is popped so the owner lands on the list they were told about.
 */
@Composable
fun AppNav(
    homeViewModel: HomeViewModel,
    onRefresh: () -> Unit,
    openHomeSignal: Int,
) {
    val navController = rememberNavController()

    LaunchedEffect(openHomeSignal) {
        if (openHomeSignal > 0) navController.popBackStack(ROUTE_HOME, inclusive = false)
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME,
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally { it / SLIDE_FRACTION } },
        exitTransition = { fadeOut(tween(180)) + slideOutHorizontally { -it / SLIDE_FRACTION } },
        popEnterTransition = { fadeIn(tween(220)) + slideInHorizontally { -it / SLIDE_FRACTION } },
        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally { it / SLIDE_FRACTION } },
    ) {
        composable(ROUTE_HOME) {
            HomeRoute(
                viewModel = homeViewModel,
                onRefresh = onRefresh,
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun HomeRoute(
    viewModel: HomeViewModel,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val callbacks = remember(viewModel, context, onRefresh, onOpenSettings) {
        HomeCallbacks(
            onRefresh = onRefresh,
            onToggleType = viewModel::toggleType,
            onSoloType = viewModel::soloType,
            onSetSuburb = viewModel::setSuburb,
            onClearFilters = viewModel::clearFilters,
            onOpenSettings = onOpenSettings,
            onOpenPost = { url -> context.openLink(url) },
        )
    }
    HomeContent(state = state, callbacks = callbacks)
}

@Composable
private fun SettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(context.applicationContext as android.app.Application),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // A refused permission is not a preference, so it lives here rather than in the store — and it
    // is remembered across configuration changes so the explanation does not blink away.
    var blocked by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        blocked = !granted
        viewModel.setNotify(granted)
    }

    val callbacks = remember(viewModel, context, onBack) {
        SettingsCallbacks(
            onBack = onBack,
            onInterval = viewModel::setInterval,
            onNotifyChange = { wanted ->
                if (!wanted) {
                    blocked = false
                    viewModel.setNotify(false)
                } else {
                    // The channel first, then the permission: the design asks for both at the
                    // moment the switch is flipped, not at the first notification.
                    viewModel.prepareNotifications()
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED ->
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                        !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
                            blocked = true

                        else -> {
                            blocked = false
                            viewModel.setNotify(true)
                        }
                    }
                }
            },
            onToggleNotifyType = viewModel::toggleNotifyType,
            onWatchedSuburbs = viewModel::setWatchedSuburbs,
            onBatterySettings = {
                context.open(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            onNotificationSettings = {
                context.open(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        )
    }

    SettingsContent(state = state.copy(notificationsBlocked = blocked), callbacks = callbacks)
}

/** Opens a link in whatever the owner uses for the web. False when the phone has nothing. */
private fun Context.openLink(url: String): Boolean =
    open(Intent(Intent.ACTION_VIEW, url.toUri()))

/** A system screen this phone may or may not have; never worth crashing over. */
private fun Context.open(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
