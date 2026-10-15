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
package com.darkcollective.relix.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * {@code COUNT(DISTINCT x)} and its kin: each distinct non-NULL value of the argument
 * is read once per group, as SQL reads it.
 */
@DisplayName("DISTINCT inside an aggregate")
final class DistinctAggregateTest {

    /** Clicks, several per session: ann has 5 clicks in 2 sessions. */
    private static final String CLICKS = """
            S := [| user | session | amount |
            | ann  | s1 | 10   |
            | ann  | s1 | 10   |
            | ann  | s1 | 20   |
            | ann  | s2 | 20   |
            | ann  | s2 | NULL |
            | bob  | s3 | 5    |
            | bob  | NULL | 5.0 |];
            """;

    @Test
    @DisplayName("counts sessions where COUNT counts clicks, in one GROUP")
    void sessionsBesideClicks() {
        try (Relix relix = Relix.open()) {
            relix.define(CLICKS);
            assertThat(relix.relation(
                    "SORT user (GROUP user, COUNT(DISTINCT session) -> sessions, COUNT(*) -> clicks (S))"))
                    .tuples()
                    .extracting(t -> t.string("user") + " " + t.get("sessions").asDisplayString()
                            + " " + t.get("clicks").asDisplayString())
                    .containsExactly("ann 2 5", "bob 1 2");
        }
    }

    @Test
    @DisplayName("SUM and AVG read each value once; NULL is skipped; 5 and 5.0 are one value")
    void sumAndAverage() {
        try (Relix relix = Relix.open()) {
            relix.define(CLICKS);
            assertThat(relix.relation("SORT user (γ user, SUM(DISTINCT amount) → s, AVG(DISTINCT amount) → a,"
                    + " COUNT(DISTINCT amount) → n, MAX(DISTINCT amount) → hi (S))"))
                    .tuples()
                    .extracting(t -> t.string("user") + " " + t.get("s").asDisplayString()
                            + " " + t.get("a").asDisplayString() + " " + t.get("n").asDisplayString()
                            + " " + t.get("hi").asDisplayString())
                    .containsExactly("ann 30 15 2 20", "bob 5 5 1 5");
        }
    }

    @Test
    @DisplayName("over no rows a distinct count is 0, and a distinct sum NULL")
    void emptyInput() {
        try (Relix relix = Relix.open()) {
            relix.define(CLICKS);
            assertThat(relix.relation("γ COUNT(DISTINCT session) → n, SUM(DISTINCT amount) → s"
                    + " (σ user = 'nobody' (S))"))
                    .tuples()
                    .extracting(t -> t.get("n").asDisplayString() + " " + t.isNull("s"))
                    .containsExactly("0 true");
        }
    }

    @Test
    @DisplayName("COLLECT(DISTINCT x) keeps each value once, and unnesting it does not bring the copies back")
    void collectDistinct() {
        try (Relix relix = Relix.open()) {
            relix.define(CLICKS);
            assertThat(relix.relation("γ user, COLLECT(DISTINCT session) → sessions (σ user = 'ann' (S))"))
                    .tuples()
                    .extracting(t -> t.get("sessions").asDisplayString())
                    .containsExactly("[s1, s2]");
            assertThat(relix.relation(
                    "UNNEST sessions (γ user, COLLECT(DISTINCT session) → sessions (σ user = 'ann' (S)))"))
                    .hasRowCount(2);
        }
    }
}
