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

import com.darkcollective.relix.lang.ast.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * A relative path in a declaration resolves against the directory of the file the
 * declaration was written in, so a session assembled from scripts in several directories
 * reads each one's files from beside it. A declaration with no file falls back to the
 * session's base directory, and so does every declaration in a closed sandbox.
 */
@DisplayName("A relative source path resolves against the file that declares it")
final class DeclaringFilePathsTest {

    private static final String CSV_SOURCE = """
            source %s from csv("data.csv") {
                header: true,
                schema: { name: STRING }
            };
            """;

    /** A directory holding {@code data.csv} with one row naming {@code who}. */
    private static Path dataDir(Path parent, String dir, String who) throws IOException {
        Path d = Files.createDirectories(parent.resolve(dir));
        Files.writeString(d.resolve("data.csv"), "name\n" + who + "\n");
        return d;
    }

    /** The statements of {@code text}, parsed as the file {@code file}. */
    private static Statement[] declaredIn(Path file, String text) throws IOException {
        Files.writeString(file, text);
        return Relix.parse(text, file.toAbsolutePath().toString()).statements()
                .toArray(Statement[]::new);
    }

    /** {@code relation}'s one row names {@code who}. */
    private static void readsOnly(Relix relix, String relation, String who) {
        assertThat(relix.relation("π name (" + relation + ")")).rows()
                .hasRowCount(1)
                .hasRow(who);
    }

    @Nested
    @DisplayName("a declaration written in a file")
    final class InAFile {

        @Test
        @DisplayName("reads from that file's directory, not the session's")
        void readsBesideItsFile(@TempDir Path root) throws IOException {
            Path a = dataDir(root, "a", "from-a");
            Path b = dataDir(root, "b", "from-b");
            dataDir(root, "session", "from-session");
            try (Relix relix = Relix.builder().baseDirectory(root.resolve("session")).build()) {
                relix.define(declaredIn(a.resolve("catalog.relix"), CSV_SOURCE.formatted("A")));
                relix.define(declaredIn(b.resolve("catalog.relix"), CSV_SOURCE.formatted("B")));
                readsOnly(relix, "A", "from-a");
                readsOnly(relix, "B", "from-b");
            }
        }

        @Test
        @DisplayName("a JSON source reads from that file's directory too")
        void json(@TempDir Path root) throws IOException {
            Path a = Files.createDirectories(root.resolve("a"));
            Files.writeString(a.resolve("people.json"), "[{\"name\": \"from-a\"}]");
            Files.createDirectories(root.resolve("session"));
            try (Relix relix = Relix.builder().baseDirectory(root.resolve("session")).build()) {
                relix.define(declaredIn(a.resolve("catalog.relix"),
                        "source People from json(\"people.json\");"));
                readsOnly(relix, "People", "from-a");
            }
        }

        @Test
        @DisplayName("a file-backed connection reads from that file's directory too")
        void connection(@TempDir Path root) throws IOException {
            Path a = Files.createDirectories(root.resolve("a"));
            Files.writeString(a.resolve("app.log"), "10.0.0.1 200\n");
            Files.createDirectories(root.resolve("session"));
            try (Relix relix = Relix.builder().baseDirectory(root.resolve("session")).build()) {
                relix.define(declaredIn(a.resolve("catalog.relix"), """
                        connection logs from log { path: "app.log", format: "$remote_addr $status" };
                        source Hits from logs { table: "app.log",
                            schema: { host: STRING, status: NUMBER } };
                        """));
                assertThat(relix.relation("π host (Hits)")).rows().hasRowCount(1).hasRow("10.0.0.1");
            }
        }

        @Test
        @DisplayName("an absolute path is kept")
        void absolute(@TempDir Path root) throws IOException {
            Path elsewhere = dataDir(root, "elsewhere", "absolute");
            Path a = Files.createDirectories(root.resolve("a"));
            String text = """
                    source A from csv("%s") { header: true, schema: { name: STRING } };
                    """.formatted(elsewhere.resolve("data.csv").toString().replace("\\", "/"));
            try (Relix relix = Relix.open()) {
                relix.define(declaredIn(a.resolve("catalog.relix"), text));
                readsOnly(relix, "A", "absolute");
            }
        }
    }

    @Nested
    @DisplayName("a declaration with no file")
    final class NoFile {

        @Test
        @DisplayName("declared in the session reads from the session's base directory")
        void session(@TempDir Path root) throws IOException {
            Path base = dataDir(root, "session", "from-session");
            try (Relix relix = Relix.builder().baseDirectory(base).build()) {
                relix.define(CSV_SOURCE.formatted("A"));
                readsOnly(relix, "A", "from-session");
            }
        }

        @Test
        @DisplayName("parsed under a label rather than an absolute path reads from the base directory")
        void label(@TempDir Path root) throws IOException {
            Path base = dataDir(root, "session", "from-session");
            dataDir(root, "a", "from-a");
            try (Relix relix = Relix.builder().baseDirectory(base).build()) {
                relix.define(Relix.parse(CSV_SOURCE.formatted("A"), "a/catalog.relix")
                        .statements().toArray(Statement[]::new));
                readsOnly(relix, "A", "from-session");
            }
        }
    }

    @Test
    @DisplayName("a closed sandbox reads only from its own base directory")
    void closedSandbox(@TempDir Path root) throws IOException {
        Path base = dataDir(root, "session", "from-sandbox");
        Path a = dataDir(root, "a", "from-a");
        Sandbox sandbox = Sandbox.builder()
                .declarations(CSV_SOURCE.formatted("A"))
                .baseDirectory(base)
                .build();
        try (Relix relix = Relix.builder().sandbox(sandbox).build()) {
            // The same declaration the sandbox permits, written in another directory: it is
            // accepted, and still reads from the sandbox's directory, not from beside it.
            relix.define(declaredIn(a.resolve("catalog.relix"), CSV_SOURCE.formatted("A")));
            readsOnly(relix, "A", "from-sandbox");
        }
    }
}
