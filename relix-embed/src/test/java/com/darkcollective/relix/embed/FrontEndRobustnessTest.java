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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text the engine did not write is answered with a diagnostic, never an {@code Error} or an
 * internal exception: deep nesting, recursive definitions, duplicate columns, and printed
 * text that must read back.
 */
@DisplayName("Front-end robustness through the facade")
final class FrontEndRobustnessTest {

    private static final String T = "T := [\n| a | s |\n|---|---|\n| 1 | x |\n];\n";

    /** The smallest stack an ordinary host gives a thread: 1 MB. */
    private static <R> R onSmallStack(Supplier<R> work) {
        return onStack(1L << 20, work);
    }

    private static <R> R onStack(long bytes, Supplier<R> work) {
        AtomicReference<R> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", bytes);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure.get() != null) {
            throw new AssertionError("threw " + failure.get(), failure.get());
        }
        return result.get();
    }

    private static List<Diagnostic> validate(String script) {
        return onSmallStack(() -> {
            try (Relix relix = Relix.open()) {
                return relix.validate(script);
            }
        });
    }

    @Nested
    @DisplayName("Deep nesting is a diagnostic")
    class DeepNesting {

        private String nested(String open, String inner, String close, int n) {
            return T + "query { π " + open.repeat(n) + inner + close.repeat(n) + " -> z (T) };";
        }

        @ParameterizedTest
        @ValueSource(ints = {300, 3000, 30000})
        @DisplayName("function calls, arrays, structs, parentheses, CASE and type nesting")
        void nestedConstructs(int n) {
            assertRefused(nested("ABS(", "1", ")", n));
            assertRefused(nested("[", "1", "]", n));
            assertRefused(nested("{a: ", "1", "}", n));
            assertRefused(nested("(", "a", "+1)", n));
            assertRefused(nested("CASE WHEN a > 1 THEN 1 ELSE ", "2", " END", n));
            assertRefused(T + "query { σ " + "¬ ".repeat(n) + "a > 1 (T) };");
            assertRefused(T + "query { π " + "- ".repeat(n) + "a -> z (T) };");
            assertRefused(T + "query { σ " + "(".repeat(n) + "a > 1" + ")".repeat(n) + " (T) };");
            assertRefused("source X from csv(\"f.csv\") { schema: { a: " + "{b: ".repeat(n)
                    + "STRING" + "}".repeat(n) + " } };");
            assertRefused("source X from csv(\"f.csv\") { schema: { a: " + "[".repeat(n)
                    + "STRING" + "]".repeat(n) + " } };");
        }

        private void assertRefused(String script) {
            List<Diagnostic> diagnostics = validate(script);
            assertThat(diagnostics).filteredOn(Diagnostic::isError).isNotEmpty()
                    .allSatisfy(d -> assertThat(d.message()).containsAnyOf("nests more than",
                            "chains more than", "nests too deeply"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"σ a > 0 ∧ a > 1", "σ a > 0 ∨ a > 1", "π a + a"})
        @DisplayName("a flat chain of 100000 operators is refused where it would be walked recursively")
        void flatChains(String head) {
            String op = head.contains("∧") ? " ∧ a > 1" : head.contains("∨") ? " ∨ a > 1" : " + a";
            String script = T + "query { " + head + op.repeat(100_000) + (head.startsWith("π") ? " -> z" : "")
                    + " (T) };";
            assertThat(validate(script)).filteredOn(Diagnostic::isError)
                    .singleElement().satisfies(d -> assertThat(d.message()).contains("chains more than"));
        }

        @Test
        @DisplayName("a relation chain of 100000 operators is refused")
        void unionChain() {
            for (String op : new String[]{" ⊎ ", " ∪ ", " ⋈ ", " − "}) {
                assertThat(validate(T + "query { T" + (op + "T").repeat(100_000) + " };"))
                        .filteredOn(Diagnostic::isError).singleElement()
                        .satisfies(d -> assertThat(d.message()).contains("chains more than"));
            }
        }

        @Test
        @DisplayName("chains and nesting that stay within the caps still analyse, and run")
        void withinTheCaps() {
            String sum = T + "query { π a" + " + a".repeat(500) + " -> z (T) };";
            assertThat(validate(sum)).filteredOn(Diagnostic::isError).isEmpty();
            String union = T + "query { T" + " ⊎ T".repeat(250) + " };";
            assertThat(validate(union)).filteredOn(Diagnostic::isError).isEmpty();
            String calls = T + "query { π " + "ABS(".repeat(150) + "1" + ")".repeat(150) + " -> z (T) };";
            assertThat(validate(calls)).filteredOn(Diagnostic::isError).isEmpty();
            // and they run, on the same small stack
            long rows = onStack(4L << 20, () -> {
                try (Relix relix = Relix.open()) {
                    return (long) relix.script(union).get(0).toList().size();
                }
            });
            assertThat(rows).isEqualTo(251);
            long total = onStack(4L << 20, () -> {
                try (Relix relix = Relix.open()) {
                    return relix.script(sum).get(0).toList().size();
                }
            });
            assertThat(total).isEqualTo(1);
        }

        @Test
        @DisplayName("a tree built in code that is too deep to analyse is a diagnostic, not a StackOverflowError")
        void builtTreeBackstop() {
            // Built the way the parser would never build it: 100000 nested DISTINCTs, through
            // the AST builders, since the parser refuses the text.
            com.darkcollective.relix.ast.RelNode node = com.darkcollective.relix.ast.AstBuilders.rel("T");
            for (int i = 0; i < 100_000; i++) {
                node = com.darkcollective.relix.ast.AstBuilders.distinct(node);
            }
            Script deep = com.darkcollective.relix.lang.ast.ScriptBuilders.script(
                    Relix.parse(T).statements().get(0),
                    com.darkcollective.relix.lang.ast.ScriptBuilders.query(node));
            List<Diagnostic> diagnostics = onSmallStack(() -> {
                try (Relix relix = Relix.open()) {
                    return relix.validate(deep);
                }
            });
            assertThat(diagnostics).filteredOn(Diagnostic::isError).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("A recursive def is an analysis error")
    class RecursiveDefs {

        private void assertCycle(String script, String... mentions) {
            List<Diagnostic> diagnostics = validate(script);
            assertThat(diagnostics).filteredOn(Diagnostic::isError).singleElement().satisfies(d -> {
                assertThat(d.message()).contains("refers to itself").contains("FIX or ITERATE")
                        .contains(mentions);
                assertThat(d.location()).isPresent();
            });
        }

        @Test
        @DisplayName("a scalar def that calls itself")
        void scalarSelf() {
            assertCycle(T + "def f(x: NUMBER): NUMBER := { f(x) };\nquery { π f(a) -> z (T) };",
                    "Function 'f' refers to itself;");
        }

        @Test
        @DisplayName("two scalar defs that call each other, reported once with the path")
        void scalarMutual() {
            assertCycle(T + "def f(x: NUMBER): NUMBER := { g(x) };\n"
                    + "def g(x: NUMBER): NUMBER := { f(x) };\nquery { π f(a) -> z (T) };",
                    "through f → g → f");
        }

        @Test
        @DisplayName("a table-valued def that calls itself")
        void tableSelf() {
            assertCycle("def r(n: NUMBER): relation := { r(n) };\nquery { r(1) };",
                    "Function 'r' refers to itself;");
        }

        @Test
        @DisplayName("table-valued defs that call each other")
        void tableMutual() {
            assertCycle("def r(n: NUMBER): relation := { s(n) };\n"
                    + "def s(n: NUMBER): relation := { r(n) };\nquery { r(1) };",
                    "through");
        }

        @Test
        @DisplayName("a def that reaches itself through a view")
        void throughView() {
            assertCycle("def r(n: NUMBER): relation := { V };\nV := { r(1) };\nquery { V };",
                    "Function 'r' refers to itself");
        }

        @Test
        @DisplayName("a scalar def used inside a table-valued def that it is called from")
        void scalarInTable() {
            assertCycle(T + "def f(x: NUMBER): NUMBER := { x };\n"
                    + "def r(n: NUMBER): relation := { π r(n) -> z (T) };\nquery { r(1) };",
                    "Function 'r' refers to itself");
        }

        @Test
        @DisplayName("a def inside a subquery of its own body")
        void insideSubquery() {
            assertCycle(T + "def f(x: NUMBER): NUMBER := { (π f(x) (T)) };\nquery { π f(a) -> z (T) };",
                    "Function 'f' refers to itself");
        }

        @Test
        @DisplayName("a cycle through a predicate's call, and through a predicate's subquery")
        void throughPredicates() {
            assertCycle(T + "def f(x: NUMBER): NUMBER := { (π a (r(x))) };\n"
                    + "def r(n: NUMBER): relation := { σ f(a) = 1 (T) };\nquery { r(1) };",
                    "through");
            assertCycle(T + "def r(n: NUMBER): relation := { σ a = (π a (r(n))) (T) };\nquery { r(1) };",
                    "Function 'r' refers to itself;");
        }

        @Test
        @DisplayName("a cycle reached under another spelling of the name says so")
        void caseNote() {
            assertCycle(T + "def Foo(x: NUMBER): NUMBER := { foo(x) };\nquery { π foo(a) -> z (T) };",
                    "names are case-insensitive, so 'foo' is 'Foo'");
        }

        @Test
        @DisplayName("a cycle reached by a qualified name carries no case note")
        void qualifiedName() {
            List<Diagnostic> diagnostics = validate(T + "def f(x: NUMBER): NUMBER := { default.f(x) };\n"
                    + "query { π f(a) -> z (T) };");
            assertThat(diagnostics).filteredOn(d -> d.message().contains("refers to itself"))
                    .singleElement().satisfies(d -> assertThat(d.message())
                            .doesNotContain("case-insensitive"));
        }

        @Test
        @DisplayName("an overload of another arity is not a cycle")
        void overloadByArity() {
            assertThat(validate(T + "def f(x: NUMBER): NUMBER := { x };\n"
                    + "def f(x: NUMBER, y: NUMBER): NUMBER := { f(x) + y };\n"
                    + "query { π f(a, a) -> z (T) };")).filteredOn(Diagnostic::isError).isEmpty();
        }

        @Test
        @DisplayName("defs that call one another without a cycle are fine, as are overloads by arity")
        void acyclicIsFine() {
            assertThat(validate(T + "def f(x: NUMBER): NUMBER := { x + 1 };\n"
                    + "def g(x: NUMBER): NUMBER := { f(x) };\n"
                    + "def h(x: NUMBER, y: NUMBER): NUMBER := { g(x) + y };\n"
                    + "query { π h(a, a) -> z (T) };")).filteredOn(Diagnostic::isError).isEmpty();
        }
    }

    @Nested
    @DisplayName("Duplicate names are positioned diagnostics")
    class Duplicates {

        @Test
        @DisplayName("a source schema naming a column twice")
        void sourceSchema() {
            for (String source : new String[]{
                    "source X from csv(\"f.csv\") { schema: { a: NUMBER, a: STRING } };",
                    "source X from csv(\"f.csv\") { schema: { a: NUMBER, A: STRING } };"}) {
                List<Diagnostic> diagnostics = validate(source);
                assertThat(diagnostics).filteredOn(Diagnostic::isError).singleElement().satisfies(d -> {
                    assertThat(d.message()).contains("Source 'X'").contains("Duplicate column name");
                    assertThat(d.location()).isPresent();
                    assertThat(d.location().get().line()).isEqualTo(1);
                });
            }
        }

        @Test
        @DisplayName("a def parameter named twice")
        void defParameters() {
            for (String def : new String[]{
                    "def f(x: NUMBER, x: NUMBER): NUMBER := { x };",
                    "def f(x: NUMBER, X: NUMBER): NUMBER := { x };",
                    "def r(n: NUMBER, n: NUMBER): relation := { UNIT };"}) {
                assertThat(validate(def)).filteredOn(Diagnostic::isError).singleElement().satisfies(d -> {
                    assertThat(d.message()).contains("declares parameter").contains("more than once");
                    assertThat(d.location()).isPresent();
                });
            }
        }
    }

    @Nested
    @DisplayName("Printed scripts read back")
    class RoundTrip {

        private void assertRoundTrips(String text) {
            Script parsed = Relix.parse(text);
            String printed = ScriptPrinter.print(parsed);
            Script reparsed = Relix.parse(printed);
            assertThat(reparsed.statements()).as(printed).usingRecursiveComparison()
                    .ignoringFieldsMatchingRegexes(".*location.*", ".*Location.*", ".*comments.*")
                    .isEqualTo(parsed.statements());
            assertThat(ScriptPrinter.print(reparsed)).isEqualTo(printed);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Z := csv[\na,b\n\"x]y\",2\n];",
                "Z := csv[\na,b\n\"x[y\",2\n];",
                "Z := csv[\na,b\n\"x\ny\",2\n];",
                "Z := csv[\na,b\n\"line one\nline two, with comma\",2\n];",
                "Z := csv[\na,b\n\"say \"\"hi\"\"]\",2\n];",
                "Z := csv[\na,b\n5\" pipe,2\n];",
                "Z := csv[\na,b\n\"\",2\n\"\"\"\",3\n];",
                "Z := [\n| a | b |\n|---|---|\n| 5\" pipe | 2 |\n];",
                "Z := [\n| a | b |\n|---|---|\n| 5\" pipe | 6\" pipe |\n];",
                "Z := [\n| a | b |\n|---|---|\n| \"x]y\" | 2 |\n];",
        })
        @DisplayName("inline tables whose cells hold brackets, quotes and line breaks")
        void inlineTableCells(String text) {
            assertRoundTrips(text);
        }

        @Test
        @DisplayName("a csv cell with a bracket or a line break keeps its value")
        void cellValuesSurvive() {
            Script parsed = Relix.parse("Z := csv[\na,b\n\"x]y\",2\n\"p\nq\",3\n];");
            try (Relix relix = Relix.open()) {
                relix.define(Relix.parse(ScriptPrinter.print(parsed)).statements()
                        .toArray(com.darkcollective.relix.lang.ast.Statement[]::new));
                List<Relation> rows = relix.script("query Z;");
                assertThat(rows.get(0).toList().toString()).contains("x]y").contains("p\nq");
            }
        }

        @Test
        @DisplayName("an unclosed quote in a csv table is an error at its row")
        void unclosedCsvQuote() {
            assertThat(validate("Z := csv[\na,b\n\"x,2\n];")).filteredOn(Diagnostic::isError).isNotEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"∪", "⊎", "⋈", "×", "−", "∩", "⊔"})
        @DisplayName("a chain of 249 binary operators prints flat and reads back")
        void longChains(String op) {
            String text = "query { T" + (" " + op + " T").repeat(249) + " };";
            Script parsed = Relix.parse(text);
            String printed = ScriptPrinter.print(parsed);
            Script reparsed = Relix.parse(printed);
            assertThat(ScriptPrinter.print(reparsed)).isEqualTo(printed);
            assertThat(printed).doesNotContain("((");
        }

        @Test
        @DisplayName("definitions() of a script with a long union chain reparses")
        void definitionsOfLongChain() {
            try (Relix relix = Relix.open()) {
                relix.define(Relix.parse(T + "V := { T" + " ∪ T".repeat(249) + " };").statements()
                        .toArray(com.darkcollective.relix.lang.ast.Statement[]::new));
                Relix.parse(relix.definitions());
            }
        }

        @Test
        @DisplayName("a comment on a left operand keeps its parentheses, so the comment stays put")
        void commentedLeftOperand() {
            String printed = ScriptPrinter.print(Relix.parse("q := { A /* c */ ∪ B ∪ C };"));
            assertThat(printed).contains("(/* c */ (A) ∪ (B)) ∪ (C)");
            assertThat(Relix.parse(printed).statements()).hasSize(1);
        }

        @Test
        @DisplayName("a right operand that is the same operator keeps its parentheses")
        void rightNestingIsKept() {
            Script parsed = Relix.parse("query { T ∪ (T ∪ T) };");
            String printed = ScriptPrinter.print(parsed);
            assertThat(Relix.parse(printed).statements()).usingRecursiveComparison()
                    .ignoringFieldsMatchingRegexes(".*location.*", ".*Location.*", ".*comments.*")
                    .isEqualTo(parsed.statements());
        }
    }

    @Nested
    @DisplayName("Positions")
    class Positions {

        @Test
        @DisplayName("an undefined query target is placed at its statement")
        void undefinedTarget() {
            assertThat(validate(T + "query nothing;")).filteredOn(Diagnostic::isError).singleElement()
                    .satisfies(d -> {
                        assertThat(d.message()).contains("Query target 'nothing' is not defined");
                        assertThat(d.location()).isPresent();
                        assertThat(d.location().get().line()).isEqualTo(6);
                    });
        }
    }

    @Nested
    @DisplayName("A byte-order mark")
    class ByteOrderMark {

        @Test
        @DisplayName("is skipped by validate and parse")
        void skipped() {
            assertThat(validate("\uFEFF" + T + "query T;")).filteredOn(Diagnostic::isError).isEmpty();
            assertThat(Relix.parse("\uFEFF" + T).statements()).hasSize(1);
        }
    }
}
