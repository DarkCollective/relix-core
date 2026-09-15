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

import java.util.List;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class SessionizeNodeTest {

    private static final RelNode INPUT = new RelationNode("Events");
    private static final Operand GAP = new DurationOperand(java.time.Duration.ofMinutes(30));

    @Test
    void exposesItsFields() {
        var s = new SessionizeNode(INPUT, "ts", GAP, List.of("user_id"), "session");
        assertThat(s.input()).isSameAs(INPUT);
        assertThat(s.orderColumn()).isEqualTo("ts");
        assertThat(s.threshold()).isSameAs(GAP);
        assertThat(s.partitionKeys()).containsExactly("user_id");
        assertThat(s.sessionColumn()).isEqualTo("session");
    }

    @Test
    void rejectsBlankOrderColumn() {
        assertThatThrownBy(() -> new SessionizeNode(INPUT, " ", GAP, List.of(), "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderColumn");
    }

    @Test
    void rejectsBlankSessionColumn() {
        assertThatThrownBy(() -> new SessionizeNode(INPUT, "ts", GAP, List.of(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionColumn");
    }

    @Test
    void rejectsNullInput() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SessionizeNode(null, "ts", GAP, List.of(), "s"));
    }

    @Test
    void rejectsNullThreshold() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SessionizeNode(INPUT, "ts", null, List.of(), "s"));
    }

    @Test
    void partitionKeysAreDefensivelyCopied() {
        var keys = new java.util.ArrayList<>(List.of("a"));
        var s = new SessionizeNode(INPUT, "ts", GAP, keys, "session");
        keys.add("b");
        assertThat(s.partitionKeys()).containsExactly("a");
    }

    @Test
    void materializesAsBag() {
        assertThat(new SessionizeNode(INPUT, "ts", GAP, List.of(), "s").materializationMode())
                .isEqualTo(MaterializationMode.BAG);
    }

    @Test
    void exposesInputAsSoleChild() {
        var s = new SessionizeNode(INPUT, "ts", GAP, List.of("u"), "session");
        assertThat(s.children()).containsExactly(INPUT);
    }

    @Test
    void mapChildrenRebuildsWithReplacedInput() {
        var s = new SessionizeNode(INPUT, "ts", GAP, List.of("u"), "session");
        RelNode replacement = new RelationNode("Other");
        RelNode mapped = s.mapChildren(child -> replacement);
        assertThat(mapped).isNode(SessionizeNode.class);
        assertThat(((SessionizeNode) mapped).input()).isSameAs(replacement);
        assertThat(((SessionizeNode) mapped).sessionColumn()).isEqualTo("session");
        assertThat(((SessionizeNode) mapped).partitionKeys()).containsExactly("u");
    }

    @Test
    void mapChildrenReturnsSameInstanceWhenUnchanged() {
        var s = new SessionizeNode(INPUT, "ts", GAP, List.of(), "session");
        assertThat(s.mapChildren(child -> child)).isSameAs(s);
    }

    @Test
    void prettyPrintsWithPartition() {
        var s = new SessionizeNode(INPUT, "ts", GAP, List.of("user_id"), "session");
        assertThat(s.prettyPrint())
                .isEqualTo("SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events)");
    }

    @Test
    void prettyPrintsWithoutPartition() {
        var s = new SessionizeNode(INPUT, "seq", new NumberOperand("5"), List.of(), "run");
        assertThat(s.prettyPrint())
                .isEqualTo("SESSIONIZE seq GAP 5 AS run (Events)");
    }
}
