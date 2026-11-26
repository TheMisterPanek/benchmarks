package app;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;

/**
 * Zero-reflection, RFC-8259 compliant, thread-local buffered JSON codec.
 * Designed for maximum throughput on both HotSpot JIT and GraalVM Native Image AOT.
 */
public final class JsonCodec {

    private static final byte[] FIELD_ID = "{\"id\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIELD_NAME = ",\"name\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIELD_ADDRESS = "\",\"address\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIELD_PHONE = "\",\"phone\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIELD_IMAGE = "\",\"image\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIELD_CREATED_AT = "\",\"createdAt\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIELD_UPDATED_AT = "\",\"updatedAt\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIELD_ERROR_PREFIX = "{\"message\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private static final ThreadLocal<ByteWriter> WRITER = ThreadLocal.withInitial(ByteWriter::new);

    private JsonCodec() {
    }

    public static Buffer encodeUsers(User[] users) {
        ByteWriter w = WRITER.get();
        w.reset();
        w.writeByte((byte) '[');
        for (int i = 0; i < users.length; i++) {
            if (i > 0) {
                w.writeByte((byte) ',');
            }
            writeUser(w, users[i]);
        }
        w.writeByte((byte) ']');
        return w.toBuffer();
    }

    public static Buffer encodeUser(User user) {
        ByteWriter w = WRITER.get();
        w.reset();
        writeUser(w, user);
        return w.toBuffer();
    }

    public static Buffer encodeError(String message) {
        ByteWriter w = WRITER.get();
        w.reset();
        w.writeBytes(FIELD_ERROR_PREFIX);
        w.writeEscapedString(message != null ? message : "error");
        w.writeByte((byte) '"');
        w.writeByte((byte) '}');
        return w.toBuffer();
    }

    private static void writeUser(ByteWriter w, User u) {
        w.writeBytes(FIELD_ID);
        w.writeInt(u.id());
        w.writeBytes(FIELD_NAME);
        w.writeEscapedString(u.name());
        w.writeBytes(FIELD_ADDRESS);
        w.writeEscapedString(u.address());
        w.writeBytes(FIELD_PHONE);
        w.writeEscapedString(u.phone());
        w.writeBytes(FIELD_IMAGE);
        w.writeEscapedString(u.image());
        if (u.createdAt() != null) {
            w.writeBytes(FIELD_CREATED_AT);
            w.writeIsoUtc(u.createdAt());
        }
        if (u.updatedAt() != null) {
            w.writeBytes(FIELD_UPDATED_AT);
            w.writeIsoUtc(u.updatedAt());
        }
        w.writeByte((byte) '"');
        w.writeByte((byte) '}');
    }

    public static CreateUserRequest decodeCreateUserRequest(Buffer buf) {
        int len = buf.length();
        if (len == 0) {
            throw new IllegalArgumentException("Empty JSON body");
        }
        Parser p = new Parser(buf, len);
        p.skipWhitespace();
        if (p.pos >= len || p.peek() != '{') {
            throw new IllegalArgumentException("Expected JSON object");
        }
        p.pos++;

        String name = null;
        String address = null;
        String phone = null;

        p.skipWhitespace();
        if (p.pos < len && p.peek() == '}') {
            p.pos++;
            p.expectEnd();
            return new CreateUserRequest(null, null, null);
        }

        while (true) {
            p.skipWhitespace();
            String key = p.readString();
            p.skipWhitespace();
            p.expect(':');
            p.skipWhitespace();

            if ("name".equals(key)) {
                name = p.readNullableString();
            } else if ("address".equals(key)) {
                address = p.readNullableString();
            } else if ("phone".equals(key)) {
                phone = p.readNullableString();
            } else {
                p.skipValue();
            }

            p.skipWhitespace();
            if (p.pos >= len) {
                throw new IllegalArgumentException("Unexpected end of JSON object");
            }
            byte ch = p.peek();
            if (ch == ',') {
                p.pos++;
            } else if (ch == '}') {
                p.pos++;
                break;
            } else {
                throw new IllegalArgumentException("Expected ',' or '}' in JSON object");
            }
        }

        p.expectEnd();
        return new CreateUserRequest(name, address, phone);
    }

    private static final class ByteWriter {
        private byte[] buf = new byte[512];
        private int pos;

        void reset() {
            pos = 0;
        }

        Buffer toBuffer() {
            return Buffer.buffer(Arrays.copyOf(buf, pos));
        }

        private void ensureCapacity(int additional) {
            int needed = pos + additional;
            if (needed > buf.length) {
                int newLen = Math.max(buf.length << 1, needed);
                buf = Arrays.copyOf(buf, newLen);
            }
        }

        void writeByte(byte b) {
            ensureCapacity(1);
            buf[pos++] = b;
        }

        void writeBytes(byte[] src) {
            int len = src.length;
            ensureCapacity(len);
            System.arraycopy(src, 0, buf, pos, len);
            pos += len;
        }

        void writeInt(int value) {
            if (value == 0) {
                writeByte((byte) '0');
                return;
            }
            if (value == Integer.MIN_VALUE) {
                writeAscii("-2147483648");
                return;
            }
            ensureCapacity(11);
            if (value < 0) {
                buf[pos++] = '-';
                value = -value;
            }
            int start = pos;
            while (value > 0) {
                int q = value / 10;
                buf[pos++] = (byte) ('0' + (value - q * 10));
                value = q;
            }
            reverse(start, pos - 1);
        }

        void writeAscii(String s) {
            int len = s.length();
            ensureCapacity(len);
            for (int i = 0; i < len; i++) {
                buf[pos++] = (byte) s.charAt(i);
            }
        }

        void writeEscapedString(String s) {
            if (s == null) {
                return;
            }
            int len = s.length();
            ensureCapacity(len);
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                if (c >= 0x20 && c < 0x80 && c != '"' && c != '\\') {
                    if (pos == buf.length) {
                        ensureCapacity(len - i);
                    }
                    buf[pos++] = (byte) c;
                } else {
                    writeEscapedOrUtf8Char(s, i, c);
                    if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                        i++;
                    }
                }
            }
        }

