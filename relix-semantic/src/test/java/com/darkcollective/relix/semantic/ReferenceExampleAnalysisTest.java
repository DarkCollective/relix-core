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
package com.darkcollective.relix.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analyses every example in the language reference, not merely parsing it.
 *
 * <p>The fourth {@code docs/reference} guard, and the one that closes the distance
 * between the other two. {@code ReferenceExampleParseTest} proves a block is
 * well-formed; {@code WorkedExampleOutputTest} proves a worked example produces
 * what its page prints, which it can only do for a block that contains a
 * {@code query}. In between sits everything else — for which the grammar was the
 * only reader.
 *
 * <p>Which is most of the manual, and the reach was measured rather than assumed:
 * 82 fenced blocks sit in {@code # Examples:} sections and 150 in a page's body,
 * of which 97 are executed by the worked-example guard. What was left is a block
 * that can be wrong in any way short of a parse error — {@code optimizer.md}
 * unioned a 1-column relation with a 2-column one for as long as the page existed,
 * and every gate stayed green.
 *
 * <p>That distance is not hypothetical. {@code why.md}'s "fully worked" Example 3
 * parsed perfectly and had never analysed since the page was written: {@code μ}
 * takes a column rather than a path, and nothing said so (#641). Analysis is also
 * the cheap check — it needs a schema, not data — so it reaches examples a worked
 * example never will.
 *
 * <h2>The fixture</h2>
 * Most examples name relations they do not declare, which is what makes them short
 * enough to read. {@code docs/reference/.fixtures.relix} declares those relations
 * once, and this guard prepends it. A block that declares its own relation shadows
 * the fixture's — a later definition wins — so a self-contained example is
 * unaffected by what the fixture happens to hold.
 *
 * <p>The fixture is therefore also the manual's vocabulary. An example that wants a
 * column no fixture relation has is asking the reader to imagine a table that
 * appears nowhere else; adding the column here, or moving the example onto a
 * relation that has it, is the point rather than the overhead.
 *
 * <h2>What is skipped</h2>
 * A block that declares a {@code source}, {@code connection} or {@code import}
 * describes a link to something outside the script. Its schema is whatever the
 * declaration says, so analysing it checks the example against itself; the parse
 * guard already covers the syntax.
 *
 * <p>So is a {@code # Syntax:} section, whole: it is the grammar written out over
 * placeholder names — {@code relate "Name" Source.col -> Target.col} declares
 * nothing and names nothing that could exist — which is the parse guard's business
 * and not this one's.
 */
@DisplayName("Reference examples analyse against the manual's fixture schema")
final class ReferenceExampleAnalysisTest {

    /**
     * One example, with where it came from and how it is to be read.
     *
     * <p>{@code fallback} is the second reading a <em>body</em> block is allowed —
     * the same block after everything its section declared before it. A body section
     * is not one sequence: {@code optimizer.md}'s prose is independent illustrations
     * that redefine {@code Orders} differently, while {@code repl.md}'s is a session
     * transcript whose blocks genuinely build. Rather than guess which a page is,
     * a body block passes if it analyses <em>either</em> way. An {@code # Examples:}
     * block has no fallback: those are a sequence by convention, and the stricter
     * reading is the one that has been catching things.
     */
    private record Example(Path page, int line, String body, String fallback) {
        String location() {
            return page.getFileName() + ":" + line;
        }
    }

    @Test
    @DisplayName("every reference example analyses without errors")
    void everyExampleAnalyses() {
        Path root = referenceRoot();
        String fixture = read(root.resolve(".fixtures.relix"));
        List<Example> examples = extractAll(root);

        // A floor rather than a presence check: an extractor that quietly stopped
        // matching most pages would still find "some" examples and pass, which is the
        // way a guard dies without anyone noticing.
        assertThat(examples)
                .as("examples were found — a count far below this means the extractor has "
                        + "stopped matching the page convention and the guard is checking "
                        + "much less than it appears to (fenced blocks and the single-line "
                        + "entries together)")
                .hasSizeGreaterThanOrEqualTo(430);
        assertThat(examples.stream().filter(e -> e.fallback() != null).count())
                .as("the body blocks — a floor of their own, because they were added to "
                        + "this guard second and an extractor that stopped finding them "
                        + "would leave the # Examples: count untouched and look healthy")
                .isGreaterThanOrEqualTo(120);

        List<String> failures = new ArrayList<>();
        for (Example example : examples) {
            String problem = analyse(fixture, example.body());
            if (problem != null && example.fallback() != null) {
                problem = analyse(fixture, example.fallback()) == null ? null : problem;
            }
            if (problem != null) {
                failures.add(example.location() + " :: " + problem);
            }
        }

        assertThat(failures)
                .as("""
                        reference examples the engine does not accept. An example that \
                        parses can still name a column that does not exist, call an \
                        operator with the wrong shape, or describe a pipeline the \
                        analyzer rejects — why.md's Example 3 did exactly that, and \
                        parsed, for as long as the page existed. Fix the example, or \
                        add what it needs to docs/reference/.fixtures.relix""")
                .isEmpty();
    }

    /** The problem with {@code body}, or null when the engine accepts it. */
    private static String analyse(String fixture, String body) {
        SemanticResult result;
        try {
            result = analyseWithStandIns(fixture, body);
        } catch (RuntimeException e) {
            return "threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return result.hasErrors() ? SemanticFixtures.errorMessages(result).toString() : null;
    }

    /** Names a relation the analyzer reported as undeclared. */
    private static final java.util.regex.Pattern UNDEFINED =
            java.util.regex.Pattern.compile("Undefined relation: '([A-Za-z_][A-Za-z0-9_]*)'");

    /**
     * Analyses {@code body}, then declares whatever it reported as an undefined
     * relation as an <em>open</em> (schema-on-read) source and analyses again.
     *
     * <h2>Why a stand-in rather than a fixture entry</h2>
     * The manual names about 150 relations. Roughly 25 recur — the running dataset
     * and its neighbours — and {@code .fixtures.relix} declares those with real
     * columns and types, so an example that uses one is checked against a schema.
     * The rest appear on one page each, chosen because a cosine example reads better
     * over {@code Wind(station, speed, direction_rad)} than over {@code Orders}. That
     * is the manual working correctly, and it should not be flattened onto a shared
     * vocabulary to satisfy a guard.
     *
     * <p>Declaring all 150 in the fixture would be worse than not checking them: the
     * columns would be <em>invented here</em>, so the guard would compare each
     * example against a schema this file made up rather than the one its author
     * meant — green, and checking nothing.
     *
     * <p>An open stand-in resolves any column, so what survives is every check that
     * does not depend on knowing the columns: operator shape, arity, union
     * compatibility, unknown functions, the shape of a nested access. That is the set
     * that caught {@code fix.md}'s transitive closure and {@code why.md}'s Example 3.
     * A page that wants its columns checked earns it by adding its relation to the
     * fixture.
     */
    private static SemanticResult analyseWithStandIns(String fixture, String body) {
        SemanticResult first = SemanticFixtures.analyze(fixture + "\n" + body);
        java.util.LinkedHashSet<String> undefined = new java.util.LinkedHashSet<>();
        for (SemanticError error : first.errors()) {
            java.util.regex.Matcher m = UNDEFINED.matcher(error.message());
            while (m.find()) {
                undefined.add(m.group(1));
            }
        }
        if (undefined.isEmpty()) {
            return first;
        }
        StringBuilder standIns = new StringBuilder();
        for (String name : undefined) {
            standIns.append("source ").append(name)
                    .append(" from json(\"").append(name).append(".json\");\n");
        }
        return SemanticFixtures.analyze(fixture + "\n" + standIns + "\n" + body);
    }

    // ── extraction ──────────────────────────────────────────────────────────────

    private static List<Example> extractAll(Path root) {
        List<Example> out = new ArrayList<>();
        for (Path page : referencePages(root)) {
            String[] lines = read(page).split("\n", -1);
            boolean inExamples = false;
            boolean wanted = false;
            StringBuilder prelude = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String stripped = lines[i].strip();
                if (lines[i].startsWith("# ")) {
                    inExamples = lines[i].startsWith("# Examples");
                    // A `# Syntax:` section is the grammar written out, over
                    // placeholder names — `relate "Name" Source.col -> Target.col`
                    // declares nothing and names nothing that could exist. It is the
                    // parse guard's business and not this one's.
                    wanted = !lines[i].startsWith("# Syntax");
                    prelude.setLength(0);      // each section starts from the fixture
                    continue;
                }
                if (!wanted || !stripped.equals("```relix")) {
                    continue;
                }
                boolean skipped = skipMarked(lines, i);
                int start = i + 1;
                StringBuilder body = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].strip().equals("```")) {
                    body.append(lines[i]).append('\n');
                    i++;
                }
                String unit = stripAnnotations(body.toString());
                if (unit.isBlank() || !isParseable(unit)) {
                    continue;
                }
                // A section's blocks build on each other — a page that defines a view
                // or a TVF in one block and uses it in the next is reading correctly,
                // and it is the worked-example guard's rule too.
                String declarations = declarationsOf(unit);
                if (skipped || reachesOutside(unit)) {
                    prelude.append(declarations);
                    continue;
                }
                // An # Examples: section is a sequence by convention, so its blocks
                // are read after the ones before them and only that way. A body
                // block is read on its own first — see Example#fallback.
                out.add(inExamples
                        ? new Example(page, start, prelude + analysisInput(unit), null)
                        : new Example(page, start, analysisInput(unit),
                                      prelude + analysisInput(unit)));
                prelude.append(declarations);
            }
            collectOneLiners(page, lines, prelude(page, lines), out);
        }
        return out;
    }

    /**
     * Adds the single-line examples of each {@code # Examples:} section — the
     * indented one-liner under a {@code Description:} line, which is how most pages
     * show their operator before any fenced block.
     *
     * <p>They outnumber the fenced blocks four to one and were the larger half of
     * what nothing but the grammar had read. Extraction mirrors
     * {@code ReferenceExampleParseTest}: a description line is unindented and ends
     * with a colon, the lines under it are indented, and only a <em>single</em> such
     * line is taken — two or more cannot be told apart from a wrapped fragment, and
     * that guard already fails a page that stacks them unfenced.
     */
    private static void collectOneLiners(Path page, String[] lines, String prelude,
                                         List<Example> out) {
        boolean inExamples = false;
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String s = line.strip();
            if (line.startsWith("# ")) {
                inExamples = line.startsWith("# Examples");
                continue;
            }
            if (s.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inExamples || inFence) {
                continue;
            }
            boolean isDescription = s.endsWith(":") && s.length() > 1 && !startsIndented(line);
            if (!isDescription) {
                continue;
            }
            List<String> code = new ArrayList<>();
            int first = i + 1;
            int j = i + 1;
            while (j < lines.length && startsIndented(lines[j])) {
                if (!lines[j].strip().isEmpty()) {
                    code.add(lines[j].strip());
                }
                j++;
            }
            i = j - 1;
            if (code.size() != 1) {
                continue;
            }
            String unit = stripAnnotations(code.get(0));
            if (unit.isBlank() || reachesOutside(unit) || !isParseable(unit)
                    || skipMarked(lines, first)) {
                continue;
            }
            out.add(new Example(page, first + 1, prelude + analysisInput(unit), null));
        }
    }

    /**
     * Everything the page's fenced examples declare, which a one-liner may lean on —
     * {@code σ rnk ≤ 3 (Ranked)} reads a view a block above it defined.
     *
     * <p>One-liners deliberately do not accumulate into each other. They sit in
     * prose rather than in a sequence, a reader takes each on its own, and letting
     * one declare a relation for the next would let a wrong example hide behind an
     * earlier one.
     */
    private static String prelude(Path page, String[] lines) {
        StringBuilder prelude = new StringBuilder();
        boolean inExamples = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("# ")) {
                inExamples = lines[i].startsWith("# Examples");
                continue;
            }
            if (!inExamples || !lines[i].strip().equals("```relix")) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            i++;
            while (i < lines.length && !lines[i].strip().equals("```")) {
                body.append(lines[i]).append('\n');
                i++;
            }
            prelude.append(declarationsOf(stripAnnotations(body.toString())));
        }
        return prelude.toString();
    }

    private static boolean startsIndented(String line) {
        return line.startsWith(" ") || line.startsWith("\t");
    }

    /**
     * The statements of {@code unit} that a later block might depend on — everything
     * but its queries. A bare-expression block declares nothing.
     */
    private static String declarationsOf(String unit) {
        if (!looksLikeStatement(unit)) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String statement : unit.split(";")) {
            String s = statement.strip();
            if (!s.isEmpty() && !s.startsWith("query")) {
                kept.append(s).append(";\n");
            }
        }
        return kept.toString();
    }

    /**
     * Normalises a block exactly as {@code ReferenceExampleParseTest} does, so this
     * guard analyses what that guard parsed. The rules are duplicated rather than
     * shared because the two live in different modules — relix-lang cannot see the
     * analyzer, and relix-semantic does not depend on relix-lang's test source — and
     * a divergence between them would show up here as an example that parses and
     * mysteriously will not analyse.
     */
    private static String analysisInput(String unit) {
        if (looksLikeStatement(unit)) {
            String terminated = unit.stripTrailing();
            return terminated.endsWith(";") ? terminated : terminated + ";";
        }
        return unit.startsWith("{") ? "query " + unit + ";" : "query {\n" + unit + "\n};";
    }

    /** Drops {@code --} comments, which may carry prose the parser would reject. */
    private static String stripAnnotations(String block) {
        return Stream.of(block.split("\n", -1))
                .map(l -> l.replaceAll("\\s+--\\s.*$", "").stripTrailing())
                .filter(l -> !l.strip().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("")
                .strip();
    }

    /** Skips content that is not a Relix snippet: CLI invocations, syntax placeholders. */
    private static boolean isParseable(String unit) {
        return !unit.startsWith("relix ")
                && !java.util.regex.Pattern.compile("<[A-Za-z]").matcher(unit).find();
    }

    private static boolean looksLikeStatement(String unit) {
        return unit.contains(";")
                || unit.matches("(?s)^(source|import|def|namespace|connection|private|query)\\b.*")
                || unit.matches("(?s)^\\w+\\s*:=.*");
    }

    /**
     * Whether the fence at {@code index} carries an {@code <!-- analysis-skip: … -->}
     * marker in the lines just above it.
     *
     * <p>The exemption sits with the example rather than in a list here, so the
     * person editing the example is the person who sees it, and it must say why. It
     * is an HTML comment because the reason is for whoever maintains the page, not
     * for the reader of the manual.
     *
     * <p>There is one at the time of writing, and it is a real limitation rather than
     * an inconvenience: a relation <em>named</em> with a delimited identifier cannot
     * be declared by any script, so the page documenting that syntax cannot supply
     * the relations its example references.
     */
    private static boolean skipMarked(String[] lines, int index) {
        for (int i = index - 1; i >= 0 && i >= index - 8; i--) {
            String line = lines[i].strip();
            if (line.contains("analysis-skip:")) {
                return true;
            }
            if (line.startsWith("```")) {
                return false;                    // reached the previous block
            }
        }
        return false;
    }

    /**
     * Whether an example must be read on its own rather than after the fixture.
     *
     * <p>A {@code source}, {@code connection} or {@code import} names an external
     * system, so its schema is whatever the declaration says and analysing it checks
     * the example against itself. A {@code namespace} declaration has a different
     * reason: it must be a script's <em>first</em> statement, so prepending anything
     * at all makes it a parse error.
     */
    private static boolean reachesOutside(String body) {
        return java.util.regex.Pattern
                .compile("(?m)^[ \t]*(source|connection|import|namespace)\\b")
                .matcher(body).find();
    }

    private static List<Path> referencePages(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().endsWith(".template.md"))
                    .filter(p -> !p.getFileName().toString().equals("README.md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path referenceRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/reference"))) {
            p = p.getParent();
        }
        assertThat(p).as("repo root containing docs/reference").isNotNull();
        return p.resolve("docs/reference");
    }
}
