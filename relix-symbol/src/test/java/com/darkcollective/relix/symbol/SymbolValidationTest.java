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
package com.darkcollective.relix.symbol;

import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Argument validation on the symbol records a caller can build directly.
 *
 * <p>A symbol's namespace and declared name are the two halves of its key in a
 * {@link com.darkcollective.relix.symbol.table.SymbolTable}, so a blank one is rejected at
 * construction rather than producing an entry nothing can look up.
 */
@DisplayName("Symbol argument validation")
final class SymbolValidationTest extends SymbolTestSupport {

    @Nested
    @DisplayName("RelationFunctionSymbol")
    final class RelationFunctions {

        @Test
        void rejectsBlankNamespace() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RelationFunctionSymbol(" ", "ordersFor",
                            Provenance.USER, ShadowPolicy.PERMITTED, List.of(),
                            rel("Orders"), Optional.empty()))
                    .withMessageContaining("namespace must not be blank");
        }

        @Test
        void rejectsBlankDeclaredName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RelationFunctionSymbol("default", "",
                            Provenance.USER, ShadowPolicy.PERMITTED, List.of(),
                            rel("Orders"), Optional.empty()))
                    .withMessageContaining("declaredName must not be blank");
        }

        @Test
        void rejectsNullBody() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new RelationFunctionSymbol("default", "ordersFor",
                            Provenance.USER, ShadowPolicy.PERMITTED, List.of(),
                            null, Optional.empty()));
        }

        @Test
        @DisplayName("The builder rejects a blank name up front, not at build()")
        void builderRejectsBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RelationFunctionSymbol.builder(" "))
                    .withMessageContaining("Function name must not be blank");
        }

        @Test
        void builderRejectsNullName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> RelationFunctionSymbol.builder(null));
        }
    }

    @Nested
    @DisplayName("SystemRelationSymbol")
    final class SystemRelations {

        @Test
        void rejectsBlankNamespace() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SystemRelationSymbol("", "events",
                            Provenance.BUILTIN, ShadowPolicy.FORBIDDEN, schema("id"), List.of()))
                    .withMessageContaining("namespace must not be blank");
        }

        @Test
        void rejectsBlankDeclaredName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SystemRelationSymbol("relix", "  ",
                            Provenance.BUILTIN, ShadowPolicy.FORBIDDEN, schema("id"), List.of()))
                    .withMessageContaining("declaredName must not be blank");
        }

        @Test
        @DisplayName("Rows are copied defensively, inner maps included")
        void copiesRowsDefensively() {
            var rows = new java.util.ArrayList<Map<String, com.darkcollective.relix.ast.Operand>>();
            rows.add(new java.util.LinkedHashMap<>(
                    Map.of("id", new com.darkcollective.relix.ast.NumberOperand("1"))));
            var sym = new SystemRelationSymbol("relix", "events", Provenance.BUILTIN,
                    ShadowPolicy.FORBIDDEN, schema("id"), rows);
            rows.clear();
            assertThat(sym.rows()).hasSize(1);
            assertThat(sym.rows().get(0)).isUnmodifiable();
        }

        @Test
        @DisplayName("An empty row list is legal — a catalog placeholder is filled after inference")
        void acceptsEmptyRows() {
            var sym = new SystemRelationSymbol("relix", "events", Provenance.BUILTIN,
                    ShadowPolicy.FORBIDDEN, schema("id"), List.of());
            assertThat(sym.rows()).isEmpty();
        }
    }
}
