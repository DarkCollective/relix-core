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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.Ordering;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrderingMaps — an ordering through a projection or a rename")
final class OrderingMapsTest {

    private static Ordering project(Ordering input, String... columns) {
        var attributes = java.util.Arrays.stream(columns).map(c -> projected(attr(c))).toList();
        return OrderingMaps.throughProjection(input, attributes,
                i -> OrderingMaps.nameOf(attributes.get(i)));
    }

    @Test
    @DisplayName("a qualified key and a qualified column match only on the same relation")
    void qualifiedNames() {
        Ordering byT = Ordering.of(List.of(asc("T.price")));

        assertThat(project(byT, "T.price")).isEqualTo(Ordering.of(List.of(asc("price"))));
        assertThat(project(byT, "U.price")).isEqualTo(Ordering.none());
    }

    @Test
    @DisplayName("a qualifier on one side only still names the same column")
    void oneSideQualified() {
        assertThat(project(Ordering.of(List.of(asc("price"))), "T.price"))
                .isEqualTo(Ordering.of(List.of(asc("price"))));
        assertThat(project(Ordering.of(List.of(asc("T.price"))), "PRICE"))
                .isEqualTo(Ordering.of(List.of(asc("PRICE"))));
    }

    @Test
    @DisplayName("a rename stops at a key it gives no name")
    void renameStops() {
        Ordering ordering = Ordering.of(List.of(asc("a"), asc("b")));
        assertThat(OrderingMaps.renamed(ordering, c -> c.equals("a") ? Optional.of("x") : Optional.empty()))
                .isEqualTo(Ordering.of(List.of(asc("x"))));
    }
}
