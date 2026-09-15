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

import com.darkcollective.relix.ast.NullPlacement;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.processor.eval.ValueComparator;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import com.darkcollective.relix.processor.DocumentRow;
import com.darkcollective.relix.value.StructValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Stateless row/key/index/concatenation helpers shared by the join, merge-join,
 * and set-operation executors.  All methods are pure and side-effect free.
 */
final class ExecSupport {

    private ExecSupport() {}

    /**
     * Builds a row comparator from sort specs, honouring direction and NULL
     * placement.  Each key's expression is evaluated per row via {@code eval}, so a
     * bare column sorts by {@code row.get(name)} and a derived key
     * (e.g. {@code to_timestamp(logged)}) by its computed value.
     */
    static Comparator<Row> rowComparator(List<SortSpecification> specs, OperandEvaluator eval) {
        Comparator<Row> comparator = null;
        for (SortSpecification spec : specs) {
            Comparator<Value> valCmp = spec.nullPlacement() == NullPlacement.NULLS_FIRST
                    ? ValueComparator.NULLS_FIRST : ValueComparator.NULLS_LAST;
            if (spec.direction() == SortDirection.DESC) valCmp = valCmp.reversed();
            Operand expr = spec.expression();
            Comparator<Row> step = Comparator.comparing(row -> eval.evaluate(expr, row), valCmp);
            comparator = (comparator == null) ? step : comparator.thenComparing(step);
        }
        return comparator;
    }

    /**
     * One output row of an operator that appends a named column — the session id, the
     * window value, the path.
     *
     * <p>A schema-on-read row carries its own fields rather than a position per
     * column, so the value is <em>set on the document</em> rather than pushed onto a
     * fixed-width tuple; building an {@link ArrayRow} against an open schema fails,
     * because an open schema has width 0. That is what made these operators
     * unusable over any JSON, HTTP or MongoDB source (#649).
     *
     * <p>Where the document already carries a field of that name, the appended column
     * wins. The operator's output is defined to carry its column, and an incoming
     * field cannot be allowed to decide whether it does — {@link DocumentRow#with}
     * has always worked this way, which is what {@code μ … WITH ORDINALITY} was
     * already relying on.
     */
    static Row appendColumn(Schema outputSchema, Schema inputSchema, Row row,
                            String column, Value value) {
        if (row instanceof DocumentRow document) {
            return document.with(column, value);
        }
        List<Value> values = new ArrayList<>(inputSchema.width() + 1);
        for (int i = 0; i < inputSchema.width(); i++) {
            values.add(row.get(i));
        }
        values.add(value);
        return ArrayRow.of(outputSchema, values);
    }

