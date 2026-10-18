package com.actionanand.localtell.app.saved

/** org.json accepts non-JSON syntax on Android. Validate strict syntax and duplicate keys first.
 * Input is bounded by the codec; nesting is bounded here. Values are decoded by org.json afterward. */
internal class StrictBackupJson(private val text: String) {
    private var index = 0
    private var values = 0

    fun validate() {
        value(0)
        whitespace()
        require(index == text.length)
    }

    private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
    private fun take(character: Char): Boolean {
        whitespace()
        if (index >= text.length || text[index] != character) return false
        index++
        return true
    }

    private fun value(depth: Int) {
        require(depth <= 16)
        // Full backups have fewer than 20,000 values at the absolute record ceilings.
        // Bound tiny-array/object floods before org.json allocates their object graph.
        require(++values <= 40_000)
        whitespace()
        require(index < text.length)
        when (text[index]) {
            '{' -> {
                index++
                val keys = mutableSetOf<String>()
                if (take('}')) return
                do {
                    whitespace()
                    require(keys.add(string()))
                    require(take(':'))
                    value(depth + 1)
                } while (take(','))
                require(take('}'))
            }
            '[' -> {
                index++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                require(take(']'))
            }
            '"' -> { string() }
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val match = NUMBER.find(text, index)
                require(match != null && match.range.first == index)
                index = match.range.last + 1
            }
        }
    }

    private fun literal(value: String) {
        require(text.startsWith(value, index))
        index += value.length
    }

    private fun string(): String {
        require(index < text.length && text[index++] == '"')
        val result = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') return result.toString().also { require(validBackupUnicode(it)) }
            require(char >= ' ')
            if (char != '\\') { result.append(char); continue }
            require(index < text.length)
            result.append(when (val escaped = text[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + 4 <= text.length)
                    val code = text.substring(index, index + 4)
                    require(code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                    index += 4
                    code.toInt(16).toChar()
                }
                else -> error("Invalid JSON escape")
            })
        }
        error("Unterminated JSON string")
    }

    companion object {
        private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}

internal fun validBackupUnicode(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char.isHighSurrogate()) {
            if (index >= text.length || !text[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}
