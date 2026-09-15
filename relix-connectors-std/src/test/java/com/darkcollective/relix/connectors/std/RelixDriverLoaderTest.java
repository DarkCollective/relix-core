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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for {@link RelixDriverLoader} — directory resolution, the scan/skip rules,
 * and end-to-end driver discovery + registration from an external JAR.
 *
 * <p>The H2 driver JAR (a test dependency) is the fixture: copied into a temporary
 * driver directory, it must be discovered, registered via a {@link DriverShim}, and
 * reported in the result.
 */
final class RelixDriverLoaderTest {

    // ─── directory resolution ────────────────────────────────────────────────

    @Test
    void resolveDirectoryUsesOverrideWhenSet() {
        assertThat(RelixDriverLoader.resolveDirectory("/opt/relix/jdbc"))
                .isEqualTo(Path.of("/opt/relix/jdbc"));
    }

    @Test
    void resolveDirectoryTrimsOverride() {
        assertThat(RelixDriverLoader.resolveDirectory("  /opt/relix/jdbc  "))
                .isEqualTo(Path.of("/opt/relix/jdbc"));
    }

    @Test
    void resolveDirectoryFallsBackWhenNull() {
        assertThat(RelixDriverLoader.resolveDirectory(null))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".relix", "drivers"));
    }

    @Test
    void resolveDirectoryFallsBackWhenBlank() {
        assertThat(RelixDriverLoader.resolveDirectory("   "))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".relix", "drivers"));
    }

    // ─── scan / skip behaviour ───────────────────────────────────────────────

    @Test
    void missingDirectoryYieldsNoDrivers(@TempDir Path tmp) {
        Path absent = tmp.resolve("does-not-exist");
        assertThat(new RelixDriverLoader(absent).load()).isEmpty();
    }

    @Test
    void emptyDirectoryYieldsNoDrivers(@TempDir Path tmp) {
        assertThat(new RelixDriverLoader(tmp).load()).isEmpty();
    }

    @Test
    void nonJarFilesAreIgnored(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("README.txt"), "not a driver");
        Files.writeString(tmp.resolve("notes.md"), "still not a driver");
        assertThat(new RelixDriverLoader(tmp).load()).isEmpty();
    }

    // ─── end-to-end load + register ──────────────────────────────────────────

    @Test
    void loadsAndRegistersDriverFromExternalJar(@TempDir Path tmp) throws Exception {
        Files.copy(h2Jar(), tmp.resolve("h2-driver.jar"), StandardCopyOption.REPLACE_EXISTING);
        // a stray non-jar alongside the driver must not disturb the load
        Files.writeString(tmp.resolve("ignore-me.txt"), "x");

        List<String> registered = new RelixDriverLoader(tmp).load();

        assertThat(registered).anyMatch(d -> d.startsWith("org.h2.Driver v"));
        assertThat(shimIsRegistered()).isTrue();
    }

    @Test
    void loadIsSafeToCallEvenWithNoDrivers(@TempDir Path tmp) {
        assertDoesNotThrow(() -> new RelixDriverLoader(tmp).load());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Locates the H2 driver JAR on the test runtime path without a compile-time dependency. */
    private static Path h2Jar() throws Exception {
        Class<?> h2 = Class.forName("org.h2.Driver");
        URI location = h2.getProtectionDomain().getCodeSource().getLocation().toURI();
        return Path.of(location);
    }

    /** True if at least one {@link DriverShim} is registered with {@link DriverManager}. */
    private static boolean shimIsRegistered() {
        for (var it = DriverManager.getDrivers().asIterator(); it.hasNext(); ) {
            Driver d = it.next();
            if (d instanceof DriverShim) {
                return true;
            }
        }
        return false;
    }
}
