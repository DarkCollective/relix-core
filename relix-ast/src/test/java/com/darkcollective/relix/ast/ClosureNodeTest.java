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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class ClosureNodeTest {

    private static final RelNode INPUT = new RelationNode("Edges");

    @Test
    void exposesItsFields() {
        var c = new ClosureNode(INPUT, "src", "dst", true);
        assertThat(c.input()).isSameAs(INPUT);
        assertThat(c.fromColumn()).isEqualTo("src");
        assertThat(c.toColumn()).isEqualTo("dst");
        assertThat(c.reflexive()).isTrue();
    }

    @Test
    void unboundedByDefault() {
        var c = new ClosureNode(INPUT, "src", "dst", true);
        assertThat(c.boundSource()).isEmpty();
        assertThat(c.boundTarget()).isEmpty();
    }

    @Test
    void withBoundsCarriesEndpointBoundsAndPreservesOtherFields() {
        Operand lit = new NumberOperand("1");
        var c = new ClosureNode(INPUT, "src", "dst", true)
                .withBounds(Optional.of(lit), Optional.empty());
        assertThat(c.boundSource()).contains(lit);
        assertThat(c.boundTarget()).isEmpty();
        assertThat(c.input()).isSameAs(INPUT);
        assertThat(c.fromColumn()).isEqualTo("src");
        assertThat(c.reflexive()).isTrue();
    }

    @Test
    void prettyPrintAnnotatesBoundEndpoints() {
        var c = new ClosureNode(INPUT, "src", "dst", false)
                .withBounds(Optional.of(new NumberOperand("1")),
                        Optional.of(new NumberOperand("4")));
        assertThat(c.prettyPrint()).isEqualTo("CLOSURE src, dst ⟨src=1, dst=4⟩ (Edges)");
    }

    @Test
    void prettyPrintUnboundedHasNoAnnotation() {
        assertThat(new ClosureNode(INPUT, "src", "dst").prettyPrint())
                .isEqualTo("CLOSURE src, dst (Edges)");
    }

    @Test
    void rejectsNullBounds() {
        assertThatNullPointerException().isThrownBy(() ->
                new ClosureNode(INPUT, "src", "dst", false, null, Optional.empty(),
                        SourceLocation.UNKNOWN));
        assertThatNullPointerException().isThrownBy(() ->
                new ClosureNode(INPUT, "src", "dst", false, Optional.empty(), null,
                        SourceLocation.UNKNOWN));
    }

    @Test
    void transitiveConvenienceCtorDefaultsToNonReflexive() {
        assertThat(new ClosureNode(INPUT, "a", "b").reflexive()).isFalse();
    }

    @Test
    void rejectsBlankFromColumn() {
        assertThatThrownBy(() -> new ClosureNode(INPUT, " ", "dst", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromColumn");
    }

    @Test
    void rejectsBlankToColumn() {
        assertThatThrownBy(() -> new ClosureNode(INPUT, "src", " ", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toColumn");
    }

    @Test
    void rejectsNullInput() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ClosureNode(null, "src", "dst", false));
    }

    @Test
    void materializesAsSet() {
        assertThat(new ClosureNode(INPUT, "a", "b").materializationMode())
                .isEqualTo(MaterializationMode.SET);
    }
}
