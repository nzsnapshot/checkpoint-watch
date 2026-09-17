package nz.personal.checkpointwatch.scan

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.personal.checkpointwatch.data.ScanTrigger
import java.io.File

/** Directory the diagnostics live in, under the app's private files. Never backed up; see backup_rules. */
private const val DIRECTORY = "diagnostics"

/**
 * Ceiling on one stored file. The collector caps its own log long before this; the limit is here
 * so that a runaway page can never fill the phone with a diagnostics file.
 */
private const val MAX_FILE_CHARS = 256 * 1024

/**
 * Keeps the last foreground scan's diagnostics and the last background scan's, so the owner can
 * copy either out of Settings and paste it into a message.
 *
 * One file per trigger, overwritten every scan. Deliberately NOT a Room table: the database schema
 * on the owner's phone is v1, and a migration is a much bigger thing to get wrong than a text file
 * that can simply be deleted. An interface so [ScanCoordinator] stays a JVM unit test.
 */
interface ScanDiagnosticsStore {

    /** Replaces the stored diagnostics for [trigger]. Never throws: this is a nicety, not a scan. */
    suspend fun write(trigger: ScanTrigger, text: String)

    /** The stored text, or `null` when no scan of that kind has been recorded on this phone yet. */
    suspend fun read(trigger: ScanTrigger): String?

    /** Whether [read] would return something, without reading it. */
    suspend fun exists(trigger: ScanTrigger): Boolean
}

/** The real store: two text files in `filesDir/diagnostics`, written off the main thread. */
class FileScanDiagnosticsStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ScanDiagnosticsStore {

    private val directory = File(context.applicationContext.filesDir, DIRECTORY)

    override suspend fun write(trigger: ScanTrigger, text: String) = withContext(io) {
        try {
            directory.mkdirs()
            file(trigger).writeText(text.take(MAX_FILE_CHARS))
        } catch (_: Exception) {
            // A diagnostics file that could not be written is a shame, not a failed scan.
        }
    }

    override suspend fun read(trigger: ScanTrigger): String? = withContext(io) {
        try {
            file(trigger).takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun exists(trigger: ScanTrigger): Boolean = withContext(io) {
        try {
            file(trigger).isFile
        } catch (_: Exception) {
            false
        }
    }

    private fun file(trigger: ScanTrigger): File = File(directory, fileName(trigger))

    private fun fileName(trigger: ScanTrigger): String = when (trigger) {
        ScanTrigger.FOREGROUND -> "last-foreground.txt"
        ScanTrigger.BACKGROUND -> "last-background.txt"
    }
}
