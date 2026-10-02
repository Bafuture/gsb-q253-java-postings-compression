package com.example.gsb.postings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 压缩倒排表求交（两表与多表），全部基于跳表 {@link PostingsIterator#advance(int)}，
 * 不做朴素的逐条归并比较。
 */
public final class Intersections {

    private Intersections() {
    }

    /** 两表求交。 */
    public static IntersectionResult intersect(CompressedPostings a, CompressedPostings b) {
        return intersect(List.of(a, b));
    }

    /**
     * 多表求交（1 个及以上的表，含空列表）。
     *
     * <p><b>处理顺序对性能的影响：</b>进入前先按表长升序排列（短表优先）：
     * <ul>
     *   <li>任一参与表为空时立即短路返回空；最短表的长度也是结果大小的上界；</li>
     *   <li>多路对齐循环中排在前面的短表（通常 gap 小、文档密）先推进，
     *       其 {@code advance} 多在单个 skip 块内完成，代价低；</li>
     *   <li>若改为“两两依次求交”的实现，更应先用最短的两张表求交，
     *       使中间结果尽可能小，再依次与更长的表求交；先处理长表会产生大量
     *       注定不会命中最终交集的候选，放大 {@code advance} 调用与解码次数。</li>
     * </ul>
     * 算法本身对输入顺序不敏感（结果完全一致），只有性能差异。
     */
    public static IntersectionResult intersect(List<CompressedPostings> lists) {
        long startNanos = System.nanoTime();
        IntersectionStats stats = new IntersectionStats();
        stats.setListCount(lists == null ? 0 : lists.size());
        int[] hits = intersectInternal(lists, stats);
        stats.setElapsedNanos(System.nanoTime() - startNanos);
        return new IntersectionResult(hits, stats);
    }

    /** 可变参数便捷重载（3 个以上表的多表求交）。 */
    public static IntersectionResult intersect(CompressedPostings first, CompressedPostings second,
                                               CompressedPostings... rest) {
        List<CompressedPostings> all = new ArrayList<>(2 + rest.length);
        all.add(first);
        all.add(second);
        all.addAll(Arrays.asList(rest));
        return intersect(all);
    }

    private static int[] intersectInternal(List<CompressedPostings> lists, IntersectionStats stats) {
        if (lists == null || lists.isEmpty()) {
            return new int[0];
        }
        List<CompressedPostings> sorted = new ArrayList<>(lists);
        sorted.sort(Comparator.comparingInt(CompressedPostings::docCount));
        if (sorted.get(0).docCount() == 0) {
            return new int[0];
        }

        int tableCount = sorted.size();
        PostingsIterator[] iterators = new PostingsIterator[tableCount];
        int[] docs = new int[tableCount];
        for (int i = 0; i < tableCount; i++) {
            iterators[i] = sorted.get(i).iterator();
            docs[i] = iterators[i].next();
        }

        int[] hits = new int[Math.min(sorted.get(0).docCount(), 4096)];
        int hitCount = 0;

        outer:
        while (true) {
            // 取所有表当前文档号的最大值作为对齐目标。
            int target = docs[0];
            for (int i = 1; i < tableCount; i++) {
                if (docs[i] > target) {
                    target = docs[i];
                }
            }
            if (target == PostingsIterator.NO_MORE_DOCS) {
                break;
            }
            // 让所有落后于目标的表用跳表快进；若某表越过目标，出现更大值则重新对齐。
            for (int i = 0; i < tableCount; i++) {
                if (docs[i] < target) {
                    docs[i] = iterators[i].advance(target);
                    if (docs[i] == PostingsIterator.NO_MORE_DOCS) {
                        break outer;
                    }
                    if (docs[i] > target) {
                        target = docs[i];
                        i = -1;
                    }
                }
            }
            // 所有表当前文档号相等 => 命中。
            if (hitCount == hits.length) {
                hits = Arrays.copyOf(hits, hits.length * 2);
            }
            hits[hitCount++] = target;
            for (int i = 0; i < tableCount; i++) {
                docs[i] = iterators[i].next();
                if (docs[i] == PostingsIterator.NO_MORE_DOCS) {
                    break outer;
                }
            }
        }

        for (PostingsIterator it : iterators) {
            stats.addDecoded(it.decodedCount());
            stats.addSkips(it.skipCount());
        }
        return Arrays.copyOf(hits, hitCount);
    }
}