        private void writeEscapedOrUtf8Char(String s, int i, char c) {
            switch (c) {
                case '"' -> { ensureCapacity(2); buf[pos++] = '\\'; buf[pos++] = '"'; }
                case '\\' -> { ensureCapacity(2); buf[pos++] = '\\'; buf[pos++] = '\\'; }
                case '\b' -> { ensureCapacity(2); buf[pos++] = '\\'; buf[pos++] = 'b'; }
                case '\f' -> { ensureCapacity(2); buf[pos++] = '\\'; buf[pos++] = 'f'; }
                case '\n' -> { ensureCapacity(2); buf[pos++] = '\\'; buf[pos++] = 'n'; }
                case '\r' -> { ensureCapacity(2); buf[pos++] = '\\'; buf[pos++] = 'r'; }
                case '\t' -> { ensureCapacity(2); buf[pos++] = '\\'; buf[pos++] = 't'; }
                default -> {
                    if (c < 0x20) {
                        ensureCapacity(6);
                        buf[pos++] = '\\';
                        buf[pos++] = 'u';
                        buf[pos++] = '0';
                        buf[pos++] = '0';
                        buf[pos++] = HEX[(c >> 4) & 0xF];
                        buf[pos++] = HEX[c & 0xF];
                    } else if (c < 0x800) {
                        ensureCapacity(2);
                        buf[pos++] = (byte) (0xC0 | (c >> 6));
                        buf[pos++] = (byte) (0x80 | (c & 0x3F));
                    } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        int cp = Character.toCodePoint(c, s.charAt(i + 1));
                        ensureCapacity(4);
                        buf[pos++] = (byte) (0xF0 | (cp >> 18));
                        buf[pos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                        buf[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                        buf[pos++] = (byte) (0x80 | (cp & 0x3F));
                    } else {
                        ensureCapacity(3);
                        buf[pos++] = (byte) (0xE0 | (c >> 12));
                        buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                        buf[pos++] = (byte) (0x80 | (c & 0x3F));
                    }
                }
            }
        }

        void writeIsoUtc(OffsetDateTime dt) {
            ensureCapacity(32);
            write4Digits(dt.getYear());
            buf[pos++] = '-';
            write2Digits(dt.getMonthValue());
            buf[pos++] = '-';
            write2Digits(dt.getDayOfMonth());
            buf[pos++] = 'T';
            write2Digits(dt.getHour());
            buf[pos++] = ':';
            write2Digits(dt.getMinute());
            buf[pos++] = ':';
            write2Digits(dt.getSecond());
            int nano = dt.getNano();
            if (nano != 0) {
                buf[pos++] = '.';
                int start = pos;
                int div = 100_000_000;
                for (int i = 0; i < 9; i++) {
                    int digit = nano / div;
                    buf[pos++] = (byte) ('0' + digit);
                    nano -= digit * div;
                    div /= 10;
                }
                while (pos > start && buf[pos - 1] == '0') {
                    pos--;
                }
            }
            buf[pos++] = 'Z';
        }

        private void write2Digits(int v) {
            buf[pos++] = (byte) ('0' + (v / 10));
            buf[pos++] = (byte) ('0' + (v % 10));
        }

        private void write4Digits(int v) {
            buf[pos++] = (byte) ('0' + ((v / 1000) % 10));
            buf[pos++] = (byte) ('0' + ((v / 100) % 10));
            buf[pos++] = (byte) ('0' + ((v / 10) % 10));
            buf[pos++] = (byte) ('0' + (v % 10));
        }

        private void reverse(int left, int right) {
            while (left < right) {
                byte tmp = buf[left];
                buf[left++] = buf[right];
                buf[right--] = tmp;
            }
        }
    }

