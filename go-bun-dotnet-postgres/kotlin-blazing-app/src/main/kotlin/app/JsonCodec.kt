package app

import io.vertx.core.buffer.Buffer
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

object JsonCodec {
    private val FIELD_ID = "{\"id\":".toByteArray(StandardCharsets.US_ASCII)
    private val FIELD_NAME = ",\"name\":\"".toByteArray(StandardCharsets.US_ASCII)
    private val FIELD_ADDRESS = "\",\"address\":\"".toByteArray(StandardCharsets.US_ASCII)
    private val FIELD_PHONE = "\",\"phone\":\"".toByteArray(StandardCharsets.US_ASCII)
    private val FIELD_IMAGE = "\",\"image\":\"".toByteArray(StandardCharsets.US_ASCII)
    private val FIELD_CREATED_AT = "\",\"createdAt\":\"".toByteArray(StandardCharsets.US_ASCII)
    private val FIELD_UPDATED_AT = "\",\"updatedAt\":\"".toByteArray(StandardCharsets.US_ASCII)
    private val FIELD_ERROR_PREFIX = "{\"message\":\"".toByteArray(StandardCharsets.US_ASCII)
    private val HEX = "0123456789abcdef".toByteArray(StandardCharsets.US_ASCII)

    private val WRITER = ThreadLocal.withInitial { ByteWriter() }

    fun encodeUsers(users: Array<User>): Buffer {
        val w = WRITER.get()
        w.reset()
        w.writeByte('['.code.toByte())
        for (i in users.indices) {
            if (i > 0) w.writeByte(','.code.toByte())
            writeUser(w, users[i])
        }
        w.writeByte(']'.code.toByte())
        return w.toBuffer()
    }

    fun encodeUser(user: User): Buffer {
        val w = WRITER.get()
        w.reset()
        writeUser(w, user)
        return w.toBuffer()
    }

    fun encodeError(message: String?): Buffer {
        val w = WRITER.get()
        w.reset()
        w.writeBytes(FIELD_ERROR_PREFIX)
        w.writeEscapedString(message ?: "error")
        w.writeByte('"'.code.toByte())
        w.writeByte('}'.code.toByte())
        return w.toBuffer()
    }

    private fun writeUser(w: ByteWriter, u: User) {
        w.writeBytes(FIELD_ID)
        w.writeInt(u.id)
        w.writeBytes(FIELD_NAME)
        w.writeEscapedString(u.name)
        w.writeBytes(FIELD_ADDRESS)
        w.writeEscapedString(u.address)
        w.writeBytes(FIELD_PHONE)
        w.writeEscapedString(u.phone)
        w.writeBytes(FIELD_IMAGE)
        w.writeEscapedString(u.image)
        if (u.createdAt != null) {
            w.writeBytes(FIELD_CREATED_AT)
            w.writeIsoUtc(u.createdAt)
        }
        if (u.updatedAt != null) {
            w.writeBytes(FIELD_UPDATED_AT)
            w.writeIsoUtc(u.updatedAt)
        }
        w.writeByte('"'.code.toByte())
        w.writeByte('}'.code.toByte())
    }

    fun decodeCreateUserRequest(buf: Buffer): CreateUserRequest {
        val len = buf.length()
        if (len == 0) throw IllegalArgumentException("Empty JSON body")
        val p = Parser(buf, len)
        p.skipWhitespace()
        if (p.pos >= len || p.peek() != '{'.code.toByte()) {
            throw IllegalArgumentException("Expected JSON object")
        }
        p.pos++

        var name: String? = null
        var address: String? = null
        var phone: String? = null

        p.skipWhitespace()
        if (p.pos < len && p.peek() == '}'.code.toByte()) {
            p.pos++
            p.expectEnd()
            return CreateUserRequest(null, null, null)
        }

        while (true) {
            p.skipWhitespace()
            val key = p.readString()
            p.skipWhitespace()
            p.expect(':')
            p.skipWhitespace()

            when (key) {
                "name" -> name = p.readNullableString()
                "address" -> address = p.readNullableString()
                "phone" -> phone = p.readNullableString()
                else -> p.skipValue()
            }

            p.skipWhitespace()
            if (p.pos >= len) throw IllegalArgumentException("Unexpected end of JSON object")
            when (p.peek().toInt().toChar()) {
                ',' -> p.pos++
                '}' -> {
                    p.pos++
                    break
                }
                else -> throw IllegalArgumentException("Expected ',' or '}' in JSON object")
            }
        }

        p.expectEnd()
        return CreateUserRequest(name, address, phone)
    }

