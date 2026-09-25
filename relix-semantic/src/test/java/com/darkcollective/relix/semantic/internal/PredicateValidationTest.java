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

import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.internal.PredicateValidator;
import com.darkcollective.relix.semantic.internal.RelAlgebraValidator;
import com.darkcollective.relix.semantic.internal.SchemaInferenceVisitor;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PredicateValidator} — attribute reference validation
 * inside predicates used by selection and all conditioned join variants.
 *
 * <p>Pre-loaded relations: {@code Users(id, name, dept_id)},
 * {@code Depts(dept_id, dept_name)}, {@code Orders(order_id, user_id, amount)}.
 */
@DisplayName("PredicateValidator — attribute references in predicates")
final class PredicateValidationTest {

    // =========================================================================
    // Fixture
    // =========================================================================

    private InMemorySymbolTable table;
    private SchemaAnnotations   annotations;

    @BeforeEach
    void setUp() {
        table       = new InMemorySymbolTable();
        annotations = new SchemaAnnotations();

        register("Users",
                col("id",        ScalarType.NUMBER),
                col("name",      ScalarType.STRING),
                col("dept_id",   ScalarType.NUMBER));
        register("Depts",
                col("dept_id",   ScalarType.NUMBER),
                col("dept_name", ScalarType.STRING));
        register("Orders",
                col("order_id",  ScalarType.NUMBER),
                col("user_id",   ScalarType.NUMBER),
                col("amount",    ScalarType.NUMBER));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void register(String name, ColumnDefinition... cols) {
        table.register(new SourceRelationSymbol(
                "default", name, Provenance.BUILTIN, ShadowPolicy.PERMITTED,
                new Schema(List.of(cols))));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    /** Comparison predicate referencing the named column against a literal zero. */
    private static ComparisonPredicate cmp(String col) {
        return AstBuilders.cmp(
                attr(col), ComparisonOperator.EQUAL,
                num("0"));
    }

    /**
     * Runs inference first to populate annotations, then runs validation.
     * Returns the list of validation-only errors (inference errors are ignored).
     */
    private List<SemanticError> inferAndValidate(RelNode tree) {
        var inferErrors = new ArrayList<SemanticError>();
        var inferVisitor = new SchemaInferenceVisitor(
                table, annotations, inferErrors, "<test>", SemanticFixtures.FUNCTIONS);
        tree.accept(inferVisitor);

        var valErrors = new ArrayList<SemanticError>();
        tree.accept(new RelAlgebraValidator(
                table, annotations, SemanticFixtures.FUNCTIONS, valErrors, "<test>"));
        return valErrors;
    }

    // =========================================================================
    // SelectionNode tests
    // =========================================================================

    @Nested
    @DisplayName("Selection predicate")
    class Selection {

        @Test
        @DisplayName("Valid column in predicate produces no errors")
        void selectionValidColumn() {
            RelNode tree = select(cmp("id"), rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Undefined column in predicate is an error")
        void selectionUndefinedColumn() {
            RelNode tree = select(cmp("ghost_col"), rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("selection")
                    .contains("ghost_col");
        }

        @Test
        @DisplayName("An unknown attribute is placed at the reference, or at its predicate without one")
        void undefinedColumnIsPlaced() {
            var at = new SourceLocation("q.relix", 6, 13);
            var predicateAt = new SourceLocation("q.relix", 6, 11);

            RelNode placed = select(new ComparisonPredicate(new AttributeOperand("ghost", at),
                    ComparisonOperator.EQUAL, num("0"), predicateAt), rel("Users"));
            assertThat(inferAndValidate(placed)).singleElement().satisfies(e -> {
                assertThat(e.message()).startsWith(
                        "Selection σ: attribute 'ghost' not found in input schema");
                assertThat(e.filePath()).isEqualTo("q.relix");
                assertThat(e.line()).isEqualTo(6);
                assertThat(e.column()).isEqualTo(13);
            });

            RelNode unplacedOperand = select(new ComparisonPredicate(attr("ghost"),
                    ComparisonOperator.EQUAL, num("0"), predicateAt), rel("Users"));
            assertThat(inferAndValidate(unplacedOperand)).singleElement().satisfies(e -> {
                assertThat(e.line()).isEqualTo(6);
                assertThat(e.column()).isEqualTo(11);
            });

            assertThat(inferAndValidate(select(cmp("ghost"), rel("Users"))))
                    .as("a tree built with no positions at all is reported against the file")
                    .singleElement().satisfies(e -> {
                        assertThat(e.filePath()).isEqualTo("<test>");
                        assertThat(e.line()).isZero();
                    });
        }

        @Test
        @DisplayName("AND predicate: both branches are validated recursively")
        void andPredicateRecurses() {
            var pred = and(cmp("bad1"), cmp("bad2"));
            RelNode tree = select(pred, rel("Users"));
            assertThat(inferAndValidate(tree)).hasSize(2);
        }

        @Test
        @DisplayName("OR predicate: both branches are validated")
        void orPredicateRecurses() {
            var pred = or(cmp("bad_a"), cmp("id"));
            RelNode tree = select(pred, rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("bad_a");
        }

        @Test
        @DisplayName("NOT predicate: inner predicate is validated")
        void notPredicateRecurses() {
            var pred = not(cmp("no_such_col"));
            RelNode tree = select(pred, rel("Users"));
            assertThat(inferAndValidate(tree)).hasSize(1);
        }

        @Test
        @DisplayName("NullPredicate: invalid operand attribute is an error")
        void nullPredicateInvalidOperand() {
            var pred = nullPred(attr("missing"), true);
            RelNode tree = select(pred, rel("Users"));
            assertThat(inferAndValidate(tree)).hasSize(1);
        }

        @Test
        @DisplayName("NullPredicate: valid attribute produces no errors")
        void nullPredicateValid() {
            var pred = nullPred(attr("name"), true);
            RelNode tree = select(pred, rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("ElementOfPredicate: invalid element attribute is an error")
        void elementOfPredicateInvalidElement() {
            var pred = elementOf(
                    attr("no_such"),
                    set(num("1")));
            RelNode tree = select(pred, rel("Users"));
            assertThat(inferAndValidate(tree)).hasSize(1);
        }

        @Test
        @DisplayName("Literal-only predicate produces no errors")
        void literalOnlyPredicate() {
            var pred = AstBuilders.cmp(
                    num("1"), ComparisonOperator.EQUAL, num("1"));
            RelNode tree = select(pred, rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Arithmetic expression with valid column produces no errors")
        void arithmeticInSelectionPredicate() {
            var arith = arith(
                    attr("amount"), ArithmeticOperator.MULTIPLY,
                    num("2"));
            var pred = AstBuilders.cmp(arith, ComparisonOperator.GREATER,
                    num("100"));
            RelNode tree = select(pred, rel("Orders"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Arithmetic expression with unknown column is an error")
        void arithmeticBadAttributeInPredicate() {
            var arith = arith(
                    attr("no_such_col"), ArithmeticOperator.PLUS,
                    num("5"));
            var pred = AstBuilders.cmp(arith, ComparisonOperator.EQUAL,
                    num("0"));
            RelNode tree = select(pred, rel("Orders"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("no_such_col");
        }

        @Test
        @DisplayName("Unary negation with valid column produces no errors")
        void unaryNegationInPredicate() {
            var unary = unary(attr("amount"));
            var pred = AstBuilders.cmp(unary, ComparisonOperator.GREATER,
                    num("0"));
            RelNode tree = select(pred, rel("Orders"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Unary negation with unknown column is an error")
        void unaryNegationBadAttributeInPredicate() {
            var unary = unary(attr("ghost_col"));
            var pred = AstBuilders.cmp(unary, ComparisonOperator.GREATER,
                    num("0"));
            RelNode tree = select(pred, rel("Orders"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost_col");
        }

        @Test
        @DisplayName("Inference failure suppresses predicate validation (no spurious errors)")
        void inferenceFailureSuppressesPredValidation() {
            RelNode tree = select(cmp("id"), rel("NoSuchRel"));

            var inferErrors = new ArrayList<SemanticError>();
            tree.accept(new SchemaInferenceVisitor(
                    table, annotations, inferErrors, "<test>", SemanticFixtures.FUNCTIONS));
            assertThat(inferErrors).hasSize(1);  // undefined relation

            var valErrors = new ArrayList<SemanticError>();
            tree.accept(new RelAlgebraValidator(
                    table, annotations, SemanticFixtures.FUNCTIONS, valErrors, "<test>"));
            assertThat(valErrors).isEmpty();     // no spurious predicate error
        }

        @Test
        @DisplayName("Boolean literal in AND predicate passes validation")
        void booleanLiteralInPredicate() {
            var pred = and(
                    AstBuilders.cmp(attr("id"), ComparisonOperator.EQUAL,
                            num("1")),
                    AstBuilders.cmp(
                            bool(true), ComparisonOperator.EQUAL,
                            bool(true)));
            RelNode tree = select(pred, rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Function call with wrong arity in predicate is an error")
        void functionCallWrongArityInPredicate() {
            table.register(
                    ScalarFunctionSymbol.builder("upper")
                            .namespace("default")
                            .provenance(Provenance.BUILTIN)
                            .shadowPolicy(ShadowPolicy.PERMITTED)
                            .returnType(ScalarType.STRING)
                            .parameter("s", ScalarType.STRING)
                            .build());

            var fn   = func("upper",attr("name"), attr("id"));
            var pred = AstBuilders.cmp(fn, ComparisonOperator.EQUAL,
                    str("value"));
            RelNode tree = select(pred, rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("upper")
                    .contains("2")
                    .contains("1");
        }
    }

    // =========================================================================
    // Conditioned join tests
    // =========================================================================

    @Nested
    @DisplayName("Conditioned joins — predicate validation")
    class ConditionedJoins {

        @Test
        @DisplayName("Theta join: condition can reference columns from both sides")
        void thetaJoinValidBothSides() {
            var pred = AstBuilders.cmp(
                    attr("dept_id"), ComparisonOperator.EQUAL,
                    attr("dept_id"));
            RelNode tree = join(
                    rel("Users"), rel("Depts"), pred);
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Theta join: unknown column in condition is an error")
        void thetaJoinUnknownColumn() {
            var pred = AstBuilders.cmp(
                    attr("ghost"), ComparisonOperator.EQUAL,
                    num("0"));
            RelNode tree = join(
                    rel("Users"), rel("Depts"), pred);
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("join")
                    .contains("ghost");
        }

        @Test
        @DisplayName("Semi-join: condition validated against combined left+right schema")
        void semiJoinConditionValidated() {
            var pred = AstBuilders.cmp(
                    attr("id"), ComparisonOperator.EQUAL,
                    attr("user_id"));
            RelNode tree = semiJoin(
                    rel("Users"), rel("Orders"), pred);
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Anti-join: condition validated against combined left+right schema")
        void antiJoinConditionValidated() {
            var pred = AstBuilders.cmp(
                    attr("id"), ComparisonOperator.EQUAL,
                    attr("user_id"));
            RelNode tree = antiJoin(
                    rel("Users"), rel("Orders"), pred);
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Anti-join: unknown column in condition is an error")
        void antiJoinUnknownColumn() {
            var pred = AstBuilders.cmp(
                    attr("ghost"), ComparisonOperator.EQUAL,
                    num("0"));
            RelNode tree = antiJoin(
                    rel("Users"), rel("Depts"), pred);
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("anti-join")
                    .contains("ghost");
        }

        @Test
        @DisplayName("Left outer join: unknown column in condition is an error")
        void leftOuterJoinConditionValidated() {
            var pred = AstBuilders.cmp(
                    attr("ghost"), ComparisonOperator.EQUAL,
                    num("0"));
            RelNode tree = leftJoin(
                    rel("Users"), rel("Depts"), pred);
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("left outer join")
                    .contains("ghost");
        }

        @Test
        @DisplayName("Right outer join: unknown column in condition is an error")
        void rightOuterJoinConditionValidated() {
            var pred = AstBuilders.cmp(
                    attr("ghost"), ComparisonOperator.EQUAL,
                    num("0"));
            RelNode tree = rightJoin(
                    rel("Users"), rel("Depts"), pred);
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("right outer join")
                    .contains("ghost");
        }

        @Test
        @DisplayName("Full outer join: unknown column in condition is an error")
        void fullOuterJoinConditionValidated() {
            var pred = AstBuilders.cmp(
                    attr("ghost"), ComparisonOperator.EQUAL,
                    num("0"));
            RelNode tree = fullJoin(
                    rel("Users"), rel("Depts"), pred);
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("full outer join")
                    .contains("ghost");
        }
    }

    // =========================================================================
    // Pass-through nodes (no condition)
    // =========================================================================

    @Nested
    @DisplayName("Pass-through nodes — no predicate, recurse into input")
    class PassThrough {

        @Test
        @DisplayName("Cartesian product: both sides recursed, no condition errors")
        void productRecurses() {
            RelNode tree = product(
                    rel("Users"), rel("Depts"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Distinct: passes through to input")
        void distinctPassesThrough() {
            RelNode tree = distinct(rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Limit: passes through to input")
        void limitPassesThrough() {
            RelNode tree = limit(5L, rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }
    }
}
