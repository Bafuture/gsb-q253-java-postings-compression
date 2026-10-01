package com.example.gsb.postings;

import java.util.Arrays;

/**
 * 不可变的压缩倒排表（线程安全：构造后只读，可被任意多查询并发共享）。
 *
 * <p><b>编码规则</b>
 * <ol>
 *   <li>文档号升序存储（构造时校验，重复值拒绝）。</li>
 *   <li>按块（block）组织，默认每 {@value #DEFAULT_BLOCK_SIZE} 个文档号一块；
 *       每块起始记录一个跳表项：锚点文档号（该块首文档的绝对值）及其在压缩字节流中的偏移。</li>
 *   <li>块内第一个文档号存绝对值（保证可以从块偏移独立解码、随机定位），
 *       其余文档号存与前一个文档号的差值 {@code d = doc[i] - doc[i-1]}（正整数）。</li>
 *   <li>绝对值与差值都用 {@link VarInt}（LEB128）编码。</li>
 * </ul>
 * 差值 {@code d >= 1} 且通常远小于文档号本身，VarInt 后每个值常只需 1~2 字节。
 *
 * <p>跳表层是稀疏的线性索引（每个块一项），配合块内顺序扫描：定位一个文档号只需
 * O(log 块数) 次锚点比较 + 至多一个块的解码，被整体跳过的块连块内数据都不需要解码。
 */
public final class CompressedPostings {

    public static final int DEFAULT_BLOCK_SIZE = 32;

    private final String term;
    private final int blockSize;
    private final byte[] data;
    private final int[] anchors;     // 每块首个文档号（绝对），升序
    private final int[] offsets;     // 每块在 data 中的起始偏移
    private final int[] blockCounts; // 每块实际文档数（最后一块可能不满）
    private final int docCount;
    private final int rawBytes;
    private final int skipBytes;

    private CompressedPostings(String term, int blockSize, int[] sortedDocs) {
        this.term = term;
        this.blockSize = blockSize;
        this.docCount = sortedDocs.length;
        this.rawBytes = sortedDocs.length * Integer.BYTES;

        int blockCount = (sortedDocs.length + blockSize - 1) / blockSize;
        this.anchors = new int[blockCount];
        this.offsets = new int[blockCount];
        this.blockCounts = new int[blockCount];
        this.skipBytes = blockCount * (Integer.BYTES + Integer.BYTES);

        byte[] buffer = new byte[rawBytes + blockCount];
        int pos = 0;
        for (int b = 0; b < blockCount; b++) {
            int start = b * blockSize;
            int end = Math.min(start + blockSize, sortedDocs.length);
            anchors[b] = sortedDocs[start];
            offsets[b] = pos;
            blockCounts[b] = end - start;

            pos += VarInt.encode(sortedDocs[start], buffer, pos);
            for (int i = start + 1; i < end; i++) {
                pos += VarInt.encode(sortedDocs[i] - sortedDocs[i - 1], buffer, pos);
            }
        }
        this.data = Arrays.copyOf(buffer, pos);
    }

    /** 压缩一份升序、去重的文档号数组（输入数组不会被修改也不会被保留）。 */
    public static CompressedPostings of(String term, int[] sortedDocs) {
        return of(term, sortedDocs, DEFAULT_BLOCK_SIZE);
    }

