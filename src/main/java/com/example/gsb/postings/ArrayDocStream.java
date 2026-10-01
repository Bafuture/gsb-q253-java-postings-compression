package com.example.gsb.postings;

/**
 * 内存中间结果上的文档流（多表求交时两两求交的中间产物）。
 * 用二分查找实现 advanceTo，与压缩表游标同一套 {@link DocStream} 协议。
 */
public final class ArrayDocStream implements DocStream {

    private final int[] docs;
    private int index = -1;
    private long skipCount;

    public ArrayDocStream(int[] docs) {
        this.docs = docs;
    }

    public int size() {
        return docs.length;
    }

    @Override
    public boolean next() {
        if (index + 1 < docs.length) {
            index++;
            return true;
        }
        return false;
    }

    @Override
    public boolean advanceTo(int target) {
        int from = Math.max(index + 1, 0);
        int lo = from;
        int hi = docs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (docs[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo == docs.length) {
            index = docs.length;
            return false;
        }
        if (lo > from) {
            skipCount++; // 二分一次越过了 lo - from 个未访问元素
        }
        index = lo;
        return true;
    }

    @Override
    public int doc() {
        return docs[index];
    }

    @Override
    public StreamStats stats() {
        return new StreamStats(0, skipCount);
    }
}
