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
package com.darkcollective.relix.embed.guide;

import com.darkcollective.relix.embed.Relix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles and runs every Java example in the programming guide, and diffs what it
 * prints against what the page says it prints.
 *
 * <p>The guide's counterpart to the three {@code docs/reference} guards, and it exists
 * for the same reason the last of them does: an example that <em>looks</em> right is not
 * an example that works. Here the bar is higher, because a guide to an API can go wrong
 * in a way a language reference cannot — a renamed method leaves the prose reading
 * perfectly while nothing it describes exists. Compiling the examples against the real
 * classpath is what makes "the guide defines the API" a fact rather than an intention:
 * rename a method and this fails.
 *
 * <h2>The contract a page is held to</h2>
 *
 * <p>Every fenced <code>```java</code> block is an example. It is compiled and run; if a
 * plain <code>```</code> fence follows it, that fence is what the example must print.
 *
 * <ul>
 *   <li><strong>A block is a run of statements</strong>, wrapped in a {@code main} method
 *       — not a whole file. Leading {@code import} lines are lifted to the top of the
 *       generated file, so a page may show its imports once and elide them afterwards.</li>
 *   <li><strong>Earlier blocks of the same page are prepended</strong>, so a later block
 *       may use what an earlier one declared and a page reads as one worked example
 *       rather than as eight snippets each re-declaring its own data. Only the block's
 *       <em>own</em> output is compared — the prelude runs again on every block, and
 *       comparing its output too would make every example's fence a transcript of the
 *       page so far.</li>
 *   <li><strong>A block that prints must say what it prints.</strong> Output nobody
 *       checked is the thing this guard exists to prevent, so a block whose run writes to
 *       stdout without an output fence after it fails.</li>
 *   <li><strong>An example that cannot run here</strong> — one that needs a live database
 *       — carries an <code>&lt;!-- guide-skip: reason --&gt;</code> comment before its
 *       fence, and is compiled but not run. The reason is reviewed like any other claim;
 *       "it is awkward" is not one.</li>
 * </ul>
 *
 * <p>Comparison forgives trailing whitespace and surrounding blank lines, and nothing
 * else. There is deliberately no regeneration mode: when an example's output legitimately
 * changes, updating the page is a considered edit, which is the entire point of pinning
 * it.
 */
@DisplayName("Programming guide examples compile, run, and print what the page says")
final class ProgrammingGuideTest {

    /**
     * The imports every example is compiled with.
     *
     * <p>A guide whose every snippet opened with nine import lines would bury the one
     * line that is the point of it, so the boilerplate lives here. A page that wants to
     * show an import may still write it: leading imports in a block are lifted out.
     */
    private static final List<String> PREAMBLE = List.of(
            "com.darkcollective.relix.embed.Diagnostic",
            "com.darkcollective.relix.embed.Relation",
            "com.darkcollective.relix.embed.Relix",
            "com.darkcollective.relix.embed.RelixException",
            "com.darkcollective.relix.embed.Rows",
            "com.darkcollective.relix.embed.Tuple",
            "com.darkcollective.relix.ast.RelNode",
            "com.darkcollective.relix.events.QueryEvent",
            "com.darkcollective.relix.processor.Row",
            "com.darkcollective.relix.symbol.Schema",
            "com.darkcollective.relix.value.Value",
            "java.util.List",
            "java.util.Map",
            "java.util.stream.Stream");

    /** Static imports every example is compiled with — the expression surface. */
    private static final List<String> STATIC_PREAMBLE = List.of(
            "com.darkcollective.relix.ast.Expr.*");

    /**
     * Printed between an example's prelude and the example itself, so only the block's
     * own output is compared. A control character, so no example can print it by
     * accident and no reader can mistake it for content.
     */
    private static final String MARKER = "\u0001";

    /** The same marker as a Java source escape, for the generated file. */
    private static final String MARKER_ESCAPE = "\\u0001";

    /**
     * An example: one {@code java} block, with the page's earlier blocks in front.
     *
     * <p>The prelude is kept as the <em>list</em> of earlier blocks rather than as one
     * concatenated string, because each block is a unit that may open with imports —
     * flatten them first and every block after the page's first loses the ability.
     */
    private record Example(Path page, int ordinal, int line, List<String> prelude, String body,
                           String expected, String skip) {

        String location() {
            return page.getFileName() + ":" + line + " (example " + ordinal + ")";
        }
    }

