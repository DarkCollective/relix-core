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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #727 — a test asserts on a recurring subject through the published assert for it,
 * not through a shape of its own.
 *
 * <p>This is a guard because the convention it protects had <em>already</em> decayed once
 * before anyone wrote it down.  When #727 was filed there were four hand-rolled cast
 * helpers in a single test class, ten copies of one message projection under four
 * different names, and 1,208 instance-checks doing tree navigation — none of it written
 * carelessly, all of it written because there was nothing better and no signal that
 * anything was accumulating.  {@code TESTING.md} now describes the assertion surface, but
 * a paragraph in a file nobody is obliged to open is what the repository already has, and
 * {@code OptimizerRuleCoverageTest} exists because that was not enough there either.
 *
 * <p>It forbids four shapes in <em>test</em> sources.  Each was at zero as of the commit
 * that added it, which is the condition {@code CLAUDE.md} sets for wiring a check in at
 * all: a gate that starts red is a gate people learn to skip.
 *
 * <ol>
 *   <li><b>Instance-checking a node kind.</b> {@code assertThat(n).isInstanceOf(K.class)}
 *       on a {@code RelNode} or {@code PhysicalNode} prints the kind that mismatched and
 *       none of the tree or plan it came from — the defect class #727 was filed about.
 *       {@code isNode(K.class)} makes the identical claim and prints the subject.</li>
 *   <li><b>A cast helper behind a kind check.</b> {@code asJoin}, {@code asDistinct},
 *       {@code asAggregate}, {@code asPushedScan} — four in {@code PlannerTest} alone,
 *       each written because cast-and-navigate got tedious and none of them improving the
 *       failure. {@code asNode(K.class)} is the published one.</li>
 *   <li><b>A local diagnostic-message projection.</b> Ten test classes each defined
 *       {@code result.errors().stream().map(SemanticError::message).toList()}. Assert
 *       through {@code messages()}/{@code errorMessages()}, or call
 *       {@code SemanticFixtures.errorMessages} where the list itself is wanted.</li>
 *   <li><b>A {@code Relation} terminal called only to feed a generic assertion</b>
 *       (issue #876). {@code assertThat(r.toList()).hasSize(3)} prints a list of rows
 *       and not the expression that produced them — the same defect one layer up, and
 *       46 of them were in six classes of the facade's own suite.
 *       {@code RelationAssert} states each of those claims with the query as the
 *       subject.</li>
 * </ol>
 *
 * <p><b>What it deliberately does not check.</b> Whether a <em>new</em> assert should
 * exist — that is a judgement ({@code TESTING.md}, <i>When to add an eighth</i>) and a
 * test that guessed at it would be wrong in both directions. This only holds the line on
 * the four shapes already known to grow back.  {@code RelationAssert} is the seventh
 * assert and was added by that judgement being made explicitly (issue #876), not by
 * anything here noticing it was missing.
 *
 * <p>It lives here for the reason {@link ProjectDocsGuardTest} does: the guards that read
 * the repository tree rather than their own module are collected in one place, and this
 * module's {@code test} task declares that tree as an input, so a change in another
 * module's tests cannot leave this UP-TO-DATE and passing because it never ran.
 */
@DisplayName("#727/#876 — tests assert through the published asserts, not around them")
final class AssertionSurfaceGuardTest {

    /**
     * The files that <em>implement</em> the message projection, and so necessarily
     * contain it.  Excluded as the mechanism rather than admitted as a use of it — the
     * distinction {@code FunctionAccessGuardTest} draws for {@code FunctionCatalog}.
     */
    private static final Set<String> PROJECTION_DECLARATIONS =
            Set.of("SemanticFixtures.java", "SemanticResultAssert.java");

    /**
     * The file that <em>implements</em> the relation assert, and so necessarily calls
     * every terminal it wraps.  Excluded as the mechanism rather than admitted as a use
     * of it, exactly as {@link #PROJECTION_DECLARATIONS} is.
     */
    private static final String RELATION_ASSERT_DECLARATION = "RelationAssert.java";

    /** The opening of the call whose argument is examined. */
    private static final String ASSERT_THAT = "assertThat(";

    /**
     * A {@code Relation} terminal inside an {@code assertThat} argument.
     *
     * <p>Not anchored to the end of it: {@code assertThat(r.schema().columns())} carries
     * on navigating what the terminal returned, and is the same discard — seven of the
     * sites swept for #876 had that shape.
     */
    private static final Pattern RELATION_TERMINAL = Pattern.compile(
            "\\.(toList|count|schema|render|explain|explainJson|plan|rewrites|events)\\(\\s*\\)");

    /** {@code Relation orders = …} — a local, field or parameter of that type. */
    private static final Pattern RELATION_DECLARATION =
            Pattern.compile("\\bRelation\\s+(\\w+)\\s*[=;)]");

    /** The receiver's first identifier, which is what is matched against the declarations. */
    private static final Pattern LEADING_IDENTIFIER = Pattern.compile("^(\\w+)");

    /**
     * Calls after which the receiver is no longer a relation, so a later {@code toList()}
     * on it is somebody else's method.
     *
     * <p>{@code relix.relation("Orders").stream()} hands back a {@code Stream}, and
     * {@code .toList()} on that is {@code Stream.toList} — which no assert on a relation
     * could state, because by then the rows have left the relation behind.
     */
    private static final List<String> LEAVES_THE_RELATION = List.of(
            "stream(", "toList(", "run(", "provenance(", "rows(", "iterator(", "spliterator(");

    /** {@code .isInstanceOf(Foo.class)} / {@code .isInstanceOf(PhysicalNode.Foo.class)}. */
    private static final Pattern INSTANCE_CHECK =
            Pattern.compile("\\.isInstanceOf\\(\\s*(PhysicalNode\\.\\w+|[A-Z]\\w*)\\.class\\s*\\)");

    /** {@code return (Foo) bar;} — the tail of a cast helper. */
    private static final Pattern CAST_RETURN =
            Pattern.compile("return\\s+\\((PhysicalNode\\.\\w+|[A-Z]\\w*)\\)\\s*\\w+\\s*;");

    /** A kind check within the few lines above a cast return. */
    private static final Pattern KIND_CHECK = Pattern.compile("\\.(isInstanceOf|isNode|asNode)\\(");

    /** Lines above a {@code return (K) x;} searched for the kind check that precedes it. */
    private static final int CAST_HELPER_WINDOW = 4;

    /** The diagnostics of a {@code SemanticResult}, projected to their messages. */
    private static final Pattern MESSAGE_PROJECTION =
            Pattern.compile("errors\\(\\)\\s*\\.\\s*stream\\(\\)[^;]*map\\(\\s*SemanticError::message\\s*\\)",
                    Pattern.DOTALL);

    @Test
    @DisplayName("no test instance-checks a RelNode or PhysicalNode kind")
    void noInstanceCheckOnANodeKind() {
        Set<String> kinds = nodeKinds();
        assertThat(kinds)
                .as("the node kinds read from the sealed hierarchies, without which this "
                        + "test asserts nothing")
                .hasSizeGreaterThan(40);

        List<String> offenders = new ArrayList<>();
        for (Path source : testSources()) {
            eachCodeLine(source, (line, number) -> {
                Matcher matcher = INSTANCE_CHECK.matcher(line);
                while (matcher.find()) {
                    String kind = matcher.group(1);
                    if (kind.startsWith("PhysicalNode.") || kinds.contains(kind)) {
                        offenders.add(source + ":" + number + " — " + line.strip());
                    }
                }
            });
        }
        assertThat(offenders)
                .as("""
                        test sources instance-checking a node kind. isInstanceOf prints \
                        the kind that mismatched and nothing of the tree or plan it came \
                        from, which is the failure issue #727 was filed about. Use \
                        isNode(K.class) — the same claim, and it prints the subject. See \
                        TESTING.md, 'The assertion surface'""")
                .isEmpty();
    }

    @Test
    @DisplayName("no test hides a kind check behind a cast helper of its own")
    void noHandRolledCastHelper() {
        List<String> offenders = new ArrayList<>();
        for (Path source : testSources()) {
            List<String> lines = codeLines(source);
            for (int i = 0; i < lines.size(); i++) {
                if (!CAST_RETURN.matcher(lines.get(i)).find()) {
                    continue;
                }
                String above = String.join("\n",
                        lines.subList(Math.max(0, i - CAST_HELPER_WINDOW), i));
                if (KIND_CHECK.matcher(above).find()) {
                    offenders.add(source + ":" + (i + 1) + " — " + lines.get(i).strip());
                }
            }
        }
        assertThat(offenders)
                .as("""
                        test sources returning a cast after checking the kind — the \
                        asJoin/asDistinct/asAggregate/asPushedScan helper a suite grows \
                        once cast-and-navigate gets tedious. Four of them were in \
                        PlannerTest and none improved the failure output. \
                        assertThat(n).asNode(K.class) is the published one and prints the \
                        subject""")
                .isEmpty();
    }

    @Test
    @DisplayName("no test re-implements the diagnostic-message projection")
    void noLocalMessageProjection() {
        List<String> offenders = new ArrayList<>();
        for (Path source : testSources()) {
            if (PROJECTION_DECLARATIONS.contains(source.getFileName().toString())) {
                continue;
            }
            String code = String.join("\n", codeLines(source));
            Matcher matcher = MESSAGE_PROJECTION.matcher(code);
            if (matcher.find()) {
                offenders.add(source + " — " + matcher.group().replaceAll("\\s+", " "));
            }
        }
        assertThat(offenders)
                .as("""
                        test sources projecting a SemanticResult's diagnostics to their \
                        messages. Ten classes each had one, under four different names. \
                        Assert through messages()/errorMessages() on SemanticResultAssert; \
                        where the list itself is wanted rather than an assertion on it, \
                        call SemanticFixtures.errorMessages""")
                .isEmpty();
    }

    @Test
    @DisplayName("no test calls a Relation terminal only to feed a generic assertion")
    void noBareRelationTerminal() {
        List<String> offenders = new ArrayList<>();
        for (Path source : testSources()) {
            if (RELATION_ASSERT_DECLARATION.equals(source.getFileName().toString())) {
                continue;
            }
            String code = String.join("\n", codeLines(source));
            Set<String> relations = declaredRelations(code);
            for (int at = code.indexOf(ASSERT_THAT); at >= 0;
                 at = code.indexOf(ASSERT_THAT, at + 1)) {
                String argument = argumentAt(code, at + ASSERT_THAT.length() - 1);
                if (argument == null) {
                    continue;
                }
                Matcher terminal = RELATION_TERMINAL.matcher(argument.strip());
                if (!terminal.find()) {
                    continue;
                }
                String receiver = argument.strip().substring(0, terminal.start()).strip();
                if (namesARelation(receiver, relations)
                        && LEAVES_THE_RELATION.stream().noneMatch(receiver::contains)) {
                    offenders.add(source + ":" + (code.substring(0, at).split("\n", -1).length)
                            + " — " + argument.strip().replaceAll("\\s+", " "));
                }
            }
        }
        assertThat(offenders)
                .as("""
                        test sources calling a Relation terminal only to feed a generic \
                        assertion. assertThat(r.toList()).hasSize(3) reports a list of \
                        rows and never the expression that produced them — the #727 \
                        defect one layer up, and what 46 sites in relix-embed did before \
                        issue #876. Assert through EmbedAssertions.assertThat(relation): \
                        hasRowCount/isEmpty/rows() for the answer, schema()/plan()/node() \
                        to navigate, renders()/explains()/count()/tuples()/events() to \
                        hand off to AssertJ with the query still attached""")
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * The names in {@code code} declared as a {@code Relation} — a local, a field
     * or a parameter.  Textual on purpose: this test reads sources rather than compiling
     * them, and a receiver is a relation when the file says so.
     */
    private static Set<String> declaredRelations(String code) {
        Set<String> names = new java.util.HashSet<>();
        Matcher matcher = RELATION_DECLARATION.matcher(code);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * Whether {@code receiver} is a relation — either it mints one
     * ({@code relix.relation(…)}, {@code relix.script(…).getFirst()}) or it starts from a
     * name declared as one.
     */
    private static boolean namesARelation(String receiver, Set<String> relations) {
        if (receiver.contains(".relation(") || receiver.contains(".script(")) {
            return true;
        }
        Matcher head = LEADING_IDENTIFIER.matcher(receiver);
        return head.find() && relations.contains(head.group(1));
    }

    /**
     * The text between the parenthesis at {@code open} and the one that closes it, or
     * null if it is unbalanced.
     *
     * <p>Balanced rather than regular, because the argument is routinely a call of its
     * own: {@code assertThat(open).hasToString(open.render())} ends in a terminal and is
     * not one of these, and a regex ending at the first {@code )} cannot tell the two
     * apart.
     */
    private static String argumentAt(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(open + 1, i);
                }
            }
        }
        return null;
    }

    /**
     * Every concrete {@code RelNode} kind, read from the {@code permits} clauses rather
     * than listed — a hand-copied list is one more thing to keep in step with the
     * hierarchy, and the one it would fall out of step with is the hierarchy this test
     * exists to protect assertions about.
     */
    private static Set<String> nodeKinds() {
        Path ast = repoRoot().resolve("relix-ast/src/main/java/com/darkcollective/relix/ast");
        Set<String> kinds = new java.util.HashSet<>();
        for (String file : List.of("RelNode.java", "ConditionalJoinNode.java")) {
            Matcher permits = Pattern.compile("permits\\s+([^{]*)\\{", Pattern.DOTALL)
                    .matcher(read(ast.resolve(file)));
            if (permits.find()) {
                Matcher name = Pattern.compile("\\b([A-Z]\\w*)\\b").matcher(permits.group(1));
                while (name.find()) {
                    kinds.add(name.group(1));
                }
            }
        }
        return kinds;
    }

    /** Every {@code .java} file in a {@code test} or {@code testFixtures} source set. */
    private static List<Path> testSources() {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                for (String set : List.of("src/test/java", "src/testFixtures/java")) {
                    Path dir = module.resolve(set);
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".java"))
                                .forEach(sources::add);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(sources).as("test sources to scan").hasSizeGreaterThan(100);
        return sources;
    }

    /**
     * The lines of {@code source} with comment lines blanked out.
     *
     * <p>Necessary rather than tidy: {@code PlanAssert}'s own javadoc shows the
     * instance-check-and-cast shape it replaces, and a guard that could not tell an
     * example of a defect from the defect would make documenting one impossible.
     * Deliberately line-granular — a trailing {@code // comment} is left alone, since
     * nothing here is ever a false positive in one.
     */
    private static List<String> codeLines(Path source) {
        List<String> lines = new ArrayList<>();
        boolean inBlockComment = false;
        for (String line : read(source).lines().toList()) {
            String trimmed = line.strip();
            boolean commented = inBlockComment
                    || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*");
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                inBlockComment = true;
            }
            if (inBlockComment && trimmed.endsWith("*/")) {
                inBlockComment = false;
            }
            lines.add(commented ? "" : line);
        }
        return lines;
    }

    private static void eachCodeLine(Path source, java.util.function.BiConsumer<String, Integer> visit) {
        List<String> lines = codeLines(source);
        for (int i = 0; i < lines.size(); i++) {
            visit.accept(lines.get(i), i + 1);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Walks up from the working directory to the directory holding the modules. */
    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("relix-ast/src/main/java"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new AssertionError("could not locate the repository root from "
                    + Paths.get("").toAbsolutePath());
        }
        return path;
    }
}
