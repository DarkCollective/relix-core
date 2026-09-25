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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NullValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The two-sided row view a join condition is evaluated against.
 *
 * <p>It answers "which side owns this reference" at <em>execution</em> time, for one
 * condition evaluation, and a wrong answer reads a value from the wrong input — the row
 * still has the right shape, so nothing downstream notices. That makes the ambiguous
 * cases the ones worth writing down, and an equi-join makes them the common cases: both
 * sides usually carry the joined column's name, so the qualifier is all that separates
 * them.
 *
 * <p>The schema-on-read path is the other half. An open schema indexes nothing, so a
 * column such a side really carries is found by asking the <em>row</em> — without that,
 * both sides looked empty and every qualified reference over a JSON/HTTP/Mongo relation
 * threw after the query had already started (#657).
 */
@DisplayName("QualifiedRow — which side owns a reference")
final class QualifiedRowTest extends ProcessorTestSupport {

    private static final Schema LEFT = schema(
            col("id", ScalarType.NUMBER), col("amount", ScalarType.NUMBER));
    private static final Schema RIGHT = schema(
            col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));

    private static final Schema CONCAT = schema(
            col("id", ScalarType.NUMBER), col("amount", ScalarType.NUMBER),
            col("id_r", ScalarType.NUMBER), col("name", ScalarType.STRING));

    private static QualifiedRow joined() {
        return new QualifiedRow(
                row(LEFT, num(1), num(100)),
                row(RIGHT, num(2), str("Ann")),
                LEFT, RIGHT, Set.of("orders"), Set.of("customers"), CONCAT);
    }

    @Nested
    @DisplayName("closed schemas on both sides")
    class Closed {

        @Test
        @DisplayName("a name only one side has needs no qualifier")
        void exclusiveNames() {
            assertThat(joined().get("amount")).isEqualTo(num(100));
            assertThat(joined().get("name")).isEqualTo(str("Ann"));
        }

        @Test
        @DisplayName("a shared name is decided by the qualifier — both directions")
        void sharedNameUsesTheQualifier() {
            assertThat(joined().get("Orders.id")).as("left").isEqualTo(num(1));
            assertThat(joined().get("Customers.id")).as("right").isEqualTo(num(2));
        }

        @Test
        @DisplayName("the qualifier is matched case-insensitively")
        void qualifierIsCaseInsensitive() {
            assertThat(joined().get("CUSTOMERS.id")).isEqualTo(num(2));
        }

        @Test
        @DisplayName("a shared name with no qualifier falls to the left, as documented")
        void sharedNameWithoutAQualifierPrefersTheLeft() {
            assertThat(joined().get("id")).isEqualTo(num(1));
        }

        // ── a dotted name that is a path, not a qualifier ────────────────────

        /** Left carries a nested `location`; right carries a plain `team` and `city`. */
        private static final Schema NESTED_LEFT = schema(
                col("name", ScalarType.STRING),
                new com.darkcollective.relix.symbol.ColumnDefinition("location",
                        new com.darkcollective.relix.symbol.StructType(List.of(
                                new com.darkcollective.relix.symbol.StructType.Field(
                                        "city", ScalarType.STRING),
                                new com.darkcollective.relix.symbol.StructType.Field(
                                        "team", ScalarType.STRING)))));
        private static final Schema PLAIN_RIGHT = schema(
                col("team", ScalarType.STRING), col("city", ScalarType.STRING));

        private static QualifiedRow nestedJoin() {
            java.util.Map<String, com.darkcollective.relix.value.Value> location =
                    new java.util.LinkedHashMap<>();
            location.put("city", str("Cambridge"));
            location.put("team", str("core"));
            return new QualifiedRow(
                    row(NESTED_LEFT, str("Grace"),
                            new com.darkcollective.relix.value.StructValue(location)),
                    row(PLAIN_RIGHT, str("infra"), str("Oslo")),
                    NESTED_LEFT, PLAIN_RIGHT, Set.of("profiles"), Set.of("teams"),
                    NESTED_LEFT.concat(PLAIN_RIGHT));
        }

        @Test
        @DisplayName("a path is routed by its head, not by the side that has its tail")
        void aPathIsRoutedByItsHead() {
            // `location.team` names no relation in scope, so it is a path into the left's
            // struct — not the right's `team` column. Reading it by its tail made
            // `location.team = Teams.team` compare the right's value with itself, so the
            // join matched every pair and quietly became a cross product.
            assertThat(nestedJoin().get("location.team")).isEqualTo(str("core"));
            assertThat(nestedJoin().get("location.city")).isEqualTo(str("Cambridge"));
        }

        @Test
        @DisplayName("the right side's own columns are unaffected")
        void theOtherSideStillAnswersItsOwnNames() {
            assertThat(nestedJoin().get("team")).isEqualTo(str("infra"));
            assertThat(nestedJoin().get("Teams.city")).isEqualTo(str("Oslo"));
        }

        @Test
        @DisplayName("a qualifier that really names a relation still wins over a struct column")
        void aRelationQualifierStillWins() {
            // The precedence that must not change: `profiles` is a relation under the
            // left input, so `profiles.city` is a qualified reference and not a path —
            // even though a `location` struct with a `city` field sits right beside it.
            assertThat(nestedJoin().get("Profiles.name")).isEqualTo(str("Grace"));
        }

        @Test
        @DisplayName("a schema-on-read side does not take the closed side's path")
        void openSideDoesNotClaimAPath() {
            // The same routing, with the other input schema-on-read. An open schema
            // resolves every name, so its answer to a path is worth nothing — but
            // declining the whole reading is not the same as declining its answer: the
            // fallback reads the bare tail, which an open row answers whenever it happens
            // to carry a field of that name, and `location.team` bound to the right's own
            // `team` after all. That is the cross product this routing exists to prevent,
            // arriving by the other door.
            java.util.Map<String, com.darkcollective.relix.value.Value> location =
                    new java.util.LinkedHashMap<>();
            location.put("team", str("core"));
            java.util.Map<String, com.darkcollective.relix.value.Value> document =
                    new java.util.LinkedHashMap<>();
            document.put("team", str("infra"));

            QualifiedRow mixed = new QualifiedRow(
                    row(NESTED_LEFT, str("Grace"),
                            new com.darkcollective.relix.value.StructValue(location)),
                    new com.darkcollective.relix.processor.internal.DocumentRow(
                            new com.darkcollective.relix.value.StructValue(document)),
                    NESTED_LEFT, Schema.open(), Set.of("profiles"), Set.of("teams"),
                    NESTED_LEFT);

            assertThat(mixed.get("location.team"))
                    .as("the closed side owns the path; the open side is not asked")
                    .isEqualTo(str("core"));
            assertThat(mixed.get("team"))
                    .as("the bare name is still the open side's own column")
                    .isEqualTo(str("infra"));

            QualifiedRow mirrored = new QualifiedRow(
                    new com.darkcollective.relix.processor.internal.DocumentRow(
                            new com.darkcollective.relix.value.StructValue(document)),
                    row(NESTED_LEFT, str("Grace"),
                            new com.darkcollective.relix.value.StructValue(location)),
                    Schema.open(), NESTED_LEFT, Set.of("teams"), Set.of("profiles"),
                    NESTED_LEFT);

            assertThat(mirrored.get("location.team"))
                    .as("and with the sides the other way round")
                    .isEqualTo(str("core"));
        }

        @Test
        @DisplayName("a qualifier neither side owns is no help — the left still wins")
        void unknownQualifierPrefersTheLeft() {
            assertThat(joined().get("Shipments.id")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("a qualifier BOTH sides own is no help either — a self-join stays left")
        void qualifierOnBothSidesPrefersTheLeft() {
            var selfJoin = new QualifiedRow(
                    row(LEFT, num(1), num(100)),
                    row(LEFT, num(2), num(200)),
                    LEFT, LEFT, Set.of("orders"), Set.of("orders"), CONCAT);
            assertThat(selfJoin.get("Orders.id")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("a column neither side has is an error when both schemas are closed")
        void unknownColumnThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> joined().get("missing"))
                    .withMessageContaining("No column 'missing'");
        }
    }

    @Nested
    @DisplayName("schema-on-read (an open side)")
    class Open {

        private static QualifiedRow withOpenLeft() {
            Row leftRow = row(schema("doc_id", "tag"), num(7), str("x"));
            return new QualifiedRow(leftRow, row(RIGHT, num(2), str("Ann")),
                    Schema.open(), RIGHT, Set.of("docs"), Set.of("customers"), CONCAT);
        }

        private static QualifiedRow withOpenRight() {
            Row rightRow = row(schema("doc_id", "tag"), num(7), str("x"));
            return new QualifiedRow(row(LEFT, num(1), num(100)), rightRow,
                    LEFT, Schema.open(), Set.of("orders"), Set.of("docs"), CONCAT);
        }

        @Test
        @DisplayName("a field an open LEFT row carries is found by asking the row")
        void openLeftCarriesTheField() {
            assertThat(withOpenLeft().get("doc_id")).isEqualTo(num(7));
        }

        @Test
        @DisplayName("a field an open RIGHT row carries is found the same way")
        void openRightCarriesTheField() {
            assertThat(withOpenRight().get("tag")).isEqualTo(str("x"));
        }

        @Test
        @DisplayName("a field an open row does not carry is absent, not an error")
        void absentFieldOnAnOpenSideIsNull() {
            // The same answer `σ missing = 1 (Docs)` gives: only a closed schema can
            // promise the column exists, and there the validator has already checked.
            assertThat(withOpenLeft().get("nothing_here")).isEqualTo(NullValue.INSTANCE);
            assertThat(withOpenRight().get("nothing_here")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a name both an open row and a closed side carry is still qualifier-decided")
        void ambiguityAcrossAnOpenSide() {
            Row leftRow = row(schema("id", "tag"), num(7), str("x"));
            var mixed = new QualifiedRow(leftRow, row(RIGHT, num(2), str("Ann")),
                    Schema.open(), RIGHT, Set.of("docs"), Set.of("customers"), CONCAT);
            assertThat(mixed.get("Customers.id")).as("qualifier picks the closed side")
                    .isEqualTo(num(2));
            assertThat(mixed.get("Docs.id")).as("qualifier picks the open side")
                    .isEqualTo(num(7));
            assertThat(mixed.get("id")).as("unqualified still falls left").isEqualTo(num(7));
        }
    }

    @Nested
    @DisplayName("positional access")
    class Positional {

        @Test
        @DisplayName("an index below the left width reads the left row, above it the right")
        void indexSplitsAtTheLeftWidth() {
            QualifiedRow r = joined();
            assertThat(r.get(0)).isEqualTo(num(1));
            assertThat(r.get(1)).isEqualTo(num(100));
            assertThat(r.get(2)).as("first right column").isEqualTo(num(2));
            assertThat(r.get(3)).isEqualTo(str("Ann"));
        }

        @Test
        @DisplayName("width and schema describe the concatenation, not either side")
        void widthAndSchema() {
            assertThat(joined().width()).isEqualTo(4);
            assertThat(joined().schema()).isEqualTo(CONCAT);
        }

        @Test
        @DisplayName("column names come from the concatenated schema")
        void columnNames() {
            assertThat(joined().columnNames())
                    .containsExactlyElementsOf(List.of("id", "amount", "id_r", "name"));
        }
    }
}
