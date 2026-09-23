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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Relix examples in the manuals, extracted once for every guard that reads them.
 *
 * <p>{@link ReferenceExampleParseTest} asks whether each one parses and
 * {@link GrammarReferenceTest} whether the published grammar agrees; both must see the
 * same blocks, wrapped the same way, or the second is checking a different corpus from
 * the one the first keeps parseable.
 */
final class ReferenceExamples {

    private ReferenceExamples() {
    }

    // ── extraction ────────────────────────────────────────────────────────────

    record Example(Path page, String desc, String unit, boolean statement,
                           boolean expectParses) {
        /** A statement snippet parses as-is; a bare expression is wrapped in a query. */
        String parseInput() {
            if (statement) {
                return ensureTerminated(unit);
            }
            String u = unit.strip();
            // A brace-wrapped body (e.g. a REPL `{ … }` snippet) becomes `query { … };`.
            return u.startsWith("{") ? "query " + u + ";" : "query {\n" + unit + "\n};";
        }
        String location() {
            return page.getFileName() + "  [" + desc + "]";
        }
    }

    private static String ensureTerminated(String s) {
        String t = s.stripTrailing();
        return t.endsWith(";") ? t : t + ";";
    }

    /** A snippet that must parse. The trailing \\s excludes ```relix-invalid. */
    private static final Pattern FENCE =
            Pattern.compile("```relix\\s*\\n(.*?)```", Pattern.DOTALL);

    /** A snippet that must NOT parse — a checked "this form is rejected" claim. */
    private static final Pattern INVALID_FENCE =
            Pattern.compile("```relix-invalid\\s*\\n(.*?)```", Pattern.DOTALL);

    static List<Example> extractAll(Path... roots) {
        List<Example> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".md"))
                     .filter(p -> !p.toString().endsWith(".template.md"))
                     .filter(p -> !p.getFileName().toString().equals("README.md"))
                     .sorted()
                     .forEach(p -> extractPage(p, out));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    private static void extractPage(Path page, List<Example> out) {
        String md;
        try {
            md = Files.readString(page);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Fenced ```relix blocks — complete, self-contained snippets.
        Matcher fm = FENCE.matcher(md);
        while (fm.find()) {
            String unit = stripAnnotations(fm.group(1));
            if (isParseable(unit)) {
                out.add(new Example(page, "fenced", unit, looksLikeStatement(unit), true));
            }
        }

        // Fenced ```relix-invalid blocks — forms the docs claim are rejected.
        Matcher im = INVALID_FENCE.matcher(md);
        while (im.find()) {
            String unit = stripAnnotations(im.group(1));
            if (!unit.isBlank()) {
                out.add(new Example(page, "fenced-invalid", unit, looksLikeStatement(unit), false));
            }
        }

        // Single-line entries in the "# Examples:" section.
        for (String[] descLines : exampleSection(md)) {
            String desc = descLines[0];
            String unit = stripAnnotations(descLines[1]);
            if (!unit.contains("\n") && isParseable(unit)) {
                out.add(new Example(page, desc, unit, looksLikeStatement(unit), true));
            }
        }
    }

    /** Returns [description, single-code-line] for each single-line example entry. */
    private static List<String[]> exampleSection(String md) {
        List<String[]> result = new ArrayList<>();
        Matcher sec = Pattern.compile("(?m)^#\\s*Examples\\s*:?[ \\t]*\\n(.*?)(?=^#\\s|\\Z)",
                Pattern.DOTALL).matcher(md);
        if (!sec.find()) {
            return result;
        }
        String[] lines = sec.group(1).split("\n", -1);
        int i = 0;
        boolean inFence = false;
        while (i < lines.length) {
            String line = lines[i];
            String s = line.strip();
            // Fenced blocks are extracted whole by FENCE/INVALID_FENCE; scanning
            // into them here would also read their contents as loose one-liners.
            if (s.startsWith("```")) {
                inFence = !inFence;
                i++;
                continue;
            }
            if (inFence) {
                i++;
                continue;
            }
            boolean isDesc = s.endsWith(":") && !startsIndented(line) && s.length() > 1;
            if (isDesc) {
                String desc = s.substring(0, s.length() - 1).strip();
                List<String> code = new ArrayList<>();
                i++;
                while (i < lines.length && startsIndented(lines[i])) {
                    if (!lines[i].strip().isEmpty()) {
                        code.add(lines[i].strip());
                    }
                    i++;
                }
                if (!desc.isEmpty() && code.size() == 1) {   // single-line only (unambiguous)
                    result.add(new String[]{desc, code.get(0)});
                }
            } else {
                i++;
            }
        }
        return result;
    }

    static boolean startsIndented(String line) {
        return line.startsWith(" ") || line.startsWith("\t");
    }

    /** Strips trailing {@code -- prose} annotations (— not a Relix comment) line by line. */
    private static String stripAnnotations(String block) {
        return Stream.of(block.split("\n", -1))
                .map(l -> l.replaceAll("\\s+--\\s.*$", "").stripTrailing())
                .filter(l -> !l.strip().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("")
                .strip();
    }

    /** Skips content that is not a Relix snippet: CLI commands, syntax placeholders, empties. */
    private static boolean isParseable(String unit) {
        if (unit.isBlank()) return false;
        if (unit.startsWith("relix ")) return false;        // shell/CLI invocation
        if (Pattern.compile("<[A-Za-z]").matcher(unit).find()) return false;  // <placeholder>
        return true;
    }

    private static boolean looksLikeStatement(String unit) {
        return unit.contains(";")
                || unit.matches("(?s)^(source|import|def|namespace|connection|private|query)\\b.*")
                || unit.matches("(?s)^\\w+\\s*:=.*");
    }
}