    private class ByteWriter {
        private var buf = ByteArray(512)
        private var pos = 0

        fun reset() {
            pos = 0
        }

        fun toBuffer(): Buffer = Buffer.buffer(buf.copyOf(pos))

        private fun ensureCapacity(additional: Int) {
            val needed = pos + additional
            if (needed > buf.size) {
                buf = buf.copyOf(maxOf(buf.size shl 1, needed))
            }
        }

        fun writeByte(b: Byte) {
            ensureCapacity(1)
            buf[pos++] = b
        }

        fun writeBytes(src: ByteArray) {
            val len = src.size
            ensureCapacity(len)
            System.arraycopy(src, 0, buf, pos, len)
            pos += len
        }

        fun writeInt(v: Int) {
            if (v == 0) {
                writeByte('0'.code.toByte())
                return
            }
            if (v == Int.MIN_VALUE) {
                writeAscii("-2147483648")
                return
            }
            ensureCapacity(11)
            var value = v
            if (value < 0) {
                buf[pos++] = '-'.code.toByte()
                value = -value
            }
            val start = pos
            while (value > 0) {
                val q = value / 10
                buf[pos++] = ('0'.code + (value - q * 10)).toByte()
                value = q
            }
            reverse(start, pos - 1)
        }

        private fun writeAscii(s: String) {
            val len = s.length
            ensureCapacity(len)
            for (i in 0 until len) {
                buf[pos++] = s[i].code.toByte()
            }
        }

