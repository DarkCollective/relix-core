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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.processor.EvaluationException;

import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.internal.CodePoints;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.util.Comparator;

/**
 * Total ordering over {@link Value} instances for use in sort and
 * aggregation operators.
 *
 * <p>Two singleton instances are provided:
 * <ul>
 *   <li>{@link #NULLS_LAST} — {@code NULL} sorts after all non-null
 *       values (standard SQL {@code ASC} behaviour).</li>
 *   <li>{@link #NULLS_FIRST} — {@code NULL} sorts before all non-null
 *       values (standard SQL {@code DESC} behaviour).</li>
 * </ul>
 *
 * <p>This class is the <em>single</em> authority on when two values are equal or
 * ordered. Equality ({@link #equal}), ordering ({@link #compareNonNull}) and the
 * hash-bucket token ({@link #joinToken}) all read one coercion rule, so a pair the
 * selection operator matches is a pair the join operator matches. They did once
 * disagree: each of the two evaluators grew the coercion its own callers needed —
 * strings against booleans here, strings against temporals there — and a predicate
 * written as a selection and the same predicate written as a join then gave
 * different answers with no diagnostic.
 *
 * <p>Non-null values of the same runtime type are ordered naturally:
 * {@link NumberValue} by {@link java.math.BigDecimal#compareTo},
 * {@link StringValue} by code point ({@link CodePoints#compare}), {@link BooleanValue} with
 * {@code false} before {@code true}, and the temporal values
 * ({@link DateValue}, {@link TimeValue}, {@link TimestampValue},
 * {@link DurationValue}) by their {@code java.time} natural order.  Comparing
 * values of different types throws {@link EvaluationException}.
 *
 * <p><strong>A string is taken to mean what it spells.</strong> Text naming a boolean
 * ({@code "true"}, {@code "TRUE"}) or spelling a temporal
 * ({@code "2024-01-15"}, {@code "2024-01-15T11:00:00+01:00"}) is that value here,
 * whatever it is being compared against — which is what lets a CSV or inline-table
 * cell, inferred as {@code STRING}, meet a typed literal. Two spellings of one value
 * are therefore equal, and are ordered by the value rather than as text: an offset
 * timestamp sorts by the instant it denotes, not by its leading digits.
 *
 * <p>The consequence to know about is that such text is no longer a {@code STRING}
 * for the purpose of ordering, so a column holding {@code "2024-01-15"} beside
 * {@code "pending"} holds a {@code DATE} beside a {@code STRING} and has no order.
 * Cast it to settle what the column holds.
 */
public final class ValueComparator implements Comparator<Value> {

    /** {@code NULL} values sort <em>after</em> all non-null values. */
    public static final ValueComparator NULLS_LAST  = new ValueComparator(true);

    /** {@code NULL} values sort <em>before</em> all non-null values. */
    public static final ValueComparator NULLS_FIRST = new ValueComparator(false);

    private final boolean nullsLast;

    private ValueComparator(boolean nullsLast) {
        this.nullsLast = nullsLast;
    }

    @Override
    public int compare(Value a, Value b) {
        boolean aN = a.isNull();
        boolean bN = b.isNull();
        if (aN && bN) return 0;
        if (aN) return nullsLast ?  1 : -1;
        if (bN) return nullsLast ? -1 :  1;
        return compareNonNull(a, b);
    }

    // ── coercion ────────────────────────────────────────────────────────────

    /**
     * Returns the value a string denotes — the temporal it spells or the boolean it
     * names — or {@code v} unchanged when it is not a string or denotes neither.
     *
     * <p>Inline-table, CSV and JSON cells all infer as {@code STRING}, so a column
     * holding timestamps or flags reaches the executor as {@link StringValue} while
     * the literal it is compared against is properly typed. Both coercions exist for
     * that one reason — {@code "2024-01-15T10:00:00"} against a {@code TIMESTAMP} and
     * {@code "true"} against a {@code BOOLEAN} — and both belong here rather than at a
     * call site, because a coercion only one caller knows about is a way for two
     * callers to disagree.
     *
     * <p><strong>It reads one value and no partner, and that is the whole design.</strong>
     * A rule that coerced a string only when the <em>other</em> operand was typed made
     * equality depend on the pair: a {@code TIMESTAMP} equalled both
     * {@code "2024-01-15T10:00:00Z"} and {@code "2024-01-15T11:00:00+01:00"} while those
     * two, being both strings, were compared as text and were unequal. That is precisely
     * the substitutability axiom {@link Comparator} requires, and breaking it does not
     * raise an error — it makes a sort's result depend on the order the rows arrived in,
     * and can place a later timestamp before an earlier one. No pairwise rule can avoid
     * this: equivalence classes have to be a property of a value alone.
     *
     * <p>The price is paid by a {@code STRING} column that mixes text denoting a value
     * with text denoting nothing. {@code "2024-01-15"} is a {@code DATE} here, so
     * ordering it against {@code "pending"} is ordering a {@code DATE} against a
     * {@code STRING}, which has no answer and says so. Cast the column to settle what it
     * holds.
     *
     * <p>It is public because the operators that <em>deduplicate</em> need it as much as
     * the ones that match. δ, γ and the set operations build a key and compare it with
     * {@code Value.equals}, which is record equality and knows nothing of this rule — so
     * σ called a string-spelled boolean equal to a {@code BOOLEAN} while δ kept the two
     * apart, and the duplicate it returned printed identically to the row beside it.
     * Keying on the canonical form is what closes that, and it works with plain list
     * equality because {@code Value.equals} is already right <em>within</em> a type
     * ({@code NumberValue} overriding it so that {@code 5} and {@code 5.0} are one
     * number); this rule is what settles it <em>across</em> types.
     *
     * <p>A key is not an output. The canonical form decides which rows group together and
     * must never replace the value the operator emits, or grouping a {@code STRING}
     * column of dates would start returning {@code DATE}s.
     */
    public static Value canonical(Value v) {
        if (!(v instanceof StringValue s)) return v;
        String t = s.value().trim();
        if (t.equalsIgnoreCase("true"))  return BooleanValue.TRUE;
        if (t.equalsIgnoreCase("false")) return BooleanValue.FALSE;
        return TemporalValueArithmetic.coerceToAnyTemporal(v);
    }

