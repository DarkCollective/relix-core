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

import com.darkcollective.relix.ast.Comment;
import com.darkcollective.relix.ast.Spelling;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.lang.ast.ScriptComments;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A parsed script printed back keeps its comments, and can be spelled in ASCII. */
@DisplayName("ScriptPrinter — comments kept, and the ASCII spelling")
final class ScriptCommentsTest {

    /** Every comment a script carries, in the order the printer writes them. */
    private static List<String> texts(Script script) {
        ScriptComments comments = script.comments();
        List<String> out = new ArrayList<>();
        addAll(out, comments.namespace());
        for (int i = 0; i < script.statements().size(); i++) {
            addAll(out, comments.statement(i));
        }
        comments.footer().forEach(c -> out.add(c.text()));
        return out;
    }

    private static void addAll(List<String> out, ScriptComments.StatementComments around) {
        around.before().forEach(c -> out.add(c.text()));
        around.inside().forEach(c -> out.add(c.text()));
        around.after().forEach(c -> out.add(c.text()));
    }

    /** Prints, re-parses and re-prints {@code text}, asserting the comments survive both. */
    private static String printed(String text) {
        Script parsed = ScriptParser.parse(text);
        String printed = ScriptPrinter.print(parsed);
        Script reparsed = ScriptParser.parse(printed);

        assertThat(texts(reparsed)).as("printed as:%n%s", printed).isEqualTo(texts(parsed));
        assertThat(ScriptPrinter.print(reparsed)).as("printing is a fixed point").isEqualTo(printed);
        return printed;
    }

    @Nested
    @DisplayName("comments come back, in order, next to their code")
    final class Comments {

        @Test
        @DisplayName("before, between, inside and after statements, and at either end")
        void everywhere() {
            String text = """
                    -- what this script is for
                    namespace shop; -- the shop

                    -- Large orders only.
                    BigOrders := { σ amount > 100 (Orders) }; -- keep these

                    Recent := {
                        σ status = "shipped" (      -- shipped, not merely placed
                            σ amount > 50 (Orders)  -- and worth looking at
                        )
                    };
                    /* a block
                       comment */
                    query { Orders ⋈ /* joined */ BigOrders };

                    -- the end
                    """;
            Script parsed = ScriptParser.parse(text);

            assertThat(texts(parsed)).containsExactly(
                    "-- what this script is for", "-- the shop", "-- Large orders only.",
                    "-- keep these", "-- shipped, not merely placed", "-- and worth looking at",
                    "/* a block\n   comment */", "/* joined */", "-- the end");
            assertThat(printed(text)).isEqualTo("""
                    -- what this script is for
                    namespace shop; -- the shop

                    -- Large orders only.
                    BigOrders := { σ amount > 100 (Orders) }; -- keep these

                    Recent := { σ status = "shipped" (-- shipped, not merely placed
                    σ amount > 50 (Orders)) -- and worth looking at
                    };
                    /* a block
                       comment */
                    query { (Orders) ⋈ (/* joined */ BigOrders) };

                    -- the end
                    """);
        }

        @Test
        @DisplayName("are placed by the statement they were written next to")
        void placement() {
            Script parsed = ScriptParser.parse("""
                    -- before
                    A := { R }; -- after
                    B := { σ x = 1 /* inside */ (R) };
                    """);
            ScriptComments.StatementComments first = parsed.comments().statement(0);
            ScriptComments.StatementComments second = parsed.comments().statement(1);

            assertThat(first.before()).extracting(Comment::text).containsExactly("-- before");
            assertThat(first.after()).extracting(Comment::text).containsExactly("-- after");
            assertThat(second.inside()).extracting(Comment::text).containsExactly("/* inside */");
            assertThat(parsed.comments().statement(2)).isEqualTo(ScriptComments.StatementComments.NONE);
            assertThat(ScriptPrinter.print(parsed))
                    .contains("B := { σ x = 1 (/* inside */ R) };");
        }

        @Test
        @DisplayName("inside a statement with no expression, before its semicolon")
        void noExpression() {
            String printed = printed("""
                    source Orders from csv("orders.csv") { -- the export
                        header: true, schema: { id: NUMBER }
                    };
                    query Orders /* by name */;
                    """);

            assertThat(printed)
                    .contains("{ id: number } } -- the export\n;")
                    .contains("query Orders /* by name */ ;");
        }

        @Test
        @DisplayName("at the end of a table's row, on that row")
        void tableRow() {
            assertThat(printed("""
                    Goals := [
                    | id | minute |
                    |----|--------|
                    | 1  | 12     |  -- keepers score too
                    ];
                    """))
                    .contains("| 1 | 12 |  -- keepers score too\n];");
        }

        @Test
        @DisplayName("a block comment after a table's ], on its last row's line, before the semicolon")
        void blockCommentAfterTable() {
            assertThat(printed("""
                    Goals := [
                    | id | minute |
                    |----|--------|
                    | 1  | 12     |] /* one so far */;
                    """))
                    .contains("| 1 | 12 |\n] /* one so far */ ;");
        }

