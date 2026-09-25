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

import com.darkcollective.relix.ast.internal.AstEquivalence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.cmp;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.select;
import static com.darkcollective.relix.ast.AstBuilders.str;

/**
 * Issue #728 — the exact, location-blind comparison, and the two ways its hand-written
 * rebuild can go wrong.
 *
 * <p>{@link AstLocations} is one visitor arm per {@link RelNode} kind, each duplicating
 * that record's canonical constructor. Two failure modes follow, and neither is a compile
 * error:
 *
 * <ol>
 *   <li><b>A missing arm.</b> Five of {@code RelNodeVisitor}'s arms are throwing
 *       {@code default}s, so a kind added that way compiles and throws at run time — in
 *       whatever downstream test happened to strip one.</li>
 *   <li><b>An arm that drops a component.</b> Rebuilding a record by hand and forgetting
 *       an alias, a flag or a column name is invisible: the comparison merely stops
 *       seeing that component, so an assertion built on it passes where it should fail.
 *       This is the same duplication {@link RelNodeMapChildrenTest} polices for
 *       {@code mapChildren}, and until this test nothing policed it here.</li>
 * </ol>
 *
 * <p>Both are caught by one claim over {@link RelNodeCorpus}: every corpus node is built
 * through {@code AstBuilders} and so already carries only {@link SourceLocation#UNKNOWN},
 * which makes stripping it an <em>identity</em>. An arm that throws fails the walk; an arm
 * that drops a component returns something unequal to what it was given.
 */
@DisplayName("#728 — AstLocations strips every kind, and drops nothing doing it")
final class AstLocationsTest {

    @Nested
    @DisplayName("over every RelNode kind")
    final class OverTheCorpus {

        @Test
        @DisplayName("stripping a location-free node returns an equal node, for every kind")
        void strippingIsIdentityOnTheCorpus() {
            List<String> broken = new ArrayList<>();
            for (RelNode node : RelNodeCorpus.everyKind()) {
                String kind = node.getClass().getSimpleName();
                try {
                    RelNode stripped = AstLocations.stripLocations(node);
                    if (!stripped.equals(node)) {
                        broken.add(kind + " — rebuilt unequal to the original:"
                                + System.lineSeparator() + "    was:      " + node
                                + System.lineSeparator() + "    stripped: " + stripped);
                    }
                } catch (RuntimeException e) {
                    broken.add(kind + " — " + e);
                }
            }

            assertThat(broken)
                    .as("""
                            RelNode kinds AstLocations rebuilds wrongly. A corpus node \
                            carries only SourceLocation.UNKNOWN, so stripping it must be \
                            the identity: an unequal result is an arm that dropped a \
                            record component, and a thrown exception is a missing arm \
                            resting on RelNodeVisitor's throwing default. Add or fix the \
                            arm in AstLocations""")
                    .isEmpty();
        }

        @Test
        @DisplayName("no SourceLocation survives anywhere in a stripped tree")
        void noLocationSurvives() {
            List<String> surviving = new ArrayList<>();
            for (RelNode node : RelNodeCorpus.everyKind()) {
                RelNode stripped = AstLocations.stripLocations(node);
                collectLocations(stripped, node.getClass().getSimpleName(),
                        new IdentityHashMap<>(), surviving);
            }

            assertThat(surviving).as("SourceLocation components left un-stripped").isEmpty();
        }
    }

    @Nested
    @DisplayName("on a tree that actually carries positions")
    final class OnPositionedTrees {

        private static final SourceLocation AT = new SourceLocation("q.relix", 3, 17);

        /** A tree carrying real positions, as the parser produces one. */
        private static RelNode positioned() {
            return new SelectionNode(
                    new ComparisonPredicate(new AttributeOperand("x", AT),
                            ComparisonOperator.GREATER, new NumberOperand("5", AT), AT),
                    new RelationNode("Users", AT), AT);
        }

        private static RelNode handBuilt() {
            return select(cmp(attr("x"), ComparisonOperator.GREATER, num("5")), rel("Users"));
        }

        @Test
        @DisplayName("a positioned tree equals its hand-built twin once stripped")
        void positionedEqualsHandBuilt() {
            assertThat(AstLocations.stripLocations(positioned())).isEqualTo(handBuilt());
            assertThat(positioned()).isStructurallyEqualTo(handBuilt());
        }

