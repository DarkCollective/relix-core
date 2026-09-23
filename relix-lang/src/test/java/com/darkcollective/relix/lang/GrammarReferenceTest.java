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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelNodeCorpus;
import com.darkcollective.relix.lang.ast.ScriptCorpus;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.parser.Lexer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@code docs/reference/language/grammar.md} to the parser it describes.
 *
 * <p>The page is the one place a generator — a person, a tool, a language model — can read
 * the whole language from, so a rule on it that the parser does not keep is worse than no
 * rule at all: it produces text that looks right and does not parse. Nothing about prose
 * can be checked, so the page's grammar is run: {@link EbnfGrammar} reads the EBNF out of
 * the page's own fences and recognises input with it, and every input below is given to
 * both the grammar and {@link ScriptParser}. They must agree — accept together, reject
 * together — which checks the page in both directions: a form the parser takes and the page
 * omits, and a form the page permits that the parser refuses.
 *
 * <p>The parser is the oracle and nothing here says which answer is right, so the inputs
 * are chosen for coverage rather than for their expected outcome: every fenced example in
 * the manuals (a thousand-odd scripts somebody wrote to be read), every node kind and every
 * statement kind as the printers write them (so a new operator reaches this test the day its
 * corpus entry lands), and a table of the edge cases the page makes claims about.
 */
@DisplayName("The published grammar agrees with the parser")
final class GrammarReferenceTest {

    private static final String PAGE = "docs/reference/language/grammar.md";

    private static final EbnfGrammar GRAMMAR =
            EbnfGrammar.parse(EbnfGrammar.fromMarkdown(read(repoRoot().resolve(PAGE))), "script");

    @Nested
    @DisplayName("the grammar itself")
    final class WellFormed {

        @Test
        @DisplayName("every name it uses is a rule or a token class")
        void everyNameIsDefined() {
            assertThat(GRAMMAR.undefinedNames()).isEmpty();
        }

        @Test
        @DisplayName("every rule is reachable from script")
        void everyRuleIsReachable() {
            assertThat(GRAMMAR.unreachableRules())
                    .as("rules the page defines that no script can use")
                    .isEmpty();
        }

        @Test
        @DisplayName("reserved_word lists exactly the words the lexer reserves")
        void reservedWordsMatchTheLexer() {
            // The one list a generator most needs to be exact: a word on it must be
            // backticked in relation position, and a word missing from it would be
            // generated bare and read as an operator.
            assertThat(lower(GRAMMAR.literalsOf("reserved_word")))
                    .isEqualTo(new TreeSet<>(Lexer.reservedWords()));
        }

        @Test
        @DisplayName("every relation keyword is a reserved word")
        void relationKeywordsAreReserved() {
            assertThat(lower(GRAMMAR.literalsOf("reserved_word")))
                    .containsAll(lower(GRAMMAR.literalsOf("relation_keyword")));
        }
    }

    @Nested
    @DisplayName("agreement with ScriptParser")
    final class Agreement {

        @Test
        @DisplayName("over every fenced example in the manuals")
        void everyDocumentedExample() {
            Map<String, String> inputs = documentedExamples();
            assertThat(inputs).as("examples were found").hasSizeGreaterThan(500);
            assertAgreement(GRAMMAR, inputs);
        }

        @Test
        @DisplayName("over every RelNode kind, as the pretty-printer writes it")
        void everyNodeKind() {
            Map<String, String> inputs = new LinkedHashMap<>();
            for (RelNode node : RelNodeCorpus.everyKind()) {
                inputs.put(node.getClass().getSimpleName(),
                        "query { " + node.prettyPrint() + " };");
            }
            assertAgreement(GRAMMAR, inputs);
        }

        @Test
        @DisplayName("over every statement and source kind, as the script printer writes it")
        void everyStatementKind() {
            Map<String, String> inputs = new LinkedHashMap<>();
            List<Statement> statements = Stream.of(
                            ScriptCorpus.everyStatement(),
                            ScriptCorpus.everyPopulatedComponent(),
                            ScriptCorpus.everySourceDeclaration(),
                            ScriptCorpus.everyAssignmentForm())
                    .flatMap(List::stream).toList();
            for (Statement statement : statements) {
                String printed = ScriptPrinter.print(statement);
                inputs.put(printed, printed);
            }
            assertAgreement(GRAMMAR, inputs);
        }

