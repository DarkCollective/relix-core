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

import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * A text cell that is not the temporal it is compared with fails naming the cell, not
 * only the two types (#123).
 */
@DisplayName("A cell that does not read as a temporal is named when it fails")
final class UnreadableCellTest {

    private static final String CLICKS = """
            Clicks := [
            | user | ts                   |
            |------|----------------------|
            | ann  | 2026-03-01T09:00:00Z |
            | ann  | 2026-03-1T10:45:00Z  |
            ];
            """;

    @Test
    @DisplayName("a comparison names the value and its column")
    void comparison() {
        try (Relix relix = Relix.builder().build()) {
            relix.define(CLICKS);
            assertThatThrownBy(() -> relix.relation("σ ts > TIMESTAMP '2026-03-01T09:30:00Z' (Clicks)").toList())
                    .hasMessageContaining("Cannot compare STRING with TIMESTAMP: "
                            + "cannot read '2026-03-1T10:45:00Z' (ts) as a TIMESTAMP");
        }
    }

    @Test
    @DisplayName("SESSIONIZE names the value and the column it orders by")
    void sessionize() {
        try (Relix relix = Relix.builder().build()) {
            relix.define(CLICKS);
            assertThatThrownBy(() -> relix.relation(
                    "SESSIONIZE ts GAP DURATION 'PT30M' PER user AS session (Clicks)").toList())
                    .hasMessageContaining("SESSIONIZE by ts: ")
                    .hasMessageContaining("cannot read '2026-03-1T10:45:00Z' as a TIMESTAMP");
        }
    }
}
