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

import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.eval.PredicateEvaluator;
import com.darkcollective.relix.processor.eval.ValueComparator;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.darkcollective.relix.processor.exec.ExecSupport.addUnmatched;
import static com.darkcollective.relix.processor.exec.ExecSupport.advanceBatch;
import static com.darkcollective.relix.processor.exec.ExecSupport.buildIndex;
import static com.darkcollective.relix.processor.exec.ExecSupport.buildNaturalJoinRow;
import static com.darkcollective.relix.processor.exec.ExecSupport.compareJoinKeys;
import static com.darkcollective.relix.processor.exec.ExecSupport.concatRows;
import static com.darkcollective.relix.processor.exec.ExecSupport.filterNullKeys;
import static com.darkcollective.relix.processor.exec.ExecSupport.keyTokens;
import static com.darkcollective.relix.processor.exec.ExecSupport.keys;
import static com.darkcollective.relix.processor.exec.ExecSupport.naturalMatches;
import static com.darkcollective.relix.processor.exec.ExecSupport.nullPaddedLeft;
import static com.darkcollective.relix.processor.exec.ExecSupport.nullPaddedRight;
import static com.darkcollective.relix.processor.exec.ExecSupport.probe;
import static com.darkcollective.relix.processor.exec.ExecSupport.rightOnlyIndices;

/**
 * Executes all join operators &mdash; hash / nested-loop joins (inner, natural,
 * the three outer forms, semi, anti, universal-semi, product) and their
 * sort-merge counterparts (Phase C2).  Holds a {@link ChildDispatch} to run the
 * two input sub-plans.
 */
final class JoinExecutor {

    private final ChildDispatch dispatch;

    JoinExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

        Stream<Row> executeJoin(PhysicalNode.Join join, EvalCtx ctx) {
        if (join.algorithm() == PhysicalNode.JoinAlgorithm.MERGE) {
            return executeMergeJoin(join, ctx);
        }
        return switch (join.kind()) {
            case PRODUCT     -> executeProduct(join, ctx);
            case INNER       -> executeInnerJoin(join, ctx);
            case NATURAL     -> executeNaturalJoin(join, ctx);
            case LEFT_OUTER  -> executeLeftOuter(join, ctx);
            case RIGHT_OUTER -> executeRightOuter(join, ctx);
            case FULL_OUTER  -> executeFullOuter(join, ctx);
            case SEMI            -> executeSemiJoin(join, ctx);
            case ANTI            -> executeAntiJoin(join, ctx);
            case UNIVERSAL_SEMI  -> executeUniversalSemiJoin(join, ctx);
        };
    }

    /**
     * A matched pair as one output row. The join's relation sets go with it so that an
     * open output can still tell which relation each field came from (#970).
     */
    private static Row joined(PhysicalNode.Join join, Row left, Row right) {
        return concatRows(left, right, join.schema(), join.leftRelations(), join.rightRelations());
    }

    /** An unmatched left row of an outer join, padded for the right side. */
    private static Row padRight(PhysicalNode.Join join, Row left, int rightWidth) {
        return nullPaddedRight(left, rightWidth, join.schema(), join.leftRelations());
    }

    /** An unmatched right row of an outer join, padded for the left side. */
    private static Row padLeft(PhysicalNode.Join join, int leftWidth, Row right) {
        return nullPaddedLeft(leftWidth, right, join.schema(), join.rightRelations());
    }

    private Stream<Row> executeProduct(PhysicalNode.Join join, EvalCtx ctx) {
        List<Row> right = dispatch.materialize(join.right(), ctx, join);
        return dispatch.execute(join.left(), ctx)
                .flatMap(leftRow -> right.stream()
                        .map(rightRow -> joined(join, leftRow, rightRow)));
    }

    private Stream<Row> executeInnerJoin(PhysicalNode.Join join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        JoinMatcher matcher = matcher(join, outputSchema, ctx);

        if (join.algorithm() == PhysicalNode.JoinAlgorithm.NESTED_LOOP) {
            List<Row> right = buildSide(join.right(), null, join, ctx).rows();
            return dispatch.execute(join.left(), ctx)
                    .flatMap(leftRow -> right.stream()
                            .filter(rightRow -> matcher.matches(leftRow, rightRow))
                            .map(rightRow -> joined(join, leftRow, rightRow)));
        }
        int[] leftKeys = keys(join.keys().left());
        int[] rightKeys = keys(join.keys().right());
        if (join.buildSide() == PhysicalNode.BuildSide.RIGHT) {
            Map<List<String>, List<Row>> index = buildSide(join.right(), rightKeys, join, ctx).index();
            return dispatch.execute(join.left(), ctx).flatMap(leftRow ->
                    probe(index, leftRow, leftKeys).stream()
                            .filter(rightRow -> matcher.matches(leftRow, rightRow))
                            .map(rightRow -> joined(join, leftRow, rightRow)));
        }
        Map<List<String>, List<Row>> index = buildSide(join.left(), leftKeys, join, ctx).index();
        return dispatch.execute(join.right(), ctx).flatMap(rightRow ->
                probe(index, rightRow, rightKeys).stream()
                        .filter(leftRow -> matcher.matches(leftRow, rightRow))
                        .map(leftRow -> joined(join, leftRow, rightRow)));
    }

    private Stream<Row> executeNaturalJoin(PhysicalNode.Join join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        int[] leftKeys = keys(join.keys().left());
        int[] rightKeys = keys(join.keys().right());
        boolean buildRight = join.buildSide() == PhysicalNode.BuildSide.RIGHT;
        SpoolCache.BuiltSide left  = buildSide(join.left(), buildRight ? null : leftKeys, join, ctx);
        SpoolCache.BuiltSide right = buildSide(join.right(), buildRight ? rightKeys : null, join, ctx);
        List<Row> leftRows = left.rows();
        List<Row> rightRows = right.rows();

        if (leftRows.isEmpty() || rightRows.isEmpty() || leftKeys.length == 0) {
            return Stream.empty();   // no rows, or no common columns → no matches
        }
        List<Integer> rightOnly = rightOnlyIndices(join.right().schema().width(), rightKeys);

        if (buildRight) {
            Map<List<String>, List<Row>> index = right.index();
            return leftRows.stream().flatMap(leftRow ->
                    probe(index, leftRow, leftKeys).stream()
                            .filter(rightRow -> naturalMatches(leftRow, rightRow, leftKeys, rightKeys))
                            .map(rightRow -> buildNaturalJoinRow(leftRow, rightRow, rightOnly, outputSchema)));
        }
        Map<List<String>, List<Row>> index = left.index();
        return rightRows.stream().flatMap(rightRow ->
                probe(index, rightRow, rightKeys).stream()
                        .filter(leftRow -> naturalMatches(leftRow, rightRow, leftKeys, rightKeys))
                        .map(leftRow -> buildNaturalJoinRow(leftRow, rightRow, rightOnly, outputSchema)));
    }