        @Test
        @DisplayName("over the edge cases the page makes claims about")
        void edgeCases() {
            Map<String, String> inputs = new LinkedHashMap<>();
            for (String input : EDGE_CASES) {
                inputs.put(input, input);
            }
            assertAgreement(GRAMMAR, inputs);
        }
    }

    @Nested
    @DisplayName("the check can fail")
    final class NotVacuous {

        // Agreement is a claim about two recognisers, and a grammar that accepted
        // everything, or a harness that compared nothing, would pass it as easily as a
        // correct one. Each case breaks the page's grammar in one known place and asks
        // the corpus to notice.

        @Test
        @DisplayName("a grammar missing an operator rejects the examples that use it")
        void missingOperatorIsNoticed() {
            EbnfGrammar broken = mutate("| distinct | why |", "| why |");
            assertThat(disagreements(broken, documentedExamples())).isNotEmpty();
        }

        @Test
        @DisplayName("a grammar accepting a rejected form is caught by the relix-invalid examples")
        void overAcceptanceIsNoticed() {
            EbnfGrammar broken = mutate("comparison_op     ::= \"=\"",
                    "comparison_op     ::= \"<\" \">\" | \"=\"");
            assertThat(disagreements(broken, documentedExamples()))
                    .anyMatch(d -> d.contains("<>"));
        }

        @Test
        @DisplayName("losing the no-space rule for calls is caught by the edge cases")
        void callAdjacencyIsNoticed() {
            EbnfGrammar broken = mutate("CALL_OPEN ( argument", "\"(\" ( argument");
            Map<String, String> inputs = new LinkedHashMap<>();
            EDGE_CASES.forEach(e -> inputs.put(e, e));
            assertThat(disagreements(broken, inputs)).isNotEmpty();
        }

        private static EbnfGrammar mutate(String from, String to) {
            String text = EbnfGrammar.fromMarkdown(read(repoRoot().resolve(PAGE)));
            assertThat(text).as("the mutation's target is on the page").contains(from);
            return EbnfGrammar.parse(text.replace(from, to), "script");
        }
    }

    // ── the edge cases ───────────────────────────────────────────────────────────

