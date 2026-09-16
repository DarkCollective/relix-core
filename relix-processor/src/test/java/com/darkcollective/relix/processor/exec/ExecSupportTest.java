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

import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.DocumentRow;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.processor.exec.ExecSupport.*;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

/**
 * Unit tests for {@link ExecSupport} — the shared row/key/index/concatenation
 * helpers used by join and set-operation executors.
 */
@DisplayName("ExecSupport — shared join/key/index helpers")
final class ExecSupportTest extends ProcessorTestSupport {

    private static final Schema AB = schema("a", "b");
    private static final Schema CD = schema("c", "d");
    private static final Schema ABCD = schema("a", "b", "c", "d");

    private static Row ab(Value a, Value b) {
        return ArrayRow.of(AB, a, b);
    }

    private static Row cd(Value c, Value d) {
        return ArrayRow.of(CD, c, d);
    }

    // =========================================================================
    // keyTokens
    // =========================================================================

    @Nested
    @DisplayName("relabel — restating a row under the heading an operator declares")
    class Relabel {

        /**
         * The case the whole helper exists for. Set-operation compatibility is decided
         * positionally, so ∪, ∩ and − may be handed branches whose headings differ —
         * and row equality is not positional, so without this step the two tuples below
         * are different rows and every set operation over them is wrong.
         */
        @Test
        @DisplayName("a differing heading is restated, values in order")
        void restatesADifferingHeading() {
            Row relabelled = relabel(cd(num(1), num(2)), AB);

            assertThat(relabelled.schema()).isEqualTo(AB);
            assertThat(relabelled).hasValue("a", "1").hasValue("b", "2");
            assertThat(relabelled).isEqualTo(ab(num(1), num(2)));
        }

        @Test
        @DisplayName("a row already under that heading is returned as it is")
        void leavesAMatchingHeadingAlone() {
            Row row = ab(num(1), num(2));
            assertThat(relabel(row, AB)).isSameAs(row);
        }

        /**
         * A schema-on-read row is addressed by field name and has no positional heading
         * to restate; building an {@code ArrayRow} against one would lose its fields,
         * since an open schema has width 0 (#649).
         */
        @Test
        @DisplayName("a document row is returned as it is")
        void leavesADocumentRowAlone() {
            Map<String, Value> fields = new LinkedHashMap<>();
            fields.put("a", num(1));
            fields.put("b", num(2));
            Row document = new DocumentRow(new StructValue(fields));

            assertThat(relabel(document, AB)).isSameAs(document);
        }

        @Test
        @DisplayName("an open target heading restates nothing")
        void leavesAnOpenTargetAlone() {
            Row row = ab(num(1), num(2));
            assertThat(relabel(row, Schema.open())).isSameAs(row);
        }

        @Test
        @DisplayName("relabelAll preserves order")
        void relabelAllPreservesOrder() {
            List<Row> out = relabelAll(List.of(cd(num(1), num(2)), cd(num(3), num(4))), AB);

            assertThat(out).hasSize(2);
            assertThat(out.get(0)).hasValue("a", "1");
            assertThat(out.get(1)).hasValue("a", "3");
        }
    }

    /**
     * The identity-key helpers, which nothing had tested directly — they were reached only
     * through the operators above them, and a rule reached incidentally is a rule whose
     * edges nobody has stated. Each test here is one of those edges: an untyped position
     * canonicalises, a typed one does not, and "untyped" has three distinct spellings
     * ({@code ANY}, an open heading, and an absent entry) that must agree.
     */
    @Nested
    @DisplayName("identity keys — which positions have a type settled enough to trust")
    final class IdentityKeys {

        private static final Schema TYPED =
                schema(col("a", ScalarType.NUMBER), col("b", ScalarType.STRING));

        @Test
        @DisplayName("a declared type is trusted, so 1 and 1.0 stay two keys")
        void declaredTypeIsTrusted() {
            assertThat(identityKey(ArrayRow.of(TYPED, num(1), str("x")), TYPED))
                    .isEqualTo(identityKey(ArrayRow.of(TYPED, num(1), str("x")), TYPED));
            assertThat(identityKey(ArrayRow.of(TYPED, num("1.0"), str("x")), TYPED).getFirst())
                    .as("NUMBER has already settled identity, so the spelling is kept")
                    .isEqualTo(num("1.0"));
        }

