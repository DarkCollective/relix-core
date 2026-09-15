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
package com.darkcollective.relix.processor.provenance;

import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.provenance.BooleanSemiring;
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.provenance.PathCost;
import com.darkcollective.relix.provenance.PathCostSemiring;
import com.darkcollective.relix.provenance.Polynomial;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.provenance.SourceRef;
import com.darkcollective.relix.provenance.TropicalSemiring;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end provenance evaluation through {@link QueryExecutor#executeProvenance},
 * which exercises {@link ProvenanceEvaluator}. The counting (ℕ) semiring is used
 * wherever a multiplicity must be visible; the boolean tests pin set semantics.
 */
@DisplayName("ProvenanceEvaluator (via QueryExecutor.executeProvenance)")
final class ProvenanceEvaluatorTest {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger THREE = BigInteger.valueOf(3);

    /** Evaluates the first query of {@code src} as a K-relation over {@code semiring}. */
    private static <K> AnnotatedRelation<K> prov(String src, Semiring<K> semiring) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        SemanticModel model = result.model().orElseThrow();
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        List<AnnotatedRelation<K>> out = new ArrayList<>();
        EXECUTOR.executeProvenance(model, ctx.connector(), semiring, (label, rel) -> out.add(rel));
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    /**
     * Evaluates the first query of {@code src} as a weighted K-relation, reading
     * per-edge weights from {@code weightColumn} (null = the {@code one()} lift) and
     * bounding the closure fixpoint to {@code maxRounds}.
     */
    private static <K> AnnotatedRelation<K> provWeighted(
            String src, Semiring<K> semiring, String weightColumn, int maxRounds) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        SemanticModel model = result.model().orElseThrow();
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        List<AnnotatedRelation<K>> out = new ArrayList<>();
        EXECUTOR.executeProvenance(model, ctx.connector(), semiring, weightColumn, maxRounds,
                (label, rel) -> out.add(rel));
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    /** Evaluates the first query of {@code src} under the polynomial-lineage semiring. */
    private static AnnotatedRelation<Polynomial> lineage(String src) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        SemanticModel model = result.model().orElseThrow();
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        List<AnnotatedRelation<Polynomial>> out = new ArrayList<>();
        EXECUTOR.executeLineage(model, ctx.connector(), (label, rel) -> out.add(rel));
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    /** Maps each tuple (by its cell display strings) to its annotation. */
    private static <K> Map<List<String>, K> annotations(AnnotatedRelation<K> rel) {
        Map<List<String>, K> map = new LinkedHashMap<>();
        rel.stream().forEach(a -> {
            List<String> key = new ArrayList<>();
            for (int i = 0; i < a.row().width(); i++) {
                key.add(a.row().get(i).isNull() ? "NULL" : a.row().get(i).asDisplayString());
            }
            map.put(key, a.annotation());
        });
        return map;
    }

    @Nested
    @DisplayName("base relations")
    class BaseRelations {

        @Test
        @DisplayName("lift combines duplicate input rows into a multiplicity")
        void liftMultiplicity() {
            var rel = prov("""
                    R := [| x |
                           | a |
                           | a |
                           | b |];
                    query R;
                    """, CountingSemiring.INSTANCE);

            assertThat(annotations(rel))
                    .containsEntry(List.of("a"), TWO)
                    .containsEntry(List.of("b"), ONE);
        }
    }

    @Nested
    @DisplayName("selection (σ)")
    class Selection {

        @Test
        @DisplayName("keeps matching tuples with their annotation, drops the rest")
        void filters() {
            var rel = prov("""
                    R := [| x | k |
                           | a | 1 |
                           | a | 1 |
                           | b | 1 |];
                    query { σ x = "a" (R) };
                    """, CountingSemiring.INSTANCE);

            Map<List<String>, BigInteger> ann = annotations(rel);
            assertThat(ann).containsEntry(List.of("a", "1"), TWO);
            assertThat(ann.keySet()).noneMatch(k -> k.contains("b"));
        }
    }

    @Nested
    @DisplayName("projection (π)")
    class Projection {

        @Test
        @DisplayName("merges colliding rows by adding their multiplicities")
        void mergesByAdding() {
            var rel = prov("""
                    Sales := [| region | amount |
                               | west | 10 |
                               | west | 20 |
                               | east | 30 |];
                    query { π region (Sales) };
                    """, CountingSemiring.INSTANCE);

            assertThat(annotations(rel))
                    .containsEntry(List.of("west"), TWO)
                    .containsEntry(List.of("east"), ONE);
        }
    }

    @Nested
    @DisplayName("rename (ρ)")
    class Rename {

        @Test
        @DisplayName("re-labels the heading and carries each annotation through unchanged")
        void relabelsAndCarriesAnnotation() {
            var rel = prov("""
                    Sales := [| region | amount |
                               | west | 10 |
                               | west | 10 |
                               | east | 30 |];
                    query { ρ S (area, total) (Sales) };
                    """, CountingSemiring.INSTANCE);

            // ρ adds, drops and reorders nothing: the same tuples, the same multiplicities.
            assertThat(annotations(rel))
                    .containsEntry(List.of("west", "10"), TWO)
                    .containsEntry(List.of("east", "30"), ONE);
        }
    }

    @Nested
    @DisplayName("product (×) and join (⨝/⋈)")
    class JoinsAndProducts {

        @Test
        @DisplayName("theta join multiplies the matched multiplicities")
        void thetaJoinMultiplies() {
            var rel = prov("""
                    L := [| id |
                           | 1 |
                           | 1 |];
                    R := [| ref | tag |
                           | 1 | x |
                           | 1 | y |];
                    query { L ⨝ L.id = R.ref R };
                    """, CountingSemiring.INSTANCE);

            // Two L rows (mult 2 on id=1) × each distinct R tuple (mult 1) = 2 each.
            Map<List<String>, BigInteger> ann = annotations(rel);
            assertThat(ann).containsEntry(List.of("1", "1", "x"), TWO);
            assertThat(ann).containsEntry(List.of("1", "1", "y"), TWO);
        }

        @Test
        @DisplayName("natural join matches on the shared column and drops the duplicate")
        void naturalJoin() {
            var rel = prov("""
                    L := [| id | a |
                           | 1 | p |];
                    R := [| id | b |
                           | 1 | q |
                           | 1 | q |];
                    query { L ⋈ R };
                    """, CountingSemiring.INSTANCE);

            // Shared 'id' appears once; R's tuple has multiplicity 2 → result 2.
            assertThat(annotations(rel)).containsEntry(List.of("1", "p", "q"), TWO);
        }

        @Test
        @DisplayName("theta join resolves qualifiers when a side is a filtered subtree")
        void thetaJoinOverSubtree() {
            // The left input is a selection (not a bare relation), so qualifier routing
            // must collect relation names through the subtree.
            var rel = prov("""
                    L := [| id | a |
                           | 1 | p |
                           | 2 | q |];
                    R := [| id | b |
                           | 1 | x |];
                    query { (σ a = "p" (L)) ⨝ L.id = R.id R };
                    """, CountingSemiring.INSTANCE);

            Map<List<String>, BigInteger> ann = annotations(rel);
            assertThat(ann).containsEntry(List.of("1", "p", "1", "x"), ONE);
            assertThat(ann.keySet()).noneMatch(k -> k.contains("q"));
        }

        @Test
        @DisplayName("natural join with no matching shared value yields nothing")
        void naturalJoinNoMatch() {
            var rel = prov("""
                    L := [| id | a |
                           | 1 | p |];
                    R := [| id | b |
                           | 2 | q |];
                    query { L ⋈ R };
                    """, CountingSemiring.INSTANCE);

            assertThat(rel.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("cross product (×) over disjoint schemas produces one annotated row per pair")
        void crossProductDisjointSchemas() {
            // ⋈ with no shared columns is now a semantic error (#181); use × instead.
            var rel = prov("""
                    L := [| a |
                           | x |];
                    R := [| b |
                           | y |];
                    query { L × R };
                    """, CountingSemiring.INSTANCE);

            assertThat(rel.isEmpty()).isFalse();
            assertThat(rel.size()).isEqualTo(1);  // one pair: (a=x, b=y), annotation=1
        }

        @Test
        @DisplayName("cartesian product multiplies every pair")
        void product() {
            var rel = prov("""
                    L := [| a |
                           | x |
                           | x |];
                    R := [| b |
                           | m |];
                    query { L CROSS R };
                    """, CountingSemiring.INSTANCE);

            assertThat(annotations(rel)).containsEntry(List.of("x", "m"), TWO);
        }
    }

    @Nested
    @DisplayName("union (∪)")
    class Union {

        @Test
        @DisplayName("adds the multiplicities of a shared tuple")
        void addsSharedTuple() {
            var rel = prov("""
                    A := [| x |
                           | a |
                           | b |];
                    B := [| x |
                           | a |
                           | c |];
                    query { A ∪ B };
                    """, CountingSemiring.INSTANCE);

            assertThat(annotations(rel))
                    .containsEntry(List.of("a"), TWO)
                    .containsEntry(List.of("b"), ONE)
                    .containsEntry(List.of("c"), ONE);
        }

        @Test
        @DisplayName("union-all (⊎) also combines alternative derivations with ⊕")
        void unionAllAddsSharedTuple() {
            var rel = prov("""
                    A := [| x |
                           | a |];
                    B := [| x |
                           | a |];
                    query { A ⊎ B };
                    """, CountingSemiring.INSTANCE);

            assertThat(annotations(rel)).containsEntry(List.of("a"), TWO);
        }
    }

    @Nested
    @DisplayName("opaque (non-positive) subtrees")
    class OpaqueSubtrees {

        @Test
        @DisplayName("an aggregation is read as a fresh base relation; σ above it threads")
        void aggregationIsOpaqueBase() {
            var rel = prov("""
                    Sales := [| dept | amount |
                               | eng | 100 |
                               | eng | 200 |
                               | mkt | 150 |];
                    Totals := { γ dept, SUM(amount) → total (Sales) };
                    query { σ total >= 200 (Totals) };
                    """, CountingSemiring.INSTANCE);

            // γ output lifted to one(); σ keeps the eng group (total 300).
            Map<List<String>, BigInteger> ann = annotations(rel);
            assertThat(ann).containsEntry(List.of("eng", "300"), ONE);
            assertThat(ann.keySet()).noneMatch(k -> k.contains("mkt"));
        }
    }

    @Nested
    @DisplayName("lineage (ℕ[X] full why-provenance)")
    class Lineage {

        private static String prov(AnnotatedRelation<Polynomial> rel, List<String> key) {
            Polynomial p = annotations(rel).get(key);
            assertThat(p).as("annotation for %s", key).isNotNull();
            return p.toString();
        }

        @Test
        @DisplayName("each base-tuple occurrence becomes a distinct variable")
        void baseTuplesGetVariables() {
            var rel = lineage("""
                    R := [| x |
                           | a |
                           | b |];
                    query R;
                    """);
            assertThat(prov(rel, List.of("a"))).isEqualTo("R#1");
            assertThat(prov(rel, List.of("b"))).isEqualTo("R#2");
        }

        @Test
        @DisplayName("projection adds the variables of merged rows (alternative derivations)")
        void projectionSumsVariables() {
            var rel = lineage("""
                    Sales := [| region | amount |
                               | west | 10 |
                               | west | 20 |];
                    query { π region (Sales) };
                    """);
            assertThat(prov(rel, List.of("west"))).isEqualTo("Sales#1 + Sales#2");
        }

        @Test
        @DisplayName("a join multiplies its inputs' variables into a product monomial")
        void joinMultipliesVariables() {
            var rel = lineage("""
                    L := [| id |
                           | 1 |];
                    R := [| ref | v |
                           | 1 | x |];
                    query { L ⨝ L.id = R.ref R };
                    """);
            assertThat(prov(rel, List.of("1", "1", "x"))).isEqualTo("L#1·R#1");
        }

        @Test
        @DisplayName("duplicate base rows become alternative variables on the merged tuple")
        void duplicateRowsSum() {
            var rel = lineage("""
                    R := [| x |
                           | a |
                           | a |];
                    query R;
                    """);
            assertThat(prov(rel, List.of("a"))).isEqualTo("R#1 + R#2");
        }

        @Test
        @DisplayName("threads through a view to name the base tuples — exposing a duplicate")
        void threadsThroughViewToBaseTables() {
            var rel = lineage("""
                    Customers := [| cid | region |
                                   | 1 | west |
                                   | 1 | west |
                                   | 2 | east |];
                    Orders := [| oid | cid | amount |
                                | 100 | 1 | 50 |
                                | 102 | 2 | 40 |];
                    PerRegion := { π region, amount (Orders ⨝ Orders.cid = Customers.cid Customers) };
                    query PerRegion;
                    """);
            // west/50 is derivable via either duplicate Customers row joined with order 100.
            assertThat(prov(rel, List.of("west", "50")))
                    .isEqualTo("Customers#1·Orders#1 + Customers#2·Orders#1");
            assertThat(prov(rel, List.of("east", "40")))
                    .isEqualTo("Customers#3·Orders#2");
        }

        @Test
        @DisplayName("ρ names the base tuples rather than becoming a variable of its own")
        void renameThreadsToBaseTables() {
            var rel = lineage("""
                    Orders := [| oid | cid |
                                | 100 | 1 |];
                    Customers := [| cid | name |
                                   | 1 | Ada |];
                    query { ρ J (Orders ⨝ Orders.cid = Customers.cid Customers) };
                    """);
            assertThat(prov(rel, List.of("100", "1", "1", "Ada")))
                    .isEqualTo("Customers#1·Orders#1");
        }

        @Test
        @DisplayName("a non-positive operator is an opaque boundary that mints fresh tokens")
        void opaqueBoundaryMintsFreshTokens() {
            var rel = lineage("""
                    Sales := [| dept | amount |
                               | eng | 100 |
                               | eng | 200 |
                               | mkt | 150 |];
                    Totals := { γ dept, SUM(amount) → total (Sales) };
                    query Totals;
                    """);
            // The γ output is read as a fresh base relation: one variable per group row,
            // not the lineage of the summed inputs.
            assertThat(prov(rel, List.of("eng", "300"))).matches("Aggregation#\\d");
            assertThat(prov(rel, List.of("mkt", "150"))).matches("Aggregation#\\d");
        }

        @Test
        @DisplayName("each base variable carries a structured source with the captured row columns")
        void capturesStructuredSourceColumns() {
            var rel = lineage("""
                    Orders := [| oid | region |
                                | 100 | west |
                                | 101 | east |];
                    query Orders;
                    """);
            SourceRef west = onlyVariable(annotations(rel).get(List.of("100", "west")));
            assertThat(west.source()).isEqualTo("Orders");
            assertThat(west.ordinal()).isEqualTo(1L);
            assertThat(west.columns())
                    .containsEntry("oid", "100")
                    .containsEntry("region", "west");
            assertThat(west.detailedLabel()).isEqualTo("Orders#1{oid: 100, region: west}");

            SourceRef east = onlyVariable(annotations(rel).get(List.of("101", "east")));
            assertThat(east.canonicalName()).isEqualTo("Orders#2");
            assertThat(east.columns()).containsEntry("region", "east");
        }

        @Test
        @DisplayName("an opaque γ row captures the group-key columns of its output tuple")
        void opaqueCapturesGroupColumns() {
            var rel = lineage("""
                    Sales := [| dept | amount |
                               | eng | 100 |
                               | eng | 200 |];
                    Totals := { γ dept, SUM(amount) → total (Sales) };
                    query Totals;
                    """);
            SourceRef eng = onlyVariable(annotations(rel).get(List.of("eng", "300")));
            assertThat(eng.source()).isEqualTo("Aggregation");
            assertThat(eng.columns())
                    .containsEntry("dept", "eng")
                    .containsEntry("total", "300");
        }

        /** {@return the single variable's {@link SourceRef} of a one-monomial, one-variable polynomial} */
        private static SourceRef onlyVariable(Polynomial p) {
            assertThat(p).as("polynomial").isNotNull();
            assertThat(p.terms()).as("monomials").hasSize(1);
            var exponents = p.terms().firstKey().exponents();
            assertThat(exponents).as("variables").hasSize(1);
            return exponents.firstKey().source().orElseThrow();
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("an unknown relation reference is rejected")
        void unknownRelation() {
            SemanticResult result = analyze("R := [| x |\n | a |];\nquery R;");
            SemanticModel model = result.model().orElseThrow();
            ExecutionContext ctx = ExecutionContext.inlineOnly(model);
            ProvenanceEvaluator evaluator = new ProvenanceEvaluator();

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    evaluator.evaluate(rel("Nope"), CountingSemiring.INSTANCE, ctx))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Unknown relation 'Nope'");
        }

        @Test
        @DisplayName("a non-relation node with no schema annotation is rejected")
        void unannotatedNode() {
            SemanticResult result = analyze("R := [| x |\n | a |];\nquery R;");
            SemanticModel model = result.model().orElseThrow();
            ExecutionContext ctx = ExecutionContext.inlineOnly(model);
            ProvenanceEvaluator evaluator = new ProvenanceEvaluator();
            // A freshly-built product node is absent from the inference annotation map.
            ProductNode unannotated = product(rel("R"), rel("R"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    evaluator.evaluate(unannotated, CountingSemiring.INSTANCE, ctx))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("No schema annotation for provenance node ProductNode");
        }
    }

    @Nested
    @DisplayName("semiring correspondence")
    class Correspondence {

        @Test
        @DisplayName("boolean reproduces set semantics (every present tuple → true)")
        void booleanIsSet() {
            var rel = prov("""
                    Sales := [| region | amount |
                               | west | 10 |
                               | west | 20 |
                               | east | 30 |];
                    query { π region (Sales) };
                    """, BooleanSemiring.INSTANCE);

            assertThat(annotations(rel))
                    .containsEntry(List.of("west"), Boolean.TRUE)
                    .containsEntry(List.of("east"), Boolean.TRUE);
            assertThat(rel.size()).isEqualTo(2);   // duplicates collapsed under set semantics
        }

        @Test
        @DisplayName("ℕ reproduces bag multiplicity end-to-end")
        void countingIsBag() {
            var rel = prov("""
                    R := [| x |
                           | a |
                           | a |
                           | a |];
                    query R;
                    """, CountingSemiring.INSTANCE);

            assertThat(annotations(rel)).containsEntry(List.of("a"), THREE);
        }
    }

    @Nested
    @DisplayName("semiring-weighted closure (#47)")
    class WeightedClosure {

        private static final int UNLIMITED = ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS;

        private static final String CHAIN =
                "Edges := [| src | dst |\n"
                + "           | 1   | 2   |\n"
                + "           | 2   | 3   |];\n";

        @Test
        @DisplayName("boolean semiring is reachability")
        void booleanReachability() {
            var rel = provWeighted(CHAIN + "query { CLOSURE src, dst (Edges) };",
                    BooleanSemiring.INSTANCE, null, UNLIMITED);

            Map<List<String>, Boolean> ann = annotations(rel);
            assertThat(ann.keySet()).containsExactlyInAnyOrder(
                    List.of("1", "2"), List.of("2", "3"), List.of("1", "3"));
            assertThat(ann.values()).allMatch(Boolean.TRUE::equals);
        }

        @Test
        @DisplayName("ℕ semiring counts distinct paths (a diamond gives two 1⤳4 paths)")
        void countingPaths() {
            String diamond =
                    "Edges := [| src | dst |\n"
                    + "           | 1   | 2   |\n"
                    + "           | 1   | 3   |\n"
                    + "           | 2   | 4   |\n"
                    + "           | 3   | 4   |];\n";

            var rel = provWeighted(diamond + "query { CLOSURE src, dst (Edges) };",
                    CountingSemiring.INSTANCE, null, UNLIMITED);

            Map<List<String>, BigInteger> ann = annotations(rel);
            assertThat(ann).containsEntry(List.of("1", "4"), TWO);   // 1→2→4 and 1→3→4
            assertThat(ann).containsEntry(List.of("1", "2"), ONE);
            assertThat(ann).containsEntry(List.of("2", "4"), ONE);
        }

        @Test
        @DisplayName("tropical semiring with a weight column is shortest path")
        void tropicalShortestPath() {
            String weighted =
                    "Edges := [| src | dst | cost |\n"
                    + "           | 1   | 2   | 1    |\n"
                    + "           | 2   | 3   | 1    |\n"
                    + "           | 1   | 3   | 5    |];\n";

            var rel = provWeighted(weighted + "query { CLOSURE src, dst (Edges) };",
                    TropicalSemiring.INSTANCE, "cost", UNLIMITED);

            Map<List<String>, Double> ann = annotations(rel);
            assertThat(ann).containsEntry(List.of("1", "3"), 2.0d);   // 1→2→3 (2) beats 1→3 (5)
            assertThat(ann).containsEntry(List.of("1", "2"), 1.0d);
            assertThat(ann).containsEntry(List.of("2", "3"), 1.0d);
        }

        @Test
        @DisplayName("without a weight column tropical edges weigh one() = 0")
        void tropicalUnweightedIsDegenerate() {
            var rel = provWeighted(CHAIN + "query { CLOSURE src, dst (Edges) };",
                    TropicalSemiring.INSTANCE, null, UNLIMITED);

            assertThat(annotations(rel)).containsEntry(List.of("1", "3"), 0.0d);
        }

        @Test
        @DisplayName("RCLOSURE adds an identity pair for every node at one()")
        void reflexiveAddsIdentity() {
            var rel = provWeighted(CHAIN + "query { RCLOSURE src, dst (Edges) };",
                    BooleanSemiring.INSTANCE, null, UNLIMITED);

            assertThat(annotations(rel).keySet()).contains(
                    List.of("1", "1"), List.of("2", "2"), List.of("3", "3"),
                    List.of("1", "2"), List.of("2", "3"), List.of("1", "3"));
        }

        @Test
        @DisplayName("an idempotent semiring converges on a cyclic graph")
        void cycleConvergesForIdempotent() {
            String cycle =
                    "Edges := [| src | dst |\n"
                    + "           | 1   | 2   |\n"
                    + "           | 2   | 1   |];\n";

            var rel = provWeighted(cycle + "query { CLOSURE src, dst (Edges) };",
                    BooleanSemiring.INSTANCE, null, UNLIMITED);

            assertThat(annotations(rel).keySet()).containsExactlyInAnyOrder(
                    List.of("1", "2"), List.of("2", "1"), List.of("1", "1"), List.of("2", "2"));
        }

        @Test
        @DisplayName("a non-idempotent semiring over a cycle is cut off by the round cap")
        void cycleCountingHitsRoundCap() {
            String cycle =
                    "Edges := [| src | dst |\n"
                    + "           | 1   | 2   |\n"
                    + "           | 2   | 1   |];\n";

            assertThatThrownBy(() -> provWeighted(cycle + "query { CLOSURE src, dst (Edges) };",
                    CountingSemiring.INSTANCE, null, 5))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("weighted CLOSURE exceeded");
        }

        @Test
        @DisplayName("σ below the closure prunes edges before the fixpoint")
        void selectionBelowClosureThreads() {
            String weighted =
                    "Edges := [| src | dst | cost |\n"
                    + "           | 1   | 2   | 1    |\n"
                    + "           | 2   | 3   | 1    |\n"
                    + "           | 9   | 1   | 1    |];\n";

            // The 9→1 edge is filtered out, so node 9 never reaches anything.
            var rel = provWeighted(
                    weighted + "query { CLOSURE src, dst (σ src != 9 (Edges)) };",
                    TropicalSemiring.INSTANCE, "cost", UNLIMITED);

            Map<List<String>, Double> ann = annotations(rel);
            assertThat(ann).containsEntry(List.of("1", "3"), 2.0d);
            assertThat(ann.keySet()).noneMatch(k -> k.contains("9"));
        }
    }

    @Nested
    @DisplayName("cheapest-route closure (cost + witness, #150)")
    class CheapestRoute {

        private static final int UNLIMITED = ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS;

        @Test
        @DisplayName("carries the cheapest cost AND the single achieving route")
        void costAndRoute() {
            String weighted =
                    "Edges := [| src | dst | cost |\n"
                    + "           | 1   | 2   | 1    |\n"
                    + "           | 2   | 3   | 1    |\n"
                    + "           | 1   | 3   | 5    |];\n";

            var rel = provWeighted(weighted + "query { CLOSURE src, dst (Edges) };",
                    PathCostSemiring.INSTANCE, "cost", UNLIMITED);

            Map<List<String>, PathCost> ann = annotations(rel);
            PathCost oneToThree = ann.get(List.of("1", "3"));
            // 1→2→3 (cost 2, edges Edges#1·Edges#2) beats the direct 1→3 (cost 5).
            assertThat(oneToThree.cost()).isEqualTo(2.0d);
            assertThat(oneToThree.routes()).hasSize(1);
            assertThat(oneToThree.toString()).isEqualTo("2.0 via Edges#1·Edges#2");
            // a one-hop pair carries its single edge token.
            assertThat(ann.get(List.of("1", "2")).toString()).isEqualTo("1.0 via Edges#1");
        }

        @Test
        @DisplayName("a cost tie keeps every co-cheapest route as the witness set")
        void coCheapestRoutesUnion() {
            String diamond =
                    "Edges := [| src | dst | cost |\n"
                    + "           | 1   | 2   | 1    |\n"   // Edges#1
                    + "           | 1   | 3   | 1    |\n"   // Edges#2
                    + "           | 2   | 4   | 1    |\n"   // Edges#3
                    + "           | 3   | 4   | 1    |];\n"; // Edges#4

            var rel = provWeighted(diamond + "query { CLOSURE src, dst (Edges) };",
                    PathCostSemiring.INSTANCE, "cost", UNLIMITED);

            PathCost oneToFour = annotations(rel).get(List.of("1", "4"));
            assertThat(oneToFour.cost()).isEqualTo(2.0d);     // both routes cost 2
            assertThat(oneToFour.routes()).hasSize(2);
            // 1→2→4 = {Edges#1, Edges#3}; 1→3→4 = {Edges#2, Edges#4}.
            assertThat(oneToFour.toString())
                    .isEqualTo("2.0 via Edges#1·Edges#3 | Edges#2·Edges#4");
        }

        @Test
        @DisplayName("without a weight column every edge weighs 0 — reachability with a witness")
        void unweightedIsDegenerate() {
            String unweighted =
                    "Edges := [| src | dst |\n"
                    + "           | 1   | 2   |\n"
                    + "           | 2   | 3   |];\n";

            var rel = provWeighted(unweighted + "query { CLOSURE src, dst (Edges) };",
                    PathCostSemiring.INSTANCE, null, UNLIMITED);

            PathCost oneToThree = annotations(rel).get(List.of("1", "3"));
            assertThat(oneToThree.cost()).isEqualTo(0.0d);
            assertThat(oneToThree.toString()).isEqualTo("0.0 via Edges#1·Edges#2");
        }

        @Test
        @DisplayName("converges on a cyclic graph with a bounded co-cheapest witness")
        void cycleConverges() {
            String cycle =
                    "Edges := [| src | dst | cost |\n"
                    + "           | 1   | 2   | 1    |\n"
                    + "           | 2   | 1   | 1    |];\n";

            // The cost component is idempotent (min), so unlike ℕ this terminates even
            // with a low round cap; the witness set stays the finite simple-path set.
            var rel = provWeighted(cycle + "query { CLOSURE src, dst (Edges) };",
                    PathCostSemiring.INSTANCE, "cost", 5);

            Map<List<String>, PathCost> ann = annotations(rel);
            assertThat(ann.get(List.of("1", "1")).cost()).isEqualTo(2.0d);   // 1→2→1
            assertThat(ann.get(List.of("1", "2")).cost()).isEqualTo(1.0d);
            assertThat(ann.get(List.of("1", "1")).routes()).hasSize(1);
        }
    }
}
