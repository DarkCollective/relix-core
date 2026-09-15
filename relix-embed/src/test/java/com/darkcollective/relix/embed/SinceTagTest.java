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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every public member of the facade says which release it arrived in.
 *
 * <p>The facade is published under a promise — deprecate for one minor, remove at a
 * major — and a promise about <em>when</em> something may be removed is unreadable
 * without knowing when it appeared. {@code @since} is where that is written down, and it
 * is worth a guard rather than a convention because it is invisible when missing: nothing
 * fails, the javadoc renders, and the gap is only discovered by the person trying to
 * work out whether they can rely on a method.
 *
 * <p>It checks the facade alone. Core carries the same compatibility promise, but its
 * surface is the whole engine and tagging it is a separate exercise; here the surface is
 * six types and the tag is cheap to keep right.
 */
@DisplayName("the facade's public members carry @since")
final class SinceTagTest {

    /**
     * A member declaration: {@code public}/{@code protected}, and a method, constructor
     * or type rather than a field.
     *
     * <p>Fields are excluded because the facade has no public ones — a constant would be
     * documented like anything else, and this pattern would want widening the day one
     * appears rather than silently passing.
     */
    private static final Pattern MEMBER = Pattern.compile(
            "^\\s+(public|protected)\\s+.*[({].*$");

    /**
     * Declarations that inherit their documentation, and with it their history.
     *
     * <p>An {@code @Override} is not a new member: it appeared when the method it
     * overrides did, and tagging it would state a date that is either wrong or a
     * duplicate of one the supertype already carries.
     */
    private static final Pattern INHERITED = Pattern.compile("^\\s+@Override\\s*$");

    @Test
    @DisplayName("no public member is missing it")
    void everyPublicMemberIsTagged() {
        List<String> missing = new ArrayList<>();
        for (Path file : facadeSources()) {
            List<String> lines = List.of(read(file).split("\n", -1));
            for (int i = 0; i < lines.size(); i++) {
                if (!MEMBER.matcher(lines.get(i)).matches() || isInherited(lines, i)) {
                    continue;
                }
                if (!precedingJavadoc(lines, i).contains("@since")) {
                    missing.add(file.getFileName() + ":" + (i + 1) + "  "
                            + lines.get(i).strip());
                }
            }
        }
        assertThat(missing)
                .as("""
                        public members of the published facade with no @since. The \
                        promise is "deprecate for one minor, remove at a major", which \
                        cannot be read without knowing which minor a member arrived in""")
                .isEmpty();
    }

    /** Whether the declaration at {@code index} is an override, and so inherits its history. */
    private static boolean isInherited(List<String> lines, int index) {
        for (int i = index - 1; i >= 0; i--) {
            String line = lines.get(i).strip();
            if (INHERITED.matcher(lines.get(i)).matches()) {
                return true;
            }
            if (!line.startsWith("@") && !line.isEmpty()) {
                return false;
            }
        }
        return false;
    }

    /**
     * The javadoc block immediately above the declaration at {@code index}, or empty when
     * there is none — a member with no documentation cannot carry the tag, and is
     * reported as missing it rather than skipped.
     */
    private static String precedingJavadoc(List<String> lines, int index) {
        int end = index - 1;
        while (end >= 0 && (lines.get(end).strip().startsWith("@") || lines.get(end).isBlank())) {
            end--;
        }
        if (end < 0 || !lines.get(end).strip().endsWith("*/")) {
            return "";
        }
        int start = end;
        while (start >= 0 && !lines.get(start).strip().startsWith("/**")) {
            start--;
        }
        return start < 0 ? "" : String.join("\n", lines.subList(start, end + 1));
    }

    private static List<Path> facadeSources() {
        Path main = repoRoot().resolve("relix-embed/src/main/java/com/darkcollective/relix/embed");
        try (Stream<Path> walk = Files.walk(main)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
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

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("relix-ast/src/main/java"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new AssertionError("could not locate the repository root");
        }
        return path;
    }
}
