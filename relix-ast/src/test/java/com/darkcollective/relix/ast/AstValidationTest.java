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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Argument validation on the AST's own constructors.
 *
 * <p>Every record here rejects a malformed component before it can reach a later phase,
 * and each rejection is the guard an embedder building an AST by hand runs into first —
 * so the arm that throws is part of the contract, not an internal detail. The two shapes
 * are kept apart deliberately: a {@code null} is a {@link NullPointerException} from
 * {@code Objects.requireNonNull}, a present-but-empty value an
 * {@link IllegalArgumentException}, and a caller distinguishing them is relying on
 * exactly that split.
 */
@DisplayName("AST node argument validation")
final class AstValidationTest extends AstTestSupport {

    private static final RelNode INPUT = new RelationNode("Users");

    @Nested
    @DisplayName("Core relational nodes")
    final class Core {

        @Test
        @DisplayName("Rejects blank relation name")
        void rejectsBlankRelationName() {
            assertThatIllegalArgumentException().isThrownBy(() -> new RelationNode(" "));
        }

        @Test
        @DisplayName("Rejects empty projection attributes list")
        void rejectsEmptyProjectionAttributes() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ProjectionNode(List.of(), INPUT));
        }

        @Test
        @DisplayName("Rejects null selection predicate")
        void rejectsNullSelectionPredicate() {
            assertThatNullPointerException().isThrownBy(() -> new SelectionNode(null, INPUT));
        }

        @Test
        @DisplayName("Rejects null left operand in UnionAll")
        void rejectsNullUnionAllLeft() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UnionAllNode(null, new RelationNode("B")));
        }

        @Test
        @DisplayName("Rejects null right operand in UnionAll")
        void rejectsNullUnionAllRight() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new UnionAllNode(new RelationNode("A"), null));
        }

        @Test
        @DisplayName("Rejects null input for Distinct")
        void rejectsNullDistinctInput() {
            assertThatNullPointerException().isThrownBy(() -> new DistinctNode(null));
        }
    }

    @Nested
    @DisplayName("RenameNode")
    final class Rename {

        @Test
        @DisplayName("Rejects a rename that renames nothing at all")
        void rejectsAnEmptyRename() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RenameNode(Optional.empty(), List.of(), List.of(),
                            INPUT, SourceLocation.UNKNOWN))
                    .withMessageContaining("must supply a new relation name");
        }

        @Test
        @DisplayName("Rejects mixing positional attributes with old→new pairs")
        void rejectsMixedForms() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RenameNode(Optional.of("R"), List.of("a"),
                            List.of(new RenameNode.RenamePair("a", "b")), INPUT, SourceLocation.UNKNOWN))
                    .withMessageContaining("cannot mix");
        }

        @Test
        @DisplayName("Any one of the three forms alone is enough")
        void anyOneFormIsEnough() {
            assertThat(new RenameNode(Optional.of("R"), List.of(), List.of(), INPUT,
                    SourceLocation.UNKNOWN).relationName()).contains("R");
            assertThat(new RenameNode(Optional.empty(), List.of("a"), List.of(), INPUT,
                    SourceLocation.UNKNOWN).attributes()).containsExactly("a");
            assertThat(new RenameNode(Optional.empty(), List.of(),
                    List.of(new RenameNode.RenamePair("a", "b")), INPUT, SourceLocation.UNKNOWN).pairs())
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("ProjectionNode.isColumnPruning")
    final class ColumnPruning {

        @Test
        @DisplayName("A projection of bare columns only is column-pruning")
        void bareColumnsArePruning() {
            var p = new ProjectionNode(List.of(
                    ProjectedAttribute.simple(new AttributeOperand("a")),
                    ProjectedAttribute.simple(new AttributeOperand("b"))), INPUT);
            assertThat(p.isColumnPruning()).isTrue();
        }

        @Test
        @DisplayName("An aliased column is not — it introduces a name the source lacks")
        void anAliasIsNotPruning() {
            var p = new ProjectionNode(List.of(
                    ProjectedAttribute.aliased(new AttributeOperand("a"), "x")), INPUT);
            assertThat(p.isColumnPruning()).isFalse();
        }

        @Test
        @DisplayName("An unaliased computed expression is not either")
        void anUnaliasedExpressionIsNotPruning() {
            // The alias test passes here and the expression test is what rejects it —
            // the arm an aliased-only counter-example never reaches.
            var p = new ProjectionNode(List.of(
                    ProjectedAttribute.simple(new BinaryArithmeticExpression(
                            new AttributeOperand("a"), ArithmeticOperator.PLUS,
                            new NumberOperand("1")))), INPUT);
            assertThat(p.isColumnPruning()).isFalse();
        }
    }

    @Nested
    @DisplayName("AllocationSpec")
    final class Allocation {

        @Test
        void rejectsNonFiniteLowerBound() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AllocationSpec(Double.NaN, 1.0, "weight"))
                    .withMessageContaining("lo must be finite");
        }

        @Test
        void rejectsNonFiniteUpperBound() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AllocationSpec(0.0, Double.POSITIVE_INFINITY, "weight"))
                    .withMessageContaining("hi must be finite");
        }

        @Test
        void rejectsInvertedBounds() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AllocationSpec(2.0, 1.0, "weight"))
                    .withMessageContaining("lo must be ≤ hi");
        }

        @Test
        void rejectsBlankColumnName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AllocationSpec(0.0, 1.0, "  "))
                    .withMessageContaining("columnName must not be blank");
        }

        @Test
        void rejectsNullColumnName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AllocationSpec(0.0, 1.0, null));
        }

        @Test
        @DisplayName("A degenerate but finite range is accepted — lo ≤ hi, not lo < hi")
        void acceptsDegenerateRange() {
            assertThat(new AllocationSpec(1.0, 1.0, "weight").lo()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("DownsampleNode")
    final class Downsample {

        private DownsampleNode node(String timestampColumn, String interval, OptionalLong maxRows) {
            return new DownsampleNode(timestampColumn, interval, ConsolidationFunction.AVG,
                    List.of(), maxRows, INPUT);
        }

        @Test
        void rejectsBlankTimestampColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> node(" ", "5m", OptionalLong.empty()))
                    .withMessageContaining("timestampColumn must not be blank");
        }

        @Test
        void rejectsBlankInterval() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> node("at", "", OptionalLong.empty()))
                    .withMessageContaining("interval must not be blank");
        }

        @Test
        @DisplayName("Rejects a maxRows below one — a bucket limit of zero asks for no output")
        void rejectsNonPositiveMaxRows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> node("at", "5m", OptionalLong.of(0)))
                    .withMessageContaining("maxRows must be ≥ 1");
        }

        @Test
        @DisplayName("An absent maxRows never reaches the bound check")
        void acceptsAbsentMaxRows() {
            assertThat(node("at", "5m", OptionalLong.empty()).maxRows()).isEmpty();
        }

        @Test
        @DisplayName("A maxRows of one is the smallest accepted limit")
        void acceptsMaxRowsOfOne() {
            assertThat(node("at", "5m", OptionalLong.of(1)).maxRows()).hasValue(1);
        }
    }

    @Nested
    @DisplayName("DownsampleNode.parseIntervalSeconds")
    final class IntervalParsing {

        @Test
        void parsesEveryShorthandUnit() {
            assertThat(DownsampleNode.parseIntervalSeconds("30s")).isEqualTo(30);
            assertThat(DownsampleNode.parseIntervalSeconds("5m")).isEqualTo(300);
            assertThat(DownsampleNode.parseIntervalSeconds("2h")).isEqualTo(7_200);
            assertThat(DownsampleNode.parseIntervalSeconds("3d")).isEqualTo(259_200);
            assertThat(DownsampleNode.parseIntervalSeconds("1w")).isEqualTo(604_800);
        }

        @Test
        void parsesIso8601Durations() {
            assertThat(DownsampleNode.parseIntervalSeconds("PT5M")).isEqualTo(300);
            assertThat(DownsampleNode.parseIntervalSeconds("P1D")).isEqualTo(86_400);
        }

        @Test
        @DisplayName("Surrounding whitespace is trimmed before matching")
        void trimsBeforeMatching() {
            assertThat(DownsampleNode.parseIntervalSeconds("  15m  ")).isEqualTo(900);
        }

        @Test
        @DisplayName("A shorthand that resolves to zero is rejected, not returned")
        void rejectsZeroShorthand() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DownsampleNode.parseIntervalSeconds("0m"))
                    .withMessageContaining("Interval must be positive");
        }

        @Test
        @DisplayName("An ISO-8601 duration that resolves to zero is rejected on the same rule")
        void rejectsZeroIsoDuration() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DownsampleNode.parseIntervalSeconds("PT0S"))
                    .withMessageContaining("Interval must be positive");
        }

        @Test
        @DisplayName("A negative ISO-8601 duration is rejected on the same rule")
        void rejectsNegativeIsoDuration() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DownsampleNode.parseIntervalSeconds("PT-5M"))
                    .withMessageContaining("Interval must be positive");
        }

        @Test
        @DisplayName("Neither shorthand nor ISO-8601 reports both spellings")
        void rejectsUnparseableInterval() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DownsampleNode.parseIntervalSeconds("fortnightly"))
                    .withMessageContaining("Cannot parse interval")
                    .withMessageContaining("ISO-8601");
        }

        @Test
        @DisplayName("A unit outside the shorthand alphabet falls through to the ISO-8601 branch")
        void unknownUnitIsNotShorthand() {
            // The shorthand pattern admits only [smhdw], so "5y" cannot match it; the
            // switch's default arm is therefore unreachable and the ISO parser reports.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DownsampleNode.parseIntervalSeconds("5y"))
                    .withMessageContaining("Cannot parse interval");
        }
    }

    @Nested
    @DisplayName("GroupingKey")
    final class Grouping {

        @Test
        void rejectsBlankAlias() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GroupingKey.aliased(new AttributeOperand("a"), " "))
                    .withMessageContaining("alias cannot be blank");
        }

        @Test
        @DisplayName("An absent alias never reaches the blank check")
        void acceptsAbsentAlias() {
            assertThat(GroupingKey.column("a").alias()).isEmpty();
        }

        @Test
        void rejectsNullAlias() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GroupingKey(new AttributeOperand("a"), null));
        }
    }

    @Nested
    @DisplayName("LateralJoinNode")
    final class Lateral {

        @Test
        void rejectsBlankFunctionName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LateralJoinNode(INPUT, " ", List.of()))
                    .withMessageContaining("functionName must not be blank");
        }

        @Test
        void rejectsNullFunctionName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new LateralJoinNode(INPUT, null, List.of()));
        }
    }

    @Nested
    @DisplayName("PivotNode")
    final class Pivot {

        @Test
        void rejectsBlankValueColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PivotNode(" ", "k", List.of("g"), INPUT))
                    .withMessageContaining("valueColumn must not be blank");
        }

        @Test
        void rejectsBlankKeyColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PivotNode("v", "", List.of("g"), INPUT))
                    .withMessageContaining("keyColumn must not be blank");
        }
    }

    @Nested
    @DisplayName("UnpivotNode")
    final class Unpivot {

        @Test
        @DisplayName("Rejects an empty column list — there is nothing to fold")
        void rejectsEmptyColumns() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UnpivotNode(List.of(), "n", "v", INPUT))
                    .withMessageContaining("columns list must not be empty");
        }

        @Test
        void rejectsBlankNameColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UnpivotNode(List.of("q1"), " ", "v", INPUT))
                    .withMessageContaining("nameColumn must not be blank");
        }

        @Test
        void rejectsBlankValueColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UnpivotNode(List.of("q1"), "n", "", INPUT))
                    .withMessageContaining("valueColumn must not be blank");
        }
    }

    @Nested
    @DisplayName("UnnestNode")
    final class Unnest {

        @Test
        void rejectsBlankColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UnnestNode(" ", INPUT))
                    .withMessageContaining("Unnest column must not be blank");
        }

        @Test
        void rejectsBlankOrdinalityColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UnnestNode("items", false, Optional.of(" "),
                            INPUT, SourceLocation.UNKNOWN))
                    .withMessageContaining("ordinality column must not be blank");
        }

        @Test
        @DisplayName("An absent ordinality column never reaches the blank check")
        void acceptsAbsentOrdinalityColumn() {
            assertThat(new UnnestNode("items", INPUT).ordinalityColumn()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Sampling — the seedless convenience constructors")
    final class SamplingDefaults {

        @Test
        @DisplayName("SAMPLE without a seed defaults to none, and is therefore volatile")
        void bernoulliSeedDefaultsToEmpty() {
            var node = new SampleNode(0.25, INPUT, SourceLocation.UNKNOWN);
            assertThat(node.seed()).isEmpty();
            assertThat(node.probability()).isEqualTo(0.25);
            assertThat(node.input()).isSameAs(INPUT);
        }

        @Test
        @DisplayName("SAMPLE n ROWS without a seed defaults to none")
        void reservoirSeedDefaultsToEmpty() {
            var node = new ReservoirSampleNode(50, INPUT, SourceLocation.UNKNOWN);
            assertThat(node.seed()).isEmpty();
            assertThat(node.count()).isEqualTo(50);
            assertThat(node.input()).isSameAs(INPUT);
        }
    }

    @Nested
    @DisplayName("ReservoirSampleNode")
    final class Reservoir {

        @Test
        void rejectsNegativeCount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReservoirSampleNode(-1, INPUT))
                    .withMessageContaining("Count cannot be negative");
        }

        @Test
        @DisplayName("A count of zero is legal — an empty sample is a sample")
        void acceptsZeroCount() {
            assertThat(new ReservoirSampleNode(0, INPUT).count()).isZero();
        }
    }

    @Nested
    @DisplayName("TraceNode")
    final class Trace {

        private TraceNode trace(String from, String to, String weight, String path) {
            return new TraceNode(INPUT, from, to, weight, ObjectiveSense.MINIMIZE, path);
        }

        @Test
        void rejectsBlankFromColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> trace(" ", "dst", "cost", "route"))
                    .withMessageContaining("fromColumn must not be blank");
        }

        @Test
        void rejectsBlankToColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> trace("src", "", "cost", "route"))
                    .withMessageContaining("toColumn must not be blank");
        }

        @Test
        void rejectsBlankWeightColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> trace("src", "dst", " ", "route"))
                    .withMessageContaining("weightColumn must not be blank");
        }

        @Test
        void rejectsBlankPathColumn() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> trace("src", "dst", "cost", ""))
                    .withMessageContaining("pathColumn must not be blank");
        }
    }

    @Nested
    @DisplayName("StructConstruction.Field")
    final class StructField {

        @Test
        void rejectsBlankFieldName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StructConstruction.Field(" ", new NumberOperand("1")))
                    .withMessageContaining("field name must not be blank");
        }

        @Test
        void rejectsNullFieldName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new StructConstruction.Field(null, new NumberOperand("1")));
        }

        @Test
        void rejectsNullFieldValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new StructConstruction.Field("qty", null));
        }
    }
}
