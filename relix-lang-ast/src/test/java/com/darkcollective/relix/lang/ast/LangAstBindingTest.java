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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.lang.ast.source.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for HTTP binding and extract/paginate AST nodes:
 * {@link QueryParamBinding}, {@link PathParamBinding}, {@link HeaderBinding},
 * {@link ExtractPathBinding}, {@link JsonExtractSpec}, {@link CsvExtractSpec},
 * {@link PaginateSpec}, and {@link PaginateEntry}.
 */
@DisplayName("relix-lang-ast — binding and extract nodes")
final class LangAstBindingTest {

    // =========================================================================
    // ColumnBinding implementations
    // =========================================================================

    @Nested
    @DisplayName("QueryParamBinding")
    class QueryParamBindingTests {

        @Test
        @DisplayName("Stores paramName")
        void storesParamName() {
            QueryParamBinding b = new QueryParamBinding("q");
            assertThat(b.paramName()).isEqualTo("q");
        }

        @Test
        @DisplayName("Rejects blank paramName")
        void rejectsBlankParamName() {
            assertThatThrownBy(() -> new QueryParamBinding("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null paramName")
        void rejectsNullParamName() {
            assertThatThrownBy(() -> new QueryParamBinding(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("PathParamBinding")
    class PathParamBindingTests {

        @Test
        @DisplayName("Stores paramName")
        void storesParamName() {
            PathParamBinding b = new PathParamBinding("id");
            assertThat(b.paramName()).isEqualTo("id");
        }

        @Test
        @DisplayName("Rejects blank paramName")
        void rejectsBlankParamName() {
            assertThatThrownBy(() -> new PathParamBinding(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null paramName")
        void rejectsNullParamName() {
            assertThatThrownBy(() -> new PathParamBinding(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("HeaderBinding")
    class HeaderBindingTests {

        @Test
        @DisplayName("Stores headerName")
        void storesHeaderName() {
            HeaderBinding b = new HeaderBinding("X-Api-Key");
            assertThat(b.headerName()).isEqualTo("X-Api-Key");
        }

        @Test
        @DisplayName("Rejects blank headerName")
        void rejectsBlankHeaderName() {
            assertThatThrownBy(() -> new HeaderBinding(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null headerName")
        void rejectsNullHeaderName() {
            assertThatThrownBy(() -> new HeaderBinding(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ExtractPathBinding")
    class ExtractPathBindingTests {

        @Test
        @DisplayName("Stores path")
        void storesPath() {
            ExtractPathBinding b = new ExtractPathBinding("$.main.temp");
            assertThat(b.path()).isEqualTo("$.main.temp");
        }

        @Test
        @DisplayName("Rejects blank path")
        void rejectsBlankPath() {
            assertThatThrownBy(() -> new ExtractPathBinding("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null path")
        void rejectsNullPath() {
            assertThatThrownBy(() -> new ExtractPathBinding(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // ExtractSpec implementations
    // =========================================================================

    @Nested
    @DisplayName("JsonExtractSpec")
    class JsonExtractSpecTests {

        @Test
        @DisplayName("Stores jsonPath")
        void storesJsonPath() {
            JsonExtractSpec spec = new JsonExtractSpec("$.items[*]");
            assertThat(spec.jsonPath()).isEqualTo("$.items[*]");
        }

        @Test
        @DisplayName("Rejects blank jsonPath")
        void rejectsBlankJsonPath() {
            assertThatThrownBy(() -> new JsonExtractSpec(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null jsonPath")
        void rejectsNullJsonPath() {
            assertThatThrownBy(() -> new JsonExtractSpec(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("CsvExtractSpec")
    class CsvExtractSpecTests {

        @Test
        @DisplayName("WITH_HEADER constant has hasHeader true")
        void withHeaderConstantIsTrue() {
            assertThat(CsvExtractSpec.WITH_HEADER.hasHeader()).isTrue();
        }

        @Test
        @DisplayName("WITHOUT_HEADER constant has hasHeader false")
        void withoutHeaderConstantIsFalse() {
            assertThat(CsvExtractSpec.WITHOUT_HEADER.hasHeader()).isFalse();
        }

        @Test
        @DisplayName("Constructor stores hasHeader correctly")
        void constructorStoresHasHeader() {
            assertThat(new CsvExtractSpec(true).hasHeader()).isTrue();
            assertThat(new CsvExtractSpec(false).hasHeader()).isFalse();
        }
    }

    // =========================================================================
    // PaginateSpec
    // =========================================================================

    @Nested
    @DisplayName("PaginateSpec")
    class PaginateSpecTests {

        @Test
        @DisplayName("entry() finds by logical name")
        void entryFindsByLogicalName() {
            PaginateEntry limit = new PaginateEntry("limit", "limit", Optional.of(100L));
            PaginateSpec spec = new PaginateSpec(List.of(limit));
            assertThat(spec.entry("limit")).isPresent();
            assertThat(spec.entry("offset")).isEmpty();
        }

        @Test
        @DisplayName("Rejects empty entries")
        void rejectsEmptyEntries() {
            assertThatThrownBy(() -> new PaginateSpec(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // PaginateEntry
    // =========================================================================

    @Nested
    @DisplayName("PaginateEntry")
    class PaginateEntryTests {

        @Test
        @DisplayName("Stores all fields")
        void storesAllFields() {
            PaginateEntry e = new PaginateEntry("limit", "per_page", Optional.of(50L));
            assertThat(e.logicalName()).isEqualTo("limit");
            assertThat(e.paramName()).isEqualTo("per_page");
            assertThat(e.defaultValue()).isEqualTo(Optional.of(50L));
        }

        @Test
        @DisplayName("Absent default value is allowed")
        void absentDefaultAllowed() {
            PaginateEntry e = new PaginateEntry("offset", "offset", Optional.empty());
            assertThat(e.defaultValue()).isEmpty();
        }

        @Test
        @DisplayName("Rejects blank logicalName")
        void rejectsBlankLogicalName() {
            assertThatThrownBy(() -> new PaginateEntry("", "limit", Optional.of(10L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects blank paramName")
        void rejectsBlankParamName() {
            assertThatThrownBy(() -> new PaginateEntry("limit", "   ", Optional.of(10L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null defaultValue")
        void rejectsNullDefaultValue() {
            assertThatThrownBy(() -> new PaginateEntry("limit", "limit", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
