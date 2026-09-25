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
import com.darkcollective.relix.semantic.internal.RelAlgebraValidator;
import com.darkcollective.relix.semantic.internal.SchemaInferenceEngine;
import com.darkcollective.relix.semantic.internal.SchemaInferenceVisitor;
import com.darkcollective.relix.semantic.internal.SemanticAnalyzer;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import com.darkcollective.relix.semantic.internal.SemanticValidator;
import com.darkcollective.relix.semantic.internal.SymbolCollector;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.OffsetFunction;
import com.darkcollective.relix.ast.RankingFunction;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OptimizeConstraint;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Unit tests for {@link RelAlgebraValidator} and {@link SemanticValidator}.
 *
 * <p>The fixture runs the schema-inference phase first so that the
 * {@link SchemaAnnotations} map is populated before validation, matching the
 * real pipeline order.
 */
@DisplayName("SemanticValidator — structural and semantic constraint checks")
final class SemanticValidationTest {

    // =========================================================================
    // Fixture
    // =========================================================================

    private InMemorySymbolTable table;
    private SchemaAnnotations   annotations;

    /**
     * Pre-loaded relations:
     * <ul>
     *   <li>Users(id: NUMBER, name: STRING, dept_id: NUMBER)</li>
     *   <li>Depts(dept_id: NUMBER, dept_name: STRING)</li>
     *   <li>Orders(order_id: NUMBER, user_id: NUMBER, amount: NUMBER)</li>
     * </ul>
     */
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