        @Test
        @DisplayName("an ANY column is canonicalised, so 1 and 1.0 are one key")
        void anyColumnIsCanonicalised() {
            Schema any = schema("a", "b");
            assertThat(identityKey(ArrayRow.of(any, num("1.0"), str("x")), any))
                    .isEqualTo(identityKey(ArrayRow.of(any, num(1), str("x")), any));
        }

        @Test
        @DisplayName("an open heading settles nothing, so every position is canonicalised")
        void openHeadingSettlesNothing() {
            Schema any = schema("a", "b");
            assertThat(identityKey(ArrayRow.of(any, num("1.0"), str("x")), Schema.open()))
                    .isEqualTo(identityKey(ArrayRow.of(any, num(1), str("x")), Schema.open()));
        }

        @Test
        @DisplayName("a row wider than the heading canonicalises the positions past its end")
        void positionsPastTheHeadingAreUnsettled() {
            Schema narrow = schema(col("a", ScalarType.NUMBER));
            Schema wide = schema("a", "b");
            assertThat(identityKey(ArrayRow.of(wide, num(1), num("2.0")), narrow))
                    .as("column b has no declared type to trust")
                    .isEqualTo(identityKey(ArrayRow.of(wide, num(1), num(2)), narrow));
        }

        @Test
        @DisplayName("canonicalKey counts a missing type entry as unsettled")
        void canonicalKeyTreatsAMissingTypeAsUnsettled() {
            List<Type> onlyFirst = List.of(ScalarType.NUMBER);
            assertThat(canonicalKey(List.of(num(1), num("2.0")), onlyFirst))
                    .as("the second position has no entry, so it canonicalises")
                    .isEqualTo(canonicalKey(List.of(num(1), num(2)), onlyFirst));
        }

        @Test
        @DisplayName("keyTypes reads the declared types of the leading columns")
        void keyTypesReadsLeadingColumns() {
            assertThat(keyTypes(TYPED, 1)).isEqualTo(List.of(ScalarType.NUMBER));
            assertThat(keyTypes(TYPED, 2))
                    .isEqualTo(List.of(ScalarType.NUMBER, ScalarType.STRING));
        }

        @Test
        @DisplayName("keyTypes stops at the heading's end rather than past it")
        void keyTypesStopsAtTheHeadingEnd() {
            assertThat(keyTypes(TYPED, 5))
                    .as("asked for more key columns than the heading has")
                    .isEqualTo(List.of(ScalarType.NUMBER, ScalarType.STRING));
        }

        @Test
        @DisplayName("keyTypes answers nothing for an open heading, which declares nothing")
        void keyTypesDeclinesAnOpenHeading() {
            assertThat(keyTypes(Schema.open(), 2)).isEmpty();
        }

        @Test
        @DisplayName("namedTypes leaves a null where the heading does not have the column")
        void namedTypesLeavesANullForAnAbsentColumn() {
            assertThat(namedTypes(TYPED, List.of("a", "nope")))
                    .containsExactly(ScalarType.NUMBER, null);
        }
    }

    @Nested
    @DisplayName("keyTokens")
    class KeyTokens {

        @Test
        @DisplayName("returns string representation of each value at the given indices")
        void returnsTokensForNonNullValues() {
            Row row = ab(num(1), str("hello"));
            List<String> key = keyTokens(row, new int[]{0, 1});
            assertThat(key).containsExactly("1", "hello");
        }

        @Test
        @DisplayName("returns null when any indexed value is NULL")
        void returnsNullWhenAnyValueIsNull() {
            Row row = ab(num(1), nullVal());
            assertThat(keyTokens(row, new int[]{0, 1})).isNull();
        }

        @Test
        @DisplayName("returns null when the first value is NULL")
        void returnsNullWhenFirstIsNull() {
            Row row = ab(nullVal(), str("x"));
            assertThat(keyTokens(row, new int[]{0, 1})).isNull();
        }

        @Test
        @DisplayName("returns empty list for empty index array")
        void returnsEmptyListForNoIndices() {
            Row row = ab(num(1), num(2));
            assertThat(keyTokens(row, new int[]{})).isEmpty();
        }

