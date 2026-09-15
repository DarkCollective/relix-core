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
package com.darkcollective.relix.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Suggestions — edit-distance 'did you mean?' helper")
final class SuggestionsTest {

    @Nested
    @DisplayName("osaDistance")
    final class Distance {

        @Test
        void identicalStringsAreZero() {
            assertThat(Suggestions.osaDistance("users", "users")).isZero();
        }

        @Test
        void singleInsertionDeletionSubstitution() {
            assertThat(Suggestions.osaDistance("user", "users")).isEqualTo(1);  // insertion
            assertThat(Suggestions.osaDistance("users", "user")).isEqualTo(1);  // deletion
            assertThat(Suggestions.osaDistance("users", "usars")).isEqualTo(1); // substitution
        }

        @Test
        @DisplayName("adjacent transposition counts as a single edit (Damerau, not plain Levenshtein)")
        void adjacentTransposition() {
            assertThat(Suggestions.osaDistance("ab", "ba")).isEqualTo(1);
            assertThat(Suggestions.osaDistance("coalsece", "coalesce")).isEqualTo(1);
        }

        @Test
        void emptyOperands() {
            assertThat(Suggestions.osaDistance("", "abc")).isEqualTo(3);
            assertThat(Suggestions.osaDistance("abc", "")).isEqualTo(3);
            assertThat(Suggestions.osaDistance("", "")).isZero();
        }
    }

    @Nested
    @DisplayName("closest")
    final class Closest {

        @Test
        void findsTheClosestCandidateWithinThreshold() {
            assertThat(Suggestions.closest("Usres", List.of("Users", "Orders", "Items")))
                    .contains("Users");
        }

        @Test
        @DisplayName("matching is case-insensitive but the candidate's spelling is preserved")
        void caseInsensitiveButPreservesCandidateCasing() {
            assertThat(Suggestions.closest("USERZ", List.of("Users")))
                    .contains("Users");
        }

        @Test
        @DisplayName("no suggestion when nothing is within the (length-scaled) threshold")
        void noSuggestionWhenTooFar() {
            assertThat(Suggestions.closest("no_such_relation_at_all", List.of("Users", "Orders")))
                    .isEmpty();
        }

        @Test
        @DisplayName("short names allow only a single edit")
        void shortNamesAllowOnlyOneEdit() {
            assertThat(Suggestions.closest("xyz", List.of("Len"))).isEmpty();   // distance 3
            assertThat(Suggestions.closest("Lon", List.of("Len"))).contains("Len"); // distance 1
        }

        @Test
        @DisplayName("an exact match is never suggested — it would not be an error")
        void exactMatchIsNotSuggested() {
            assertThat(Suggestions.closest("Users", List.of("Users"))).isEmpty();
        }

        @Test
        @DisplayName("ties are broken deterministically regardless of candidate order")
        void deterministicTieBreak() {
            // "cat" is distance 1 from both "bat" and "car"; "bat" wins (case-insensitive order).
            assertThat(Suggestions.closest("cat", List.of("bat", "car"))).contains("bat");
            assertThat(Suggestions.closest("cat", List.of("car", "bat"))).contains("bat");
        }

        @Test
        void emptyTargetOrCandidates() {
            assertThat(Suggestions.closest("", List.of("Users"))).isEmpty();
        }

        @Test
        @DisplayName("every degenerate input is refused, and each is its own arm")
        void degenerateInputsAreRefused() {
            // Four separate conditions guard this, and an empty target short-circuits on
            // the first — so the rest are only reached by writing them out.
            assertThat(Suggestions.closest(null, List.of("Users"))).as("null target").isEmpty();
            assertThat(Suggestions.closest("Usres", null)).as("null candidates").isEmpty();
            assertThat(Suggestions.closest("Usres", List.of())).as("no candidates").isEmpty();
        }

        @Test
        @DisplayName("a null or empty candidate is skipped rather than measured against")
        void degenerateCandidatesAreSkipped() {
            // A candidate list is built from a schema, and an open one can carry a blank.
            // Measuring an edit distance against "" would make it the nearest match to
            // every short name.
            List<String> withHoles = new java.util.ArrayList<>();
            withHoles.add(null);
            withHoles.add("");
            withHoles.add("Users");
            assertThat(Suggestions.closest("Usres", withHoles)).contains("Users");
            assertThat(Suggestions.closest("ab", List.of(""))).isEmpty();
            assertThat(Suggestions.closest("Users", List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("didYouMean")
    final class DidYouMean {

        @Test
        void formatsASuffixForAClosely() {
            assertThat(Suggestions.didYouMean("Usres", List.of("Users")))
                    .isEqualTo(" — did you mean 'Users'?");
        }

        @Test
        void emptyWhenNoCandidateIsCloseEnough() {
            assertThat(Suggestions.didYouMean("totally_different", List.of("Users")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("columnHint")
    final class ColumnHint {

        @Test
        void prefersAClosestColumnSuggestion() {
            assertThat(Suggestions.columnHint("nam", List.of("id", "name"), true))
                    .isEqualTo(" — did you mean 'name'?");
        }

        @Test
        void listsAvailableColumnsWhenNoCloseMatchAndSchemaKnown() {
            assertThat(Suggestions.columnHint("zzzzzz", List.of("id", "name"), true))
                    .isEqualTo(" (available: id, name)");
        }

        @Test
        void emptyWhenSchemaIsOpenAndNoCloseMatch() {
            // An open (schema-on-read) input has no fixed column set to list.
            assertThat(Suggestions.columnHint("zzzzzz", List.of(), false)).isEmpty();
        }

        @Test
        void truncatesALongAvailabilityListWithAnEllipsis() {
            List<String> many = List.of("c0", "c1", "c2", "c3", "c4", "c5",
                    "c6", "c7", "c8", "c9", "c10", "c11", "c12", "c13");
            String hint = Suggestions.columnHint("zzzzzz", many, true);
            assertThat(hint).startsWith(" (available: c0, c1,").endsWith(", …)");
        }

        @Test
        @DisplayName("no availability list when the schema is unknown or carries no columns")
        void noAvailabilityListWithoutAKnownSchema() {
            // Three conditions gate the list and each says something different: an
            // open/schema-on-read input cannot promise what it has, and an empty or absent
            // column list has nothing to offer. Listing nothing beats listing "()".
            assertThat(Suggestions.columnHint("zzzzzz", List.of("a", "b"), false))
                    .as("schema not known").isEmpty();
            assertThat(Suggestions.columnHint("zzzzzz", null, true))
                    .as("no column list").isEmpty();
            assertThat(Suggestions.columnHint("zzzzzz", List.of(), true))
                    .as("empty column list").isEmpty();
        }
    }
}
