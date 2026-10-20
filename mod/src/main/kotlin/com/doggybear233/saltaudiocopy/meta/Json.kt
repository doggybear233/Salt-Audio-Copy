package com.doggybear233.saltaudiocopy.meta

/**
 * 极简 JSON 解析器。宿主 JRE 没有 `java.scripting`，插件只有 stdlib，
 * 因此不引入第三方 JSON 库；MB/CAA 的响应结构简单，够用。
 *
 * 支持对象/数组/字符串（含 \uXXXX）/数字/true/false/null；不保证数字精度超出 Double 的范围。
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

    fun str(v: Any?): String? = v as? String

    fun long(v: Any?): Long? = when (v) {
        is Double -> v.toLong()
        is Long -> v
        is String -> v.toLongOrNull()
        else -> null
    }

    /** 按 `a.b.0.c` 取值，数组用数字下标。 */
    fun path(root: Any?, vararg keys: String): Any? {
        var cur: Any? = root
        for (k in keys) {
            cur = when (val c = cur) {
                is Map<*, *> -> c[k]
                is List<*> -> k.toIntOrNull()?.let { c.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return cur
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("JSON 意外结束")
            return when (s[i]) {
                '{' -> objValue()
                '[' -> arrValue()
                '"' -> strValue()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> numValue()
            }
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("位置 $i 期望 $word")
            i += word.length
        }

        private fun objValue(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = strValue()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("位置 $i 期望 ':'")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("对象未闭合")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("位置 $i 期望 ',' 或 '}'")
                }
            }
        }

        private fun arrValue(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l += value()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("数组未闭合")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("位置 $i 期望 ',' 或 ']'")
                }
            }
        }

        private fun strValue(): String {
            if (s[i] != '"') throw IllegalArgumentException("位置 $i 期望字符串")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$e")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("字符串未闭合")
        }

        private fun numValue(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            val t = s.substring(start, i)
            return t.toDoubleOrNull() ?: throw IllegalArgumentException("位置 $start 非法数字 '$t'")
        }
    }
}
