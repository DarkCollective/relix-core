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

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AST-contract tests for the lineage-reification node {@link WhyNode}
 * (ADR-0018, epic #317 slice 1).
 */
final class WhyNodeTest {

    private static final RelNode INPUT = new RelationNode("Orders");

    @Test
    void exposesInput() {
        assertThat(new WhyNode(INPUT).input()).isSameAs(INPUT);
    }

    @Test
    void rejectsNullInput() {
        assertThatNullPointerException().isThrownBy(() -> new WhyNode(null));
    }

    @Test
    void rejectsNullLocation() {
        assertThatNullPointerException().isThrownBy(() -> new WhyNode(INPUT, null));
    }

    @Test
    void convenienceConstructorUsesUnknownLocation() {
        assertThat(new WhyNode(INPUT).location()).isEqualTo(SourceLocation.UNKNOWN);
    }

    @Test
    void materializesAsBag() {
        assertThat(new WhyNode(INPUT).materializationMode())
                .isEqualTo(MaterializationMode.BAG);
    }

    @Test
    void exposesInputAsSoleChild() {
        assertThat(new WhyNode(INPUT).children()).containsExactly(INPUT);
    }

    @Test
    void mapChildrenRebuildsWithReplacedInput() {
        var w = new WhyNode(INPUT);
        RelNode replacement = new RelationNode("Other");
        RelNode mapped = w.mapChildren(child -> replacement);
        assertThat(mapped).isNode(WhyNode.class);
        assertThat(((WhyNode) mapped).input()).isSameAs(replacement);
    }

    @Test
    void mapChildrenReturnsSameInstanceWhenUnchanged() {
        var w = new WhyNode(INPUT);
        assertThat(w.mapChildren(child -> child)).isSameAs(w);
    }

    @Test
    void prettyPrints() {
        assertThat(new WhyNode(INPUT).prettyPrint()).isEqualTo("ω (Orders)");
    }

    @Test
    void acceptDispatchesToVisitWhy() {
        var w = new WhyNode(INPUT);
        String result = w.accept(new BareRelNodeVisitor<String>() {
            @Override public String visit(WhyNode node) { return "why!"; }
        });
        assertThat(result).isEqualTo("why!");
    }

    @Test
    void defaultVisitorArmThrows() {
        // A visitor that does not override visit(WhyNode) inherits the throwing default.
        RelNodeVisitor<String> bare = new BareRelNodeVisitor<>() { };
        assertThatThrownBy(() -> new WhyNode(INPUT).accept(bare))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("WHY");
    }
}
