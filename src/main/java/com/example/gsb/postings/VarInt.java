package com.example.gsb.postings;

/**
 * 无符号变长整数编码（LEB128，小端序 7 位分组）。
 *
 * <p>编码规则：把整数的二进制按每 7 位分为一组，从最低位组开始依次输出；
 * 除最后一组外，每组字节的最高位（continuation bit）置 1 表示"还有后续字节"，
 * 最后一组最高位置 0。例如：
 * <ul>
 *   <li>0        -&gt; {@code 0x00}（1 字节）</li>
 *   <li>127      -&gt; {@code 0x7F}（1 字节）</li>
 *   <li>128      -&gt; {@code 0x80 0x01}（2 字节）</li>
 *   <li>300      -&gt; {@code 0xAC 0x02}（2 字节）</li>
 *   <li>16384    -&gt; {@code 0x80 0x80 0x01}（3 字节）</li>
 * </ul>
 * 小整数占 1 字节，每多 7 个有效位多占 1 字节。倒排表相邻文档号的差值通常很小，
 * 因此差值 + VarInt 能显著压缩存储。
 */
public final class VarInt {

    private VarInt() {
    }

    /** 将非负 int 以 LEB128 写入 {@code out}，返回写入的字节数。 */
    public static int encode(int value, byte[] out, int offset) {
        if (value < 0) {
            throw new IllegalArgumentException("VarInt only supports non-negative values: " + value);
        }
        int v = value;
        int pos = offset;
        while ((v & ~0x7F) != 0) {
            out[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[pos++] = (byte) v;
        return pos - offset;
    }

    /** 从 {@code in[offset]} 开始解码一个 VarInt，返回 [value, 消费的字节数]。 */
    public static int[] decode(byte[] in, int offset) {
        int result = 0;
        int shift = 0;
        int pos = offset;
        while (true) {
            if (pos >= in.length) {
                throw new IllegalArgumentException("Truncated VarInt at offset " + offset);
            }
            int b = in[pos++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{result, pos - offset};
            }
            shift += 7;
            if (shift > 28) {
                throw new IllegalArgumentException("VarInt too long at offset " + offset);
            }
        }
    }

    /** 非负整数编码后占用的字节数。 */
    public static int encodedSize(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("VarInt only supports non-negative values: " + value);
        }
        int size = 1;
        int v = value >>> 7;
        while (v != 0) {
            size++;
            v >>>= 7;
        }
        return size;
    }
}
