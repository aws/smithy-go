package software.amazon.smithy.go.codegen;

import java.nio.charset.StandardCharsets;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * The hash that name lookup tables of frozen schemas are built with.
 *
 * <p>This MUST produce the same results as staticNameHash in schema_static.go of the Go runtime. Both are checked
 * against the same golden values. Names are ASCII, so the bytes of a name are its characters.
 */
@SmithyInternalApi
final class StaticNameHash {
    private static final long K0 = 0x9E3779B97F4A7C15L;
    private static final long K1 = 0xA0761D6478BD642FL;

    private static final int BLOCK = 16;
    private static final int WORD = 8;
    private static final int HALF_WORD = 4;
    private static final int LONG_BITS = 63;

    private StaticNameHash() {
    }

    static long hash(String name, long seed) {
        var s = name.getBytes(StandardCharsets.UTF_8);
        int n = s.length;
        long h = seed ^ ((long) n * K0);

        long a = 0;
        long b = 0;
        if (n > BLOCK) {
            int i = 0;
            for (; n - i > BLOCK; i += BLOCK) {
                h = mix(le64(s, i) ^ K1, le64(s, i + WORD) ^ h);
            }
            a = le64(s, n - BLOCK);
            b = le64(s, n - WORD);
        } else if (n >= WORD) {
            a = le64(s, 0);
            b = le64(s, n - WORD);
        } else if (n >= HALF_WORD) {
            a = le32(s, 0);
            b = le32(s, n - HALF_WORD);
        } else if (n > 0) {
            a = (u8(s, 0) << 16) | (u8(s, n >> 1) << 8) | u8(s, n - 1);
        }
        return mix(a ^ K1, b ^ h);
    }

    // The high and low halves of the unsigned 128-bit product, xor'ed together.
    private static long mix(long a, long b) {
        long hi = Math.multiplyHigh(a, b) + ((a >> LONG_BITS) & b) + ((b >> LONG_BITS) & a);
        return hi ^ (a * b);
    }

    private static long u8(byte[] s, int i) {
        return s[i] & 0xFFL;
    }

    private static long le32(byte[] s, int i) {
        return u8(s, i) | u8(s, i + 1) << 8 | u8(s, i + 2) << 16 | u8(s, i + 3) << 24;
    }

    private static long le64(byte[] s, int i) {
        return le32(s, i) | le32(s, i + HALF_WORD) << 32;
    }
}