    /**
     * Inputs chosen for where the grammar is subtle, and so where a page and a parser are
     * likeliest to disagree. No expected answer is recorded: the parser supplies it.
     */
    private static final List<String> EDGE_CASES = List.of(
            // sets are braces; a parenthesis opens one expression
            "query { σ status IN {'open', 'held'} (Orders) };",
            "query { σ status IN ('open', 'held') (Orders) };",
            "query { σ status IN ('open') (Orders) };",
            "query { σ x IN tags (R) };",
            "query { σ x IN {} (R) };",
            "query { σ x NOT IN {1} (R) };",
            "query { σ x ∉ {1} (R) };",
            // comparisons, and what is not one
            "query { σ status <> 'open' (Orders) };",
            "query { σ a != 1 ∧ b ≠ 2 ∧ c <= 3 ∧ d ≥ 4 (R) };",
            "query { σ x < -1 (R) };",
            "query { σ a - -1 > 2 (R) };",
            "query { σ -x > 1 (R) };",
            // a predicate always compares
            "query { σ active (R) };",
            "query { σ IsNull(x) (R) };",
            "query { σ IsNull(x) = true (R) };",
            "query { σ f(a, b > 1) = true (R) };",
            "query { π IIf(amount > 100, 'big', 'small') → size (Orders) };",
            // null tests, and that NULL is otherwise a name
            "query { σ x = NULL (Orders) };",
            "query { σ x = ⊥ ∨ y IS NOT NULL ∨ z IS ⊥ (R) };",
            "query { σ x IS ¬ ⊥ (R) };",
            "query { π Nz(x, NULL) → y (Orders) };",
            "query { π x → y (R) };",
            "query { π x AS y (R) };",
            // a predicate's truth value as an operand
            "query { σ (a > b) = ⊥ (R) };",
            "query { π (a > b) → f (R) };",
            "query { σ NOT x LIKE 'a%' ∧ y NOT LIKE 'b%' (R) };",
            // calls touch their name
            "query { π Round(price, 2) → p (R) };",
            "query { π Round (price) (Orders) };",
            "query { τ name (Users) };",
            "query { τ to_timestamp(logged) DESC (RawLogs) };",
            // joins carry their condition between operator and right input
            "query { Users ⨝ Users.id = Orders.user_id Orders };",
            "query { R ⨝ R.a = S.b S ∪ T ∩ U };",
            "query { R |>< R.a = S.b S };",
            "query { R ><| R.a = S.b S };",
            "query { R |><| R.a = S.b S };",
            "query { R LJOIN R.a = S.b S };",
            "query { R ASOF inner R.t >= S.t WITHIN DURATION 'PT5M' TIES(FIRST) S };",
            "query { R IJOIN OVERLAPS (R.s, R.e, S.s, S.e) S };",
            "query { R LATERAL recent(R.id, 3) };",
            "query { R⁺ OVER (a <-> b) };",
            "query { R* OVER (a, b) };",
            "query { DEE × Orders };",
            "query { R − S };",
            "query { R - S };",
            "query { R MINUS S EXCEPT T };",
            // aggregation
            "query { γ region (Orders) };",
            "query { γ region SUM(amount) (Orders) };",
            "query { γ SUM(amount), (Orders) };",
            "query { GROUP BY k, COUNT(*) (R) };",
            "query { γ k, COUNT(*) → n, ARGMAX(a, b) → w (R) };",
            "query { γ {k} SUM(a) (R) };",
            "query { γ SUM(*) (R) };",
            // sort, limit, top
            "query { ORDER BY x (R) };",
            "query { τ by (R) };",
            "query { λ 1.5 (R) };",
            "query { λ 10, 5 (R) };",
            "query { TOP 3 a DESC PER k (R) };",
            "query { TOP 3 a PER sum (R) };",
            "query { TOP 3 a (R) };",
            // reserved words as names
            "query { `sum` };",
            "query { π name (`order`) };",
            "query { π name (order) };",
            "count := { R }; query { count };",
            "Top := { R }; query Top;",
            "query { σ `true` = 1 (R) };",
            "query { σ true = 1 (R) };",
            "query { π {id, total: a * 2} → s (R) };",
            "query { π {`count`} → s (R) };",
            "query { π {count} → s (R) };",
            "query { FIX count (R, count) };",
            "query { FIX Reach (Edges, π src, dst (Reach ⋈ Edges)) };",
            // assorted operators
            "query { ρ (a) (R) };",
            "query { ρ (a → b, c) (R) };",
            "query { ρ S (a, b) (R) };",
            "query { OPTIMIZE MAXIMIZE SUM(v) SUBJECT TO SUM(w) <= 5 ∧ SUM(x) >= -1 PER k (R) };",
            "query { OPTIMIZE ALLOCATE (0, 1) MINIMIZE SUM(c) SUBJECT TO SUM(s) = 1 -> s (R) };",
            "query { OPTIMIZE ALLOCATE (0, 1) MINIMIZE SUM(c) SUBJECT TO SUM(s) = 1 (R) };",
            "query { SAMPLE 1 (R) };",
            "query { SAMPLE 0.5 SEED 7 (R) };",
            "query { SAMPLE 10 ROWS SEED 7 (R) };",
            "query { SAMPLE 0.5 ROWS (R) };",
            "query { PATH a, b HOPS 1 TO 3 AS d (E) };",
            "query { PATH a <-> b HOPS 2 AS d (E) };",
            "query { CLUSTER a <-> b AS c (E) };",
            "query { ROLLING AVG(p) OVER ALL ROWS τ t PER k AS a (R) };",
            "query { ROLLING AVG(p) OVER 3 ROWS ORDER t AS a (R) };",
            "query { WINDOW LAG(x, 1, 0) SORT t AS l (R) };",
            "query { WINDOW NTILE(4) SORT t AS l (R) };",
            "query { DOWNSAMPLE ts BY '5m' USING avg PER s FOR 10 ROWS (R) };",
            "query { μ items WITH ORDINALITY pos (R) };",
            "query { ∀ k : v > 0 (R) };",
            "query { FORALL : v > 0 (R) };",
            "query { UNPIVOT (a, b) AS (n, v) (R) };",
            "query { TREE id BY parent ORDER pos DESC AS kids (R) };",
            "query { ω (R) };",
            // the statement layer
            "source O from csv('o.csv') { schema: { id: NUMBER } };",
            "source O from csv(\"o.csv\") { schema: { id: NUMBER } header: false, };",
            "source D from json(\"d.json\");",
            "source D from warehouse { table: \"t\", schema: { `order`: NUMBER, a: [{ b: STRING }] } };",
            "source D from warehouse { table: \"t\", schema: { a: {} } };",
            "source H from http { url: \"u\", method: GET, auth: apikey(query(\"k\"), \"v\"),"
                    + " schema: { q: in STRING as query(\"q\") [required], n: NUMBER at \"$.n\" } };",
            "connection wh from jdbc { url: \"x\", user: app };",
            "connection wh from mongodb { anything: \"x\" };",
            "relate \"a\" A.x -> B.y;",
            "relate \"a\" A.x → B.y;",
            "relate symmetric \"a\" / \"b\" A(x, y) [0..*] -> B.c.d [1..1];",
            "query relix.plan;",
            "query { relix.plan };",
            "query {};",
            "query { Orders }; // every order",
            "private import \"x.relix\";",
            "import { A, B, } from \"x.relix\";",
            "def f(): NUMBER := { 1 };",
            "def f(x: NUMBER, y: BOOLEAN): relation := { λ 1 (R) };",
            "env;",
            "namespace a; env from \"x\" using \"dev\"; query { R };",
            "namespace a; env from \"x\"; namespace b;",
            "query { R }; namespace a;",
            "x := csv[\n a,b\n 1,2\n];",
            "x := [ | a | b |\n |---|---|\n | 1 | 2 | ] references { a -> Y.a };",
            "x := [ ];",
            "x := [ |---| ];",
            "x := { R } references { a -> Y.a };",
            "query { σ x = 'a\\nb' (R) };",
            "query { σ ts > TIMESTAMP '2026-03-01T09:30:00Z' (R) };");