    private static final class Parser {
        private final Buffer buf;
        private final int len;
        int pos;

        Parser(Buffer buf, int len) {
            this.buf = buf;
            this.len = len;
        }

        byte peek() {
            return buf.getByte(pos);
        }

        void skipWhitespace() {
            while (pos < len) {
                byte b = buf.getByte(pos);
                if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        void expect(char expected) {
            if (pos >= len || buf.getByte(pos) != (byte) expected) {
                throw new IllegalArgumentException("Expected '" + expected + "'");
            }
            pos++;
        }

        void expectEnd() {
            skipWhitespace();
            if (pos != len) {
                throw new IllegalArgumentException("Unexpected trailing characters in JSON");
            }
        }

        String readNullableString() {
            if (pos < len && peek() == 'n') {
                expectLiteral("null");
                return null;
            }
            return readString();
        }

        String readString() {
            if (pos >= len || buf.getByte(pos) != '"') {
                throw new IllegalArgumentException("Expected JSON string");
            }
            pos++;
            int start = pos;
            boolean hasEscapeOrNonAscii = false;
            while (pos < len) {
                byte b = buf.getByte(pos);
                if (b == '"') {
                    if (!hasEscapeOrNonAscii) {
                        String result = buf.getString(start, pos, "ISO-8859-1");
                        pos++;
                        return result;
                    }
                    break;
                }
                if (b == '\\' || b < 0) {
                    hasEscapeOrNonAscii = true;
                    break;
                }
                if (b < 0x20) {
                    throw new IllegalArgumentException("Unescaped control character in JSON string");
                }
                pos++;
            }

            if (!hasEscapeOrNonAscii) {
                throw new IllegalArgumentException("Unterminated JSON string");
            }

            // Slow path for escaped characters or multi-byte UTF-8
            pos = start;
            StringBuilder sb = new StringBuilder(32);
            while (pos < len) {
                byte b = buf.getByte(pos++);
                if (b == '"') {
                    return sb.toString();
                }
                if (b == '\\') {
                    if (pos >= len) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    byte esc = buf.getByte(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            int code = (hexVal(buf.getByte(pos++)) << 12)
                                | (hexVal(buf.getByte(pos++)) << 8)
                                | (hexVal(buf.getByte(pos++)) << 4)
                                | hexVal(buf.getByte(pos++));
                            sb.append((char) code);
                        }
                        default -> throw new IllegalArgumentException("Invalid escape character");
                    }
                } else if (b >= 0) {
                    if (b < 0x20) {
                        throw new IllegalArgumentException("Unescaped control character in JSON string");
                    }
                    sb.append((char) b);
                } else {
                    int b0 = b & 0xFF;
                    if ((b0 & 0xE0) == 0xC0) {
                        int b1 = readContinuation();
                        sb.append((char) (((b0 & 0x1F) << 6) | (b1 & 0x3F)));
                    } else if ((b0 & 0xF0) == 0xE0) {
                        int b1 = readContinuation();
                        int b2 = readContinuation();
                        sb.append((char) (((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F)));
                    } else if ((b0 & 0xF8) == 0xF0) {
                        int b1 = readContinuation();
                        int b2 = readContinuation();
                        int b3 = readContinuation();
                        int cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                        sb.appendCodePoint(cp);
                    } else {
                        throw new IllegalArgumentException("Invalid UTF-8 byte sequence");
                    }
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private int readContinuation() {
            if (pos >= len) {
                throw new IllegalArgumentException("Truncated UTF-8 sequence");
            }
            return buf.getByte(pos++) & 0xFF;
        }

        private static int hexVal(byte b) {
            if (b >= '0' && b <= '9') return b - '0';
            if (b >= 'a' && b <= 'f') return b - 'a' + 10;
            if (b >= 'A' && b <= 'F') return b - 'A' + 10;
            throw new IllegalArgumentException("Invalid hex digit");
        }

        void skipValue() {
            skipWhitespace();
            if (pos >= len) {
                throw new IllegalArgumentException("Unexpected end of JSON value");
            }
            byte b = peek();
            switch (b) {
                case '"' -> readString();
                case '{' -> {
                    pos++;
                    skipWhitespace();
                    if (pos < len && peek() == '}') {
                        pos++;
                        return;
                    }
                    while (true) {
                        skipWhitespace();
                        readString();
                        skipWhitespace();
                        expect(':');
                        skipValue();
                        skipWhitespace();
                        if (pos >= len) throw new IllegalArgumentException("Unterminated object");
                        byte next = peek();
                        if (next == ',') pos++;
                        else if (next == '}') { pos++; return; }
                        else throw new IllegalArgumentException("Invalid object syntax");
                    }
                }
                case '[' -> {
                    pos++;
                    skipWhitespace();
                    if (pos < len && peek() == ']') {
                        pos++;
                        return;
                    }
                    while (true) {
                        skipValue();
                        skipWhitespace();
                        if (pos >= len) throw new IllegalArgumentException("Unterminated array");
                        byte next = peek();
                        if (next == ',') pos++;
                        else if (next == ']') { pos++; return; }
                        else throw new IllegalArgumentException("Invalid array syntax");
                    }
                }
                case 't' -> expectLiteral("true");
                case 'f' -> expectLiteral("false");
                case 'n' -> expectLiteral("null");
                default -> skipNumber();
            }
        }

        private void expectLiteral(String lit) {
            int l = lit.length();
            if (pos + l > len) {
                throw new IllegalArgumentException("Unexpected end of JSON literal");
            }
            for (int i = 0; i < l; i++) {
                if (buf.getByte(pos++) != (byte) lit.charAt(i)) {
                    throw new IllegalArgumentException("Invalid JSON literal");
                }
            }
        }

        private void skipNumber() {
            int start = pos;
            if (pos < len && buf.getByte(pos) == '-') pos++;
            while (pos < len) {
                byte b = buf.getByte(pos);
                if ((b >= '0' && b <= '9') || b == '.' || b == 'e' || b == 'E' || b == '+' || b == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw new IllegalArgumentException("Invalid JSON value");
            }
        }
    }
}
