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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.TruthRelationNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cost, cardinality, and relation properties of the nullary truth-relation
 * literals (issue #51). Their cardinality is <em>exact</em> — 1 or 0 — rather than
 * an estimate, because a zero-column relation admits no other possibility.
 */
@DisplayName("Truth relations — cost, cardinality, and properties")
final class TruthRelationCostTest {

    private static TruthRelationNode unit()  { return TruthRelationNode.unit(SourceLocation.UNKNOWN); }

    private static TruthRelationNode empty() { return TruthRelationNode.empty(SourceLocation.UNKNOWN); }

    @Nested
    @DisplayName("cardinality")
    class Cardinality {

        @Test
        @DisplayName("UNIT is exactly one row")
        void unitIsOneRow() {
            assertThat(new CostEstimator(null).estimateRows(unit())).isEqualTo(OptionalLong.of(1L));
        }

        @Test
        @DisplayName("EMPTY is exactly zero rows")
        void emptyIsZeroRows() {
            assertThat(new CostEstimator(null).estimateRows(empty())).isEqualTo(OptionalLong.of(0L));
        }

        @Test
        @DisplayName("the cardinality is known even with no symbol table or statistics")
        void needsNoStatistics() {
            // A base relation with no table resolves to "unknown"; the literal never does.
            var est = new CostEstimator(null);
            assertThat(est.estimateRows(rel("Trades"))).isEmpty();
            assertThat(est.estimateRows(unit())).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("cost tier")
    class Tier {

        @Test
        @DisplayName("a literal materialised from nothing is the cheapest tier")
        void isInline() {
            assertThat(new CostEstimator(null).estimate(unit())).isEqualTo(CostTier.INLINE);
            assertThat(new CostEstimator(null).estimate(empty())).isEqualTo(CostTier.INLINE);
        }

        @Test
        @DisplayName("it does not raise the tier of the tree it sits in")
        void doesNotDominateAProduct() {
            RelNode product = product(rel("Trades"), unit());
            // The FILE leaf still decides — the literal contributes INLINE.
            assertThat(new CostEstimator(null).estimate(product)).isEqualTo(CostTier.FILE);
        }
    }

    @Nested
    @DisplayName("relation properties")
    class Properties {

        @Test
        @DisplayName("at most one row means whole-row distinct, with no source needed")
        void isDuplicateFree() {
            assertThat(PropertyDeriver.derive(unit()).isDuplicateFree()).isTrue();
            assertThat(PropertyDeriver.derive(empty()).isDuplicateFree()).isTrue();
        }

        @Test
        @DisplayName("a literal leaf is BOUNDED, so a blocking operator above it is legal")
        void isBounded() {
            assertThat(PropertyDeriver.boundedness(unit(), BoundednessSource.ALL_BOUNDED))
                    .isEqualTo(Boundedness.BOUNDED);
        }

        @Test
        @DisplayName("it delivers no row ordering")
        void deliversNoOrdering() {
            assertThat(OrderDeriver.derive(unit())).isEqualTo(Ordering.none());
        }
    }
}