        fun writeEscapedString(s: String?) {
            if (s == null) return
            val len = s.length
            ensureCapacity(len)
            var i = 0
            while (i < len) {
                val c = s[i]
                if (c.code in 0x20..0x7F && c != '"' && c != '\\') {
                    if (pos == buf.size) ensureCapacity(len - i)
                    buf[pos++] = c.code.toByte()
                } else {
                    writeEscapedOrUtf8Char(s, i, c)
                    if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s[i + 1])) {
                        i++
                    }
                }
                i++
            }
        }

        private fun writeEscapedOrUtf8Char(s: String, i: Int, c: Char) {
            when (c) {
                '"' -> { ensureCapacity(2); buf[pos++] = '\\'.code.toByte(); buf[pos++] = '"'.code.toByte() }
                '\\' -> { ensureCapacity(2); buf[pos++] = '\\'.code.toByte(); buf[pos++] = '\\'.code.toByte() }
                '\b' -> { ensureCapacity(2); buf[pos++] = '\\'.code.toByte(); buf[pos++] = 'b'.code.toByte() }
                '\u000C' -> { ensureCapacity(2); buf[pos++] = '\\'.code.toByte(); buf[pos++] = 'f'.code.toByte() }
                '\n' -> { ensureCapacity(2); buf[pos++] = '\\'.code.toByte(); buf[pos++] = 'n'.code.toByte() }
                '\r' -> { ensureCapacity(2); buf[pos++] = '\\'.code.toByte(); buf[pos++] = 'r'.code.toByte() }
                '\t' -> { ensureCapacity(2); buf[pos++] = '\\'.code.toByte(); buf[pos++] = 't'.code.toByte() }
                else -> {
                    val code = c.code
                    if (code < 0x20) {
                        ensureCapacity(6)
                        buf[pos++] = '\\'.code.toByte()
                        buf[pos++] = 'u'.code.toByte()
                        buf[pos++] = '0'.code.toByte()
                        buf[pos++] = '0'.code.toByte()
                        buf[pos++] = HEX[(code shr 4) and 0xF]
                        buf[pos++] = HEX[code and 0xF]
                    } else if (code < 0x800) {
                        ensureCapacity(2)
                        buf[pos++] = (0xC0 or (code shr 6)).toByte()
                        buf[pos++] = (0x80 or (code and 0x3F)).toByte()
                    } else if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        val cp = Character.toCodePoint(c, s[i + 1])
                        ensureCapacity(4)
                        buf[pos++] = (0xF0 or (cp shr 18)).toByte()
                        buf[pos++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                        buf[pos++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                        buf[pos++] = (0x80 or (cp and 0x3F)).toByte()
                    } else {
                        ensureCapacity(3)
                        buf[pos++] = (0xE0 or (code shr 12)).toByte()
                        buf[pos++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
                        buf[pos++] = (0x80 or (code and 0x3F)).toByte()
                    }
                }
            }
        }

        fun writeIsoUtc(dt: OffsetDateTime) {
            ensureCapacity(32)
            write4Digits(dt.year)
            buf[pos++] = '-'.code.toByte()
            write2Digits(dt.monthValue)
            buf[pos++] = '-'.code.toByte()
            write2Digits(dt.dayOfMonth)
            buf[pos++] = 'T'.code.toByte()
            write2Digits(dt.hour)
            buf[pos++] = ':'.code.toByte()
            write2Digits(dt.minute)
            buf[pos++] = ':'.code.toByte()
            write2Digits(dt.second)
            var nano = dt.nano
            if (nano != 0) {
                buf[pos++] = '.'.code.toByte()
                val start = pos
                var div = 100_000_000
                for (i in 0 until 9) {
                    val digit = nano / div
                    buf[pos++] = ('0'.code + digit).toByte()
                    nano -= digit * div
                    div /= 10
                }
                while (pos > start && buf[pos - 1] == '0'.code.toByte()) {
                    pos--
                }
            }
            buf[pos++] = 'Z'.code.toByte()
        }

        private fun write2Digits(v: Int) {
            buf[pos++] = ('0'.code + (v / 10)).toByte()
            buf[pos++] = ('0'.code + (v % 10)).toByte()
        }

        private fun write4Digits(v: Int) {
            buf[pos++] = ('0'.code + ((v / 1000) % 10)).toByte()
            buf[pos++] = ('0'.code + ((v / 100) % 10)).toByte()
            buf[pos++] = ('0'.code + ((v / 10) % 10)).toByte()
            buf[pos++] = ('0'.code + (v % 10)).toByte()
        }

        private fun reverse(l: Int, r: Int) {
            var left = l
            var right = r
            while (left < right) {
                val tmp = buf[left]
                buf[left++] = buf[right]
                buf[right--] = tmp
            }
        }
    }

    private class Parser(private val buf: Buffer, private val len: Int) {
        var pos = 0

        fun peek(): Byte = buf.getByte(pos)

        fun skipWhitespace() {
            while (pos < len) {
                val b = buf.getByte(pos).toInt()
                if (b == ' '.code || b == '\t'.code || b == '\r'.code || b == '\n'.code) {
                    pos++
                } else {
                    break
                }
            }
        }

        fun expect(expected: Char) {
            if (pos >= len || buf.getByte(pos) != expected.code.toByte()) {
                throw IllegalArgumentException("Expected '$expected'")
            }
            pos++
        }

        fun expectEnd() {
            skipWhitespace()
            if (pos != len) throw IllegalArgumentException("Unexpected trailing characters in JSON")
        }

        fun readNullableString(): String? {
            if (pos < len && peek() == 'n'.code.toByte()) {
                expectLiteral("null")
                return null
            }
            return readString()
        }

        fun readString(): String {
            if (pos >= len || buf.getByte(pos) != '"'.code.toByte()) {
                throw IllegalArgumentException("Expected JSON string")
            }
            pos++
            val start = pos
            var hasEscapeOrNonAscii = false
            while (pos < len) {
                val b = buf.getByte(pos)
                if (b == '"'.code.toByte()) {
                    if (!hasEscapeOrNonAscii) {
                        val res = buf.getString(start, pos, "ISO-8859-1")
                        pos++
                        return res
                    }
                    break
                }
                if (b == '\\'.code.toByte() || b < 0) {
                    hasEscapeOrNonAscii = true
                    break
                }
                if (b < 0x20) {
                    throw IllegalArgumentException("Unescaped control character in JSON string")
                }
                pos++
            }

            if (!hasEscapeOrNonAscii) throw IllegalArgumentException("Unterminated JSON string")

            pos = start
            val sb = StringBuilder(32)
            while (pos < len) {
                val b = buf.getByte(pos++)
                if (b == '"'.code.toByte()) return sb.toString()
                if (b == '\\'.code.toByte()) {
                    if (pos >= len) throw IllegalArgumentException("Unterminated escape sequence")
                    when (buf.getByte(pos++).toInt().toChar()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > len) throw IllegalArgumentException("Invalid unicode escape")
                            val code = (hexVal(buf.getByte(pos++)) shl 12) or
                                (hexVal(buf.getByte(pos++)) shl 8) or
                                (hexVal(buf.getByte(pos++)) shl 4) or
                                hexVal(buf.getByte(pos++))
                            sb.append(code.toChar())
                        }
                        else -> throw IllegalArgumentException("Invalid escape character")
                    }
                } else if (b >= 0) {
                    if (b < 0x20) throw IllegalArgumentException("Unescaped control character in JSON string")
                    sb.append(b.toInt().toChar())
                } else {
                    val b0 = b.toInt() and 0xFF
                    when {
                        (b0 and 0xE0) == 0xC0 -> {
                            val b1 = readContinuation()
                            sb.append((((b0 and 0x1F) shl 6) or (b1 and 0x3F)).toChar())
                        }
                        (b0 and 0xF0) == 0xE0 -> {
                            val b1 = readContinuation()
                            val b2 = readContinuation()
                            sb.append((((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)).toChar())
                        }
                        (b0 and 0xF8) == 0xF0 -> {
                            val b1 = readContinuation()
                            val b2 = readContinuation()
                            val b3 = readContinuation()
                            val cp = ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                            sb.appendCodePoint(cp)
                        }
                        else -> throw IllegalArgumentException("Invalid UTF-8 byte sequence")
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun readContinuation(): Int {
            if (pos >= len) throw IllegalArgumentException("Truncated UTF-8 sequence")
            return buf.getByte(pos++).toInt() and 0xFF
        }

        private fun hexVal(b: Byte): Int {
            val c = b.toInt().toChar()
            return when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> throw IllegalArgumentException("Invalid hex digit")
            }
        }

        fun skipValue() {
            skipWhitespace()
            if (pos >= len) throw IllegalArgumentException("Unexpected end of JSON value")
            when (peek().toInt().toChar()) {
                '"' -> readString()
                '{' -> {
                    pos++
                    skipWhitespace()
                    if (pos < len && peek() == '}'.code.toByte()) {
                        pos++
                        return
                    }
                    while (true) {
                        skipWhitespace()
                        readString()
                        skipWhitespace()
                        expect(':')
                        skipValue()
                        skipWhitespace()
                        if (pos >= len) throw IllegalArgumentException("Unterminated object")
                        when (peek().toInt().toChar()) {
                            ',' -> pos++
                            '}' -> { pos++; return }
                            else -> throw IllegalArgumentException("Invalid object syntax")
                        }
                    }
                }
                '[' -> {
                    pos++
                    skipWhitespace()
                    if (pos < len && peek() == ']'.code.toByte()) {
                        pos++
                        return
                    }
                    while (true) {
                        skipValue()
                        skipWhitespace()
                        if (pos >= len) throw IllegalArgumentException("Unterminated array")
                        when (peek().toInt().toChar()) {
                            ',' -> pos++
                            ']' -> { pos++; return }
                            else -> throw IllegalArgumentException("Invalid array syntax")
                        }
                    }
                }
                't' -> expectLiteral("true")
                'f' -> expectLiteral("false")
                'n' -> expectLiteral("null")
                else -> skipNumber()
            }
        }

        private fun expectLiteral(lit: String) {
            if (pos + lit.length > len) throw IllegalArgumentException("Unexpected end of JSON literal")
            for (i in lit.indices) {
                if (buf.getByte(pos++) != lit[i].code.toByte()) {
                    throw IllegalArgumentException("Invalid JSON literal")
                }
            }
        }

        private fun skipNumber() {
            val start = pos
            if (pos < len && buf.getByte(pos) == '-'.code.toByte()) pos++
            while (pos < len) {
                val c = buf.getByte(pos).toInt().toChar()
                if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++
                } else {
                    break
                }
            }
            if (pos == start) throw IllegalArgumentException("Invalid JSON value")
        }
    }
}
