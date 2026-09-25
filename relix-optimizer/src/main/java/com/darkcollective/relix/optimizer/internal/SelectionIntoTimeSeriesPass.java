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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * Partition-pruning pass (ADR-0020) for the two time-series operators
 * ({@code SESSION-001} / {@code DOWNSAMPLE-001}).
 *
 * <pre>
 *   σ k = c (SESSIONIZE ts GAP … PER k AS s (R))  →  SESSIONIZE … PER k AS s (σ k = c (R))
 *   σ k = c (DOWNSAMPLE ts BY '5m' USING AVG PER k (R))
 *                                                 →  DOWNSAMPLE … PER k (σ k = c (R))
 * </pre>
 *
 * <p>Both compute independently per partition — a session boundary never spans a
 * {@code PER} key, and a bucket is keyed by ({@code PER} keys, bucket) — which is the
 * identical soundness argument ADR-0020 already accepted for {@code WINDOW},
 * {@code TOP} and {@code OPTIMIZE}.  Both are also
 * {@link com.darkcollective.relix.ast.MaterializationMode#BAG}: they buffer and process
 * <em>every</em> partition to produce the one the query asked for, so the saving is real
 * work, not just rows discarded a little later.
 *
 * <p>The descriptor and the traversal are {@link PartitionPruning}'s; this class supplies
 * only the two node shapes.
 *
 * <h2>{@code FOR n ROWS} blocks the DOWNSAMPLE push</h2>
 * <p>{@code DownsampleNode}'s {@code maxRows} keeps the {@code n} most recent buckets
 * <strong>globally</strong>, not per group — {@code DownsampleExecutor} sorts all output
 * rows by bucket descending and takes the first {@code n}.  So with two groups and
 * {@code FOR 2 ROWS}, filtering afterwards can leave one row while pushing the filter
 * first would produce two.  The rule therefore does not fire at all when
 * {@code maxRows} is present.  (The issue did not flag this; it is a genuine
 * counterexample rather than a conservative choice.)
 *
 * <p>Out of scope, and noted as a follow-on: a predicate on {@code DOWNSAMPLE}'s
 * <em>timestamp</em> column.  It is a <em>range</em> restriction on the buckets rather
 * than a partition selection — a different and more interesting rewrite, which has to
 * reason about bucket boundaries rather than just about which groups exist.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static
 * method.
 */
public final class SelectionIntoTimeSeriesPass {

    private SelectionIntoTimeSeriesPass() {}

    /**
     * Applies partition pruning (SESSION-001 / DOWNSAMPLE-001) to the entire tree rooted
     * at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations (not used — the keys are read off the operator)
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return PartitionPruning.apply(node, queryName, ctx, SelectionIntoTimeSeriesPass::target);
    }

    /** The two time-series operators, as {@link PartitionPruning} describes them. */
    private static PartitionPruning.Target target(RelNode node) {
        return switch (node) {
            case SessionizeNode s -> PartitionPruning.Target.of(
                    OptimizationCode.SESSION_001, s.partitionKeys(), s.input(),
                    input -> new SessionizeNode(input, s.orderColumn(), s.threshold(),
                            s.partitionKeys(), s.sessionColumn(), s.location()),
                    "partition", "SESSIONIZE", "other partitions not sessionized");

            // FOR n ROWS takes the n most recent buckets across *all* groups, so pushing
            // a group filter below it changes which buckets survive.
            case DownsampleNode d when d.maxRows().isEmpty() -> PartitionPruning.Target.of(
                    OptimizationCode.DOWNSAMPLE_001, d.groupingKeys(), d.input(),
                    input -> new DownsampleNode(d.timestampColumn(), d.interval(), d.function(),
                            d.groupingKeys(), d.maxRows(), input, d.location()),
                    "group", "DOWNSAMPLE", "other groups not bucketed");

            default -> null;
        };
    }
}
