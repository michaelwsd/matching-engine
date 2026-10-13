package com.mwang.model;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIdAllocatorTest {

    @Test
    void shouldIssueConsecutiveIdsFromOne() {
        OrderIdAllocator ids = new OrderIdAllocator();

        assertThat(LongStream.range(0, 5).map(i -> ids.next()).toArray())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void shouldStartWhereTold() {
        OrderIdAllocator ids = new OrderIdAllocator(1_000_000);

        assertThat(ids.next()).isEqualTo(1_000_000);
        assertThat(ids.next()).isEqualTo(1_000_001);
    }

    @Test
    void shouldReportHowManyItHasIssued() {
        OrderIdAllocator ids = new OrderIdAllocator();
        assertThat(ids.issued()).isZero();

        ids.next();
        ids.next();

        assertThat(ids.issued()).isEqualTo(2);
    }

    @Test
    void shouldRejectANonPositiveFirstId() {
        assertThatThrownBy(() -> new OrderIdAllocator(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    /**
     * The reason this is an {@code AtomicLong} and not a {@code volatile long}. Two threads draw
     * at once, as the producer and a strategy will; a plain increment would hand the same id to
     * both, and the book would reject the second order.
     */
    @Test
    void shouldNeverIssueTheSameIdTwiceUnderContention() throws Exception {
        int threads = 8;
        int perThread = 50_000;
        OrderIdAllocator ids = new OrderIdAllocator();
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        CyclicBarrier gate = new CyclicBarrier(threads);

        Thread[] drawers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            drawers[i] = new Thread(() -> {
                try {
                    gate.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                for (int n = 0; n < perThread; n++) {
                    seen.add(ids.next());
                }
            });
            drawers[i].start();
        }
        for (Thread t : drawers) {
            t.join();
        }

        assertThat(seen)
                .as("every draw must be unique, so the set holds one entry per draw")
                .hasSize(threads * perThread);
        assertThat(ids.issued()).isEqualTo(threads * perThread);
    }
}
