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

import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProduceBound;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.events.EventMetrics;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.DocumentRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.eval.ValueComparator;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Executes leaf nodes — table/inline scans and UNNEST.
 *
 * <p>Holds a {@link ChildDispatch} to run input sub-plans.
 */
final class LeafExecutor {

    private final ChildDispatch dispatch;

    LeafExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeScan(PhysicalNode.Scan scan, EvalCtx ctx) {
        RelationSymbol symbol = scan.source();
        Stream<Row> rows = switch (symbol) {
            case InlineRelationSymbol   inline -> streamRows(inline.schema(), inline.rows());
            case SystemRelationSymbol   sys    -> streamRows(sys.schema(), sys.rows());
            // The plan's schema, not the symbol's: the planner may have narrowed this scan to
            // the columns the query reads, and a connector that resolves each column
            // independently then fetches only those. The two are the same heading wherever
            // nothing narrowed it, which is everywhere else.
            case SourceRelationSymbol   src    -> ctx.connector().open(src.canonicalName(), scan.schema());
            case DatabaseRelationSymbol db     -> ctx.connector().open(db.canonicalName(), db.schema());
            // Views are inlined by the planner and never reach the executor.
            case QueryRelationSymbol    view   -> throw new EvaluationException(
                    "Unexpected view in physical plan (should have been inlined): '"
                    + view.declaredName() + "'");
        };
        if (scan.schema().isOpen() && scan.qualifier().isPresent()) {
            // A declared row answers `products.name` through its heading's provenance; a
            // document has no heading, so without this a qualified reference to its own
            // field read as a path into a field called `products` (#972).
            String qualifier = scan.qualifier().get();
            rows = rows.map(row -> row instanceof DocumentRow document ? document.reanchored(qualifier) : row);
        }
        // GEN-001: a pushed generator bound stops the (otherwise unbounded,
        // ascending) producer via takeWhile, so the lazy scan is finite.
        if (scan.produceBound().isPresent()) {
            // Not counted: a bounded producer stops early by design, so its length is a
            // fact about the bound rather than about the relation.
            return applyProduceBound(rows, scan.produceBound().get(), ctx);
        }
        return counted(rows, symbol.canonicalName(), ctx);
    }

    /**
     * Reports how many rows a full scan of {@code relation} really produced — but only
     * if the scan was read to the end.
     *
     * <p>The engine estimates a leaf's size from whatever the catalog said, and a scan
     * that ran is a chance to replace that estimate with the number. The condition is the
     * whole of it: a consumer that stopped early (a {@code λ} above, a caller closing the
     * stream) delivered a count about <em>itself</em>, and filing that as the relation's
     * cardinality would make the next plan confident and wrong. So exhaustion is tracked
     * rather than assumed, and a partial read reports nothing at all.
     *
     * <p>Note what is already excluded by not being here: a pushed-down scan is a
     * {@code PushedScan}, whose rows have been filtered by the backend, and is never this
     * node.
     *
     * <p>It also times the scan, and the structure below is what makes that number mean
     * anything. The engine is pull-based, so a leaf's stream is only advanced when
     * something above it asks for a row — the interval from opening the stream to closing
     * it therefore spans every join, sort and format the consumer performed <em>through</em>
     * this scan, and reporting it would say a scan under a join took as long as the query.
     * So only {@code source.tryAdvance} is inside the timed window: the row is captured
     * there and handed on afterwards, leaving the elapsed time the scan's own — reading
     * and decoding its rows, which for a real source is where the I/O is. That is the
     * measurement the question "is the join the problem, or one of the scans?" needs.
     */
    private static Stream<Row> counted(Stream<Row> rows, String relation, EvalCtx ctx) {
        if (ctx.listener() == QueryEventListener.NONE) {
            return rows;   // nobody is listening; do not pay for the wrapper
        }
        Spliterator<Row> source = rows.spliterator();
        long[] delivered = {0};
        long[] nanos = {0};
        boolean[] exhausted = {false};
        Spliterator<Row> counting = new Spliterators.AbstractSpliterator<>(
                source.estimateSize(), source.characteristics()) {
            @Override
            public boolean tryAdvance(Consumer<? super Row> action) {
                Row[] pulled = {null};
                long start = System.nanoTime();
                boolean advanced = source.tryAdvance(row -> pulled[0] = row);
                nanos[0] += System.nanoTime() - start;
                if (!advanced) {
                    exhausted[0] = true;
                    return false;
                }
                delivered[0]++;
                action.accept(pulled[0]);
                return true;
            }
        };
        return StreamSupport.stream(counting, false)
                .onClose(rows::close)
                .onClose(() -> {
                    if (exhausted[0]) {
                        ctx.listener().onEvent(QueryEvent.of(QueryEvent.Stage.EXECUTE,
                                        "SCAN", "scanned " + delivered[0] + " row"
                                                + (delivered[0] == 1 ? "" : "s"), relation)
                                .withMetrics(EventMetrics.of(
                                        delivered[0], Duration.ofNanos(nanos[0]))));
                    }
                });
    }

