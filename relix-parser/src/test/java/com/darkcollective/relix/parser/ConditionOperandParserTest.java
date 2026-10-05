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

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.cmp;
import static com.darkcollective.relix.ast.AstBuilders.condition;
import static com.darkcollective.relix.ast.AstBuilders.not;
import static com.darkcollective.relix.ast.AstBuilders.nullPred;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.ast.AstBuilders.project;
import static com.darkcollective.relix.ast.AstBuilders.projected;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.select;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A parenthesised predicate in <em>operand</em> position — the spelling that reads a
 * condition's truth value rather than the condition.
 *
 * <p>It existed in the AST from the start ({@code ConditionOperand}) and the grammar
 * produced it in one position only, a function-call argument. Everywhere else the AST
 * could hold a tree the grammar could not read back, which made
 * {@code PrettyPrinter}'s output unparseable for a shape {@code AstBuilders} — main
 * source, and part of the published contract — is free to build.
 *
 * <p>The motivating use is the one question a null test on a <em>value</em> cannot ask:
 * whether a <em>comparison</em> came out UNKNOWN. {@code (amount > 100) = ⊥} is true for
 * exactly the rows a σ on {@code amount > 100} silently drops, which is what the rows
 * {@code ¬(amount > 100)} does not give back.
 */
@DisplayName("A condition in operand position")
final class ConditionOperandParserTest extends ParserTestSupport {

    @Nested
    @DisplayName("null test")
    class NullTest {

        @Test
        @DisplayName("(a > b) = ⊥ reads the comparison's truth value")
        void glyphForm() {
            assertParsesTo("σ (amount > 100) = ⊥ (Orders)",
                    select(nullPred(
                            condition(cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                            true), rel("Orders")));
        }

        @Test
        @DisplayName("the SQL-prior IS NULL spelling parses to the same AST")
        void sqlPriorForm() {
            assertParsesTo("σ (amount > 100) IS NULL (Orders)",
                    select(nullPred(
                            condition(cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                            true), rel("Orders")));
        }

        @Test
        @DisplayName("≠ ⊥ is the IS NOT NULL form")
        void notNullForm() {
            assertParsesTo("σ (amount > 100) ≠ ⊥ (Orders)",
                    select(nullPred(
                            condition(cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                            false), rel("Orders")));
        }
    }

    @Nested
    @DisplayName("projection")
    class Projection {

        @Test
        @DisplayName("a condition can be projected directly, not only through a function")
        void projectedCondition() {
            assertParsesTo("π (amount > 100) → big (Orders)",
                    project(List.of(projected(
                            condition(cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                            "big")), rel("Orders")));
        }
    }

    @Nested
    @DisplayName("what did NOT change")
    class Unchanged {

        @Test
        @DisplayName("a parenthesised predicate with no tail is still just the predicate")
        void groupingIsUntouched() {
            // The escalation is keyed on what FOLLOWS the closing parenthesis, so the
            // overwhelmingly common case — parentheses used for grouping — is unaffected.
            assertParsesTo("σ (amount > 100) (Orders)",
                    select(cmp(attr("amount"), ComparisonOperator.GREATER, num("100")),
                            rel("Orders")));
        }

        @Test
        @DisplayName("a parenthesised ARITHMETIC expression is still arithmetic")
        void arithmeticParenthesisIsUntouched() {
            RelNode parsed = parse("π (amount + 1) → x (Orders)");

            assertThat(parsed.prettyPrint()).isEqualTo("π amount + 1 → x (Orders)");
        }

        @Test
        @DisplayName("a function argument still accepts the bare form it always did")
        void bareFunctionArgumentStillParses() {
            // Locations differ — the parentheses shift every column — so the claim is
            // about the trees, which is what stripLocations is for.
            assertThat(stripLocations(parse("π IIf(amount > 100, \"big\", \"small\") → sz (Orders)")))
                    .isEqualTo(stripLocations(
                            parse("π IIf((amount > 100), \"big\", \"small\") → sz (Orders)")));
        }
    }

    @Nested
    @DisplayName("a condition inside a call inside a group")
    class CallInsideGroup {

        @Test
        @DisplayName("an arithmetic group holding a call with a condition argument is arithmetic")
        void conditionArgumentDoesNotMakeTheGroupAPredicate() {
            // The = belongs to Iif's own argument list, so it says nothing about what
            // the surrounding parenthesis opens. (The printer parenthesises a condition
            // argument; the grouping it keeps around a + Iif(…) is the point.)
            assertThat(parse("π 4 * (a + Iif(b = 1, 1, 0)) → r (T)").prettyPrint())
                    .isEqualTo("π 4 * (a + Iif((b = 1), 1, 0)) → r (T)");
        }

        @Test
        @DisplayName("a call alone in a group, with a condition argument, is an operand")
        void callAloneInAGroup() {
            assertThat(stripLocations(parse("π (Iif(b = 1, 1, 0)) → r (T)")))
                    .isEqualTo(stripLocations(parse("π Iif(b = 1, 1, 0) → r (T)")));
        }

        @Test
        @DisplayName("a comparison beside the call, at the group's own level, still makes a predicate")
        void comparisonAtTheGroupsLevel() {
            assertThat(stripLocations(parse("σ (Iif(b = 1, 1, 0) = 1) (T)")))
                    .isEqualTo(stripLocations(parse("σ Iif(b = 1, 1, 0) = 1 (T)")));
        }

        @Test
        @DisplayName("a predicate inside a plain nested group is still a predicate")
        void nestedGroupStillCounts() {
            assertParsesTo("σ ((amount > 100)) (Orders)",
                    select(cmp(attr("amount"), ComparisonOperator.GREATER, num("100")),
                            rel("Orders")));
        }

        @Test
        @DisplayName("an operator keyword written against a parenthesis still makes a predicate")
        void operatorKeywordAgainstAParenthesis() {
            assertParsesTo("σ (NOT(amount > 100)) (Orders)",
                    select(not(cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                            rel("Orders")));
        }

        @Test
        @DisplayName("a parenthesis set apart from a name is not a call, as the call parser reads it")
        void spacedParenthesisIsNotACall() {
            // The group's = is counted, so the parenthesis opens a predicate whose
            // left operand is the bare name Iif — and the error is the predicate's.
            assertThatThrownBy(() -> parse("π (Iif (b = 1, 1, 0)) → r (T)"))
                    .hasMessageContaining("Expected comparison operator");
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        /**
         * The property the whole change exists for: a tree built through the authoring
         * surface prints to text the grammar reads back as the same tree.
         */
        private void roundTrips(RelNode built) {
            String printed = built.prettyPrint();
            assertThat(stripLocations(parse(printed)))
                    .as("printed as: %s", printed)
                    .isEqualTo(stripLocations(built));
        }

        @Test
        @DisplayName("a null test over a condition")
        void nullTestOverCondition() {
            roundTrips(select(nullPred(
                    condition(cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                    true), rel("Orders")));
        }

        @Test
        @DisplayName("a projected condition")
        void projectedCondition() {
            roundTrips(project(List.of(projected(
                    condition(cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                    "big")), rel("Orders")));
        }

        @Test
        @DisplayName("a comparison whose left side is a condition")
        void comparisonOverCondition() {
            roundTrips(select(cmp(
                    condition(cmp(attr("amount"), ComparisonOperator.GREATER, num("100"))),
                    ComparisonOperator.EQUAL,
                    com.darkcollective.relix.ast.AstBuilders.bool(true)), rel("Orders")));
        }
    }
}