        @Test
        @DisplayName("single-column key works")
        void singleColumnKey() {
            Row row = ab(str("abc"), num(99));
            assertThat(keyTokens(row, new int[]{0})).containsExactly("abc");
        }

        @Test
        @DisplayName("normalises numbers — 1.0 and 1 produce the same key token")
        void normalisesNumbers() {
            Row r1 = ab(num("1.0"), num(2));
            Row r2 = ab(num("1"), num(2));
            assertThat(keyTokens(r1, new int[]{0})).isEqualTo(keyTokens(r2, new int[]{0}));
        }
    }

    // =========================================================================
    // buildIndex / probe
    // =========================================================================

    @Nested
    @DisplayName("buildIndex and probe")
    class IndexAndProbe {

        @Test
        @DisplayName("probe returns matching bucket for a key that was indexed")
        void probeHit() {
            Row r1 = ab(num(1), str("a"));
            Row r2 = ab(num(2), str("b"));
            Map<List<String>, List<Row>> idx = buildIndex(List.of(r1, r2), new int[]{0});

            assertThat(probe(idx, ab(num(1), str("x")), new int[]{0})).containsExactly(r1);
            assertThat(probe(idx, ab(num(2), str("x")), new int[]{0})).containsExactly(r2);
        }

        @Test
        @DisplayName("probe returns empty list for a key not in the index")
        void probeMiss() {
            Row r1 = ab(num(1), str("a"));
            Map<List<String>, List<Row>> idx = buildIndex(List.of(r1), new int[]{0});
            assertThat(probe(idx, ab(num(99), str("x")), new int[]{0})).isEmpty();
        }

        @Test
        @DisplayName("rows with NULL join keys are excluded from the index")
        void nullKeyRowsExcluded() {
            Row withNull = ab(nullVal(), str("a"));
            Row noNull   = ab(num(1),   str("b"));
            Map<List<String>, List<Row>> idx = buildIndex(List.of(withNull, noNull), new int[]{0});
            assertThat(idx).hasSize(1);
            assertThat(probe(idx, withNull, new int[]{0})).isEmpty();
        }

        @Test
        @DisplayName("probe returns empty for a NULL-key probe row")
        void nullProbeReturnsEmpty() {
            Row r1 = ab(num(1), str("a"));
            Map<List<String>, List<Row>> idx = buildIndex(List.of(r1), new int[]{0});
            assertThat(probe(idx, ab(nullVal(), str("x")), new int[]{0})).isEmpty();
        }

        @Test
        @DisplayName("multiple rows with the same key are all returned by probe")
        void multipleRowsSameKey() {
            Row r1 = ab(num(1), str("a"));
            Row r2 = ab(num(1), str("b"));
            Map<List<String>, List<Row>> idx = buildIndex(List.of(r1, r2), new int[]{0});
            assertThat(probe(idx, ab(num(1), str("x")), new int[]{0})).containsExactly(r1, r2);
        }
    }

    // =========================================================================
    // filterNullKeys
    // =========================================================================

    @Nested
    @DisplayName("filterNullKeys")
    class FilterNullKeys {

        @Test
        @DisplayName("passes through rows with no NULL keys")
        void allNonNull() {
            Row r1 = ab(num(1), str("a"));
            Row r2 = ab(num(2), str("b"));
            assertThat(filterNullKeys(List.of(r1, r2), new int[]{0})).containsExactly(r1, r2);
        }

        @Test
        @DisplayName("removes rows where the key column is NULL")
        void someNull() {
            Row good = ab(num(1), str("a"));
            Row bad  = ab(nullVal(), str("b"));
            assertThat(filterNullKeys(List.of(good, bad), new int[]{0})).containsExactly(good);
        }

        @Test
        @DisplayName("returns empty list when all rows have NULL keys")
        void allNull() {
            Row r1 = ab(nullVal(), str("a"));
            Row r2 = ab(nullVal(), str("b"));
            assertThat(filterNullKeys(List.of(r1, r2), new int[]{0})).isEmpty();
        }

        @Test
        @DisplayName("empty input produces empty output")
        void emptyInput() {
            assertThat(filterNullKeys(List.of(), new int[]{0})).isEmpty();
        }
    }