    /**
     * Stops an ascending generator stream once the pushed upper bound is passed
     * ({@code takeWhile}): keep rows while {@code column < limit} (exclusive) or
     * {@code column <= limit} (inclusive, for {@code <=}/{@code =}).
     */
    private static Stream<Row> applyProduceBound(Stream<Row> rows, ProduceBound bound, EvalCtx ctx) {
        Value limit = ctx.operandEval().evaluate(bound.limit(), EMPTY_ROW);
        String column = bound.column();
        boolean inclusive = bound.inclusive();
        return rows.takeWhile(row -> {
            int cmp = ValueComparator.NULLS_LAST.compare(row.get(column), limit);
            return inclusive ? cmp <= 0 : cmp < 0;
        });
    }

    /** A zero-column row used only to evaluate constant (literal) produce-bound operands. */
    private static final Row EMPTY_ROW = ArrayRow.of(Schema.empty(), List.of());

    /**
     * Explodes the array-valued column into one row per element.  A non-array
     * value (empty array, NULL, missing, or scalar) emits no rows for inner unnest,
     * or one NULL-bound row for outer unnest.
     */
    Stream<Row> executeUnnest(PhysicalNode.Unnest node, EvalCtx ctx) {
        Schema schema = node.schema();
        String column = node.column();
        Optional<String> ordCol = node.ordinalityColumn();
        return dispatch.execute(node.input(), ctx).flatMap(row -> {
            Value cell = cellOf(row, column);
            if (cell instanceof ArrayValue array && !array.elements().isEmpty()) {
                List<Value> els = array.elements();
                List<Row> out = new ArrayList<>(els.size());
                for (int k = 0; k < els.size(); k++) {
                    // WITH ORDINALITY numbers elements from 1.
                    out.add(unnestRow(row, schema, column, els.get(k),
                            ordCol, new NumberValue(BigDecimal.valueOf(k + 1L))));
                }
                return out.stream();
            }
            return node.outer()
                    ? Stream.of(unnestRow(row, schema, column,
                            NullValue.INSTANCE, ordCol, NullValue.INSTANCE))
                    : Stream.empty();
        });
    }

    /** Reads the unnest column from a row — by path for an open document, by index otherwise. */
    private static Value cellOf(Row row, String column) {
        if (row instanceof DocumentRow doc) {
            return doc.get(column);
        }
        int index = row.schema().indexOf(column);
        return index >= 0 ? row.get(index) : NullValue.INSTANCE;
    }

    /**
     * Builds one unnest output row: a copy of {@code row} with {@code column} set to
     * {@code element}, and — when {@code ordinalityColumn} is present — the ordinality
     * column appended (it is the last column of the output {@code schema}).
     */
    private static Row unnestRow(Row row, Schema schema, String column, Value element,
                                 Optional<String> ordinalityColumn, Value ordinality) {
        if (row instanceof DocumentRow doc) {
            DocumentRow out = doc.with(column, element);
            return ordinalityColumn.map(name -> out.with(name, ordinality)).orElse(out);
        }
        int index = schema.indexOf(column);
        int dataWidth = ordinalityColumn.isPresent() ? schema.width() - 1 : schema.width();
        List<Value> values = new ArrayList<>(schema.width());
        for (int i = 0; i < dataWidth; i++) {
            values.add(i == index ? element : row.get(i));
        }
        if (ordinalityColumn.isPresent()) {
            values.add(ordinality);
        }
        return ArrayRow.of(schema, values);
    }

    private static Stream<Row> streamRows(Schema schema, List<Map<String, Operand>> rows) {
        return rows.stream().map(rowMap -> {
            List<Value> values = new ArrayList<>(schema.width());
            for (ColumnDefinition col : schema.columns()) {
                Operand operand = rowMap.get(col.name());
                values.add(operand == null ? NullValue.INSTANCE : literalToValue(operand));
            }
            return ArrayRow.of(schema, values);
        });
    }

    private static Value literalToValue(Operand operand) {
        return switch (operand) {
            case StringOperand    s -> new StringValue(s.value());
            case NumberOperand    n -> NumberValue.of(n.value());
            case BooleanOperand   b -> BooleanValue.of(b.value());
            case TimestampOperand t -> new TimestampValue(t.value());
            case DateOperand      d -> new DateValue(d.value());
            case TimeOperand      t -> new TimeValue(t.value());
            case DurationOperand  d -> new DurationValue(d.value());
            default -> throw new EvaluationException(
                    "Inline row contains non-literal operand: " + operand.getClass().getSimpleName());
        };
    }
}
