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
 * NULL written as a value, and NULLIF, answering as PostgreSQL does.
 */
@DisplayName("NULL as a value, and NULLIF")
final class NullLiteralTest {

    private static final String ORDERS = """
            Orders := [| id | paid  | amount | qty |
            | 1  | true  | 10     | 2   |
            | 2  | false | 20     | 0   |
            | 3  | true  | NULL   | 5   |
            | 4  | true  | 40     | 4   |];
            """;

    @Test
    @DisplayName("COUNT(IIf(c, x, NULL)) counts only the rows the condition keeps")
    void conditionalCount() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(relix.relation("γ COUNT(IIf(paid = true, id, NULL)) → paid_orders,"
                    + " SUM(IIf(paid = true, amount, ⊥)) → paid_amount (Orders)"))
                    .tuples()
                    .extracting(t -> t.get("paid_orders").asDisplayString() + " "
                            + t.get("paid_amount").asDisplayString())
                    .containsExactly("3 50");
        }
    }

    @Test
    @DisplayName("a NULL branch is missing in the result, and a typed column beside it")
    void nullBranch() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(relix.relation("π id, IIf(paid = true, amount, NULL) → owed (Orders)"))
                    .tuples()
                    .extracting(t -> t.get("id").asDisplayString() + " " + t.isNull("owed"))
                    .containsExactly("1 false", "2 true", "3 true", "4 false");
        }
    }

    @Test
    @DisplayName("NULLIF returns NULL on a match, the value otherwise, and turns a zero divisor into NULL")
    void nullIf() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(relix.relation("π id, amount / NULLIF(qty, 0) → unit, NULLIF(amount, 40) → a,"
                    + " NULLIF(id, NULL) → same, NULLIF(NULL, id) → none (Orders)"))
                    .tuples()
                    .extracting(t -> t.get("id").asDisplayString() + " " + t.get("unit").asDisplayString()
                            + " " + t.get("a").asDisplayString() + " " + t.get("same").asDisplayString()
                            + " " + t.isNull("none"))
                    .containsExactly("1 5 10 1 true", "2 NULL 20 2 true", "3 NULL NULL 3 true",
                            "4 10 NULL 4 true");
            // 5 and 5.0 are one value; a number and a word are two.
            assertThat(relix.relation("π NULLIF(5, 5.0) → a, NULLIF(5, 'five') → b (UNIT)"))
                    .tuples()
                    .extracting(t -> t.isNull("a") + " " + t.get("b").asDisplayString())
                    .containsExactly("true 5");
        }
    }
}
