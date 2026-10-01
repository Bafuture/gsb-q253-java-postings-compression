package com.example.gsb.postings;

/** 一次求交流水的运行统计（单条流或多条流聚合）。 */
public record StreamStats(long decodeCount, long skipCount) {

    public static final StreamStats ZERO = new StreamStats(0, 0);

    public StreamStats merge(StreamStats other) {
        return new StreamStats(decodeCount + other.decodeCount, skipCount + other.skipCount);
    }
}
