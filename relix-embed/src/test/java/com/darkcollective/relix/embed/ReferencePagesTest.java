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
    @DisplayName("a page is keyed by its symbol, so looking up what the index prints finds it")
    void symbolIsAKey() {
        assertThat(Relix.referencePages()).allSatisfy(page -> assertThat(page.keys())
                .as(page.path())
                .contains(page.symbol().toLowerCase(Locale.ROOT)));

        assertThat(lookup("top")).isEqualTo("advanced/top.md");
        assertThat(lookup("window rank")).isEqualTo("operators/window-ranking.md");
        assertThat(lookup("window lag")).isEqualTo("operators/window-offset.md");
        assertThat(lookup("limit")).isEqualTo("operators/limit.md");
    }

    @Test
    @DisplayName("a page is keyed by the ASCII spellings the spellings table gives its operator")
    void asciiSpellingsAreKeys() {
        assertThat(lookup("anti")).isEqualTo("joins/anti-join.md");
        assertThat(lookup("semi")).isEqualTo("joins/semi-join.md");
        assertThat(lookup("><")).isEqualTo("joins/theta-join.md");
        assertThat(lookup("|><")).isEqualTo("joins/left-outer-join.md");
        assertThat(lookup("ljoin")).isEqualTo("joins/left-outer-join.md");
        assertThat(lookup("rjoin")).isEqualTo("joins/right-outer-join.md");
        assertThat(lookup("|><|")).isEqualTo("joins/full-outer-join.md");
        assertThat(lookup("diff")).isEqualTo("set-operations/difference.md");
        assertThat(lookup("except")).isEqualTo("set-operations/difference.md");
        assertThat(lookup("intersect")).isEqualTo("set-operations/intersection.md");
        assertThat(lookup("order by")).isEqualTo("operators/sort.md");
        assertThat(lookup("<=")).isEqualTo("predicates/comparison.md");
        assertThat(lookup("⊥")).isEqualTo("predicates/is-null.md");
        assertThat(lookup("x is null"))
                .as("a usage with an operand in it is not a spelling")
                .isEqualTo("(none)");
        assertThat(lookup("->"))
                .as("a row naming no page adds nothing")
                .isEqualTo("(none)");
    }

    @Test
    @DisplayName("an ASCII spelling of a glyph no page is about keys nothing")
    void rowNamingNoPage() {
        ReferencePage page = new ReferencePage("a.md", "operator", "A", "σ", "s", List.of("σ"));
        List<ReferencePage> keyed = ReferenceIndex.withSpellings(List.of(page), """
                | Unicode | ASCII |
                |---|---|
                | `σ` | `SELECT` |
                | `→` | `->` |
                | `x` / `y` | `p` / `q` |
                """);
        assertThat(keyed).singleElement()
                .extracting(ReferencePage::keys)
                .isEqualTo(List.of("σ", "select"));
    }

    /** The page a key finds, as a reader of the index would look it up. */
    static String lookup(String key) {
        String wanted = key.toLowerCase(Locale.ROOT);
        return Relix.referencePages().stream()
                .filter(page -> page.keys().contains(wanted))
                .map(ReferencePage::path)
                .findFirst()
                .orElse("(none)");
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