    // =========================================================================
    // naturalMatches
    // =========================================================================

    @Nested
    @DisplayName("naturalMatches")
    class NaturalMatches {

        @Test
        @DisplayName("returns true when all key columns match")
        void matchingPair() {
            Row left  = ab(num(1), str("x"));
            Row right = cd(num(1), str("y"));
            assertThat(naturalMatches(left, right, new int[]{0}, new int[]{0})).isTrue();
        }

        @Test
        @DisplayName("returns false when key columns differ")
        void nonMatchingPair() {
            Row left  = ab(num(1), str("x"));
            Row right = cd(num(2), str("y"));
            assertThat(naturalMatches(left, right, new int[]{0}, new int[]{0})).isFalse();
        }

        @Test
        @DisplayName("returns false when either key value is NULL")
        void nullKeyReturnsFalse() {
            Row left     = ab(nullVal(), str("x"));
            Row right    = cd(nullVal(), str("y"));
            Row leftNum  = ab(num(1),    str("x"));
            assertThat(naturalMatches(left,    right, new int[]{0}, new int[]{0})).isFalse();
            assertThat(naturalMatches(leftNum, right, new int[]{0}, new int[]{0})).isFalse();
        }

        @Test
        @DisplayName("returns false for empty key array (no common columns)")
        void emptyKeyArrayReturnsFalse() {
            Row left  = ab(num(1), str("x"));
            Row right = cd(num(1), str("y"));
            // empty keys: naturalMatches guards leftKeys.length > 0
            assertThat(naturalMatches(left, right, new int[]{}, new int[]{})).isFalse();
        }

        @Test
        @DisplayName("checks all key columns — partial match is not a match")
        void partialMatchIsFalse() {
            Row left  = ab(num(1), str("x"));
            Row right = cd(num(1), str("y"));  // c matches, d differs
            assertThat(naturalMatches(left, right, new int[]{0, 1}, new int[]{0, 1})).isFalse();
        }
    }

    // =========================================================================
    // compareJoinKeys
    // =========================================================================

    @Nested
    @DisplayName("compareJoinKeys")
    class CompareJoinKeys {

        @Test
        @DisplayName("returns 0 for equal keys")
        void equalKeys() {
            Row left  = ab(num(5), str("a"));
            Row right = cd(num(5), str("a"));
            assertThat(compareJoinKeys(left, new int[]{0}, right, new int[]{0})).isZero();
        }

        @Test
        @DisplayName("returns negative when left key is less than right")
        void leftLessThanRight() {
            Row left  = ab(num(1), str("x"));
            Row right = cd(num(9), str("y"));
            assertThat(compareJoinKeys(left, new int[]{0}, right, new int[]{0})).isNegative();
        }

        @Test
        @DisplayName("returns positive when left key is greater than right")
        void leftGreaterThanRight() {
            Row left  = ab(num(9), str("x"));
            Row right = cd(num(1), str("y"));
            assertThat(compareJoinKeys(left, new int[]{0}, right, new int[]{0})).isPositive();
        }

        @Test
        @DisplayName("NULL sorts last (both NULL: equal; one NULL: non-null wins)")
        void nullSortsLast() {
            Row nullRow  = ab(nullVal(), str("x"));
            Row nonNull  = cd(num(1),   str("y"));
            Row nullRow2 = ab(nullVal(), str("x"));

            // null vs non-null: null sorts last → positive
            assertThat(compareJoinKeys(nullRow, new int[]{0}, nonNull,  new int[]{0})).isPositive();
            // non-null vs null: non-null wins → negative
            assertThat(compareJoinKeys(nonNull, new int[]{0}, nullRow,  new int[]{0})).isNegative();
            // null vs null: equal
            assertThat(compareJoinKeys(nullRow, new int[]{0}, nullRow2, new int[]{0})).isZero();
        }

        @Test
        @DisplayName("empty key array returns 0")
        void emptyKeyArrayEquals() {
            Row left  = ab(num(1), str("x"));
            Row right = cd(num(9), str("y"));
            assertThat(compareJoinKeys(left, new int[]{}, right, new int[]{})).isZero();
        }
    }

    // =========================================================================
    // advanceBatch
    // =========================================================================

