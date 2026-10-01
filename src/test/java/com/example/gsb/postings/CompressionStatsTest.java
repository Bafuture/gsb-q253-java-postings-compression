package com.example.gsb.postings;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class CompressionStatsTest {

    @Test
    void densePostingsAchieveGoodCompressionRatio() {
        Random rnd = new Random(808);
        int totalRaw = 0;
        int totalCompressed = 0;
        int totalData = 0;
        int totalSkip = 0;

        for (int t = 0; t < 20; t++) {
            int[] docs = RandomPostings.denseDocs(rnd, 100_000, 100);
            CompressedPostings p = CompressedPostings.of("dense-" + t, docs, 32);
            totalRaw += p.rawBytes();
            totalCompressed += p.compressedBytes();
            totalData += p.compressedDataBytes();
            totalSkip += p.skipBytes();

            assertThat(p.compressedDataBytes()).isLessThan(p.rawBytes() / 2);
            assertThat(p.compressionRatio()).isLessThan(0.6);
        }

        double ratio = (double) totalCompressed / totalRaw;
        // 典型压缩率数据输出（mvn test 标准输出可见）
        System.out.printf(
                "dense postings: raw=%d bytes, varint payload=%d bytes, skip=%d bytes, "
                        + "total=%d bytes, ratio=%.2f%%%n",
                totalRaw, totalData, totalSkip, totalCompressed, ratio * 100);
        assertThat(ratio).isLessThan(0.5);
    }

    @Test
    void statsAreConsistentForBlockSizes() {
        Random rnd = new Random(809);
        int[] docs = RandomPostings.denseDocs(rnd, 50_000, 50);
        for (int blockSize : new int[]{8, 16, 32, 64, 128}) {
            CompressedPostings p = CompressedPostings.of("z", docs, blockSize);
            assertThat(p.skipBytes()).isEqualTo(p.blockCount() * 8);
            assertThat(p.compressedBytes()).isEqualTo(p.compressedDataBytes() + p.skipBytes());
            assertThat(p.rawBytes()).isEqualTo(docs.length * 4);
        }
    }

    @Test
    void decodeAndSkipCountersMoveDuringIntersection() {
        int[] adocs = new int[5_000];
        for (int i = 0; i < adocs.length; i++) {
            adocs[i] = 1 + i * 1000;
        }
        int[] bdocs = {500_001, 1_500_001, 4_999_001};
        CompressedPostings a = CompressedPostings.of("a", adocs, 32);
        CompressedPostings b = CompressedPostings.of("b", bdocs, 32);

        IntersectionResult r = Intersector.intersectTwo(a, b);
        assertThat(r.docs()).containsExactly(500_001, 1_500_001, 4_999_001);
        assertThat(r.decodeCount()).isPositive();
        assertThat(r.skipCount()).isPositive();
        // 绝不能把 a 的 5000 个差值全部解出来
        assertThat(r.decodeCount()).isLessThan(5_000L);
    }
}