    // ── equality ────────────────────────────────────────────────────────────

    /**
     * Returns whether two values are equal, canonicalised per {@link #canonical}.
     *
     * <p>Unlike {@link #compareNonNull} this never throws: values of genuinely
     * different types are simply not equal. That is what an equality test wants —
     * a join looking for matching rows should skip a mismatched pair, not abort the
     * query — and it is why a match test must call this rather than compare against
     * zero, which conflates "different" with "unorderable".
     *
     * <p>NULL is equal to nothing, itself included.
     */
    public static boolean equal(Value a, Value b) {
        if (a.isNull() || b.isNull()) return false;
        final Value ca = canonical(a);
        final Value cb = canonical(b);
        return switch (ca) {
            case NumberValue  x when cb instanceof NumberValue  y -> x.value().compareTo(y.value()) == 0;
            case StringValue  x when cb instanceof StringValue  y -> x.value().equals(y.value());
            case BooleanValue x when cb instanceof BooleanValue y -> x.value() == y.value();
            case DateValue      x when cb instanceof DateValue      y -> x.value().equals(y.value());
            case TimeValue      x when cb instanceof TimeValue      y -> x.value().equals(y.value());
            case TimestampValue x when cb instanceof TimestampValue y -> x.value().equals(y.value());
            case DurationValue  x when cb instanceof DurationValue  y -> x.value().equals(y.value());
            // Nested values compare structurally (record equality).
            case StructValue x when cb instanceof StructValue y -> x.equals(y);
            case ArrayValue  x when cb instanceof ArrayValue  y -> x.equals(y);
            default -> false;
        };
    }

    // ── the hash-bucket token ───────────────────────────────────────────────

    /**
     * Returns the hash-bucket token for {@code v} — the form in which any two values
     * {@link #equal} accepts are guaranteed to produce the same string.
     *
     * <p>A hash join buckets rows by this token and then re-checks each candidate
     * pair, so the token is a pre-filter and carries a one-way obligation: it may
     * put more pairs in a bucket than actually match (the re-check discards them,
     * costing only time), but it must never separate a pair that would match —
     * those rows are then silently dropped from the result.
     *
     * <p>That is why this is not simply {@link Value#asDisplayString()}. The display
     * form already collapses {@code NUMBER} scale ({@code 5} and {@code 5.0} both
     * render {@code "5"}), but it keeps a string apart from the value
     * {@link #canonical} turns it into — {@code "2024-01-15T10:00:00"} against an
     * {@code Instant} rendering {@code "2024-01-15T10:00:00Z"}, or {@code "TRUE"}
     * against {@code true}. Reading the same canonical form closes that gap, and
     * reading it from the same method is what keeps the token and {@link #equal} from
     * drifting apart — they were two statements of one rule until they were not.
     */
    public static String joinToken(Value v) {
        return canonical(v).asDisplayString();
    }

    // ── ordering ────────────────────────────────────────────────────────────

    /**
     * Compares two non-null values, canonicalised per {@link #canonical}.
     *
     * @throws EvaluationException if the values have no shared order
     */
    public static int compareNonNull(Value a, Value b) {
        final Value ca = canonical(a);
        final Value cb = canonical(b);
        return switch (ca) {
            case NumberValue  na when cb instanceof NumberValue  nb ->
                    na.value().compareTo(nb.value());
            case StringValue  sa when cb instanceof StringValue  sb ->
                    // By code point, which is the unit the rest of the string library
                    // counts in. String.compareTo would compare UTF-16 code units and
                    // sort a supplementary character before U+E000..U+FFFF, because it
                    // is comparing a surrogate against a real character's code.
                    CodePoints.compare(sa.value(), sb.value());
            case BooleanValue ba when cb instanceof BooleanValue bb ->
                    Boolean.compare(ba.value(), bb.value());
            case DateValue      da when cb instanceof DateValue      db ->
                    da.value().compareTo(db.value());
            case TimeValue      ta when cb instanceof TimeValue      tb ->
                    ta.value().compareTo(tb.value());
            case TimestampValue sa when cb instanceof TimestampValue sb ->
                    sa.value().compareTo(sb.value());
            case DurationValue  da when cb instanceof DurationValue  db ->
                    da.value().compareTo(db.value());
            default -> throw new EvaluationException(
                    "Cannot compare " + ca.type() + " with " + cb.type());
        };
    }
}