    @Nested
    @DisplayName("advanceBatch")
    class AdvanceBatch {

        @Test
        @DisplayName("single-element run: end is start+1")
        void singleElement() {
            List<Row> rows = List.of(
                    ab(num(1), str("a")),
                    ab(num(2), str("b")));
            assertThat(advanceBatch(rows, 0, new int[]{0})).isEqualTo(1);
        }

        @Test
        @DisplayName("multi-element run: end points past all equal rows")
        void multiElementRun() {
            List<Row> rows = List.of(
                    ab(num(1), str("a")),
                    ab(num(1), str("b")),
                    ab(num(1), str("c")),
                    ab(num(2), str("d")));
            assertThat(advanceBatch(rows, 0, new int[]{0})).isEqualTo(3);
        }

        @Test
        @DisplayName("run extends to end of list when all remaining rows are equal")
        void runAtEndOfList() {
            List<Row> rows = List.of(
                    ab(num(1), str("a")),
                    ab(num(5), str("b")),
                    ab(num(5), str("c")));
            assertThat(advanceBatch(rows, 1, new int[]{0})).isEqualTo(3);
        }

        @Test
        @DisplayName("run starting at last element gives end = list size")
        void runAtLastElement() {
            List<Row> rows = List.of(
                    ab(num(1), str("a")),
                    ab(num(2), str("b")));
            assertThat(advanceBatch(rows, 1, new int[]{0})).isEqualTo(2);
        }
    }

    // =========================================================================
    // rowComparator
    // =========================================================================

    @Nested
    @DisplayName("rowComparator")
    class RowComparatorTests {

