/*
 * Copyright 2026 Darkcollective, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.darkcollective.relix.events.internal;

import com.darkcollective.relix.events.QueryEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EventBuffer — the retaining listener behind relix.events")
final class EventBufferTest {

    private static QueryEvent event(String code) {
        return QueryEvent.of(QueryEvent.Stage.OPTIMIZE, code, "fired " + code);
    }

    @Nested
    @DisplayName("Retention")
    class Retention {

        @Test
        @DisplayName("retains observed events oldest-first")
        void retainsInArrivalOrder() {
            EventBuffer buffer = new EventBuffer();
            buffer.onEvent(event("SEL-001"));
            buffer.onEvent(event("PROJ-003"));
            buffer.onEvent(event("DIST-001"));

            assertThat(buffer.events()).map(QueryEvent::code)
                    .containsExactly("SEL-001", "PROJ-003", "DIST-001");
            assertThat(buffer.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("starts empty and drops nothing")
        void startsEmpty() {
            EventBuffer buffer = new EventBuffer();
            assertThat(buffer.events()).isEmpty();
            assertThat(buffer.size()).isZero();
            assertThat(buffer.droppedCount()).isZero();
        }

        @Test
        @DisplayName("events() is an immutable snapshot, unaffected by later appends")
        void snapshotIsIndependent() {
            EventBuffer buffer = new EventBuffer();
            buffer.onEvent(event("SEL-001"));
            List<QueryEvent> snapshot = buffer.events();

            buffer.onEvent(event("SEL-002"));

            assertThat(snapshot).hasSize(1);
            assertThat(buffer.events()).hasSize(2);
            assertThatThrownBy(() -> snapshot.add(event("X")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null event is ignored rather than retained")
        void ignoresNull() {
            EventBuffer buffer = new EventBuffer();
            buffer.onEvent(null);
            assertThat(buffer.events()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Bounded capacity")
    class Bounded {

        @Test
        @DisplayName("evicts the oldest event once full, keeping the most recent window")
        void evictsOldest() {
            EventBuffer buffer = new EventBuffer(3);
            IntStream.rangeClosed(1, 5).forEach(i -> buffer.onEvent(event("R-" + i)));

            assertThat(buffer.events()).map(QueryEvent::code)
                    .containsExactly("R-3", "R-4", "R-5");
            assertThat(buffer.size()).isEqualTo(3);
            assertThat(buffer.droppedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("never exceeds its capacity")
        void neverExceedsCapacity() {
            EventBuffer buffer = new EventBuffer(10);
            IntStream.rangeClosed(1, 1_000).forEach(i -> buffer.onEvent(event("R-" + i)));

            assertThat(buffer.size()).isEqualTo(10);
            assertThat(buffer.capacity()).isEqualTo(10);
            assertThat(buffer.droppedCount()).isEqualTo(990);
        }

        @Test
        @DisplayName("the default capacity is DEFAULT_CAPACITY")
        void defaultCapacity() {
            assertThat(new EventBuffer().capacity()).isEqualTo(EventBuffer.DEFAULT_CAPACITY);
        }

        @Test
        @DisplayName("a non-positive capacity is rejected")
        void rejectsNonPositiveCapacity() {
            assertThatThrownBy(() -> new EventBuffer(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity must be positive");
            assertThatThrownBy(() -> new EventBuffer(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Observation windows")
    class Windows {

        @Test
        @DisplayName("clear() empties the feed and resets the dropped count")
        void clearResetsWindow() {
            EventBuffer buffer = new EventBuffer(2);
            IntStream.rangeClosed(1, 5).forEach(i -> buffer.onEvent(event("R-" + i)));
            assertThat(buffer.droppedCount()).isEqualTo(3);

            buffer.clear();

            assertThat(buffer.events()).isEmpty();
            assertThat(buffer.size()).isZero();
            assertThat(buffer.droppedCount()).isZero();
        }

        @Test
        @DisplayName("a cleared buffer retains the next window from scratch")
        void reusableAcrossWindows() {
            EventBuffer buffer = new EventBuffer();
            buffer.onEvent(event("FIRST"));
            buffer.clear();
            buffer.onEvent(event("SECOND"));

            assertThat(buffer.events()).map(QueryEvent::code).containsExactly("SECOND");
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class Concurrency {

        @Test
        @DisplayName("concurrent producers lose no event and corrupt no state")
        void concurrentAppends() throws Exception {
            int threads = 8;
            int perThread = 250;
            EventBuffer buffer = new EventBuffer(threads * perThread);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int id = t;
                Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            buffer.onEvent(event(id + "-" + i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(buffer.size()).isEqualTo(threads * perThread);
            assertThat(buffer.events()).hasSize(threads * perThread);
            assertThat(buffer.droppedCount()).isZero();
        }
    }
}
