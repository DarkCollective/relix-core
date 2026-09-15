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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.cost.BoundednessChecker;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.plan.BoundednessException;
import com.darkcollective.relix.processor.generator.GeneratorBoundednessSource;
import com.darkcollective.relix.processor.generator.GeneratorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Collecting a relation that never ends must fail loudly rather than fill memory.
 *
 * <p>The planner's {@code BoundednessChecker} cannot cover this: it looks for a blocking
 * <em>operator</em> over an unbounded input, and here the blocking is done by the
 * <em>consumer</em>. {@code σ n > 5 (Naturals)} contains no blocking node at all, so the
 * plan is perfectly legal — and {@code QueryExecutor.execute}, which collects each result
 * into a list, would never return. The first test below asserts both halves of that: the
 * planner's check passes the tree, and the collecting guard rejects it.
 */
@DisplayName("Collecting an unbounded relation is refused")
final class UnboundedCollectGuardTest {

    private final GeneratorRegistry registry = new GeneratorRegistry();

    /** A boundedness source in which {@code Naturals} is the unbounded generator. */
    private BoundednessSource naturals() {
        var decl = source(true, "Naturals",
                generatorSource("Naturals", Map.of()));
        return new GeneratorBoundednessSource(Map.of("naturals", decl), registry);
    }

    /** σ n > 5 (Naturals) — endless, and containing no blocking operator. */
    private static RelNode endlessWithoutABlockingOperator() {
        return select(
                cmp(attr("n"),
                        ComparisonOperator.GREATER, num("5")),
                rel("Naturals"));
    }

    @Test
    @DisplayName("the planner's check passes it, and the collecting guard does not")
    void guardsWhatThePlannerCannotSee() {
        RelNode endless = endlessWithoutABlockingOperator();

        assertThat(BoundednessChecker.check(endless, naturals()))
                .as("no blocking operator, so the plan is legal — this is the gap")
                .isEmpty();

        assertThatThrownBy(() -> QueryExecutor.requireBounded(List.of(endless), naturals()))
                .isInstanceOf(BoundednessException.class)
                .hasMessageContaining("add a bound");
    }

    @Test
    @DisplayName("a bound makes it collectable")
    void allowsABoundedRelation() {
        RelNode bounded = limit(10L, rel("Naturals"));

        assertThatCode(() -> QueryExecutor.requireBounded(List.of(bounded), naturals()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an ordinary relation is unaffected")
    void allowsABoundedLeaf() {
        assertThatCode(() -> QueryExecutor.requireBounded(
                List.of(rel("Orders")), naturals()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the message names which query, since a script may state several")
    void namesTheOffendingQuery() {
        assertThatThrownBy(() -> QueryExecutor.requireBounded(
                List.of(rel("Orders"), endlessWithoutABlockingOperator()),
                naturals()))
                .hasMessageContaining("query 2");
    }

    @Test
    @DisplayName("a blocking operator over it is still the planner's to reject, not this guard's")
    void leavesBlockingOperatorsToThePlanner() {
        // γ COUNT(n) (Naturals) never ends either, but the planner already refuses it —
        // and would refuse it for a *streaming* caller too, which this guard must not.
        RelNode blocking = groupBy(
                List.of(),
                List.of(AggregateFunction.simple(AggregateOperator.COUNT, "n")),
                rel("Naturals"));

        assertThat(BoundednessChecker.check(blocking, naturals()))
                .as("the planner owns this one")
                .isNotEmpty();
    }
}