    /** A fenced block; {@code lang} is empty for a plain ``` fence. */
    private record Block(String lang, String body, int line, String skip) {}

    @Test
    @DisplayName("every example compiles against the real API")
    void everyExampleCompiles() {
        List<Example> examples = extractAll();
        assertThat(examples)
                .as("Java examples were found — an empty list means the extractor has "
                        + "stopped matching the page convention and this guard is "
                        + "checking nothing")
                .isNotEmpty();

        Map<String, String> failures = new LinkedHashMap<>();
        for (Example example : examples) {
            String error = compile(example).error();
            if (error != null) {
                // Keyed on the error, not the example: every block carries the earlier
                // ones as its prelude, so one broken line breaks the rest of the page
                // too. Reporting it once names the line to fix instead of burying it.
                failures.putIfAbsent(withoutPositions(error),
                        example.location() + " :: does not compile\n" + indent(error));
            }
        }
        assertThat(failures.values())
                .as("guide examples that no longer compile — the API moved and the guide "
                        + "did not")
                .isEmpty();
    }

    @Test
    @DisplayName("every runnable example prints exactly what its page says")
    void everyExamplePrintsWhatThePageSays() {
        Map<String, String> failures = new LinkedHashMap<>();
        for (Example example : extractAll()) {
            if (example.skip() != null) {
                continue;
            }
            String failure = run(example);
            if (failure != null) {
                // An example that throws throws again as every later block's prelude,
                // so the cause is the key — a wrong printed result is unique already.
                failures.putIfAbsent(withoutPositions(failure), failure);
            }
        }
        assertThat(failures.values())
                .as("guide examples that threw, or printed something other than what the "
                        + "page shows — rerun the example and update the page (or fix the "
                        + "code, if the guide is the one that is right)")
                .isEmpty();
    }

    @Test
    @DisplayName("every skip names a reason")
    void everySkipNamesAReason() {
        List<String> unexplained = extractAll().stream()
                .filter(e -> e.skip() != null && e.skip().isBlank())
                .map(Example::location)
                .toList();
        assertThat(unexplained)
                .as("`guide-skip` markers with no reason after the colon — a skip is a "
                        + "claim that an example cannot run here, and a claim needs a "
                        + "reason")
                .isEmpty();
    }

    // ── running ─────────────────────────────────────────────────────────────────

    /** Runs one example; returns {@code null} when it prints what its page says. */
    private static String run(Example example) {
        Compiled compiled = compile(example);
        if (compiled.error() != null) {
            // Reported by everyExampleCompiles; not worth failing twice for.
            return null;
        }
        String printed;
        try {
            printed = invoke(compiled);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            return example.location() + " :: threw " + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        } catch (ReflectiveOperationException | IOException e) {
            return example.location() + " :: could not be run: " + e;
        }

        if (example.expected() == null) {
            return printed.isBlank() ? null : example.location()
                    + " :: prints output the page does not show — add a plain ``` fence "
                    + "holding it (run the example; do not hand-write the result)\n"
                    + quote(printed);
        }
        return normalise(printed).equals(normalise(example.expected()))
                ? null
                : example.location() + " :: printed output differs\n"
                        + "  ── the page says ──\n" + quote(example.expected())
                        + "  ── it printed    ──\n" + quote(printed);
    }

