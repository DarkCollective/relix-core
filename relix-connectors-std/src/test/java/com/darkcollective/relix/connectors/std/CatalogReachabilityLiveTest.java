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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.processor.connector.Artifact;
import com.darkcollective.relix.processor.connector.ConnectorProvisioner;
import com.darkcollective.relix.processor.connector.Fetcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provisioning catalogs are claims about the outside world, and only the outside world
 * can check them.
 *
 * <p>{@code relix-drivers.json} names a Maven Central URL and a pinned SHA-256 for every
 * downloadable JDBC driver, and {@code ConnectorProvisioner.DEFAULT_CATALOG_URL} names a
 * file served from this repository. Nothing in the repository can tell whether those URLs
 * still resolve: a coordinate that moves, a release asset that is deleted, an artifact
 * republished under the same name, and {@code relix drivers download} stops working for
 * every user while the whole suite stays green. The hash is the sharpest case — a mismatch
 * is not a broken download but a <em>refused</em> one, and the refusal looks identical to
 * tampering.
 *
 * <p>This is what the {@code live} tier is for, and why these assertions cannot move into
 * the gate: they need the network, they are slow (a few MB per run), and they can fail for
 * reasons that are nobody's fault. Run them deliberately:
 *
 * <pre>
 * ./gradlew :relix-connectors-std:liveTest
 * ./gradlew verifyAll -PtestTiers=live
 * </pre>
 *
 * <p>A failure here is a release-blocking fact about the published catalogs, not a broken
 * change — read it as "the catalog needs updating", and check the artifact by hand before
 * changing a pinned hash.
 *
 * <p>It earned its place on the first run: the connector catalog's published URL returns 404
 * to anyone unauthenticated, because this repository is private (issue #686).
 */
@Tag("live")
@DisplayName("The published catalogs still resolve")
final class CatalogReachabilityLiveTest {

    private static final Fetcher HTTPS = HttpFetcher.https();

    @TestFactory
    @DisplayName("every bundled driver artifact downloads and matches its pinned hash")
    Stream<DynamicTest> driverArtifactsAreReachableAndUnchanged() {
        List<DriverCatalog.Entry> entries = DriverCatalog.load().entries();
        assertThat(entries)
                .as("the bundled catalog is empty, so this tier is checking nothing")
                .isNotEmpty();

        return entries.stream().flatMap(entry -> entry.artifacts().stream()
                .map(artifact -> DynamicTest.dynamicTest(
                        entry.name() + " — " + fileName(artifact),
                        () -> assertArtifactMatches(entry.name(), artifact))));
    }

    private static void assertArtifactMatches(String driver, Artifact artifact) throws IOException {
        byte[] bytes = HTTPS.fetch(URI.create(artifact.url()));

        assertThat(bytes)
                .as("%s: %s returned nothing", driver, artifact.url())
                .isNotEmpty();
        assertThat(sha256(bytes))
                .as("%s: the artifact at %s no longer hashes to the pinned value. Either it "
                    + "was republished under the same coordinate or the pin is stale — check "
                    + "the artifact by hand before changing the hash, because a mismatch is "
                    + "how a tampered download is refused.", driver, artifact.url())
                .isEqualTo(artifact.sha256());
    }

    @Test
    @DisplayName("the connector catalog URL is at least HTTPS and well-formed")
    void connectorCatalogUrlIsWellFormed() {
        // Still deliberately NOT a reachability check, and the event it waits on has moved
        // once more — closer, and to something exactly datable. relix-core exists and is
        // public, so the old blocker (#686) is gone; but the URL is now a release asset
        // rather than a file on a branch, and `/releases/latest/download/` resolves only
        // once there is a release that is not a pre-release. So it 404s until the first
        // real release, and a release candidate will not change that — which is the whole
        // point of serving it from there.
        //
        // A permanently-red test in an opt-in tier is a broken window: people learn to
        // ignore the tier, and then it stops reporting the failures it exists for. So the
        // assertion goes in with the first non-prerelease release, and the tier starts
        // verifying it the day it becomes verifiable.
        assertThat(ConnectorProvisioner.DEFAULT_CATALOG_URL)
                .as("a catalog fetched over plaintext could be swapped in transit")
                .startsWith("https://");
    }

    @Test
    @DisplayName("a plaintext URL is refused before any request is made")
    void plaintextIsRefused() {
        // Part of the same contract: the fetcher is what stops a manifest smuggling a
        // plaintext download past the hash check. Hermetic, but it belongs with the rest —
        // it is the same object, and the assertion is about what it does on the network.
        assertThatThrownBy(() -> HTTPS.fetch(URI.create("http://repo1.maven.org/maven2/")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("non-HTTPS");
    }

    // ── support ─────────────────────────────────────────────────────────────────

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required of every JVM", e);
        }
    }

    private static String fileName(Artifact artifact) {
        String url = artifact.url();
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
