package com.example.gsb.postings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 倒排表求交器（无状态、线程安全：只读压缩表，统计由每次调用内的局部游标维护）。
 */
public final class Intersector {

    /**
     * 两表求交：基于 {@link DocStream#advanceTo(int)} 的"追赶式"合并。
     *
     * <p>设两表长 n、m（n &le; m），块大小为 B。每轮比较后，落后方用跳表直接越过
     * 第一个不小于对方文档号的位置，被整体跨过的块只比较锚点、不解码块内数据，
     * 因此<b>不会退化为逐条比较</b>。
     *
     * <p>复杂度：
     * <ul>
     *   <li>文档号比较次数 O(n + m/B + n·log(m/(nB)))（上界可收紧到 O(n log(1+m/n) + 匹配块扫描)）；
     *       最坏情况下两表完全重叠仍需逐条 O(n+m)，但任何稀疏间隔都被整段跳过。</li>
     *   <li>每次跳跃定位 O(log(m/B) + B)（锚点二分 + 至多一个块的解码）。</li>
     *   <li>空间复杂度 O(B + k)，k 为交集大小。</li>
     * </ul>
     */
    public static IntersectionResult intersectTwo(CompressedPostings left, CompressedPostings right) {
        return intersect(List.of(left, right));
    }

    /**
     * 多表求交（支持 0、1 或 3 张以上的表）。
     *
     * <p><b>处理顺序对性能的影响</b>：按表长（df）升序处理，先把最短的两张表求交，
     * 再用其（很小的）结果去更长的表中跳跃匹配。中间结果每经过一次求交都单调缩小，
     * 长表上发生的 {@code advanceTo} 次数由"最短表有多少个候选"决定；若反过来用长表驱动，
     * 会在短表上产生大量无谓的跳跃调用。因此顺序错误在 df 悬殊时可造成数量级的性能差异
     * （这也是检索系统中常用的 "shortest-first / adaptive" 优化）。
     */
    public static IntersectionResult intersect(List<CompressedPostings> postings) {
        long start = System.nanoTime();
        List<DocStream> used = new ArrayList<>();
        try {
            if (postings.isEmpty()) {
                return new IntersectionResult(new int[0], 0, 0, 0);
            }

            List<CompressedPostings> sorted = new ArrayList<>(postings);
            sorted.sort(Comparator.comparingInt(CompressedPostings::docCount));

            CompressedPostings.PostingsCursor first = sorted.get(0).newCursor();
            used.add(first);

            if (sorted.size() == 1) {
                int[] docs = drain(first);
                return result(docs, used, start);
            }

            CompressedPostings.PostingsCursor second = sorted.get(1).newCursor();
            used.add(second);
            int[] common = pairIntersect(first, second);

            for (int i = 2; i < sorted.size(); i++) {
                if (common.length == 0) {
                    break;
                }
                ArrayDocStream candidate = new ArrayDocStream(common);
                used.add(candidate);
                CompressedPostings.PostingsCursor next = sorted.get(i).newCursor();
                used.add(next);
                common = pairIntersect(candidate, next);
            }
            return result(common, used, start);
        } finally {
            // 统计全部由局部游标持有，随调用结束回收，共享的压缩表没有任何可变状态
        }
    }

    /** 追赶式两流求交：落后一方调用 advanceTo 整段跳跃，而非逐条 next。 */
    static int[] pairIntersect(DocStream a, DocStream b) {
        int[] out = new int[Math.min(estimateSize(a), estimateSize(b))];
        int size = 0;
        if (!a.next() || !b.next()) {
            return new int[0];
        }
        while (true) {
            int da = a.doc();
            int db = b.doc();
            if (da == db) {
                out[size++] = da;
                if (!a.next() || !b.next()) {
                    break;
                }
            } else if (da < db) {
                if (!a.advanceTo(db)) {
                    break;
                }
            } else {
                if (!b.advanceTo(da)) {
                    break;
                }
            }
        }
        if (size == out.length) {
            return out;
        }
        int[] trimmed = new int[size];
        System.arraycopy(out, 0, trimmed, 0, size);
        return trimmed;
    }

    private static int estimateSize(DocStream s) {
        if (s instanceof CompressedPostings.PostingsCursor c) {
            return c.outer().docCount();
        }
        if (s instanceof ArrayDocStream a) {
            return a.size();
        }
        return Integer.MAX_VALUE / 2;
    }

    private static int[] drain(DocStream s) {
        int[] tmp = new int[s instanceof CompressedPostings.PostingsCursor c
                ? c.outer().docCount() : 1024];
        int size = 0;
        while (s.next()) {
            if (size == tmp.length) {
                tmp = java.util.Arrays.copyOf(tmp, tmp.length * 2);
            }
            tmp[size++] = s.doc();
        }
        return size == tmp.length ? tmp : java.util.Arrays.copyOf(tmp, size);
    }

    private static IntersectionResult result(int[] docs, List<DocStream> used, long start) {
        long decodes = 0;
        long skips = 0;
        for (DocStream s : used) {
            StreamStats st = s.stats();
            decodes += st.decodeCount();
            skips += st.skipCount();
        }
        return new IntersectionResult(docs, decodes, skips, System.nanoTime() - start);
    }

    private Intersector() {
    }
}