        @Test
        @DisplayName("at the end of several of a table's rows, each on its own row")
        void tableRows() {
            assertThat(printed("""
                    Goals := [
                    | id | minute |
                    |----|--------|
                    | 1  | 12     |  -- the first

                    | 2  | 30     |
                    | 3  | 55     |  -- and the third
                    ];
                    query { Goals };
                    """))
                    .contains("""
                            | 1 | 12 |  -- the first
                            | 2 | 30 |
                            | 3 | 55 |  -- and the third
                            ];
                            query""");
        }

        @Test
        @DisplayName("inside a def, before its body")
        void def() {
            assertThat(printed("""
                    def twice(x: NUMBER) : NUMBER := { -- double it
                        x * 2 };
                    def Big(R: RELATION(amount: NUMBER)) : RELATION := {
                        σ amount > 100 (R) -- the big ones
                    };
                    """))
                    .contains("def twice(x: number) : number := { -- double it\nx * 2 };")
                    .contains("(R) -- the big ones\n};");
        }

        @Test
        @DisplayName("inside the namespace declaration, before its semicolon")
        void namespaceInside() {
            assertThat(printed("namespace /* the */ shop;\nquery { R };\n"))
                    .startsWith("namespace shop /* the */ ;\n");
        }

        @Test
        @DisplayName("an HTTP column's direction, binding and modifier print as the grammar reads them")
        void httpColumns() {
            assertThat(printed("""
                    source Spell from http {
                        url: "https://example.org/spells/{index}",
                        schema: {
                            index: in STRING as path("index") [required],
                            lang:  in STRING as query("lang") [default: "en"],
                            name:  out STRING at "$.name"
                        }
                    };
                    """))
                    .contains("index: in string as path(\"index\") [required]")
                    .contains("lang: in string as query(\"lang\") [default: \"en\"]")
                    .contains("name: string at \"$.name\"");
        }

        @Test
        @DisplayName("a script with only comments keeps them")
        void onlyComments() {
            assertThat(printed("-- nothing yet\n/* or here */\n"))
                    .isEqualTo("-- nothing yet\n/* or here */\n");
        }

        @Test
        @DisplayName("a script that was built, not parsed, has none and prints as it did")
        void built() {
            Script built = ScriptBuilders.script(ScriptBuilders.query("Orders"));

            assertThat(built.comments()).isEqualTo(ScriptComments.NONE);
            assertThat(ScriptPrinter.print(built)).isEqualTo("query Orders;\n");
        }
    }

    @Nested
    @DisplayName("the ASCII spelling")
    final class Keywords {

        @Test
        @DisplayName("writes every operator as its keyword, and parses back to the same script")
        void keywords() {
            Script parsed = ScriptParser.parse("""
                    A := { π a → b (σ a ≠ 1 ∧ ¬(a ≤ 2) ∨ a ≥ 3 (R ⋈ S)) };
                    B := { γ k, SUM(v) → t (R ⟕ a = b S) ∪ (R − S) };
                    C := { σ a = ⊥ ∧ b ∉ {1, 2} (R ▷ a = b S) };
                    """);

            String keywords = ScriptPrinter.print(parsed, Spelling.KEYWORDS);

            assertThat(keywords).contains("PROJECT a -> b", "SELECT", " != 1", " AND ", "NOT(",
                    " <= 2", " OR ", " >= 3", " JOIN ", "GROUP k", " |>< ", " UNION ", " DIFF ",
                    " = NULL", " NOT IN ", " ANTI ");
            assertThat(keywords.chars().filter(c -> c > 127)).as(keywords).isEmpty();
            assertThat(ScriptPrinter.print(ScriptParser.parse(keywords)))
                    .isEqualTo(ScriptPrinter.print(parsed, Spelling.GLYPHS));
        }

        @Test
        @DisplayName("prints one statement the same way")
        void statement() {
            assertThat(ScriptPrinter.print(ScriptBuilders.query(
                    ScriptBuilders.rel("Orders")), Spelling.KEYWORDS))
                    .isEqualTo("query { Orders };");
        }

        @Test
        @DisplayName("of every reference-page example parses back to its glyph form")
        void everyReferenceExample() {
            Path root = repoRoot();
            List<String> differ = new ArrayList<>();
            List<String> lost = new ArrayList<>();
            int checked = 0;
            for (ReferenceExamples.Example example : ReferenceExamples.extractAll(
                    root.resolve("docs/reference"))) {
                if (!example.expectParses()) {
                    continue;
                }
                Script script;
                try {
                    script = ScriptParser.parse(example.parseInput());
                } catch (RuntimeException notAScript) {
                    continue;
                }
                checked++;
                String glyphs = ScriptPrinter.print(script, Spelling.GLYPHS);
                String keywords = ScriptPrinter.print(script, Spelling.KEYWORDS);
                if (!ScriptPrinter.print(ScriptParser.parse(keywords)).equals(glyphs)) {
                    differ.add(example.location() + "\n" + keywords);
                }
                if (!texts(ScriptParser.parse(glyphs)).equals(texts(script))) {
                    lost.add(example.location() + "\n" + glyphs);
                }
            }
            assertThat(checked).isGreaterThan(300);
            assertThat(differ).as("examples whose ASCII form reads back differently").isEmpty();
            assertThat(lost).as("examples whose comments did not survive printing").isEmpty();
        }
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/reference"))) {
            p = p.getParent();
        }
        return p;
    }
}
