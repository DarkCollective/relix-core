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

import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Relation — a row read into a record")
final class RecordMappingTest {

    // -----------------------------------------------------------------------
    // A session whose columns carry real types, which is the point of the mapping
    // -----------------------------------------------------------------------

    private static final Schema ORDERS = new Schema(List.of(
            new ColumnDefinition("order_id", ScalarType.NUMBER),
            new ColumnDefinition("customer", ScalarType.STRING),
            new ColumnDefinition("amount", ScalarType.NUMBER),
            new ColumnDefinition("placed", ScalarType.TIMESTAMP),
            new ColumnDefinition("due", ScalarType.DATE),
            new ColumnDefinition("paid", ScalarType.BOOLEAN)));

    private static Row order(long id, String customer, String amount, boolean paid) {
        return ArrayRow.of(ORDERS,
                NumberValue.of(String.valueOf(id)),
                new StringValue(customer),
                NumberValue.of(amount),
                new TimestampValue(Instant.parse("2026-06-15T13:40:00Z")),
                new DateValue(LocalDate.of(2026, 7, 1)),
                com.darkcollective.relix.value.BooleanValue.of(paid));
    }

    private static Relix orders() {
        Relix relix = Relix.open();
        relix.source("Orders", ORDERS, () -> Stream.of(
                order(1, "Acme", "99.50", true),
                order(2, "Globex", "10", false)));
        return relix;
    }

    record Order(long order_id, String customer, BigDecimal amount) { }

    @Nested
    @DisplayName("the mapping itself")
    final class Mapping {

        @Test
        @DisplayName("each component reads the column of its own name")
        void mapsByName() {
            try (Relix relix = orders()) {
                List<Order> mapped = relix.relation("Orders").toList(Order.class);

                assertThat(mapped).containsExactly(
                        new Order(1, "Acme", new BigDecimal("99.50")),
                        new Order(2, "Globex", new BigDecimal("10")));
            }
        }

        @Test
        @DisplayName("a component may be any type Tuple can read")
        void readsEveryType() {
            record Wide(String customer, Instant placed, LocalDate due, boolean paid,
                        int order_id, double amount) { }
            try (Relix relix = orders()) {
                Wide first = relix.relation("Orders").toList(Wide.class).getFirst();

                assertThat(first.customer()).isEqualTo("Acme");
                assertThat(first.placed()).isEqualTo(Instant.parse("2026-06-15T13:40:00Z"));
                assertThat(first.due()).isEqualTo(LocalDate.of(2026, 7, 1));
                assertThat(first.paid()).isTrue();
                assertThat(first.order_id()).isEqualTo(1);
                assertThat(first.amount()).isEqualTo(99.50);
            }
        }

        @Test
        @DisplayName("a record need not name every column")
        void takesASubsetOfColumns() {
            record Just(String customer) { }
            try (Relix relix = orders()) {
                assertThat(relix.relation("Orders").toList(Just.class))
                        .extracting(Just::customer).containsExactly("Acme", "Globex");
            }
        }

        @Test
        @DisplayName("the match is case-insensitive, as every column lookup is")
        void matchesCaseInsensitively() {
            record Loud(String CUSTOMER) { }
            try (Relix relix = orders()) {
                assertThat(relix.relation("Orders").toList(Loud.class))
                        .extracting(Loud::CUSTOMER).containsExactly("Acme", "Globex");
            }
        }

        @Test
        @DisplayName("it maps whatever the expression produces, not just a base relation")
        void mapsADerivedHeading() {
            record Total(String customer, BigDecimal sum_amount) { }
            try (Relix relix = orders()) {
                List<Total> totals = relix
                        .relation("γ customer, SUM(amount) → sum_amount (Orders)")
                        .toList(Total.class);

                assertThat(totals).extracting(Total::customer)
                        .containsExactlyInAnyOrder("Acme", "Globex");
            }
        }

        @Test
        @DisplayName("stream(Class) is the lazy counterpart, and is still the caller's to close")
        void streams() {
            try (Relix relix = orders()) {
                try (Stream<Order> rows = relix.relation("Orders").stream(Order.class)) {
                    assertThat(rows.map(Order::customer).toList())
                            .containsExactly("Acme", "Globex");
                }
            }
        }
    }

    @Nested
    @DisplayName("what is refused before any row moves")
    final class CheckedUpFront {

        @Test
        @DisplayName("a component naming no column, listing the ones there are")
        void refusesAnUnknownColumn() {
            record Wrong(String custmer) { }
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> relix.relation("Orders").toList(Wrong.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("custmer")
                        .hasMessageContaining("customer");
            }
        }

        @Test
        @DisplayName("a column holding a type the component cannot hold")
        void refusesAMismatchedType() {
            record Wrong(BigDecimal customer) { }
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> relix.relation("Orders").toList(Wrong.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("customer")
                        .hasMessageContaining("cannot hold");
            }
        }

        @Test
        @DisplayName("a component of a type no column can be read into")
        void refusesAnUnsupportedComponentType() {
            record Wrong(StringBuilder customer) { }
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> relix.relation("Orders").toList(Wrong.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("StringBuilder");
            }
        }

        @Test
        @DisplayName("a type that is not a record at all")
        void refusesANonRecord() {
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> relix.relation("Orders").toList(String.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("not a record");
            }
        }

        @Test
        @DisplayName("a record with no components, which nothing could be read into")
        void refusesAnEmptyRecord() {
            record Nothing() { }
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> relix.relation("Orders").toList(Nothing.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("no components");
            }
        }

