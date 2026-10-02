package com.example.gsb.postings;

/**
 * 压缩倒排表的只读游标迭代器。每个查询线程/每次求交各自新建一个迭代器，
 * 迭代器自身有可变解码状态，但只读取所属 {@link CompressedPostings} 的不可变数据，
 * 因此多线程并发求交互不干扰，也不会破坏压缩数据。
 *
 * <p>语义与 Lucene DocIdSetIterator 类似：
 * <ul>
 *   <li>{@link #docId()} 初始为 {@code -1}（未开始）；</li>
 *   <li>{@link #next()} 解码下一个文档号，耗尽返回 {@link #NO_MORE_DOCS}；</li>
 *   <li>{@link #advance(int)} 快进到第一个 &gt;= target 的文档号，
 *       利用跳表二分定位 + 块内顺序解码，不会逐条解码整块之前的文档。</li>
 * </ul>
 */
public final class PostingsIterator {

    /** 表中已无更大文档号时的哨兵值。 */
    public static final int NO_MORE_DOCS = Integer.MAX_VALUE;

    private final byte[] data;
    private final int docCount;
    private final int skipInterval;
    private final int[] skipDocIds;
    private final int[] skipOffsets;
    private final int[] skipBaseDocs;

    /** 下一个待解码文档号的索引（0-based）。 */
    private int index;
    /** 下一个待解码字节在 data 中的偏移。 */
    private int pos;
    /** 解码基准：上一个已解码文档号（初始 0）。 */
    private int baseDoc;
    /** 当前文档号：-1 未开始，NO_MORE_DOCS 耗尽。 */
    private int current = -1;

    /** 统计：本迭代器实际解码了多少个 gap（变长整数）。 */
    private long decodedCount;
    /** 统计：本迭代器利用跳表发生了多少次块跳跃。 */
    private long skipCount;

    PostingsIterator(CompressedPostings postings) {
        this.data = postings.data();
        this.docCount = postings.docCount();
        this.skipInterval = postings.skipInterval();
        this.skipDocIds = postings.skipDocIds();
        this.skipOffsets = postings.skipOffsets();
        this.skipBaseDocs = postings.skipBaseDocs();
        this.index = 0;
        this.pos = 0;
        this.baseDoc = 0;
    }

    /** 当前文档号；未开始为 -1，耗尽为 {@link #NO_MORE_DOCS}。 */
    public int docId() {
        return current;
    }

    /** 解码并返回下一个文档号；耗尽返回 {@link #NO_MORE_DOCS}。 */
    public int next() {
        if (current == NO_MORE_DOCS) {
            return NO_MORE_DOCS;
        }
        if (index >= docCount) {
            current = NO_MORE_DOCS;
            return NO_MORE_DOCS;
        }
        int gap = readVarInt();
        baseDoc += gap;
        current = baseDoc;
        index++;
        return current;
    }

    /**
     * 快进到第一个 &gt;= target 的文档号并返回；未开始时任意 target 都会移动到首项或之后；
     * 已开始且 target 不大于当前值时直接返回当前值（绝不回退）；
     * 耗尽返回 {@link #NO_MORE_DOCS}。
     *
     * <p>过程：二分跳表找到最后一个文档号 &lt;= target 的跳表项，若它位于当前位置
     * 之后则直接把字节偏移、解码基准与索引跳到该块（计一次跳跃），随后只在该块内
     * 顺序解码到 &gt;= target。
     */
    public int advance(int target) {
        if (current == NO_MORE_DOCS) {
            return NO_MORE_DOCS;
        }
        if (current != -1 && target <= current) {
            return current;
        }
        int s = lastLessOrEqual(skipDocIds, target);
        if (s >= 0) {
            int skipIndex = s * skipInterval;
            if (skipIndex > index) {
                pos = skipOffsets[s];
                baseDoc = skipBaseDocs[s];
                index = skipIndex;
                skipCount++;
            }
        }
        while ((current == -1 || current < target) && current != NO_MORE_DOCS) {
            next();
        }
        return current;
    }

    public long decodedCount() {
        return decodedCount;
    }

    public long skipCount() {
        return skipCount;
    }

    private int readVarInt() {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = data[pos++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                decodedCount++;
                return value;
            }
            shift += 7;
        }
    }

    /** 二分：返回升序数组中最后一个 <= target 的下标，不存在返回 -1。 */
    private static int lastLessOrEqual(int[] arr, int target) {
        int lo = 0;
        int hi = arr.length - 1;
        int answer = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] <= target) {
                answer = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return answer;
    }
}
