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

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.List;
import java.util.Objects;

/**
 * Group-wise universal quantification (∀) — partitions {@code input} by the
 * {@code groupingAttributes} and keeps the grouping-key tuple of each group in
 * which <em>every</em> row satisfies {@code predicate}.
 *
 * <p>This is the "every row of the group satisfies P" operation that SQL forces
 * into the {@code NOT EXISTS (… NOT P)} double-negation — for example, "customers
 * all of whose orders are completed":
 *
 * <pre>{@code ∀ customer_id : status = "completed" (Orders)}</pre>
 *
 * <p><b>Strict NULL semantics:</b> a row whose predicate evaluates to UNKNOWN
 * counts as not-satisfied and disqualifies its group (the {@code NOT EXISTS}
 * reading, not {@code bool_and}).
 *
 * <p>The output schema is the grouping-key columns only (member rows are
 * recovered with a semi-join back). With <em>no</em> grouping attributes the
 * operator is the whole-relation form {@code ∀ : P (R)} — "does every row of
 * {@code R} satisfy {@code P}?" — whose output is the nullary truth relation
 * (the empty schema, {@code Schema.empty()} — relix-symbol, which this module
 * deliberately does not depend on, hence no link):
 * one empty tuple when every row satisfies {@code P} (vacuously true on an empty
 * input), no tuple otherwise.
 *
 * @param groupingAttributes the attributes to group by; empty for the
 *                           whole-relation truth form
 * @param predicate          the condition every row of a group must satisfy; never null
 * @param input              the source relation; never null
 * @param location           the source location of this node; never null
 */
public record UniversalNode(
        List<String> groupingAttributes,
        Predicate predicate,
        RelNode input,
        SourceLocation location
) implements RelNode {
    public UniversalNode {
        Objects.requireNonNull(groupingAttributes, "groupingAttributes");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        groupingAttributes = List.copyOf(groupingAttributes);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public UniversalNode(List<String> groupingAttributes, Predicate predicate, RelNode input) {
        this(groupingAttributes, predicate, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
