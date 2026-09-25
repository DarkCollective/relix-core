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
package com.darkcollective.relix.processor.connector.internal;

import com.darkcollective.relix.processor.connector.Artifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Tests for {@link DriverDownloader} — checksum verification, idempotency, atomic write. */
final class DriverDownloaderTest {

    private static final byte[] PAYLOAD = "fake-driver-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String GOOD_SHA = sha256(PAYLOAD);

    private static List<Artifact> entry(String sha) {
        return List.of(new Artifact("https://example.test/fake-driver.jar", sha));
    }

    @Test
    void downloadsAndWritesWhenChecksumMatches(@TempDir Path dir) throws IOException {
        DriverDownloader downloader = new DriverDownloader(dir, uri -> PAYLOAD);

        List<Path> jars = downloader.download(entry(GOOD_SHA));

        assertThat(jars).singleElement().satisfies(p -> {
            assertThat(p.getFileName()).hasToString("fake-driver.jar");
            assertThat(p).hasBinaryContent(PAYLOAD);
        });
    }

    @Test
    void createsTargetDirectoryWhenAbsent(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("a/b/drivers");
        new DriverDownloader(nested, uri -> PAYLOAD).download(entry(GOOD_SHA));
        assertThat(nested.resolve("fake-driver.jar")).exists();
    }

    @Test
    void rejectsChecksumMismatchAndWritesNothing(@TempDir Path dir) {
        DriverDownloader downloader = new DriverDownloader(dir, uri -> PAYLOAD);

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> downloader.download(entry("0000deadbeef")))
                .withMessageContaining("checksum mismatch");

        assertThat(dir.resolve("fake-driver.jar")).doesNotExist();
    }

    @Test
    void skipsRedownloadWhenAlreadyPresentAndIntact(@TempDir Path dir) throws IOException {
        AtomicInteger fetches = new AtomicInteger();
        DriverDownloader downloader = new DriverDownloader(dir, uri -> {
            fetches.incrementAndGet();
            return PAYLOAD;
        });

        downloader.download(entry(GOOD_SHA));
        downloader.download(entry(GOOD_SHA));   // second call must not fetch again

        assertThat(fetches).hasValue(1);
    }

    @Test
    void propagatesFetchFailure(@TempDir Path dir) {
        DriverDownloader downloader = new DriverDownloader(dir, uri -> {
            throw new IOException("network down");
        });
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> downloader.download(entry(GOOD_SHA)))
                .withMessageContaining("network down");
    }

    @Test
    void wrapsInvalidArtifactUrl(@TempDir Path dir) {
        DriverDownloader downloader = new DriverDownloader(dir, uri -> PAYLOAD);
        var badEntry = List.of(new Artifact("http://exa mple/with space", GOOD_SHA));
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> downloader.download(badEntry))
                .withMessageContaining("invalid driver artifact URL");
    }

    @Test
    void downloadsMultipleArtifacts(@TempDir Path dir) throws IOException {
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        var multi = List.of(
                new Artifact("https://x/one.jar", GOOD_SHA),
                new Artifact("https://x/two.jar", sha256(second)));

        DriverDownloader downloader = new DriverDownloader(dir, uri ->
                uri.toString().endsWith("two.jar") ? second : PAYLOAD);

        assertThat(downloader.download(multi)).hasSize(2);
        assertThat(dir.resolve("one.jar")).exists();
        assertThat(dir.resolve("two.jar")).exists();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
