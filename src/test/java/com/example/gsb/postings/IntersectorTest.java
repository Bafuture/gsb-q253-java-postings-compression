package com.example.gsb.postings;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IntersectorTest {

    @Test
    void smallHandPickedIntersection() {
        CompressedPostings a = CompressedPostings.of("a", new int[]{1, 3, 7, 10, 15, 21, 33, 100});
        CompressedPostings b = CompressedPostings.of("b", new int[]{2, 3, 7, 8, 15, 22, 33, 99, 100});
        IntersectionResult r = Intersector.intersectTwo(a, b);
        assertThat(r.docs()).containsExactly(3, 7, 15, 33, 100);
    }

    @Test
    void emptyAndSingleAndDisjointCases() {
        CompressedPostings empty = CompressedPostings.of("e", new int[0]);
        CompressedPostings a = CompressedPostings.of("a", new int[]{1, 2, 3});
        CompressedPostings b = CompressedPostings.of("b", new int[]{4, 5, 6});

        assertThat(Intersector.intersect(List.of()).docs()).isEmpty();
        assertThat(Intersector.intersect(List.of(a)).docs()).containsExactly(1, 2, 3);
        assertThat(Intersector.intersectTwo(empty, a).docs()).isEmpty();
        assertThat(Intersector.intersectTwo(a, b).docs()).isEmpty();
    }

    @Test
    void largeRandomTwoWayIntersectionMatchesBruteForce() {
        Random rnd = new Random(2024);
        for (int trial = 0; trial < 300; trial++) {
            int universe = 1_000_000 + rnd.nextInt(4_000_000);
            int[] adocs = RandomPostings.randomDocs(rnd, universe, 1_000 + rnd.nextInt(50_000));
            int[] bdocs = RandomPostings.randomDocs(rnd, universe, 1_000 + rnd.nextInt(50_000));

            CompressedPostings a = CompressedPostings.of("a", adocs);
            CompressedPostings b = CompressedPostings.of("b", bdocs);
            int[] expected = RandomPostings.bruteIntersect(adocs, bdocs);

            IntersectionResult r1 = Intersector.intersectTwo(a, b);
            IntersectionResult r2 = Intersector.intersectTwo(b, a);
            assertThat(r1.docs()).as("trial=%s", trial).containsExactly(expected);
            assertThat(r2.docs()).as("trial=%s reversed", trial).containsExactly(expected);
        }
    }

    @Test
    void largeRandomMultiWayIntersectionMatchesBruteForce() {
        Random rnd = new Random(1337);
        for (int trial = 0; trial < 100; trial++) {
            int numLists = 3 + rnd.nextInt(5); // 3 ~ 7 张表
            int universe = 2_000_000;
            CompressedPostings[] postings = new CompressedPostings[numLists];
            int[][] raws = new int[numLists][];
            for (int i = 0; i < numLists; i++) {
                raws[i] = RandomPostings.randomDocs(rnd, universe, 5_000 + rnd.nextInt(80_000));
                postings[i] = CompressedPostings.of("t" + i, raws[i]);
            }

            int[] expected = raws[0];
            for (int i = 1; i < numLists; i++) {
                expected = RandomPostings.bruteIntersect(expected, raws[i]);
            }
            List<CompressedPostings> shuffled = new java.util.ArrayList<>(Arrays.asList(postings));
            java.util.Collections.shuffle(shuffled, rnd); // 乱序传入，顺序由实现内部决定
            IntersectionResult r = Intersector.intersect(shuffled);
            assertThat(r.docs()).as("trial=%s lists=%s", trial, numLists).containsExactly(expected);
        }
    }

    @Test
    void skipListActuallyJumpsInsteadOfElementwiseScanning() {
        // 稀疏长表 vs 短表：短表的每个值都远小于长表下一个候选，
        // 若逐条比较则解码次数接近两表长度；跳表应让解码次数远小于逐条
        int[] dense = IntStream.iterate(1, x -> x + 1).limit(200_000).toArray();
        int[] sparse = IntStream.iterate(100_000_000, x -> x + 97).limit(200).toArray();
        CompressedPostings d = CompressedPostings.of("dense", dense, 32);
        CompressedPostings s = CompressedPostings.of("sparse", sparse, 32);

        IntersectionResult r = Intersector.intersectTwo(d, s);
        assertThat(r.docs()).isEmpty();
        assertThat(r.skipCount()).isGreaterThan(100);
        // 稠密表有 200k 文档；跳表跳过后实际解码的 VarInt 应远小于总数
        assertThat(r.decodeCount()).isLessThan(20_000L);
    }

    @Test
    void identicalListsIntersectFullyAndReportTime() {
        int[] docs = IntStream.iterate(1, x -> x + 2).limit(100_000).toArray();
        CompressedPostings a = CompressedPostings.of("a", docs);
        CompressedPostings b = CompressedPostings.of("b", docs);
        IntersectionResult r = Intersector.intersectTwo(a, b);
        assertThat(r.docs()).hasSize(100_000).containsExactly(docs);
        assertThat(r.elapsedNanos()).isPositive();
        assertThat(r.elapsedMillis()).isGreaterThanOrEqualTo(0.0);
    }
}
