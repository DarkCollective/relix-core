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
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link RelationFunctionCall} leaf node.
 */
final class RelationFunctionCallTest {

    @Test
    void isALeafWithNoRelationChildren() {
        RelationFunctionCall call = new RelationFunctionCall("f",
                List.of(new NumberOperand("2")));
        assertThat(call.children()).isEmpty();
        assertThat(call.mapChildren(UnaryOperator.identity())).isSameAs(call);
    }

    @Test
    void streamsByDefault() {
        RelationFunctionCall call = new RelationFunctionCall("f", List.of());
        assertThat(call.materializationMode()).isEqualTo(MaterializationMode.STREAM);
    }

    @Test
    void prettyPrintsAsNameWithArguments() {
        RelationFunctionCall call = new RelationFunctionCall("recentOrders",
                List.of(new NumberOperand("2")));
        assertThat(call.prettyPrint()).isEqualTo("recentOrders(2)");
    }

    @Test
    void prettyPrintsZeroArgCall() {
        assertThat(new RelationFunctionCall("activeUsers", List.of()).prettyPrint())
                .isEqualTo("activeUsers()");
    }

    @Test
    void argumentsAreDefensivelyCopied() {
        var args = new java.util.ArrayList<Operand>();
        args.add(new NumberOperand("1"));
        RelationFunctionCall call = new RelationFunctionCall("f", args);
        args.clear();
        assertThat(call.arguments()).hasSize(1);
    }

    @Test
    void rejectsBlankFunctionName() {
        assertThatThrownBy(() -> new RelationFunctionCall("  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