    /** Loads the compiled example and runs its {@code main}, capturing stdout. */
    private static String invoke(Compiled compiled) throws ReflectiveOperationException, IOException {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{compiled.classes().toUri().toURL()},
                ProgrammingGuideTest.class.getClassLoader())) {
            Method main = loader.loadClass("guide." + compiled.className())
                    .getDeclaredMethod("main", String[].class);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream original = System.out;
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            try {
                main.invoke(null, (Object) new String[0]);
            } finally {
                System.setOut(original);
            }
            String printed = captured.toString(StandardCharsets.UTF_8);
            int mark = printed.lastIndexOf(MARKER);
            return mark < 0 ? printed : printed.substring(mark + MARKER.length());
        }
    }

    // ── compiling ───────────────────────────────────────────────────────────────

    /** A compiled example: where its classes are, or why there are none. */
    private record Compiled(String className, Path classes, String error) {}

    /**
     * Compiles one example into a temporary directory.
     *
     * <p>Against {@code java.class.path} — the very classpath these tests run on — so an
     * example is compiled against the same artifacts a caller would depend on, rather
     * than against a description of them.
     */
    private static Compiled compile(Example example) {
        String className = "Example"
                + example.page().getFileName().toString().replaceAll("[^A-Za-z0-9]", "_")
                + "_" + example.ordinal();
        Path output;
        try {
            output = Files.createTempDirectory("relix-guide-");
            output.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("a JDK compiler — the guide is checked by compiling it")
                .isNotNull();

        StringWriter diagnostics = new StringWriter();
        boolean ok = compiler.getTask(
                diagnostics, null, null,
                List.of("-classpath", System.getProperty("java.class.path"),
                        "-d", output.toString(), "-proc:none", "-nowarn"),
                null,
                List.of(new InMemorySource(className,
                        wrap(className, example.prelude(), example.body()))))
                .call();
        return ok
                ? new Compiled(className, output, null)
                : new Compiled(className, output, diagnostics.toString());
    }

    /**
     * Wraps a run of statements into a compilation unit.
     *
     * <p>Leading {@code import} lines are lifted out of <em>each</em> block — the page's
     * earlier ones as well as this one — so a page may show an import where it is the
     * point and omit it where it is noise, and the reader sees the same text either way.
     * Lifting is per block rather than per compilation unit because a block is what a
     * reader sees: a page whose second section introduces a type would otherwise have to
     * declare its import three sections earlier.
     */
    private static String wrap(String className, List<String> prelude, String body) {
        StringBuilder imports = new StringBuilder();
        StringBuilder statements = new StringBuilder();
        prelude.forEach(earlier -> appendStatements(earlier, imports, statements));
        statements.append("        System.out.println(\"").append(MARKER_ESCAPE).append("\");\n");
        appendStatements(body, imports, statements);

        StringBuilder out = new StringBuilder("package guide;\n\n");
        PREAMBLE.forEach(type -> out.append("import ").append(type).append(";\n"));
        STATIC_PREAMBLE.forEach(type -> out.append("import static ").append(type).append(";\n"));
        out.append(imports).append('\n')
                .append("public final class ").append(className).append(" {\n")
                .append("    public static void main(String[] args) throws Exception {\n")
                .append(statements)
                .append("    }\n}\n");
        return out.toString();
    }

    /** Splits one block into the imports it opens with and the statements that follow. */
    private static void appendStatements(String body, StringBuilder imports,
                                         StringBuilder statements) {
        boolean stillLeading = true;
        for (String line : body.split("\n", -1)) {
            if (stillLeading && line.strip().startsWith("import ")) {
                imports.append(line.strip()).append('\n');
                continue;
            }
            if (stillLeading && line.isBlank()) {
                continue;   // the blank line under an import block
            }
            stillLeading = false;
            statements.append("        ").append(line).append('\n');
        }
    }

    /** A source file the compiler reads from memory rather than from disk. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String className, String code) {
            super(URI.create("string:///guide/" + className + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    // ── extraction ──────────────────────────────────────────────────────────────

    private static final Pattern SKIP =
            Pattern.compile("<!--\\s*guide-skip:(.*?)-->", Pattern.DOTALL);

    private static List<Example> extractAll() {
        List<Example> out = new ArrayList<>();
        for (Path page : guidePages()) {
            int ordinal = 0;
            List<String> prelude = new ArrayList<>();
            List<Block> blocks = blocks(page);
            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (!block.lang().equals("java")) {
                    continue;
                }
                Block next = i + 1 < blocks.size() ? blocks.get(i + 1) : null;
                String expected = next != null && next.lang().isEmpty() ? next.body() : null;
                out.add(new Example(page, ++ordinal, block.line(),
                        List.copyOf(prelude), block.body(), expected, block.skip()));
                prelude.add(block.body());
            }
        }
        return out;
    }

    /**
     * Every fenced block of {@code page}, in document order.
     *
     * <p>A page is the unit an example accumulates over — not a section. A guide page is
     * one worked example read top to bottom, and scoping the prelude any smaller would
     * make each heading re-declare the data the page is about.
     */
    private static List<Block> blocks(Path page) {
        List<Block> blocks = new ArrayList<>();
        String pending = null;
        String[] lines = read(page).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            Matcher skip = SKIP.matcher(stripped);
            if (skip.matches()) {
                pending = skip.group(1).strip();
                continue;
            }
            if (stripped.startsWith("```")) {
                String lang = stripped.substring(3).strip();
                int start = i;
                StringBuilder body = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].strip().equals("```")) {
                    body.append(lines[i]).append('\n');
                    i++;
                }
                blocks.add(new Block(lang, body.toString(), start + 1, pending));
                pending = null;
            }
        }
        return blocks;
    }

    // ── text ────────────────────────────────────────────────────────────────────

    /**
     * Trailing whitespace and surrounding blank lines are the only differences forgiven.
     * Leading spaces are load-bearing — they are a rendered table's left margin.
     */
    /**
     * The build's own version, replaced by a placeholder wherever it is printed.
     *
     * <p>The one value on these pages that no page can state. {@code relix.version}
     * reports the version the engine was <em>built</em> at, so an example printing it
     * prints {@code 1.0.0-rc2} here, a release number under
     * {@code -PrelixVersion=1.0.0}, and whatever the reader installed for the reader —
     * three different correct answers to one query. Pasting any of them makes the page
     * wrong for everybody else, and pinning one fails the release build, which is how
     * this was found: the first {@code build -PrelixVersion=} of the release workflow
     * reported two pages as printing the wrong thing when both were right.
     *
     * <p>So the placeholder is what the pages show and the substitution is made here,
     * once, rather than each page working around it. It is narrow deliberately: the
     * literal running version and nothing else, skipped entirely when the build is
     * unstamped — {@code unknown} is a real value that rows legitimately carry, and
     * masking it would hide a component that failed to report.
     */
    private static final String VERSION_PLACEHOLDER = "<version>";

    private static final String BUILD_VERSION = buildVersion();

    private static String buildVersion() {
        try (Relix relix = Relix.open()) {
            return relix.relation("π version (σ kind = 'engine' (relix.version))")
                    .toList().stream()
                    .findFirst()
                    .map(row -> row.string("version"))
                    .orElse("");
        }
    }

    private static String versionless(String text) {
        return BUILD_VERSION.isBlank() || "unknown".equals(BUILD_VERSION)
                ? text
                : text.replace(BUILD_VERSION, VERSION_PLACEHOLDER);
    }

    private static String normalise(String text) {
        List<String> lines = new ArrayList<>(Stream.of(versionless(text).split("\n", -1))
                .map(String::stripTrailing)
                .toList());
        while (!lines.isEmpty() && lines.getFirst().isEmpty()) {
            lines.removeFirst();
        }
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return String.join("\n", lines);
    }

    private static String quote(String text) {
        return Stream.of(normalise(text).split("\n", -1))
                .map(line -> "  │ " + line + "\n")
                .reduce("", String::concat);
    }

    /**
     * A failure stripped of what makes it this example's rather than that one's — the
     * generated class name and the line numbers inside it. Two reports that reduce to
     * the same text are the same defect seen from two blocks.
     */
    private static String withoutPositions(String text) {
        return text.replaceAll("Example\\w+\\.java:\\d+", "")
                .replaceAll("(?m)^\\S+\\.md:\\d+ \\(example \\d+\\)", "");
    }

    private static String indent(String text) {
        return Stream.of(text.split("\n", -1))
                .map(line -> "  " + line + "\n")
                .reduce("", String::concat);
    }

    // ── the guide ───────────────────────────────────────────────────────────────

    private static List<Path> guidePages() {
        try (Stream<Path> walk = Files.walk(guideRoot())) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path page) {
        try {
            return Files.readString(page);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path guideRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/guide"))) {
            p = p.getParent();
        }
        assertThat(p).as("repo root containing docs/guide").isNotNull();
        return p.resolve("docs/guide");
    }
}
