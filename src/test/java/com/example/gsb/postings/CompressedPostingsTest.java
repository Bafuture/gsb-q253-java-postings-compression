package com.example.gsb.postings;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressedPostingsTest {

    @Test
    void emptyPostingsRoundTrip() {
        CompressedPostings p = CompressedPostings.of("empty", new int[0]);
        assertThat(p.docCount()).isZero();
        assertThat(p.decodeAll()).isEmpty();
        assertThat(p.compressedBytes()).isZero();
        assertThat(p.newCursor().next()).isFalse();
    }

    @Test
    void rejectsNonAscending() {
        assertThatThrownBy(() -> CompressedPostings.of("bad", new int[]{1, 2, 2}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompressedPostings.of("bad", new int[]{3, 2}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomEncodeDecodeRoundTrip() {
        Random rnd = new Random(7);
        for (int t = 0; t < 200; t++) {
            int[] docs = RandomPostings.randomDocs(rnd, 1_000_000, rnd.nextInt(5_000));
            CompressedPostings p = CompressedPostings.of("t" + t, docs);
            assertThat(p.decodeAll()).containsExactly(docs);
        }
    }

    @Test
    void roundTripAcrossBlockSizes() {
        Random rnd = new Random(11);
        int[] docs = RandomPostings.randomDocs(rnd, 1_000_000, 5_000);
        for (int blockSize : new int[]{1, 2, 7, 32, 64, 10_000}) {
            CompressedPostings p = CompressedPostings.of("b" + blockSize, docs, blockSize);
            assertThat(p.decodeAll()).as("blockSize=%s", blockSize).containsExactly(docs);
        }
    }

    @Test
    void cursorAdvanceToMatchesLinearScan() {
        Random rnd = new Random(23);
        int[] docs = RandomPostings.randomDocs(rnd, 2_000_000, 10_000);
        CompressedPostings p = CompressedPostings.of("x", docs, 32);

        for (int trial = 0; trial < 1000; trial++) {
            int target = rnd.nextInt(2_100_000) + 1;
            CompressedPostings.PostingsCursor cursor = p.newCursor();
            boolean found = cursor.advanceTo(target);

            int expectedIdx = lowerBound(docs, target);
            if (expectedIdx == docs.length) {
                assertThat(found).as("target=%s beyond end", target).isFalse();
            } else {
                assertThat(found).as("target=%s", target).isTrue();
                assertThat(cursor.doc()).as("target=%s", target).isEqualTo(docs[expectedIdx]);
            }
        }
    }

    @Test
    void interleavedNextAndAdvanceTo() {
        Random rnd = new Random(99);
        int[] docs = RandomPostings.denseDocs(rnd, 2_000, 50);
        CompressedPostings p = CompressedPostings.of("mix", docs, 16);

        for (int run = 0; run < 50; run++) {
            CompressedPostings.PostingsCursor cursor = p.newCursor();
            int idx = -1;
            for (int step = 0; step < 200 && idx < docs.length - 1; step++) {
                if (rnd.nextBoolean()) {
                    idx++;
                    assertThat(cursor.next()).isTrue();
                } else {
                    idx += 1 + rnd.nextInt(60);
                    if (idx >= docs.length) {
                        assertThat(cursor.advanceTo(docs[docs.length - 1] + 10)).isFalse();
                        idx = docs.length;
                        break;
                    }
                    assertThat(cursor.advanceTo(docs[idx])).isTrue();
                }
                assertThat(cursor.doc()).isEqualTo(docs[idx]);
            }
        }
    }

    private static int lowerBound(int[] docs, int target) {
        int lo = 0;
        int hi = docs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (docs[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
