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

import com.darkcollective.relix.ast.AstAssertions;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.ExistsPredicate;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.LateralKind;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.QuantifiedPredicate;
import com.darkcollective.relix.ast.Quantifier;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.ScalarSubqueryOperand;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.Spelling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.ComparisonOperator.EQUAL;
import static com.darkcollective.relix.ast.ComparisonOperator.GREATER;
import static org.assertj.core.api.Assertions.assertThat;

/** Subqueries as values, EXISTS, IN and ANY/ALL over a relation, and LATERAL over an expression (#261). */
final class SubqueryParserTest extends ParserTestSupport {

    private static final String COUNT_ORDERS = "(γ COUNT(*) → n (σ customer_id = Customers.customer_id (Orders)))";

    @Test
    @DisplayName("a relation in parentheses is a value in a projection and in a comparison")
    void scalarSubquery() {
        RelNode projected = parse("π name, " + COUNT_ORDERS + " → order_count (Customers)");
        ProjectionNode projection = (ProjectionNode) projected;
        assertThat(projection.attributes().get(1).expression()).isInstanceOf(ScalarSubqueryOperand.class);
        assertThat(((ScalarSubqueryOperand) projection.attributes().get(1).expression()).query().prettyPrint())
                .isEqualTo("γ COUNT(1) → n (σ customer_id = Customers.customer_id (Orders))");

        SelectionNode selection = (SelectionNode) parse("σ s = (γ MAX(s) → m (T)) (T)");
        assertThat(((ComparisonPredicate) selection.predicate()).right()).isInstanceOf(ScalarSubqueryOperand.class);
        AstAssertions.assertThat(parse("σ (γ COUNT(*) → n (S)) > 0 (R)")).isNode(SelectionNode.class);
        assertThat(parse("σ (γ k, COUNT(*) → n (S)) > 0 (R)").prettyPrint())
                .isEqualTo("σ (γ k, COUNT(1) → n (S)) > 0 (R)");
    }

    @Test
    @DisplayName("every relational operator opens a subquery, as does a name and a join, in ASCII too")
    void openers() {
        for (String text : new String[] {"π v (S)", "σ v > 1 (S)", "λ 1 (τ v (S))", "δ (π v (S))",
                "TOP 1 v (S)", "ρ U (π v (S))", "S ⋈ T"}) {
            SelectionNode selection = (SelectionNode) parse("σ x = (" + text + ") (R)");
            assertThat(((ComparisonPredicate) selection.predicate()).right())
                    .as(text).isInstanceOf(ScalarSubqueryOperand.class);
        }
        assertThat(parse("PROJECT name, (GROUP COUNT(*) -> n (SELECT k = R.k (S))) -> c (R)").prettyPrint())
                .isEqualTo("π name, (γ COUNT(1) → n (σ k = R.k (S))) → c (R)");
    }

    @Test
    @DisplayName("a subquery is an argument, a quantifier's relation may open on a join, and ANY's may be followed by OR")
    void morePositions() {
        assertThat(parse("π Coalesce((γ MAX(v) → m (S)), 0) → m (R)").prettyPrint())
                .isEqualTo("π Coalesce((γ MAX(v) → m (S)), 0) → m (R)");
        assertThat(parse("π IIf((a > 1), 1, 0) → b (R)").prettyPrint()).isEqualTo("π IIf((a > 1), 1, 0) → b (R)");
        assertThat(parse("σ k = ANY (S ⋈ T) (R)").prettyPrint()).isEqualTo("σ k = ANY ((S) ⋈ (T)) (R)");
        assertThat(parse("σ s > ANY (π v (S)) OR a = 1 (R)").prettyPrint())
                .isEqualTo("σ (s > ANY (π v (S))) ∨ (a = 1) (R)");
        assertParseError("σ x IN (SELECT S.y (S)) (R)").hasMessageContaining("such as (π y (S))");
        AstAssertions.assertThat(parse("σ x IN (SELECT (a > 1) (S)) (R)")).isNode(SelectionNode.class);
    }

