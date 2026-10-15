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

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UsingJoinNode — the column list a USING join is built on")
final class UsingJoinNodeTest {

    private static void refuses(List<String> columns, String message) {
        assertThatThrownBy(() -> new UsingJoinNode(UsingJoinNode.Kind.INNER, AstBuilders.rel("A"),
                AstBuilders.rel("B"), columns))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    @Test
    @DisplayName("needs a column, no blank or missing one, and none twice in any case")
    void columns() {
        refuses(List.of(), "USING needs at least one column");
        refuses(List.of(" "), "USING column must not be blank");
        refuses(Arrays.asList("id", null), "USING column must not be blank");
        refuses(List.of("id", "ID"), "USING names column 'ID' twice");
    }
}
