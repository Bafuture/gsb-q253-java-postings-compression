package com.example.gsb.postings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 多查询并发共享不可变压缩表：结果必须各自正确，压缩数据不得被破坏。 */
class ConcurrentIntersectionTest {

    @Test
    void manyThreadsIntersectSharedPostings() throws Exception {
        int threads = 16;
        int queriesPerThread = 50;
        int universe = 5_000_000;

        Random seedRnd = new Random(555);
        int[][] raws = new int[8][];
        List<CompressedPostings> shared = new ArrayList<>();
        for (int i = 0; i < raws.length; i++) {
            raws[i] = RandomPostings.randomDocs(seedRnd, universe, 10_000 + seedRnd.nextInt(120_000));
            shared.add(CompressedPostings.of("shared-" + i, raws[i]));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final long seed = 9000 + t;
            futures.add(pool.submit(() -> {
                try {
                    Random rnd = new Random(seed);
                    for (int q = 0; q < queriesPerThread; q++) {
                        int n = 2 + rnd.nextInt(4);
                        List<CompressedPostings> picked = new ArrayList<>();
                        int[] expected = null;
                        for (int i = 0; i < n; i++) {
                            int idx = rnd.nextInt(raws.length);
                            picked.add(shared.get(idx));
                            expected = (expected == null) ? raws[idx].clone()
                                    : RandomPostings.bruteIntersect(expected, raws[idx]);
                        }
                        IntersectionResult r = Intersector.intersect(picked);
                        assertThat(r.docs()).containsExactly(expected);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(2, TimeUnit.MINUTES);
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        assertThat(failure.get()).isNull();

        // 并发结束后压缩表仍然完整：顺序解码与跳表定位照常工作
        for (CompressedPostings p : shared) {
            int firstDoc = p.docCount() == 0 ? -1 : p.decodeAll()[0];
            CompressedPostings.PostingsCursor cursor = p.newCursor();
            if (p.docCount() > 0) {
                assertThat(cursor.advanceTo(firstDoc)).isTrue();
                assertThat(cursor.doc()).isEqualTo(firstDoc);
            }
        }
    }
}
