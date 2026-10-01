package com.example.gsb.postings;

import java.util.Random;

/** 测试辅助：生成各种形态的升序、去重随机倒排表。 */
final class RandomPostings {

    private RandomPostings() {
    }

    /** 从 [1, universe] 中随机抽取 count 个不同文档号并排序。 */
    static int[] randomDocs(Random rnd, int universe, int count) {
        if (count > universe) {
            throw new IllegalArgumentException("count > universe");
        }
        int[] docs = rnd.ints(1, universe + 1).distinct().limit(count).sorted().toArray();
        return docs;
    }

    /** 相邻文档号间距在 [1, maxGap] 均匀分布的稠密表。 */
    static int[] denseDocs(Random rnd, int count, int maxGap) {
        int[] docs = new int[count];
        int doc = 0;
        for (int i = 0; i < count; i++) {
            doc += 1 + rnd.nextInt(maxGap);
            docs[i] = doc;
        }
        return docs;
    }

    /** 逐条比较的朴素两表求交（正确性基准）。 */
    static int[] bruteIntersect(int[] a, int[] b) {
        int[] out = new int[Math.min(a.length, b.length)];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                out[k++] = a[i];
                i++;
                j++;
            } else if (a[i] < b[j]) {
                i++;
            } else {
                j++;
            }
        }
        int[] trimmed = new int[k];
        System.arraycopy(out, 0, trimmed, 0, k);
        return trimmed;
    }
}