    // ── the harness ──────────────────────────────────────────────────────────────

    private static void assertAgreement(EbnfGrammar grammar, Map<String, String> inputs) {
        assertThat(disagreements(grammar, inputs))
                .as("""
                        inputs on which the grammar in %s and ScriptParser disagree. \
                        'parser only' is a form the page does not describe — add it to the \
                        grammar; 'grammar only' is a form the page permits and the parser \
                        rejects — tighten the rule, or note the restriction in 'Rules the \
                        grammar does not show' if EBNF cannot say it""", PAGE)
                .isEmpty();
    }

    private static List<String> disagreements(EbnfGrammar grammar, Map<String, String> inputs) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            boolean parser = parses(input.getValue());
            EbnfGrammar.Result result = grammar.recognise(input.getValue());
            if (parser != result.accepted()) {
                out.add((parser ? "parser only" : "grammar only") + " — " + input.getKey()
                        + (parser ? "\n    " + result.diagnostic() : "")
                        + "\n    " + oneLine(input.getValue()));
            }
        }
        return out;
    }

    private static boolean parses(String source) {
        try {
            ScriptParser.parse(source);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── the documented examples ──────────────────────────────────────────────────

    /** The corpus ReferenceExampleParseTest keeps parseable, keyed by where each came from. */
    private static Map<String, String> documentedExamples() {
        Path root = repoRoot();
        Map<String, String> out = new LinkedHashMap<>();
        List<ReferenceExamples.Example> examples = ReferenceExamples.extractAll(
                root.resolve("docs/reference"), root.resolve(".claude"),
                root.resolve("relix-site/content"));
        for (int i = 0; i < examples.size(); i++) {
            out.put(examples.get(i).location() + " #" + i, examples.get(i).parseInput());
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Set<String> lower(Set<String> words) {
        return words.stream().map(w -> w.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() > 160 ? flat.substring(0, 160) + " …" : flat;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
