package com.example.gsb.postings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IntersectionTest {

    @Test
    void smallFixedExamples() {
        CompressedPostings a = CompressedPostings.of(new int[] {1, 3, 5, 7, 9, 11}, 2);
        CompressedPostings b = CompressedPostings.of(new int[] {2, 3, 5, 8, 11, 13}, 2);
        assertThat(Intersections.intersect(a, b).docIds()).containsExactly(3, 5, 11);

        CompressedPostings c = CompressedPostings.of(new int[] {3, 5, 11, 17}, 2);
        assertThat(Intersections.intersect(a, b, c).docIds()).containsExactly(3, 5, 11);

        // 无交集
        CompressedPostings d = CompressedPostings.of(new int[] {0, 2, 4}, 2);
        assertThat(Intersections.intersect(a, d).docIds()).isEmpty();
        // 完全相同
        assertThat(Intersections.intersect(a, a).docIds()).containsExactly(1, 3, 5, 7, 9, 11);
        // 空表参与
        CompressedPostings empty = CompressedPostings.of(new int[0]);
        assertThat(Intersections.intersect(a, empty).docIds()).isEmpty();
        assertThat(Intersections.intersect(empty, empty).docIds()).isEmpty();
        assertThat(Intersections.intersect(List.of()).docIds()).isEmpty();
        // 单表求交即自身
        assertThat(Intersections.intersect(List.of(a)).docIds())
                .containsExactly(1, 3, 5, 7, 9, 11);
    }

    @Test
    void twoWayIntersectionMatchesBruteForceOnLargeRandomData() {
        Random random = new Random(20250101L);
        int trials = 120;
        for (int trial = 0; trial < trials; trial++) {
            int countA = random.nextInt(50_000);
            int countB = random.nextInt(50_000);
            int avgGapA = 1 + random.nextInt(500);
            int avgGapB = 1 + random.nextInt(500);
            int[] rawA = PostingsTestSupport.randomPostings(random, countA, avgGapA);
            int[] rawB = PostingsTestSupport.randomPostings(random, countB, avgGapB);
            int skipInterval = 1 + random.nextInt(256);

            CompressedPostings a = CompressedPostings.of(rawA, skipInterval);
            CompressedPostings b = CompressedPostings.of(rawB, skipInterval);

            int[] expected = PostingsTestSupport.bruteForceIntersect(rawA, rawB);
            IntersectionResult result = Intersections.intersect(a, b);
            assertThat(result.docIds())
                    .as("trial=%d countA=%d countB=%d skip=%d", trial, countA, countB, skipInterval)
                    .containsExactly(expected);
            PostingsTestSupport.assertAscending(result.docIds());
        }
    }

    @Test
    void multiWayIntersectionMatchesBruteForce() {
        Random random = new Random(424242L);
        for (int trial = 0; trial < 60; trial++) {
            int tableCount = 3 + random.nextInt(4); // 3~6 张表
            List<int[]> rawLists = new ArrayList<>(tableCount);
            for (int t = 0; t < tableCount; t++) {
                rawLists.add(PostingsTestSupport.randomPostings(
                        random, random.nextInt(20_000), 1 + random.nextInt(200)));
            }
            int skipInterval = 1 + random.nextInt(128);
            List<CompressedPostings> compressed =
                    PostingsTestSupport.compressAll(rawLists, skipInterval);

            int[] expected = PostingsTestSupport.bruteForceIntersect(rawLists);
            IntersectionResult result = Intersections.intersect(compressed);
            assertThat(result.docIds())
                    .as("trial=%d tables=%d", trial, tableCount)
                    .containsExactly(expected);
            assertThat(result.stats().listCount()).isEqualTo(tableCount);
        }
    }

    @Test
    void intersectionIsOrderIndependent() {
        Random random = new Random(31337L);
        List<int[]> rawLists = new ArrayList<>();
        for (int t = 0; t < 5; t++) {
            rawLists.add(PostingsTestSupport.randomPostings(random, 8_000, 20));
        }
        List<CompressedPostings> compressed = PostingsTestSupport.compressAll(rawLists, 64);
        int[] expected = Intersections.intersect(compressed).docIds();

        // 打乱输入顺序，结果必须一致（顺序只影响性能，不影响正确性）。
        List<CompressedPostings> shuffled = new ArrayList<>(compressed);
        java.util.Collections.shuffle(shuffled, new Random(999L));
        assertThat(Intersections.intersect(shuffled).docIds()).containsExactly(expected);
    }

    @Test
    void doesNotDegenerateToLinearScanWhenIntersectionIsSparse() {
        // 稀疏表 A（200 个文档）与密集大表 B（200 万个文档）求交：
        // 跳表求交的解码次数必须远小于两表文档总数，否则就是逐条比较。
        Random random = new Random(80808L);
        int[] rawA = PostingsTestSupport.randomPostings(random, 200, 50_000);
        int[] rawB = PostingsTestSupport.randomPostings(random, 2_000_000, 5);
        int skipInterval = 128;
        CompressedPostings a = CompressedPostings.of(rawA, skipInterval);
        CompressedPostings b = CompressedPostings.of(rawB, skipInterval);

        IntersectionResult result = Intersections.intersect(a, b);
        int[] expected = PostingsTestSupport.bruteForceIntersect(rawA, rawB);
        assertThat(result.docIds()).containsExactly(expected);

        long totalDocs = (long) rawA.length + rawB.length;
        assertThat(result.stats().skipCount()).isGreaterThan(0);
        assertThat(result.stats().decodedCount())
                .as("解码次数必须远小于逐条扫描的 %d", totalDocs)
                .isLessThan(totalDocs / 20);
    }

    @Test
    void statsArePopulated() {
        Random random = new Random(60606L);
        int[] rawA = PostingsTestSupport.randomPostings(random, 10_000, 30);
        int[] rawB = PostingsTestSupport.randomPostings(random, 10_000, 30);
        CompressedPostings a = CompressedPostings.of(rawA, 64);
        CompressedPostings b = CompressedPostings.of(rawB, 64);

        IntersectionResult result = Intersections.intersect(a, b);
        IntersectionStats stats = result.stats();
        assertThat(stats.listCount()).isEqualTo(2);
        assertThat(stats.decodedCount()).isGreaterThan(0);
        assertThat(stats.decodedCount()).isLessThanOrEqualTo((long) rawA.length + rawB.length + 2);
        assertThat(stats.skipCount()).isGreaterThanOrEqualTo(0);
        assertThat(stats.elapsedNanos()).isGreaterThanOrEqualTo(0);
        assertThat(stats.elapsedMillis()).isGreaterThanOrEqualTo(0.0);
        assertThat(stats.toString()).contains("decoded=").contains("skips=");
    }
}
