package com.example.gsb.postings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** 测试共享工具：随机倒排表生成 + 朴素逐条归并求交（正确性基准）。 */
final class PostingsTestSupport {

    private PostingsTestSupport() {
    }

    /** 生成 count 个严格升序文档号，平均间距约为 avgGap（gap ∈ [1, 2*avgGap-1]）。 */
    static int[] randomPostings(Random random, int count, int avgGap) {
        int[] docs = new int[count];
        int doc = random.nextInt(Math.max(1, avgGap));
        for (int i = 0; i < count; i++) {
            docs[i] = doc;
            doc += 1 + random.nextInt(2 * avgGap);
        }
        return docs;
    }

    /** 朴素两表归并求交（逐条比较），作为正确性基准。 */
    static int[] bruteForceIntersect(int[] a, int[] b) {
        List<Integer> hits = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) {
                i++;
            } else if (b[j] < a[i]) {
                j++;
            } else {
                hits.add(a[i]);
                i++;
                j++;
            }
        }
        return hits.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 朴素多表求交：逐对归并。 */
    static int[] bruteForceIntersect(List<int[]> lists) {
        if (lists.isEmpty()) {
            return new int[0];
        }
        int[] acc = lists.get(0);
        for (int i = 1; i < lists.size(); i++) {
            acc = bruteForceIntersect(acc, lists.get(i));
            if (acc.length == 0) {
                return acc;
            }
        }
        return acc;
    }

    static CompressedPostings compress(int[] docs, int skipInterval) {
        return CompressedPostings.of(docs, skipInterval);
    }

    static List<CompressedPostings> compressAll(List<int[]> rawLists, int skipInterval) {
        List<CompressedPostings> result = new ArrayList<>(rawLists.size());
        for (int[] raw : rawLists) {
            result.add(CompressedPostings.of(raw, skipInterval));
        }
        return result;
    }

    static void assertAscending(int[] docs) {
        for (int i = 1; i < docs.length; i++) {
            if (docs[i] <= docs[i - 1]) {
                throw new AssertionError("not ascending at " + i + ": " + Arrays.toString(docs));
            }
        }
    }
}
