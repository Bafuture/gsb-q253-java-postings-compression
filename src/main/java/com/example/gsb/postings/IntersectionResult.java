package com.example.gsb.postings;

/** 一次求交的结果与运行统计。 */
public record IntersectionResult(
        int[] docs,
        long decodeCount,
        long skipCount,
        long elapsedNanos) {

    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }
}
