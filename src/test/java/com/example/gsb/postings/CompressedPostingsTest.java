package com.example.gsb.postings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CompressedPostingsTest {

    @Test
    void emptyAndSingleElementTables() {
        CompressedPostings empty = CompressedPostings.of(new int[0]);
        assertThat(empty.docCount()).isZero();
        assertThat(empty.rawBytes()).isZero();
        assertThat(empty.compressedBytes()).isZero();
        assertThat(empty.skipEntryCount()).isZero();
        assertThat(empty.decodeAll()).isEmpty();
        PostingsIterator it = empty.iterator();
        assertThat(it.docId()).isEqualTo(-1);
        assertThat(it.next()).isEqualTo(PostingsIterator.NO_MORE_DOCS);
        assertThat(it.advance(100)).isEqualTo(PostingsIterator.NO_MORE_DOCS);

        CompressedPostings single = CompressedPostings.of(new int[] {42}, 4);
        assertThat(single.docCount()).isEqualTo(1);
        assertThat(single.skipEntryCount()).isEqualTo(1);
        assertThat(single.decodeAll()).containsExactly(42);
        PostingsIterator singleIt = single.iterator();
        assertThat(singleIt.next()).isEqualTo(42);
        assertThat(singleIt.next()).isEqualTo(PostingsIterator.NO_MORE_DOCS);
        assertThat(singleIt.advance(1000)).isEqualTo(PostingsIterator.NO_MORE_DOCS);
    }

    @Test
    void fullDecodeRoundTripRandomTables() {
        Random random = new Random(7701L);
        int[] intervals = {1, 2, 7, 128, 1024};
        for (int trial = 0; trial < 50; trial++) {
            int count = random.nextInt(5_000);
            int avgGap = 1 + random.nextInt(1000);
            int[] docs = PostingsTestSupport.randomPostings(random, count, avgGap);
            int skipInterval = intervals[random.nextInt(intervals.length)];
            CompressedPostings postings = CompressedPostings.of(docs, skipInterval);
            assertThat(postings.decodeAll()).containsExactly(docs);

            // 用 next() 顺序迭代，结果同样一致。
            PostingsIterator it = postings.iterator();
            for (int expected : docs) {
                assertThat(it.next()).isEqualTo(expected);
            }
            assertThat(it.next()).isEqualTo(PostingsIterator.NO_MORE_DOCS);
            assertThat(it.decodedCount()).isEqualTo(count);
        }
    }

    @Test
    void advanceMatchesBruteForcePosition() {
        Random random = new Random(99021L);
        int[] docs = PostingsTestSupport.randomPostings(random, 3_000, 50);
        int[] intervals = {1, 8, 128, 1024};
        for (int skipInterval : intervals) {
            CompressedPostings postings = CompressedPostings.of(docs, skipInterval);

            // 对大量目标值做 advance，与“第一个 >= target 的文档号”逐点比较。
            int checkFrom = -5;
            int checkTo = docs[docs.length - 1] + 100;
            int step = Math.max(1, (checkTo - checkFrom) / 20_000);
            int expectedIndex = 0;
            PostingsIterator it = postings.iterator();
            for (int target = checkFrom; target <= checkTo; target += step) {
                while (expectedIndex < docs.length && docs[expectedIndex] < target) {
                    expectedIndex++;
                }
                int expected = expectedIndex < docs.length
                        ? docs[expectedIndex] : PostingsIterator.NO_MORE_DOCS;
                assertThat(it.advance(target))
                        .as("skipInterval=%d target=%d", skipInterval, target)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void advanceNeverMovesBackwardAndNeverBelowTarget() {
        int[] docs = PostingsTestSupport.randomPostings(new Random(5L), 2_000, 30);
        CompressedPostings postings = CompressedPostings.of(docs, 16);
        PostingsIterator it = postings.iterator();
        it.advance(500);
        int at500 = it.docId();
        assertThat(at500).isGreaterThanOrEqualTo(500);
        // 向回跳必须停在当前文档号
        assertThat(it.advance(10)).isEqualTo(at500);
        assertThat(it.docId()).isEqualTo(at500);
        // 连续单调 advance
        int prev = at500;
        for (int target = 500; target < docs[docs.length - 1]; target += 17) {
            int current = it.advance(target);
            assertThat(current).isGreaterThanOrEqualTo(prev);
            assertThat(current).isGreaterThanOrEqualTo(target);
            prev = current;
        }
    }

    @Test
    void advanceUsesSkipTableInsteadOfFullScan() {
        // 密集大表，跳到远端时必须通过跳表跳跃，且块内解码次数有界。
        int count = 100_000;
        int[] docs = new int[count];
        for (int i = 0; i < count; i++) {
            docs[i] = i;
        }
        int skipInterval = 128;
        CompressedPostings postings = CompressedPostings.of(docs, skipInterval);
        PostingsIterator it = postings.iterator();
        it.advance(90_000);
        assertThat(it.docId()).isEqualTo(90_000);
        assertThat(it.skipCount()).isGreaterThanOrEqualTo(1);
        // 跳跃后只解码一个块内的文档号（最多 skipInterval 个），而不是 9 万个
        assertThat(it.decodedCount()).isLessThanOrEqualTo(skipInterval);
    }

    @Test
    void skipEntryCountMatchesInterval() {
        int[] docs = PostingsTestSupport.randomPostings(new Random(11L), 1_000, 10);
        assertThat(CompressedPostings.of(docs, 128).skipEntryCount()).isEqualTo(8);
        assertThat(CompressedPostings.of(docs, 100).skipEntryCount()).isEqualTo(10);
        assertThat(CompressedPostings.of(docs, 1).skipEntryCount()).isEqualTo(1_000);
        assertThat(CompressedPostings.of(docs, 1000).skipEntryCount()).isEqualTo(1);
        assertThat(CompressedPostings.of(docs, 2000).skipEntryCount()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> CompressedPostings.of(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompressedPostings.of(new int[] {1, 2}, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompressedPostings.of(new int[] {-1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompressedPostings.of(new int[] {5, 5}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompressedPostings.of(new int[] {5, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
