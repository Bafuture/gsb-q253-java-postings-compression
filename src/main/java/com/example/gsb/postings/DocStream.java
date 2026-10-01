package com.example.gsb.postings;

/**
 * 有序文档号流：游标式读取，支持基于跳表的向前跳跃定位。
 * 实现类要么是不可变压缩表上的一次性游标（{@link PostingsCursor}），
 * 要么是内存中间结果（{@link ArrayDocStream}）；非线程安全，每次查询各自创建。
 */
public interface DocStream {

    /** 取下一个文档号，读到返回 true，耗尽返回 false。 */
    boolean next();

    /**
     * 跳到第一个 &gt;= target 的文档号。target 不得小于当前文档号（只允许向前）。
     * 内部使用跳表/二分定位，避免逐条解码。
     */
    boolean advanceTo(int target);

    /** 当前文档号；未加载或已耗尽时行为未定义。 */
    int doc();

    /** 该流截至目前的解码与跳跃统计。 */
    StreamStats stats();
}
