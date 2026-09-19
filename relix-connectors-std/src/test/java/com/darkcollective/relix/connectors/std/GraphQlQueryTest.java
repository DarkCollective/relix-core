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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GraphQlQuery — the selection set is the field list")
final class GraphQlQueryTest {

    @Nested
    @DisplayName("what it builds")
    final class Builds {

        @Test
        @DisplayName("the records path becomes the wrapper, minus GraphQL's data envelope")
        void envelopeIsNotAField() {
            assertThat(GraphQlQuery.build("data.users", List.of("id", "name")))
                    .contains("{ users { id name } }");
        }

        @Test
        @DisplayName("a records path with no envelope is taken as written")
        void withoutEnvelope() {
            assertThat(GraphQlQuery.build("users", List.of("id")))
                    .contains("{ users { id } }");
        }

        @Test
        @DisplayName("a deeper records path nests one selection per segment")
        void nestedRecordsPath() {
            assertThat(GraphQlQuery.build("data.search.results", List.of("id")))
                    .contains("{ search { results { id } } }");
        }

        @Test
        @DisplayName("a dotted column path becomes a nested selection")
        void nestedColumn() {
            assertThat(GraphQlQuery.build("data.posts", List.of("id", "author.name")))
                    .contains("{ posts { id author { name } } }");
        }

        @Test
        @DisplayName("two columns under one parent share its selection set")
        void siblingsShareAParent() {
            assertThat(GraphQlQuery.build("data.posts", List.of("author.name", "author.email")))
                    .contains("{ posts { author { name email } } }");
        }

        @Test
        @DisplayName("fields keep the order the scan asked for them in")
        void fieldOrderIsTheScansOrder() {
            assertThat(GraphQlQuery.build("data.users", List.of("name", "id")))
                    .contains("{ users { name id } }");
        }
    }

    @Nested
    @DisplayName("what it declines")
    final class Declines {

        // Declining is the safe direction: the caller then sends what it would have sent
        // anyway. A nearly-right document is either an error from the server or, worse,
        // rows that look plausible.

        @Test
        @DisplayName("a records path that is only the envelope leaves nothing to select from")
        void envelopeAlone() {
            assertThat(GraphQlQuery.build("data", List.of("id"))).isEmpty();
        }

        @Test
        @DisplayName("an empty records path")
        void emptyRecordsPath() {
            assertThat(GraphQlQuery.build("", List.of("id"))).isEmpty();
        }

        @Test
        @DisplayName("no columns at all — a bare selection set is not a valid document")
        void noColumns() {
            assertThat(GraphQlQuery.build("data.users", List.of())).isEmpty();
        }

        @Test
        @DisplayName("a records-path segment that is not a GraphQL name")
        void recordsPathIsNotAName() {
            assertThat(GraphQlQuery.build("data.users[0]", List.of("id"))).isEmpty();
            assertThat(GraphQlQuery.build("data.user-list", List.of("id"))).isEmpty();
        }

        @Test
        @DisplayName("a column path that is not a GraphQL name")
        void columnIsNotAName() {
            assertThat(GraphQlQuery.build("data.users", List.of("id", "full name"))).isEmpty();
            assertThat(GraphQlQuery.build("data.users", List.of("items[0]"))).isEmpty();
        }

        @Test
        @DisplayName("a blank column path")
        void blankColumn() {
            assertThat(GraphQlQuery.build("data.users", List.of("id", ""))).isEmpty();
        }

        @Test
        @DisplayName("a null path, from either side")
        void nulls() {
            assertThat(GraphQlQuery.build(null, List.of("id"))).isEmpty();
            assertThat(GraphQlQuery.build("data.users", Arrays.asList("id", null))).isEmpty();
        }
    }
}
