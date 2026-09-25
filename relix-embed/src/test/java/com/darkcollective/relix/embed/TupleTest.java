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

import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Tuple — a result row read as Java types")
final class TupleTest {

    private static final Schema SCHEMA = new Schema(List.of(
            new ColumnDefinition("name", ScalarType.STRING),
            new ColumnDefinition("amount", ScalarType.NUMBER),
            new ColumnDefinition("whole", ScalarType.NUMBER),
            new ColumnDefinition("active", ScalarType.BOOLEAN),
            new ColumnDefinition("at", ScalarType.TIMESTAMP),
            new ColumnDefinition("day", ScalarType.DATE),
            new ColumnDefinition("clock", ScalarType.TIME),
            new ColumnDefinition("elapsed", ScalarType.DURATION),
            new ColumnDefinition("tags", array(ScalarType.STRING)),
            new ColumnDefinition("addr", struct(new StructType.Field("city", ScalarType.STRING))),
            new ColumnDefinition("missing", ScalarType.STRING)));

    private static final Instant AT = Instant.parse("2026-08-31T09:15:00Z");

    private static Tuple tuple() {
        return Tuple.of(ArrayRow.of(SCHEMA,
                new StringValue("Ada"),
                NumberValue.of("99.50"),
                NumberValue.of("42"),
                new BooleanValue(true),
                new TimestampValue(AT),
                new DateValue(LocalDate.of(2026, 8, 31)),
                new TimeValue(LocalTime.of(9, 15)),
                new DurationValue(Duration.ofMinutes(90)),
                new ArrayValue(List.of(new StringValue("a"), new StringValue("b"))),
                new StructValue(Map.of("city", new StringValue("Leeds"))),
                NullValue.INSTANCE));
    }

    @Nested
    @DisplayName("each accessor gives the type it names")
    final class Typed {

        @Test
        void everyKind() {
            Tuple row = tuple();

            assertThat(row.string("name")).isEqualTo("Ada");
            assertThat(row.decimal("amount")).isEqualByComparingTo("99.50");
            assertThat(row.longValue("whole")).isEqualTo(42L);
            assertThat(row.doubleValue("amount")).isEqualTo(99.5d);
            assertThat(row.booleanValue("active")).isTrue();
            assertThat(row.instant("at")).isEqualTo(AT);
            assertThat(row.date("day")).isEqualTo(LocalDate.of(2026, 8, 31));
            assertThat(row.time("clock")).isEqualTo(LocalTime.of(9, 15));
            assertThat(row.duration("elapsed")).isEqualTo(Duration.ofMinutes(90));
            assertThat(row.array("tags")).containsExactly(
                    new StringValue("a"), new StringValue("b"));
            assertThat(row.struct("addr")).containsEntry("city", new StringValue("Leeds"));
        }

        @Test
        @DisplayName("a lookup is case-insensitive, as everywhere else in the language")
        void caseInsensitive() {
            assertThat(tuple().string("NAME")).isEqualTo("Ada");
        }

        @Test
        @DisplayName("decimal is the exact form; doubleValue is the lossy one")
        void decimalIsExact() {
            Tuple row = tuple();
            assertThat(row.decimal("amount")).isEqualTo(new BigDecimal("99.50"));
            assertThat(row.doubleValue("amount")).isEqualTo(99.5);
        }
    }

    @Nested
    @DisplayName("a NULL comes back as Java null, from every accessor")
    final class Nulls {

        @Test
        void nullIsNull() {
            Tuple row = tuple();

            assertThat(row.isNull("missing")).isTrue();
            assertThat(row.isNull("name")).isFalse();
            assertThat(row.string("missing")).isNull();
        }

        /**
         * Every accessor, not only the ones whose type would have made it awkward: a rule
         * with an exception in it is a rule a caller has to remember the shape of.
         */
        @Test
        void everyAccessorAnswersNull() {
            Schema nulls = new Schema(SCHEMA.columns().stream()
                    .map(c -> new ColumnDefinition(c.name(), c.type()))
                    .toList());
            Tuple row = Tuple.of(ArrayRow.of(nulls,
                    nulls.columns().stream().map(c -> (Value) NullValue.INSTANCE).toList()));

            assertThat(row.string("name")).isNull();
            assertThat(row.decimal("amount")).isNull();
            assertThat(row.longValue("whole")).isNull();
            assertThat(row.doubleValue("amount")).isNull();
            assertThat(row.booleanValue("active")).isNull();
            assertThat(row.instant("at")).isNull();
            assertThat(row.date("day")).isNull();
            assertThat(row.time("clock")).isNull();
            assertThat(row.duration("elapsed")).isNull();
            assertThat(row.array("tags")).isNull();
            assertThat(row.struct("addr")).isNull();
        }
    }

    @Nested
    @DisplayName("a value of the wrong type is refused, not coerced")
    final class WrongType {

        @Test
        @DisplayName("the message names what the column holds and what was asked for")
        void namesBothTypes() {
            assertThatThrownBy(() -> tuple().longValue("name"))
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("'name'")
                    .hasMessageContaining("string")
                    .hasMessageContaining("number");
        }

        @Test
        @DisplayName("display formatting is asked for by that name, not by string(...)")
        void stringDoesNotFormat() {
            assertThatThrownBy(() -> tuple().string("amount"))
                    .isInstanceOf(RelixException.class);
            // asDisplayString normalises — which is exactly why decimal(...) is the
            // accessor to reach for when the exact figure is the point.
            assertThat(tuple().get("amount").asDisplayString()).isEqualTo("99.5");
        }

        /**
         * The one case where refusing costs something and is still right: truncating
         * would give a wrong answer rather than an approximate one, and the message
         * names the accessor that does not lose anything.
         */
        @Test
        @DisplayName("longValue refuses a fractional number rather than truncating it")
        void longRefusesFractions() {
            assertThatThrownBy(() -> tuple().longValue("amount"))
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("decimal(");
        }

        @Test
        @DisplayName("a column that is not there is an error, as it is on any row")
        void unknownColumn() {
            assertThatThrownBy(() -> tuple().string("nope"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("it is still the row it wraps")
    final class StillARow {

        @Test
        void delegatesTheRowContract() {
            Tuple row = tuple();

            assertThat(row.schema()).isEqualTo(SCHEMA);
            assertThat(row.width()).isEqualTo(SCHEMA.columns().size());
            assertThat(row.get(0)).isEqualTo(new StringValue("Ada"));
            assertThat(row.columnNames()).startsWith("name", "amount");
        }

        @Test
        @DisplayName("wrapping a tuple returns it, so a row is never wrapped twice")
        void wrappingIsIdempotent() {
            Tuple once = tuple();
            assertThat(Tuple.of(once)).isSameAs(once);
        }

        @Test
        @DisplayName("equality and toString read through to the row")
        void equalityAndDisplay() {
            assertThat(tuple()).isEqualTo(tuple());
            assertThat(tuple()).hasSameHashCodeAs(tuple());
            assertThat(tuple().toString()).contains("name=Ada").startsWith("(").endsWith(")");
        }
    }
}
