package com.example.gsb.postings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class VarIntCodecTest {

    @Test
    void boundaryValuesRoundTrip() {
        int[] values = {
            0, 1, 2, 126, 127, 128, 129, 255, 256, 300,
            16383, 16384, 2097151, 2097152, 268435455, 268435456,
            Integer.MAX_VALUE
        };
        byte[] buf = new byte[8];
        for (int value : values) {
            int written = VarIntCodec.encode(value, buf, 0);
            assertThat(VarIntCodec.encodedLength(value)).isEqualTo(written);
            int decoded = VarIntCodec.decode(buf, 0);
            assertThat(decoded).isEqualTo(value);
            int[] withOffset = VarIntCodec.decodeWithNextOffset(buf, 0);
            assertThat(withOffset[0]).isEqualTo(value);
            assertThat(withOffset[1]).isEqualTo(written);
        }
    }

    @Test
    void encodedByteLengthsFollow7BitGroups() {
        assertThat(VarIntCodec.encodedLength(0)).isEqualTo(1);
        assertThat(VarIntCodec.encodedLength(127)).isEqualTo(1);
        assertThat(VarIntCodec.encodedLength(128)).isEqualTo(2);
        assertThat(VarIntCodec.encodedLength(16383)).isEqualTo(2);
        assertThat(VarIntCodec.encodedLength(16384)).isEqualTo(3);
        assertThat(VarIntCodec.encodedLength(2097151)).isEqualTo(3);
        assertThat(VarIntCodec.encodedLength(2097152)).isEqualTo(4);
        assertThat(VarIntCodec.encodedLength(268435455)).isEqualTo(4);
        assertThat(VarIntCodec.encodedLength(268435456)).isEqualTo(5);
        assertThat(VarIntCodec.encodedLength(Integer.MAX_VALUE)).isEqualTo(5);
    }

    @Test
    void concreteByteLayout() {
        byte[] buf = new byte[5];
        // 1 -> 单字节，最高位为 0
        VarIntCodec.encode(1, buf, 0);
        assertThat(buf[0]).isEqualTo((byte) 0x01);
        // 128 = 低组 0 + 续传，高组 1
        int written = VarIntCodec.encode(128, buf, 0);
        assertThat(written).isEqualTo(2);
        assertThat(buf[0]).isEqualTo((byte) 0x80);
        assertThat(buf[1]).isEqualTo((byte) 0x01);
        // 300 = [0xAC, 0x02]
        VarIntCodec.encode(300, buf, 0);
        assertThat(buf[0]).isEqualTo((byte) 0xAC);
        assertThat(buf[1]).isEqualTo((byte) 0x02);
        // 连续写入不互相覆盖
        int w1 = VarIntCodec.encode(300, buf, 0);
        int w2 = VarIntCodec.encode(1, buf, w1);
        assertThat(w1 + w2).isEqualTo(3);
        assertThat(VarIntCodec.decode(buf, w1)).isEqualTo(1);
    }

    @Test
    void largeRandomRoundTrip() {
        Random random = new Random(20240921L);
        byte[] buf = new byte[16];
        for (int i = 0; i < 100_000; i++) {
            int value;
            switch (random.nextInt(4)) {
                case 0 -> value = random.nextInt(256);
                case 1 -> value = random.nextInt(1 << 14);
                case 2 -> value = random.nextInt(1 << 21);
                default -> value = random.nextInt() & Integer.MAX_VALUE;
            }
            int written = VarIntCodec.encode(value, buf, 0);
            int[] decoded = VarIntCodec.decodeWithNextOffset(buf, 0);
            assertThat(decoded[0]).isEqualTo(value);
            assertThat(decoded[1]).isEqualTo(written);
        }
    }

    @Test
    void rejectsNegativeValues() {
        byte[] buf = new byte[8];
        assertThatThrownBy(() -> VarIntCodec.encode(-1, buf, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VarIntCodec.encodedLength(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
