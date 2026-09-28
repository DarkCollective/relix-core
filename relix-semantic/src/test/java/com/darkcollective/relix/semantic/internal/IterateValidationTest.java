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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.IterateStop;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation and typing of {@code ITERATE}: the step rules it drops ({@code FIX}'s
 * monotonicity and linearity), the ones it keeps or adds (union-compatibility, at least one
 * reference, determinism), the stop clause's columns, and how it reads to an enclosing
 * {@code FIX}.
 */
final class IterateValidationTest {

    private InMemorySymbolTable table;
    private SchemaAnnotations annotations;

    @BeforeEach
    void setUp() {
        table = new InMemorySymbolTable();
        annotations = new SchemaAnnotations();
        register("Scores", col("node", ScalarType.STRING), col("rank", ScalarType.NUMBER));
        register("Labels", col("node", ScalarType.STRING), col("rank", ScalarType.NUMBER),
                col("label", ScalarType.STRING));
        register("Edges", col("src", ScalarType.NUMBER), col("dst", ScalarType.NUMBER));
    }

    private void register(String name, ColumnDefinition... cols) {
        table.register(new SourceRelationSymbol("default", name, Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(cols))));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    private List<SemanticError> errors(RelNode tree) {
        tree.accept(new SchemaInferenceVisitor(table, annotations, new ArrayList<>(), "<test>",
                SemanticFixtures.FUNCTIONS));
        List<SemanticError> errors = new ArrayList<>();
        tree.accept(new RelAlgebraValidator(table, annotations, SemanticFixtures.FUNCTIONS,
                errors, "<test>"));
        return errors;
    }

    private static List<String> messages(List<SemanticError> errors) {
        return errors.stream().map(SemanticError::message).toList();
    }

    private static RelNode over(String base, RelNode step, IterateStop stop) {
        return iterate("R", rel(base), step, stop);
    }

    private static IterateStop.Converged until(List<String> columns, List<String> keys) {
        return untilConverged(columns, new BigDecimal("0.01"), keys, 10);
    }

    @Nested
    class TheStep {

        @Test
        void mayUseOperatorsFixForbids() {
            // Difference, and an aggregate under an outer join: both non-monotone.
            RelNode step = difference(rel("Scores"), recRef("R"));
            assertThat(errors(over("Scores", step, rounds(3)))).isEmpty();

            RelNode totals = groupBy(List.of("node"), List.of(agg(AggregateOperator.SUM, "rank", "rank")),
                    recRef("R"));
            RelNode joined = project(attrs("node", "rank"),
                    leftJoin(rel("Scores"), rename("T", List.of("tnode", "total"), totals),
                            cmp(attr("Scores.node"), ComparisonOperator.EQUAL, attr("T.tnode"))));
            assertThat(errors(over("Scores", joined, untilStable(5)))).isEmpty();
        }

        @Test
        void mayReadTheNameMoreThanOnce() {
            assertThat(errors(over("Scores", union(recRef("R"), recRef("R")), rounds(1)))).isEmpty();
        }

        @Test
        void mustReadTheNameAtLeastOnce() {
            assertThat(messages(errors(over("Scores", rel("Scores"), rounds(2)))))
                    .anySatisfy(m -> assertThat(m).contains("ITERATE 'R' does not iterate"));
        }

        @Test
        void mustBeUnionCompatibleWithTheBase() {
            assertThat(messages(errors(over("Scores", project(attrs("node"), recRef("R")), rounds(1)))))
                    .anySatisfy(m -> assertThat(m).contains("ITERATE 'R': step is not union-compatible")
                            .contains("base has 2 column(s), step has 1"));
        }

        @Test
        void mustBeUnionCompatibleColumnByColumn() {
            RelNode swapped = project(attrs("rank", "node"), recRef("R"));
            assertThat(messages(errors(over("Scores", swapped, rounds(1)))))
                    .anySatisfy(m -> assertThat(m).contains("ITERATE 'R'").contains("type mismatch"));
        }

        @Test
        void mustBeDeterministic() {
            RelNode random = project(List.of(projected(attr("node")), projected(func("Rand"), "rank")),
                    recRef("R"));
            assertThat(messages(errors(over("Scores", random, untilStable(5)))))
                    .anySatisfy(m -> assertThat(m).contains("must give the same result"));
        }

        @Test
        void mayNotSampleWithoutASeed() {
            assertThat(messages(errors(over("Scores", sample(0.5, recRef("R")), untilStable(5)))))
                    .anySatisfy(m -> assertThat(m).contains("must give the same result"));
        }

        @Test
        void maySampleWithASeed() {
            assertThat(errors(over("Scores", sample(0.5, Optional.of(7L), recRef("R")), untilStable(5))))
                    .isEmpty();
        }
    }

    @Nested
    class TheStopClause {

        @Test
        void acceptsNumericColumnsAndExistingKeys() {
            assertThat(errors(over("Scores", recRef("R"), until(List.of("rank"), List.of("node")))))
                    .isEmpty();
        }

