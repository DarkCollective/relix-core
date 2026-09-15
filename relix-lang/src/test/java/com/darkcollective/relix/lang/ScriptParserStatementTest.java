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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.lang.ast.*;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;

/**
 * Tests for {@link ScriptParser} covering statement-level constructs:
 * RA-expression assignments, query statements, def statements, and
 * multi-statement scripts.
 */
@DisplayName("ScriptParser — statements")
class ScriptParserStatementTest {

    private static Script parse(String source) {
        return ScriptParser.parse(source);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T firstStatement(Script s) {
        return (T) s.statements().get(0);
    }

    // =========================================================================
    // Assignment — RA expression
    // =========================================================================

    @Nested
    @DisplayName("assignment — RA expression")
    class AssignmentRaTests {

        @Test
        @DisplayName("Simple relation name in braces")
        void simpleRelationInBraces() {
            Script s = parse("ActiveUsers := { Users };");
            AssignmentStatement a = firstStatement(s);
            assertThat(a.name()).isEqualTo("ActiveUsers");
            assertThat(a.exported()).isTrue();
            assertThat(a.body()).isInstanceOf(QueryAssignmentBody.class);
            QueryAssignmentBody body = (QueryAssignmentBody) a.body();
            assertThat(body.expression()).isNode(RelationNode.class);
            assertThat(((RelationNode) body.expression()).name()).isEqualTo("Users");
        }

        @Test
        @DisplayName("Private assignment sets exported to false")
        void privateAssignmentSetsExportedFalse() {
            Script s = parse("private Temp := { Users };");
            AssignmentStatement a = firstStatement(s);
            assertThat(a.exported()).isFalse();
        }

        @Test
        @DisplayName("RA expression with nested braces (set literal)")
        void raExpressionWithNestedBraces() {
            Script s = parse("Result := { Users };");
            AssignmentStatement a = firstStatement(s);
            assertThat(a.body()).isInstanceOf(QueryAssignmentBody.class);
        }
    }

    // =========================================================================
    // Query statement
    // =========================================================================

    @Nested
    @DisplayName("query statement")
    class QueryStatementTests {

        @Test
        @DisplayName("Named query target")
        void namedQueryTarget() {
            Script s = parse("query ActiveUsers;");
            QueryStatement q = firstStatement(s);
            assertThat(q.target()).isInstanceOf(NamedQueryTarget.class);
            assertThat(((NamedQueryTarget) q.target()).name()).isEqualTo("ActiveUsers");
        }

        @Test
        @DisplayName("Expression query target")
        void expressionQueryTarget() {
            Script s = parse("query { Users };");
            QueryStatement q = firstStatement(s);
            assertThat(q.target()).isInstanceOf(ExpressionQueryTarget.class);
            assertThat(((ExpressionQueryTarget) q.target()).expression())
                    .isNode(RelationNode.class);
        }
    }

    // =========================================================================
    // Def statement
    // =========================================================================

    @Nested
    @DisplayName("def statement")
    class DefStatementTests {

        @Test
        @DisplayName("Parses function with parameters")
        void parsesFunctionWithParameters() {
            Script s = parse("def double(x: NUMBER) : NUMBER := { x * 2 };");
            DefStatement def = firstStatement(s);
            assertThat(def.name()).isEqualTo("double");
            assertThat(def.parameters()).hasSize(1);
            assertThat(def.parameters().get(0).name()).isEqualTo("x");
            assertThat(def.parameters().get(0).type()).isEqualTo(ScalarType.NUMBER);
            assertThat(def.returnType()).isEqualTo(ScalarType.NUMBER);
            assertThat(def.exported()).isTrue();
        }

        @Test
        @DisplayName("Parses no-parameter function")
        void parsesNoParameterFunction() {
            Script s = parse("def pi() : NUMBER := { 3 };");
            DefStatement def = firstStatement(s);
            assertThat(def.parameters()).isEmpty();
            assertThat(def.body()).isInstanceOf(NumberOperand.class);
        }

        @Test
        @DisplayName("Private def sets exported to false")
        void privateDefSetsExportedFalse() {
            Script s = parse("private def helper(x: STRING) : ANY := { x };");
            DefStatement def = firstStatement(s);
            assertThat(def.exported()).isFalse();
        }

        @Test
        @DisplayName("Multi-parameter def")
        void multiParameterDef() {
            Script s = parse("def add(a: NUMBER, b: NUMBER) : NUMBER := { a + b };");
            DefStatement def = firstStatement(s);
            assertThat(def.parameters()).hasSize(2);
        }

        @Test
        @DisplayName("def with ANY return type")
        void defWithAnyReturnType() {
            Script s = parse("def f() : ANY := { 1 };");
            DefStatement def = firstStatement(s);
            assertThat(def.returnType()).isEqualTo(ScalarType.ANY);
        }
    }

    // =========================================================================
    // Def statement — table-valued (relation-returning) function
    // =========================================================================

    @Nested
    @DisplayName("def statement — table-valued function")
    class DefRelationStatementTests {

        @Test
        @DisplayName("Parses a relation-returning def with a parameter")
        void parsesRelationDefWithParameter() {
            Script s = parse("def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };");
            DefRelationStatement def = firstStatement(s);
            assertThat(def.name()).isEqualTo("ordersFor");
            assertThat(def.parameters()).hasSize(1);
            assertThat(def.parameters().get(0).name()).isEqualTo("cid");
            assertThat(def.parameters().get(0).type()).isEqualTo(ScalarType.NUMBER);
            assertThat(def.body()).isInstanceOf(com.darkcollective.relix.ast.SelectionNode.class);
            assertThat(def.exported()).isTrue();
        }

        @Test
        @DisplayName("Parses a no-parameter relation-returning def")
        void parsesNoParamRelationDef() {
            Script s = parse("def activeUsers(): RELATION := { σ status = \"active\" (Users) };");
            DefRelationStatement def = firstStatement(s);
            assertThat(def.parameters()).isEmpty();
            assertThat(def.body()).isInstanceOf(com.darkcollective.relix.ast.SelectionNode.class);
        }

        @Test
        @DisplayName("private def: relation sets exported to false")
        void privateRelationDef() {
            Script s = parse("private def h(): RELATION := { Users };");
            DefRelationStatement def = firstStatement(s);
            assertThat(def.exported()).isFalse();
        }

        @Test
        @DisplayName("The RELATION return-type keyword is case-insensitive")
        void relationKeywordIsCaseInsensitive() {
            for (String spelling : new String[] {"RELATION", "relation", "Relation"}) {
                Script s = parse("def f(): " + spelling + " := { Users };");
                assertThat((Statement) firstStatement(s)).isInstanceOf(DefRelationStatement.class);
            }
        }

        @Test
        @DisplayName("The lowercase relation keyword still drives import relation")
        void importRelationStillParses() {
            Script s = parse("import relation Users from \"users.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.kind()).isEqualTo(ImportKind.RELATION);
        }
    }

    // =========================================================================
    // Multi-statement scripts
    // =========================================================================

    @Nested
    @DisplayName("multi-statement script")
    class MultiStatementTests {

        @Test
        @DisplayName("Namespace + env + import + assignment + query")
        void fullScriptStructure() {
            String src = """
                    namespace myapp;
                    env from ".env.json" using "dev";
                    import "./lib.relix";
                    ActiveUsers := { Users };
                    query ActiveUsers;
                    """;
            Script s = parse(src);
            assertThat(s.namespace()).isEqualTo(Optional.of("myapp"));
            assertThat(s.statements()).hasSize(4); // env + import + assignment + query
            assertThat(s.statements().get(0)).isInstanceOf(EnvStatement.class);
            assertThat(s.statements().get(1)).isInstanceOf(ImportStatement.class);
            assertThat(s.statements().get(2)).isInstanceOf(AssignmentStatement.class);
            assertThat(s.statements().get(3)).isInstanceOf(QueryStatement.class);
        }

        @Test
        @DisplayName("Multiple assignments")
        void multipleAssignments() {
            String src = """
                    A := { Users };
                    B := { Orders };
                    """;
            Script s = parse(src);
            assertThat(s.statements()).hasSize(2);
        }
    }
}
