package com.example.gsb.postings;

import java.util.Arrays;

/**
 * 压缩后的倒排表（文档号升序、无重复），构建完成后不可变，多线程可安全共享。
 *
 * <p>压缩方式：对升序文档号 {@code d0 < d1 < ... < d(n-1)} 做差值（delta/gap）编码：
 * {@code g0 = d0}，{@code gi = di - d(i-1)}（i &gt; 0），所有 gap 均为正整数，
 * 再对每个 gap 用 {@link VarIntCodec} 变长写入字节流。
 *
 * <p>跳表结构：每 {@code skipInterval} 个文档号记录一个跳表项（文档号 + 字节偏移 +
 * 解码基准文档号）。第 s 个跳表项对应文档索引 {@code s * skipInterval}，
 * 其字节偏移指向该文档号 gap 编码的起始位置，解码基准为该文档号的前一个文档号
 * （首项基准为 0）。{@link PostingsIterator#advance(int)} 先二分跳表定位到最后一个
 * 文档号不超过目标值的跳表项，直接跳转字节偏移，只在一个 skip 块内顺序解码。
 *
 * <p>跳表项只额外占用 {@code O(n / skipInterval)} 个 int，典型取
 * {@link #DEFAULT_SKIP_INTERVAL}（≈ √n 量级），在空间与跳跃粒度间平衡。
 */
public final class CompressedPostings {

    /** 默认跳表粒度：每 128 个文档号一个跳表项。 */
    public static final int DEFAULT_SKIP_INTERVAL = 128;

    private final byte[] data;
    private final int docCount;
    private final int skipInterval;

    /** 每个跳表项的文档号（升序）。 */
    private final int[] skipDocIds;
    /** 每个跳表项在 data 中的字节偏移（gap 编码起始位置）。 */
    private final int[] skipOffsets;
    /** 每个跳表项的解码基准（前一个文档号，首项为 0）。 */
    private final int[] skipBaseDocs;

    /** 压缩前字节数（按每个文档号 4 字节的 int32 存储计算）。 */
    private final int rawBytes;

    private CompressedPostings(byte[] data, int docCount, int skipInterval,
                               int[] skipDocIds, int[] skipOffsets, int[] skipBaseDocs,
                               int rawBytes) {
        this.data = data;
        this.docCount = docCount;
        this.skipInterval = skipInterval;
        this.skipDocIds = skipDocIds;
        this.skipOffsets = skipOffsets;
        this.skipBaseDocs = skipBaseDocs;
        this.rawBytes = rawBytes;
    }

    /** 用 {@link #DEFAULT_SKIP_INTERVAL} 压缩一张升序文档号表。 */
    public static CompressedPostings of(int[] sortedDocIds) {
        return of(sortedDocIds, DEFAULT_SKIP_INTERVAL);
    }

    /**
     * 压缩一张文档号表。
     *
     * @param sortedDocIds 严格升序、非负、无重复的文档号
     * @param skipInterval 跳表粒度（每多少个文档号一个跳表项），必须 &gt;= 1
     */
    public static CompressedPostings of(int[] sortedDocIds, int skipInterval) {
        if (sortedDocIds == null) {
            throw new IllegalArgumentException("sortedDocIds must not be null");
        }
        if (skipInterval < 1) {
            throw new IllegalArgumentException("skipInterval must be >= 1: " + skipInterval);
        }
        int n = sortedDocIds.length;

        int[] sDocs = new int[(n + skipInterval - 1) / skipInterval];
        int[] sOffsets = new int[sDocs.length];
        int[] sBaseDocs = new int[sDocs.length];

        byte[] buf = new byte[Math.max(8, n)];
        int pos = 0;
        int prev = 0;
        int skipIdx = 0;
        for (int i = 0; i < n; i++) {
            int doc = sortedDocIds[i];
            if (doc < 0) {
                throw new IllegalArgumentException("doc id must be non-negative at index " + i);
            }
            if (i > 0 && doc <= sortedDocIds[i - 1]) {
                throw new IllegalArgumentException(
                        "doc ids must be strictly ascending at index " + i + ": " + doc);
            }
            if (i % skipInterval == 0) {
                sDocs[skipIdx] = doc;
                sOffsets[skipIdx] = pos;
                sBaseDocs[skipIdx] = prev;
                skipIdx++;
            }
            int gap = doc - prev;
            int len = VarIntCodec.encodedLength(gap);
            if (pos + len > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + len));
            }
            pos += VarIntCodec.encode(gap, buf, pos);
            prev = doc;
        }

        return new CompressedPostings(
                Arrays.copyOf(buf, pos), n, skipInterval, sDocs, sOffsets, sBaseDocs, n * 4);
    }

    /** 新建一个只读迭代器；每次求交各自新建，不共享解码游标。 */
    public PostingsIterator iterator() {
        return new PostingsIterator(this);
    }

    /** 完整解码回文档号数组。 */
    public int[] decodeAll() {
        int[] docs = new int[docCount];
        int pos = 0;
        int prev = 0;
        for (int i = 0; i < docCount; i++) {
            int[] decoded = VarIntCodec.decodeWithNextOffset(data, pos);
            prev += decoded[0];
            docs[i] = prev;
            pos = decoded[1];
        }
        return docs;
    }

    public int docCount() {
        return docCount;
    }

    /** 压缩前字节数（int32 数组：4 × 文档数）。 */
    public int rawBytes() {
        return rawBytes;
    }

    /** 压缩后字节数（压缩字节流长度，不含跳表）。 */
    public int compressedBytes() {
        return data.length;
    }

    /** 跳表占用的额外字节数（每个跳表项 3 个 int）。 */
    public int skipTableBytes() {
        return skipDocIds.length * Integer.BYTES * 3;
    }

    /**
     * 压缩率 = 压缩后字节数 / 压缩前字节数，越小越好；空表压缩率定义为 1.0。
     * 若把跳表开销也算入，使用 {@code (compressedBytes() + skipTableBytes()) / rawBytes()}。
     */
    public double compressionRatio() {
        return rawBytes == 0 ? 1.0 : (double) data.length / rawBytes;
    }

    public double compressionRatioWithSkipTable() {
        return rawBytes == 0 ? 1.0 : (double) (data.length + skipTableBytes()) / rawBytes;
    }

    public int skipInterval() {
        return skipInterval;
    }

    public int skipEntryCount() {
        return skipDocIds.length;
    }

    byte[] data() {
        return data;
    }

    int[] skipDocIds() {
        return skipDocIds;
    }

    int[] skipOffsets() {
        return skipOffsets;
    }

    int[] skipBaseDocs() {
        return skipBaseDocs;
    }
}