    /** Registers a schema-on-read relation — one that declares nothing it can be asked about. */
    private void registerOpen(String name) {
        table.register(new SourceRelationSymbol(
                "default", name, Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    private static ComparisonPredicate truePred() {
        return cmp(
                num("1"), ComparisonOperator.EQUAL, num("1"));
    }

    /**
     * Runs inference first to populate annotations, then runs validation.
     * Returns the list of validation-only errors (inference errors are ignored).
     */
    private List<SemanticError> inferAndValidate(RelNode tree) {
        var inferErrors = new ArrayList<SemanticError>();
        var visitor = new SchemaInferenceVisitor(table, annotations, inferErrors, "<test>", SemanticFixtures.FUNCTIONS);
        tree.accept(visitor);

        var valErrors = new ArrayList<SemanticError>();
        var validator = new RelAlgebraValidator(table, annotations, SemanticFixtures.FUNCTIONS, valErrors, "<test>");
        tree.accept(validator);
        return valErrors;
    }

    /** Runs the full SemanticValidator over a list of root query statements. */
    private List<SemanticError> validateRootQueries(List<QueryStatement> rootQueries) {
        // Infer schemas for any expression targets first
        for (QueryStatement q : rootQueries) {
            if (q.target() instanceof ExpressionQueryTarget expr) {
                var inferErrors = new ArrayList<SemanticError>();
                var inferVisitor = new SchemaInferenceVisitor(table, annotations, inferErrors, "<test>", SemanticFixtures.FUNCTIONS);
                expr.expression().accept(inferVisitor);
            }
        }
        var validator = new SemanticValidator(table, annotations, SemanticFixtures.FUNCTIONS);
        validator.validate(rootQueries);
        return validator.errors();
    }

    // =========================================================================
    // Set-operation compatibility
    // =========================================================================

    @Nested
    @DisplayName("Set operations — schema compatibility")
    class SetOpCompatibility {

        @Test
        @DisplayName("Union of compatible schemas produces no errors")
        void unionCompatible() {
            RelNode tree = union(
                    rel("Users"), rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Union of schemas with different widths produces an error")
        void unionWidthMismatch() {
            // Users has 3 columns, Depts has 2
            RelNode tree = union(
                    rel("Users"), rel("Depts"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("width")
                    .contains("3").contains("2");
        }

        @Test
        @DisplayName("Union of schemas with same width but incompatible types produces errors")
        void unionTypeMismatch() {
            // Users(id:NUMBER, name:STRING, dept_id:NUMBER) vs
            // Depts(dept_id:NUMBER, dept_name:STRING) — different widths actually, let's
            // build a custom relation with matching width but mismatched types.
            register("BadMatch",
                    col("a", ScalarType.STRING),   // should be NUMBER for col 1
                    col("b", ScalarType.NUMBER),
                    col("c", ScalarType.STRING));

            // Users is id:NUMBER, name:STRING, dept_id:NUMBER; BadMatch is a:STRING,
            // b:NUMBER, c:STRING — same arity, incompatible types.
            RelNode tree = union(rel("Users"), rel("BadMatch"));

            List<SemanticError> errors = inferAndValidate(tree);
            // Col 1: NUMBER vs STRING → mismatch
            // Col 2: STRING vs NUMBER → mismatch
            // Col 3: NUMBER vs STRING → mismatch
            assertThat(errors).hasSize(3);
            errors.forEach(e -> assertThat(e.message()).containsIgnoringCase("mismatch"));
        }

        @Test
        @DisplayName("ANY type is compatible with any concrete type — no error")
        void anyTypeIsCompatible() {
            register("AnyRelation",
                    col("x", ScalarType.ANY),
                    col("y", ScalarType.ANY),
                    col("z", ScalarType.ANY));

            RelNode tree = union(
                    rel("Users"),
                    rel("AnyRelation"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Intersection and Difference also checked")
        void intersectionAndDifferenceChecked() {
            RelNode intNode = intersection(
                    rel("Users"), rel("Depts"));
            RelNode diffNode = difference(
                    rel("Users"), rel("Depts"));

            assertThat(inferAndValidate(intNode)).hasSize(1);
            assertThat(inferAndValidate(diffNode)).hasSize(1);
        }

        @Test
        @DisplayName("UnionAll also checked")
        void unionAllChecked() {
            RelNode tree = unionAll(
                    rel("Users"), rel("Depts"));
            assertThat(inferAndValidate(tree)).hasSize(1);
        }

        @Test
        @DisplayName("Symmetric difference of compatible schemas produces no errors")
        void symmetricDifferenceCompatible() {
            RelNode tree = symmetricDifference(
                    rel("Users"), rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Symmetric difference of different widths produces an error")
        void symmetricDifferenceWidthMismatch() {
            RelNode tree = symmetricDifference(
                    rel("Users"), rel("Depts"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("width")
                    .contains("3").contains("2");
        }

        @Test
        @DisplayName("Outer-union tolerates heterogeneous schemas — no compatibility error")
        void outerUnionAcceptsHeterogeneousSchemas() {
            // Users has 3 columns, Depts has 2 — a plain ∪ errors on width, but ⊔
            // deliberately reconciles them.
            RelNode tree = outerUnion(
                    rel("Users"), rel("Depts"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Outer-union still surfaces errors from its children")
        void outerUnionRecursesIntoChildren() {
            // A bad attribute reference in a child selection must still be reported.
            RelNode badChild = select(
                    cmp(
                            attr("nonexistent"),
                            ComparisonOperator.EQUAL, num("1")),
                    rel("Users"));
            RelNode tree = outerUnion(badChild, rel("Depts"));
            assertThat(inferAndValidate(tree)).isNotEmpty();
        }

        @Test
        @DisplayName("Incompatible subtree — validation skipped when inference failed")
        void skippedOnInferenceFailure() {
            // One side references a non-existent relation
            RelNode tree = union(
                    rel("Users"), rel("Ghost"));
            List<SemanticError> errors = inferAndValidate(tree);

            // Validation skips the set-op check when right schema is absent,
            // so only the inference error (not a validation error) is relevant.
            // valErrors should be empty (inference errors were in a separate list).
            assertThat(errors).isEmpty();
        }
    }

    // =========================================================================
    // Division validity
    // =========================================================================

    @Nested
    @DisplayName("DivisionNode — right schema must be a column subset of left")
    class DivisionValidity {

        @Test
        @DisplayName("Valid division — right columns all in left — no errors")
        void validDivision() {
            // Users(id,name,dept_id) ÷ Depts(dept_id,dept_name)
            // dept_id is in left; dept_name is NOT in left → expect an error
            // Let's use a sub-relation with only dept_id instead:
            register("DeptIdOnly", col("dept_id", ScalarType.NUMBER));
            RelNode tree = division(
                    rel("Users"), rel("DeptIdOnly"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Division with right column absent from left is an error")
        void rightColumnNotInLeft() {
            // Depts has dept_name which is NOT in Users
            RelNode tree = division(
                    rel("Users"), rel("Depts"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("dept_name")
                    .containsIgnoringCase("does not appear");
        }

        @Test
        @DisplayName("Multiple missing right columns each produce their own error")
        void multipleRightColumnsNotInLeft() {
            register("Wide",
                    col("x", ScalarType.NUMBER),
                    col("y", ScalarType.STRING),
                    col("z", ScalarType.BOOLEAN));
            register("DivBy", col("dept_id", ScalarType.NUMBER));

            // Depts is dept_id, dept_name; Wide is x, y, z — none of them in Depts.
            RelNode tree = division(rel("Depts"), rel("Wide"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(3); // x, y, z each missing
        }
    }

    // =========================================================================
    // Composition validity
    // =========================================================================

    @Nested
    @DisplayName("CompositionNode — recurses into inputs, no extra validation")
    class CompositionValidity {

        @Test
        @DisplayName("Valid composition over shared columns produces no validation errors")
        void validComposition() {
            // Users(id,name,dept_id) ∘ Depts(dept_id,dept_name) — shared dept_id
            RelNode tree = composition(
                    rel("Users"), rel("Depts"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Errors inside a composition input are surfaced")
        void recursesIntoInputs() {
            // A selection referencing a non-existent column on the left input.
            RelNode badInput = select(
                    cmp(attr("ghost"),
                            ComparisonOperator.EQUAL, num("1")),
                    rel("Users"));
            RelNode tree = composition(badInput, rel("Depts"));

            assertThat(inferAndValidate(tree)).isNotEmpty();
        }
    }

    // =========================================================================
    // Universal quantification validity
    // =========================================================================

    @Nested
    @DisplayName("UniversalNode — grouping keys exist + predicate is valid")
    class UniversalValidity {

        @Test
        @DisplayName("Valid ∀ over an existing key and predicate produces no errors")
        void valid() {
            RelNode tree = universal(
                    List.of("dept_id"),
                    cmp(attr("id"),
                            ComparisonOperator.GREATER, num("0")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Unknown grouping key is an error")
        void unknownKey() {
            RelNode tree = universal(
                    List.of("ghost"), truePred(), rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).anySatisfy(e ->
                    assertThat(e.message()).contains("ghost").contains("grouping key"));
        }

        @Test
        @DisplayName("Predicate referencing a missing column is an error")
        void badPredicate() {
            RelNode tree = universal(
                    List.of("dept_id"),
                    cmp(attr("ghost"),
                            ComparisonOperator.EQUAL, num("1")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isNotEmpty();
        }

        @Test
        @DisplayName("No-key whole-relation ∀ is valid; only the predicate is checked")
        void noKeyWholeRelation() {
            RelNode tree = universal(
                    List.of(),
                    cmp(attr("id"),
                            ComparisonOperator.GREATER, num("0")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("No-key whole-relation ∀ still rejects a bad predicate")
        void noKeyBadPredicate() {
            RelNode tree = universal(
                    List.of(),
                    cmp(attr("ghost"),
                            ComparisonOperator.EQUAL, num("1")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isNotEmpty();
        }
    }

    // =========================================================================
    // Sampling validity
    // =========================================================================

    @Nested
    @DisplayName("SampleNode — probability must be in [0, 1]")
    class SampleValidity {

        @Test
        @DisplayName("A probability in range produces no errors")
        void valid() {
            assertThat(inferAndValidate(sample(0.1, rel("Users")))).isEmpty();
        }

        @Test
        @DisplayName("0 and 1 are accepted (inclusive)")
        void boundariesValid() {
            assertThat(inferAndValidate(sample(0.0, rel("Users")))).isEmpty();
            assertThat(inferAndValidate(sample(1.0, rel("Users")))).isEmpty();
        }

        @Test
        @DisplayName("A probability above 1 is an error")
        void tooHigh() {
            List<SemanticError> errors = inferAndValidate(sample(1.5, rel("Users")));
            assertThat(errors).anySatisfy(e ->
                    assertThat(e.message()).contains("between 0 and 1"));
        }

        @Test
        @DisplayName("A negative probability is an error")
        void negative() {
            List<SemanticError> errors = inferAndValidate(sample(-0.2, rel("Users")));
            assertThat(errors).anySatisfy(e ->
                    assertThat(e.message()).contains("between 0 and 1"));
        }
    }

    // =========================================================================
    // Top-k validity
    // =========================================================================

    @Nested
    @DisplayName("TopKNode — grouping keys and sort attributes must exist")
    class TopKValidity {

        @Test
        @DisplayName("Valid TOP over existing key and sort attribute — no errors")
        void valid() {
            RelNode tree = topK(List.of("dept_id"),
                    List.of(desc("id")), 3,
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Unknown grouping key is an error")
        void unknownKey() {
            RelNode tree = topK(List.of("ghost"),
                    List.of(desc("id")), 3,
                    rel("Users"));
            assertThat(inferAndValidate(tree)).anySatisfy(e ->
                    assertThat(e.message()).contains("ghost").contains("grouping key"));
        }

        @Test
        @DisplayName("Unknown sort attribute is an error")
        void unknownSortAttr() {
            RelNode tree = topK(List.of("dept_id"),
                    List.of(asc("ghost")), 3,
                    rel("Users"));
            assertThat(inferAndValidate(tree)).anySatisfy(e ->
                    assertThat(e.message()).contains("ghost").contains("sort attribute"));
        }
    }

    // =========================================================================
    // Covering reduction — strength bounds + closed schema required
    // =========================================================================

    @Nested
    @DisplayName("CoverNode — 1 ≤ strength ≤ width; open input is rejected")
    class CoverValidity {

        @Test
        @DisplayName("Valid COVER 2 over a 3-column relation — no errors")
        void validCoverTwo() {
            assertThat(inferAndValidate(cover(2, rel("Users")))).isEmpty();
        }

        @Test
        @DisplayName("COVER 1 (each value at least once) over a 2-column relation — no errors")
        void validCoverOne() {
            assertThat(inferAndValidate(cover(1, rel("Depts")))).isEmpty();
        }

        @Test
        @DisplayName("COVER t = width (every distinct row must appear) — no errors")
        void validCoverEqualWidth() {
            // Users has 3 columns; COVER 3 is the t = w degenerate case (= δ)
            assertThat(inferAndValidate(cover(3, rel("Users")))).isEmpty();
        }

        @Test
        @DisplayName("Strength exceeding column count is an error")
        void strengthExceedsWidth() {
            // Users has 3 columns; strength 4 is out of range
            List<SemanticError> errors = inferAndValidate(cover(4, rel("Users")));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("coverage strength")
                    .containsIgnoringCase("exceeds")
                    .contains("4")
                    .contains("3");
        }

        @Test
        @DisplayName("Strength much larger than column count also reported")
        void strengthFarExceedsWidth() {
            List<SemanticError> errors = inferAndValidate(cover(10, rel("Depts")));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("coverage strength");
        }

        @Test
        @DisplayName("Open (schema-on-read) input is a validation error")
        void openSchemaIsError() {
            table.register(new SourceRelationSymbol(
                    "default", "Docs",
                    com.darkcollective.relix.symbol.Provenance.BUILTIN,
                    com.darkcollective.relix.symbol.ShadowPolicy.PERMITTED,
                    Schema.open()));
            List<SemanticError> errors = inferAndValidate(cover(2, rel("Docs")));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("open")
                    .containsIgnoringCase("column set");
        }
    }

    // =========================================================================
    // Unnest — column existence
    // =========================================================================

    @Nested
    @DisplayName("UnnestNode — column must exist in the input schema")
    class UnnestValidity {

        @Test
        @DisplayName("Unnesting an existing column is valid")
        void validUnnest() {
            assertThat(inferAndValidate(unnest("dept_id", rel("Users")))).isEmpty();
        }

        @Test
        @DisplayName("Unnesting a missing column is an error")
        void missingColumn() {
            List<SemanticError> errors =
                    inferAndValidate(unnest("items", rel("Users")));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("items")
                    .containsIgnoringCase("not found");
        }

        @Test
        @DisplayName("WITH ORDINALITY using a fresh column name is valid")
        void ordinalityFreshName() {
            assertThat(inferAndValidate(unnest("dept_id", false,
                    java.util.Optional.of("pos"), rel("Users")))).isEmpty();
        }

        @Test
        @DisplayName("WITH ORDINALITY whose name clashes with an existing column is an error")
        void ordinalityNameClash() {
            List<SemanticError> errors = inferAndValidate(unnest("dept_id", false,
                    java.util.Optional.of("id"), rel("Users")));
            assertThat(errors).anySatisfy(e -> assertThat(e.message())
                    .containsIgnoringCase("ordinality").containsIgnoringCase("already exists"));
        }
    }

    // =========================================================================
    // Open (schema-on-read) sources — dynamic path resolution
    // =========================================================================

    @Nested
    @DisplayName("Open sources — arbitrary paths resolve dynamically, never 'not found'")
    class OpenSchemaSourceValidity {

        @BeforeEach
        void registerOpenSource() {
            // "Docs" is a schemaless (open) source — e.g. a NoSQL collection.
            table.register(new SourceRelationSymbol(
                    "default", "Docs", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));
        }

        @Test
        @DisplayName("a selection predicate over an arbitrary path is valid")
        void selectionOverArbitraryPath() {
            RelNode tree = select(
                    cmp(attr("user.name"),
                            ComparisonOperator.EQUAL, str("x")),
                    rel("Docs"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("projecting arbitrary fields is valid")
        void projectionOfArbitraryFields() {
            RelNode tree = project(
                    List.of(projected(attr("anything")),
                            projected(attr("whatever"))),
                    rel("Docs"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("unnesting an arbitrary column is valid")
        void unnestArbitraryColumn() {
            assertThat(inferAndValidate(unnest("items", rel("Docs")))).isEmpty();
        }

        @Test
        @DisplayName("an open schema is set-op compatible with any closed schema")
        void unionWithClosedSchema() {
            RelNode tree = union(rel("Docs"), rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("joining two open sources infers an open schema rather than failing")
        void joinOfTwoOpenSourcesInfersOpen() {
            table.register(new SourceRelationSymbol(
                    "default", "Feed", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));
            // Both headings are column-less, so concatenating them has no column list to
            // build; the join of two dynamic documents is itself dynamic.
            RelNode tree = product(rel("Docs"), rel("Feed"));
            assertThat(inferAndValidate(tree)).isEmpty();
            assertThat(annotations.get(tree)).hasValueSatisfying(s -> assertThat(s.isOpen()).isTrue());
        }

        @Test
        @DisplayName("an AS-OF join over two open sources analyses cleanly")
        void asOfJoinOverTwoOpenSourcesAnalyses() {
            table.register(new SourceRelationSymbol(
                    "default", "Feed", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));
            RelNode tree = new AsOfJoinNode(
                    rel("Docs"), rel("Feed"),
                    and(
                            cmp(attr("Docs.sym"), ComparisonOperator.EQUAL,
                                    attr("Feed.sym")),
                            cmp(attr("Docs.ts"),
                                    ComparisonOperator.GREATER_EQUAL, attr("Feed.ts"))),
                    SourceLocation.UNKNOWN);
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("by contrast, a closed source still reports a missing column")
        void closedSourceStillErrors() {
            // same unnest against the closed Users schema is an error — open is not unresolved
            assertThat(inferAndValidate(unnest("items", rel("Users")))).isNotEmpty();
        }
    }

    // =========================================================================
    // Projection — attribute existence
    // =========================================================================

    @Nested
    @DisplayName("ProjectionNode — AttributeOperand column existence")
    class ProjectionColumnExistence {

        @Test
        @DisplayName("Projecting an existing column produces no errors")
        void existingColumn() {
            RelNode tree = project(
                    List.of(ProjectedAttribute.simple(attr("name"))),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Projecting a non-existent column is an error")
        void missingColumn() {
            RelNode tree = project(
                    List.of(ProjectedAttribute.simple(attr("salary"))),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("salary");
        }

        @Test
        @DisplayName("Qualified attribute: stripped column name must exist")
        void qualifiedAttributeExists() {
            RelNode tree = project(
                    List.of(ProjectedAttribute.simple(attr("Users.name"))),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Qualified attribute with bad column name is an error")
        void qualifiedAttributeMissing() {
            RelNode tree = project(
                    List.of(ProjectedAttribute.simple(attr("Users.bogus"))),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("bogus");
        }

        @Test
        @DisplayName("Arithmetic expression over existing columns — no errors")
        void arithmeticExpression() {
            var expr = arith(
                    attr("amount"), ArithmeticOperator.MULTIPLY, num("1.1"));
            RelNode tree = project(
                    List.of(ProjectedAttribute.aliased(expr, "adjusted")),
                    rel("Orders"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Arithmetic over a non-existent attribute is an error")
        void arithmeticBadAttribute() {
            var expr = arith(
                    attr("no_such_col"), ArithmeticOperator.PLUS, num("5"));
            RelNode tree = project(
                    List.of(ProjectedAttribute.aliased(expr, "result")),
                    rel("Orders"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("no_such_col");
        }

        @Test
        @DisplayName("Literal projections (number, string) produce no errors")
        void literalProjection() {
            RelNode tree = project(
                    List.of(ProjectedAttribute.aliased(num("42"), "magic"),
                            ProjectedAttribute.aliased(str("hi"), "greeting")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }
    }

    // =========================================================================
    // Function call validation
    // =========================================================================

    @Nested
    @DisplayName("FunctionCall — existence and arity")
    class FunctionCallValidation {

        @BeforeEach
        void registerFunction() {
            // Register a 1-argument function "upper"
            table.register(
                    com.darkcollective.relix.symbol.function.ScalarFunctionSymbol.builder("upper")
                            .namespace("default")
                            .provenance(Provenance.BUILTIN)
                            .shadowPolicy(ShadowPolicy.PERMITTED)
                            .returnType(ScalarType.STRING)
                            .parameter("s", ScalarType.STRING)
                            .build());
        }

        @Test
        @DisplayName("Known function with correct arity produces no errors")
        void knownFunctionCorrectArity() {
            var call = func("upper",attr("name"));
            RelNode tree = project(
                    List.of(ProjectedAttribute.aliased(call, "upper_name")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Unknown function produces an error")
        void unknownFunction() {
            var call = func("no_such_fn",attr("name"));
            RelNode tree = project(
                    List.of(ProjectedAttribute.aliased(call, "result")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("no_such_fn");
        }

        @Test
        @DisplayName("Known function called with wrong arity produces an error")
        void wrongArity() {
            // upper expects 1 arg; pass 2
            var call = func("upper",attr("name"), attr("id"));
            RelNode tree = project(
                    List.of(ProjectedAttribute.aliased(call, "result")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("upper")
                    .contains("2")     // actual
                    .contains("1");    // expected
        }
    }

    // =========================================================================
    // Aggregation attribute existence
    // =========================================================================

    @Nested
    @DisplayName("AggregationNode — group-by and aggregate attribute existence")
    class AggregationAttributes {

        @Test
        @DisplayName("Valid group-by and aggregate attributes produce no errors")
        void validAggregation() {
            RelNode tree = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Non-existent group-by attribute is an error")
        void badGroupByAttribute() {
            RelNode tree = groupBy(
                    List.of("bad_group"),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("bad_group");
        }

        @Test
        @DisplayName("Non-existent aggregate attribute is an error")
        void badAggregateAttribute() {
            RelNode tree = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "ghost_col")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost_col");
        }

        @Test
        @DisplayName("Aggregate over an expression with a valid column produces no errors")
        void validExpressionAggregate() {
            RelNode tree = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.of(AggregateOperator.SUM,
                            arith(attr("id"),
                                    ArithmeticOperator.MULTIPLY, attr("id")))),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Aggregate over an expression with a non-existent column is an error")
        void badExpressionAggregateColumn() {
            RelNode tree = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.of(AggregateOperator.SUM,
                            arith(attr("ghost_col"),
                                    ArithmeticOperator.MULTIPLY, attr("id")))),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).anySatisfy(e -> assertThat(e.message()).contains("ghost_col"));
        }

        @Test
        @DisplayName("ARGMAX with valid rank and yield columns produces no errors")
        void validArgmax() {
            RelNode tree = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.arg(AggregateOperator.ARGMAX, "id", "dept_id")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("ARGMAX with a non-existent yield column is an error")
        void badArgmaxYieldColumn() {
            RelNode tree = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.arg(AggregateOperator.ARGMAX, "id", "ghost_yield")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost_yield");
        }

        @Test
        @DisplayName("Both group-by and aggregate are invalid — two separate errors")
        void bothBadAttributes() {
            RelNode tree = groupBy(
                    List.of("bad_group"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "bad_agg")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).hasSize(2);
        }
    }

    // =========================================================================
    // Closure column existence / compatibility
    // =========================================================================

    @Nested
    @DisplayName("ClosureNode — from/to column existence and type compatibility")
    class ClosureValidation {

        @Test
        @DisplayName("Valid closure over two same-typed columns produces no errors")
        void validClosure() {
            RelNode tree = closure("id", "dept_id",rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Unknown 'from' column is an error")
        void unknownFromColumn() {
            RelNode tree = closure("ghost", "dept_id",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("Unknown 'to' column is an error")
        void unknownToColumn() {
            RelNode tree = closure("id", "ghost",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("from == to is an error")
        void sameColumn() {
            RelNode tree = closure("id", "id",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("differ");
        }

        @Test
        @DisplayName("type-incompatible from/to columns are an error")
        void typeMismatch() {
            // id: NUMBER vs name: STRING
            RelNode tree = closure("id", "name",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("same type");
        }

        @Test
        @DisplayName("closure over an undefined input reports no closure error (input failure is upstream)")
        void undefinedInput() {
            RelNode tree = closure("a", "b",rel("Ghost"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("an ANY column is type-compatible with any other column")
        void anyColumnCompatible() {
            register("Mixed", col("n", ScalarType.NUMBER), col("a", ScalarType.ANY));
            assertThat(inferAndValidate(closure("n", "a",rel("Mixed")))).isEmpty();
            assertThat(inferAndValidate(closure("a", "n",rel("Mixed")))).isEmpty();
        }
    }

    // =========================================================================
    // Cluster (connected components) column existence / compatibility
    // =========================================================================

    @Nested
    @DisplayName("WindowNode — partition/sort/output-column validation")
    class WindowValidation {

        private WindowNode rolling(AggregateOperator op, AttributeOperand arg,
                                   List<String> keys, String sortCol, String out) {
            return window(new WindowFunction.AggregateWindow(op, arg), keys,
                    List.of(asc(sortCol)),
                    new WindowFrame.BoundedFrame(3), out, rel("Orders"));
        }

        @Test
        @DisplayName("Valid rolling window produces no errors")
        void valid() {
            RelNode tree = rolling(AggregateOperator.SUM, attr("amount"),
                    List.of("user_id"), "amount", "run");
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("every aggregate a ROLLING frame cannot carry is named, not just COLLECT")
        void nestingAggregatesAreRejected() {
            // Three separate arms of one ||. COLLECT, ARGMAX and ARGMIN all produce
            // something a sliding frame cannot accumulate incrementally — a nested array,
            // or a value from a *different* row than the one being ranked — and each has
            // to be named or it slips through as a silently wrong window.
            for (AggregateOperator op : List.of(AggregateOperator.COLLECT,
                    AggregateOperator.ARGMAX, AggregateOperator.ARGMIN)) {
                RelNode tree = rolling(op, attr("amount"),
                        List.of("user_id"), "amount", "run");
                assertThat(inferAndValidate(tree))
                        .as("ROLLING %s", op)
                        .anyMatch(e -> e.message().contains(op.name()));
            }
        }

        @Test
        @DisplayName("Unknown partition key is an error")
        void unknownPartitionKey() {
            RelNode tree = rolling(AggregateOperator.SUM, attr("amount"),
                    List.of("ghost"), "amount", "run");
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).anyMatch(e -> e.message().contains("ghost"));
        }

        @Test
        @DisplayName("Unknown sort attribute is an error")
        void unknownSortColumn() {
            RelNode tree = rolling(AggregateOperator.SUM, attr("amount"),
                    List.of("user_id"), "ghost", "run");
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).anyMatch(e -> e.message().contains("ghost"));
        }

        @Test
        @DisplayName("output column clashing with an input column is an error")
        void outputClash() {
            RelNode tree = rolling(AggregateOperator.SUM, attr("amount"),
                    List.of("user_id"), "amount", "amount");
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).anyMatch(e -> e.message().contains("already exists"));
        }

        @Test
        @DisplayName("bad aggregate argument (unknown column) is an error")
        void badAggregateArgument() {
            RelNode tree = rolling(AggregateOperator.SUM, attr("ghost"),
                    List.of("user_id"), "amount", "run");
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).anyMatch(e -> e.message().contains("ghost"));
        }

        @Test
        @DisplayName("COLLECT is rejected in a window")
        void rejectsCollect() {
            RelNode tree = rolling(AggregateOperator.COLLECT, attr("amount"),
                    List.of("user_id"), "amount", "run");
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).anyMatch(e -> e.message().contains("COLLECT"));
        }

        private WindowNode ranking(RankingFunction fn, java.util.Optional<Operand> ntile, String out) {
            return window(new WindowFunction.RankingWindow(fn, ntile),
                    List.of("user_id"), List.of(desc("amount")),
                    new WindowFrame.PartitionFrame(), out, rel("Orders"));
        }

        @Test
        @DisplayName("Valid RANK window produces no errors")
        void validRank() {
            assertThat(inferAndValidate(ranking(RankingFunction.RANK,
                    java.util.Optional.empty(), "rnk"))).isEmpty();
        }

        @Test
        @DisplayName("Valid NTILE with a positive integer literal produces no errors")
        void validNtile() {
            assertThat(inferAndValidate(ranking(RankingFunction.NTILE,
                    java.util.Optional.of(num("4")), "q"))).isEmpty();
        }

        @Test
        @DisplayName("NTILE without an argument is an error")
        void ntileMissingArgument() {
            List<SemanticError> errors = inferAndValidate(ranking(RankingFunction.NTILE,
                    java.util.Optional.empty(), "q"));
            assertThat(errors).anyMatch(e -> e.message().contains("NTILE"));
        }

        @Test
        @DisplayName("NTILE with a non-integer literal is an error")
        void ntileNonInteger() {
            List<SemanticError> errors = inferAndValidate(ranking(RankingFunction.NTILE,
                    java.util.Optional.of(num("2.5")), "q"));
            assertThat(errors).anyMatch(e -> e.message().contains("positive integer"));
        }

        @Test
        @DisplayName("NTILE with a non-numeric literal is an error")
        void ntileNonNumber() {
            List<SemanticError> errors = inferAndValidate(ranking(RankingFunction.NTILE,
                    java.util.Optional.of(str("four")), "q"));
            assertThat(errors).anyMatch(e -> e.message().contains("positive integer"));
        }

        @Test
        @DisplayName("NTILE with a zero bucket count is an error")
        void ntileZero() {
            List<SemanticError> errors = inferAndValidate(ranking(RankingFunction.NTILE,
                    java.util.Optional.of(num("0")), "q"));
            assertThat(errors).anyMatch(e -> e.message().contains("at least 1"));
        }

        // ─── offset functions (slice 4, issue #234) ─────────────────────────────

        private WindowNode offset(OffsetFunction fn, AttributeOperand expr,
                                  java.util.Optional<Operand> off,
                                  java.util.Optional<Operand> dflt, String out) {
            return window(new WindowFunction.OffsetWindow(fn, expr, off, dflt),
                    List.of("user_id"), List.of(asc("amount")),
                    new WindowFrame.PartitionFrame(), out, rel("Orders"));
        }

        @Test
        @DisplayName("Valid LAG with a positive integer offset produces no errors")
        void validLag() {
            assertThat(inferAndValidate(offset(OffsetFunction.LAG, attr("amount"),
                    java.util.Optional.of(num("1")), java.util.Optional.empty(),
                    "prev"))).isEmpty();
        }

        @Test
        @DisplayName("Valid FIRST_VALUE without an offset produces no errors")
        void validFirstValue() {
            assertThat(inferAndValidate(offset(OffsetFunction.FIRST_VALUE,
                    attr("amount"), java.util.Optional.empty(),
                    java.util.Optional.empty(), "first"))).isEmpty();
        }

        @Test
        @DisplayName("LAG over an unknown column is an error")
        void lagUnknownColumn() {
            List<SemanticError> errors = inferAndValidate(offset(OffsetFunction.LAG,
                    attr("ghost"), java.util.Optional.of(num("1")),
                    java.util.Optional.empty(), "prev"));
            assertThat(errors).anyMatch(e -> e.message().contains("ghost"));
        }

        @Test
        @DisplayName("LAG with a non-integer offset is an error")
        void lagNonIntegerOffset() {
            List<SemanticError> errors = inferAndValidate(offset(OffsetFunction.LAG,
                    attr("amount"), java.util.Optional.of(num("2.5")),
                    java.util.Optional.empty(), "prev"));
            assertThat(errors).anyMatch(e -> e.message().contains("positive integer"));
        }

        @Test
        @DisplayName("LAG with a non-numeric offset is an error")
        void lagNonNumericOffset() {
            List<SemanticError> errors = inferAndValidate(offset(OffsetFunction.LAG,
                    attr("amount"), java.util.Optional.of(str("one")),
                    java.util.Optional.empty(), "prev"));
            assertThat(errors).anyMatch(e -> e.message().contains("positive integer"));
        }

        @Test
        @DisplayName("LAG with a zero offset is an error")
        void lagZeroOffset() {
            List<SemanticError> errors = inferAndValidate(offset(OffsetFunction.LAG,
                    attr("amount"), java.util.Optional.of(num("0")),
                    java.util.Optional.empty(), "prev"));
            assertThat(errors).anyMatch(e -> e.message().contains("at least 1"));
        }

        @Test
        @DisplayName("an offset output column clashing with an input column is an error")
        void offsetOutputClash() {
            List<SemanticError> errors = inferAndValidate(offset(OffsetFunction.LAG,
                    attr("amount"), java.util.Optional.of(num("1")),
                    java.util.Optional.empty(), "amount"));
            assertThat(errors).anyMatch(e -> e.message().contains("already exists"));
        }
    }

    @Nested
    @DisplayName("ClusterNode — from/to/label column validation")
    class ClusterValidation {

        @Test
        @DisplayName("Valid cluster over two same-typed columns produces no errors")
        void validCluster() {
            RelNode tree = cluster("id", "dept_id", "cid",rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Unknown 'from' column is an error")
        void unknownFromColumn() {
            RelNode tree = cluster("ghost", "dept_id", "cid",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("Unknown 'to' column is an error")
        void unknownToColumn() {
            RelNode tree = cluster("id", "ghost", "cid",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("from == to is an error")
        void sameColumn() {
            RelNode tree = cluster("id", "id", "cid",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("differ");
        }

        @Test
        @DisplayName("type-incompatible from/to columns are an error")
        void typeMismatch() {
            RelNode tree = cluster("id", "name", "cid",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("same type");
        }

        @Test
        @DisplayName("label colliding with the node column is an error")
        void labelCollidesWithNodeColumn() {
            RelNode tree = cluster("id", "dept_id", "id",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("label");
        }

        @Test
        @DisplayName("cluster over an undefined input reports no cluster error (input failure is upstream)")
        void undefinedInput() {
            RelNode tree = cluster("a", "b", "cid",rel("Ghost"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }
    }

    @Nested
    @DisplayName("PathNode — from/to/depth column validation")
    class PathValidation {

        @Test
        @DisplayName("Valid path over two same-typed columns produces no errors")
        void validPath() {
            RelNode tree = path("id", "dept_id", 1, 3, "depth",rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Unknown 'from' column is an error")
        void unknownFromColumn() {
            RelNode tree = path("ghost", "dept_id", 1, 3, "depth",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("Unknown 'to' column is an error")
        void unknownToColumn() {
            RelNode tree = path("id", "ghost", 1, 3, "depth",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("from == to is an error")
        void sameColumn() {
            RelNode tree = path("id", "id", 1, 3, "depth",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("differ");
        }

        @Test
        @DisplayName("type-incompatible from/to columns are an error")
        void typeMismatch() {
            RelNode tree = path("id", "name", 1, 3, "depth",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("same type");
        }

        @Test
        @DisplayName("depth colliding with an endpoint is an error")
        void depthCollidesWithEndpoint() {
            RelNode tree = path("id", "dept_id", 1, 3, "id",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("depth");
        }

        @Test
        @DisplayName("…and colliding with the OTHER endpoint is the same error")
        void depthCollidesWithTheToEndpoint() {
            // The rule names both endpoints and each is its own arm: a fixture that only
            // ever collides with `from` short-circuits before the `to` comparison, so
            // nothing has shown that half is checked at all.
            RelNode tree = path("id", "dept_id", 1, 3, "dept_id",rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("depth");
        }

        @Test
        @DisplayName("path over an undefined input reports no path error (input failure is upstream)")
        void undefinedInput() {
            RelNode tree = path("a", "b", 1, 2, "depth",rel("Ghost"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }
    }

    // =========================================================================
    // Trace (optimal-path extraction) column existence / type checks
    // =========================================================================

    @Nested
    @DisplayName("TraceNode — from/to/weight/path column validation")
    class TraceValidation {

        @Test
        @DisplayName("Valid trace over numeric edge columns produces no errors")
        void validTrace() {
            register("Edges",
                    col("src",  ScalarType.NUMBER),
                    col("dst",  ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode tree = trace(
                    "src", "dst", "cost", ObjectiveSense.MINIMIZE, "route",rel("Edges"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Unknown 'from' column is an error")
        void unknownFromColumn() {
            register("Edges",
                    col("src",  ScalarType.NUMBER),
                    col("dst",  ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode tree = trace(
                    "ghost", "dst", "cost", ObjectiveSense.MINIMIZE, "route",rel("Edges"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("Unknown 'to' column is an error")
        void unknownToColumn() {
            register("Edges",
                    col("src",  ScalarType.NUMBER),
                    col("dst",  ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode tree = trace(
                    "src", "ghost", "cost", ObjectiveSense.MINIMIZE, "route",rel("Edges"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("Unknown weight column is an error")
        void unknownWeightColumn() {
            register("Edges",
                    col("src",  ScalarType.NUMBER),
                    col("dst",  ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode tree = trace(
                    "src", "dst", "ghost", ObjectiveSense.MINIMIZE, "route",rel("Edges"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost");
        }

        @Test
        @DisplayName("from == to is an error")
        void sameColumn() {
            register("Edges",
                    col("src",  ScalarType.NUMBER),
                    col("dst",  ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode tree = trace(
                    "src", "src", "cost", ObjectiveSense.MINIMIZE, "route",rel("Edges"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("differ");
        }

        @Test
        @DisplayName("weight column of type STRING (non-numeric) is an error")
        void weightColumnNotNumeric() {
            register("Edges",
                    col("src",   ScalarType.NUMBER),
                    col("dst",   ScalarType.NUMBER),
                    col("label", ScalarType.STRING));
            RelNode tree = trace(
                    "src", "dst", "label", ObjectiveSense.MINIMIZE, "route",rel("Edges"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("NUMBER");
        }

        @Test
        @DisplayName("path column name clashing with an existing column is an error")
        void pathColumnClash() {
            register("Edges",
                    col("src",  ScalarType.NUMBER),
                    col("dst",  ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode tree = trace(
                    "src", "dst", "cost", ObjectiveSense.MINIMIZE, "src",rel("Edges"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("path");
        }

        @Test
        @DisplayName("over an open input, the explicit name comparisons are the only guard")
        void pathColumnClashOverAnOpenSchema() {
            // `declares` answers false for every name on a schema-on-read input, so the
            // first arm cannot fire and the three explicit comparisons — against from, to
            // and weight — are all that stop the path column overwriting one of them.
            // Each is a separate arm and none is reachable through a closed schema, where
            // `declares` short-circuits first.
            registerOpen("Docs");
            for (String clashing : List.of("src", "dst", "cost")) {
                RelNode tree = trace(
                        "src", "dst", "cost", ObjectiveSense.MINIMIZE, clashing,rel("Docs"));
                assertThat(inferAndValidate(tree))
                        .as("path column '%s'", clashing)
                        .anySatisfy(e -> assertThat(e.message()).containsIgnoringCase("path"));
            }
        }

        @Test
        @DisplayName("the comparisons ignore case, as a column reference does everywhere else")
        void pathColumnClashIsCaseInsensitive() {
            registerOpen("Docs");
            RelNode tree = trace(
                    "src", "dst", "cost", ObjectiveSense.MINIMIZE, "SRC",rel("Docs"));
            assertThat(inferAndValidate(tree))
                    .anySatisfy(e -> assertThat(e.message()).containsIgnoringCase("path"));
        }

        @Test
        @DisplayName("a distinct path column over an open input is accepted")
        void distinctPathColumnOverAnOpenSchema() {
            registerOpen("Docs");
            RelNode tree = trace(
                    "src", "dst", "cost", ObjectiveSense.MINIMIZE, "route",rel("Docs"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("trace over an undefined input reports no trace error (input failure is upstream)")
        void undefinedInput() {
            RelNode tree = trace(
                    "a", "b", "w", ObjectiveSense.MINIMIZE, "route",rel("Ghost"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }
    }

    // =========================================================================
    // Sort attribute existence
    // =========================================================================

    @Nested
    @DisplayName("SortNode — sort attribute existence")
    class SortAttributeExistence {

        @Test
        @DisplayName("Valid sort attribute produces no errors")
        void validSort() {
            RelNode tree = sort(
                    List.of(asc("name")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Non-existent sort attribute is an error")
        void badSortAttribute() {
            RelNode tree = sort(
                    List.of(desc("ghost_col")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost_col");
        }

        @Test
        @DisplayName("Multiple bad sort attributes produce separate errors")
        void multipleBadSortAttributes() {
            RelNode tree = sort(
                    List.of(asc("bad1"),
                            desc("bad2")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).hasSize(2);
        }
    }

    // =========================================================================
    // SemanticValidator — named query target resolution
    // =========================================================================

    @Nested
    @DisplayName("SemanticValidator — named query target resolution")
    class NamedTargetResolution {

        @Test
        @DisplayName("Named target for a registered relation produces no errors")
        void knownNamedTarget() {
            var q = query("Users");
            assertThat(validateRootQueries(List.of(q))).isEmpty();
        }

        @Test
        @DisplayName("Named target for an unregistered name is an error")
        void unknownNamedTarget() {
            var q = query("NoSuchRelation");
            List<SemanticError> errors = validateRootQueries(List.of(q));

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("NoSuchRelation");
        }

        @Test
        @DisplayName("Named target lookup is case-insensitive")
        void namedTargetCaseInsensitive() {
            var q = query("users");
            assertThat(validateRootQueries(List.of(q))).isEmpty();
        }

        @Test
        @DisplayName("Inline expression query target is validated structurally")
        void expressionQueryTargetValidated() {
            // Project a non-existent column in the root query expression
            RelNode bad = project(
                    List.of(ProjectedAttribute.simple(attr("ghost_col"))),
                    rel("Users"));
            var q = query(bad);
            List<SemanticError> errors = validateRootQueries(List.of(q));

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("ghost_col");
        }
    }

    // =========================================================================
    // SemanticValidator — QueryRelationSymbol body validation
    // =========================================================================

    @Nested
    @DisplayName("SemanticValidator — QueryRelationSymbol body validation")
    class QueryRelationBodyValidation {

        @Test
        @DisplayName("Valid QueryRelationSymbol body produces no errors")
        void validBody() {
            RelNode body = select(truePred(), rel("Users"));
            registerQueryRelation("ActiveUsers", body);

            var validator = new SemanticValidator(table, annotations, SemanticFixtures.FUNCTIONS);
            validator.validate(List.of());
            assertThat(validator.errors()).isEmpty();
        }

        @Test
        @DisplayName("QueryRelationSymbol body with bad projection is an error")
        void badProjectionInBody() {
            RelNode body = project(
                    List.of(ProjectedAttribute.simple(attr("nonexistent"))),
                    rel("Users"));
            registerQueryRelation("BadView", body);

            // Run inference to populate annotations
            var inferErrors = new ArrayList<SemanticError>();
            body.accept(new SchemaInferenceVisitor(table, annotations, inferErrors, "<test>", SemanticFixtures.FUNCTIONS));

            var validator = new SemanticValidator(table, annotations, SemanticFixtures.FUNCTIONS);
            validator.validate(List.of());

            assertThat(validator.errors()).hasSize(1);
            assertThat(validator.errors().get(0).message()).contains("nonexistent");
        }

        private void registerQueryRelation(String name, RelNode body) {
            table.register(new QueryRelationSymbol(
                    "default", name,
                    Provenance.USER, ShadowPolicy.PERMITTED,
                    SymbolCollector.UNRESOLVED_SCHEMA, body));
            // Populate annotations via inference
            var inferErrors = new ArrayList<SemanticError>();
            body.accept(new SchemaInferenceVisitor(table, annotations, inferErrors, "<test>", SemanticFixtures.FUNCTIONS));
            // Update symbol with inferred schema
            new SchemaInferenceEngine(table, annotations, SemanticFixtures.FUNCTIONS).infer(List.of());
        }
    }

    // =========================================================================
    // Rename arity validation
    // =========================================================================

    @Nested
    @DisplayName("RenameNode — attribute count vs input schema width")
    class RenameArityValidation {

        @Test
        @DisplayName("Rename with correct attribute count produces no errors")
        void correctCount() {
            // Users has 3 columns; supply exactly 3 names
            RelNode tree = rename(
                    "E", List.of("eid", "ename", "did"), rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Rename with too few attribute names is an error")
        void tooFewNames() {
            RelNode tree = rename(
                    "E", List.of("eid", "ename"), rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("rename")
                    .contains("2")
                    .contains("3");
        }

        @Test
        @DisplayName("Rename with too many attribute names is an error")
        void tooManyNames() {
            RelNode tree = rename(
                    "E", List.of("a", "b", "c", "d"), rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("rename")
                    .contains("4")
                    .contains("3");
        }

        @Test
        @DisplayName("Rename with empty attribute list (relation-rename only) produces no errors")
        void emptyAttributeList() {
            // No column renaming — only relation alias is given
            RelNode tree = rename("Alias", List.of(), rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Pair rename over existing columns produces no errors")
        void pairRenameValid() {
            RelNode tree = rename(java.util.Optional.of("E"), List.of(),
                    List.of(new RenameNode.RenamePair("name", "full_name")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Pair rename of an unknown source column is an error")
        void pairRenameUnknownSource() {
            RelNode tree = rename(java.util.Optional.of("E"), List.of(),
                    List.of(new RenameNode.RenamePair("nope", "x")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("unknown source column")
                    .contains("nope");
        }

        @Test
        @DisplayName("Pair rename targeting an existing surviving column collides")
        void pairRenameTargetCollision() {
            // Rename name → id, but id already survives → two columns named id
            RelNode tree = rename(java.util.Optional.of("E"), List.of(),
                    List.of(new RenameNode.RenamePair("name", "id")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("collides");
        }

        // Over a schema-on-read input which fields exist is a fact about each document,
        // so only the checks the pairs settle by themselves apply (#977).

        @Test
        @DisplayName("Pair rename over an open input: a source it may not carry is not an error")
        void openPairRenameUnknownSourceIsFine() {
            registerOpen("Docs");
            RelNode tree = rename(java.util.Optional.empty(), List.of(),
                    List.of(new RenameNode.RenamePair("anything", "id")), rel("Docs"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Pair rename over an open input: a source renamed twice is an error")
        void openPairRenameSourceTwice() {
            registerOpen("Docs");
            RelNode tree = rename(java.util.Optional.empty(), List.of(),
                    List.of(new RenameNode.RenamePair("a", "x"), new RenameNode.RenamePair("A", "y")),
                    rel("Docs"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("renamed more than once").contains("A");
        }

        @Test
        @DisplayName("Pair rename over an open input: two pairs with one target collide")
        void openPairRenameDuplicateTarget() {
            registerOpen("Docs");
            RelNode tree = rename(java.util.Optional.of("D"), List.of(),
                    List.of(new RenameNode.RenamePair("a", "x"), new RenameNode.RenamePair("b", "X")),
                    rel("Docs"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("collides").contains("X");
        }

        @Test
        @DisplayName("Two pairs targeting the same new name collide")
        void pairRenameDuplicateTarget() {
            RelNode tree = rename(java.util.Optional.of("E"), List.of(),
                    List.of(new RenameNode.RenamePair("name", "x"),
                            new RenameNode.RenamePair("dept_id", "x")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("collides");
        }

        @Test
        @DisplayName("Rename arity check suppressed when input schema is unresolved")
        void unresolvableInputSuppressesCheck() {
            // Undefined relation → inference fails → arity check silently skipped
            RelNode tree = rename(
                    "E", List.of("a"), rel("NoSuchRel"));
            // Inference produces 1 error (undefined relation); validation adds 0 more
            var inferErrors = new java.util.ArrayList<SemanticError>();
            tree.accept(new SchemaInferenceVisitor(table, annotations, inferErrors, "<test>", SemanticFixtures.FUNCTIONS));
            assertThat(inferErrors).hasSize(1);

            var valErrors = new java.util.ArrayList<SemanticError>();
            tree.accept(new RelAlgebraValidator(table, annotations, SemanticFixtures.FUNCTIONS, valErrors, "<test>"));
            assertThat(valErrors).isEmpty();
        }
    }

    // =========================================================================
    // Projection — UnaryOperand and SetLiteralOperand validation
    // =========================================================================

    @Nested
    @DisplayName("ProjectionNode — UnaryOperand and SetLiteralOperand operands")
    class ProjectionEdgeCases {

        @Test
        @DisplayName("Projection with UnaryOperand validates recursively (no error for valid attribute)")
        void unaryNegationValidated() {
            // π -id (Users) — negation of 'id'; attribute reference is valid
            var unary = unary(attr("id"));
            var tree = project(
                    List.of(ProjectedAttribute.aliased(unary, "neg_id")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("Projection with SetLiteralOperand validates each element")
        void setLiteralInProjectionValidated() {
            // Project a set literal expression — unusual but valid in AST terms
            var setLit = set(
                    num("1"),
                    attr("id"));   // valid attribute reference
            var tree = project(
                    List.of(ProjectedAttribute.aliased(setLit, "set_col")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }
    }

    // =========================================================================
    // End-to-end: full SemanticAnalyzer integration
    // =========================================================================

    @Nested
    @DisplayName("SemanticAnalyzer integration — validation errors surfaced in result")
    class FullPipelineIntegration {

        @Test
        @DisplayName("Valid script with source and selection produces no validation errors")
        void validScript() throws Exception {
            var analyzer = new SemanticAnalyzer(path -> new com.darkcollective.relix.lang.ast.Script(
                    Optional.empty(), List.of()));
            SemanticResult result = analyzer.analyze(ScriptParser.parse(""));

            assertThat(result).isFullyValid();
        }

        @Test
        @DisplayName("Query targeting an undefined relation surfaces an error")
        void undefinedQueryTarget() throws Exception {
            // Parse a script with a named query targeting something not declared
            var src = "query NoSuchThing;";
            var analyzer = new SemanticAnalyzer(path -> new com.darkcollective.relix.lang.ast.Script(
                    Optional.empty(), List.of()));
            SemanticResult result = analyzer.analyze(ScriptParser.parse(src));

            assertThat(result).hasErrors();
            assertThat(result.errors()).anySatisfy(e ->
                    assertThat(e.message()).contains("NoSuchThing"));
        }
    }

    // =========================================================================
    // Goal-seek (SOLVE) — equation validation
    // =========================================================================

    @Nested
    @DisplayName("Goal-seek SOLVE — equation validation")
    class GoalSeek {

        private SolveNode solve(com.darkcollective.relix.ast.Operand left,
                                com.darkcollective.relix.ast.Operand right) {
            return AstBuilders.solve(left, right, rel("Orders"));
        }

        private BinaryArithmeticExpression bin(com.darkcollective.relix.ast.Operand l,
                                               ArithmeticOperator op,
                                               com.darkcollective.relix.ast.Operand r) {
            return arith(l, op, r);
        }

        @Test
        @DisplayName("a well-formed numeric equation produces no errors")
        void valid() {
            RelNode tree = solve(attr("amount"),
                    bin(attr("order_id"), ArithmeticOperator.MULTIPLY, attr("user_id")));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("an unknown column is reported")
        void unknownColumn() {
            RelNode tree = solve(attr("amount"),
                    bin(attr("order_id"), ArithmeticOperator.PLUS, attr("missing")));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("not found").contains("missing");
        }

        @Test
        @DisplayName("a column used twice is rejected (non-deterministic inversion)")
        void duplicateColumn() {
            RelNode tree = solve(attr("amount"),
                    bin(attr("order_id"), ArithmeticOperator.PLUS, attr("order_id")));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("more than once").contains("order_id");
        }

        @Test
        @DisplayName("a non-invertible construct (function call) is rejected")
        void nonInvertible() {
            RelNode tree = solve(attr("amount"),
                    func("Abs",attr("order_id")));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("cannot be inverted");
        }

        @Test
        @DisplayName("an equation with no columns is rejected")
        void noColumns() {
            RelNode tree = solve(num("1"), num("2"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("at least one column");
        }

        @Test
        @DisplayName("a non-numeric column is rejected")
        void nonNumericColumn() {
            // Users.name is STRING; id is NUMBER.
            RelNode tree = AstBuilders.solve(attr("name"), attr("id"), rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("must be NUMBER").contains("name");
        }
    }

    // =========================================================================
    // Declarative optimisation (OPTIMIZE) — objective/constraint validation
    // =========================================================================

    @Nested
    @DisplayName("Optimisation OPTIMIZE — objective/constraint validation")
    class Optimisation {

        private OptimizeConstraint le(String col, double bound) {
            return constraint(attr(col), ComparisonOperator.LESS_EQUAL, bound);
        }

        private OptimizeNode optimize(Operand objective, List<OptimizeConstraint> constraints,
                                      List<String> keys) {
            return AstBuilders.optimize(ObjectiveSense.MAXIMIZE, objective, constraints, keys,
                    rel("Orders"));
        }

        @Test
        @DisplayName("all three constraint operators are accepted; anything else is not")
        void everyLegalConstraintOperator() {
            // <=, >= and = each end the rejecting chain at a different link; a test using
            // only <= never shows that = is admitted rather than merely unreached.
            for (ComparisonOperator op : List.of(ComparisonOperator.LESS_EQUAL,
                    ComparisonOperator.GREATER_EQUAL, ComparisonOperator.EQUAL)) {
                RelNode tree = optimize(attr("amount"),
                        List.of(constraint(attr("order_id"), op, 100)),
                        List.of("user_id"));
                assertThat(inferAndValidate(tree)).as("constraint operator %s", op).isEmpty();
            }
            RelNode strict = optimize(attr("amount"),
                    List.of(constraint(attr("order_id"), ComparisonOperator.LESS, 100)),
                    List.of("user_id"));
            assertThat(inferAndValidate(strict))
                    .as("a strict inequality has no linear-programming meaning here")
                    .anyMatch(e -> e.message().contains("must be <=, >=, or ="));
        }

        @Test
        @DisplayName("a well-formed objective/constraint produces no errors")
        void valid() {
            RelNode tree = optimize(attr("amount"), List.of(le("order_id", 100)),
                    List.of("user_id"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("an unknown grouping key is reported")
        void unknownKey() {
            RelNode tree = optimize(attr("amount"), List.of(le("order_id", 100)),
                    List.of("nope"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("grouping key").contains("nope");
        }

        @Test
        @DisplayName("an unknown objective column is reported")
        void unknownObjectiveColumn() {
            RelNode tree = optimize(attr("missing"), List.of(le("order_id", 100)), List.of());
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("not found").contains("missing");
        }

        @Test
        @DisplayName("an unknown constraint column is reported")
        void unknownConstraintColumn() {
            RelNode tree = optimize(attr("amount"), List.of(le("missing", 100)), List.of());
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("not found").contains("missing");
        }

        @Test
        @DisplayName("an objective with no constraints is rejected")
        void noConstraints() {
            RelNode tree = optimize(attr("amount"), List.of(), List.of());
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).containsIgnoringCase("at least one constraint");
        }

        @Test
        @DisplayName("LP mode: a valid allocation column name produces no errors")
        void lpModeValid() {
            RelNode tree = AstBuilders.optimize(
                    ObjectiveSense.MAXIMIZE, attr("amount"),
                    List.of(le("order_id", 100)), List.of("user_id"),
                    Optional.of(allocation(0.0, 1.0, "weight")),
                    rel("Orders"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("LP mode: allocation column name clashing with an input column is rejected")
        void lpModeAllocationColumnClash() {
            // "amount" already exists in Orders (order_id, user_id, amount, status)
            RelNode tree = AstBuilders.optimize(
                    ObjectiveSense.MAXIMIZE, attr("amount"),
                    List.of(le("order_id", 100)), List.of(),
                    Optional.of(allocation(0.0, 1.0, "amount")),
                    rel("Orders"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .containsIgnoringCase("allocation column")
                    .contains("amount")
                    .containsIgnoringCase("already exists");
        }
    }

    // =========================================================================
    // Pairwise universal semi-join (USEMI)
    // =========================================================================

    @Nested
    @DisplayName("PairwiseUniversalNode USEMI — join condition validation")
    class PairwiseUniversalValidity {

        private static ComparisonPredicate cmp(String left, ComparisonOperator op, String right) {
            return AstBuilders.cmp(attr(left), op,
                    attr(right));
        }

        @Test
        @DisplayName("valid USEMI referencing columns from left and right produces no errors")
        void validUsemi() {
            // Users(id, name, dept_id) USEMI Users.id = Orders.user_id Orders
            RelNode tree = pairwiseUniversal(
                    rel("Users"),
                    rel("Orders"),
                    cmp("Users.id", ComparisonOperator.EQUAL, "Orders.user_id"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("USEMI condition referencing unknown left column is an error")
        void unknownLeftColumn() {
            RelNode tree = pairwiseUniversal(
                    rel("Users"),
                    rel("Orders"),
                    cmp("Users.no_such_col", ComparisonOperator.EQUAL, "Orders.user_id"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("no_such_col");
        }

        @Test
        @DisplayName("USEMI condition referencing unknown right column is an error")
        void unknownRightColumn() {
            RelNode tree = pairwiseUniversal(
                    rel("Users"),
                    rel("Orders"),
                    cmp("Users.id", ComparisonOperator.EQUAL, "Orders.no_such_col"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("no_such_col");
        }

        @Test
        @DisplayName("validation skipped when left input schema is unresolved")
        void unresolvableLeftSkipped() {
            RelNode tree = pairwiseUniversal(
                    rel("Unknown"),
                    rel("Orders"),
                    cmp("Unknown.x", ComparisonOperator.EQUAL, "Orders.user_id"));
            // Should not throw; may produce zero or one error but not cascade
            assertThat(inferAndValidate(tree)).hasSizeLessThanOrEqualTo(1);
        }
    }

    // =========================================================================
    // General recursion (FIX) — monotonicity, linearity, union-compatibility
    // =========================================================================

    @Nested
    @DisplayName("General recursion (FIX) — monotonicity / linearity / union-compat")
    class FixpointValidity {

        @BeforeEach
        void registerEdges() {
            register("Edges", col("src", ScalarType.NUMBER), col("dst", ScalarType.NUMBER));
        }

        /** {@code FIX R (Edges, step)}. */
        private FixpointNode fix(RelNode step) {
            return fixpoint("R", rel("Edges"), step);
        }

        private static RecursiveRefNode ref() {
            return recRef("R");
        }

        private void assertNonMonotone(RelNode step, String reason) {
            assertThat(inferAndValidate(fix(step))).anySatisfy(e ->
                    assertThat(e.message())
                            .contains("Recursive relation 'R'")
                            .contains("non-monotone position (" + reason + ")"));
        }

        // ── Allowed (monotone) positions — no monotonicity error ────────────────

        @Test
        @DisplayName("ref under σ/π/ρ/δ chain is monotone (valid)")
        void monotoneUnaryChain() {
            RelNode step = select(truePred(),
                    project(List.of(
                            projected(attr("src")),
                            projected(attr("dst"))),
                            rename("R2", List.of(), distinct(ref()))));
            assertThat(inferAndValidate(fix(step))).isEmpty();
        }

        @Test
        @DisplayName("ref under μ (unnest) is monotone (valid)")
        void monotoneUnnest() {
            RelNode step = unnest("src", ref());
            assertThat(inferAndValidate(fix(step))).noneSatisfy(e ->
                    assertThat(e.message()).contains("non-monotone"));
        }

        @Test
        @DisplayName("ref on either side of ⋈/⨝/× is monotone (valid)")
        void monotoneJoins() {
            assertThat(inferAndValidate(fix(naturalJoin(ref(), rel("Edges")))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
            assertThat(inferAndValidate(fix(join(rel("Edges"), ref(), truePred()))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
            assertThat(inferAndValidate(fix(product(ref(), rel("Edges")))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
        }

        @Test
        @DisplayName("ref on EITHER side of ⋉ is monotone — semi-join is monotone in both inputs")
        void monotoneSemiJoinBothSides() {
            // Left side: R is the relation being filtered. Adding rows to the growing fixpoint
            // can only add rows to the output (more left rows pass the ∃ check).
            assertThat(inferAndValidate(fix(semiJoin(ref(), rel("Edges"), truePred()))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));

            // Right side: R is the filter source. Adding rows to the right operand can only
            // allow MORE left rows to satisfy the ∃ condition — never remove an already-matching
            // left row. Design decision: both sides of ⋉ are monotone (contrast with ▷ anti-join,
            // where the right side is non-monotone because adding right rows removes output rows).
            assertThat(inferAndValidate(fix(semiJoin(rel("Edges"), ref(), truePred()))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
        }

        @Test
        @DisplayName("ref on the LEFT of ▷ is monotone (valid) — only the right side breaks monotonicity")
        void monotoneAntiJoinLeft() {
            // Adding rows to the left of an anti-join can only grow the output; the right side
            // is non-monotone (tested separately in forbiddenAntiJoinRight).
            assertThat(inferAndValidate(fix(antiJoin(ref(), rel("Edges"), truePred()))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
        }

        @Test
        @DisplayName("ref on either side of ∪/⊎/∩ is monotone (valid)")
        void monotoneSetOps() {
            assertThat(inferAndValidate(fix(union(ref(), rel("Edges")))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
            assertThat(inferAndValidate(fix(unionAll(rel("Edges"), ref()))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
            assertThat(inferAndValidate(fix(intersection(ref(), rel("Edges")))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
        }

        @Test
        @DisplayName("ref on the LEFT of − is monotone (valid)")
        void monotoneDifferenceLeft() {
            assertThat(inferAndValidate(fix(difference(ref(), rel("Edges")))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
        }

        @Test
        @DisplayName("ref on either side of ⊔ (outer union) is monotone (valid)")
        void monotoneOuterUnion() {
            assertThat(inferAndValidate(fix(outerUnion(ref(), rel("Edges")))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
            assertThat(inferAndValidate(fix(outerUnion(rel("Edges"), ref()))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
        }

        @Test
        @DisplayName("ref on the left of LATERAL is monotone — more left rows, more TVF calls")
        void monotoneLateralJoin() {
            // Only the left input is walked: a lateral's arguments are scalar Operands
            // and cannot hold a RecursiveRefNode.
            assertThat(inferAndValidate(fix(lateral(ref(), "someTvf"))))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"));
        }

        @Test
        @DisplayName("UNIT is a leaf — R × UNIT keeps the single ref and the heading")
        void truthRelationIsALeaf() {
            RelNode step = product(ref(), TruthRelationNode.unit(SourceLocation.UNKNOWN));
            assertThat(inferAndValidate(fix(step)))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("linear recursion"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("is not recursive"));
        }

        @Test
        @DisplayName("∅ is an opaque leaf — it holds no ref and does not disturb the count")
        void emptyRelationIsALeaf() {
            // ∅ carries the heading of the expression it replaced as an inert component,
            // not as a child, so the walk must not descend into it and find that ref.
            RelNode empty = EmptyRelationNode.of(select(truePred(), ref()));
            RelNode step = union(ref(), empty);
            assertThat(inferAndValidate(fix(step)))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("linear recursion"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("is not recursive"));
        }

        // ── Forbidden (non-monotone) positions — one test per arm ───────────────

        @Test
        @DisplayName("ref on the RIGHT of − is non-monotone")
        void forbiddenDifferenceRight() {
            assertNonMonotone(difference(rel("Edges"), ref()), "right side of −");
        }

        @Test
        @DisplayName("ref on the RIGHT of ▷ is non-monotone")
        void forbiddenAntiJoinRight() {
            assertNonMonotone(antiJoin(rel("Edges"), ref(), truePred()),
                    "right side of ▷");
        }

        @Test
        @DisplayName("ref anywhere under ÷ is non-monotone")
        void forbiddenDivision() {
            register("DivBy", col("dst", ScalarType.NUMBER));
            assertNonMonotone(division(ref(), rel("DivBy")), "under ÷");
        }

        @Test
        @DisplayName("ref anywhere under ∆ is non-monotone")
        void forbiddenSymDiff() {
            assertNonMonotone(symmetricDifference(ref(), rel("Edges")), "under ∆");
        }

        @Test
        @DisplayName("ref anywhere under ∘ is non-monotone")
        void forbiddenComposition() {
            assertNonMonotone(composition(ref(), rel("Edges")), "under ∘");
        }

        @Test
        @DisplayName("ref under an outer join is non-monotone (left/right/full)")
        void forbiddenOuterJoins() {
            assertNonMonotone(leftJoin(ref(), rel("Edges"), truePred()),
                    "under an outer join");
            assertNonMonotone(rightJoin(rel("Edges"), ref(), truePred()),
                    "under an outer join");
            assertNonMonotone(fullJoin(ref(), rel("Edges"), truePred()),
                    "under an outer join");
        }

        @Test
        @DisplayName("ref under a pairwise-universal is non-monotone")
        void forbiddenPairwiseUniversal() {
            assertNonMonotone(pairwiseUniversal(ref(), rel("Edges"), truePred()),
                    "under ∀ (pairwise)");
        }

        @Test
        @DisplayName("ref under an AS-OF join is non-monotone")
        void forbiddenAsOfJoin() {
            assertNonMonotone(asOfJoin(ref(), rel("Edges"), truePred()),
                    "under an AS-OF join");
        }

        @Test
        @DisplayName("ref under γ is non-monotone")
        void forbiddenAggregation() {
            RelNode step = groupBy(List.of("src"),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "dst")), ref());
            assertNonMonotone(step, "under γ");
        }

        @Test
        @DisplayName("ref under ∀ is non-monotone")
        void forbiddenUniversal() {
            assertNonMonotone(universal(List.of("src"), truePred(), ref()), "under ∀");
        }

        @Test
        @DisplayName("ref under OPTIMIZE is non-monotone")
        void forbiddenOptimize() {
            RelNode step = optimize(ObjectiveSense.MAXIMIZE, attr("dst"),
                    List.of(constraint(attr("dst"), ComparisonOperator.LESS_EQUAL, 10.0)),
                    List.of("src"), ref());
            assertNonMonotone(step, "under OPTIMIZE");
        }

        @Test
        @DisplayName("ref under TOP is non-monotone")
        void forbiddenTopK() {
            RelNode step = topK(List.of("src"),
                    List.of(desc("dst")), 3, ref());
            assertNonMonotone(step, "under TOP");
        }

        @Test
        @DisplayName("ref under SOLVE is non-monotone")
        void forbiddenSolve() {
            RelNode step = solve(attr("src"), attr("dst"), ref());
            assertNonMonotone(step, "under SOLVE");
        }

        @Test
        @DisplayName("ref under SAMPLE p is non-monotone")
        void forbiddenSample() {
            assertNonMonotone(sample(0.5, ref()), "under SAMPLE");
        }

        @Test
        @DisplayName("ref under SAMPLE n ROWS is non-monotone")
        void forbiddenReservoirSample() {
            assertNonMonotone(reservoirSample(10, ref()), "under SAMPLE");
        }

        @Test
        @DisplayName("ref under λ (LIMIT) is non-monotone")
        void forbiddenLimit() {
            assertNonMonotone(limit(5L, ref()), "under λ");
        }

        @Test
        @DisplayName("ref under τ (SORT) is non-monotone")
        void forbiddenSort() {
            RelNode step = sort(
                    List.of(asc("src")), ref());
            assertNonMonotone(step, "under τ");
        }

        @Test
        @DisplayName("ref under CLOSURE is non-monotone")
        void forbiddenClosure() {
            assertNonMonotone(closure("src", "dst",ref()), "under CLOSURE");
        }

        @Test
        @DisplayName("ref under ω (WHY) is non-monotone — reification is a blocking barrier")
        void forbiddenWhy() {
            assertNonMonotone(why(ref()), "under ω (WHY)");
        }

        @Test
        @DisplayName("ref under an interval join is non-monotone")
        void forbiddenIntervalJoin() {
            assertNonMonotone(intervalJoin(ref(), rel("Edges"),
                            AllenRelation.INTERSECTS, "src", "dst", "src", "dst"),
                    "under an interval join");
        }

        @Test
        @DisplayName("ref under DOWNSAMPLE is non-monotone")
        void forbiddenDownsample() {
            RelNode step = downsample("src", "1h", ConsolidationFunction.AVG,
                    ref());
            assertNonMonotone(step, "under DOWNSAMPLE");
        }

        @Test
        @DisplayName("ref under CLUSTER is non-monotone")
        void forbiddenCluster() {
            assertNonMonotone(cluster("src", "dst", "cid",ref()), "under CLUSTER");
        }

        @Test
        @DisplayName("ref under PATH is non-monotone")
        void forbiddenPath() {
            assertNonMonotone(path("src", "dst", 1, 3, "depth",ref()), "under PATH");
        }

        @Test
        @DisplayName("ref under TRACE is non-monotone")
        void forbiddenTrace() {
            // TRACE needs a weight column, so this one recurses over a weighted
            // edge relation rather than the two-column Edges the other cases use.
            register("WEdges", col("src", ScalarType.NUMBER), col("dst", ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode step = trace("src", "dst", "cost",
                    ObjectiveSense.MINIMIZE, "route",ref());
            FixpointNode weighted =
                    fixpoint("R", rel("WEdges"), step);
            assertThat(inferAndValidate(weighted)).anySatisfy(e ->
                    assertThat(e.message())
                            .contains("Recursive relation 'R'")
                            .contains("non-monotone position (under TRACE)"));
        }

        @Test
        @DisplayName("ref under COVER is non-monotone")
        void forbiddenCover() {
            assertNonMonotone(cover(2, ref()), "under COVER");
        }

        @Test
        @DisplayName("ref under WINDOW is non-monotone")
        void forbiddenWindow() {
            RelNode step = window(
                    new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("dst")),
                    List.of("src"),
                    List.of(asc("dst")),
                    new WindowFrame.BoundedFrame(3), "w", ref());
            assertNonMonotone(step, "under WINDOW");
        }

        @Test
        @DisplayName("ref under SESSIONIZE is non-monotone")
        void forbiddenSessionize() {
            RelNode step = sessionize("src", num("2"), "session",ref());
            assertNonMonotone(step, "under SESSIONIZE");
        }

        @Test
        @DisplayName("ref under UNPIVOT is non-monotone")
        void forbiddenUnpivot() {
            RelNode step = unpivot(List.of("src", "dst"), "k", "v", ref());
            assertNonMonotone(step, "under UNPIVOT");
        }

        @Test
        @DisplayName("ref under PIVOT is non-monotone")
        void forbiddenPivot() {
            assertNonMonotone(pivot("dst", "src", List.of(), ref()), "under PIVOT");
        }

        @Test
        @DisplayName("ref under TREE is non-monotone")
        void forbiddenTree() {
            RelNode step = tree("src", "dst", "children",ref());
            assertNonMonotone(step, "under TREE");
        }

        @Test
        @DisplayName("the OUTERMOST forbidden edge names the error (σ under − right)")
        void outermostForbiddenEdgeWins() {
            // σ true (R) sits on the right of −; the − edge is the first forbidden one.
            RelNode step = difference(rel("Edges"),
                    select(truePred(), ref()));
            assertNonMonotone(step, "right side of −");
        }

        @Test
        @DisplayName("a forbidden edge nested under another keeps the OUTER reason")
        void outermostReasonAcrossStackedForbidden() {
            // λ over (Edges − R): both λ and the − right are forbidden, but λ is
            // outermost on the path to R, so its reason wins.
            RelNode step = limit(5L,
                    difference(rel("Edges"), ref()));
            assertNonMonotone(step, "under λ");
        }

        // ── Linearity ───────────────────────────────────────────────────────────

        @Test
        @DisplayName("step with zero references to the binder is rejected (not recursive)")
        void zeroReferences() {
            assertThat(inferAndValidate(fix(select(truePred(), rel("Edges")))))
                    .anySatisfy(e -> assertThat(e.message())
                            .contains("is not recursive").contains("drop the FIX"));
        }

        @Test
        @DisplayName("step with two references to the binder is rejected (non-linear)")
        void twoReferences() {
            assertThat(inferAndValidate(fix(union(ref(), ref()))))
                    .anySatisfy(e -> assertThat(e.message())
                            .contains("linear recursion only").contains("found 2"));
        }

        /**
         * The linearity count is a sum over each binary operator's two sides, written out
         * nineteen times, and only {@code ∪} had a test that put a reference on both. The
         * other arms could have been counting the difference rather than the sum and every
         * suite would still have passed — {@code T ⋈ T} would then be reported as "not
         * recursive" instead of non-linear, and a deeper nesting could go unreported
         * altogether. Nothing about the operator makes its arm more likely to be right, so
         * each one is asked the same question.
         */
        @TestFactory
        @DisplayName("two references are non-linear through every monotone binary operator")
        Stream<DynamicTest> twoReferencesThroughEveryMonotoneBinary() {
            Map<String, BinaryOperator<RelNode>> operators = new LinkedHashMap<>();
            operators.put("∪", AstBuilders::union);
            operators.put("⊎", AstBuilders::unionAll);
            operators.put("⊔", AstBuilders::outerUnion);
            operators.put("∩", AstBuilders::intersection);
            operators.put("×", AstBuilders::product);
            operators.put("⋈", AstBuilders::naturalJoin);
            operators.put("⨝", (l, r) -> join(l, r, truePred()));
            operators.put("⋉", (l, r) -> semiJoin(l, r, truePred()));

            return operators.entrySet().stream().map(e -> DynamicTest.dynamicTest(
                    e.getKey(),
                    () -> assertThat(inferAndValidate(fix(e.getValue().apply(ref(), ref()))))
                            .as("a reference on each side of %s is two references", e.getKey())
                            .anySatisfy(err -> assertThat(err.message())
                                    .contains("linear recursion only").contains("found 2"))));
        }

        /**
         * The same question of the arms that also forbid the reference. Two references
         * are still two, and the count and the monotonicity report are separate claims:
         * an arm could report the forbidden position correctly while counting the sides'
         * difference, and the query would be rejected for one reason instead of two.
         */
        @TestFactory
        @DisplayName("two references are non-linear through every non-monotone binary operator too")
        Stream<DynamicTest> twoReferencesThroughEveryNonMonotoneBinary() {
            Map<String, BinaryOperator<RelNode>> operators = new LinkedHashMap<>();
            operators.put("−", AstBuilders::difference);
            operators.put("∆", AstBuilders::symmetricDifference);
            operators.put("÷", AstBuilders::division);
            operators.put("∘", AstBuilders::composition);
            operators.put("▷", (l, r) -> antiJoin(l, r, truePred()));
            operators.put("⟕", (l, r) -> leftJoin(l, r, truePred()));
            operators.put("⟖", (l, r) -> rightJoin(l, r, truePred()));
            operators.put("⟗", (l, r) -> fullJoin(l, r, truePred()));
            operators.put("∀ (pairwise)", (l, r) -> pairwiseUniversal(l, r, truePred()));
            operators.put("AS-OF", (l, r) -> asOfJoin(l, r, truePred()));
            operators.put("interval", (l, r) -> intervalJoin(l, r, AllenRelation.OVERLAPS,
                    "src", "dst", "src", "dst"));

            return operators.entrySet().stream().map(e -> DynamicTest.dynamicTest(
                    e.getKey(),
                    () -> assertThat(inferAndValidate(fix(e.getValue().apply(ref(), ref()))))
                            .as("both sides of %s hold a reference", e.getKey())
                            .anySatisfy(err -> assertThat(err.message())
                                    .contains("linear recursion only").contains("found 2"))
                            .anySatisfy(err -> assertThat(err.message())
                                    .contains("non-monotone position"))));
        }

        @Test
        @DisplayName("a nested FIX under a DIFFERENT name shadows nothing, so both its sides count")
        void nestedDifferentNameCountsBothSides() {
            // FIX R (Edges, FIX S (R, R)) — the inner binder is S, so the outer R is in
            // scope through the inner base *and* its step, and that is two references.
            // Only the shadowing case had a test, which left the arm free to count the
            // base alone and call a non-linear recursion linear.
            RelNode step = fixpoint("S", ref(), ref());
            assertThat(inferAndValidate(fix(step)))
                    .anySatisfy(e -> assertThat(e.message())
                            .contains("linear recursion only").contains("found 2"));
        }

        @Test
        @DisplayName("a nested FIX REUSING the binder name shadows the outer ref in its step")
        void nestedSameNameShadowsStep() {
            // FIX R (Edges, FIX R (Edges, R) ∪ R) — the inner FIX R's step `R` binds
            // the inner binder, so only the outer `R` (right of ∪) counts: exactly one.
            RelNode inner = fixpoint("R", rel("Edges"),
                    select(truePred(), recRef("R")));
            RelNode step = union(inner, ref());
            assertThat(inferAndValidate(fix(step)))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("linear recursion"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("is not recursive"));
        }

        @Test
        @DisplayName("a table-valued-function call in the step is an opaque leaf (no ref)")
        void tableValuedFunctionCallIsOpaqueLeaf() {
            // The TVF call carries no recursive reference; the walk must treat it as
            // a leaf and still find the single ref elsewhere in the step.
            RelNode tvf = tvf("someTvf");
            RelNode step = union(ref(), tvf);
            assertThat(inferAndValidate(fix(step)))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("linear recursion"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("is not recursive"));
        }

        @Test
        @DisplayName("a nested FIX with a different name keeps its own refs out of the outer count")
        void nestedDifferentNameNotCounted() {
            // FIX R (Edges, FIX S (Edges, S) ∪ R) — inner S refs belong to S, not R;
            // the outer step still has exactly one R reference, so it is valid.
            RelNode inner = fixpoint("S", rel("Edges"),
                    select(truePred(), recRef("S")));
            RelNode step = union(inner, ref());
            assertThat(inferAndValidate(fix(step)))
                    .noneSatisfy(e -> assertThat(e.message()).contains("non-monotone"))
                    .noneSatisfy(e -> assertThat(e.message()).contains("linear recursion"));
        }

        // ── Union-compatibility (schema rule) ─────────────────────────────────────

        @Test
        @DisplayName("step union-compatible with base is valid")
        void compatibleStep() {
            assertThat(inferAndValidate(fix(select(truePred(), ref())))).isEmpty();
        }

        @Test
        @DisplayName("step of different width is not union-compatible")
        void widthMismatch() {
            RelNode step = project(
                    List.of(projected(attr("src"))), ref());
            assertThat(inferAndValidate(fix(step))).anySatisfy(e ->
                    assertThat(e.message()).contains("not union-compatible")
                            .contains("column(s)"));
        }

        @Test
        @DisplayName("step with a positional type mismatch is not union-compatible")
        void typeMismatch() {
            // step yields (a: STRING, b: NUMBER); base is (src: NUMBER, dst: NUMBER)
            RelNode step = project(List.of(
                    projected(str("x"),"a"),
                    projected(attr("dst"),"b")), ref());
            assertThat(inferAndValidate(fix(step))).anySatisfy(e ->
                    assertThat(e.message()).contains("not union-compatible")
                            .contains("type mismatch"));
        }

        @Test
        @DisplayName("an open base skips the union-compatibility check")
        void openBaseSkipsCompat() {
            table.register(new SourceRelationSymbol(
                    "default", "Docs", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));
            FixpointNode openFix = fixpoint("R", rel("Docs"),
                    select(truePred(), recRef("R")));
            assertThat(inferAndValidate(openFix)).noneSatisfy(e ->
                    assertThat(e.message()).contains("union-compatible"));
        }

        @Test
        @DisplayName("union-compat check is skipped when the BASE is unresolved")
        void unresolvedBaseSkipsCompat() {
            // base relation does not exist → its schema is unannotated; the compat
            // check bails out without throwing (and the step still gets its walk).
            FixpointNode badFix = fixpoint("R", rel("Missing"),
                    select(truePred(), ref()));
            assertThat(inferAndValidate(badFix)).noneSatisfy(e ->
                    assertThat(e.message()).contains("not union-compatible"));
        }

        @Test
        @DisplayName("union-compat check is skipped when inference left a side unresolved")
        void unresolvedSideSkipsCompat() {
            // base resolves, but the step references an undefined relation → step
            // schema is unresolved; the compat check bails out rather than throwing.
            FixpointNode badFix = fixpoint("R", rel("Edges"),
                    union(ref(), rel("Missing")));
            // No crash; no union-compat error from the (now unannotated) step.
            assertThat(inferAndValidate(badFix)).noneSatisfy(e ->
                    assertThat(e.message()).contains("not union-compatible"));
        }
    }

    // =========================================================================
    // Natural join — common-column diagnostics (#181)
    // =========================================================================

    @Nested
    @DisplayName("Natural join — common-column diagnostics")
    class NaturalJoinDiagnostics {

        @Test
        @DisplayName("natural join with a shared column produces no error")
        void sharedColumnProducesNoError() {
            // Users(id, name, dept_id) ⋈ Depts(dept_id, dept_name) — common: dept_id
            RelNode tree = naturalJoin(
                    rel("Users"), rel("Depts"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("natural join of disjoint schemas reports an error mentioning CROSS")
        void disjointSchemasProducesError() {
            // Users(id, name, dept_id) ⋈ Orders(order_id, user_id, amount) — no common column
            RelNode tree = naturalJoin(
                    rel("Users"), rel("Orders"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message())
                    .contains("no common columns")
                    .contains("CROSS");
        }

        @Test
        @DisplayName("error message lists column names from both sides")
        void errorMessageListsColumnNames() {
            RelNode tree = naturalJoin(
                    rel("Users"), rel("Orders"));
            String message = inferAndValidate(tree).get(0).message();
            assertThat(message).contains("id").contains("order_id");
        }

        @Test
        @DisplayName("no false positive when a subtree's schema could not be inferred")
        void unresolvedSchemaSkipsCheck() {
            // One input is a missing relation — inference returns Optional.empty() and
            // leaves the node unannotated; the validator guard bails out early so no
            // spurious no-common-columns error is reported on top of the inference error.
            RelNode tree = naturalJoin(
                    rel("Users"), rel("DoesNotExist"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).noneSatisfy(e ->
                    assertThat(e.message()).contains("no common columns"));
        }
    }

    // =========================================================================
    // Temporal arithmetic and comparison (ADR-0013 slice 3)
    // =========================================================================

    @Nested
    @DisplayName("Temporal arithmetic and comparison")
    class TemporalValidity {

        @org.junit.jupiter.api.BeforeEach
        void registerEvents() {
            register("Events",
                    col("at",     ScalarType.TIMESTAMP),
                    col("opened", ScalarType.TIMESTAMP),
                    col("held",   ScalarType.DURATION),
                    col("day",    ScalarType.DATE),
                    col("n",      ScalarType.NUMBER));
        }

        private SelectionNode selectWhere(ComparisonPredicate p) {
            return select(p, rel("Events"));
        }

        @Test
        @DisplayName("legal arithmetic in a predicate is accepted (ts − ts = DURATION)")
        void legalArithmetic() {
            ComparisonPredicate p = cmp(
                    arith(attr("at"), ArithmeticOperator.MINUS, attr("opened")),
                    ComparisonOperator.EQUAL, attr("held"));
            assertThat(inferAndValidate(selectWhere(p))).isEmpty();
        }

        @Test
        @DisplayName("illegal arithmetic is a precise error (TIMESTAMP + NUMBER)")
        void illegalArithmetic() {
            ComparisonPredicate p = cmp(
                    arith(attr("at"), ArithmeticOperator.PLUS, attr("n")),
                    ComparisonOperator.EQUAL, attr("at"));
            List<SemanticError> errors = inferAndValidate(selectWhere(p));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("cannot add NUMBER to TIMESTAMP");
        }

        @Test
        @DisplayName("comparing different temporal types is an error (TIMESTAMP vs DATE)")
        void illegalCrossTemporalComparison() {
            ComparisonPredicate p = cmp(
                    attr("at"), ComparisonOperator.EQUAL, attr("day"));
            List<SemanticError> errors = inferAndValidate(selectWhere(p));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("cannot compare TIMESTAMP with DATE");
        }

        @Test
        @DisplayName("comparing a temporal with a number is an error")
        void illegalTemporalVsNumber() {
            ComparisonPredicate p = cmp(
                    attr("at"), ComparisonOperator.GREATER_EQUAL, attr("n"));
            List<SemanticError> errors = inferAndValidate(selectWhere(p));
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("cannot compare");
        }

        @Test
        @DisplayName("comparing the same temporal type is accepted")
        void legalSameTypeComparison() {
            ComparisonPredicate p = cmp(
                    attr("at"), ComparisonOperator.GREATER_EQUAL, attr("opened"));
            assertThat(inferAndValidate(selectWhere(p))).isEmpty();
        }
    }

    // =========================================================================
    // AS-OF join shape (ADR-0014)
    // =========================================================================

    @Nested
    @DisplayName("AS-OF join — match-condition shape")
    class AsOfValidity {

        private RelNode asOf(com.darkcollective.relix.ast.Predicate condition) {
            return asOfJoin(rel("Orders"), rel("Users"), condition);
        }

        private ComparisonPredicate eq() {
            return cmp(attr("Orders.user_id"), ComparisonOperator.EQUAL, attr("Users.id"));
        }

        private ComparisonPredicate ineq(ComparisonOperator op) {
            return cmp(attr("Orders.amount"), op, attr("Users.dept_id"));
        }

        @Test
        @DisplayName("equality keys + one ordering inequality is valid")
        void validBackward() {
            assertThat(inferAndValidate(asOf(
                    and(eq(), ineq(ComparisonOperator.GREATER_EQUAL))))).isEmpty();
        }

        @Test
        @DisplayName("a single ordering inequality with no partition key is valid")
        void validNoPartition() {
            assertThat(inferAndValidate(asOf(ineq(ComparisonOperator.LESS_EQUAL)))).isEmpty();
        }

        @Test
        @DisplayName("an OR makes it not a conjunction → error")
        void rejectsOr() {
            var errors = inferAndValidate(asOf(or(eq(), ineq(ComparisonOperator.GREATER))));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).containsIgnoringCase("conjunction");
        }

        @Test
        @DisplayName("two ordering inequalities → error")
        void rejectsTwoInequalities() {
            var errors = inferAndValidate(asOf(and(
                    ineq(ComparisonOperator.GREATER_EQUAL), ineq(ComparisonOperator.LESS))));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).containsIgnoringCase("exactly one ordering inequality");
        }

        @Test
        @DisplayName("no ordering inequality (only equality) → error")
        void rejectsNoInequality() {
            var errors = inferAndValidate(asOf(eq()));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).containsIgnoringCase("exactly one ordering inequality");
        }

        @Test
        @DisplayName("a ≠ conjunct → error")
        void rejectsNotEqual() {
            var errors = inferAndValidate(asOf(and(
                    ineq(ComparisonOperator.GREATER_EQUAL),
                    cmp(attr("Orders.order_id"), ComparisonOperator.NOT_EQUAL, attr("Users.id")))));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).containsIgnoringCase("may not use");
        }
    }

    // =========================================================================
    // Interval join column validation (ADR-0015)
    // =========================================================================

    @Nested
    @DisplayName("Interval join — column existence checks")
    class IntervalJoinValidity {

        /**
         * Builds an IJOIN over Orders (left) and Users (right) with the
         * given endpoint column names.
         */
        private com.darkcollective.relix.ast.RelNode ijoin(
                String leftStart, String leftEnd, String rightStart, String rightEnd) {
            return new com.darkcollective.relix.ast.IntervalJoinNode(
                    new com.darkcollective.relix.ast.RelationNode("Orders"),
                    new com.darkcollective.relix.ast.RelationNode("Users"),
                    com.darkcollective.relix.ast.AllenRelation.INTERSECTS,
                    leftStart, leftEnd, rightStart, rightEnd);
        }

        @Test
        @DisplayName("valid: all four endpoint columns exist in their schemas")
        void validIjoin() {
            // Orders: order_id, user_id, amount  — Users: id, name, dept_id
            var errors = inferAndValidate(ijoin("order_id", "amount", "id", "dept_id"));
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("invalid: left-start column does not exist → error")
        void rejectsUnknownLeftStart() {
            var errors = inferAndValidate(ijoin("no_such_col", "amount", "id", "dept_id"));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).containsIgnoringCase("left-start");
        }

        @Test
        @DisplayName("invalid: right-end column does not exist → error")
        void rejectsUnknownRightEnd() {
            var errors = inferAndValidate(ijoin("order_id", "amount", "id", "no_such_col"));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).containsIgnoringCase("right-end");
        }

        @Test
        @DisplayName("valid: qualified endpoint names (Orders.order_id, …) resolve after stripping the qualifier")
        void acceptsQualifiedEndpoints() {
            var errors = inferAndValidate(
                    ijoin("Orders.order_id", "Orders.amount", "Users.id", "Users.dept_id"));
            assertThat(errors).isEmpty();
        }
    }

    // =========================================================================
    // PatternPredicate (LIKE) validation
    // =========================================================================

    @Nested
    @DisplayName("PatternPredicate (LIKE) — column reference validation")
    class LikeValidation {

        @Test
        @DisplayName("LIKE on a known column is valid → no errors")
        void likeOnKnownColumn() {
            RelNode tree = select(
                    like(attr("name"), str("%alice%")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("NOT LIKE on a known column is valid → no errors")
        void notLikeOnKnownColumn() {
            RelNode tree = select(
                    notLike(attr("name"), str("test%")),
                    rel("Users"));
            assertThat(inferAndValidate(tree)).isEmpty();
        }

        @Test
        @DisplayName("LIKE on an unknown column produces an error")
        void likeOnUnknownColumn() {
            RelNode tree = select(
                    like(attr("no_such_col"), str("%x%")),
                    rel("Users"));
            List<SemanticError> errors = inferAndValidate(tree);
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).contains("no_such_col");
        }
    }

    // =========================================================================
    // Schema-on-read defers a check — from either side
    // =========================================================================

    /**
     * The checks that step aside when an input has no fixed shape.
     *
     * <p>An open (schema-on-read) schema declares nothing, so a check that compares
     * headings cannot run against one and every such check opens with
     * {@code if (left.isOpen() || right.isOpen()) return}. That is two arms, and a fixture
     * that only ever opens the <em>left</em> short-circuits before the right is consulted
     * — leaving the arm that would report a spurious error over a JSON/HTTP/Mongo relation
     * on the right-hand side of an operator entirely unexercised.
     *
     * <p>These are all negative assertions by nature: the property is that nothing is
     * reported. Each therefore carries its closed-schema counterpart, so an empty error
     * list cannot be mistaken for a check that never ran.
     */
    @Nested
    @DisplayName("an open input defers a check, whichever side it is on")
    class OpenSchemaDefers {

        @BeforeEach
        void registerRelations() {
            register("Left2", col("a", ScalarType.NUMBER), col("b", ScalarType.NUMBER));
            register("Right3", col("x", ScalarType.NUMBER), col("y", ScalarType.NUMBER),
                    col("z", ScalarType.NUMBER));
            registerOpen("OpenDocs");
        }

        private RelNode setOp(String op, RelNode left, RelNode right) {
            return switch (op) {
                case "∪" -> union(left, right);
                case "∩" -> intersection(left, right);
                default  -> difference(left, right);
            };
        }

        @Test
        @DisplayName("a set operation over mismatched widths reports — unless a side is open")
        void setOperationWidthCheck() {
            for (String op : List.of("∪", "∩", "−")) {
                assertThat(inferAndValidate(
                        setOp(op, rel("Left2"), rel("Right3"))))
                        .as("%s over two closed schemas of different width", op)
                        .isNotEmpty();
                assertThat(inferAndValidate(
                        setOp(op, rel("OpenDocs"), rel("Right3"))))
                        .as("%s with the OPEN side on the left", op)
                        .isEmpty();
                assertThat(inferAndValidate(
                        setOp(op, rel("Left2"), rel("OpenDocs"))))
                        .as("%s with the OPEN side on the right", op)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a natural join with no shared column reports — unless a side is open")
        void naturalJoinCommonColumnCheck() {
            assertThat(inferAndValidate(
                    naturalJoin(rel("Left2"), rel("Right3"))))
                    .as("two closed schemas sharing no column name")
                    .isNotEmpty();
            assertThat(inferAndValidate(
                    naturalJoin(rel("OpenDocs"), rel("Right3"))))
                    .as("OPEN on the left").isEmpty();
            assertThat(inferAndValidate(
                    naturalJoin(rel("Left2"), rel("OpenDocs"))))
                    .as("OPEN on the right").isEmpty();
        }

        @Test
        @DisplayName("an interval join checks each side's endpoints only when that side is closed")
        void intervalJoinEndpointCheck() {
            register("Stays", col("checkin", ScalarType.TIMESTAMP),
                    col("checkout", ScalarType.TIMESTAMP));
            register("Bookings", col("bfrom", ScalarType.TIMESTAMP),
                    col("bto", ScalarType.TIMESTAMP));

            assertThat(inferAndValidate(intervalJoin(
                    rel("Stays"), rel("Bookings"),
                    AllenRelation.OVERLAPS, "ghost", "checkout", "bfrom", "bto")))
                    .as("a bad LEFT endpoint over a closed left").isNotEmpty();
            assertThat(inferAndValidate(intervalJoin(
                    rel("Stays"), rel("Bookings"),
                    AllenRelation.OVERLAPS, "checkin", "checkout", "ghost", "bto")))
                    .as("a bad RIGHT endpoint over a closed right").isNotEmpty();

            assertThat(inferAndValidate(intervalJoin(
                    rel("OpenDocs"), rel("Bookings"),
                    AllenRelation.OVERLAPS, "anything", "at_all", "bfrom", "bto")))
                    .as("an OPEN left defers its own endpoints, not the right's").isEmpty();
            assertThat(inferAndValidate(intervalJoin(
                    rel("Stays"), rel("OpenDocs"),
                    AllenRelation.OVERLAPS, "checkin", "checkout", "anything", "at_all")))
                    .as("an OPEN right defers its own endpoints").isEmpty();
        }

        @Test
        @DisplayName("an ANY-typed column defers a type check, from either endpoint")
        void anyTypedColumnsDeferTheTypeCheck() {
            // ANY is the schema-on-read column type: it says the shape varies, so a
            // same-type comparison against it can only be answered at run time. The rule
            // exempts *each* endpoint separately, and a fixture whose ANY column is always
            // the `from` short-circuits before the `to` exemption is reached.
            register("Mixed",
                    col("num", ScalarType.NUMBER),
                    col("txt", ScalarType.STRING),
                    col("dyn", ScalarType.ANY),
                    col("dyn2", ScalarType.ANY));

            assertThat(inferAndValidate(
                    path("num", "txt", 1, 3, "depth",rel("Mixed"))))
                    .as("PATH over two known, different types reports").isNotEmpty();
            assertThat(inferAndValidate(
                    path("dyn", "txt", 1, 3, "depth",rel("Mixed"))))
                    .as("PATH with ANY as 'from'").isEmpty();
            assertThat(inferAndValidate(
                    path("num", "dyn", 1, 3, "depth",rel("Mixed"))))
                    .as("PATH with ANY as 'to'").isEmpty();

            assertThat(inferAndValidate(
                    cluster("num", "txt", "component",rel("Mixed"))))
                    .as("CLUSTER over two known, different types reports").isNotEmpty();
            assertThat(inferAndValidate(
                    cluster("dyn", "txt", "component",rel("Mixed"))))
                    .as("CLUSTER with ANY as 'from'").isEmpty();
            assertThat(inferAndValidate(
                    cluster("num", "dyn", "component",rel("Mixed"))))
                    .as("CLUSTER with ANY as 'to'").isEmpty();
        }

        @Test
        @DisplayName("DOWNSAMPLE accepts an ANY timestamp column, and rejects a known wrong one")
        void downsampleTimestampColumnType() {
            register("Readings",
                    col("at", ScalarType.TIMESTAMP),
                    col("label", ScalarType.STRING),
                    col("dyn", ScalarType.ANY),
                    col("v", ScalarType.NUMBER));

            assertThat(inferAndValidate(downsample("at", "5m",
                    ConsolidationFunction.AVG,
                    rel("Readings"))))
                    .as("a real TIMESTAMP").isEmpty();
            assertThat(inferAndValidate(downsample("dyn", "5m",
                    ConsolidationFunction.AVG,
                    rel("Readings"))))
                    .as("ANY — the shape is not known until run time").isEmpty();
            assertThat(inferAndValidate(downsample("label", "5m",
                    ConsolidationFunction.AVG,
                    rel("Readings"))))
                    .as("a column known not to be a timestamp").isNotEmpty();
        }

        @Test
        @DisplayName("a rename-pair check is skipped over an open input")
        void renamePairCheckOverAnOpenInput() {
            assertThat(inferAndValidate(rename(Optional.empty(), List.of(),
                    List.of(new RenameNode.RenamePair("ghost", "other")),
                    rel("Left2"))))
                    .as("a closed input reports the unknown old name").isNotEmpty();
            assertThat(inferAndValidate(rename(Optional.empty(), List.of(),
                    List.of(new RenameNode.RenamePair("ghost", "other")),
                    rel("OpenDocs"))))
                    .as("an open input declares nothing, so nothing is unknown").isEmpty();
        }
    }

    // =========================================================================
    // Checks that step aside because an input has no schema at all
    // =========================================================================

    /**
     * The other half of the deferral guards: not "the heading is open" but "there is no
     * heading yet".
     *
     * <p>Every binary check opens with {@code leftOpt.isEmpty() || rightOpt.isEmpty()}, and
     * a node whose own inference failed carries no annotation. The undefined relation has
     * already been reported once, by name — piling a derived complaint on top of it turns
     * one honest diagnosis into two, the second of which is about a heading nobody wrote.
     * Each side is a separate arm, and the fixtures only ever failed on the left.
     */
    @Nested
    @DisplayName("an unresolvable input silences the check that needed its schema")
    class UnresolvableInputs {

        @BeforeEach
        void registerRelations() {
            register("L2", col("a", ScalarType.NUMBER), col("b", ScalarType.NUMBER));
            register("Stays", col("checkin", ScalarType.TIMESTAMP),
                    col("checkout", ScalarType.TIMESTAMP));
            register("Bookings", col("bfrom", ScalarType.TIMESTAMP),
                    col("bto", ScalarType.TIMESTAMP));
            registerOpen("OpenDocs");
        }

        private static Predicate eqCols(String left, String right) {
            return cmp(attr(left),
                    ComparisonOperator.EQUAL, attr(right));
        }

        private List<String> messages(RelNode tree) {
            return inferAndValidate(tree).stream().map(SemanticError::message).toList();
        }

        @Test
        @DisplayName("⋈ says nothing about shared columns when either side is unresolvable")
        void naturalJoinWithAnUnresolvableSide() {
            assertThat(messages(naturalJoin(rel("Ghost"), rel("L2"))))
                    .as("left unresolvable")
                    .noneSatisfy(m -> assertThat(m).contains("share"));
            assertThat(messages(naturalJoin(rel("L2"), rel("Ghost"))))
                    .as("right unresolvable")
                    .noneSatisfy(m -> assertThat(m).contains("share"));
        }

        @Test
        @DisplayName("an interval join checks each side's endpoints only when that side has a schema")
        void intervalJoinWithAnUnresolvableSide() {
            assertThat(messages(intervalJoin(
                    rel("Ghost"), rel("Bookings"),
                    AllenRelation.OVERLAPS, "nope", "nope2", "bfrom", "bto")))
                    .as("an unresolvable left cannot answer for its own endpoints")
                    .noneSatisfy(m -> assertThat(m).contains("left-start"));
            assertThat(messages(intervalJoin(
                    rel("Stays"), rel("Ghost"),
                    AllenRelation.OVERLAPS, "checkin", "checkout", "nope", "nope2")))
                    .as("nor an unresolvable right")
                    .noneSatisfy(m -> assertThat(m).contains("right-start"));
        }

        @Test
        @DisplayName("⟖ and ⟗ reject an OPEN input from either side")
        void unmatchedRightOverAnOpenInput() {
            for (RelNode tree : List.of(
                    rightJoin(rel("OpenDocs"), rel("L2"),
                            eqCols("a", "a")),
                    rightJoin(rel("L2"), rel("OpenDocs"),
                            eqCols("a", "a")),
                    fullJoin(rel("OpenDocs"), rel("L2"),
                            eqCols("a", "a")),
                    fullJoin(rel("L2"), rel("OpenDocs"),
                            eqCols("a", "a")))) {
                assertThat(messages(tree))
                        .as("%s", tree.getClass().getSimpleName())
                        .anySatisfy(m -> assertThat(m).contains("open (schema-on-read) input"));
            }
            assertThat(messages(fullJoin(rel("L2"),
                    rel("Stays"), eqCols("a", "checkin"))))
                    .as("two closed inputs are fine — the guard is about openness")
                    .noneSatisfy(m -> assertThat(m).contains("open (schema-on-read) input"));
        }

        @Test
        @DisplayName("an AS-OF condition is rejected when its LEFT conjunct is not a comparison")
        void asOfConditionRejectsANonConjunctionOnTheLeft() {
            // flattenConjuncts recurses `left && right`, so a bad *left* branch
            // short-circuits and the right is never walked. Only a bad right had a case,
            // which leaves the short-circuit itself unexercised.
            Predicate ok = eqCols("checkin", "bfrom");
            Predicate bad = or(ok, ok);
            assertThat(messages(asOfJoin(rel("Stays"),
                    rel("Bookings"), and(bad, ok))))
                    .anySatisfy(m -> assertThat(m).contains("must be a conjunction"));
        }
    }
}