    /**
     * Re-labels {@code row} with {@code target}, positionally: same values, in the same
     * order, under the heading the operator declares. Returns {@code row} unchanged when
     * it already carries that heading.
     *
     * <p>Set-operation compatibility is decided <em>positionally</em> —
     * {@code RelAlgebraValidator.checkSetOpCompatibility} compares width and column types
     * and never compares names — so ∪, ∩ and − may legally be given two branches whose
     * headings differ. Row equality is not positional: {@code ArrayRow.equals} includes
     * the row's {@link Schema}, and a row carries the heading of the node that produced
     * it. Without this step the two readings disagree, and they disagree silently: ∪
     * returns the same tuple twice, ∩ returns nothing, − subtracts nothing.
     *
     * <p>It also settles what the operator <em>emits</em>. A set operation declares one
     * output heading, and unrelabelled rows arrive at the consumer still carrying their
     * branch's — so {@code L ∪ R} would yield a row whose heading says {@code b} from an
     * operator whose schema says {@code a}.
     *
     * <p>A {@link DocumentRow} is returned unchanged: a schema-on-read row is addressed by
     * field name rather than by position, so there is no positional heading to restate.
     *
     * @param row    the row to re-label; must not be null
     * @param target the heading to restate it under; must not be null
     * @return {@code row} under {@code target}; never null
     */
    static Row relabel(Row row, Schema target) {
        if (row instanceof DocumentRow || target.isOpen() || row.schema().equals(target)) {
            return row;
        }
        List<Value> values = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) {
            values.add(row.get(i));
        }
        return ArrayRow.of(target, values);
    }

    /** {@link #relabel} over a whole list, preserving order. */
    static List<Row> relabelAll(List<Row> rows, Schema target) {
        List<Row> out = new ArrayList<>(rows.size());
        for (Row row : rows) {
            out.add(relabel(row, target));
        }
        return out;
    }

    /**
     * The identity key of a whole row — what δ, ∪, ∩ and − compare rows by.
     *
     * <p>A value is taken as it is where its column's declared type has already settled
     * what identity means, and in {@link ValueComparator#canonical} form where it has
     * not. A column is unsettled when it is {@code ANY}, or when the heading is open and
     * declares nothing — which is exactly the case the rule is for: schema-on-read data,
     * where one column can hold a {@code BOOLEAN} and the string that spells it, and
     * where σ already calls the two equal.
     *
     * <p><b>Narrower than "canonicalise everything", and deliberately.</b> A declared
     * {@code STRING} column holds strings, and two strings that denote one instant —
     * {@code "…T10:00:00Z"} and {@code "…T11:00:00+01:00"} — are still two strings. A δ
     * over them must return both: collapsing them loses a value the user asked for, and
     * it would disagree with the {@code SELECT DISTINCT} this operator folds into, which
     * compares the text. The type is what decides which question is being asked.
     *
     * <p>Plain {@code List<Value>} equality carries the rest, because {@code Value.equals}
     * is already correct <em>within</em> a type — {@code NumberValue} overrides it so that
     * {@code 5} and {@code 5.0} are one number. Canonicalising is only ever about settling
     * identity <em>across</em> types, which is a question a typed column does not pose.
     *
     * <p>NULL is its own key and equal to itself, which is deliberately not what {@code =}
     * does: {@link ValueComparator#equal} answers false for NULL against anything. Grouping
     * asks a different question — DISTINCT keeps one NULL, GROUP BY puts every NULL in one
     * group — and {@code NullValue} being a singleton gives exactly that.
     *
     * @param row    the row to key; must not be null
     * @param schema the heading deciding which columns have settled types; must not be null
     * @return the key in column order; never null
     */
    static List<Value> identityKey(Row row, Schema schema) {
        List<Value> key = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) {
            key.add(settled(schema, i) ? row.get(i) : ValueComparator.canonical(row.get(i)));
        }
        return key;
    }

    /**
     * {@link #identityKey}'s rule over an already-extracted key tuple, with {@code types}
     * giving the declared type of each position (a missing entry counts as unsettled).
     */
    static List<Value> canonicalKey(List<Value> values, List<Type> types) {
        List<Value> key = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Type type = i < types.size() ? types.get(i) : null;
            key.add(settlesIdentity(type) ? values.get(i) : ValueComparator.canonical(values.get(i)));
        }
        return key;
    }

    /** The declared types of {@code schema}'s first {@code count} columns. */
    static List<Type> keyTypes(Schema schema, int count) {
        if (schema.isOpen()) {
            return List.of();
        }
        List<Type> types = new ArrayList<>(count);
        for (int i = 0; i < count && i < schema.width(); i++) {
            types.add(schema.columns().get(i).type());
        }
        return types;
    }

    /** The declared types of {@code names}, in order, as {@code schema} gives them. */
    static List<Type> namedTypes(Schema schema, List<String> names) {
        List<Type> types = new ArrayList<>(names.size());
        for (String name : names) {
            types.add(schema.column(name).map(ColumnDefinition::type).orElse(null));
        }
        return types;
    }

    private static boolean settled(Schema schema, int index) {
        return !schema.isOpen() && index < schema.width()
                && settlesIdentity(schema.columns().get(index).type());
    }

    /** Whether a declared type has already decided what makes two of its values one value. */
    private static boolean settlesIdentity(Type type) {
        return type != null && type != ScalarType.ANY;
    }

    /**
     * A stateful predicate keeping the first row of each {@link #identityKey} it sees —
     * {@code Stream.distinct()} under the engine's equality rather than {@code Row}'s.
     */
    static java.util.function.Predicate<Row> distinctByIdentity(Schema schema) {
        Set<List<Value>> seen = new HashSet<>();
        return row -> seen.add(identityKey(row, schema));
    }

    static int[] keys(List<Integer> indices) {
        int[] arr = new int[indices.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = indices.get(i);
        return arr;
    }

    /**
     * Canonical, compareTo-consistent hash key for {@code row}; null if any key
     * value is NULL.
     *
     * <p>The tokens come from {@link ValueComparator#joinToken}, which is what makes
     * "compareTo-consistent" true rather than merely intended: the bucket a row
     * lands in has to agree with the {@link ValueComparator#compareNonNull} re-check
     * that follows, or matching rows are dropped with no error.
     */
    static List<String> keyTokens(Row row, int[] indices) {
        List<String> key = new ArrayList<>(indices.length);
        for (int idx : indices) {
            Value v = row.get(idx);
            if (v.isNull()) return null;
            key.add(ValueComparator.joinToken(v));
        }
        return key;
    }

    /** Removes rows whose join keys contain a NULL value, matching hash-join NULL semantics. */
    static List<Row> filterNullKeys(List<Row> rows, int[] keyIndices) {
        List<Row> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (keyTokens(row, keyIndices) != null) result.add(row);
        }
        return result;
    }

    /**
     * Lexicographic comparison of join keys across two rows using
     * {@link ValueComparator#compareNonNull}.  NULL values sort last (NULLS_LAST),
     * consistent with the ASC merge ordering the planner inserts via Sort enforcers.
     */
    static int compareJoinKeys(Row left, int[] leftKeys, Row right, int[] rightKeys) {
        for (int i = 0; i < leftKeys.length; i++) {
            Value lv = left.get(leftKeys[i]);
            Value rv = right.get(rightKeys[i]);
            if (lv.isNull() && rv.isNull()) continue;
            if (lv.isNull()) return 1;   // null sorts last
            if (rv.isNull()) return -1;
            int cmp = ValueComparator.compareNonNull(lv, rv);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    /**
     * Returns the index past the last row in {@code rows} whose join keys equal
     * those of {@code rows.get(start)}, used to batch equal-key groups in the
     * two-pointer merge.
     */
    static int advanceBatch(List<Row> rows, int start, int[] keyIndices) {
        int end = start + 1;
        while (end < rows.size() &&
               compareJoinKeys(rows.get(end), keyIndices, rows.get(start), keyIndices) == 0) {
            end++;
        }
        return end;
    }

    static Map<List<String>, List<Row>> buildIndex(List<Row> rows, int[] keyIndices) {
        Map<List<String>, List<Row>> index = new HashMap<>();
        for (Row row : rows) {
            List<String> key = keyTokens(row, keyIndices);
            if (key != null) {
                index.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        return index;
    }

    static List<Row> probe(Map<List<String>, List<Row>> index, Row row, int[] keyIndices) {
        List<String> key = keyTokens(row, keyIndices);
        if (key == null) return List.of();
        List<Row> bucket = index.get(key);
        return bucket == null ? List.of() : bucket;
    }

    static void addUnmatched(List<Row> rows, Set<Row> matched, Consumer<Row> sink) {
        for (Row row : rows) {
            if (!matched.contains(row)) sink.accept(row);
        }
    }

    /** Returns every value of {@code row}, in column order. */
    static List<Value> allValues(Row row) {
        List<Value> values = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) values.add(row.get(i));
        return values;
    }

    /** Returns the values of {@code row} at the given column indices, in order. */
    static List<Value> projectValues(Row row, List<Integer> indices) {
        List<Value> values = new ArrayList<>(indices.size());
        for (int idx : indices) values.add(row.get(idx));
        return values;
    }

    static Row concatRows(Row left, Row right, Schema outputSchema) {
        if (outputSchema.isOpen()) {
            return concatDocuments(left, right);
        }
        List<Value> values = new ArrayList<>(outputSchema.width());
        for (int i = 0; i < left.width();  i++) values.add(left.get(i));
        for (int i = 0; i < right.width(); i++) values.add(right.get(i));
        return ArrayRow.of(outputSchema, values);
    }

    /**
     * Joins two rows when the output heading is schema-on-read: the fields of one
     * document followed by the fields of the other.
     *
     * <p>A join over an open input has no fixed-width tuple to concatenate into —
     * a document names its fields per row, and the heading names at most the columns
     * of the declared side — so the values have to be carried by name.
     * A name the left already holds is suffixed {@code _r} on the right, which is
     * what a declared join heading does with a collision, so the same query reads
     * the same either way.
     */
    private static Row concatDocuments(Row left, Row right) {
        Map<String, Value> fields = new LinkedHashMap<>();
        for (String name : left.columnNames()) {
            fields.put(name, left.get(name));
        }
        for (String name : right.columnNames()) {
            String target = name;
            int suffix = 0;
            while (containsIgnoreCase(fields, target)) {
                target = name + "_r" + (suffix == 0 ? "" : String.valueOf(suffix));
                suffix++;
            }
            fields.put(target, right.get(name));
        }
        return new DocumentRow(new StructValue(fields));
    }

    private static boolean containsIgnoreCase(Map<String, Value> fields, String name) {
        for (String key : fields.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    static Row nullPaddedRight(Row left, int rightWidth, Schema outputSchema) {
        if (outputSchema.isOpen()) {
            // There is nothing to pad *with*: an unmatched row keeps its own fields,
            // and a field a document does not carry already reads as NULL. Padding a
            // declared heading exists to keep every row the same width, which a
            // schema-on-read relation does not promise in the first place.
            return documentOf(left);
        }
        List<Value> values = new ArrayList<>(outputSchema.width());
        for (int i = 0; i < left.width(); i++) values.add(left.get(i));
        for (int i = 0; i < rightWidth;   i++) values.add(NullValue.INSTANCE);
        return ArrayRow.of(outputSchema, values);
    }

    /** The row as a document — itself when it already is one, else its named values. */
    private static Row documentOf(Row row) {
        if (row instanceof DocumentRow document) {
            return document;
        }
        Map<String, Value> fields = new LinkedHashMap<>();
        for (String name : row.columnNames()) {
            fields.put(name, row.get(name));
        }
        return new DocumentRow(new StructValue(fields));
    }

    static Row nullPaddedLeft(int leftWidth, Row right, Schema outputSchema) {
        if (outputSchema.isOpen()) {
            return documentOf(right);
        }
        List<Value> values = new ArrayList<>(outputSchema.width());
        for (int i = 0; i < leftWidth;     i++) values.add(NullValue.INSTANCE);
        for (int i = 0; i < right.width(); i++) values.add(right.get(i));
        return ArrayRow.of(outputSchema, values);
    }

    static Row buildNaturalJoinRow(Row leftRow, Row rightRow,
                                   List<Integer> rightOnlyIndices, Schema outputSchema) {
        List<Value> values = new ArrayList<>(outputSchema.width());
        for (int i = 0; i < leftRow.width(); i++) values.add(leftRow.get(i));
        for (int idx : rightOnlyIndices)          values.add(rightRow.get(idx));
        return ArrayRow.of(outputSchema, values);
    }

    static List<Integer> rightOnlyIndices(int rightWidth, int[] rightKeys) {
        Set<Integer> common = new HashSet<>();
        for (int k : rightKeys) common.add(k);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < rightWidth; i++) {
            if (!common.contains(i)) indices.add(i);
        }
        return indices;
    }

    /** Common-column match for a natural join, compared positionally by key index. */
    static boolean naturalMatches(Row leftRow, Row rightRow, int[] leftKeys, int[] rightKeys) {
        for (int i = 0; i < leftKeys.length; i++) {
            Value lv = leftRow.get(leftKeys[i]);
            Value rv = rightRow.get(rightKeys[i]);
            if (!ValueComparator.equal(lv, rv)) return false;
        }
        return leftKeys.length > 0;
    }
}
