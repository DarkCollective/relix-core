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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NullValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThatRows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The claim {@link QueryResultAssert} makes is that a content mismatch prints the
 * <em>result</em> rather than one cell, so the negative cases assert on the message.
 * A row-at-a-time assertion cannot show "the rows came back in the wrong order" at
 * all; that is the case this exists for, and it is tested.
 */
@DisplayName("QueryResultAssert — result assertions that print the table")
final class QueryResultAssertTest extends ProcessorTestSupport {

    private static final Schema FRUIT = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("name", ScalarType.STRING),
            new ColumnDefinition("price", ScalarType.NUMBER)));

    private static final QueryResult RESULT = new QueryResult("Fruit", FRUIT, List.of(
            row(FRUIT, num("1"), str("Apple"), num("0.99")),
            row(FRUIT, num("2"), str("Banana"), NullValue.INSTANCE)));

    @Nested
    @DisplayName("columns and rows")
    class Content {

        @Test
        @DisplayName("one claim replaces the three per-cell assertions this was written for")
        void oneRowInOneClaim() {
            assertThat(RESULT)
                    .hasColumns("id", "name", "price")
                    .hasRowCount(2)
                    .hasRowAt(0, "1", "Apple", "0.99");
        }

        @Test
        @DisplayName("hasRow is the order-independent form, for a set-valued result")
        void unordered() {
            assertThat(RESULT).hasRow("2", "Banana", "NULL").hasNoRow("3", "Cherry", "1.50");
        }

        @Test
        @DisplayName("a wrong row prints the whole table — where the wrong order becomes visible")
        void wrongRowPrintsTheTable() {
            assertThatThrownBy(() -> assertThat(RESULT).hasRowAt(0, "2", "Banana", "NULL"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected row 0 to be")
                    .hasMessageContaining("Apple")
                    .hasMessageContaining("Banana");
        }

        @Test
        @DisplayName("a wrong column list reports both headings")
        void wrongColumns() {
            assertThatThrownBy(() -> assertThat(RESULT).hasColumns("id", "name"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("[id, name, price]");
        }

        @Test
        @DisplayName("an empty result says so rather than printing a bare header")
        void empty() {
            QueryResult none = new QueryResult("Fruit", FRUIT, List.of());
            assertThat(none).isEmpty().hasRowCount(0);
            assertThatThrownBy(() -> assertThat(none).hasRowCount(1))
                    .hasMessageContaining("<no rows>");
        }

        @Test
        @DisplayName("displayedRows hands the cells to AssertJ for the claims a list assert makes")
        void displayedRows() {
            assertThat(RESULT).displayedRows()
                    .containsExactlyInAnyOrder(List.of("2", "Banana", "NULL"),
                            List.of("1", "Apple", "0.99"));
        }
    }

    @Nested
    @DisplayName("one row")
    class SingleRow {

        @Test
        @DisplayName("row(i) keeps the whole result as the failure's context")
        void rowKeepsContext() {
            assertThat(RESULT).row(0).hasValue("name", "Apple").hasValues("1", "Apple", "0.99");
        }

        @Test
        @DisplayName("isNullAt makes the claim hasValue cannot — a NULL and \"NULL\" display alike")
        void nullVersusTheString() {
            assertThat(RESULT).row(1).isNullAt("price").isNotNullAt("name");
            assertThat(RESULT).row(1).hasValue("price", "NULL");   // the rendering agrees…
            Schema textPrice = new Schema(List.of(new ColumnDefinition("price", ScalarType.STRING)));
            assertThatThrownBy(() -> assertThat(row(textPrice, str("NULL"))).isNullAt("price"))
                    .isInstanceOf(AssertionError.class)     // …the type does not
                    .hasMessageContaining("expected column price to be NULL");
        }

        @Test
        @DisplayName("an unknown column names the columns the row does have")
        void unknownColumn() {
            assertThatThrownBy(() -> assertThat(RESULT).row(0).hasValue("discount", "0"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected a column discount")
                    .hasMessageContaining("[id, name, price]");
        }

        @Test
        @DisplayName("a relation-qualified reference resolves, as the row resolves it")
        void qualifiedName() {
            // Row.get resolves Users.name through column provenance, and that spelling is
            // not in columnNames() — so the column claim must go through the row rather
            // than pre-check a name list, which would reject a reference the row answers.
            Schema qualified = new Schema(List.of(
                    new ColumnDefinition("name", ScalarType.STRING)
                            .withProvenance(new com.darkcollective.relix.symbol.ColumnProvenance("Users", "name"))));
            assertThat(row(qualified, str("Alice"))).hasValue("Users.name", "Alice");
        }

        @Test
        @DisplayName("value() hands the typed Value to AssertJ")
        void typedValue() {
            assertThat(RESULT).row(1).value("price").isEqualTo(NullValue.INSTANCE);
        }
    }

    @Nested
    @DisplayName("a bare list of rows")
    class BareRows {

        @Test
        @DisplayName("assertThatRows takes the heading from the first row")
        void fromRows() {
            assertThatRows(RESULT.rows()).hasColumns("id", "name", "price").hasRowCount(2);
        }

        @Test
        @DisplayName("the schema is stated explicitly where the rows may be empty")
        void explicitSchema() {
            assertThatRows(FRUIT, List.of()).isEmpty().hasColumns("id", "name", "price");
        }
    }
}
