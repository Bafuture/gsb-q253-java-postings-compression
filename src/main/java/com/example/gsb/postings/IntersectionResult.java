package com.example.gsb.postings;

/**
 * 一次求交的结果：命中的升序文档号数组及其运行统计。
 */
public final class IntersectionResult {

    private final int[] docIds;
    private final IntersectionStats stats;

    IntersectionResult(int[] docIds, IntersectionStats stats) {
        this.docIds = docIds;
        this.stats = stats;
    }

    /** 命中的文档号（升序）。 */
    public int[] docIds() {
        return docIds;
    }

    public int size() {
        return docIds.length;
    }

    public IntersectionStats stats() {
        return stats;
    }
}