    /** 压缩并指定块大小（即相邻两个跳表项之间间隔的文档数）。 */
    public static CompressedPostings of(String term, int[] sortedDocs, int blockSize) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        int[] copy = sortedDocs.clone();
        for (int i = 1; i < copy.length; i++) {
            if (copy[i] <= copy[i - 1]) {
                throw new IllegalArgumentException(
                        "docs must be strictly ascending at index " + i + ": "
                                + copy[i - 1] + " -> " + copy[i]);
            }
        }
        return new CompressedPostings(term, blockSize, copy);
    }

    public String term() {
        return term;
    }

    public int docCount() {
        return docCount;
    }

    public int blockSize() {
        return blockSize;
    }

    public int blockCount() {
        return anchors.length;
    }

    /** 压缩前字节数（文档号按 4 字节 int 计）。 */
    public int rawBytes() {
        return rawBytes;
    }

    /** 压缩后文档数据字节数（仅 VarInt 负载，不含跳表）。 */
    public int compressedDataBytes() {
        return data.length;
    }

    /** 跳表（锚点 + 偏移）占用字节数。 */
    public int skipBytes() {
        return skipBytes;
    }

    /** 压缩后总字节数（VarInt 负载 + 跳表）。 */
    public int compressedBytes() {
        return data.length + skipBytes;
    }

    /** 压缩率：压缩后 / 压缩前，越小越好；空表约定为 1.0。 */
    public double compressionRatio() {
        return rawBytes == 0 ? 1.0 : (double) compressedBytes() / rawBytes;
    }

    /** 整表顺序解码（测试/校验用）。 */
    public int[] decodeAll() {
        int[] out = new int[docCount];
        PostingsCursor cursor = newCursor();
        int i = 0;
        while (cursor.next()) {
            out[i++] = cursor.doc();
        }
        return out;
    }

    /** 创建一次查询使用的游标；不同查询各自创建，互不影响。 */
    public PostingsCursor newCursor() {
        return new PostingsCursor();
    }

    /**
     * 压缩表上的一次性游标：块内惰性解码，块间通过跳表跳跃。
     * 每个游标仅被单线程使用（每个查询新建自己的游标）。
     */
    public final class PostingsCursor implements DocStream {

        private int block = -1;          // 当前所在块
        private int indexInBlock = -1;   // 当前文档在块内的下标
        private final int[] blockDocs = new int[blockSize];
        private int loadedCount;
        private int current = -1;
        private boolean exhausted;
        private long decodeCount;
        private long skipCount;

        @Override
        public boolean next() {
            if (exhausted || anchors.length == 0) {
                exhausted = true;
                return false;
            }
            if (block < 0) {
                landBlock(0, 0);
                indexInBlock = 0;
                current = blockDocs[0];
                return true;
            }
            if (indexInBlock + 1 < loadedCount) {
                indexInBlock++;
                current = blockDocs[indexInBlock];
                return true;
            }
            if (block + 1 < anchors.length) {
                landBlock(block + 1, 0);
                indexInBlock = 0;
                current = blockDocs[0];
                return true;
            }
            exhausted = true;
            return false;
        }

        @Override
        public boolean advanceTo(int target) {
            if (exhausted) {
                return false;
            }
            if (block < 0) {
                int b = lowerBoundAnchor(0, anchors.length, target);
                if (b == anchors.length) {
                    // target 大于所有锚点：只可能落在最后一块，前面整块全部跳过
                    landBlock(anchors.length - 1, anchors.length - 1);
                    indexInBlock = 0;
                    current = blockDocs[0];
                    return scanOrExhaust(target);
                }
                if (b == 0) {
                    landBlock(0, 0);
                    indexInBlock = 0;
                    current = blockDocs[0];
                    return true; // 首锚点已 >= target
                }
                // 先扫描候选前块 b-1（其锚点 < target，但块内可能有 >= target 的值）
                landBlock(b - 1, b - 1);
                indexInBlock = 0;
                current = blockDocs[0];
                if (scanWithinBlock(target)) {
                    return true;
                }
                landBlock(b, 0); // 块 b-1 未命中，落到锚点 >= target 的块 b
                indexInBlock = 0;
                current = blockDocs[0];
                return true;
            }

            if (target <= current) {
                return true;
            }
            // 先在当前块剩余部分内顺序找
            if (indexInBlock + 1 < loadedCount && scanWithinBlock(target)) {
                return true;
            }
            if (block == anchors.length - 1) {
                exhausted = true;
                return false;
            }
            int b = lowerBoundAnchor(block + 1, anchors.length, target);
            if (b == anchors.length) {
                // 锚点全部 < target：扫描最后一块，其余块整块跳过
                landBlock(anchors.length - 1, anchors.length - 1 - block - 1);
                indexInBlock = 0;
                current = blockDocs[0];
                return scanOrExhaust(target);
            }
            if (b == block + 1) {
                landBlock(b, 0);
                indexInBlock = 0;
                current = blockDocs[0];
                return true; // 下一块锚点已 >= target
            }
            // 中间存在整块跳过；候选块 b-1 的锚点 < target，需先解码扫描它
            landBlock(b - 1, b - 1 - block - 1);
            indexInBlock = 0;
            current = blockDocs[0];
            if (scanWithinBlock(target)) {
                return true;
            }
            landBlock(b, 0);
            indexInBlock = 0;
            current = blockDocs[0];
            return true;
        }

        private boolean scanOrExhaust(int target) {
            if (current >= target || scanWithinBlock(target)) {
                return true;
            }
            exhausted = true;
            return false;
        }

        /** 在当前块内从当前位置顺序找到第一个 >= target 的下标；找到返回 true。 */
        private boolean scanWithinBlock(int target) {
            while (indexInBlock + 1 < loadedCount && blockDocs[indexInBlock + 1] < target) {
                indexInBlock++;
            }
            if (indexInBlock + 1 < loadedCount) {
                indexInBlock++;
                current = blockDocs[indexInBlock];
                return true;
            }
            indexInBlock = loadedCount - 1;
            current = blockDocs[indexInBlock];
            return false;
        }

        /** 区间 [from, to) 内第一个 anchor >= target 的下标；不存在返回 to。 */
        private int lowerBoundAnchor(int from, int to, int target) {
            int lo = from;
            int hi = to;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (anchors[mid] < target) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /**
         * 落到块 b 并解码整块。{@code bypassedBlocks} 为本次跳跃中被整体跳过、
         * 未解码块内数据的块数，计入跳表跳跃次数。
         */
        private void landBlock(int b, int bypassedBlocks) {
            skipCount += bypassedBlocks;
            block = b;
            int pos = offsets[b];
            int limit = b + 1 < offsets.length ? offsets[b + 1] : data.length;

            int[] first = VarInt.decode(data, pos);
            int prev = first[0];
            pos += first[1];
            blockDocs[0] = prev;
            decodeCount++;

            int n = 1;
            while (pos < limit) {
                int[] r = VarInt.decode(data, pos);
                pos += r[1];
                prev += r[0];
                blockDocs[n++] = prev;
                decodeCount++;
            }
            if (n != blockCounts[b]) {
                throw new IllegalStateException("corrupt block " + b
                        + ": decoded " + n + ", expected " + blockCounts[b]);
            }
            loadedCount = n;
        }

        /** 游标所属的不可变压缩表。 */
        public CompressedPostings outer() {
            return CompressedPostings.this;
        }

        @Override
        public int doc() {
            return current;
        }

        @Override
        public StreamStats stats() {
            return new StreamStats(decodeCount, skipCount);
        }
    }
}
