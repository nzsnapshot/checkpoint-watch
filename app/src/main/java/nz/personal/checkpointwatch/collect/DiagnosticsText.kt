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

    /**
     * One pass's facts and its script's log: a few `key=value` lines, a blank line, the JSON.
     *
     * A starved scan has two of these, because it is two scans — the page, and then the widget —
     * and "why did the fallback bring back five posts?" is a question only the second half answers.
     */
    data class Block(val fields: List<Pair<String, String?>>, val json: String?)

    /**
     * [header], then a blank line, then the collector's JSON log if there is one — and, when a
     * second pass ran, the same again under [PLUGIN_HEADING].
     */
    fun document(
        fields: List<Pair<String, String?>>,
        json: String?,
        plugin: Block? = null,
    ): String = buildString {
        append(header(fields))
        append('\n')
        append(json ?: NO_LOG)
        append('\n')
        if (plugin != null) {
            append('\n')
            append(PLUGIN_HEADING)
            append('\n')
            append(header(plugin.fields))
            append('\n')
            append(plugin.json ?: NO_LOG)
            append('\n')
        }
    }

    /** What the log says when the script never reported: an absence worth naming. */
    const val NO_LOG: String = "(the collector script sent no log)"

    /** The line that separates the page's pass from the page widget's. */
    const val PLUGIN_HEADING: String = "--- page widget pass ---"

    private fun oneLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()
}
