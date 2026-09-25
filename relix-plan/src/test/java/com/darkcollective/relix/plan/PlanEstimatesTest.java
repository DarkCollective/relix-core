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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.plan.internal.PhysicalPlanJson;
import com.darkcollective.relix.plan.internal.PhysicalPlanPrinter;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PlanEstimates} and the estimate column the plan renderers add.
 */
@DisplayName("PlanEstimates")
final class PlanEstimatesTest {

    private static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("x", ScalarType.NUMBER)));

    private static PhysicalNode.Scan scan(String name) {
        return new PhysicalNode.Scan(SCHEMA, InlineRelationSymbol.of(name, SCHEMA, List.of()));
    }

    @Test
    @DisplayName("a recorded estimate comes back")
    void recordAndRead() {
        var estimates = new PlanEstimates();
        var node = scan("R");
        estimates.record(node, OptionalLong.of(42));
        assertThat(estimates.rows(node)).hasValue(42L);
        assertThat(estimates.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("an absent estimate is not stored — unknown has one representation")
    void absentIsNotStored() {
        var estimates = new PlanEstimates();
        estimates.record(scan("R"), OptionalLong.empty());
        assertThat(estimates.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an unrecorded node reads as unknown, not as zero")
    void unknownIsNotZero() {
        var estimates = new PlanEstimates();
        estimates.record(scan("R"), OptionalLong.of(7));
        assertThat(estimates.rows(scan("Other"))).isEmpty();
    }

    @Test
    @DisplayName("zero is a real answer and survives the round trip")
    void zeroIsRecordable() {
        var estimates = new PlanEstimates();
        var node = new PhysicalNode.Empty(SCHEMA);
        estimates.record(node, OptionalLong.of(0));
        assertThat(estimates.rows(node)).hasValue(0L);
    }

    @Test
    @DisplayName("keys are compared by identity — two equal Scans do not collide")
    void identityKeyed() {
        // `A ⨝ A` is a legal self-join and its two Scans are equal records; keying on
        // equality would let one side's estimate overwrite the other's.
        var left = scan("R");
        var right = scan("R");
        assertThat(left).isEqualTo(right);
        var estimates = new PlanEstimates();
        estimates.record(left, OptionalLong.of(10));
        estimates.record(right, OptionalLong.of(20));
        assertThat(estimates.rows(left)).hasValue(10L);
        assertThat(estimates.rows(right)).hasValue(20L);
    }

    @Test
    @DisplayName("none() knows nothing and is empty")
    void noneIsEmpty() {
        assertThat(PlanEstimates.none().isEmpty()).isTrue();
        assertThat(PlanEstimates.none().rows(scan("R"))).isEmpty();
    }

    @Test
    @DisplayName("null arguments are rejected")
    void nullGuards() {
        var estimates = new PlanEstimates();
        assertThatThrownBy(() -> estimates.record(null, OptionalLong.of(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> estimates.record(scan("R"), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> estimates.rows(null))
                .isInstanceOf(NullPointerException.class);
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Test
    @DisplayName("the ASCII tree adds no column when nothing was estimated")
    void printerOmitsTheColumnWhenEmpty() {
        assertThat(PhysicalPlanPrinter.explain(scan("R"), PlanEstimates.none()))
                .isEqualTo("Scan R\n");
    }

    @Test
    @DisplayName("the ASCII tree distinguishes ~0 rows from ~? rows")
    void printerDistinguishesUnknownFromZero() {
        var known = scan("Known");
        var unknown = scan("Unknown");
        var join = new PhysicalNode.SetOp(SCHEMA, PhysicalNode.SetKind.UNION_ALL, known, unknown);
        var estimates = new PlanEstimates();
        estimates.record(join, OptionalLong.of(0));
        estimates.record(known, OptionalLong.of(0));
        // `unknown` is deliberately not recorded.

        assertThat(PhysicalPlanPrinter.explain(join, estimates)).isEqualTo("""
                SetOp UNION_ALL  ~0 rows
                ├─ Scan Known  ~0 rows
                └─ Scan Unknown  ~? rows
                """);
    }

    @Test
    @DisplayName("the JSON writes a number when known and null when not")
    void jsonDistinguishesUnknownFromZero() {
        var node = scan("R");
        var estimates = new PlanEstimates();
        estimates.record(node, OptionalLong.of(0));
        assertThat(PhysicalPlanJson.toJson(node, estimates)).contains("\"estimatedRows\":0");
        assertThat(PhysicalPlanJson.toJson(node)).contains("\"estimatedRows\":null");
    }
}
