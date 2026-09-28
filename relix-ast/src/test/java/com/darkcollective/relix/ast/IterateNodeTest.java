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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static com.darkcollective.relix.ast.AstBuilders.iterate;
import static com.darkcollective.relix.ast.AstBuilders.recRef;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.rounds;
import static com.darkcollective.relix.ast.AstBuilders.untilConverged;
import static com.darkcollective.relix.ast.AstBuilders.untilStable;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** {@link IterateNode} and the three {@link IterateStop} clauses it carries. */
final class IterateNodeTest {

    private static final RelNode BASE = rel("Base");
    private static final RelNode STEP = recRef("R");
    private static final BigDecimal TENTH = new BigDecimal("0.1");

    @Nested
    class Node {

        @Test
        void exposesItsFields() {
            IterateNode node = iterate("R", BASE, STEP, rounds(3));
            assertThat(node.name()).isEqualTo("R");
            assertThat(node.base()).isSameAs(BASE);
            assertThat(node.step()).isSameAs(STEP);
            assertThat(node.stop()).isEqualTo(rounds(3));
            assertThat(node.location()).isEqualTo(SourceLocation.UNKNOWN);
        }

        @Test
        void isASetWhoseChildrenAreBaseThenStep() {
            IterateNode node = iterate("R", BASE, STEP, rounds(3));
            assertThat(node.materializationMode()).isEqualTo(MaterializationMode.SET);
            assertThat(node.children()).containsExactly(BASE, STEP);
        }

        @Test
        void mappingItsChildrenKeepsNameAndStop() {
            IterateNode node = iterate("R", BASE, STEP, untilStable(9));
            RelNode other = rel("Other");
            assertThat(node.mapChildren(child -> other))
                    .isEqualTo(iterate("R", other, other, untilStable(9)));
        }

        @Test
        void mappingToTheSameChildrenReturnsTheSameNode() {
            IterateNode node = iterate("R", BASE, STEP, rounds(3));
            assertThat(node.mapChildren(child -> child)).isSameAs(node);
        }

        @Test
        void rejectsABlankName() {
            assertThatIllegalArgumentException().isThrownBy(() -> iterate(" ", BASE, STEP, rounds(1)))
                    .withMessageContaining("name");
        }

        @Test
        void rejectsMissingParts() {
            assertThatNullPointerException().isThrownBy(() -> iterate(null, BASE, STEP, rounds(1)));
            assertThatNullPointerException().isThrownBy(() -> iterate("R", null, STEP, rounds(1)));
            assertThatNullPointerException().isThrownBy(() -> iterate("R", BASE, null, rounds(1)));
            assertThatNullPointerException().isThrownBy(() -> iterate("R", BASE, STEP, null));
        }
    }

    @Nested
    class Stop {

        @Test
        void roundsMayBeZeroButNotNegative() {
            assertThat(rounds(0).rounds()).isZero();
            assertThatIllegalArgumentException().isThrownBy(() -> rounds(-1))
                    .withMessageContaining("negative");
        }

        @Test
        void aCapMustBeAtLeastOne() {
            assertThatIllegalArgumentException().isThrownBy(() -> untilStable(0))
                    .withMessageContaining("at least 1");
            assertThatIllegalArgumentException().isThrownBy(
                            () -> untilConverged(List.of("x"), TENTH, List.of("k"), 0))
                    .withMessageContaining("at least 1");
        }

        @Test
        void convergenceNeedsColumnsAndKeys() {
            assertThatIllegalArgumentException().isThrownBy(
                            () -> untilConverged(List.of(), TENTH, List.of("k"), 5))
                    .withMessageContaining("at least one column");
            assertThatIllegalArgumentException().isThrownBy(
                            () -> untilConverged(List.of("x"), TENTH, List.of(), 5))
                    .withMessageContaining("key column");
        }

        @Test
        void aToleranceMayBeZeroButNotNegative() {
            assertThat(untilConverged(List.of("x"), BigDecimal.ZERO, List.of("k"), 5).tolerance())
                    .isZero();
            assertThatIllegalArgumentException().isThrownBy(
                            () -> untilConverged(List.of("x"), TENTH.negate(), List.of("k"), 5))
                    .withMessageContaining("negative");
        }

        @Test
        void convergenceRejectsMissingParts() {
            assertThatNullPointerException().isThrownBy(
                    () -> untilConverged(null, TENTH, List.of("k"), 5));
            assertThatNullPointerException().isThrownBy(
                    () -> untilConverged(List.of("x"), null, List.of("k"), 5));
            assertThatNullPointerException().isThrownBy(
                    () -> untilConverged(List.of("x"), TENTH, null, 5));
        }

        @Test
        void convergenceCopiesItsLists() {
            List<String> columns = new ArrayList<>(List.of("x"));
            IterateStop.Converged stop = untilConverged(columns, TENTH, List.of("k"), 5);
            columns.add("y");
            assertThat(stop.columns()).containsExactly("x");
        }

        @Test
        void eachClauseReadsAsItIsWritten() {
            assertThat(rounds(4).clause()).isEqualTo("ROUNDS 4");
            assertThat(untilStable(50).clause()).isEqualTo("UNTIL STABLE MAX 50 ROUNDS");
            assertThat(untilConverged(List.of("hub", "auth"), new BigDecimal("0.00010"),
                    List.of("node"), 100).clause())
                    .isEqualTo("UNTIL hub, auth WITHIN 0.00010 PER node MAX 100 ROUNDS");
        }
    }
}
