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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The language reference, as the jar serves it. */
@DisplayName("Relix.referencePages / referencePage — the reference in the jar")
final class ReferencePagesTest {

    @Test
    @DisplayName("every indexed page is in the jar, and is a reference page")
    void everyPageIsBundled() {
        List<String> missing = new ArrayList<>();
        for (ReferencePage page : Relix.referencePages()) {
            Relix.referencePage(page.path())
                    .filter(md -> md.startsWith("# "))
                    .ifPresentOrElse(md -> { }, () -> missing.add(page.path()));
        }
        assertThat(Relix.referencePages()).hasSizeGreaterThan(90);
        assertThat(missing).as("indexed pages the jar does not carry").isEmpty();
    }

    @Test
    @DisplayName("no lookup key belongs to two pages, which would hide one of them")
    void keysAreUnique() {
        Map<String, String> owner = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        for (ReferencePage page : Relix.referencePages()) {
            for (String key : page.keys()) {
                String previous = owner.putIfAbsent(key.toLowerCase(Locale.ROOT), page.path());
                if (previous != null && !previous.equals(page.path())) {
                    clashes.add(key + ": " + previous + " and " + page.path());
                }
            }
        }
        assertThat(clashes).isEmpty();
    }

    @Test
    @DisplayName("a path the reference has no page at answers empty")
    void unknownPaths() {
        assertThat(Relix.referencePage("operators/nothing.md")).isEmpty();
        assertThat(Relix.referencePage("../../../module-info.class")).isEmpty();
        assertThat(Relix.referencePage("operators/select")).isEmpty();
        assertThatThrownBy(() -> Relix.referencePage(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("function pages and the REPL page are not in it")
    void whatIsLeftOut() {
        assertThat(Relix.referencePages()).extracting(ReferencePage::path)
                .noneMatch(p -> p.startsWith("functions/"))
                .doesNotContain("advanced/repl.md");
        assertThat(Relix.referencePage("advanced/repl.md")).isEmpty();
        assertThat(Relix.referencePage("operators/select.md")).get().asString()
                .startsWith("# Name: Selection");
    }

    @Test
    @DisplayName("a page's components are checked and its keys copied")
    void record() {
        List<String> keys = new ArrayList<>(List.of("σ"));
        ReferencePage page = new ReferencePage("a.md", "operator", "A", "σ", "s", keys);
        keys.add("x");
        assertThat(page.keys()).containsExactly("σ");
        assertThatThrownBy(() -> new ReferencePage(null, "c", "t", "s", "s", List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
