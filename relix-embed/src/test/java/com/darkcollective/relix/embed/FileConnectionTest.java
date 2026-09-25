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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("a file connection's file, as a session resolves it")
final class FileConnectionTest {

    private static final String LOG = """
            connection logs from log { %s, format: "common" };
            source Access from logs { table: "access.log", schema: { host: STRING, status: NUMBER } };
            """;

    @Test
    @DisplayName("a relative path resolves against the session's base directory")
    void relativeToBaseDirectory(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("logs"));
        Files.writeString(dir.resolve("logs/access.log"),
                "h - - [10/Oct/2026:13:02:11 +0000] \"GET / HTTP/1.1\" 200 1\n");
        try (Relix relix = Relix.builder().baseDirectory(dir).build()) {
            relix.define(LOG.formatted("path: \"logs\""));
            assertThat(relix.relation("σ status = 200 (Access)")).hasRowCount(1);
        }
    }

    @Test
    @DisplayName("a session that does not fetch refuses an https file before reading anything")
    void remoteFilesRefused() {
        try (Relix relix = Relix.builder().remoteFiles(false).build()) {
            relix.define(LOG.formatted("url: \"https://relix.darkcollective.com/access.log\""));
            assertThatThrownBy(() -> relix.relation("Access").toList())
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("does not fetch remote files");
        }
    }
}
