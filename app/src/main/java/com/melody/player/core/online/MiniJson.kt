package com.melody.player.core.online

/**
 * 极简 JSON 解析器（递归下降，零依赖）。
 *
 * 为什么不用 `org.json`：它是 Android 框架的一部分，在 JVM 单测里只有一个空壳
 * （配合 `isReturnDefaultValues = true` 时所有方法返回默认值），
 * 于是「解析对不对」这件事根本没法测。而歌词接口的响应结构恰好就是我们最需要
 * 测的东西 —— 匹配打分错了，用户就会拿到别人的歌词。
 *
 * 本工程已经有手写的 ID3 / FLAC / LRC 解析器，这里再补一个只读的 JSON 解析器
 * 是同一套做法：纯 Kotlin、可完整单测、不引入任何依赖。
 *
 * 只实现「读」，不做序列化；不支持尾随逗号、注释、单引号字符串等非标准写法。
 */
sealed interface JsonValue {

    data class Obj(val entries: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val value: Double) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** 取子节点：路径上任一步缺失都返回 null，调用方不必层层判空。 */
fun JsonValue?.field(name: String): JsonValue? = (this as? JsonValue.Obj)?.entries?.get(name)

fun JsonValue?.str(name: String): String? = field(name)?.let { (it as? JsonValue.Str)?.value }

/**
 * 按数字读字段。
 *
 * 单独有这一个（而不只有 [long]）是因为有的接口把时长给成浮点秒
 * （LRCLIB 的 `duration` 实测是 `299.360658`），取整之后再算会丢掉有用的精度。
 */
fun JsonValue?.num(name: String): Double? = field(name)?.let {
    when (it) {
        is JsonValue.Num -> it.value
        is JsonValue.Str -> it.value.toDoubleOrNull()
        else -> null
    }
}

fun JsonValue?.long(name: String): Long? = field(name)?.let {
    when (it) {
        is JsonValue.Num -> it.value.toLong()
        is JsonValue.Str -> it.value.toLongOrNull()
        else -> null
    }
}

fun JsonValue?.bool(name: String): Boolean? = field(name)?.let { (it as? JsonValue.Bool)?.value }

fun JsonValue?.array(name: String): List<JsonValue> =
    (field(name) as? JsonValue.Arr)?.items ?: emptyList()

/** 把节点当字符串读：数字也接受（接口里 id 有时是数字、有时被包成字符串）。 */
fun JsonValue?.asText(): String? = when (this) {
    is JsonValue.Str -> value
    is JsonValue.Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    else -> null
}

object MiniJson {

    /** 解析失败一律返回 null：歌词是锦上添花的功能，不能因为一份脏响应就崩。 */
    fun parse(text: String): JsonValue? = runCatching { Parser(text).parseTop() }.getOrNull()

    private class Parser(private val src: String) {

        private var i = 0

        fun parseTop(): JsonValue {
            val value = parseValue()
            skipWhitespace()
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (i >= src.length) fail("输入意外结束")
            return when (val c = src[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> {
                    if (c == '-' || c == '+' || c in '0'..'9') parseNumber() else fail("意外的字符 '$c'")
                }
            }
        }

        private fun parseObject(): JsonValue.Obj {
            expect('{')
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return JsonValue.Obj(map)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonValue.Obj(map)
                    }

                    else -> fail("对象里缺少 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonValue.Arr(items)
                    }

                    else -> fail("数组里缺少 ',' 或 ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= src.length) fail("字符串没有闭合")
                when (val c = src[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }

                    '\\' -> {
                        i++
                        if (i >= src.length) fail("转义符后面没有内容")
                        when (val e = src[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= src.length) fail("\\u 转义不完整")
                                val hex = src.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("非法的 \\u 转义 '$hex'")
                                sb.append(code.toChar())
                                i += 4
                            }

                            else -> fail("不支持的转义 '\\$e'")
                        }
                        i++
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = i
            if (peek() == '-' || peek() == '+') i++
            while (i < src.length && (src[i] in '0'..'9' || src[i] == '.' ||
                        src[i] == 'e' || src[i] == 'E' || src[i] == '-' || src[i] == '+')
            ) {
                i++
            }
            val raw = src.substring(start, i)
            val value = raw.toDoubleOrNull() ?: fail("非法数字 '$raw'")
            return JsonValue.Num(value)
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!src.startsWith(word, i)) fail("期望 '$word'")
            i += word.length
            return value
        }

        private fun skipWhitespace() {
            while (i < src.length && (src[i] == ' ' || src[i] == '\t' || src[i] == '\n' || src[i] == '\r')) i++
        }

        private fun peek(): Char = if (i < src.length) src[i] else '\u0000'

        private fun expect(c: Char) {
            skipWhitespace()
            if (peek() != c) fail("期望 '$c' 但遇到 '${peek()}'")
            i++
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("JSON 解析失败（偏移 $i）：$message")
    }
}