    @Test
    @DisplayName("SQL's LEFT JOIN without LATERAL is still told to write LJOIN")
    void leftJoinStillLjoin() {
        assertParseError("R LEFT JOIN S ON R.k = S.k").hasMessageContaining("LJOIN");
        assertParseError("R LEFT OUTER JOIN S ON R.k = S.k").hasMessageContaining("LJOIN");
    }

    @Test
    @DisplayName("an operator's word in parentheses before an operator or a ')' is still a column")
    void keywordColumnsStayColumns() {
        assertParsesTo("π (group) + 1 → a, (project) → b (T)", parse("π `group` + 1 → a, `project` → b (T)"));
        assertParsesTo("σ (top) > 1 (T)", parse("σ `top` > 1 (T)"));
        assertParsesTo("π (top) * 2 → x (T)", parse("π `top` * 2 → x (T)"));
        assertParsesTo("σ (sort IS NULL) (T)", parse("σ `sort` IS NULL (T)"));
    }

    @Test
    @DisplayName("EXISTS takes a relation, as a condition and as a value, and NOT EXISTS negates it")
    void exists() {
        SelectionNode selection = (SelectionNode) parse(
                "σ EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers)");
        assertThat(selection.predicate()).isInstanceOf(ExistsPredicate.class);
        SelectionNode negated = (SelectionNode) parse("σ NOT EXISTS (σ k = R.k (S)) (R)");
        assertThat(((NotPredicate) negated.predicate()).predicate()).isInstanceOf(ExistsPredicate.class);
        ProjectionNode projection = (ProjectionNode) parse("π k, EXISTS (σ k = R.k (S)) → has (R)");
        assertThat(((ConditionOperand) projection.attributes().get(1).expression()).predicate())
                .isInstanceOf(ExistsPredicate.class);
        assertThat(parse("σ a = 1 AND EXISTS (σ k = R.k (S)) (R)").prettyPrint())
                .isEqualTo("σ (a = 1) ∧ (EXISTS (σ k = R.k (S))) (R)");
    }

    @Test
    @DisplayName("IN over a relation is = ANY, NOT IN its negation")
    void in() {
        SelectionNode selection = (SelectionNode) parse("σ k IN (π k (S)) (R)");
        QuantifiedPredicate any = (QuantifiedPredicate) selection.predicate();
        assertThat(any.operator()).isEqualTo(EQUAL);
        assertThat(any.quantifier()).isEqualTo(Quantifier.ANY);
        SelectionNode negated = (SelectionNode) parse("σ k NOT IN (π k (S)) (R)");
        assertThat(((NotPredicate) negated.predicate()).predicate()).isInstanceOf(QuantifiedPredicate.class);
        assertParsesTo("σ k ∉ (π k (S)) (R)", parse("σ k NOT IN (π k (S)) (R)"));
        assertThat(parse("σ k IN (1, 2) (R)").prettyPrint()).isEqualTo("σ k ∈ {1, 2} (R)");
    }

    @Test
    @DisplayName("ANY, SOME and ALL take a relation, after which the selection's input follows")
    void quantified() {
        QuantifiedPredicate all = (QuantifiedPredicate) ((SelectionNode) parse("σ s > ALL (π v (S)) (R)")).predicate();
        assertThat(all.operator()).isEqualTo(GREATER);
        assertThat(all.quantifier()).isEqualTo(Quantifier.ALL);
        QuantifiedPredicate some = (QuantifiedPredicate) ((SelectionNode) parse("σ s = some (π v (S)) (R)")).predicate();
        assertThat(some.quantifier()).isEqualTo(Quantifier.ANY);
        assertThat(parse("σ (s > ALL (π v (S))) (R)").prettyPrint()).isEqualTo("σ s > ALL (π v (S)) (R)");
        assertThat(parse("σ s > ANY (π v (S)) AND a = 1 (R)").prettyPrint())
                .isEqualTo("σ (s > ANY (π v (S))) ∧ (a = 1) (R)");
        assertThat(parse("π (s > ALL (π v (S))) → b (R)").prettyPrint()).isEqualTo("π (s > ALL (π v (S))) → b (R)");
    }

