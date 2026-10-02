package com.example.gsb.postings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {

    @Test
    void concurrentQueriesShareImmutableCompressedData() throws Exception {
        Random random = new Random(20260214L);
        int tableCount = 6;
        List<int[]> rawLists = new ArrayList<>(tableCount);
        for (int t = 0; t < tableCount; t++) {
            rawLists.add(PostingsTestSupport.randomPostings(random, 30_000, 1 + random.nextInt(100)));
        }
        List<CompressedPostings> tables = PostingsTestSupport.compressAll(rawLists, 128);

        // 预先构造固定的查询计划（2~4 张表），单线程算出基准结果。
        int queryCount = 40;
        List<List<Integer>> plans = new ArrayList<>();
        for (int q = 0; q < queryCount; q++) {
            int k = 2 + random.nextInt(3);
            List<Integer> plan = new ArrayList<>();
            for (int j = 0; j < k; j++) {
                int idx = random.nextInt(tableCount);
                if (!plan.contains(idx)) {
                    plan.add(idx);
                }
            }
            plans.add(plan);
        }
        List<int[]> expected = new ArrayList<>(queryCount);
        for (List<Integer> plan : plans) {
            List<CompressedPostings> tablesForPlan = new ArrayList<>();
            List<int[]> rawForPlan = new ArrayList<>();
            for (int idx : plan) {
                tablesForPlan.add(tables.get(idx));
                rawForPlan.add(rawLists.get(idx));
            }
            int[] baseline = Intersections.intersect(tablesForPlan).docIds();
            assertThat(baseline).containsExactly(PostingsTestSupport.bruteForceIntersect(rawForPlan));
            expected.add(baseline);
        }

        // 记录压缩数据快照的特征，供并发后比对。
        int[] sizesBefore = tables.stream().mapToInt(CompressedPostings::compressedBytes).toArray();

        int threads = 12;
        int iterationsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Callable<Void> task = () -> {
                for (int i = 0; i < iterationsPerThread; i++) {
                    int queryIdx = (i + Thread.currentThread().hashCode()) % queryCount;
                    List<CompressedPostings> tablesForPlan = new ArrayList<>();
                    for (int idx : plans.get(queryIdx)) {
                        tablesForPlan.add(tables.get(idx));
                    }
                    int[] actual = Intersections.intersect(tablesForPlan).docIds();
                    assertThat(actual).containsExactly(expected.get(queryIdx));
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        for (Future<Void> future : futures) {
            future.get(2, TimeUnit.MINUTES);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 并发后压缩数据未被破坏：大小不变，且仍可完整解码回原始文档号。
        for (int i = 0; i < tableCount; i++) {
            assertThat(tables.get(i).compressedBytes()).isEqualTo(sizesBefore[i]);
            assertThat(tables.get(i).decodeAll()).containsExactly(rawLists.get(i));
        }

        // 同表反复创建多个迭代器互不干扰（游标独立）。
        CompressedPostings shared = tables.get(0);
        PostingsIterator it1 = shared.iterator();
        PostingsIterator it2 = shared.iterator();
        assertThat(it1.advance(12345)).isEqualTo(it2.advance(12345));
        assertThat(it1.next()).isEqualTo(it2.next());
    }
}
