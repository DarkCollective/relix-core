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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.visitor.RelNodeVisitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The five {@code default} arms of {@link RelNodeVisitor}.
 *
 * <p>Those five node kinds are reached through a {@code default} that throws rather than
 * through an abstract method, so that a visitor with no meaningful answer for a covering
 * reduction, a least fixpoint, a correlated TVF join or a lineage reification inherits the
 * failure instead of being forced to carry a stub. That is a contract with two halves and
 * both are checked here: an overriding visitor is dispatched to, and a visitor that does
 * not override gets an {@link UnsupportedOperationException} naming the operator rather
 * than a silent {@code null}.
 */
@DisplayName("RelNodeVisitor default arms")
final class RelNodeVisitorDefaultsTest {

    private static final RelNode INPUT = rel("Edges");

    /** A visitor overriding nothing but the abstract arms — every default is inherited. */
    private static final RelNodeVisitor<String> BARE = new BareRelNodeVisitor<>() { };

    @Test
    void coverDefaultThrows() {
        assertThatThrownBy(() -> cover().accept(BARE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("COVER");
    }

    @Test
    void fixpointDefaultThrows() {
        assertThatThrownBy(() -> fixpoint("T", INPUT, INPUT).accept(BARE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("FIX");
    }

    @Test
    void recursiveRefDefaultThrows() {
        assertThatThrownBy(() -> recRef("T").accept(BARE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("RecursiveRefNode");
    }

    @Test
    void lateralJoinDefaultThrows() {
        assertThatThrownBy(() -> lateral(INPUT, "explode").accept(BARE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("LATERAL");
    }

    @Test
    void whyDefaultThrows() {
        assertThatThrownBy(() -> why(INPUT).accept(BARE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("WHY");
    }

    @Test
    @DisplayName("An overriding visitor is dispatched to instead of the default")
    void overridingVisitorWins() {
        RelNodeVisitor<String> overriding = new BareRelNodeVisitor<String>() {
            @Override public String visit(CoverNode node)        { return "cover"; }
            @Override public String visit(FixpointNode node)     { return "fix"; }
            @Override public String visit(RecursiveRefNode node) { return "ref"; }
            @Override public String visit(LateralJoinNode node)  { return "lateral"; }
            @Override public String visit(WhyNode node)          { return "why"; }
        };
        assertThat(cover().accept(overriding)).isEqualTo("cover");
        assertThat(fixpoint("T", INPUT, INPUT).accept(overriding)).isEqualTo("fix");
        assertThat(recRef("T").accept(overriding)).isEqualTo("ref");
        assertThat(lateral(INPUT, "explode").accept(overriding))
                .isEqualTo("lateral");
        assertThat(why(INPUT).accept(overriding)).isEqualTo("why");
    }

    private static CoverNode cover() {
        return AstBuilders.cover(2, false, INPUT);
    }
}
