package com.example.gsb.postings;

/**
 * 变长整数（VByte / LEB128 风格）无符号编码，仅支持非负 int。
 *
 * <p>编码规则：
 * <ul>
 *   <li>每个字节只用低 {@code 7} 位存放数据（7-bit group），最高位（continuation bit）
 *       作为续传标志：{@code 1} 表示后面还有字节，{@code 0} 表示这是最后一个字节。</li>
 *   <li>数值按小端组序写出：最低的 7 位组先写，随后逐组写更高位。</li>
 *   <li>例如 {@code 300 = 0b10_0101100}，拆成两个组 {@code 0101100}（低组）与
 *       {@code 0000010}（高组），编码字节为 {@code [0xAC, 0x02]}
 *       （低组最高位置 1：{@code 0101100 | 10000000 = 10101100}；高组最高位为 0）。</li>
 *   <li>数值区间与字节数：{@code [0, 2^7)} → 1 字节，{@code [2^7, 2^14)} → 2 字节，
 *       {@code [2^14, 2^21)} → 3 字节，{@code [2^21, 2^28)} → 4 字节，
 *       最大 {@code 2^32-1} 需要 5 字节。</li>
 * </ul>
 *
 * <p>倒排表保存的是相邻文档号的差值（gap），gap 通常很小，因此该编码能显著压缩
 * 存储（见 {@link CompressedPostings}）。
 */
public final class VarIntCodec {

    private VarIntCodec() {
    }

    /** 返回 value 编码后的字节数，value 必须为非负整数。 */
    public static int encodedLength(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        int bits = 32 - Integer.numberOfLeadingZeros(value);
        return Math.max(1, (bits + 6) / 7);
    }

    /** 把 value 编码到 buf 的 offset 位置，返回写入的字节数。 */
    public static int encode(int value, byte[] buf, int offset) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        int written = 0;
        while ((value & ~0x7F) != 0) {
            buf[offset + written] = (byte) ((value & 0x7F) | 0x80);
            written++;
            value >>>= 7;
        }
        buf[offset + written] = (byte) value;
        return written + 1;
    }

    /** 从 buf 的 offset 处解码一个变长整数，返回数值。 */
    public static int decode(byte[] buf, int offset) {
        int value = 0;
        int shift = 0;
        int pos = offset;
        while (true) {
            int b = buf[pos++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    /** 从 buf 的 offset 处解码，数组 {数值, 下一个字节的偏移}，供需要连续解码的调用方使用。 */
    public static int[] decodeWithNextOffset(byte[] buf, int offset) {
        int value = 0;
        int shift = 0;
        int pos = offset;
        while (true) {
            int b = buf[pos++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[] {value, pos};
            }
            shift += 7;
        }
    }
}
