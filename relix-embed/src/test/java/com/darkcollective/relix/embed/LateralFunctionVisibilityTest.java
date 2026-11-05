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

import com.darkcollective.relix.ast.Expr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code Relation.lateral} names a table function, and a relation is a value pinned to
 * the declarations that existed when it was built. A function defined afterwards used to
 * surface as "No schema annotation" from the planner; it is now refused with the cause named.
 */
@DisplayName("Relation.lateral and the table functions a relation can see")
final class LateralFunctionVisibilityTest {

    private static final String ORDERS = "Orders := [| id | cid |\n| 1 | 10 |\n| 2 | 20 |];";
    private static final String ORDERS_FOR = "def ordersFor(c: NUMBER) : RELATION := { σ cid = c (Orders) };";

    @Test
    @DisplayName("a relation built after the function was defined can be joined to it")
    void builtAfter() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            relix.define(ORDERS_FOR);
            assertThat(relix.relation("π cid (Orders)").lateral("ordersFor", Expr.attr("cid")).toList())
                    .hasSize(2);
        }
    }

    @Test
    @DisplayName("a relation built before it says what is wrong when it runs, not with a planner invariant")
    void builtBefore() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            Relation early = relix.relation("π cid (Orders)");
            relix.define(ORDERS_FOR);
            Relation joined = early.lateral("ordersFor", Expr.attr("cid"));
            assertThatThrownBy(joined::toList)
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("table function it was not built with: ordersFor")
                    .hasMessageContaining("then build the relation again")
                    .hasMessageNotContaining("schema annotation");
        }
    }

    @Test
    @DisplayName("a name that is not a table function, a scalar function included, is refused the same way")
    void notATableFunction() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            Relation joined = relix.relation("π cid (Orders)").lateral("Upper", Expr.attr("cid"));
            assertThatThrownBy(joined::toList)
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("table function it was not built with: Upper");
        }
    }
}
