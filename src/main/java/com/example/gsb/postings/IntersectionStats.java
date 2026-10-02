package com.example.gsb.postings;

/**
 * 一次求交的运行统计。每次求交返回独立的新实例，因此天然线程安全、互不干扰。
 */
public final class IntersectionStats {

    private int listCount;
    private long decodedCount;
    private long skipCount;
    private long elapsedNanos;

    public int listCount() {
        return listCount;
    }

    /** 本次求交过程中所有迭代器实际解码的变长整数（gap）总次数。 */
    public long decodedCount() {
        return decodedCount;
    }

    /** 本次求交过程中利用跳表跳过整个块的总次数。 */
    public long skipCount() {
        return skipCount;
    }

    /** 本次求交耗时（纳秒）。 */
    public long elapsedNanos() {
        return elapsedNanos;
    }

    /** 本次求交耗时（毫秒，double）。 */
    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }

    @Override
    public String toString() {
        return "IntersectionStats{lists=" + listCount
                + ", decoded=" + decodedCount
                + ", skips=" + skipCount
                + ", elapsedNanos=" + elapsedNanos + '}';
    }

    void setListCount(int listCount) {
        this.listCount = listCount;
    }

    void addDecoded(long n) {
        this.decodedCount += n;
    }

    void addSkips(long n) {
        this.skipCount += n;
    }

    void setElapsedNanos(long elapsedNanos) {
        this.elapsedNanos = elapsedNanos;
    }
}
