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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class ProduceBoundTest {

    private static final Operand FIVE = new NumberOperand("5");

    @Test
    void exposesFieldsAndInclusivity() {
        var lt = new ProduceBound("n", ComparisonOperator.LESS, FIVE);
        assertThat(lt.column()).isEqualTo("n");
        assertThat(lt.operator()).isEqualTo(ComparisonOperator.LESS);
        assertThat(lt.limit()).isEqualTo(FIVE);
        assertThat(lt.inclusive()).isFalse();

        assertThat(new ProduceBound("n", ComparisonOperator.LESS_EQUAL, FIVE).inclusive()).isTrue();
        assertThat(new ProduceBound("n", ComparisonOperator.EQUAL, FIVE).inclusive()).isTrue();
    }

    @Test
    void rejectsNonUpperBoundOperators() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProduceBound("n", ComparisonOperator.GREATER, FIVE))
                .withMessageContaining("upper bound");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProduceBound("n", ComparisonOperator.NOT_EQUAL, FIVE));
    }

    @Test
    void rejectsBlankColumnAndNulls() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProduceBound(" ", ComparisonOperator.LESS, FIVE));
        assertThatNullPointerException()
                .isThrownBy(() -> new ProduceBound("n", null, FIVE));
        assertThatNullPointerException()
                .isThrownBy(() -> new ProduceBound("n", ComparisonOperator.LESS, null));
    }

    @Test
    void relationNodeCarriesAndPrettyPrintsTheBound() {
        var leaf = new RelationNode("Naturals");
        assertThat(leaf.produceBound()).isEmpty();
        assertThat(leaf.prettyPrint()).isEqualTo("Naturals");

        var bounded = leaf.withProduceBound(new ProduceBound("n", ComparisonOperator.LESS, FIVE));
        assertThat(bounded.produceBound()).isPresent();
        assertThat(bounded.name()).isEqualTo("Naturals");
        assertThat(bounded.prettyPrint()).isEqualTo("Naturals ⟨produce while n < 5⟩");
    }

    @Test
    void relationNodeRejectsNullProduceBound() {
        assertThatNullPointerException().isThrownBy(
                () -> new RelationNode("R", (Optional<ProduceBound>) null, SourceLocation.UNKNOWN));
    }
}
