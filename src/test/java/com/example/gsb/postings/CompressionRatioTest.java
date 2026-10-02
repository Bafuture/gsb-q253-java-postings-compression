package com.example.gsb.postings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CompressionRatioTest {

    @Test
    void denseTableCompressesToAboutOneBytePerDoc() {
        // gap 恒为 1 的最密集表：每个 gap 只需 1 字节（int32 的 1/4）。
        int count = 100_000;
        int[] docs = new int[count];
        for (int i = 0; i < count; i++) {
            docs[i] = i;
        }
        CompressedPostings postings = CompressedPostings.of(docs, 128);
        assertThat(postings.rawBytes()).isEqualTo(count * 4);
        assertThat(postings.compressedBytes()).isEqualTo(count);
        assertThat(postings.compressionRatio()).isEqualTo(0.25);
        // 加上跳表开销后仍远小于原始存储（数据 25% + 跳表约 2.3%）。
        assertThat(postings.compressionRatioWithSkipTable()).isLessThan(0.30);
    }

    @Test
    void smallGapsSaveMoreThanHalf() {
        // 平均 gap 约 50：全部落在 1 字节（<128）区间，压缩率约 25%。
        Random random = new Random(1234L);
        int[] docs = PostingsTestSupport.randomPostings(random, 200_000, 50);
        CompressedPostings postings = CompressedPostings.of(docs, 128);
        assertThat(postings.compressedBytes()).isLessThan(postings.rawBytes() / 2);
        assertThat(postings.compressionRatio()).isLessThan(0.3);
    }

    @Test
    void largerGapsUseMoreBytesButStillCompress() {
        // 平均 gap 约 5000：多数落在 2 字节区间（[2^7, 2^14)），压缩率约 50%。
        Random random = new Random(9876L);
        int[] docs = PostingsTestSupport.randomPostings(random, 100_000, 5_000);
        CompressedPostings postings = CompressedPostings.of(docs, 128);
        assertThat(postings.compressionRatio()).isBetween(0.3, 0.65);
        assertThat(postings.compressedBytes()).isLessThan(postings.rawBytes());
    }

    @Test
    void skipTableOverheadIsBounded() {
        Random random = new Random(555L);
        int[] docs = PostingsTestSupport.randomPostings(random, 100_000, 10);
        CompressedPostings postings = CompressedPostings.of(docs, 128);
        // 跳表项数 = ceil(100000/128) = 782，每项 12 字节
        assertThat(postings.skipEntryCount()).isEqualTo(782);
        assertThat(postings.skipTableBytes()).isEqualTo(782 * 12);
        // 跳表开销相对未压缩原始存储不到 5%（9384 / 400000）。
        assertThat(postings.skipTableBytes()).isLessThan(postings.rawBytes() / 20);
    }

    @Test
    void statsFieldsAreConsistent() {
        Random random = new Random(777L);
        int[] docs = PostingsTestSupport.randomPostings(random, 10_000, 100);
        CompressedPostings postings = CompressedPostings.of(docs, 64);
        assertThat(postings.rawBytes()).isEqualTo(40_000);
        assertThat(postings.compressedBytes()).isPositive();
        assertThat(postings.compressedBytes()).isLessThanOrEqualTo(10_000 * 5);
        assertThat(postings.compressionRatio())
                .isEqualTo((double) postings.compressedBytes() / postings.rawBytes());
        assertThat(postings.skipInterval()).isEqualTo(64);
    }
}
