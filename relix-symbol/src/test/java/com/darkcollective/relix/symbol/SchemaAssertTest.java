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
package com.darkcollective.relix.symbol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.symbol.SymbolAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SchemaAssert}'s reason to exist is the dotted form of
 * {@link SchemaAssert#hasColumn(String, Type)} and the heading its failures print,
 * so both are tested directly — including the negative cases, which is where the
 * message is the deliverable.
 */
@DisplayName("SchemaAssert — heading assertions that print the heading")
final class SchemaAssertTest {

    /** id:N  name:S  addr:{city:S, zip:S} — flat columns beside a nested one. */
    private static final Schema NESTED = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("name", ScalarType.STRING),
            new ColumnDefinition("addr", new StructType(List.of(
                    new StructType.Field("city", ScalarType.STRING),
                    new StructType.Field("zip", ScalarType.STRING))))));

    @Nested
    @DisplayName("column names")
    class Names {

        @Test
        @DisplayName("states the whole heading in one claim, case-insensitively")
        void names() {
            assertThat(NESTED).hasColumnNames("id", "NAME", "addr");
        }

        @Test
        @DisplayName("a mismatch reports both headings")
        void mismatch() {
            assertThatThrownBy(() -> assertThat(NESTED).hasColumnNames("id", "name"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("[id, name, addr]");
        }

        @Test
        @DisplayName("width is a separate, narrower claim")
        void width() {
            assertThat(NESTED).hasWidth(3);
            assertThatThrownBy(() -> assertThat(NESTED).hasWidth(2))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected 2 column(s) but the heading has 3");
        }
    }

    @Nested
    @DisplayName("dotted paths into a nested column (#684)")
    class DottedPaths {

        @Test
        @DisplayName("resolves a struct field's type through Schema.resolvePath")
        void nestedType() {
            assertThat(NESTED)
                    .hasColumn("id", ScalarType.NUMBER)
                    .hasColumn("addr.city", ScalarType.STRING)
                    .hasColumn("addr.zip");
        }

        @Test
        @DisplayName("a field the struct does not declare fails, naming the heading")
        void unknownField() {
            assertThatThrownBy(() -> assertThat(NESTED).hasColumn("addr.country", ScalarType.STRING))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected a column addr.country")
                    .hasMessageContaining("[id, name, addr]");
        }

        @Test
        @DisplayName("a wrong nested type reports both types")
        void wrongType() {
            assertThatThrownBy(() -> assertThat(NESTED).hasColumn("addr.city", ScalarType.NUMBER))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected column addr.city to be")
                    .hasMessageContaining(ScalarType.STRING.display());
        }

        @Test
        @DisplayName("hasNoColumn is the claim a pruning test makes")
        void hasNoColumn() {
            assertThat(NESTED).hasNoColumn("discount");
            assertThatThrownBy(() -> assertThat(NESTED).hasNoColumn("addr.city"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected no column addr.city");
        }
    }

    @Nested
    @DisplayName("the two zero-column headings")
    class ZeroColumn {

        @Test
        @DisplayName("open and empty are kept apart, as Schema keeps them apart")
        void openVersusEmpty() {
            assertThat(Schema.open()).isOpen();
            assertThat(Schema.empty()).isEmptyHeading();
            assertThatThrownBy(() -> assertThat(Schema.open()).isEmptyHeading())
                    .isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> assertThat(Schema.empty()).isOpen())
                    .isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("the description names which of the two it is")
        void describesItself() {
            assertThatThrownBy(() -> assertThat(Schema.open()).hasWidth(1))
                    .hasMessageContaining("<open>");
        }
    }

    @Test
    @DisplayName("columnNamesAssert hands the names to AssertJ, keeping the heading as context")
    void columnNamesAssert() {
        assertThat(NESTED).columnNamesAssert().containsExactlyInAnyOrder("addr", "id", "name");
    }

    @Test
    @DisplayName("the failure carries the compact name:type heading, not a record dump")
    void failurePrintsHeading() {
        assertThatThrownBy(() -> assertThat(NESTED).hasWidth(9))
                .hasMessageContaining("id:N")
                .hasMessageContaining("name:S")
                .hasMessageNotContaining("ColumnDefinition[");
    }
}