        @Test
        @DisplayName("and is unequal to it before stripping — the reason this exists")
        void positionedIsUnequalUnstripped() {
            assertThat(positioned())
                    .as("record equality includes SourceLocation; without stripping, "
                            + "nothing a parser produces compares equal to a fixture")
                    .isNotEqualTo(handBuilt());
        }

        @Test
        @DisplayName("predicates and operands strip on their own, not only inside a node")
        void predicatesAndOperandsStripAlone() {
            Predicate positionedPredicate = new ComparisonPredicate(new AttributeOperand("x", AT),
                    ComparisonOperator.EQUAL, new StringOperand("a", AT), AT);

            assertThat(AstLocations.stripLocations(positionedPredicate))
                    .isEqualTo(cmp(attr("x"), ComparisonOperator.EQUAL, str("a")));
            assertThat(AstLocations.stripLocations((Operand) new AttributeOperand("x", AT)))
                    .isEqualTo(attr("x"));
        }
    }

    @Nested
    @DisplayName("against AstEquivalence")
    final class AgainstEquivalence {

        /**
         * The distinction #728 was filed to write down: the two comparisons normalise
         * different things, so neither is a drop-in replacement for the other. A parser
         * test asserting that {@code 5.0} survives the lexer as {@code 5.0}, or that
         * identifier case is preserved, asserts nothing under {@link AstEquivalence}.
         */
        @Test
        @DisplayName("literal spelling and identifier case separate operands here, not there")
        void exactWhereEquivalenceNormalises() {
            assertThat(AstEquivalence.equivalent(num("5"), num("5.0")))
                    .as("AstEquivalence compares numeric literals as BigDecimal")
                    .isTrue();
            assertThat(AstEquivalence.equivalent(attr("x"), attr("X")))
                    .as("AstEquivalence compares identifiers case-insensitively")
                    .isTrue();

            assertThat(AstLocations.stripLocations((Operand) num("5")))
                    .as("the exact comparison keeps a literal's spelling")
                    .isNotEqualTo(AstLocations.stripLocations((Operand) num("5.0")));
            assertThat(AstLocations.stripLocations((Operand) attr("x")))
                    .as("and a name's case")
                    .isNotEqualTo(AstLocations.stripLocations((Operand) attr("X")));
        }

        /**
         * At {@link RelNode} level {@link AstEquivalence} decides by comparing
         * {@link RelNode#prettyPrint()} forms, which spell a literal and a name
         * verbatim — so the normalisation above does <em>not</em> reach a whole tree.
         * Stated here because the natural reading of that class's "what is normalised"
         * list is that it does, and a caller relying on it would be relying on a false
         * negative that happens to be the safe direction.
         */
        @Test
        @DisplayName("neither normalisation reaches a whole tree, which prints its literals verbatim")
        void nodeLevelEquivalenceIsPrintedForm() {
            RelNode five = select(cmp(attr("x"), ComparisonOperator.EQUAL, num("5")), rel("Users"));
            RelNode fivePointZero =
                    select(cmp(attr("x"), ComparisonOperator.EQUAL, num("5.0")), rel("Users"));

            assertThat(five).isNotEquivalentTo(fivePointZero);
            assertThat(five).isEquivalentTo(
                    select(cmp(attr("x"), ComparisonOperator.EQUAL, num("5")), rel("Users")));
        }
    }

    // =========================================================================
    // Reflective walk over record components, looking for a surviving location
    // =========================================================================

    /**
     * Walks {@code value}'s record components — through {@link Optional}s and
     * {@link Collection}s — recording any {@link SourceLocation} that is not
     * {@link SourceLocation#UNKNOWN}.
     */
    private static void collectLocations(Object value, String kind,
                                         Map<Object, Boolean> seen, List<String> out) {
        switch (value) {
            case null -> { }
            case SourceLocation location -> {
                if (!SourceLocation.UNKNOWN.equals(location)) {
                    out.add(kind + " retains " + location);
                }
            }
            case Optional<?> optional -> optional.ifPresent(v -> collectLocations(v, kind, seen, out));
            case Collection<?> items -> items.forEach(v -> collectLocations(v, kind, seen, out));
            case Record record -> {
                if (seen.put(record, Boolean.TRUE) == null) {
                    for (RecordComponent component : record.getClass().getRecordComponents()) {
                        collectLocations(read(record, component), kind, seen, out);
                    }
                }
            }
            default -> { }
        }
    }

    private static Object read(Record record, RecordComponent component) {
        try {
            component.getAccessor().setAccessible(true);
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read " + component.getName()
                    + " of " + record.getClass().getSimpleName(), e);
        }
    }
}
