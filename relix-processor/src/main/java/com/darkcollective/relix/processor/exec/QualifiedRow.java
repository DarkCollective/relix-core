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

import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.internal.NestedPaths;
import com.darkcollective.relix.symbol.Schema;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A read-only positional view over a left row concatenated with a right row, used
 * to evaluate a <em>join condition</em> with correct handling of qualified
 * attribute references.
 *
 * <p>When the two join inputs share a column name (e.g. both sides expose
 * {@code k}), the flat concatenated schema must rename one of them, which loses
 * the association between the predicate's qualifier and the intended side.  A
 * plain {@link com.darkcollective.relix.processor.internal.ArrayRow} can only resolve such
 * a name to its first positional match, so {@code A.k = B.k} would compare
 * {@code A.k} against itself.  This view instead resolves an ambiguous bare name
 * by its qualifier: {@code B.k} is routed to the right input when {@code B} is a
 * relation reachable on the right side (and not the left), and vice versa.
 *
 * <p>Resolution rules for {@link #get(String)}:
 * <ol>
 *   <li>If the bare column name exists in exactly one input, that side is used
 *       (the qualifier, if any, is irrelevant).</li>
 *   <li>If it exists in both inputs, the qualifier selects the side: a qualifier
 *       that names a relation on exactly one side wins.</li>
 *   <li>If still ambiguous (no qualifier, or a qualifier present on both/neither
 *       side), the left input is used — preserving the historical behaviour.</li>
 * </ol>
 *
 * <p>Ahead of all three sits one further case: a qualifier that names <em>no</em>
 * relation under either input, but does name a nested column under exactly one. That is
 * a struct path rather than a qualified reference, and it is routed to the side whose
 * schema resolves it. The ordering is what keeps the two apart — a qualifier that really
 * is a relation in scope never reaches this step, so every case the rules above describe
 * behaves exactly as it did.</p>
 *
 * <p>Output rows are <em>not</em> built from this view; operators continue to emit
 * flat {@code ArrayRow}s so downstream value-equality (distinct, set operations)
 * is unaffected.  This view exists only for the lifetime of a single condition
 * evaluation.
 *
 * <p>Public so the provenance evaluator can reuse it for the same qualifier-aware
 * theta-join condition evaluation.
 */
public final class QualifiedRow implements Row {

    private final Row leftRow;
    private final Row rightRow;
    private final Schema leftSchema;
    private final Schema rightSchema;
    private final Set<String> leftRelations;   // lowercased relation names under the left input
    private final Set<String> rightRelations;  // lowercased relation names under the right input
    private final Schema concatSchema;          // flat schema for schema()/get(int)

    public QualifiedRow(Row leftRow, Row rightRow,
                 Schema leftSchema, Schema rightSchema,
                 Set<String> leftRelations, Set<String> rightRelations,
                 Schema concatSchema) {
        this.leftRow        = leftRow;
        this.rightRow       = rightRow;
        this.leftSchema     = leftSchema;
        this.rightSchema    = rightSchema;
        this.leftRelations  = leftRelations;
        this.rightRelations = rightRelations;
        this.concatSchema   = concatSchema;
    }

    @Override
    public Schema schema() {
        return concatSchema;
    }

    @Override
    public Value get(String columnName) {
        Objects.requireNonNull(columnName, "columnName");
        int dot = columnName.lastIndexOf('.');
        String qualifier = dot >= 0 ? columnName.substring(0, dot) : null;
        String column    = AttributeNames.stripQualifier(columnName);

        // A dotted name may be a path into a nested column rather than a relation
        // qualifier. Where the qualifier names a relation under one of the inputs the
        // relation reading wins, as it must — that is what this view exists for. Where
        // it names no relation at all but does name a struct column, the reference is a
        // path, and resolving it by its tail binds to whichever side happens to carry a
        // column of that name: `location.team = Teams.team` compared `Teams.team` with
        // itself, so the join matched every pair and quietly became a cross product.
        if (qualifier != null) {
            String q = qualifier.toLowerCase(Locale.ROOT);
            if (!leftRelations.contains(q) && !rightRelations.contains(q)) {
                // NestedPaths owns the rule, including why an open schema is asked
                // nothing: it resolves every name, so its answer is no evidence about
                // which side was meant. The optimizer asks the same question of the same
                // headings when it decides which input a σ may be pushed into, and the
                // two must agree or a predicate is planned against one side and evaluated
                // against the other.
                switch (NestedPaths.ownerOf(columnName, leftSchema, rightSchema)) {
                    case LEFT -> {
                        return leftRow.get(columnName);
                    }
                    case RIGHT -> {
                        return rightRow.get(columnName);
                    }
                    case BOTH, NEITHER -> {
                        // Ambiguous, or not a path at all; the bare name decides below.
                    }
                }
            }
        }

        int li = leftSchema.indexOf(column);
        int ri = rightSchema.indexOf(column);

        // A schema-on-read side indexes nothing, so a column it really carries is
        // found by asking the *row* which fields it has. Without this both sides
        // looked empty and every qualified reference in a join condition over a
        // JSON/HTTP/Mongo relation threw (#657) — after the query had started.
        boolean leftHas  = li >= 0 || (leftSchema.isOpen()  && carries(leftRow, column));
        boolean rightHas = ri >= 0 || (rightSchema.isOpen() && carries(rightRow, column));

        if (leftHas && rightHas) {
            // Ambiguous bare name — let the qualifier choose the side.
            if (qualifier != null) {
                String q = qualifier.toLowerCase(Locale.ROOT);
                boolean onLeft  = leftRelations.contains(q);
                boolean onRight = rightRelations.contains(q);
                if (onRight && !onLeft) return valueOf(rightRow, ri, column);
                if (onLeft && !onRight) return valueOf(leftRow, li, column);
            }
            return valueOf(leftRow, li, column);   // indistinguishable → left wins
        }
        if (leftHas)  return valueOf(leftRow,  li, column);
        if (rightHas) return valueOf(rightRow, ri, column);
        if (leftSchema.isOpen() || rightSchema.isOpen()) {
            // A field a document does not carry is absent, not an error — the same
            // answer `σ missing = 1 (Docs)` gives. Only a closed schema can promise
            // the column exists, and there the validator has already checked it.
            return NullValue.INSTANCE;
        }
        throw new IllegalArgumentException("No column '" + columnName + "' in schema");
    }

    /** Whether {@code row} actually carries a field of this name (case-insensitively). */
    private static boolean carries(Row row, String column) {
        for (String name : row.columnNames()) {
            if (name.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    /** The value at {@code index}, or by name when the side declared no positions. */
    private static Value valueOf(Row row, int index, String column) {
        return index >= 0 ? row.get(index) : row.get(column);
    }

    @Override
    public Value get(int index) {
        int leftWidth = leftSchema.width();
        return index < leftWidth ? leftRow.get(index) : rightRow.get(index - leftWidth);
    }

    @Override
    public int width() {
        return leftSchema.width() + rightSchema.width();
    }
}
