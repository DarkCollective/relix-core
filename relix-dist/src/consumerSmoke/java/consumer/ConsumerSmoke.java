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
package consumer;

import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.Tuple;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an embedder gets from the published artifacts, run against what was
 * <em>published</em> rather than the projects that produced it.
 *
 * <p>It exists because a broken POM is invisible to every other test here. The build
 * resolves projects, not coordinates, so a dependency declared with the wrong scope —
 * a battery left `runtimeOnly` where a consumer needs it to compile, an `api` that
 * should have been `implementation` — compiles and passes everything and then fails for
 * the first person to depend on the release.
 *
 * <p>The same program runs once per way of depending on the engine — {@code relix}
 * alone, with {@code relix-solver-ojalgo}, with {@code relix-docs}, and through
 * {@code relix-all}, on the class path and on the module path — and is told by its
 * arguments which optional artifacts it was given, so it can check that each brings
 * what it should and that leaving one out costs only what it should.
 */
public final class ConsumerSmoke {

    private ConsumerSmoke() {
    }

    /**
     * @param args {@code solver=true|false} and {@code docs=true|false}: which optional
     *             artifacts this build depends on; {@code modular=true|false}: whether it
     *             was built as a module
     */
    public static void main(String[] args) throws Exception {
        boolean solver = flag(args, "solver");
        boolean docs = flag(args, "docs");
        boolean modular = flag(args, "modular");
        // The modular variant is only evidence if it really ran as a module: a build that
        // fell back to the class path would pass every other check here.
        if (ConsumerSmoke.class.getModule().isNamed() != modular) {
            throw new AssertionError("expected to run " + (modular ? "as a named module"
                    : "on the class path") + ", but ran in " + ConsumerSmoke.class.getModule());
        }
        try (Relix relix = Relix.open()) {
            relix.table("Orders", List.of(
                    row("customer", "Ada", "amount", 100),
                    row("customer", "Grace", "amount", 250)));

            // Composing and rendering, which run nothing.
            Relation large = relix.relation("σ amount > 150 (Orders)");
            expect(large.render(), "σ amount > 150 (Orders)");

            // Executing, which needs the whole assembled stack.
            List<Tuple> rows = large.toList();
            expect(String.valueOf(rows.size()), "1");
            expect(rows.getFirst().string("customer"), "Grace");
            expect(String.valueOf(relix.relation("Orders").count()), "2");

            // A built-in function: present only if the default library came with the
            // facade rather than having to be named separately.
            expect(relix.relation("π UCase(customer) → c (Orders)").toList()
                    .getFirst().string("c"), "ADA");

            // The inventory, which reports what actually got installed.
            List<String> kinds = relix.relation("π kind (relix.version)").toList().stream()
                    .map(r -> r.string("kind")).distinct().sorted().toList();
            for (String required : List.of("engine", "facade", "function-library")) {
                if (!kinds.contains(required)) {
                    throw new AssertionError(
                            "the published facade did not bring a " + required + ": " + kinds);
                }
            }
            if (kinds.contains("solver") != solver) {
                throw new AssertionError("expected a solver " + (solver ? "" : "not ")
                        + "to be installed: " + kinds);
            }

            checkSolver(relix, solver);
        }
        checkDocs(docs);
        System.out.println("consumer smoke (solver=" + solver + ", docs=" + docs
                + ", modular=" + modular + "): ok");
    }

    /** OPTIMIZE runs with the solver, and without it fails naming the artifact to add. */
    private static void checkSolver(Relix relix, boolean installed) {
        relix.table("Backlog", List.of(
                row("feature", "A", "value", 60, "cost", 10),
                row("feature", "B", "value", 100, "cost", 20),
                row("feature", "C", "value", 120, "cost", 30)));
        Relation chosen = relix.relation(
                "OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(cost) <= 30 (Backlog)");
        if (installed) {
            expect(String.valueOf(chosen.count()), "2");
            return;
        }
        try {
            chosen.toList();
        } catch (RuntimeException e) {
            String message = rootMessage(e);
            if (!message.contains("relix-solver-ojalgo")) {
                throw new AssertionError("the no-solver error does not name the artifact: "
                        + message, e);
            }
            return;
        }
        throw new AssertionError("OPTIMIZE ran with no solver installed");
    }

    /**
     * The reference pages, read the way a tool reads them.
     *
     * <p>Reflectively, because the program is compiled once for every variant and two of
     * them do not have {@code relix-docs} at all — and what those two must show is that
     * the class is not there, which a static reference could not.
     */
    private static void checkDocs(boolean installed) throws Exception {
        Class<?> docs;
        try {
            docs = Class.forName("com.darkcollective.relix.docs.RelixDocs");
        } catch (ClassNotFoundException e) {
            if (installed) {
                throw new AssertionError("relix-docs is a dependency but RelixDocs is missing", e);
            }
            return;
        }
        if (!installed) {
            throw new AssertionError("RelixDocs is present without depending on relix-docs");
        }
        Optional<?> page = (Optional<?>) docs.getMethod("referencePage", String.class)
                .invoke(null, "operators/select.md");
        String text = (String) page.orElseThrow(
                () -> new AssertionError("relix-docs has no page operators/select.md"));
        if (!text.startsWith("# Name: Selection")) {
            throw new AssertionError("unexpected page: " + text.lines().findFirst().orElse(""));
        }
        List<?> pages = (List<?>) docs.getMethod("referencePages").invoke(null);
        if (pages.size() < 50) {
            throw new AssertionError("relix-docs lists only " + pages.size() + " pages");
        }
    }

    private static boolean flag(String[] args, String name) {
        for (String arg : args) {
            if (arg.equals(name + "=true")) {
                return true;
            }
            if (arg.equals(name + "=false")) {
                return false;
            }
        }
        throw new IllegalArgumentException("missing argument " + name + "=true|false");
    }

    private static String rootMessage(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
