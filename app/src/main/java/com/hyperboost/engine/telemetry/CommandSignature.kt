// app/src/main/java/com/hyperboost/engine/telemetry/CommandSignature.kt
package com.hyperboost.engine.telemetry

/**
 * Reduces a privileged shell command to a signature that is safe to upload.
 *
 * The capability report exists to answer one question per device: *which operations does this ROM
 * accept?* For that it only needs the verb path (`settings put system`, `cmd wifi
 * force-low-latency-mode`), never the arguments. Arguments carry the user's setting values, package
 * names and hostnames, so they are replaced with fixed placeholders before anything leaves the
 * device.
 *
 * `settings put system peak_refresh_rate '120'` becomes `settings put system peak_refresh_rate`.
 * `am set-standby-bucket com.example.game restricted` becomes `am set-standby-bucket <pkg>`.
 */
object CommandSignature {

    /** Placeholder used for anything that could identify the device, an app or a preference. */
    const val VALUE = "<v>"
    const val PACKAGE = "<pkg>"
    const val PATH = "<path>"

    /** Verbs whose meaning lives one token further right, so one extra token is kept. */
    private val VERBS_WITH_KEY = setOf("settings", "device_config", "setprop", "getprop")

    private val NUMERIC = Regex("^[+-]?[0-9]+(\\.[0-9]+)?$")
    private val PLAIN_TOKEN = Regex("^[A-Za-z][A-Za-z0-9_.-]*$")

    fun of(command: String): String {
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return "unknown"
        val verb = tokens.first()
        val keep = if (verb in VERBS_WITH_KEY) 4 else 3
        // The key position holds an AOSP name (`debug.hwui.renderer`, `peak_refresh_rate`), which is
        // exactly the signal the report needs and carries no user data. Everywhere else a dotted
        // token is a package name or a hostname and must go.
        val keyIndex = when (verb) {
            "settings", "device_config" -> 3
            "setprop", "getprop" -> 1
            else -> -1
        }
        return tokens.take(keep)
            .mapIndexed { index, token -> sanitize(token, allowDotted = index == keyIndex) }
            .joinToString(" ")
    }

    /** Splits on whitespace while keeping quoted runs together, so a quoted value stays one token. */
    private fun tokenize(command: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (ch in command.trim()) {
            when {
                escaped -> { current.append(ch); escaped = false }
                ch == '\\' -> { current.append(ch); escaped = true }
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    current.append(ch)
                }
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun sanitize(raw: String, allowDotted: Boolean = false): String {
        val token = raw.trim()
        if (token.isEmpty()) return VALUE
        // Quoted values are always user data.
        if (token.startsWith("'") || token.startsWith("\"")) return VALUE
        if (NUMERIC.matches(token)) return VALUE
        if (!allowDotted && token.contains('.') && !token.contains('/')) return PACKAGE
        if (token.contains('/') || token.contains(':')) return PATH
        if (token.length > 40) return VALUE
        return if (PLAIN_TOKEN.matches(token)) token else VALUE
    }
}