        @Test
        @DisplayName("the check happens without running the query")
        void doesNotRunTheQuery() {
            record Wrong(String nope) { }
            java.util.concurrent.atomic.AtomicInteger scans =
                    new java.util.concurrent.atomic.AtomicInteger();
            try (Relix relix = Relix.open()) {
                relix.source("Orders", ORDERS, () -> {
                    scans.incrementAndGet();
                    return Stream.empty();
                });

                assertThatThrownBy(() -> relix.relation("Orders").toList(Wrong.class))
                        .isInstanceOf(RelixException.class);
                assertThat(scans).hasValue(0);
            }
        }
    }

    @Nested
    @DisplayName("nulls, and the numbers a component cannot hold")
    final class Refusals {

        private static final Schema SPARSE = new Schema(List.of(
                new ColumnDefinition("id", ScalarType.NUMBER),
                new ColumnDefinition("label", ScalarType.STRING)));

        private static Relix sparse(Value id, Value label) {
            Relix relix = Relix.open();
            relix.source("Sparse", SPARSE, () -> Stream.of(ArrayRow.of(SPARSE, id, label)));
            return relix;
        }

        @Test
        @DisplayName("a NULL reaches a boxed component as Java null")
        void nullIntoABoxedComponent() {
            record Sparse(Long id, String label) { }
            try (Relix relix = sparse(NullValue.INSTANCE, NullValue.INSTANCE)) {
                assertThat(relix.relation("Sparse").toList(Sparse.class))
                        .containsExactly(new Sparse(null, null));
            }
        }

        @Test
        @DisplayName("a NULL into a primitive component is refused, naming it")
        void nullIntoAPrimitiveComponent() {
            record Sparse(long id) { }
            try (Relix relix = sparse(NullValue.INSTANCE, new StringValue("x"))) {
                assertThatThrownBy(() -> relix.relation("Sparse").toList(Sparse.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("id")
                        .hasMessageContaining("boxed");
            }
        }

        @Test
        @DisplayName("a fractional number into a long component is refused, as Tuple refuses it")
        void fractionalIntoALong() {
            record Sparse(long id) { }
            try (Relix relix = sparse(NumberValue.of("1.5"), new StringValue("x"))) {
                assertThatThrownBy(() -> relix.relation("Sparse").toList(Sparse.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("whole number");
            }
        }

        @Test
        @DisplayName("a number too large for an int is refused rather than wrapped")
        void tooLargeForAnInt() {
            record Sparse(int id) { }
            try (Relix relix = sparse(NumberValue.of("3000000000"), new StringValue("x"))) {
                assertThatThrownBy(() -> relix.relation("Sparse").toList(Sparse.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("int");
            }
        }

        @Test
        @DisplayName("a compact constructor of the caller's own is reported as its own failure")
        void aRejectingConstructor() {
            try (Relix relix = sparse(NumberValue.of("1"), new StringValue("x"))) {
                assertThatThrownBy(() -> relix.relation("Sparse").toList(Positive.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("only even ids");
            }
        }
    }

    /** A record that validates in its compact constructor, as a caller's own may. */
    record Positive(long id) {
        Positive {
            if (id % 2 != 0) {
                throw new IllegalArgumentException("only even ids");
            }
        }
    }

    @Nested
    @DisplayName("columns whose type the heading cannot settle")
    final class UndeclaredTypes {

        private static final Schema NESTED = new Schema(List.of(
                new ColumnDefinition("tags", array(ScalarType.STRING)),
                new ColumnDefinition("addr", struct(
                        new StructType.Field("city", ScalarType.STRING))),
                new ColumnDefinition("anything", ScalarType.ANY)));

        private static Relix nested(Value anything) {
            Relix relix = Relix.open();
            relix.source("Docs", NESTED, () -> Stream.of(ArrayRow.of(NESTED,
                    new ArrayValue(List.of(new StringValue("a"), new StringValue("b"))),
                    new StructValue(Map.of("city", new StringValue("Leeds"))),
                    anything)));
            return relix;
        }

        @Test
        @DisplayName("an array column reads into a List, a struct column into a Map")
        void nestedColumns() {
            record Doc(List<Value> tags, Map<String, Value> addr) { }
            try (Relix relix = nested(NullValue.INSTANCE)) {
                Doc doc = relix.relation("Docs").toList(Doc.class).getFirst();

                assertThat(doc.tags()).hasSize(2);
                assertThat(doc.addr().get("city").asDisplayString()).isEqualTo("Leeds");
            }
        }

        @Test
        @DisplayName("an ANY column passes the up-front check and is settled at the row")
        void anyIsCheckedAtTheRow() {
            record Doc(String anything) { }
            try (Relix relix = nested(new StringValue("text"))) {
                assertThat(relix.relation("Docs").toList(Doc.class))
                        .containsExactly(new Doc("text"));
            }
            try (Relix relix = nested(NumberValue.of("1"))) {
                assertThatThrownBy(() -> relix.relation("Docs").toList(Doc.class))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("anything");
            }
        }

        @Test
        @DisplayName("a Value component takes a column of any type, NULL included")
        void valueComponent() {
            record Doc(Value anything) { }
            try (Relix relix = nested(NullValue.INSTANCE)) {
                // A NULL arrives as NullValue rather than as Java null: the sealed
                // hierarchy models NULL itself, which is the reason to ask for a Value.
                assertThat(relix.relation("Docs").toList(Doc.class).getFirst().anything().isNull())
                        .isTrue();
            }
        }
    }
}