    @Test
    @DisplayName("with nothing after it, a quantifier's relation is still the selection's input")
    void quantifierWordStaysAColumn() {
        SelectionNode selection = (SelectionNode) parse("σ s > all (π s (T))");
        assertThat(selection.predicate()).isInstanceOf(ComparisonPredicate.class);
        assertThat(selection.input().prettyPrint()).isEqualTo("π s (T)");
    }

    @Test
    @DisplayName("LATERAL takes a relation in parentheses, and OUTER LATERAL keeps an unmatched row")
    void lateral() {
        LateralJoinNode inner = (LateralJoinNode) parse("Customers LATERAL (σ customer_id = Customers.customer_id (Orders))");
        assertThat(inner.kind()).isEqualTo(LateralKind.INNER);
        assertThat(inner.isFunctionCall()).isFalse();
        LateralJoinNode outer = (LateralJoinNode) parse("Customers OUTER LATERAL (λ 1 (σ k = Customers.k (Orders)))");
        assertThat(outer.kind()).isEqualTo(LateralKind.OUTER);
        LateralJoinNode call = (LateralJoinNode) parse("Customers OUTER LATERAL ordersFor(customer_id)");
        AstAssertions.assertThat(call.right()).isNode(RelationFunctionCall.class);
        assertThat(call.kind()).isEqualTo(LateralKind.OUTER);
        assertThat(parse("A ⋈ B OUTER LATERAL (σ k = A.k (C))").prettyPrint())
                .isEqualTo("((A) ⋈ (B)) OUTER LATERAL (σ k = A.k (C))");
    }

    @Test
    @DisplayName("printed back, every form parses to the tree it came from")
    void roundTrips() {
        for (String text : new String[] {
                "π name, " + COUNT_ORDERS + " → order_count (Customers)",
                "σ EXISTS (σ k = R.k (S)) (R)",
                "σ ¬(EXISTS (σ k = R.k (S))) (R)",
                "σ s > ALL (π v (S)) (R)",
                "σ k = ANY (π k (S)) (R)",
                "(Customers) LATERAL (σ k = Customers.k (Orders))",
                "(Customers) OUTER LATERAL (σ k = Customers.k (Orders))",
                "(Customers) OUTER LATERAL f(k)"}) {
            RelNode tree = parse(text);
            assertParsesTo(tree.prettyPrint(), tree);
            assertParsesTo(tree.prettyPrint(Spelling.KEYWORDS), tree);
        }
    }

    @Test
    @DisplayName("SQL's subquery, SELECT … FROM, is refused with the Relix spelling")
    void sqlSubqueryRefused() {
        assertParseError("σ x = (SELECT MAX(v) FROM S) (R)").hasMessageContaining("SELECT is σ in Relix")
                .hasMessageContaining("(π v (σ k = R.k (S)))");
        assertParseError("σ EXISTS (SELECT 1 FROM S) (R)").hasMessageContaining("SELECT is σ in Relix");
        assertParseError("σ x > ALL (SELECT v FROM S) (R)")
                .hasMessageContaining("write the relation ALL compares with as (π v (S))");
        assertParseError("σ x IN (SELECT y (S)) (R)").hasMessageContaining("such as (π y (S))")
                .hasMessageContaining("R SEMI R.x = S.y S");
    }

    @Test
    @DisplayName("SQL's lateral spellings are refused naming LATERAL or OUTER LATERAL")
    void sqlLateralRefused() {
        assertParseError("Customers CROSS APPLY (σ k = Customers.k (Orders))")
                .hasMessageContaining("'CROSS APPLY' is SQL's spelling of LATERAL: write Customers LATERAL (σ k = Customers.k (Orders))");
        assertParseError("Customers OUTER APPLY (σ k = Customers.k (Orders))")
                .hasMessageContaining("'OUTER APPLY' is SQL's spelling of OUTER LATERAL: write Customers OUTER LATERAL");
        assertParseError("Customers LEFT LATERAL (σ k = Customers.k (Orders))")
                .hasMessageContaining("is SQL's spelling of OUTER LATERAL");
        assertParseError("Customers LEFT JOIN LATERAL (σ k = Customers.k (Orders))")
                .hasMessageContaining("'LEFT JOIN LATERAL' is SQL's spelling of OUTER LATERAL");
    }
}
