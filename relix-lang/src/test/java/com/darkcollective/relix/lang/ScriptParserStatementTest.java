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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // =========================================================================
    // Statement structure written wrong (#187)
    // =========================================================================

    @Nested
    @DisplayName("statement structure written wrong names the fix (#187)")
    class StructureTests {

        @Test
        @DisplayName("an expression written as a statement names query, at the token after its first name")
        void expressionAsStatement() {
            assertThatThrownBy(() -> parse("R := { S };\nR ANTI R.a = S.a S;"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessage("a result is written query { … }: write query { R ANTI R.a = S.a S }; (line 2, col 3)");
            assertThatThrownBy(() -> parse("orders"))
                    .hasMessage("a result is written query { … }: write query orders; (line 1, col 7)");
            // Read before the statement lexer meets the '>' it has no token for.
            assertThatThrownBy(() -> parse("SELECT total > 100 (orders);"))
                    .hasMessage("a result is written query { … }: write query { SELECT total > 100 (orders) };"
                            + " (line 1, col 8)");
            assertThatThrownBy(() -> parse("orders -- the orders; all of them\n  ;"))
                    .hasMessage("a result is written query { … }: write query orders; (line 2, col 3)");
            assertThatThrownBy(() -> parse("R /* kept */ ANTI\n  R.a = S.a S;"))
                    .hasMessageStartingWith("a result is written query { … }: write query { R ANTI R.a = S.a S };");
            // Not an expression, or an assignment: the parser's own message.
            assertThatThrownBy(() -> parse("orders customers;")).hasMessageContaining("Expected 'assign'");
            assertThatThrownBy(() -> parse("X = { R };")).hasMessageNotContaining("query");
            assertThat(parse("X /* the view */ := { R };").statements()).hasSize(1);
        }

        @Test
        @DisplayName("a ';' inside braces is reported at the ';', moved after the brace or splitting the expressions")
        void semicolonInBraces() {
            assertThatThrownBy(() -> parse("query { SELECT a > 1 (R); };"))
                    .hasMessage("Syntax error in RA expression: a ';' ends a statement and goes after the closing"
                            + " brace: write query { SELECT a > 1 (R) }; (line 1, col 25)");
            assertThatThrownBy(() -> parse("private  V :=\n{ R ; };"))
                    .hasMessageContaining(": write private V := { R };");
            assertThatThrownBy(() -> parse("query {\n  SELECT a > 1 -- big ones\n    (R);\n};"))
                    .hasMessageContaining(": write query { SELECT a > 1 (R) };");
            assertThatThrownBy(() -> parse("query { R; S };"))
                    .hasMessageContaining("a query holds one expression; write each as its own:"
                            + " query { R }; query { S };");
            assertThatThrownBy(() -> parse("A := { R; SELECT a > 1 (A); PROJECT a (A) };"))
                    .hasMessageContaining("a view holds one expression; give the first its own name:"
                            + " A_1 := { R }; A_2 := { SELECT a > 1 (A_1) }; A := { PROJECT a (A_2) };")
                    .hasMessageEndingWith("(line 1, col 9)");
            // The expression before the ';' in error: its own error.
            assertThatThrownBy(() -> parse("query { SELECT a > (R); };"))
                    .hasMessageNotContaining("closing brace");
            // A ';' in a string is the string's.
            assertThat(parse("query { SELECT a = \"x;y\" (R) };").statements()).hasSize(1);
        }

        @Test
        @DisplayName("an unclosed brace is reported at the brace, with '}' put before the ';'")
        void unclosedBrace() {
            assertThatThrownBy(() -> parse("query { SELECT a > 1 (R) ;\nquery R;"))
                    .hasMessage("Unterminated brace block; put '}' before the ';':"
                            + " write query { SELECT a > 1 (R) }; (line 1, col 7)");
            assertThatThrownBy(() -> parse("def f(x: NUMBER): NUMBER := { x * 2 ;"))
                    .hasMessageContaining("write def f(x: NUMBER): NUMBER := { x * 2 };");
            // No ';' to put it before: the lexer's message.
            assertThatThrownBy(() -> parse("query { R"))
                    .hasMessage("Unterminated brace block (line 1, col 8)");
            assertThatThrownBy(() -> parse("query { ;"))
                    .hasMessage("Unterminated brace block (line 1, col 8)");
        }
    }
}
