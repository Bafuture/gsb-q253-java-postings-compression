package com.example.gsb.postings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarIntTest {

    @Test
    void encodesKnownValues() {
        assertRoundTrip(0, 0x00);
        assertRoundTrip(1, 0x01);
        assertRoundTrip(127, 0x7F);
        assertRoundTrip(128, 0x80, 0x01);
        assertRoundTrip(300, 0xAC, 0x02);
        assertRoundTrip(16384, 0x80, 0x80, 0x01);
        assertRoundTrip(Integer.MAX_VALUE,
                0xFF, 0xFF, 0xFF, 0xFF, 0x07);
    }

    @Test
    void sizeMatchesEncoding() {
        int[] values = {0, 1, 127, 128, 16383, 16384, 2097151, 2097152, Integer.MAX_VALUE};
        int[] expectedSizes = {1, 1, 1, 2, 2, 3, 3, 4, 5};
        for (int i = 0; i < values.length; i++) {
            byte[] buf = new byte[5];
            int written = VarInt.encode(values[i], buf, 0);
            assertThat(written).isEqualTo(expectedSizes[i]);
            assertThat(VarInt.encodedSize(values[i])).isEqualTo(written);
        }
    }

    @Test
    void randomRoundTrip() {
        java.util.Random rnd = new java.util.Random(42);
        for (int t = 0; t < 100_000; t++) {
            int value = rnd.nextInt(Integer.MAX_VALUE);
            byte[] buf = new byte[5];
            int n = VarInt.encode(value, buf, 0);
            int[] decoded = VarInt.decode(buf, 0);
            assertThat(decoded[0]).isEqualTo(value);
            assertThat(decoded[1]).isEqualTo(n);
        }
    }

    @Test
    void encodesSequentiallyWithOffsets() {
        int[] values = {0, 128, 300, 16384, Integer.MAX_VALUE};
        byte[] buf = new byte[64];
        int pos = 0;
        for (int v : values) {
            pos += VarInt.encode(v, buf, pos);
        }
        int offset = 0;
        for (int v : values) {
            int[] r = VarInt.decode(buf, offset);
            assertThat(r[0]).isEqualTo(v);
            offset += r[1];
        }
        assertThat(offset).isEqualTo(pos);
    }

    @Test
    void rejectsNegativeAndTruncatedInput() {
        assertThatThrownBy(() -> VarInt.encode(-1, new byte[5], 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VarInt.decode(new byte[]{(byte) 0x80}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRoundTrip(int value, int... expectedBytes) {
        byte[] buf = new byte[5];
        int n = VarInt.encode(value, buf, 0);
        assertThat(n).isEqualTo(expectedBytes.length);
        for (int i = 0; i < n; i++) {
            assertThat(buf[i] & 0xFF).isEqualTo(expectedBytes[i]);
        }
        int[] decoded = VarInt.decode(buf, 0);
        assertThat(decoded[0]).isEqualTo(value);
        assertThat(decoded[1]).isEqualTo(n);
    }
}