    private Stream<Row> executeLeftOuter(PhysicalNode.Join join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        int leftWidth = join.left().schema().width();
        int rightWidth = outputSchema.width() - leftWidth;
        JoinMatcher matcher = matcher(join, outputSchema, ctx);
        SpoolCache.BuiltSide built =
                buildSide(join.right(), hashable(join) ? keys(join.keys().right()) : null, join, ctx);
        List<Row> right = built.rows();
        Map<List<String>, List<Row>> index = built.index();
        int[] leftKeys = keys(join.keys().left());

        return dispatch.execute(join.left(), ctx).flatMap(leftRow -> {
            List<Row> candidates = (index == null) ? right : probe(index, leftRow, leftKeys);
            List<Row> matches = candidates.stream()
                    .filter(rightRow -> matcher.matches(leftRow, rightRow))
                    .map(rightRow -> joined(join, leftRow, rightRow))
                    .toList();
            return matches.isEmpty()
                    ? Stream.of(padRight(join, leftRow, rightWidth))
                    : matches.stream();
        });
    }

    private Stream<Row> executeRightOuter(PhysicalNode.Join join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        int leftWidth = join.left().schema().width();
        JoinMatcher matcher = matcher(join, outputSchema, ctx);
        SpoolCache.BuiltSide built =
                buildSide(join.left(), hashable(join) ? keys(join.keys().left()) : null, join, ctx);
        List<Row> left = built.rows();
        Map<List<String>, List<Row>> index = built.index();
        int[] rightKeys = keys(join.keys().right());

        return dispatch.execute(join.right(), ctx).flatMap(rightRow -> {
            List<Row> candidates = (index == null) ? left : probe(index, rightRow, rightKeys);
            List<Row> matches = candidates.stream()
                    .filter(leftRow -> matcher.matches(leftRow, rightRow))
                    .map(leftRow -> joined(join, leftRow, rightRow))
                    .toList();
            return matches.isEmpty()
                    ? Stream.of(padLeft(join, leftWidth, rightRow))
                    : matches.stream();
        });
    }

