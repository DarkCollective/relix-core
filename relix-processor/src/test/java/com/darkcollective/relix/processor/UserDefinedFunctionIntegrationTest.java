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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.semantic.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

/**
 * End-to-end tests for user-defined scalar functions: parse → semantic
 * analysis → execution.
 *
 * <p>Each test uses a complete {@code .relix} script with a {@code def}
 * declaration and at least one {@code query} statement that exercises the
 * function.
 */
@DisplayName("User-defined functions — end-to-end")
final class UserDefinedFunctionIntegrationTest extends ProcessorTestSupport {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Analyzes, asserts no semantic errors, then executes inline-only. */
    private static List<QueryResult> run(String src) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        return EXECUTOR.execute(result);
    }

    // ── test cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Single-parameter functions")
    class SingleParam {

        @Test
        @DisplayName("double(x) := x * 2 — applied in a projection")
        void doubleInProjection() {
            var results = run(
                    "def double(x: NUMBER): NUMBER := { x * 2 };\n" +
                    "Numbers := [| n |\n" +
                    "            | 3 |\n" +
                    "            | 7 |];\n" +
                    "query { π double(n) -> doubled (Numbers) };"
            );
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("doubled", "6");
            assertThat(rows.get(1)).hasValue("doubled", "14");
        }

        @Test
        @DisplayName("negate(x) := -x — unary negation in body")
        void negateFunction() {
            var results = run(
                    "def negate(x: NUMBER): NUMBER := { -x };\n" +
                    "Vals := [| v |\n" +
                    "         | 5 |];\n" +
                    "query { π negate(v) -> neg (Vals) };"
            );
            assertThat(results).hasSize(1);
            Row row = results.get(0).rows().get(0);
            assertThat(row).hasValue("neg", "-5");
        }

        @Test
        @DisplayName("function used in a projection alongside a literal argument")
        void functionWithLiteralArg() {
            var results = run(
                    "def triple(x: NUMBER): NUMBER := { x * 3 };\n" +
                    "Nums := [| n |\n" +
                    "         | 4 |\n" +
                    "         | 2 |];\n" +
                    "query { π triple(n) -> t (Nums) };"
            );
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("t", "12");
            assertThat(rows.get(1)).hasValue("t", "6");
        }
    }

    @Nested
    @DisplayName("Multi-parameter functions")
    class MultiParam {

        @Test
        @DisplayName("add(a, b) := a + b — two NUMBER parameters")
        void addTwoNumbers() {
            var results = run(
                    "def add(a: NUMBER, b: NUMBER): NUMBER := { a + b };\n" +
                    "Pairs := [| x | y |\n" +
                    "          | 3 | 4 |\n" +
                    "          | 10| 20|];\n" +
                    "query { π add(x, y) -> sum (Pairs) };"
            );
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            assertThat(rows.get(0)).hasValue("sum", "7");
            assertThat(rows.get(1)).hasValue("sum", "30");
        }

        @Test
        @DisplayName("discount(price, pct) := price * (1 - pct / 100)")
        void discountFunction() {
            var results = run(
                    "def discount(price: NUMBER, pct: NUMBER): NUMBER := { price * (1 - pct / 100) };\n" +
                    "Products := [| price | pct |\n" +
                    "             | 200   | 10  |\n" +
                    "             | 500   | 20  |];\n" +
                    "query { π discount(price, pct) -> discounted (Products) };"
            );
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            // 200 * (1 - 0.1) = 180, 500 * (1 - 0.2) = 400
            assertThat(((com.darkcollective.relix.value.NumberValue) rows.get(0).get("discounted")).value())
                    .isEqualByComparingTo(BigDecimal.valueOf(180));
            assertThat(((com.darkcollective.relix.value.NumberValue) rows.get(1).get("discounted")).value())
                    .isEqualByComparingTo(BigDecimal.valueOf(400));
        }
    }

    @Nested
    @DisplayName("String functions")
    class StringFunctions {

        @Test
        @DisplayName("greeting(name) := 'Hello, ' + name — string concat in body")
        void stringFunction() {
            var results = run(
                    "def greeting(name: STRING): STRING := { \"Hello, \" + name };\n" +
                    "People := [| name  |\n" +
                    "           | Alice |\n" +
                    "           | Bob   |];\n" +
                    "query { π greeting(name) -> msg (People) };"
            );
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            assertThat(rows.get(0)).hasValue("msg", "Hello, Alice");
            assertThat(rows.get(1)).hasValue("msg", "Hello, Bob");
        }
    }

    @Nested
    @DisplayName("Function calling built-in in its body")
    class BodyCallsBuiltin {

        @Test
        @DisplayName("absDouble(x) := Abs(x) * 2 — UDF body calls built-in")
        void udfBodyCallsBuiltin() {
            var results = run(
                    "def absDouble(x: NUMBER): NUMBER := { Abs(x) * 2 };\n" +
                    "Vals := [| v  |\n" +
                    "         | -3 |\n" +
                    "         | 5  |];\n" +
                    "query { π absDouble(v) -> result (Vals) };"
            );
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            assertThat(((com.darkcollective.relix.value.NumberValue) rows.get(0).get("result")).value())
                    .isEqualByComparingTo(BigDecimal.valueOf(6));
            assertThat(((com.darkcollective.relix.value.NumberValue) rows.get(1).get("result")).value())
                    .isEqualByComparingTo(BigDecimal.valueOf(10));
        }
    }

    @Nested
    @DisplayName("Multiple functions in one script")
    class MultipleFunctions {

        @Test
        @DisplayName("two functions defined; both used in projections")
        void twoFunctions() {
            var results = run(
                    "def double(x: NUMBER): NUMBER := { x * 2 };\n" +
                    "def square(x: NUMBER): NUMBER := { x * x };\n" +
                    "Nums := [| n |\n" +
                    "         | 4 |\n" +
                    "         | 5 |];\n" +
                    "query { π n, double(n) -> d, square(n) -> sq (Nums) };"
            );
            assertThat(results).hasSize(1);
            List<Row> rows = results.get(0).rows();
            assertThat(rows.get(0)).hasValue("d", "8")
                    .hasValue("sq", "16");
            assertThat(rows.get(1)).hasValue("d", "10")
                    .hasValue("sq", "25");
        }
    }
}