        @Test
        @DisplayName("sorts rows by a single column ASC")
        void singleColumnAsc() {
            List<Row> rows = new ArrayList<>(List.of(
                    ab(num(3), str("c")),
                    ab(num(1), str("a")),
                    ab(num(2), str("b"))));
            rows.sort(rowComparator(List.of(asc("a")), new OperandEvaluator()));
            assertThat(rows.stream().map(r -> r.get("a").asDisplayString()).toList())
                    .containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("sorts rows by a single column DESC")
        void singleColumnDesc() {
            List<Row> rows = new ArrayList<>(List.of(
                    ab(num(1), str("a")),
                    ab(num(3), str("c")),
                    ab(num(2), str("b"))));
            rows.sort(rowComparator(List.of(desc("a")), new OperandEvaluator()));
            assertThat(rows.stream().map(r -> r.get("a").asDisplayString()).toList())
                    .containsExactly("3", "2", "1");
        }

        @Test
        @DisplayName("ASC sort places NULL rows after non-null rows (NULLS_LAST default)")
        void ascNullsLast() {
            List<Row> rows = new ArrayList<>(List.of(
                    ab(nullVal(), str("b")),
                    ab(num(1), str("a")),
                    ab(num(2), str("c"))));
            rows.sort(rowComparator(List.of(asc("a")), new OperandEvaluator()));
            assertThat(rows.get(rows.size() - 1).get("a")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("DESC sort places NULL rows after non-null rows (effective NULLS_LAST after reversal)")
        void descNullsLast() {
            List<Row> rows = new ArrayList<>(List.of(
                    ab(num(1), str("a")),
                    ab(nullVal(), str("b")),
                    ab(num(2), str("c"))));
            rows.sort(rowComparator(List.of(desc("a")), new OperandEvaluator()));
            assertThat(rows.get(rows.size() - 1).get("a")).isEqualTo(NullValue.INSTANCE);
        }
    }

    // =========================================================================
    // concatRows / nullPaddedLeft / nullPaddedRight
    // =========================================================================

    @Nested
    @DisplayName("row builders")
    class RowBuilders {

        @Test
        @DisplayName("concatRows produces a row with left values followed by right values")
        void concatRows() {
            Row left  = ab(num(1), str("x"));
            Row right = cd(num(2), str("y"));
            Row out   = ExecSupport.concatRows(left, right, ABCD);
            assertThat(out).hasValue("a", "1")
                    .hasValue("b", "x")
                    .hasValue("c", "2")
                    .hasValue("d", "y");
        }

        @Test
        @DisplayName("nullPaddedRight pads right side with NULLs")
        void nullPaddedRight() {
            Row left  = ab(num(1), str("x"));
            Row out   = ExecSupport.nullPaddedRight(left, 2, ABCD);
            assertThat(out).hasValue("a", "1");
            assertThat(out.get("c")).isEqualTo(NullValue.INSTANCE);
            assertThat(out.get("d")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("nullPaddedLeft pads left side with NULLs")
        void nullPaddedLeft() {
            Row right = cd(num(2), str("y"));
            Row out   = ExecSupport.nullPaddedLeft(2, right, ABCD);
            assertThat(out.get("a")).isEqualTo(NullValue.INSTANCE);
            assertThat(out.get("b")).isEqualTo(NullValue.INSTANCE);
            assertThat(out).hasValue("c", "2");
        }
    }

    /**
     * A join whose output heading is open carries values by name, so it has to carry
     * each field's origin too — otherwise a qualified reference above the join reads
     * NULL (#970).
     */
    @Nested
    @DisplayName("row builders over an open output — field origins (#970)")
    class OpenRowBuilders {

        /** {@code orders(product_id, quantity)}, each column knowing it came from {@code orders}. */
        private static final Schema ORDERS = new Schema(List.of(
                new ColumnDefinition("product_id", ScalarType.NUMBER, new ColumnProvenance("orders", "product_id")),
                new ColumnDefinition("quantity", ScalarType.NUMBER, new ColumnProvenance("orders", "quantity"))));

        private static Row order(int id, int quantity) {
            return ArrayRow.of(ORDERS, num(id), num(quantity));
        }

        private static DocumentRow product(int id, String name) {
            Map<String, Value> fields = new LinkedHashMap<>();
            fields.put("product_id", num(id));
            fields.put("name", str(name));
            return new DocumentRow(new StructValue(fields));
        }

        private static List<ColumnProvenance> origin(String relation, String column) {
            return List.of(new ColumnProvenance(relation, column));
        }

        @Test
        @DisplayName("a declared left and an open right each keep their own side")
        void declaredLeftOpenRight() {
            Row out = concatRows(order(1, 2), product(7, "Novel"), Schema.open(),
                    Set.of("orders"), Set.of("products"));

            assertThat(out.get("orders.product_id")).isEqualTo(num(1));
            assertThat(out.get("products.product_id")).isEqualTo(num(7));
            assertThat(out.get("products.name")).isEqualTo(str("Novel"));
            assertThat(((DocumentRow) out).origins())
                    .containsEntry("product_id", origin("orders", "product_id"))
                    .containsEntry("product_id_r", origin("products", "product_id"));
        }

        @Test
        @DisplayName("an open left and a declared right: the rename lands on the declared side")
        void openLeftDeclaredRight() {
            Row out = concatRows(product(7, "Novel"), order(1, 2), Schema.open(),
                    Set.of("products"), Set.of("orders"));

            assertThat(out.get("products.product_id")).isEqualTo(num(7));
            assertThat(out.get("orders.product_id")).isEqualTo(num(1));
            assertThat(out.get("orders.quantity")).isEqualTo(num(2));
        }

        @Test
        @DisplayName("a declared column's provenance is kept over the join's relation set")
        void declaredProvenanceWins() {
            // The relation set names what the planner saw; the column already knows better.
            Row out = concatRows(order(1, 2), product(7, "Novel"), Schema.open(),
                    Set.of("something_else"), Set.of("products"));
            assertThat(out.get("orders.product_id")).isEqualTo(num(1));
            assertThat(out.get("something_else.product_id")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a declared column with no provenance answers to its side's relations")
        void declaredColumnWithoutProvenance() {
            Row out = concatRows(ab(num(1), num(2)), product(7, "Novel"), Schema.open(),
                    Set.of("t"), Set.of("products"));
            assertThat(out.get("t.a")).isEqualTo(num(1));
            assertThat(out.get("t.b")).isEqualTo(num(2));
        }

        @Test
        @DisplayName("a document an earlier join built keeps its origins through the next join")
        void nestedJoinKeepsOrigins() {
            Row inner = concatRows(order(1, 2), product(7, "Novel"), Schema.open(),
                    Set.of("orders"), Set.of("products"));
            Map<String, Value> fields = new LinkedHashMap<>();
            fields.put("product_id", num(9));
            Row outer = concatRows(inner, new DocumentRow(new StructValue(fields)), Schema.open(),
                    Set.of("orders", "products"), Set.of("returns"));

            assertThat(outer.get("orders.product_id")).isEqualTo(num(1));
            assertThat(outer.get("products.product_id")).isEqualTo(num(7));
            assertThat(outer.get("returns.product_id")).isEqualTo(num(9));
        }

        @Test
        @DisplayName("without relation sets only declared provenance is recorded")
        void threeArgumentFormRecordsDeclaredOnly() {
            Row out = concatRows(order(1, 2), product(7, "Novel"), Schema.open());
            assertThat(out.get("orders.product_id")).isEqualTo(num(1));
            assertThat(((DocumentRow) out).origins().get("product_id_r")).isEmpty();
        }

        @Test
        @DisplayName("an unmatched row keeps its origins on either side of an outer join")
        void paddedRowsKeepOrigins() {
            Row right = nullPaddedRight(order(1, 2), 0, Schema.open(), Set.of("orders"));
            assertThat(right.get("orders.quantity")).isEqualTo(num(2));
            assertThat(right.get("products.name")).isEqualTo(NullValue.INSTANCE);

            Row left = nullPaddedLeft(0, product(7, "Novel"), Schema.open(), Set.of("products"));
            assertThat(left.get("products.name")).isEqualTo(str("Novel"));

            // …and the forms without relation sets still produce a document
            assertThat(nullPaddedRight(product(7, "Novel"), 0, Schema.open())).isInstanceOf(DocumentRow.class);
            assertThat(nullPaddedLeft(0, order(1, 2), Schema.open()).get("orders.product_id"))
                    .isEqualTo(num(1));
        }
    }

    // =========================================================================
    // rightOnlyIndices
    // =========================================================================

    @Nested
    @DisplayName("rightOnlyIndices")
    class RightOnlyIndices {

        @Test
        @DisplayName("returns indices not in the key set")
        void returnsNonKeyIndices() {
            // rightWidth=3, rightKeys=[0] → right-only are [1, 2]
            assertThat(rightOnlyIndices(3, new int[]{0})).containsExactly(1, 2);
        }

        @Test
        @DisplayName("returns empty list when all columns are key columns")
        void allColumnsAreKeys() {
            assertThat(rightOnlyIndices(2, new int[]{0, 1})).isEmpty();
        }

        @Test
        @DisplayName("returns all indices when no columns are keys")
        void noColumnsAreKeys() {
            assertThat(rightOnlyIndices(3, new int[]{})).containsExactly(0, 1, 2);
        }
    }

    // =========================================================================
    // buildNaturalJoinRow
    // =========================================================================

    @Nested
    @DisplayName("buildNaturalJoinRow")
    class BuildNaturalJoinRow {

        // Left=(a,b), Right=(a,c) — common key is "a" at right index 0; right-only = [1] (c)
        private static final Schema LEFT_AC  = schema("a", "b");
        private static final Schema RIGHT_AC = schema("a", "c");
        private static final Schema OUT_ABC  = schema("a", "b", "c");

        @Test
        @DisplayName("appends right-only columns after all left columns")
        void appendsRightOnlyColumns() {
            Row left  = ArrayRow.of(LEFT_AC,  num(1), num(2));  // a=1, b=2
            Row right = ArrayRow.of(RIGHT_AC, num(1), num(3));  // a=1, c=3
            // right-only index = [1] (the "c" column)
            Row out = buildNaturalJoinRow(left, right, List.of(1), OUT_ABC);
            assertThat(out).hasValue("a", "1")
                    .hasValue("b", "2")
                    .hasValue("c", "3");
        }

        @Test
        @DisplayName("when there are no right-only columns the output equals the left row")
        void noRightOnlyColumnsYieldsLeftRow() {
            // Natural join where right has no extra columns beyond the key
            Schema KEY  = schema("k");
            Schema OUT2 = schema("k");
            Row left  = ArrayRow.of(KEY, num(7));
            Row right = ArrayRow.of(KEY, num(7));
            Row out = buildNaturalJoinRow(left, right, List.of(), OUT2);
            assertThat(out).hasValue("k", "7");
            assertThat(out.width()).isEqualTo(1);
        }

        @Test
        @DisplayName("picks up multiple right-only columns in order")
        void multipleRightOnlyColumns() {
            // Left=(k), Right=(k,x,y) — right-only = [1,2] (x,y)
            Schema L = schema("k");
            Schema R = schema("k", "x", "y");
            Schema O = schema("k", "x", "y");
            Row left  = ArrayRow.of(L, num(5));
            Row right = ArrayRow.of(R, num(5), str("hello"), num(9));
            Row out = buildNaturalJoinRow(left, right, List.of(1, 2), O);
            assertThat(out).hasValue("k", "5")
                    .hasValue("x", "hello")
                    .hasValue("y", "9");
        }
    }

    // =========================================================================
    // addUnmatched
    // =========================================================================

    @Nested
    @DisplayName("addUnmatched")
    class AddUnmatched {

        @Test
        @DisplayName("emits rows not in the matched set")
        void emitsUnmatchedRows() {
            Row r1 = ab(num(1), str("a"));
            Row r2 = ab(num(2), str("b"));
            Row r3 = ab(num(3), str("c"));
            Set<Row> matched = Set.of(r1);
            List<Row> sink = new ArrayList<>();
            addUnmatched(List.of(r1, r2, r3), matched, sink::add);
            assertThat(sink).containsExactly(r2, r3);
        }

        @Test
        @DisplayName("emits nothing when every row was matched")
        void allMatchedProducesNoOutput() {
            Row r1 = ab(num(1), str("a"));
            Row r2 = ab(num(2), str("b"));
            Set<Row> matched = Set.of(r1, r2);
            List<Row> sink = new ArrayList<>();
            addUnmatched(List.of(r1, r2), matched, sink::add);
            assertThat(sink).isEmpty();
        }

        @Test
        @DisplayName("emits all rows when none were matched")
        void noneMatchedEmitsAll() {
            Row r1 = ab(num(1), str("a"));
            Row r2 = ab(num(2), str("b"));
            Set<Row> matched = Set.of();
            List<Row> sink = new ArrayList<>();
            addUnmatched(List.of(r1, r2), matched, sink::add);
            assertThat(sink).containsExactly(r1, r2);
        }

        @Test
        @DisplayName("empty input produces empty output regardless of matched set")
        void emptyInputProducesEmptyOutput() {
            List<Row> sink = new ArrayList<>();
            addUnmatched(List.of(), Set.of(), sink::add);
            assertThat(sink).isEmpty();
        }
    }

    @Nested
    @DisplayName("appendColumn — adding a column to either shape of row")
    class AppendColumn {

        @Test
        @DisplayName("a closed row gains the value in its last position")
        void closedRow() {
            Schema input  = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
            Schema output = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING),
                    col("visit", ScalarType.NUMBER));
            Row row = ExecSupport.appendColumn(output, input,
                    row(input, num(1), str("Ada")), "visit", num(7));
            assertThat(row.width()).isEqualTo(3);
            assertThat(row).hasValue("visit", "7")
                    .hasValue("name", "Ada");
        }

        @Test
        @DisplayName("an open row gains a field — it has no position to append to")
        void openRow() {
            // The bug behind #649: an open schema has width 0, so building a tuple
            // from it threw "Value count 1 does not match schema width 0" and every
            // column-appending operator was unusable over a JSON/HTTP/Mongo source.
            DocumentRow document = new DocumentRow(new StructValue(new LinkedHashMap<>(Map.of(
                    "user_id", num(1)))));
            Row row = ExecSupport.appendColumn(Schema.open(), Schema.open(),
                    document, "visit", num(7));
            assertThat(row).hasValue("visit", "7")
                    .hasValue("user_id", "1");
        }

        @Test
        @DisplayName("an open row's existing field of that name is replaced, not duplicated")
        void openRowCollision() {
            DocumentRow document = new DocumentRow(new StructValue(new LinkedHashMap<>(Map.of(
                    "visit", str("from the document")))));
            Row row = ExecSupport.appendColumn(Schema.open(), Schema.open(),
                    document, "visit", num(7));
            assertThat(row).hasValue("visit", "7");
            assertThat(row.columnNames()).containsExactly("visit");
        }
    }
}