    private Stream<Row> executeFullOuter(PhysicalNode.Join join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        int leftWidth = join.left().schema().width();
        int rightWidth = outputSchema.width() - leftWidth;
        JoinMatcher matcher = matcher(join, outputSchema, ctx);
        boolean buildRight = join.buildSide() == PhysicalNode.BuildSide.RIGHT;
        SpoolCache.BuiltSide left = buildSide(join.left(),
                hashable(join) && !buildRight ? keys(join.keys().left()) : null, join, ctx);
        SpoolCache.BuiltSide right = buildSide(join.right(),
                hashable(join) && buildRight ? keys(join.keys().right()) : null, join, ctx);
        List<Row> leftRows = left.rows();
        List<Row> rightRows = right.rows();
        List<Row> result = new ArrayList<>();

        if (!hashable(join)) {
            fullOuterDriveLeft(join, leftRows, rightRows, leftRow -> rightRows,
                    matcher, leftWidth, rightWidth, result);
        } else if (buildRight) {
            Map<List<String>, List<Row>> index = right.index();
            int[] leftKeys = keys(join.keys().left());
            fullOuterDriveLeft(join, leftRows, rightRows, leftRow -> probe(index, leftRow, leftKeys),
                    matcher, leftWidth, rightWidth, result);
        } else {
            Map<List<String>, List<Row>> index = left.index();
            int[] rightKeys = keys(join.keys().right());
            Set<Row> matchedLeft = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Row rightRow : rightRows) {
                boolean matched = false;
                for (Row leftRow : probe(index, rightRow, rightKeys)) {
                    if (matcher.matches(leftRow, rightRow)) {
                        result.add(joined(join, leftRow, rightRow));
                        matchedLeft.add(leftRow);
                        matched = true;
                    }
                }
                if (!matched) result.add(padLeft(join, leftWidth, rightRow));
            }
            addUnmatched(leftRows, matchedLeft, l -> result.add(padRight(join, l, rightWidth)));
        }
        return BagRelation.of(outputSchema, result).stream();
    }

    /**
     * Full-outer pass that drives over the left rows: for each left row it emits a
     * joined row per matching right candidate (right-padding it when none match),
     * then left-pads every right row that never matched. The two variants that
     * iterate left — nested-loop (all right rows) and right-build hash (probed
     * candidates) — differ only in {@code rightCandidates}.
     */
    private void fullOuterDriveLeft(PhysicalNode.Join join, List<Row> leftRows, List<Row> rightRows,
                                    Function<Row, Iterable<Row>> rightCandidates,
                                    JoinMatcher matcher, int leftWidth, int rightWidth,
                                    List<Row> result) {
        Set<Row> matchedRight = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Row leftRow : leftRows) {
            boolean matched = false;
            for (Row rightRow : rightCandidates.apply(leftRow)) {
                if (matcher.matches(leftRow, rightRow)) {
                    result.add(joined(join, leftRow, rightRow));
                    matchedRight.add(rightRow);
                    matched = true;
                }
            }
            if (!matched) result.add(padRight(join, leftRow, rightWidth));
        }
        addUnmatched(rightRows, matchedRight, r -> result.add(padLeft(join, leftWidth, r)));
    }

    /** Semi-join: emits each left row that has at least one matching right row. */
    private Stream<Row> executeSemiJoin(PhysicalNode.Join join, EvalCtx ctx) {
        return hashSemiAntiJoin(join, ctx, true);
    }

    /** Anti-join: emits each left row that has no matching right row. */
    private Stream<Row> executeAntiJoin(PhysicalNode.Join join, EvalCtx ctx) {
        return hashSemiAntiJoin(join, ctx, false);
    }

    /**
     * Shared skeleton for hash semi-join and anti-join: builds a hash index on the
     * right side (or uses the full list when NESTED_LOOP), then filters left rows
     * based on whether a match exists.  {@code emitOnMatch=true} → semi (keep when
     * any match); {@code emitOnMatch=false} → anti (keep when no match; NULL-key
     * left rows probe to nothing and are always kept).
     */
    private Stream<Row> hashSemiAntiJoin(PhysicalNode.Join join, EvalCtx ctx,
                                          boolean emitOnMatch) {
        Schema concatSchema = join.left().schema().concat(join.right().schema());
        JoinMatcher matcher = matcher(join, concatSchema, ctx);
        SpoolCache.BuiltSide built =
                buildSide(join.right(), hashable(join) ? keys(join.keys().right()) : null, join, ctx);
        List<Row> right = built.rows();
        Map<List<String>, List<Row>> index = built.index();
        int[] leftKeys = keys(join.keys().left());

        return dispatch.execute(join.left(), ctx).filter(leftRow -> {
            List<Row> candidates = (index == null) ? right : probe(index, leftRow, leftKeys);
            return emitOnMatch
                    ? candidates.stream().anyMatch(rightRow -> matcher.matches(leftRow, rightRow))
                    : candidates.stream().noneMatch(rightRow -> matcher.matches(leftRow, rightRow));
        });
    }

    private Stream<Row> executeUniversalSemiJoin(PhysicalNode.Join join, EvalCtx ctx) {
        Schema concatSchema = join.left().schema().concat(join.right().schema());
        JoinMatcher matcher = matcher(join, concatSchema, ctx);
        // Materialise the right side once; an empty right → all left rows pass (vacuously true).
        List<Row> right = dispatch.materialize(join.right(), ctx, join);

        return dispatch.execute(join.left(), ctx).filter(leftRow ->
                right.stream().allMatch(rightRow -> matcher.matches(leftRow, rightRow)));
    }

    // =========================================================================
    // Merge join operators (Phase C2 — sort-merge execution)
    // =========================================================================

    /**
     * Dispatches a merge-algorithm join to the appropriate kind-specific executor.
     * The planner guarantees inputs are delivered in an order satisfying the join
     * keys (inserting Sort enforcers where needed), so this executor never re-sorts.
     */
    private Stream<Row> executeMergeJoin(PhysicalNode.Join join, EvalCtx ctx) {
        return switch (join.kind()) {
            case INNER   -> executeMergeInner(join, ctx);
            case NATURAL -> executeMergeNatural(join, ctx);
            case SEMI    -> executeMergeSemi(join, ctx);
            case ANTI    -> executeMergeAnti(join, ctx);
            default -> throw new EvaluationException(
                    "Merge algorithm not supported for join kind: " + join.kind());
        };
    }

    /**
     * Receives one matched batch of a sort-merge scan: the equal-join-key run
     * {@code left[leftStart, leftEnd)} on the left paired with the equal-key run
     * {@code right[rightStart, rightEnd)} on the right.  How the matched rows are
     * combined (cross-product, natural-join row, semi-filter) is the only thing
     * that varies between the merge variants.
     */
    @FunctionalInterface
    private interface MergeBatchConsumer {
        void accept(List<Row> left, int leftStart, int leftEnd,
                    List<Row> right, int rightStart, int rightEnd);
    }

    /**
     * Variant of {@link MergeBatchConsumer} that receives the shared {@code result}
     * list alongside the batch ranges, so the merge-stream skeleton can create the
     * list and the collector can add to it without a separate closure.
     */
    @FunctionalInterface
    private interface MergeBatchCollector {
        void collect(List<Row> left, int ls, int le,
                     List<Row> right, int rs, int re,
                     List<Row> result);
    }

    /**
     * The shared two-pointer sorted-merge loop: advances over {@code left} and
     * {@code right} (both pre-sorted on their join keys), and for every pair of
     * equal-key batches invokes {@code onMatch} with the batch ranges.  Rows whose
     * key lies below the other side are skipped; only matching batches are visited.
     */
    private static void mergeScan(List<Row> left, int[] leftKeys,
                                  List<Row> right, int[] rightKeys,
                                  MergeBatchConsumer onMatch) {
        int i = 0, j = 0;
        while (i < left.size() && j < right.size()) {
            int cmp = compareJoinKeys(left.get(i), leftKeys, right.get(j), rightKeys);
            if (cmp < 0) {
                i++;
            } else if (cmp > 0) {
                j++;
            } else {
                int iEnd = advanceBatch(left,  i, leftKeys);
                int jEnd = advanceBatch(right, j, rightKeys);
                onMatch.accept(left, i, iEnd, right, j, jEnd);
                i = iEnd;
                j = jEnd;
            }
        }
    }

    /**
     * Shared skeleton for merge inner/natural/semi: filters NULL keys from both
     * materialised inputs, runs the two-pointer merge scan, and wraps the collected
     * result as a stream.  The {@code collector} adds matched rows to the provided
     * result list.
     */
    private Stream<Row> mergeStream(PhysicalNode.Join join, EvalCtx ctx,
                                     int[] leftKeys, int[] rightKeys,
                                     Schema outputSchema, MergeBatchCollector collector) {
        List<Row> leftRows  = filterNullKeys(dispatch.materialize(join.left(),  ctx, join), leftKeys);
        List<Row> rightRows = filterNullKeys(dispatch.materialize(join.right(), ctx, join), rightKeys);
        List<Row> result = new ArrayList<>();
        mergeScan(leftRows, leftKeys, rightRows, rightKeys,
                  (l, ls, le, r, rs, re) -> collector.collect(l, ls, le, r, rs, re, result));
        return BagRelation.of(outputSchema, result).stream();
    }

    /**
     * Sort-merge inner join: two-pointer scan over pre-sorted inputs.  For each
     * batch of equal join-key rows on both sides the full cross-product is emitted,
     * filtered by the join condition.  Rows with NULL join keys are skipped
     * (consistent with hash-join NULL semantics: NULL never equals NULL).
     */
    private Stream<Row> executeMergeInner(PhysicalNode.Join join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        int[] leftKeys  = keys(join.keys().left());
        int[] rightKeys = keys(join.keys().right());
        JoinMatcher matcher = matcher(join, outputSchema, ctx);
        return mergeStream(join, ctx, leftKeys, rightKeys, outputSchema,
                (left, ls, le, right, rs, re, result) -> {
                    for (int li = ls; li < le; li++) {
                        for (int ri = rs; ri < re; ri++) {
                            Row leftRow  = left.get(li);
                            Row rightRow = right.get(ri);
                            if (matcher.matches(leftRow, rightRow)) {
                                result.add(joined(join, leftRow, rightRow));
                            }
                        }
                    }
                });
    }

    /**
     * Sort-merge natural join: like inner merge but output rows are built via
     * {@link ExecSupport#buildNaturalJoinRow} — common columns appear once, from
     * the left.  No condition predicate; natural joins match purely on key equality.
     */
    private Stream<Row> executeMergeNatural(PhysicalNode.Join join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        int[] leftKeys  = keys(join.keys().left());
        int[] rightKeys = keys(join.keys().right());
        List<Integer> rightOnly = rightOnlyIndices(join.right().schema().width(), rightKeys);
        if (leftKeys.length == 0) return Stream.empty();  // no common columns → no matches
        return mergeStream(join, ctx, leftKeys, rightKeys, outputSchema,
                (left, ls, le, right, rs, re, result) -> {
                    for (int li = ls; li < le; li++) {
                        for (int ri = rs; ri < re; ri++) {
                            result.add(buildNaturalJoinRow(
                                    left.get(li), right.get(ri), rightOnly, outputSchema));
                        }
                    }
                });
    }

    /**
     * Sort-merge semi-join: emits each left row for which at least one matching
     * right row exists (key-equal and condition-satisfying).  Equal-key batches are
     * processed together.  Rows with NULL join keys are skipped (NULL never matches).
     */
    private Stream<Row> executeMergeSemi(PhysicalNode.Join join, EvalCtx ctx) {
        int[] leftKeys  = keys(join.keys().left());
        int[] rightKeys = keys(join.keys().right());
        Schema concatSchema = join.left().schema().concat(join.right().schema());
        JoinMatcher matcher = matcher(join, concatSchema, ctx);
        return mergeStream(join, ctx, leftKeys, rightKeys, join.schema(),
                (left, ls, le, right, rs, re, result) -> {
                    for (int li = ls; li < le; li++) {
                        Row leftRow = left.get(li);
                        for (int ri = rs; ri < re; ri++) {
                            if (matcher.matches(leftRow, right.get(ri))) {
                                result.add(leftRow);
                                break;  // semi: one match is enough per left row
                            }
                        }
                    }
                });
    }

    /**
     * Sort-merge anti-join: emits each left row for which no matching right row
     * exists.  Left rows whose key lies below the current right key are emitted
     * immediately.  Left rows with NULL join keys are always emitted (NULL can
     * never match, consistent with hash anti-join semantics).
     *
     * <p>This variant uses a bespoke two-pointer loop rather than
     * {@link #mergeStream} because it must emit unmatched left rows <em>as it
     * scans</em> — including those below the current right cursor — which does not
     * fit the equal-batch model of {@code mergeScan}.
     */
    private Stream<Row> executeMergeAnti(PhysicalNode.Join join, EvalCtx ctx) {
        int[] leftKeys  = keys(join.keys().left());
        int[] rightKeys = keys(join.keys().right());
        Schema concatSchema = join.left().schema().concat(join.right().schema());
        JoinMatcher matcher = matcher(join, concatSchema, ctx);

        List<Row> allLeft   = dispatch.materialize(join.left(),  ctx, join);
        List<Row> rightRows = filterNullKeys(dispatch.materialize(join.right(), ctx, join), rightKeys);

        List<Row> leftRows = new ArrayList<>();
        List<Row> result   = new ArrayList<>();
        for (Row row : allLeft) {
            // NULL-key left rows have no right match — always qualify for anti-join.
            if (keyTokens(row, leftKeys) == null) result.add(row);
            else leftRows.add(row);
        }

        int i = 0, j = 0;
        while (i < leftRows.size() && j < rightRows.size()) {
            int cmp = compareJoinKeys(leftRows.get(i), leftKeys, rightRows.get(j), rightKeys);
            if (cmp < 0) {
                result.add(leftRows.get(i++));  // no right match possible — emit
            } else if (cmp > 0) {
                j++;  // right is behind, advance it
            } else {
                int iEnd = advanceBatch(leftRows,  i, leftKeys);
                int jEnd = advanceBatch(rightRows, j, rightKeys);
                for (int li = i; li < iEnd; li++) {
                    Row leftRow = leftRows.get(li);
                    boolean matched = false;
                    for (int ri = j; ri < jEnd; ri++) {
                        if (matcher.matches(leftRow, rightRows.get(ri))) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) result.add(leftRow);
                }
                i = iEnd;
                j = jEnd;
            }
        }
        while (i < leftRows.size()) result.add(leftRows.get(i++));
        return BagRelation.of(join.schema(), result).stream();
    }

    // =========================================================================
    // AS-OF join (ADR-0014) — temporal nearest-match left-outer join
    // =========================================================================

    /**
     * Executes an AS-OF join: for each left probe row, emits the probe concatenated
     * with the single nearest right row (by the match column, in the node's
     * direction) among the right rows sharing its partition keys; a NULL-padded row
     * when none matches (left-outer).
     *
     * <p>The right side is materialised once, hash-partitioned by the equality keys,
     * and each partition sorted ascending by its match column (a stable sort, so the
     * input order is preserved among equal match values — the documented tie rule:
     * the last such right row wins).  Each probe then binary-searches its partition
     * for the nearest qualifying value.  Right rows with a NULL partition key or NULL
     * match value can never match and are dropped; a probe with a NULL partition key
     * or NULL match value never matches.
     */
    Stream<Row> executeAsOfJoin(PhysicalNode.AsOfJoin join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        int leftWidth = join.left().schema().width();
        int rightWidth = outputSchema.width() - leftWidth;
        int[] partLeft = keys(join.partitionKeys().left());
        int[] partRight = keys(join.partitionKeys().right());
        int lMatch = join.leftMatchIndex();
        int rMatch = join.rightMatchIndex();
        boolean backward = join.backward();
        boolean strict = join.strict();
        boolean inner = join.inner();
        java.util.Optional<java.time.Duration> tolerance = join.tolerance();
        com.darkcollective.relix.ast.TieBreak tieBreak = join.tieBreak();

        Map<List<String>, List<Row>> partitions = new HashMap<>();
        for (Row r : dispatch.materialize(join.right(), ctx, join)) {
            List<String> token = keyTokens(r, partRight);
            if (token == null) continue;            // NULL partition key — never matches
            if (r.get(rMatch).isNull()) continue;   // NULL match value — uncomparable
            partitions.computeIfAbsent(token, k -> new ArrayList<>()).add(r);
        }
        Comparator<Row> byMatch = (a, b) -> ValueComparator.compareNonNull(a.get(rMatch), b.get(rMatch));
        partitions.values().forEach(list -> list.sort(byMatch));

        return dispatch.execute(join.left(), ctx).flatMap(leftRow -> {
            Row match = nearestMatch(leftRow, lMatch, rMatch, partLeft, partitions,
                                     backward, strict, tolerance, tieBreak);
            if (match == null) {
                if (inner) return Stream.empty();   // inner mode: drop unmatched probe
                return Stream.of(nullPaddedRight(leftRow, rightWidth, outputSchema));
            }
            return Stream.of(concatRows(leftRow, match, outputSchema));
        });
    }

    /** Finds the nearest qualifying right row for a probe, or {@code null} if none. */
    private static Row nearestMatch(Row leftRow, int lMatch, int rMatch, int[] partLeft,
                                    Map<List<String>, List<Row>> partitions,
                                    boolean backward, boolean strict,
                                    java.util.Optional<java.time.Duration> tolerance,
                                    com.darkcollective.relix.ast.TieBreak tieBreak) {
        List<String> token = keyTokens(leftRow, partLeft);
        if (token == null) return null;                 // NULL partition key
        List<Row> part = partitions.get(token);
        if (part == null) return null;
        Value probe = leftRow.get(lMatch);
        if (probe.isNull()) return null;                // NULL probe — uncomparable

        Row candidate;
        if (backward) {
            // Greatest value at-or-before the probe (strict: strictly before).
            int k = countAtMost(part, rMatch, probe, !strict);
            if (k == 0) return null;
            // The qualifying prefix is part[0..k-1] (ascending sort). Among the tied
            // nearest (part[k-1] value) group: LAST = part[k-1] (default), FIRST = first
            // element in the run.
            if (tieBreak == com.darkcollective.relix.ast.TieBreak.LAST) {
                candidate = part.get(k - 1);
            } else {
                // FIRST: find the start of the run sharing part[k-1]'s match value.
                Value nearVal = part.get(k - 1).get(rMatch);
                int runStart = countAtMost(part, rMatch, nearVal, false); // first index where val >= nearVal
                candidate = part.get(runStart);
            }
        } else {
            // Forward: least value at-or-after the probe (strict: strictly after).
            int start = countAtMost(part, rMatch, probe, strict);
            if (start >= part.size()) return null;
            Value least = part.get(start).get(rMatch);
            int runEnd = countAtMost(part, rMatch, least, true);
            // Among the tied least-value run: LAST = part[runEnd-1] (default), FIRST = part[start].
            candidate = tieBreak == com.darkcollective.relix.ast.TieBreak.LAST
                    ? part.get(runEnd - 1)
                    : part.get(start);
        }

        // Apply tolerance: discard if |probe − candidate.matchVal| > tolerance.
        if (candidate != null && tolerance.isPresent()) {
            Value candidateVal = candidate.get(rMatch);
            if (!withinTolerance(probe, candidateVal, tolerance.get())) {
                candidate = null;
            }
        }
        return candidate;
    }

    /**
     * Returns {@code true} when the distance between the probe and the candidate
     * match value is within the given {@code tolerance}.
     *
     * <p>A pair with no defined distance — two strings, two numbers — is an error
     * rather than a skipped check: the bound the user wrote would otherwise be
     * discarded in silence, and ISO-8601 text still <em>orders</em> correctly, so
     * the join goes on looking right while {@code WITHIN} does nothing.  The
     * validator rejects this ahead of execution wherever the match column's type is
     * known; what reaches here is what it could not see — an open (schema-on-read)
     * source, or an {@code ANY} column.
     */
    private static boolean withinTolerance(Value probe, Value candidate, java.time.Duration tolerance) {
        java.time.Duration distance = com.darkcollective.relix.processor.eval.TemporalDistance
                .between(probe, candidate)
                .orElseThrow(() -> new EvaluationException(
                        "AS-OF WITHIN measures a temporal distance, and none is defined between "
                        + probe.type().name() + " and " + candidate.type().name()
                        + " match values; convert the match column, e.g. π to_timestamp(ts) → ts (…)"));
        return distance.compareTo(tolerance) <= 0;
    }

    /**
     * Counts the leading elements of the ascending-sorted {@code sorted} whose match
     * value is {@code < probe} ({@code orEqual=false}) or {@code <= probe}
     * ({@code orEqual=true}).  Binary search; the qualifying elements form a prefix.
     */
    private static int countAtMost(List<Row> sorted, int matchIdx, Value probe, boolean orEqual) {
        int lo = 0, hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = ValueComparator.compareNonNull(sorted.get(mid).get(matchIdx), probe);
            boolean atMost = orEqual ? cmp <= 0 : cmp < 0;
            if (atMost) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * Whether this join builds a hash index; {@code false} means the caller walks the
     * whole opposite input per probe row, i.e. an O(n·m) nested loop.
     *
     * <p>The test is the algorithm the planner chose, and nothing else — MERGE is
     * dispatched before any of these call sites, so {@code false} here means
     * {@link PhysicalNode.JoinAlgorithm#NESTED_LOOP}, which the planner picks exactly
     * when the condition yielded no equi-join key.  Plan and execution therefore cannot
     * disagree about which strategy runs, and the planner emits a
     * {@code PLAN/JOIN-NESTED-LOOP} event explaining why it chose this one —
     * so a quadratic join is visible in {@code --trace} rather than only in the clock.
     */
    private static boolean hashable(PhysicalNode.Join join) {
        return join.algorithm() == PhysicalNode.JoinAlgorithm.HASH;
    }

    /**
     * The build side of a join: its rows, and the hash index over {@code keys} when
     * the join hashes ({@code keys} null for a nested loop, which walks the rows).
     *
     * <p><b>Prepared once per execution when the sub-plan is a spool.</b> A fixpoint
     * step is re-executed on every round, so a join inside one buffered and re-hashed
     * its build side per round even where nothing behind it changed — the spool made
     * the <em>scan</em> happen once and could do no more, because caching rows is all
     * a spool can do. The rows behind a spool are stable for the whole execution (the
     * planner shares only a reproducible sub-plan, and never one naming a recursive
     * relation bound outside itself), so the hash table over them is too.
     *
     * <p>Kept only while a fixpoint step is being evaluated. Everywhere else a plan
     * runs once, so an artifact cached from it would be built, never re-read, and held
     * until the root stream closed.
     *
     * <p>The rows come back as the same list on every round, so a caller must not sort
     * or otherwise mutate them.
     *
     * <p>The merge, AS-OF and interval joins are not routed through here, and not
     * because of that rule — none of them mutates its buffer, each copying first. It
     * is that the artifact worth keeping there is a <em>different</em> one: a
     * partition map of match-sorted lists for AS-OF, endpoint-sorted lists or a plane
     * sweep for interval, a null-filtered copy for merge. This memo holds rows and a
     * hash index, so reaching them means generalising it to an arbitrary artifact
     * keyed per operator rather than swapping a call.
     */
    private SpoolCache.BuiltSide buildSide(PhysicalNode side, int[] keys,
                                           PhysicalNode.Join owner, EvalCtx ctx) {
        String signature = keys == null ? "-" : Arrays.toString(keys);
        // Only inside a fixpoint step, which is the only place a plan is executed
        // more than once — elsewhere the artifact would be built, never re-read, and
        // held until the root stream closed, which is a memory cost for no saving.
        // A non-empty binding map is exactly "a recursive step is being evaluated".
        Integer spoolId = !ctx.recursionBindings().isEmpty()
                && side instanceof PhysicalNode.Spool spool ? spool.id() : null;
        if (spoolId != null) {
            SpoolCache.BuiltSide kept = ctx.spools().builtSide(spoolId, signature);
            if (kept != null) {
                return kept;
            }
        }
        List<Row> rows = dispatch.materialize(side, ctx, owner);
        SpoolCache.BuiltSide built =
                new SpoolCache.BuiltSide(rows, keys == null ? null : buildIndex(rows, keys));
        if (spoolId != null) {
            ctx.spools().keepBuiltSide(spoolId, signature, built);
        }
        return built;
    }

    private JoinMatcher matcher(PhysicalNode.Join join, Schema evalSchema, EvalCtx ctx) {
        return new JoinMatcher(ctx.predicateEval(),
                join.condition().orElseThrow(() -> new EvaluationException("join is missing its condition")),
                join.left().schema(), join.right().schema(),
                join.leftRelations(), join.rightRelations(), evalSchema);
    }

    /**
     * Executes an interval join (ADR-0014): for each pair of rows whose endpoint
     * columns satisfy the chosen {@link com.darkcollective.relix.ast.AllenRelation},
     * emits the concatenation of the two rows.  Always an inner join (no NULL padding).
     *
     * <p>The comparisons use {@link ValueComparator#compareNonNull} exactly as
     * the AS-OF join does; rows with a NULL endpoint are treated as non-matching
     * and silently skipped.
     */
    Stream<Row> executeIntervalJoin(PhysicalNode.IntervalJoin join, EvalCtx ctx) {
        Schema outputSchema = join.schema();
        int lStart = join.leftStartIdx();
        int lEnd   = join.leftEndIdx();
        int rStart = join.rightStartIdx();
        int rEnd   = join.rightEndIdx();
        AllenRelation rel = join.relation();

        // Materialise both sides, dropping rows with a NULL endpoint (which can
        // never satisfy any Allen relation), so the sweep/band logic sees only
        // comparable intervals.
        List<Row> lefts  = nonNullEndpoints(dispatch.materialize(join.left(), ctx, join),  lStart, lEnd);
        List<Row> rights = nonNullEndpoints(dispatch.materialize(join.right(), ctx, join), rStart, rEnd);

        List<Row> out = switch (rel) {
            // The disjoint "before"/"after" relations have no overlap structure
            // to exploit; a sorted-endpoint band binary-searches the boundary and
            // emits the matching suffix/prefix per left row.
            case PRECEDES    -> precedesBand(lefts, rights, lEnd, rStart, outputSchema);
            case PRECEDED_BY -> precededByBand(lefts, rights, lStart, rEnd, outputSchema);
            // Every other relation implies the closed intervals intersect (overlap
            // or touch); a plane sweep generates exactly those candidate pairs in
            // O((n+m)·log(n+m) + matches) and the precise Allen predicate confirms
            // each candidate, replacing the O(n·m) nested loop.  When both inputs
            // already arrive start-ordered (planner-chosen, ADR-0009), a streaming
            // sort-merge skips the global endpoint sort.
            default -> join.merge()
                    ? mergeIntervalJoin(rel, lefts, rights, lStart, lEnd, rStart, rEnd, outputSchema)
                    : planeSweep(rel, lefts, rights, lStart, lEnd, rStart, rEnd, outputSchema);
        };
        return out.stream();
    }

    /** Drops rows whose interval start or end is NULL (never a match). */
    private static List<Row> nonNullEndpoints(List<Row> rows, int startIdx, int endIdx) {
        List<Row> kept = new ArrayList<>(rows.size());
        for (Row r : rows) {
            if (!r.get(startIdx).isNull() && !r.get(endIdx).isNull()) {
                kept.add(r);
            }
        }
        return kept;
    }

    /** One interval occurrence; {@code seq} keeps duplicate-valued rows distinct. */
    private record Interval(Value start, Value end, Row row, int seq) {}

    /** A start ({@code open}) or end endpoint event for one interval during the sweep. */
    private record SweepEvent(Value coord, boolean open, boolean left, Interval interval) {}

    // Sort by coordinate; OPEN before CLOSE at an equal coordinate so a touching
    // pair (ℓ.end == r.start) is still seen as an overlap candidate.
    private static final Comparator<SweepEvent> EVENT_ORDER =
            Comparator.<SweepEvent, Value>comparing(SweepEvent::coord, ValueComparator::compareNonNull)
                      .thenComparingInt(e -> e.open() ? 0 : 1);

    /**
     * Plane-sweep interval join for every relation whose intervals must overlap
     * or touch (all but {@code PRECEDES}/{@code PRECEDED_BY}). Sweeping the sorted
     * endpoints maintains the set of currently-active intervals on each side; when
     * an interval opens it is paired with every active interval on the opposite
     * side — exactly the closed-interval-intersecting pairs — and {@link #allenTest}
     * confirms the precise relation.
     */
    private static List<Row> planeSweep(AllenRelation rel, List<Row> lefts, List<Row> rights,
                                        int lStart, int lEnd, int rStart, int rEnd, Schema schema) {
        List<SweepEvent> events = new ArrayList<>((lefts.size() + rights.size()) * 2);
        int seq = 0;
        for (Row row : lefts) {
            Interval iv = new Interval(row.get(lStart), row.get(lEnd), row, seq++);
            events.add(new SweepEvent(iv.start(), true,  true, iv));
            events.add(new SweepEvent(iv.end(),   false, true, iv));
        }
        for (Row row : rights) {
            Interval iv = new Interval(row.get(rStart), row.get(rEnd), row, seq++);
            events.add(new SweepEvent(iv.start(), true,  false, iv));
            events.add(new SweepEvent(iv.end(),   false, false, iv));
        }
        events.sort(EVENT_ORDER);

        List<Row> out = new ArrayList<>();
        Set<Interval> activeLeft  = new LinkedHashSet<>();
        Set<Interval> activeRight = new LinkedHashSet<>();
        for (SweepEvent e : events) {
            Interval iv = e.interval();
            if (!e.open()) {
                (e.left() ? activeLeft : activeRight).remove(iv);
                continue;
            }
            if (e.left()) {
                for (Interval r : activeRight) {
                    if (allenTest(rel, iv.start(), iv.end(), r.start(), r.end())) {
                        out.add(concatRows(iv.row(), r.row(), schema));
                    }
                }
                activeLeft.add(iv);
            } else {
                for (Interval l : activeLeft) {
                    if (allenTest(rel, l.start(), l.end(), iv.start(), iv.end())) {
                        out.add(concatRows(l.row(), iv.row(), schema));
                    }
                }
                activeRight.add(iv);
            }
        }
        return out;
    }

    /** Orders active intervals by their end coordinate, so the soonest-expiring is the heap head. */
    private static final Comparator<Interval> BY_END =
            Comparator.comparing(Interval::end, ValueComparator::compareNonNull);

    /**
     * Streaming sort-merge interval join for the overlap-or-touch relations, used when
     * <b>both</b> inputs already deliver an ascending order on their interval start
     * column (ADR-0009 ordering reuse) — so the global endpoint sort of {@link #planeSweep}
     * is unnecessary.
     *
     * <p>The two start-ordered inputs are merged by start coordinate.  Each side keeps a
     * bounded "active" set of still-open intervals in a min-heap by end.  When a new
     * interval with start {@code s} arrives, every active interval whose end is strictly
     * before {@code s} is purged (it can no longer intersect anything starting at {@code s}
     * or later); the strict bound keeps a touching interval (end == s, e.g. {@code MEETS})
     * alive.  The survivor set on the opposite side is exactly the closed-interval
     * intersections, which {@link #allenTest} confirms precisely.  Equal-start pairs are
     * each tested once — whichever interval is processed second sees the other already in
     * its active set (an equal-start interval is never purged, as its end ≥ s).
     */
    private static List<Row> mergeIntervalJoin(AllenRelation rel, List<Row> lefts, List<Row> rights,
                                               int lStart, int lEnd, int rStart, int rEnd, Schema schema) {
        List<Row> out = new ArrayList<>();
        PriorityQueue<Interval> activeLeft  = new PriorityQueue<>(BY_END);
        PriorityQueue<Interval> activeRight = new PriorityQueue<>(BY_END);
        int i = 0, j = 0, seq = 0;
        while (i < lefts.size() || j < rights.size()) {
            // Take the smaller start next; ties take the left so the merge is deterministic.
            boolean takeLeft = j >= rights.size()
                    || (i < lefts.size()
                        && ValueComparator.compareNonNull(
                               lefts.get(i).get(lStart), rights.get(j).get(rStart)) <= 0);
            if (takeLeft) {
                Row row = lefts.get(i++);
                Interval iv = new Interval(row.get(lStart), row.get(lEnd), row, seq++);
                purgeExpired(activeLeft, iv.start());
                purgeExpired(activeRight, iv.start());
                for (Interval r : activeRight) {
                    if (allenTest(rel, iv.start(), iv.end(), r.start(), r.end())) {
                        out.add(concatRows(iv.row(), r.row(), schema));
                    }
                }
                activeLeft.add(iv);
            } else {
                Row row = rights.get(j++);
                Interval iv = new Interval(row.get(rStart), row.get(rEnd), row, seq++);
                purgeExpired(activeLeft, iv.start());
                purgeExpired(activeRight, iv.start());
                for (Interval l : activeLeft) {
                    if (allenTest(rel, l.start(), l.end(), iv.start(), iv.end())) {
                        out.add(concatRows(l.row(), iv.row(), schema));
                    }
                }
                activeRight.add(iv);
            }
        }
        return out;
    }

    /** Removes every interval whose end is strictly before {@code start} (touch survives). */
    private static void purgeExpired(PriorityQueue<Interval> active, Value start) {
        while (!active.isEmpty()
               && ValueComparator.compareNonNull(active.peek().end(), start) < 0) {
            active.poll();
        }
    }

    /**
     * {@code PRECEDES} (ℓ.end &lt; r.start): with the right rows sorted by start,
     * each left row matches the contiguous suffix whose start is strictly greater
     * than the left end — located by binary search.
     */
    private static List<Row> precedesBand(List<Row> lefts, List<Row> rights,
                                          int lEnd, int rStart, Schema schema) {
        List<Row> byStart = new ArrayList<>(rights);
        byStart.sort((a, b) -> ValueComparator.compareNonNull(a.get(rStart), b.get(rStart)));
        List<Row> out = new ArrayList<>();
        for (Row left : lefts) {
            int from = firstGreater(byStart, rStart, left.get(lEnd));
            for (int i = from; i < byStart.size(); i++) {
                out.add(concatRows(left, byStart.get(i), schema));
            }
        }
        return out;
    }

    /**
     * {@code PRECEDED_BY} (ℓ.start &gt; r.end): with the right rows sorted by end,
     * each left row matches the contiguous prefix whose end is strictly less than
     * the left start — located by binary search.
     */
    private static List<Row> precededByBand(List<Row> lefts, List<Row> rights,
                                            int lStart, int rEnd, Schema schema) {
        List<Row> byEnd = new ArrayList<>(rights);
        byEnd.sort((a, b) -> ValueComparator.compareNonNull(a.get(rEnd), b.get(rEnd)));
        List<Row> out = new ArrayList<>();
        for (Row left : lefts) {
            int upto = firstAtLeast(byEnd, rEnd, left.get(lStart));
            for (int i = 0; i < upto; i++) {
                out.add(concatRows(left, byEnd.get(i), schema));
            }
        }
        return out;
    }

    /** First index whose column value is strictly greater than {@code v} (upper bound). */
    private static int firstGreater(List<Row> rows, int idx, Value v) {
        int lo = 0, hi = rows.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ValueComparator.compareNonNull(rows.get(mid).get(idx), v) > 0) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** First index whose column value is greater than or equal to {@code v} (lower bound). */
    private static int firstAtLeast(List<Row> rows, int idx, Value v) {
        int lo = 0, hi = rows.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ValueComparator.compareNonNull(rows.get(mid).get(idx), v) >= 0) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /**
     * Tests whether the Allen interval algebra relation {@code rel} holds between
     * the left interval {@code [ls, le)} and the right interval {@code [rs, re)}.
     * All comparisons use {@link ValueComparator#compareNonNull}.
     */
    static boolean allenTest(AllenRelation rel,
                             Value ls, Value le, Value rs, Value re) {
        return switch (rel) {
            // ℓ.start < r.end ∧ r.start < ℓ.end  (any overlap)
            case INTERSECTS ->
                    ValueComparator.compareNonNull(ls, re) < 0
                    && ValueComparator.compareNonNull(rs, le) < 0;
            // ℓ.start < r.start ∧ ℓ.end > r.start ∧ ℓ.end < r.end
            case OVERLAPS ->
                    ValueComparator.compareNonNull(ls, rs) < 0
                    && ValueComparator.compareNonNull(le, rs) > 0
                    && ValueComparator.compareNonNull(le, re) < 0;
            // r.start < ℓ.start ∧ r.end > ℓ.start ∧ r.end < ℓ.end  (converse of OVERLAPS)
            case OVERLAPPED_BY ->
                    ValueComparator.compareNonNull(rs, ls) < 0
                    && ValueComparator.compareNonNull(re, ls) > 0
                    && ValueComparator.compareNonNull(re, le) < 0;
            // r.start < ℓ.start ∧ ℓ.end < r.end  (strict: L strictly inside R)
            case DURING ->
                    ValueComparator.compareNonNull(rs, ls) < 0
                    && ValueComparator.compareNonNull(le, re) < 0;
            // ℓ.start < r.start ∧ r.end < ℓ.end  (strict converse of DURING)
            case CONTAINS ->
                    ValueComparator.compareNonNull(ls, rs) < 0
                    && ValueComparator.compareNonNull(re, le) < 0;
            // ℓ.start = r.start ∧ ℓ.end < r.end
            case STARTS ->
                    ValueComparator.compareNonNull(ls, rs) == 0
                    && ValueComparator.compareNonNull(le, re) < 0;
            // ℓ.start = r.start ∧ r.end < ℓ.end  (converse of STARTS)
            case STARTED_BY ->
                    ValueComparator.compareNonNull(ls, rs) == 0
                    && ValueComparator.compareNonNull(re, le) < 0;
            // ℓ.end = r.end ∧ r.start < ℓ.start
            case FINISHES ->
                    ValueComparator.compareNonNull(le, re) == 0
                    && ValueComparator.compareNonNull(rs, ls) < 0;
            // ℓ.end = r.end ∧ ℓ.start < r.start  (converse of FINISHES)
            case FINISHED_BY ->
                    ValueComparator.compareNonNull(le, re) == 0
                    && ValueComparator.compareNonNull(ls, rs) < 0;
            // ℓ.start = r.start ∧ ℓ.end = r.end
            case EQUALS ->
                    ValueComparator.compareNonNull(ls, rs) == 0
                    && ValueComparator.compareNonNull(le, re) == 0;
            // ℓ.end = r.start  (adjacent)
            case MEETS ->
                    ValueComparator.compareNonNull(le, rs) == 0;
            // ℓ.start = r.end  (adjacent; converse of MEETS)
            case MET_BY ->
                    ValueComparator.compareNonNull(ls, re) == 0;
            // ℓ.end < r.start
            case PRECEDES ->
                    ValueComparator.compareNonNull(le, rs) < 0;
            // ℓ.start > r.end  (converse of PRECEDES)
            case PRECEDED_BY ->
                    ValueComparator.compareNonNull(ls, re) > 0;
        };
    }

    /** Evaluates a join condition over a left/right pair, resolving qualified names per side. */
    private record JoinMatcher(PredicateEvaluator eval, Predicate condition,
                               Schema leftSchema, Schema rightSchema,
                               Set<String> leftRelations, Set<String> rightRelations, Schema evalSchema) {
        boolean matches(Row leftRow, Row rightRow) {
            return eval.evaluate(condition, new QualifiedRow(
                    leftRow, rightRow, leftSchema, rightSchema, leftRelations, rightRelations, evalSchema));
        }
    }

}
