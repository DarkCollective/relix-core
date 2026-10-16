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
package com.darkcollective.relix.ast;

/**
 * The scope of rows fed to a {@link WindowFunction} within a partition.
 *
 * <p>A frame is the window operator's analogue of SQL's
 * {@code ROWS BETWEEN … AND …} clause.  The sealed hierarchy has three permits,
 * each corresponding to one surface form (or the implicit full-partition frame
 * used by ranking and offset functions):
 *
 * <ul>
 *   <li>{@link BoundedFrame} — {@code OVER n ROWS}: the current row plus the
 *       preceding {@code n − 1} rows (a trailing {@code n}-row sliding window).</li>
 *   <li>{@link CumulativeFrame} — {@code OVER ALL ROWS}: the full prefix of the
 *       partition up to and including the current row (running total).</li>
 *   <li>{@link Between} — {@code OVER ROWS BETWEEN … AND …} or
 *       {@code OVER RANGE BETWEEN … AND …}: SQL's frame clause, each end a bound
 *       counted in rows or measured along the sort key.</li>
 *   <li>{@link PartitionFrame} — the whole partition: {@code OVER PARTITION} for an
 *       aggregate, which then needs no sort, and set implicitly for ranking and
 *       offset functions.</li>
 * </ul>
 */
public sealed interface WindowFrame
        permits WindowFrame.BoundedFrame, WindowFrame.CumulativeFrame, WindowFrame.Between,
                WindowFrame.PartitionFrame {

    /**
     * A trailing {@code n}-row sliding window — {@code OVER n ROWS} — equivalent to
     * SQL's {@code ROWS BETWEEN n-1 PRECEDING AND CURRENT ROW}.  The per-partition
     * buffer never exceeds {@code n} rows, so a bounded frame is materialisation-safe
     * over an unbounded input.
     *
     * @param n the window width in rows; must be ≥ 1
     */
    record BoundedFrame(int n) implements WindowFrame {
        public BoundedFrame {
            if (n < 1) {
                throw new IllegalArgumentException("Window frame size must be at least 1, got " + n);
            }
        }
    }

    /**
     * The cumulative (running) frame — {@code OVER ALL ROWS} — equivalent to SQL's
     * {@code ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW}.  The buffer grows
     * with the partition, so this frame is blocking.
     */
    record CumulativeFrame() implements WindowFrame {}

    /**
     * SQL's frame clause, {@code ROWS | RANGE BETWEEN start AND end}.
     *
     * <p>In {@link Unit#ROWS} an offset counts rows: {@code 2 PRECEDING} is two rows
     * before the current one. In {@link Unit#RANGE} it is measured along the single sort
     * key: {@code 7 PRECEDING} reaches every row whose key is within seven of the
     * current row's, and {@code CURRENT ROW} includes the current row's peers — every
     * row with an equal key. A RANGE offset is a number for a {@code NUMBER} key or a
     * {@code DURATION} for a date or timestamp key.
     *
     * @param unit  whether offsets count rows or measure the sort key; never null
     * @param start where the frame begins; never {@code UNBOUNDED FOLLOWING}
     * @param end   where it ends; never {@code UNBOUNDED PRECEDING}, nor before the start
     */
    record Between(Unit unit, Bound start, Bound end) implements WindowFrame {
        public Between {
            java.util.Objects.requireNonNull(unit, "unit");
            java.util.Objects.requireNonNull(start, "start");
            java.util.Objects.requireNonNull(end, "end");
            if (start.kind() == Bound.Kind.UNBOUNDED_FOLLOWING) {
                throw new IllegalArgumentException("A frame cannot start at UNBOUNDED FOLLOWING");
            }
            if (end.kind() == Bound.Kind.UNBOUNDED_PRECEDING) {
                throw new IllegalArgumentException("A frame cannot end at UNBOUNDED PRECEDING");
            }
            if (start.kind().compareTo(end.kind()) > 0) {
                throw new IllegalArgumentException("A frame cannot end before it starts: "
                        + start + " AND " + end);
            }
        }

        /** Whether either end is an offset measured along the sort key. */
        public boolean measuresKey() {
            return unit == Unit.RANGE && (start.offset().isPresent() || end.offset().isPresent());
        }

        @Override
        public String toString() {
            return unit + " BETWEEN " + start + " AND " + end;
        }
    }

    /** What a {@link Between} frame's offsets count. */
    enum Unit {
        /** Rows before and after the current one. */
        ROWS,
        /** Distance along the sort key from the current row's value. */
        RANGE
    }

    /**
     * One end of a {@link Between} frame.
     *
     * @param kind   which end, in the order the kinds are declared; never null
     * @param offset the distance, present exactly for {@code PRECEDING} and
     *               {@code FOLLOWING}: a non-negative number, or a duration
     */
    record Bound(Kind kind, java.util.Optional<Operand> offset) {

        /** The kinds of bound, from the partition's start to its end. */
        public enum Kind {
            UNBOUNDED_PRECEDING, PRECEDING, CURRENT_ROW, FOLLOWING, UNBOUNDED_FOLLOWING
        }

        public Bound {
            java.util.Objects.requireNonNull(kind, "kind");
            java.util.Objects.requireNonNull(offset, "offset");
            boolean offsetKind = kind == Kind.PRECEDING || kind == Kind.FOLLOWING;
            if (offsetKind != offset.isPresent()) {
                throw new IllegalArgumentException(offsetKind
                        ? kind + " needs an offset" : kind + " takes no offset");
            }
        }

        public static Bound unboundedPreceding() {
            return new Bound(Kind.UNBOUNDED_PRECEDING, java.util.Optional.empty());
        }

        public static Bound preceding(Operand offset) {
            return new Bound(Kind.PRECEDING, java.util.Optional.of(offset));
        }

        public static Bound currentRow() {
            return new Bound(Kind.CURRENT_ROW, java.util.Optional.empty());
        }

        public static Bound following(Operand offset) {
            return new Bound(Kind.FOLLOWING, java.util.Optional.of(offset));
        }

        public static Bound unboundedFollowing() {
            return new Bound(Kind.UNBOUNDED_FOLLOWING, java.util.Optional.empty());
        }

        /** The bound as SQL writes it, the offset printed by {@code offsetText}. */
        public String render(java.util.function.Function<Operand, String> offsetText) {
            return switch (kind) {
                case UNBOUNDED_PRECEDING -> "UNBOUNDED PRECEDING";
                case PRECEDING -> offsetText.apply(offset.orElseThrow()) + " PRECEDING";
                case CURRENT_ROW -> "CURRENT ROW";
                case FOLLOWING -> offsetText.apply(offset.orElseThrow()) + " FOLLOWING";
                case UNBOUNDED_FOLLOWING -> "UNBOUNDED FOLLOWING";
            };
        }

        @Override
        public String toString() {
            return render(Bound::literal);
        }

        /** A literal offset as written: a number, or {@code DURATION '…'}. */
        public static String literal(Operand offset) {
            return switch (offset) {
                case NumberOperand n -> n.value();
                case DurationOperand d -> "DURATION '" + d.value() + "'";
                default -> String.valueOf(offset);
            };
        }
    }

    /**
     * The full-partition frame — equivalent to SQL's {@code ROWS BETWEEN UNBOUNDED
     * PRECEDING AND UNBOUNDED FOLLOWING}. Written {@code OVER PARTITION} for an
     * aggregate, each row then carrying its partition's total; set implicitly for
     * ranking and offset functions.
     */
    record PartitionFrame() implements WindowFrame {}
}
