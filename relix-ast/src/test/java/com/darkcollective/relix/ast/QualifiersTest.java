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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Qualifiers.inScope — the relation names a subtree answers to")
final class QualifiersTest {

    @Test
    @DisplayName("a leaf answers to its own name, lowercased")
    void leaf() {
        assertThat(Qualifiers.inScope(rel("Orders"))).containsExactly("orders");
    }

    @Test
    @DisplayName("a relation-renaming ρ shadows what is beneath it")
    void renamingRhoShadows() {
        assertThat(Qualifiers.inScope(rename("V", List.of(), rel("Orders"))))
                .containsExactly("v");
    }

    @Test
    @DisplayName("a column-only ρ leaves the underlying names in scope")
    void columnOnlyRhoDoesNot() {
        RelNode renamed = rename(Optional.empty(), List.of("a", "b"), List.of(), rel("Orders"));
        assertThat(Qualifiers.inScope(renamed)).containsExactly("orders");
    }

    @Test
    @DisplayName("every leaf under an operator is reachable")
    void recursesThroughOtherOperators() {
        RelNode tree = select(cmp(attr("a"), ComparisonOperator.EQUAL, num("1")),
                product(rel("Orders"), join(rel("Customers"), rel("orders"), cmp(
                        attr("x"), ComparisonOperator.EQUAL, attr("y")))));
        assertThat(Qualifiers.inScope(tree)).containsExactlyInAnyOrder("orders", "customers");
    }

    @Test
    @DisplayName("a leaf with no relation name contributes nothing")
    void truthLiteral() {
        assertThat(Qualifiers.inScope(unitRel())).isEmpty();
    }

    @Test
    @DisplayName("the result is a fresh, mutable set")
    void freshSet() {
        var names = Qualifiers.inScope(rel("Orders"));
        names.add("extra");
        assertThat(Qualifiers.inScope(rel("Orders"))).containsExactly("orders");
    }

    @Test
    @DisplayName("a null tree is refused")
    void nullRefused() {
        assertThatThrownBy(() -> Qualifiers.inScope(null)).isInstanceOf(NullPointerException.class);
    }
}
