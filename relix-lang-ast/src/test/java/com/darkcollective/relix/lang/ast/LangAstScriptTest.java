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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for script-level AST nodes: {@link Script}, {@link EnvStatement},
 * {@link ImportStatement}, {@link AssignmentStatement}, {@link DefStatement},
 * and {@link QueryStatement}.
 */
@DisplayName("relix-lang-ast — script-level nodes")
final class LangAstScriptTest {

    // =========================================================================
    // Script
    // =========================================================================

    @Nested
    @DisplayName("Script")
    class ScriptTests {

        @Test
        @DisplayName("Stores namespace and statements")
        void storesNamespaceAndStatements() {
            QueryStatement q = new QueryStatement(new NamedQueryTarget("X"));
            Script s = new Script(Optional.of("myns"), List.of(q));
            assertThat(s.namespace()).isEqualTo(Optional.of("myns"));
            assertThat(s.statements()).hasSize(1);
        }

        @Test
        @DisplayName("Empty namespace is allowed")
        void emptyNamespaceAllowed() {
            Script s = new Script(Optional.empty(), List.of());
            assertThat(s.namespace()).isEmpty();
        }

        @Test
        @DisplayName("Statements list is unmodifiable")
        void statementsListIsUnmodifiable() {
            Script s = new Script(Optional.empty(), List.of());
            assertThatThrownBy(() -> s.statements().add(new QueryStatement(new NamedQueryTarget("X"))))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Rejects null namespace")
        void rejectsNullNamespace() {
            assertThatThrownBy(() -> new Script(null, List.of()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // EnvStatement
    // =========================================================================

    @Nested
    @DisplayName("EnvStatement")
    class EnvStatementTests {

        @Test
        @DisplayName("Stores filePath and activeEnv")
        void storesFields() {
            EnvStatement e = new EnvStatement(Optional.of("./.env.json"), Optional.of("dev"));
            assertThat(e.filePath()).isEqualTo(Optional.of("./.env.json"));
            assertThat(e.activeEnv()).isEqualTo(Optional.of("dev"));
        }

        @Test
        @DisplayName("defaults() produces empty optionals")
        void defaultsProducesEmptyOptionals() {
            EnvStatement e = EnvStatement.defaults();
            assertThat(e.filePath()).isEmpty();
            assertThat(e.activeEnv()).isEmpty();
        }
    }

    // =========================================================================
    // ImportStatement
    // =========================================================================

    @Nested
    @DisplayName("ImportStatement")
    class ImportStatementTests {

        @Test
        @DisplayName("Bulk import has no names")
        void bulkImportHasNoNames() {
            ImportStatement imp = new ImportStatement(ImportKind.BULK, List.of(), "./common.relix");
            assertThat(imp.names()).isEmpty();
            assertThat(imp.kind()).isEqualTo(ImportKind.BULK);
        }

        @Test
        @DisplayName("Typed import stores kind and names")
        void typedImportStoresKindAndNames() {
            ImportStatement imp = new ImportStatement(ImportKind.SOURCE,
                    List.of("current_weather"), "./weather.relix");
            assertThat(imp.kind()).isEqualTo(ImportKind.SOURCE);
            assertThat(imp.names()).containsExactly("current_weather");
        }

        @Test
        @DisplayName("Rejects blank sourcePath")
        void rejectsBlankSourcePath() {
            assertThatThrownBy(() -> new ImportStatement(ImportKind.BULK, List.of(), ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Names list is unmodifiable")
        void namesListIsUnmodifiable() {
            ImportStatement imp = new ImportStatement(ImportKind.FUNCTION,
                    List.of("tax"), "./calc.relix");
            assertThatThrownBy(() -> imp.names().add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // AssignmentStatement
    // =========================================================================

    @Nested
    @DisplayName("AssignmentStatement")
    class AssignmentStatementTests {

        @Test
        @DisplayName("of() factory creates exported assignment")
        void factoryCreatesExportedAssignment() {
            QueryAssignmentBody body = new QueryAssignmentBody(new RelationNode("Users"));
            AssignmentStatement a = AssignmentStatement.of("ActiveUsers", body);
            assertThat(a.exported()).isTrue();
            assertThat(a.name()).isEqualTo("ActiveUsers");
        }

        @Test
        @DisplayName("Rejects blank name")
        void rejectsBlankName() {
            QueryAssignmentBody body = new QueryAssignmentBody(new RelationNode("Users"));
            assertThatThrownBy(() -> AssignmentStatement.of("  ", body))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("private modifier sets exported to false")
        void privateModifierSetsExportedFalse() {
            QueryAssignmentBody body = new QueryAssignmentBody(new RelationNode("T"));
            AssignmentStatement a = new AssignmentStatement(false, "Temp", body);
            assertThat(a.exported()).isFalse();
        }
    }

    // =========================================================================
    // DefStatement
    // =========================================================================

    @Nested
    @DisplayName("DefStatement")
    class DefStatementTests {

        @Test
        @DisplayName("Stores name, parameters, return type, and body")
        void storesAllFields() {
            NumberOperand body = new NumberOperand("42");
            DefStatement def = DefStatement.of("answer",
                    List.of(new ParameterDefinition("x", ScalarType.NUMBER)),
                    ScalarType.NUMBER, body);
            assertThat(def.name()).isEqualTo("answer");
            assertThat(def.parameters()).hasSize(1);
            assertThat(def.returnType()).isEqualTo(ScalarType.NUMBER);
            assertThat(def.body()).isEqualTo(body);
        }

        @Test
        @DisplayName("Parameters list is unmodifiable")
        void parametersListIsUnmodifiable() {
            DefStatement def = DefStatement.of("f", List.of(), ScalarType.ANY, new NumberOperand("1"));
            assertThatThrownBy(() -> def.parameters().add(new ParameterDefinition("x", ScalarType.NUMBER)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Rejects blank name")
        void rejectsBlankName() {
            assertThatThrownBy(() -> DefStatement.of("", List.of(), ScalarType.ANY, new NumberOperand("1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Convenience constructor (without location) defaults to UNKNOWN location")
        void convenienceConstructorDefaultsToUnknownLocation() {
            var def = new DefStatement(true, "fn", List.of(), ScalarType.NUMBER,
                    new NumberOperand("1"));
            assertThat(def.location()).isEqualTo(com.darkcollective.relix.ast.SourceLocation.UNKNOWN);
            assertThat(def.exported()).isTrue();
        }

        @Test
        @DisplayName("Private def statement has exported=false")
        void privateDefNotExported() {
            var def = new DefStatement(false, "helper", List.of(), ScalarType.NUMBER,
                    new NumberOperand("0"));
            assertThat(def.exported()).isFalse();
        }
    }

    // =========================================================================
    // QueryStatement
    // =========================================================================

    @Nested
    @DisplayName("QueryStatement")
    class QueryStatementTests {

        @Test
        @DisplayName("Named target stores name")
        void namedTargetStoresName() {
            QueryStatement q = new QueryStatement(new NamedQueryTarget("USCities"));
            assertThat(q.target()).isInstanceOf(NamedQueryTarget.class);
            assertThat(((NamedQueryTarget) q.target()).name()).isEqualTo("USCities");
        }

        @Test
        @DisplayName("Expression target stores RelNode")
        void expressionTargetStoresRelNode() {
            RelationNode node = new RelationNode("Users");
            QueryStatement q = new QueryStatement(new ExpressionQueryTarget(node));
            assertThat(q.target()).isInstanceOf(ExpressionQueryTarget.class);
        }

        @Test
        @DisplayName("NamedQueryTarget rejects blank name")
        void namedTargetRejectsBlankName() {
            assertThatThrownBy(() -> new NamedQueryTarget("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
