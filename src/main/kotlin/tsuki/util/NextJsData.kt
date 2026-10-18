package tsuki.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts and searches embedded data in Next.js HTML pages.
 *
 * Supports both routing systems:
 *
 * - App Router (Next.js 13+):
 *   Reads Flight data from `self.__next_f.push([1, "..."])` scripts.
 *   Combines escaped strings and parses newline-separated RSC rows,
 *   including JSON objects, arrays, and `T:` text chunks.
 *   RSC references such as `$L`, `$@`, and `$S` are not fully resolved.
 *
 * - Pages Router (Next.js 12 and earlier):
 *   Reads the JSON payload from `__NEXT_DATA__`.
 *   Extracts `props.pageProps` and the root object.
 *
 * Provides helpers to:
 * - Extract top-level JSON payloads.
 * - Find the first matching value using a predicate.
 * - Find objects by key, value, and required properties.
 * - Find arrays, optionally validating their first element.
 *
 * Supports inline JSON data and basic RSC Flight parsing,
 * but does not resolve the complete RSC reference graph.
 */
object NextJsData {

    private const val NEXT_F_PREFIX = "self.__next_f.push([1,"

    /** Returns every top-level JSON node embedded in [html] */
    fun extractPayloads(html: String): List<Any> {
        val appRouter = extractFlightPayloads(html)
        if (appRouter.isNotEmpty()) return appRouter
        return extractNextDataPayloads(html)
    }

    /** Finds the first node anywhere in the payload graph matching [predicate] */
    fun findValue(html: String, predicate: (Any) -> Boolean): Any? {
        for (payload in extractPayloads(html)) {
            findValueIn(payload, predicate)?.let { return it }
        }
        return null
    }

    /** Finds the first object with `[key] == value` and every [requiredKeys] */
    fun findObject(
        html: String,
        key: String,
        value: String,
        vararg requiredKeys: String,
    ): JSONObject? = findValue(html) { node ->
        node is JSONObject &&
                node.optString(key) == value &&
                requiredKeys.all { node.has(it) }
    } as? JSONObject

    /** Finds the first array, optionally validating the first element */
    fun findArray(
        html: String,
        validator: ((JSONObject) -> Boolean)? = null,
    ): JSONArray? = findValue(html) { node ->
        node is JSONArray && node.length() > 0 &&
                (validator == null || node.optJSONObject(0)?.let(validator) == true)
    } as? JSONArray

    /** App router */
    private fun extractFlightPayloads(html: String): List<Any> {
        val scripts = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val marker = html.indexOf(NEXT_F_PREFIX, searchFrom)
            if (marker == -1) break

            var i = marker + NEXT_F_PREFIX.length
            if (i >= html.length || html[i] != '"') { searchFrom = i; continue }
            i++
            val start = i
            var escaped = false
            var end = -1
            while (i < html.length) {
                val c = html[i]
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> { end = i; break }
                }
                i++
            }
            if (end == -1) break
            scripts += html.substring(start, end).unescapeJson()
            searchFrom = end + 1
        }
        return scripts.flatMap { extractRscRows(it) }
    }

    private fun extractRscRows(body: String): List<Any> {
        val results = mutableListOf<Any>()
        var pos = 0
        while (pos < body.length) {
            val colon = body.indexOf(':', pos)
            if (colon == -1) break

            val id = body.substring(pos, colon)
            if (id.isEmpty() || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                pos++; continue
            }

            pos = colon + 1
            if (pos >= body.length) break

            if (body[pos] == 'T') {
                pos++
                val comma = body.indexOf(',', pos)
                if (comma == -1) break
                val byteLen = body.substring(pos, comma).toIntOrNull(16) ?: break
                pos = comma + 1
                var bytes = 0
                val start = pos
                while (pos < body.length && bytes < byteLen) {
                    bytes += when {
                        body[pos].code < 0x80 -> 1
                        body[pos].code < 0x800 -> 2
                        Character.isHighSurrogate(body[pos]) -> { pos++; 4 }
                        else -> 3
                    }
                    pos++
                }
                runCatching { parseJson(body.substring(start, pos)) }
                    .getOrNull()?.let { results += it }
            } else {
                val (element, end) = parseJsonAt(body, pos)
                element?.let { results += it }
                pos = end
            }
        }
        return results
    }

    /** Pages router */
    private fun extractNextDataPayloads(html: String): List<Any> {
        val marker = "id=\"__NEXT_DATA__\""
        val idx = html.indexOf(marker)
        if (idx == -1) return emptyList()
        val start = html.indexOf('>', idx) + 1
        if (start <= 0) return emptyList()
        val end = html.indexOf("</script>", start)
        if (end == -1) return emptyList()
        return runCatching {
            val root = JSONObject(html.substring(start, end).trim())
            listOfNotNull(
                root.optJSONObject("props")?.optJSONObject("pageProps"),
                root,
            )
        }.getOrDefault(emptyList())
    }

    /** Helper */
    private fun findValueIn(node: Any, predicate: (Any) -> Boolean): Any? {
        if (predicate(node)) return node
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val child = node.opt(keys.next()) ?: continue
                    findValueIn(child, predicate)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val child = node.opt(i) ?: continue
                    findValueIn(child, predicate)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseJson(text: String): Any {
        val t = text.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> throw IllegalArgumentException("Not JSON")
        }
    }

    private fun parseJsonAt(body: String, start: Int): Pair<Any?, Int> {
        if (start >= body.length) return null to start
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < body.length) {
            val c = body[i++]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inString) { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) {
                    return runCatching { parseJson(body.substring(start, i)) }.getOrNull() to i
                }
            }
            if (depth == 0 && c.isWhitespace()) {
                return runCatching { parseJson(body.substring(start, i - 1)) }.getOrNull() to i
            }
        }
        return null to i
    }

    private fun String.unescapeJson(): String {
        val sb = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c == '\\' && i + 1 < length) {
                when (this[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        val code = if (i + 5 < length) substring(i + 2, i + 6).toIntOrNull(16) else null
                        if (code != null) { sb.append(code.toChar()); i += 6 }
                        else { sb.append(c); i++ }
                    }
                    else -> { sb.append(c); i++ }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
