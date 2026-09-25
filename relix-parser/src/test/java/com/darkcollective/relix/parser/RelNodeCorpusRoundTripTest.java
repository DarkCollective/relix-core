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
package com.darkcollective.relix.parser;

import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelNodeCorpus;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.visitor.internal.PrettyPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips <strong>every</strong> concrete {@link RelNode} kind through
 * {@code prettyPrint → parse}, driven by {@link RelNodeCorpus}.
 *
 * <p>{@code VisitorRoundTripTest} does the same by hand for the textbook operators and
 * reaches 21 of the 52 kinds.  Everything added since — the window/analytics family, the
 * recursion and graph operators, the solver nodes, the temporal joins — was printed by a
 * visitor no test ever asked to print it.  That gap has a precedent: {@code PrettyPrinter}
 * once emitted an {@code IJOIN} form the grammar would not parse, and nothing failed.
 *
 * <h2>Why a print bug is a correctness bug, not a cosmetic one</h2>
 *
 * <p>{@code AstEquivalence} compares two {@code RelNode}s by their {@code prettyPrint()}
 * output, and {@code SharedSubexpressions.detect} keys the planner's spools on
 * {@code AstEquivalence.digest}.  So a printer that renders two <em>different</em>
 * sub-trees identically makes the planner share one spool between sub-plans that are not
 * the same relation — and the query returns wrong rows.  {@link Distinctness} below is
 * the guard against exactly that, and it is why this test lives on the printed form
 * rather than only on structural equality.
 *
 * <h2>Three buckets, and every kind is in exactly one</h2>
 *
 * <ul>
 *   <li>{@link StructuralRoundTrip} — 46 kinds: {@code parse(print(n))} is structurally
 *       equal to {@code n}, modulo source locations.  The strongest property available.</li>
 *   <li>{@link RebindingForms} — {@link RecursiveRefNode}: prints and re-parses stably,
 *       but re-parses to a different <em>kind</em>, because a recursive reference is only
 *       distinguishable from a plain relation name inside its {@code FIX} binder.</li>
 *   <li>{@link PrintOnlyForms} — 5 corpus entries carrying a component with no surface
 *       syntax.  These assert the printed form does <strong>not</strong> parse.</li>
 * </ul>
 *
 * <p>{@link Completeness} holds the three buckets to the corpus, so a new node kind cannot
 * silently opt out: it lands in the round-trip set the day its corpus entry does, or the
 * build fails until someone says which bucket it belongs in and why.
 */
@DisplayName("RelNodeCorpus — prettyPrint → parse over every node kind")
final class RelNodeCorpusRoundTripTest extends ParserTestSupport {

    private static final PrettyPrinter PRINTER = new PrettyPrinter();

    /**
     * The corpus entries whose printed form carries a component the grammar cannot read
     * back, each with the reason it has no surface syntax.
     *
     * <p>Note what is being excluded: for four of the five it is the <em>component</em>,
     * not the kind.  A bare {@code Users}, {@code CLOSURE src, dst (R)},
     * {@code TRACE …} and {@code μ tags (R)} all round-trip, and
     * {@link PrintOnlyForms#bareFormsStillRoundTrip} asserts they do — so the exclusion
     * stays as narrow as the reason justifies rather than writing off a whole kind.
     */
    private static final Map<Class<? extends RelNode>, String> PRINT_ONLY =
            new LinkedHashMap<>(Map.of(
                    RelationNode.class,
                    "the ⟨produce while …⟩ bound GEN-001 pushes into a generator is an "
                            + "optimizer artifact printed for diagnostics; source never carries one",
                    ClosureNode.class,
                    "the ⟨src=…, dst=…⟩ endpoint bounds CLOSURE-001 pushes down are an "
                            + "optimizer artifact, as above",
                    TraceNode.class,
                    "the ⟨src=…, dst=…⟩ endpoint bounds TRACE-001 pushes down are an "
                            + "optimizer artifact, as above",
                    PathNode.class,
                    "the ⟨src=…, dst=…⟩ endpoint bounds PATH-001 pushes down are an "
                            + "optimizer artifact, as above",
                    UnnestNode.class,
                    "the OUTER variant is programmatic only — docs/reference/operators/"
                            + "unnest.md states it, and the parser hard-codes outer=false",
                    EmptyRelationNode.class,
                    "∅ is optimizer-only with no surface syntax at all: it carries another "
                            + "expression's heading as an inert component"));

    /** The one kind that re-parses stably but as a different kind. See {@link RebindingForms}. */
    private static final Class<? extends RelNode> REBINDING = RecursiveRefNode.class;

    private static String print(RelNode node) {
        return node.accept(PRINTER);
    }

    private static Stream<RelNode> corpus(boolean printOnly) {
        return RelNodeCorpus.everyKind().stream()
                .filter(n -> PRINT_ONLY.containsKey(n.getClass()) == printOnly);
    }

    // =========================================================================

    @Nested
    @DisplayName("Structural round-trip — parse(print(n)) ≡ n")
    class StructuralRoundTrip {

        @TestFactory
        @DisplayName("every kind with surface syntax survives print → parse unchanged")
        Stream<DynamicTest> everyKindRoundTrips() {
            return corpus(false)
                    .filter(n -> n.getClass() != REBINDING)
                    .map(node -> DynamicTest.dynamicTest(node.getClass().getSimpleName(), () -> {
                        String printed = print(node);
                        RelNode reparsed = RelAlgebraParser.parse(printed);
                        assertThat(stripLocations(reparsed))
                                .as("parse(%s) should rebuild the original node", printed)
                                .isEqualTo(stripLocations(node));
                    }));
        }

