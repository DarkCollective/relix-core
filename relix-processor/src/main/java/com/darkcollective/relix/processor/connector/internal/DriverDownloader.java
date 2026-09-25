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
import com.darkcollective.relix.processor.connector.Fetcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Downloads driver {@link Artifact}s into the driver directory,
 * verifying each against its SHA-256 before it is trusted.
 *
 * <p>An artifact is a URL and a digest, so this works the same for a JDBC driver
 * and for a connector plugin's JARs — the two callers of it.
 *
 * <p>Integrity is mandatory: a byte stream whose digest does not match the
 * manifest is rejected (never written), so a tampered mirror or a corrupted
 * transfer cannot install a driver.  An artifact already present with a matching
 * checksum is skipped, so provisioning is idempotent.  Writes are atomic (download
 * to a temp file, verify, then move into place).
 */
public final class DriverDownloader {

    private final Path targetDirectory;
    private final Fetcher fetcher;

    /**
     * @param targetDirectory the directory to download JARs into (created if absent)
     * @param fetcher         the byte-fetching seam
     */
    public DriverDownloader(Path targetDirectory, Fetcher fetcher) {
        this.targetDirectory = Objects.requireNonNull(targetDirectory, "targetDirectory");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * Downloads a list of artifacts into the target directory (creating it), each
     * verified against its SHA-256.  Shared by driver and connector-plugin provisioning.
     *
     * @param artifacts the artifacts to download
     * @return the paths of the downloaded (or already-present) JARs
     * @throws IOException if a download fails or a checksum does not match
     */
    public List<Path> download(List<Artifact> artifacts) throws IOException {
        Objects.requireNonNull(artifacts, "artifacts");
        Files.createDirectories(targetDirectory);
        List<Path> result = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            result.add(downloadArtifact(artifact));
        }
        return List.copyOf(result);
    }

    private Path downloadArtifact(Artifact artifact) throws IOException {
        Path target = targetDirectory.resolve(artifact.fileName());
        String expected = artifact.sha256().toLowerCase(Locale.ROOT);

        if (Files.isRegularFile(target) && sha256(Files.readAllBytes(target)).equals(expected)) {
            return target;   // already present and intact — idempotent
        }

        byte[] bytes;
        try {
            bytes = fetcher.fetch(java.net.URI.create(artifact.url()));
        } catch (IllegalArgumentException badUri) {
            throw new IOException("invalid driver artifact URL: " + artifact.url(), badUri);
        }

        String actual = sha256(bytes);
        if (!actual.equals(expected)) {
            throw new IOException("checksum mismatch for " + artifact.url()
                    + " (expected " + expected + ", got " + actual + ")");
        }

        Path temp = Files.createTempFile(targetDirectory, ".download-", ".jar.tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // never on a conformant JVM
        }
    }
}
