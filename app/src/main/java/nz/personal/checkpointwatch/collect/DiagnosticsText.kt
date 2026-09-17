package nz.personal.checkpointwatch.collect

/**
 * The shape of the scan diagnostics the owner copies out of the app and pastes into a message.
 *
 * A short block of `key=value` lines that can be read at a glance, then the collector script's own
 * JSON log, which cannot. Two halves, because the two halves answer different questions: the lines
 * say what the app and the WebView did, the JSON says what the page did.
 *
 * Pure, so the layout can be tested without a WebView, a phone or a file. Values are flattened to
 * one line each: a stray newline in a WebView version string or an error description must not be
 * able to forge a key of its own.
 */
object DiagnosticsText {

    /** Fields with a `null` value are left out entirely rather than printed as "null". */
    fun header(fields: List<Pair<String, String?>>): String = buildString {
        fields.forEach { (key, value) ->
            if (value != null) {
                append(key).append('=').append(oneLine(value)).append('\n')
            }
        }
    }

    /** [header], then a blank line, then the collector's JSON log if there is one. */
    fun document(fields: List<Pair<String, String?>>, json: String?): String = buildString {
        append(header(fields))
        append('\n')
        append(json ?: NO_LOG)
        append('\n')
    }

    /** What the log says when the script never reported: an absence worth naming. */
    const val NO_LOG: String = "(the collector script sent no log)"

    private fun oneLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()
}