        @TestFactory
        @DisplayName("printing is a fixed point — print(parse(print(n))) == print(n)")
        Stream<DynamicTest> printingIsStable() {
            return corpus(false)
                    .map(node -> DynamicTest.dynamicTest(node.getClass().getSimpleName(), () -> {
                        String printed = print(node);
                        assertThat(print(RelAlgebraParser.parse(printed)))
                                .as("re-printing a re-parsed node should be identical")
                                .isEqualTo(printed);
                    }));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Rebinding forms — print-stable, but a different kind on the way back")
    class RebindingForms {

        @Test
        @DisplayName("a recursive reference re-parses as a plain relation name")
        void recursiveRefReparsesAsRelation() {
            RelNode ref = RelNodeCorpus.everyKind().stream()
                    .filter(n -> n.getClass() == REBINDING)
                    .findFirst().orElseThrow();

            String printed = print(ref);
            RelNode reparsed = RelAlgebraParser.parse(printed);

            // A recursive reference and a relation of the same name print identically —
            // correctly so: outside a FIX there is nothing to tell them apart, and it is
            // the FIX binder that reclassifies the name. The printed form is therefore
            // stable even though the kind is not preserved.
            assertThat(print(reparsed)).isEqualTo(printed);
            assertThat(reparsed).isNode(RelationNode.class);
        }

        @Test
        @DisplayName("inside its FIX binder the reference does round-trip")
        void insideFixItRoundTrips() {
            RelNode fix = RelNodeCorpus.everyKind().stream()
                    .filter(n -> n.getClass().getSimpleName().equals("FixpointNode"))
                    .findFirst().orElseThrow();

            assertThat(print(RelAlgebraParser.parse(print(fix)))).isEqualTo(print(fix));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Print-only forms — printed for diagnostics, not readable back")
    class PrintOnlyForms {

        @TestFactory
        @DisplayName("each print-only form is rejected by the grammar")
        Stream<DynamicTest> printOnlyFormsDoNotParse() {
            return corpus(true).map(node -> {
                Class<? extends RelNode> kind = node.getClass();
                return DynamicTest.dynamicTest(kind.getSimpleName(), () -> {
                    String printed = print(node);
                    // Stated as a check rather than as prose: if surface syntax is added
                    // later, this fails and says to move the entry into the round-trip
                    // bucket — where a comment would simply have gone quietly stale.
                    assertThatThrownBy(() -> RelAlgebraParser.parse(printed))
                            .as("%s prints %s — %s", kind.getSimpleName(), printed,
                                    PRINT_ONLY.get(kind))
                            .isInstanceOf(ParseException.class);
                });
            });
        }

        @Test
        @DisplayName("the exclusion is the component, not the kind — bare forms round-trip")
        void bareFormsStillRoundTrip() {
            List<RelNode> bare = List.of(
                    rel("Users"),
                    closure("src", "dst", false, true,
                            Optional.empty(), Optional.empty(), rel("Edges")),
                    trace("src", "dst", false, "weight",
                            ObjectiveSense.MINIMIZE, "route",
                            Optional.empty(), Optional.empty(), rel("Edges")),
                    unnest("tags", false, Optional.of("ordinality"), rel("Articles")));

            for (RelNode node : bare) {
                String printed = print(node);
                assertThat(stripLocations(RelAlgebraParser.parse(printed)))
                        .as("the un-annotated %s should round-trip: %s",
                                node.getClass().getSimpleName(), printed)
                        .isEqualTo(stripLocations(node));
            }
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Distinctness — the property AstEquivalence.digest rests on")
    class Distinctness {

        @Test
        @DisplayName("no two kinds print identically")
        void printsAreDistinct() {
            Map<String, String> byPrint = new HashMap<>();
            Map<String, String> collisions = new LinkedHashMap<>();

            for (RelNode node : RelNodeCorpus.everyKind()) {
                String kind = node.getClass().getSimpleName();
                String previous = byPrint.put(print(node), kind);
                if (previous != null) {
                    collisions.put(previous + " / " + kind, print(node));
                }
            }

            assertThat(collisions)
                    .as("two kinds printing alike collide in AstEquivalence.digest, which "
                            + "SharedSubexpressions.detect keys spools on — the planner would "
                            + "share one evaluation between sub-plans that are not the same")
                    .isEmpty();
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Completeness — a new kind cannot opt out")
    class Completeness {

        @Test
        @DisplayName("every corpus kind is classified, and every classification is used")
        void everyKindIsClassified() {
            List<String> kinds = RelNodeCorpus.everyKind().stream()
                    .map(n -> n.getClass().getSimpleName())
                    .toList();

            long structural = corpus(false).filter(n -> n.getClass() != REBINDING).count();

            assertThat(structural + 1 + PRINT_ONLY.size())
                    .as("every kind belongs to exactly one of the three buckets")
                    .isEqualTo(kinds.size());

            // PRINT_ONLY naming a kind the corpus no longer carries is a justification
            // outliving what it justified — the same failure the coverage register
            // reports rather than tolerating.
            assertThat(kinds).containsAll(
                    PRINT_ONLY.keySet().stream().map(Class::getSimpleName).toList());
        }
    }
}
