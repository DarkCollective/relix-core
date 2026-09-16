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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full truth table of {@link JoinSides#sideOf}.
 *
 * <p>Two callers turn this answer into a rewrite — {@link TransitiveEqualityPass} derives
 * a filter for one side, {@link OuterJoinDemotionPass} asks whether a predicate constrains
 * the null-supplying side — and both read {@code UNKNOWN} as "skip". So a wrong
 * {@code LEFT}/{@code RIGHT} is a wrong rewrite while a wrong {@code UNKNOWN} is only a
 * missed one, which is why every ambiguous combination is asserted here rather than left
 * to whichever the two rules happen to exercise.
 *
 * <p>The two resolution paths are separate claims: a <em>qualified</em> reference resolves
 * by column provenance, an unqualified one by name exclusivity, and the qualified path
 * falls back to the bare one whenever provenance cannot settle it.
 */
@DisplayName("JoinSides.sideOf")
final class JoinSidesTest {

    private static ColumnDefinition col(String name) {
        return new ColumnDefinition(name, ScalarType.NUMBER);
    }

    private static ColumnDefinition from(String relation, String name) {
        return new ColumnDefinition(name, ScalarType.NUMBER,
                new ColumnProvenance(relation, name));
    }

    private static Schema schema(ColumnDefinition... columns) {
        return new Schema(List.of(columns));
    }

    @Nested
    @DisplayName("Unqualified — resolved by name exclusivity")
    class Unqualified {

        private final Schema left = schema(col("a"), col("shared"));
        private final Schema right = schema(col("b"), col("shared"));

        @Test
        @DisplayName("a name only the left has is LEFT")
        void leftOnly() {
            assertThat(JoinSides.sideOf("a", left, right)).isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("a name only the right has is RIGHT")
        void rightOnly() {
            assertThat(JoinSides.sideOf("b", left, right)).isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("a name both sides have is UNKNOWN — the rule declines to guess")
        void bothSides() {
            assertThat(JoinSides.sideOf("shared", left, right)).isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("a name neither side has is UNKNOWN")
        void neitherSide() {
            assertThat(JoinSides.sideOf("absent", left, right)).isEqualTo(JoinSides.Side.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("Qualified — resolved by column provenance")
    class Qualified {

        private final Schema left = schema(from("A", "id"), from("A", "k"));
        private final Schema right = schema(from("B", "id"), from("B", "k"));

        @Test
        @DisplayName("A.k resolves to LEFT even though both sides have a column named k")
        void qualifierPicksTheLeft() {
            assertThat(JoinSides.sideOf("A.k", left, right)).isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("B.k resolves to RIGHT on the same evidence")
        void qualifierPicksTheRight() {
            assertThat(JoinSides.sideOf("B.k", left, right)).isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("a self-join both sides claim is UNKNOWN, not a guess")
        void bothSidesClaimIt() {
            // A raw self-join with no intervening rename: both inputs carry A's provenance,
            // so A.k names a column on each. This is the arm that must not pick one.
            Schema selfLeft = schema(from("A", "k"));
            Schema selfRight = schema(from("A", "k"));
            assertThat(JoinSides.sideOf("A.k", selfLeft, selfRight))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("a qualifier matching neither side falls back to the bare name")
        void unknownQualifierFallsBackToTheBareName() {
            // C.k matches no provenance, so neither inLeft nor inRight holds and the
            // bare-name path runs — where k is on both sides, hence UNKNOWN.
            assertThat(JoinSides.sideOf("C.k", left, right)).isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("an unmatched qualifier still resolves when the bare name is exclusive")
        void unknownQualifierResolvesByBareName() {
            Schema exclusiveLeft = schema(from("A", "only_left"));
            assertThat(JoinSides.sideOf("C.only_left", exclusiveLeft, right))
                    .isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("a schema with no provenance at all falls back to the bare name")
        void noProvenanceFallsBackToTheBareName() {
            Schema plainLeft = schema(col("a"));
            Schema plainRight = schema(col("b"));
            assertThat(JoinSides.sideOf("A.a", plainLeft, plainRight))
                    .isEqualTo(JoinSides.Side.LEFT);
            assertThat(JoinSides.sideOf("B.b", plainLeft, plainRight))
                    .isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("provenance on one side only still resolves the other by name")
        void provenanceOnOneSideOnly() {
            Schema provenanced = schema(from("A", "k"));
            Schema plain = schema(col("other"));
            assertThat(JoinSides.sideOf("A.k", provenanced, plain))
                    .isEqualTo(JoinSides.Side.LEFT);
            assertThat(JoinSides.sideOf("A.other", provenanced, plain))
                    .as("no provenance match, and only the right has the bare name")
                    .isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("an ambiguous provenance match on one side is not a match")
        void ambiguousProvenanceIsNotAMatch() {
            // Two columns with the same origin: qualifiedIndices returns 2, so the
            // size() == 1 test fails and the bare-name path decides instead.
            Schema ambiguous = new Schema(List.of(
                    from("A", "k"), new ColumnDefinition("k_1", ScalarType.NUMBER,
                            new ColumnProvenance("A", "k"))));
            Schema plain = schema(col("other"));
            assertThat(JoinSides.sideOf("A.k", ambiguous, plain))
                    .as("bare name k is on the left only")
                    .isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("an open schema carries no provenance, and resolves every bare name")
        void openSchema() {
            // qualifiedIndices is empty for an open schema, so the qualified path cannot
            // fire; column(name) then answers for *any* name, which makes a reference the
            // other side also has ambiguous rather than resolving it.
            assertThat(JoinSides.sideOf("A.k", Schema.open(), right))
                    .as("both sides claim k").isEqualTo(JoinSides.Side.UNKNOWN);
            // Not LEFT: the qualifier matched neither heading, and a row read straight
            // from an open source cannot resolve it once the predicate is pushed there —
            // it reads `A.absent` as a path into a field named `A` (#971, #972).
            assertThat(JoinSides.sideOf("A.absent", Schema.open(), right))
                    .as("only the open side could claim it, and cannot say").isEqualTo(JoinSides.Side.UNKNOWN);
        }
    }

    /**
     * What a schema-on-read side is evidence of (#971). Its heading resolves every name,
     * so its yes decides only a plain name the other side lacks — and not even that when
     * the name is one the join invents.
     */
    @Nested
    @DisplayName("An open side — what its answer is evidence of (#971)")
    class OpenSide {

        private final Schema declared = schema(from("orders", "product_id"), from("orders", "quantity"));

        @Test
        @DisplayName("a plain name the declared side lacks belongs to the open side")
        void plainNameGoesToTheOpenSide() {
            assertThat(JoinSides.sideOf("product_name", declared, Schema.open()))
                    .isEqualTo(JoinSides.Side.RIGHT);
            assertThat(JoinSides.sideOf("product_name", Schema.open(), declared))
                    .isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("a name the declared left has is the left's — an open right's copy would be renamed")
        void declaredLeftKeepsASharedName() {
            assertThat(JoinSides.sideOf("quantity", declared, Schema.open()))
                    .isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("a name the declared right has is UNKNOWN when an open left may hold it too")
        void openLeftMayOwnASharedName() {
            // A document carrying `quantity` keeps the name and renames the right's to
            // `quantity_r` — row by row, so no plan-time answer is right.
            assertThat(JoinSides.sideOf("quantity", Schema.open(), declared))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("two open sides decide nothing")
        void twoOpenSides() {
            assertThat(JoinSides.sideOf("anything", Schema.open(), Schema.open()))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("a qualifier that names neither side is not pushed into the open side")
        void unmatchedQualifierIsUnknown() {
            // `J` is a view over this join: in scope above it, nowhere below.
            assertThat(JoinSides.sideOf("J.product_name", declared, Schema.open()))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
            assertThat(JoinSides.sideOf("J.product_name", Schema.open(), declared))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("a qualified name the declared side's provenance settles still goes there")
        void provenanceStillDecides() {
            assertThat(JoinSides.sideOf("orders.quantity", declared, Schema.open()))
                    .isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("a collision name the join invents is UNKNOWN — the open input holds it unsuffixed")
        void collisionNameIsUnknown() {
            assertThat(JoinSides.sideOf("product_id_r", declared, Schema.open()))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
            assertThat(JoinSides.sideOf("PRODUCT_ID_R2", declared, Schema.open()))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
            assertThat(JoinSides.sideOf("product_id_r", Schema.open(), declared))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("a name that only looks like a collision is a plain name")
        void lookalikeCollisionNames() {
            assertThat(JoinSides.sideOf("price_r", declared, Schema.open()))
                    .as("the declared side has no `price`").isEqualTo(JoinSides.Side.RIGHT);
            assertThat(JoinSides.sideOf("quantity_rx", declared, Schema.open()))
                    .as("not a numeric suffix").isEqualTo(JoinSides.Side.RIGHT);
            assertThat(JoinSides.sideOf("_r", declared, Schema.open()))
                    .as("no stem at all").isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("a qualifier naming only the open input settles it there (#972)")
        void qualifierNamesTheOpenInput() {
            Set<String> orders = Set.of("orders");
            Set<String> products = Set.of("products");
            assertThat(JoinSides.sideOf("products.product_name", declared, Schema.open(), orders, products))
                    .isEqualTo(JoinSides.Side.RIGHT);
            assertThat(JoinSides.sideOf("Products.product_id", Schema.open(), declared, products, orders))
                    .as("even for a name the declared side also carries")
                    .isEqualTo(JoinSides.Side.LEFT);
            assertThat(JoinSides.sideOf("products.product_name", Schema.open(), Schema.open(),
                    Set.of("docs"), products))
                    .as("two open inputs, told apart by name").isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("a qualifier the inputs' names do not settle stays unknown")
        void qualifierNamesUnsettled() {
            Set<String> products = Set.of("products");
            assertThat(JoinSides.sideOf("products.x", Schema.open(), Schema.open(), products, products))
                    .as("both inputs answer to it").isEqualTo(JoinSides.Side.UNKNOWN);
            assertThat(JoinSides.sideOf("orders.x", declared, Schema.open(), Set.of("orders"), products))
                    .as("it names the declared input, whose provenance has no `x`")
                    .isEqualTo(JoinSides.Side.UNKNOWN);
            assertThat(JoinSides.sideOf("products.details.maker", declared, Schema.open(),
                    Set.of("orders"), products))
                    .as("a longer dotted name is not read by the inputs' names")
                    .isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("provenance still outranks the inputs' names")
        void provenanceFirst() {
            assertThat(JoinSides.sideOf("orders.quantity", declared, Schema.open(),
                    Set.of("orders"), Set.of("orders")))
                    .isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("an open heading's known columns answer exactly, like a declared one")
        void knownColumnsOfAnOpenHeading() {
            Schema partlyOpen = declared.concat(Schema.open());
            Schema other = schema(from("returns", "reason"));
            assertThat(JoinSides.sideOf("quantity", partlyOpen, other))
                    .isEqualTo(JoinSides.Side.LEFT);
            assertThat(JoinSides.sideOf("reason", partlyOpen, other))
                    .as("the open heading could hold it too").isEqualTo(JoinSides.Side.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("A dotted name that is a path, not a qualifier")
    class Paths {

        private static Schema nested(String column, String... fields) {
            return new Schema(List.of(
                    new ColumnDefinition(column,
                            new com.darkcollective.relix.symbol.StructType(
                                    java.util.Arrays.stream(fields)
                                            .map(f -> new com.darkcollective.relix.symbol
                                                    .StructType.Field(f, ScalarType.STRING))
                                            .toList()))));
        }

        @Test
        @DisplayName("a path belongs to the side whose column is its head")
        void pathResolvesByItsHead() {
            // The tail says `city`, which only the right input has; the head says
            // `location`, which only the left input has. The head is the column the
            // reference names, so this is a left-hand predicate — and reading it by its
            // tail is what pushed a σ on a left nested column into the right input.
            Schema left = nested("location", "city", "team");
            Schema right = schema(col("city"));

            assertThat(JoinSides.sideOf("location.city", left, right))
                    .isEqualTo(JoinSides.Side.LEFT);
        }

        @Test
        @DisplayName("a path on the right resolves to the right")
        void pathOnTheRight() {
            assertThat(JoinSides.sideOf("location.city", schema(col("city")),
                    nested("location", "city")))
                    .isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("a path both sides could answer is UNKNOWN, not a guess")
        void ambiguousPath() {
            assertThat(JoinSides.sideOf("location.city",
                    nested("location", "city"), nested("location", "city")))
                    .isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("a field the struct does not declare is not a path")
        void unknownFieldFallsBackToTheBareName() {
            // `location` is on the left but has no `postcode`, so the path reading finds
            // nothing and the bare name decides — the legacy behaviour, kept.
            assertThat(JoinSides.sideOf("location.postcode",
                    nested("location", "city"), schema(col("postcode"))))
                    .isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("an open side answers no path, and does not take the other side's")
        void openSideAnswersNoPath() {
            // The reason the reading is per side rather than all-or-nothing. An open
            // schema resolves every name, so asking it about a path is meaningless — but
            // declining the whole reading is not the same as declining its answer: the
            // fallback reads the bare tail, which an open schema also answers, and the σ
            // on the left's nested column ends up pushed into the right regardless.
            Schema nestedLeft = nested("location", "city", "team");

            assertThat(JoinSides.sideOf("location.city", nestedLeft, Schema.open()))
                    .as("the closed side owns the path; the open side is not asked")
                    .isEqualTo(JoinSides.Side.LEFT);
            assertThat(JoinSides.sideOf("location.city", Schema.open(), nestedLeft))
                    .as("and the mirror")
                    .isEqualTo(JoinSides.Side.RIGHT);
        }

        @Test
        @DisplayName("two open sides resolve nothing by path, and fall back to the bare name")
        void bothOpenFallsBack() {
            assertThat(JoinSides.sideOf("location.city", Schema.open(), Schema.open()))
                    .as("every name is answered by both, so there is nothing to decide")
                    .isEqualTo(JoinSides.Side.UNKNOWN);
        }

        @Test
        @DisplayName("a genuine relation qualifier still resolves by provenance")
        void provenanceStillWins() {
            // The precedence that must not change: `A.k` is a qualified reference even
            // when one side carries a struct column called `A`.
            Schema left = new Schema(List.of(from("A", "k")));
            Schema right = new Schema(List.of(
                    new ColumnDefinition("A",
                            new com.darkcollective.relix.symbol.StructType(List.of(
                                    new com.darkcollective.relix.symbol.StructType.Field(
                                            "k", ScalarType.STRING))))));

            assertThat(JoinSides.sideOf("A.k", left, right))
                    .isEqualTo(JoinSides.Side.LEFT);
        }
    }

    @Test
    @DisplayName("the side label is the lower-case name, for a transformation record")
    void labels() {
        assertThat(JoinSides.Side.LEFT.label()).isEqualTo("left");
        assertThat(JoinSides.Side.RIGHT.label()).isEqualTo("right");
        assertThat(JoinSides.Side.UNKNOWN.label()).isEqualTo("unknown");
    }
}
