package nz.personal.checkpointwatch.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import nz.personal.checkpointwatch.model.ReportType
import java.io.IOException

/**
 * Everything the owner can choose. Defaults are the quiet ones: no background updates, no
 * notifications, nothing hidden from the list.
 *
 * [notifyTypes] and [hiddenTypes] are separate on purpose: what is worth a notification is not
 * the same question as what is worth showing in the list.
 */
data class Settings(
    /**
     * Background scan interval in minutes; 0 is off. One of the values the settings screen
     * offers (see `ALLOWED_BACKGROUND_MINUTES`); anything else is coerced when it is scheduled.
     */
    val backgroundMinutes: Int = 0,
    val notify: Boolean = false,
    val notifyTypes: Set<ReportType> = ReportType.entries.toSet() - ReportType.OTHER,
    /** When non-empty, only these suburbs are worth a notification. */
    val watchedSuburbs: Set<String> = emptySet(),
    /** Types the owner has filtered out of the list (a UI filter, not a notification one). */
    val hiddenTypes: Set<ReportType> = emptySet(),
    val suburbFilter: String? = null,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The owner's [Settings], backed by DataStore Preferences.
 *
 * Enum sets are stored as sets of enum names so a rename or removal in a later version degrades
 * to "that one is ignored" rather than a crash or a wiped file. A corrupt or unreadable file
 * reads back as the defaults instead of throwing into whatever is collecting the flow.
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<Settings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { it.toSettings() }

    /**
     * Applies [transform] to the stored settings in one atomic read-modify-write, so two
     * concurrent updates (say, the settings screen and the startup scheduler) cannot lose each
     * other's change.
     */
    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }
}

/** Keys as they appear in the file; changing one of these strings forgets that setting. */
private object Keys {
    val BACKGROUND_MINUTES = intPreferencesKey("background_minutes")
    val NOTIFY = booleanPreferencesKey("notify")
    val NOTIFY_TYPES = stringSetPreferencesKey("notify_types")
    val WATCHED_SUBURBS = stringSetPreferencesKey("watched_suburbs")
    val HIDDEN_TYPES = stringSetPreferencesKey("hidden_types")
    val SUBURB_FILTER = stringPreferencesKey("suburb_filter")
}

/** The pure half of the store: enum sets in and out of the strings DataStore can hold. */
internal object SettingsCodec {

    /** Names that are no longer (or not yet) a [ReportType] are dropped, never fatal. */
    fun decodeTypes(names: Set<String>): Set<ReportType> =
        names.mapNotNullTo(LinkedHashSet()) { name -> ReportType.entries.firstOrNull { it.name == name } }

    fun encodeTypes(types: Set<ReportType>): Set<String> = types.mapTo(LinkedHashSet()) { it.name }
}

private fun Preferences.toSettings(): Settings {
    val defaults = Settings()
    return Settings(
        backgroundMinutes = this[Keys.BACKGROUND_MINUTES] ?: defaults.backgroundMinutes,
        notify = this[Keys.NOTIFY] ?: defaults.notify,
        notifyTypes = this[Keys.NOTIFY_TYPES]?.let(SettingsCodec::decodeTypes) ?: defaults.notifyTypes,
        watchedSuburbs = this[Keys.WATCHED_SUBURBS] ?: defaults.watchedSuburbs,
        hiddenTypes = this[Keys.HIDDEN_TYPES]?.let(SettingsCodec::decodeTypes) ?: defaults.hiddenTypes,
        suburbFilter = this[Keys.SUBURB_FILTER] ?: defaults.suburbFilter,
    )
}

private fun MutablePreferences.write(settings: Settings) {
    this[Keys.BACKGROUND_MINUTES] = settings.backgroundMinutes
    this[Keys.NOTIFY] = settings.notify
    this[Keys.NOTIFY_TYPES] = SettingsCodec.encodeTypes(settings.notifyTypes)
    this[Keys.WATCHED_SUBURBS] = settings.watchedSuburbs
    this[Keys.HIDDEN_TYPES] = SettingsCodec.encodeTypes(settings.hiddenTypes)
    val suburbFilter = settings.suburbFilter
    if (suburbFilter == null) remove(Keys.SUBURB_FILTER) else this[Keys.SUBURB_FILTER] = suburbFilter
}