        @Test
        void acceptsAnUntypedColumn() {
            register("Untyped", col("node", ScalarType.STRING), col("rank", ScalarType.ANY));
            assertThat(errors(over("Untyped", recRef("R"), until(List.of("rank"), List.of("node")))))
                    .isEmpty();
        }

        @Test
        void refusesAnUnknownColumn() {
            assertThat(messages(errors(over("Scores", recRef("R"), until(List.of("score"), List.of("node"))))))
                    .anySatisfy(m -> assertThat(m).contains("UNTIL column").contains("score"));
        }

        @Test
        void refusesANonNumericColumn() {
            assertThat(messages(errors(over("Labels", recRef("R"), until(List.of("label"), List.of("node"))))))
                    .anySatisfy(m -> assertThat(m).contains("UNTIL column 'label' must be NUMBER"));
        }

        @Test
        void refusesAnUnknownKey() {
            assertThat(messages(errors(over("Scores", recRef("R"), until(List.of("rank"), List.of("id"))))))
                    .anySatisfy(m -> assertThat(m).contains("PER key column").contains("id"));
        }

        @Test
        void refusesAColumnNamedTwice() {
            assertThat(messages(errors(over("Scores", recRef("R"),
                    until(List.of("rank", "RANK"), List.of("node"))))))
                    .anySatisfy(m -> assertThat(m).contains("UNTIL column 'RANK' is named more than once"));
        }

        @Test
        void refusesAKeyNamedTwice() {
            assertThat(messages(errors(over("Scores", recRef("R"),
                    until(List.of("rank"), List.of("node", "node"))))))
                    .anySatisfy(m -> assertThat(m).contains("PER key column 'node' is named more than once"));
        }

        @Test
        void refusesAColumnThatIsAlsoAKey() {
            assertThat(messages(errors(over("Scores", recRef("R"),
                    until(List.of("rank"), List.of("node", "rank"))))))
                    .anySatisfy(m -> assertThat(m).contains("'rank' is both an UNTIL column and a PER key"));
        }

        @Test
        void checksNoColumnsOfASchemaReadFromTheData() {
            table.register(new SourceRelationSymbol("default", "Open", Provenance.BUILTIN,
                    ShadowPolicy.PERMITTED, Schema.open()));
            assertThat(errors(over("Open", recRef("R"), until(List.of("anything"), List.of("key")))))
                    .isEmpty();
        }

        @Test
        void isNotCheckedWhenTheBaseDoesNotResolve() {
            assertThat(messages(errors(over("Missing", recRef("R"), until(List.of("x"), List.of("k"))))))
                    .noneSatisfy(m -> assertThat(m).contains("UNTIL"));
        }
    }

    @Nested
    class Typing {

        @Test
        void theOutputAndTheReferenceCarryTheBaseSchema() {
            RelNode ref = recRef("R");
            RelNode tree = over("Scores", project(attrs("node", "rank"), ref), rounds(1));
            errors(tree);
            assertThat(annotations.get(tree)).get()
                    .extracting(s -> s.columns().stream().map(ColumnDefinition::name).toList())
                    .isEqualTo(List.of("node", "rank"));
            assertThat(annotations.get(ref)).isEqualTo(annotations.get(tree));
        }

        @Test
        void anUnresolvableBaseLeavesItUntyped() {
            RelNode tree = over("Missing", recRef("R"), rounds(1));
            errors(tree);
            assertThat(annotations.get(tree)).isEmpty();
        }
    }

    @Nested
    class InsideAFix {

        @Test
        void aFixReferenceInItsStepIsNonMonotone() {
            RelNode tree = fixpoint("F", rel("Edges"),
                    iterate("I", rel("Edges"), union(recRef("I"), recRef("F")), rounds(1)));
            assertThat(messages(errors(tree)))
                    .anySatisfy(m -> assertThat(m).contains("Recursive relation 'F'")
                            .contains("non-monotone position (under ITERATE)"));
        }

        @Test
        void aFixReferenceInItsBaseIsNonMonotone() {
            RelNode tree = fixpoint("F", rel("Edges"),
                    iterate("I", recRef("F"), recRef("I"), rounds(1)));
            assertThat(messages(errors(tree)))
                    .anySatisfy(m -> assertThat(m).contains("non-monotone position (under ITERATE)"));
        }

        @Test
        void anIterateOfTheSameNameShadowsTheFix() {
            // The inner R is the ITERATE's own, so the FIX's step never reads the FIX.
            RelNode tree = fixpoint("R", rel("Edges"),
                    iterate("R", rel("Edges"), recRef("R"), rounds(1)));
            assertThat(messages(errors(tree)))
                    .anySatisfy(m -> assertThat(m).contains("FIX 'R' is not recursive"))
                    .noneSatisfy(m -> assertThat(m).contains("non-monotone"));
        }
    }
}
