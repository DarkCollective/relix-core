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

import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UsingJoinNode;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ParserErrorTest extends ParserTestSupport {

    @Test
    public void reportsEmptyInput() {
        assertParseError("")
                .hasMessageContaining("Expected relation name")
                .at(1, 1);
    }

    @Test
    public void reportsUnexpectedCharacter() {
        assertParseError("@")
                .hasMessageContaining("Unexpected character")
                .at(1, 1);
    }

    @Test
    public void reportsMissingProjectionAttribute() {
        assertParseError("π (Users)")
                .hasMessageContaining("'(Users)' was read as a column, and the input relation is"
                        + " missing: the list of columns comes first, then the input in"
                        + " parentheses, as in π a, b (Users)")
                .at(1, 10);
    }

    @Test
    public void reportsTrailingProjectionComma() {
        assertParseError("π id, (Users)")
                .hasMessageContaining("as in π id (Users)")
                .at(1, 15);
    }

    @Test
    public void reportsMissingProjectionInputParen() {
        assertParseError("π id Users")
                .hasMessageContaining("The input relation goes in parentheses: write π id (Users)")
                .at(1, 6);
    }

    @Test
    @DisplayName("what follows a projection's list says which mistake left out its input's parentheses")
    public void reportsWhatFollowsTheProjectionList() {
        assertParseError("PROJECT id SELECT v > 1 (R)")
                .hasMessageContaining("The input goes in parentheses: write PROJECT id (SELECT v > 1 (R))")
                .at(1, 12);
        assertParseError("PROJECT 201 -> room, 0 -> dist")
                .hasMessageContaining("PROJECT needs an input relation in parentheses. A row of"
                        + " constants is an inline table, a statement of its own: write"
                        + " Name := [| room | dist |], with the row | 201 | 0 | on the next"
                        + " line, then ];");
        assertParseError("PROJECT id, v")
                .hasMessageContaining("PROJECT takes its input relation in parentheses after the"
                        + " list: write PROJECT id, v (R)");
        assertParseError("PROJECT r1.id FROM R r1")
                .hasMessageContaining("Relix has no FROM: the input relation goes in parentheses"
                        + " after the list, as in PROJECT r1.id (R)")
                .at(1, 15);
        assertParseError("PROJECT id, v WHERE v > 1 (R)")
                .hasMessageContaining("Relix has no WHERE: a filter is SELECT, written around the"
                        + " input, as in PROJECT id, v (SELECT v > 1 (R))");
        assertParseError("PROJECT id, v > 2 -> big (R)")
                .hasMessageContaining("A condition as a value goes in parentheses: write (v > 2) -> big")
                .at(1, 15);
        assertParseError("PROJECT id, v IS NULL OR v < 0 (R)")
                .hasMessageContaining("write (v IS NULL OR v < 0) -> name");
        assertParseError("PROJECT id, ROW_NUMBER() SORT at ASC PER grp AS rn (R)")
                .hasMessageContaining("A window function is WINDOW, its own operator, and cannot"
                        + " be a column of PROJECT: write PROJECT id, rn (WINDOW ROW_NUMBER()"
                        + " SORT at ASC PER grp AS rn (R))")
                .at(1, 26);
        assertParseError("PROJECT id, v DIV 2 -> half (R)")
                .hasMessageContaining("Relix has no DIV: for the whole part of a division,"
                        + " write Int(v / 2)")
                .at(1, 15);
        // A token none of these explains keeps the plain message.
        assertParseError("PROJECT id ] (R)")
                .hasMessageContaining("Expected '(' after projection attribute list");
    }

    @Test
    public void reportsMissingSelectionInputParen() {
        assertParseError("σ age > 18 Users")
                .hasMessageContaining("Expected '(' after selection predicate")
                .at(1, 12);
    }

    @Test
    public void reportsMissingSelectionComparison() {
        assertParseError("σ age (Users) ∪ T")
                .hasMessageContaining("'σ' filters rows by a condition, and found '(' where its"
                        + " comparison belongs. To choose columns, use PROJECT (π): write π age (Users)")
                .at(1, 7);
        assertParseError("σ age > 1 AND name (Users)")
                .hasMessageContaining("Expected comparison operator")
                .at(1, 20);
        assertParseError("SELECT age (")
                .hasMessageContaining("Expected comparison operator")
                .at(1, 12);
    }

    @Test
    @DisplayName("SELECT with one column or aggregate and an input names PROJECT or GROUP (#203)")
    public void reportsSingleColumnSelect() {
        assertParseError("SELECT x (A)")
                .hasMessageContaining("'SELECT' (σ) filters rows by a condition, and found '(' where"
                        + " its comparison belongs. To choose columns, use PROJECT (π): write PROJECT x (A)")
                .at(1, 10);
        assertParseError("SELECT name (SELECT name LIKE '%P%' (devices))")
                .hasMessageContaining("write PROJECT name (SELECT name LIKE '%P%' (devices))");
        assertParseError("SELECT COUNT(*) (A)")
                .hasMessageContaining("'SELECT' filters rows; an aggregate belongs in GROUP:"
                        + " write GROUP COUNT(*) -> n (A)")
                .at(1, 14);
        assertParseError("SELECT MAX(x) (A)")
                .hasMessageContaining("'SELECT' filters rows; an aggregate belongs in GROUP:"
                        + " write GROUP MAX(x) -> m (A)")
                .at(1, 15);
        assertParseError("σ MAX(x) (A)")
                .hasMessageContaining("'σ' filters rows; an aggregate belongs in γ: write γ MAX(x) → m (A)");
        assertThat(parse("PROJECT x (A)")).isNode(ProjectionNode.class);
        assertThat(parse("π (a + b) → c (R)")).isNode(ProjectionNode.class);
        assertThat(parse("GROUP COUNT(*) -> n (A)")).isNode(AggregationNode.class);
        assertThat(parse("GROUP MAX(x) -> m (A)")).isNode(AggregationNode.class);
    }

    @Test
    @DisplayName("SELECT followed by SQL's column list says that SELECT filters, and names PROJECT")
    public void reportsColumnListAfterSelect() {
        assertParseError("SELECT 'TOTAL' -> Item, SUM(Value) -> Value (Selection)")
                .hasMessageContaining("'SELECT' filters rows; an aggregate belongs in GROUP:"
                        + " write GROUP AVG(x) -> avg (Selection)")
                .at(1, 16);
        assertParseError("σ a, b (R)")
                .hasMessageContaining("'σ' filters rows by a condition, and found ','")
                .at(1, 4);
        assertParseError("select a → b (R)")
                .hasMessageContaining("'select' (σ) filters rows by a condition, and found '→'");
    }

    @Test
    @DisplayName("SQL's subquery as a value is told what Relix writes instead")
    public void reportsSqlSubqueryAsValue() {
        String message = "Relix has no subqueries as values: join the relation instead"
                + " (a SEMI join, ⋉, keeps the rows that have a match), use GROUP for a count"
                + " or a total, or WINDOW LAG / LEAD for the previous or next row";
        assertParseError("PROJECT id, (SELECT v FROM R WHERE grp = 1) -> w (R)")
                .hasMessageContaining(message)
                .at(1, 14);
        assertParseError("SELECT v > (SELECT MAX(v) FROM R) (R)").hasMessageContaining(message);
        assertParseError("SELECT EXISTS (SELECT id FROM S) (R)").hasMessageContaining(message);
        assertParseError("PROJECT Coalesce((SELECT v FROM R), 0) -> w (R)")
                .hasMessageContaining(message);
        // A column called select is still a column.
        assertPrettyPrints(parse("PROJECT (select > 1) -> big (R)"), "π (`select` > 1) → big (R)");
    }

    @Test
    @DisplayName("a projected column renamed with =, : or AS names expr -> name, at the sign")
    public void reportsForeignAliasInProjection() {
        assertParseError("PROJECT loan_id, days = loan_id * 2 (loans)")
                .hasMessageContaining("a column is named with expr -> name, not name = expr:"
                        + " write loan_id * 2 -> days")
                .at(1, 23);
        assertParseError("PROJECT member_id, book_name: name (loans)")
                .hasMessageContaining("a column is named with expr -> name, not name: expr:"
                        + " write name -> book_name")
                .at(1, 29);
        assertParseError("TOP 2 seconds ASC WITH TIES (T")
                .hasMessageContaining(": write TOP 2 WITH TIES seconds ASC (…)")
                .at(1, 19);
        assertParseError("PROJECT member_id, name AS book_name (loans)")
                .hasMessageContaining("a column is named with expr -> name, and AS is SQL's"
                        + " spelling of '->': write name -> book_name")
                .at(1, 25);
        assertParseError("π days_out = DATEDIFF(day, out_on, back_on) (loans)")
                .hasMessageContaining("write DATEDIFF(day, out_on, back_on) -> days_out");
        assertParseError("π days =\n  loan_id\n  * 2 (loans)")
                .hasMessageContaining("write loan_id * 2 -> days")
                .at(1, 8);
    }

    @Test
    @DisplayName("only a sign after one name, or AS before a name, is taken for a rename")
    public void foreignAliasNeedsItsShape() {
        assertParseError("PROJECT a AS (loans)")
                .hasMessageContaining("Expected '(' after projection attribute list");
        // Not renames: a comparison, which as a column is a condition in parentheses.
        assertParseError("PROJECT 1 = a (loans)")
                .hasMessageContaining("A condition as a value goes in parentheses: write (1 = a) -> name");
        assertParseError("PROJECT a + 1 = b (loans)")
                .hasMessageContaining("write (a + 1 = b) -> name");
        assertParseError("GROUP k, COUNT(*) -> n, total (loans)")
                .hasMessageContaining("Expected '(' after aggregation expression");
    }

    @Test
    @DisplayName("an aggregate or key renamed with =, : or AS in GROUP names expr -> name, at the sign")
    public void reportsForeignAliasInAggregation() {
        assertParseError("GROUP member_id, n = COUNT(*) (loans)")
                .hasMessageContaining("a column is named with expr -> name, not name = expr:"
                        + " write COUNT(*) -> n")
                .at(1, 20);
        assertParseError("GROUP member_id, COUNT(*) AS n (loans)")
                .hasMessageContaining("a column is named with expr -> name, and AS is SQL's"
                        + " spelling of '->': write COUNT(*) -> n")
                .at(1, 27);
        assertParseError("GROUP member_id, COUNT(*) -> n, total: SUM(fine) (loans)")
                .hasMessageContaining("not name: expr: write SUM(fine) -> total")
                .at(1, 38);
        assertParseError("GROUP member_id, COUNT(*) -> n, total = SUM(fine) (loans)")
                .hasMessageContaining("not name = expr: write SUM(fine) -> total")
                .at(1, 39);
        assertParseError("GROUP yr = YEAR(out_on), COUNT(*) -> n (loans)")
                .hasMessageContaining("not name = expr: write YEAR(out_on) -> yr")
                .at(1, 10);
        assertParseError("γ member_id AS m, COUNT(*) -> n (loans)")
                .hasMessageContaining("write member_id -> m")
                .at(1, 13);
    }

    @Test
    @DisplayName("the column-list hint is a selection's own: a join condition keeps the plain message")
    public void columnListHintStaysWithItsSelection() {
        assertParseError("π a, b (R) >< x -> y S")
                .hasMessageContaining("Expected comparison operator");
        assertParseError("σ a > 1 (R >< x -> y S)")
                .hasMessageContaining("Expected comparison operator");
        // A selection in another's input gets the hint for its own condition.
        assertParseError("σ a > 1 (σ b, c (R))")
                .hasMessageContaining("'σ' filters rows by a condition, and found ','")
                .at(1, 13);
    }

    @Test
    @DisplayName("OPTIMIZE constraints listed with a comma are told to join with AND")
    public void reportsCommaBetweenOptimizeConstraints() {
        assertParseError("OPTIMIZE MAXIMIZE SUM(v) SUBJECT TO SUM(w) <= 80, SUM(u) <= 60 (Items)")
                .hasMessageContaining("Join OPTIMIZE constraints with AND, not ',':"
                        + " SUBJECT TO SUM(a) <= 80 AND SUM(b) <= 60")
                .at(1, 49);
        // A comma before a PER key list is something else, and keeps its own message.
        assertParseError("OPTIMIZE MAXIMIZE SUM(v) SUBJECT TO SUM(w) <= 80, x (Items)")
                .hasMessageContaining("Expected '(' after the OPTIMIZE specification");
    }

    @Test
    @DisplayName("a column list with no operator before it names PROJECT, in a rewrite that parses (#225)")
    public void namesProjectForABareColumnList() {
        String written = "A column list needs PROJECT (π) before it: write ";
        assertParseError("category, parent (T)")
                .hasMessageContaining(written + "PROJECT category, parent (T)")
                .at(1, 9);
        RelAlgebraParser.parse("PROJECT category, parent (T)");
        assertParseError("T.category, T.parent (T)")
                .hasMessageContaining(written + "PROJECT T.category, T.parent (T)");
        RelAlgebraParser.parse("PROJECT T.category, T.parent (T)");
        String filtered = "PROJECT view_id, customer_id, viewed_at (σ customer_id = 7 (page_views))";
        assertParseError("view_id, customer_id, viewed_at (σ customer_id = 7 (page_views))")
                .hasMessageContaining(written + filtered);
        RelAlgebraParser.parse(filtered);
        // No input to echo, so no rewrite of the writer's own.
        assertParseError("a, b")
                .hasMessageContaining(written + "PROJECT a, b (R)");
        // A relation before the comma keeps the plain message.
        assertParseError("R ⋈ S, b")
                .hasMessageContaining("Expected end of input");
    }

    @Test
    @DisplayName("a GROUP after a PROJECT that holds the aggregates moves them into GROUP, in a rewrite that parses")
    public void movesProjectedAggregatesIntoGroup() {
        String written = "In Relix the aggregates go in GROUP, after its keys: write ";
        String rewrite = "GROUP customer_id, MAX(viewed_at) -> last_view (P)";
        assertParseError("PROJECT customer_id, MAX(viewed_at) -> last_view (P) GROUP customer_id")
                .hasMessageContaining(written + rewrite)
                .at(1, 54);
        assertParseError("PROJECT customer_id, MAX(viewed_at) -> last_view (P) GROUP BY customer_id")
                .hasMessageContaining(written + rewrite);
        // Written in Relix's order but with the aggregate still in the PROJECT.
        assertParseError("GROUP customer_id (PROJECT customer_id, MAX(viewed_at) -> last_view (P))")
                .hasMessageContaining(written + rewrite);
        RelAlgebraParser.parse(rewrite);
        String several = "GROUP a, b, SUM(x * 2) -> s, AVG(y) -> n (SELECT x > 1 (T))";
        assertParseError("PROJECT a, b, SUM(x * 2) -> s, AVG(y) -> n (SELECT x > 1 (T)) GROUP a, b")
                .hasMessageContaining(written + several);
        RelAlgebraParser.parse(several);
        assertParseError("π k, MAX(x) → m (R) γ k")
                .hasMessageContaining("In Relix the aggregates go in γ, after its keys: write γ k, MAX(x) → m (R)");
        RelAlgebraParser.parse("γ k, MAX(x) → m (R)");
        // No aggregate in the PROJECT, or more than one PROJECT: the messages they had.
        assertParseError("PROJECT a, b (T) GROUP a")
                .hasMessageContaining("'GROUP' is written before its input");
        assertParseError("GROUP a (PROJECT a, b (T))")
                .hasMessageContaining("for distinct rows write DISTINCT (PROJECT a (PROJECT a, b (T)))");
        assertParseError("PROJECT a, STDDEV(x) -> sd (T) GROUP a")
                .hasMessageContaining(written + "GROUP a, STDDEV(x) -> sd (T)");
        RelAlgebraParser.parse("GROUP a, STDDEV(x) -> sd (T)");
        // A scalar call is not an aggregate, and is left behind with the keys.
        assertParseError("PROJECT a, UPPER(b) -> u, MAX(x) -> m (T) GROUP a")
                .hasMessageContaining(written + "GROUP a, MAX(x) -> m (T)");
        for (String other : List.of("R ⋈ PROJECT a, MAX(x) -> m (T) GROUP a",
                "PROJECT a (R) ⋈ PROJECT a, MAX(x) -> m (T) GROUP a",
                "PROJECT a, MAX(x) -> m (T) ⋈ R GROUP a")) {
            assertParseError(other).hasMessageContaining("'GROUP' is written before its input");
        }
    }

    @Test
    @DisplayName("an operator written after its input, SQL's ORDER BY order, is rewritten into Relix's")
    public void reportsOperatorAfterItsInput() {
        assertParseError("PROJECT Item, Value (Selected) SORT Item ASC")
                .hasMessageContaining("'SORT' is written before its input, which it takes in"
                        + " parentheses: SORT Item ASC (PROJECT Item, Value (Selected))")
                .at(1, 32);
        assertParseError("R ⋈ S LIMIT 5")
                .hasMessageContaining("'LIMIT' is written before its input, which it takes in"
                        + " parentheses: LIMIT 5 (R ⋈ S)");
        assertParseError("π a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z (R) σ a > 1")
                .hasMessageContaining("parentheses: σ a > 1 (…)");
        // Anything else after a whole expression is still the end of input expected.
        assertParseError("R S")
                .hasMessageContaining("Expected end of input");
    }

    @Test
    @DisplayName("SQL's clauses after an expression are rewritten into Relix's operators, in SQL's order")
    public void reportsSqlClausesAfterTheirInput() {
        assertParseError("PROJECT read_at, value (readings) WHERE capability_id = 703")
                .hasMessageContaining("Relix has no WHERE: a filter is SELECT, written before its"
                        + " input, which it takes in parentheses: PROJECT read_at, value"
                        + " (SELECT capability_id = 703 (readings))")
                .at(1, 35);
        assertParseError("PROJECT grp, COUNT(id) -> n (R) WHERE v > 0 GROUP BY grp ORDER BY n DESC LIMIT 2")
                .hasMessageContaining("parentheses: LIMIT 2 (SORT n DESC"
                        + " (GROUP grp, COUNT(id) -> n (SELECT v > 0 (R))))");
        assertParseError("SELECT v > 1 (R) ORDER BY v DESC")
                .hasMessageContaining("'ORDER BY' is SORT, written before its input, which it"
                        + " takes in parentheses: SORT v DESC (SELECT v > 1 (R))");
        assertParseError("R ⋈ S GROUP BY k")
                .hasMessageContaining("'GROUP BY' is written before its input, which it takes in"
                        + " parentheses, with its keys and then its aggregates:"
                        + " GROUP k, SUM(x) -> total (R ⋈ S)");
        assertParseError("(PROJECT id, v (R) FROM R)")
                .hasMessageContaining("Relix has no FROM: an operator takes its input in"
                        + " parentheses after it, as in PROJECT a, b (R)");
        assertParseError("PROJECT id (R) AS x")
                .hasMessageContaining("Relix has no AS for a relation: to name one, write RENAME"
                        + " before it, which takes it in parentheses: RENAME x (PROJECT id (R))");
        // A projection joined to something else takes the clause around the whole join.
        assertParseError("PROJECT id (R) >< R.id = S.id S WHERE v > 1")
                .hasMessageContaining("parentheses: SELECT v > 1 (PROJECT id (R) >< R.id = S.id S)");
    }

    @Test
    @DisplayName("a binary operator written before its inputs is reported at the operator, with its infix form")
    public void reportsInfixOperatorWrittenFirst() {
        assertParseError("JOIN A >< A.id = B.a_id B")
                .hasMessageContaining("'JOIN' goes between the two relations it joins:"
                        + " A JOIN B, or A >< condition B")
                .at(1, 1);
        assertParseError("UNION A B")
                .hasMessageContaining("'UNION' goes between the two relations it combines: A UNION B")
                .at(1, 1);
        assertParseError("σ a > 1 (ljoin A B)")
                .hasMessageContaining("'ljoin' goes between the two relations it joins, with its"
                        + " condition after it: A ljoin A.id = B.a_id B")
                .at(1, 10);
        assertParseError("EXCEPT π a (R) S")
                .hasMessageContaining("'EXCEPT' goes between the two relations it combines");
        assertParseError("⋈ A B")
                .hasMessageContaining("'⋈' goes between the two relations it joins: A ⋈ B")
                .at(1, 1);
        assertParseError("∪ A B")
                .hasMessageContaining("'∪' goes between the two relations it combines: A ∪ B");
        assertParseError("× A B")
                .hasMessageContaining("'×' goes between its two inputs: A × B");
        // A unary operator's glyph after the word starts a relation as its keyword does.
        for (String unary : List.of("π a (R)", "μ tags (R)", "ω (R)", "∀ x (R)")) {
            assertParseError("union " + unary)
                    .hasMessageContaining("'union' goes between the two relations it combines")
                    .at(1, 1);
        }
    }

    @Test
    @DisplayName("a relation named like a binary operator still reads as a relation")
    public void relationNamedLikeInfixOperator() {
        assertThat(parse("diff ⋈ R")).isNode(NaturalJoinNode.class).left().isRelation("diff");
        assertThat(parse("union")).isRelation("union");
        assertThat(parse("R ∪ (minus)")).isNode(UnionNode.class).right().isRelation("minus");
        assertThat(parse("union union S")).isNode(UnionNode.class).left().isRelation("union");
    }

    @Test
    public void reportsMissingRightPredicateOperand() {
        assertParseError("σ age >")
                .hasMessageContaining("Expected attribute, string, number, boolean literal, function call, or '('")
                .at(1, 8);
    }

    @Test
    public void reportsMissingRightRelationOperand() {
        assertParseError("Users ⋈")
                .hasMessageContaining("Expected relation name")
                .at(1, 8);
    }

    @Test
    public void reportsJoinMissingItsCondition() {
        assertThatThrownBy(() -> parse("Customers ANTI Orders"))
                .hasMessage("'ANTI' needs a join condition between its two inputs, written after"
                        + " the operator: Customers ANTI <condition> Orders at line 1, column 11");
        assertParseError("Customers ANTI Orders")
                .at(1, 11)
                .found("'ANTI'");
    }

    @Test
    public void reportsEveryConditionedJoinMissingItsCondition() {
        for (String op : new String[] {"SEMI", "⋉", "▷", "LJOIN", "RJOIN", "FJOIN", "><",
                "|><", "><|", "|><|", "⨝", "ASOF"}) {
            assertParseError("A " + op + " B")
                    .hasMessageContaining("'" + op + "' needs a join condition")
                    .at(1, 3);
        }
    }

    @Test
    public void reportsJoinMissingItsConditionBeforeAnotherOperatorOrParen() {
        assertParseError("(A SEMI s.B) ∪ C")
                .hasMessageContaining("A SEMI <condition> s.B at line 1, column 4")
                .at(1, 4);
        assertParseError("(A ∪ B) SEMI C")
                .as("a left input with no single name is elided")
                .hasMessageContaining("… SEMI <condition> C")
                .at(1, 9);
        assertParseError("A ▷ B ∪ C")
                .hasMessageContaining("A ▷ <condition> B")
                .at(1, 3);
        assertParseError("A ASOF inner B")
                .hasMessageContaining("'ASOF' needs a join condition")
                .at(1, 3);
    }

    @Test
    public void conditionStartingWithANameStillParses() {
        assertThat(parse("A ▷ A.id = B.id B")).isNode(AntiJoinNode.class);
        assertParseError("σ x (A ▷ x(1) B)")
                .as("a name followed by '(' is a function call, so the condition is there")
                .hasMessageContaining("Expected comparison operator");
        assertParseError("A ⋉ x.")
                .as("a dot with no name after it is left to the condition's own error")
                .hasMessageContaining("Expected identifier after '.'");
    }

    @Test
    public void reportsNaturalJoinGivenACondition() {
        assertThatThrownBy(() -> parse("A ⋈ A.nope = A.id A"))
                .hasMessage("'⋈' is the natural join: it joins on the columns both inputs share"
                        + " and takes no condition; for an explicit condition use ⨝ (><)"
                        + " at line 1, column 3");
        assertParseError("A ⋈ A.nope = A.id A").at(1, 3);
        assertParseError("A JOIN x < 1 B")
                .hasMessageContaining("'JOIN' is the natural join")
                .at(1, 3);
    }

    @Test
    public void reportsConditionAfterNaturalJoinsRightInput() {
        String script = "Round3 JOIN Teams (Teams.team_id = home_id)";
        assertThatThrownBy(() -> parse(script))
                .hasMessage("'JOIN' is the natural join: it joins on the columns both inputs"
                        + " share and takes no condition; to join on a condition, use ⨝ (><)"
                        + " and write the condition between the inputs:"
                        + " Round3 >< Teams.team_id = home_id Teams at line 1, column 8");
        assertParseError(script).at(1, 8).found("'JOIN'");
        assertThat(parse("Round3 >< Teams.team_id = home_id Teams"))
                .as("the rewrite the message offers parses")
                .isNode(ThetaJoinNode.class);
        assertParseError("(A ∪ B) ⋈ s.B (x IN (1,2) AND NOT y LIKE 'a%')")
                .hasMessageContaining(": … ⨝ x IN (1, 2) AND NOT y LIKE 'a%' s.B")
                .at(1, 9);
        assertParseError("A JOIN B (x = y) ∪ C").hasMessageContaining(": A >< x = y B");
    }

    @Test
    public void reportsConditionAfterConditionedJoinsRightInput() {
        for (String op : new String[] {"LJOIN", "SEMI", "ANTI", "▷", "><", "|><|", "ASOF"}) {
            assertThatThrownBy(() -> parse("A " + op + " B (B.id <= A.id)"))
                    .hasMessage("'" + op + "' takes its join condition between its two inputs,"
                            + " not after them: A " + op + " B.id <= A.id B at line 1, column 3");
        }
        assertParseError("A ASOF INNER B (x = y)").hasMessageContaining(": A ASOF x = y B");
        assertParseError("σ z = 1 (A SEMI B (x ∉ (1, 2)))")
                .hasMessageContaining(": A SEMI x ∉ (1, 2) B")
                .at(1, 12);
        assertParseError("A ANTI B (x IS NULL) ∪ C").hasMessageContaining(": A ANTI x IS NULL B");
    }

    @Test
    @DisplayName("SQL's ON after a join's right input is reported at ON, with the join rewritten")
    public void reportsOnClause() {
        assertThatThrownBy(() -> parse("loans JOIN members ON loans.member_id = members.member_id"))
                .hasMessage("'JOIN' takes no condition; to join on one, use >< with the condition"
                        + " between the two relations, not after them with ON: write"
                        + " loans >< loans.member_id = members.member_id members at line 1, column 20");
        assertThatThrownBy(() -> parse("members LJOIN loans ON members.member_id = loans.member_id"))
                .hasMessage("'LJOIN' takes its condition between the two relations, not after"
                        + " them with ON: write members LJOIN members.member_id = loans.member_id"
                        + " loans at line 1, column 21");
        assertParseError("loans >< members ON loans.member_id = members.member_id")
                .hasMessageContaining(": write loans >< loans.member_id = members.member_id members")
                .at(1, 18);
        for (String op : List.of("RJOIN", "FJOIN", "SEMI", "ANTI", "⟕", "▷", "⨝", "ASOF")) {
            assertParseError("A " + op + " B on A.id = B.a_id")
                    .hasMessageContaining("'" + op + "' takes its condition between the two relations")
                    .hasMessageContaining(": write A " + op + " A.id = B.a_id B");
        }
        assertParseError("A ⋈ B ON x = y").hasMessageContaining(": write A ⨝ x = y B");
        assertParseError("A × B ON x = y").hasMessageContaining(": write A >< x = y B");
        assertParseError("(A ∪ C) LJOIN (σ z > 1 (B)) ON x = y AND p < q")
                .hasMessageContaining(": write (A ∪ C) LJOIN x = y AND p < q (σ z > 1 (B))");
        assertParseError("A JOIN B ON")
                .as("no condition after ON")
                .hasMessageContaining(": write A >< condition B")
                .at(1, 10);
        assertThat(parse("loans >< loans.member_id = members.member_id members"))
                .as("the rewrite the message offers parses")
                .isNode(ThetaJoinNode.class);
    }

    @Test
    @DisplayName("aggregates in parentheses after GROUP's keys are reported at '(', moved into the key list")
    public void reportsParenthesizedAggregates() {
        assertThatThrownBy(() -> parse("GROUP age_group (COUNT(*) -> n) (T)"))
                .hasMessage("aggregates follow the grouping keys in the same list, without parentheses:"
                        + " write GROUP age_group, COUNT(*) -> n (T) at line 1, column 17");
        assertParseError("GROUP age_group, seconds (COUNT(*) -> n, MIN(seconds) -> best) (T)")
                .hasMessageContaining(": write GROUP age_group, seconds, COUNT(*) -> n, MIN(seconds) -> best (T)")
                .at(1, 26);
        assertParseError("GROUP (COUNT(*) -> n) (T)")
                .hasMessageContaining(": write GROUP COUNT(*) -> n (T)")
                .at(1, 7);
        assertParseError("GROUP age_group, (COUNT(*) -> n) (T)")
                .hasMessageContaining(": write GROUP age_group, COUNT(*) -> n (T)")
                .at(1, 18);
        assertParseError("GROUP age_group (MEDIAN(seconds) -> m) (T)")
                .hasMessageContaining(": write GROUP age_group, MEDIAN(seconds) -> m (T)")
                .at(1, 17);
        assertParseError("GROUP age_group (COUNT(*) -> n (T)")
                .hasMessageContaining("aggregates follow the grouping keys in the same list, without parentheses")
                .at(1, 17);
        assertParseError("GROUP age_group (COUNT(*) -> n) (T")
                .hasMessageContaining(": write GROUP age_group, COUNT(*) -> n (…)")
                .at(1, 17);
        // A parenthesis not followed by an aggregate call keeps its message.
        for (String script : List.of("GROUP age_group (UPPER(name)) (T)",
                "GROUP age_group (median (seconds)) (T)")) {
            assertParseError(script).hasMessageContaining("Expected aggregate function").at(1, 17);
        }
        assertThat(parse("GROUP age_group, COUNT(*) -> n (T)")).isNode(AggregationNode.class);
    }

    @Test
    @DisplayName("WITH TIES after TOP's sort or PER keys is reported at WITH, moved after the count")
    public void reportsMisplacedTies() {
        assertThatThrownBy(() -> parse("TOP 2 seconds ASC WITH TIES PER age_group (T)"))
                .hasMessage("WITH TIES goes right after the count: write"
                        + " TOP 2 WITH TIES seconds ASC PER age_group (T) at line 1, column 19");
        assertParseError("TOP 2 seconds ASC PER age_group WITH TIES (T)")
                .hasMessageContaining(": write TOP 2 WITH TIES seconds ASC PER age_group (T)")
                .at(1, 33);
        assertParseError("TOP 1, 2 seconds DESC, name WITH TIES (T)")
                .hasMessageContaining(": write TOP 1, 2 WITH TIES seconds DESC, name (T)")
                .at(1, 29);
        assertParseError("TOP 2 seconds ASC WITH TIES (T")
                .hasMessageContaining(": write TOP 2 WITH TIES seconds ASC (…)")
                .at(1, 19);
        assertThat(parse("TOP 2 WITH TIES seconds ASC PER age_group (T)")).isNode(TopKNode.class);
    }

    @Test
    @DisplayName("RENAME's pairs written without parentheses are reported at the arrow, parenthesized")
    public void reportsUnparenthesizedRename() {
        assertThatThrownBy(() -> parse("RENAME age_group -> grp (T)"))
                .hasMessage("the columns to rename go in parentheses:"
                        + " write RENAME (age_group -> grp) (T) at line 1, column 18");
        assertParseError("RENAME age_group -> grp, name -> who (T)")
                .hasMessageContaining(": write RENAME (age_group -> grp, name -> who) (T)")
                .at(1, 18);
        assertParseError("ρ age_group → grp (T)")
                .hasMessageContaining(": write ρ (age_group → grp) (T)")
                .at(1, 13);
        assertParseError("RENAME age_group -> grp (T")
                .hasMessageContaining(": write RENAME (age_group -> grp) (…)")
                .at(1, 18);
        assertThatThrownBy(() -> parse("RENAME age_group -> grp, name (T)"))
                .hasMessage("the columns to rename go in parentheses at line 1, column 18");
        assertThat(parse("RENAME (age_group -> grp) (T)")).isNode(RenameNode.class);
    }

    @Test
    @DisplayName("SQL's ways of asking for distinct rows are reported naming DISTINCT (PROJECT …)")
    public void reportsSqlDistinct() {
        assertThatThrownBy(() -> parse("SELECT DISTINCT age_group, seconds (T)"))
                .hasMessage("SELECT filters rows; for distinct rows of some columns write"
                        + " DISTINCT (PROJECT age_group, seconds (T)) at line 1, column 8");
        assertParseError("SELECT DISTINCT * (T)")
                .hasMessageContaining("SELECT filters rows; for distinct rows write DISTINCT (T)")
                .at(1, 8);
        assertParseError("DISTINCT age_group, seconds (T)")
                .hasMessageContaining("DISTINCT takes a relation, not columns:"
                        + " write DISTINCT (PROJECT age_group, seconds (T))")
                .at(1, 10);
        assertParseError("δ age_group (T)")
                .hasMessageContaining("δ takes a relation, not columns: write δ (π age_group (T))")
                .at(1, 3);
        assertParseError("PROJECT DISTINCT age_group, seconds (T)")
                .hasMessageContaining("PROJECT takes no DISTINCT; for distinct rows of some columns write"
                        + " DISTINCT (PROJECT age_group, seconds (T))")
                .at(1, 9);
        assertParseError("GROUP age_group, seconds (T)")
                .hasMessageContaining("GROUP needs an aggregate after its keys;"
                        + " for distinct rows write DISTINCT (PROJECT age_group, seconds (T))")
                .at(1, 26);
        assertParseError("GROUP BY age_group, seconds -> n (T)")
                .hasMessageContaining(" write DISTINCT (PROJECT age_group, seconds -> n (T))")
                .at(1, 34);
        assertParseError("DISTINCT date, seconds (T)")
                .hasMessageContaining(": write DISTINCT (PROJECT date, seconds (T))")
                .at(1, 10);
        // Without an input to rewrite, or with no columns, the plain message.
        assertParseError("DISTINCT age_group").hasMessageContaining("Expected '(' after 'DISTINCT'");
        assertParseError("DISTINCT count (T)").hasMessageContaining("Expected '(' after 'DISTINCT'");
        assertParseError("DISTINCT * 2").hasMessageContaining("Expected '(' after 'DISTINCT'");
        assertParseError("GROUP age_group (T").hasMessageContaining("Expected aggregate function").at(1, 17);
        assertParseError("GROUP (T)").hasMessageContaining("Expected aggregate function");
        assertParseError("GROUP ROLLUP (age_group) (T)").hasMessageContaining("Expected aggregate function");
        assertThat(parse("DISTINCT (PROJECT age_group, seconds (T))")).isNode(DistinctNode.class);
        // A column named distinct still reads as one.
        assertThat(parse("SELECT distinct = 1 (T)")).isNode(SelectionNode.class);
        assertThat(parse("PROJECT distinct, a (T)")).isNode(ProjectionNode.class);
    }

    @Test
    @DisplayName("COUNT(*) and * outside GROUP, an aggregate in SELECT and IN (SELECT …) name the fix (#188)")
    public void reportsStarAggregatesAndSubqueries() {
        assertParseError("PROJECT region, COUNT(*) -> n (T)")
                .hasMessageContaining("COUNT(*) is an aggregate and belongs in GROUP:"
                        + " write GROUP region, COUNT(*) -> n (T)")
                .at(1, 23);
        assertParseError("PROJECT COUNT(*) (T)")
                .hasMessageContaining(": write GROUP COUNT(*) -> n (T)");
        assertParseError("π region, COUNT(*) + 1 → m (T)")
                .hasMessageContaining("COUNT(*) is an aggregate and belongs in γ:"
                        + " write π region, n + 1 → m (γ region, COUNT(*) → n (T))");
        assertParseError("SELECT COUNT(*) > 5 (T)")
                .hasMessageContaining(": write SELECT n > 5 (GROUP COUNT(*) -> n (T))")
                .at(1, 14);
        // A column computed outside the aggregate would need to be a key: the plain form.
        assertParseError("PROJECT UPPER(region), COUNT(*) -> n (T)")
                .hasMessageContaining(": write GROUP COUNT(*) -> n (T)");
        // Outside an item list or a condition, and * given to another aggregate.
        assertParseError("T JOIN COUNT(*) = 1 S")
                .hasMessageContaining("COUNT(*) is an aggregate and belongs in GROUP");
        assertParseError("PROJECT SUM(*) -> s (T)")
                .hasMessageContaining("SUM needs a column, and only COUNT takes *: write SUM(column),"
                        + " or COUNT(*) to count rows")
                .at(1, 13);
        assertParseError("γ region, AVG(*) → a (T)")
                .hasMessageContaining(": write γ region, AVG(column) → a (T), naming the column")
                .at(1, 15);
        assertParseError("SUM(total) (T)")
                .hasMessageContaining("SUM(total) is an aggregate and belongs in GROUP:"
                        + " write GROUP SUM(total) -> s (T)")
                .at(1, 1);
        assertParseError("σ * (T)")
                .hasMessageContaining("a relation already has all its columns: write T, or σ a condition (T)")
                .at(1, 3);
        assertParseError("SELECT MIN(a) -> m, region (T)")
                .hasMessageContaining("'SELECT' filters rows; an aggregate belongs in GROUP:"
                        + " write GROUP region, MIN(a) -> m (T)")
                .at(1, 15);
        // Not an aggregate: the PROJECT advice stands.
        assertParseError("SELECT UPPER(a) -> m (T)")
                .hasMessageContaining("To choose or compute columns, use PROJECT");
        assertParseError("SELECT a -> b (T)")
                .hasMessageContaining("To choose or compute columns, use PROJECT");
        // The subquery anywhere but a selection's whole condition over a name: the general join.
        assertParseError("SELECT a = 1 AND b IN (SELECT b FROM S) (T)")
                .hasMessageContaining("to keep rows with a match in another relation write R SEMI R.x = S.y S")
                .at(1, 24);
        assertParseError("SELECT b ∉ (SELECT b FROM S) (T)")
                .hasMessageContaining("with no match in another relation write T ANTI T.b = S.b S");
        assertParseError("SELECT T.b IN (σ c > 1 (S)) (T)")
                .hasMessageContaining("write R SEMI R.x = S.y S");
        // What still reads.
        assertThat(parse("SELECT b IN {1, 2} (T)")).isNode(SelectionNode.class);
        assertThat(parse("GROUP region, COUNT(*) -> n (T)")).isNode(AggregationNode.class);
        assertThat(parse("PROJECT a * 2 -> b (T)")).isNode(ProjectionNode.class);
    }

    @Test
    @DisplayName("an aggregate rewrite names each call once, and only its own operator's (#188)")
    public void rewritesOnlyItsOwnAggregates() {
        // An aggregate inside another is the outer one's argument; a named aggregate counts.
        assertParseError("PROJECT SUM(MAX(a)) + COUNT(*) -> x (T)")
                .hasMessageContaining(": write PROJECT s + n -> x (GROUP SUM(MAX(a)) -> s, COUNT(*) -> n (T))");
        assertParseError("PROJECT COUNT(*)+1 -> m (T)")
                .hasMessageContaining(": write PROJECT n+1 -> m (GROUP COUNT(*) -> n (T))");
        assertParseError("PROJECT MEDIAN(a) -> m, COUNT(*) (T)")
                .hasMessageContaining(": write GROUP MEDIAN(a) -> m, COUNT(*) -> n (T)");
        assertParseError("PROJECT 2 * COUNT(*), COUNT(*) -> n (T)")
                .hasMessageContaining(": write PROJECT 2 * n2, n (GROUP COUNT(*) -> n2, COUNT(*) -> n (T))")
                .at(1, 19);
        // Aggregates in the input, or around the operator, are not this operator's.
        assertParseError("PROJECT COUNT(*) -> n (SELECT SUM(x) > 1 (T))")
                .hasMessageContaining(": write GROUP COUNT(*) -> n (SELECT SUM(x) > 1 (T))");
        assertParseError("SELECT COUNT(*) > 1 (PROJECT COUNT(*) -> n (T))")
                .hasMessageContaining(": write GROUP COUNT(*) -> n (T)")
                .at(1, 36);
        assertParseError("PROJECT COUNT(*\n (T)").hasMessageContaining("Expected ')' after COUNT(*");
        // SQL's FROM names the input.
        assertParseError("SELECT * FROM T").hasMessageContaining("SQL's FROM is the input: write T");
    }

    @Test
    @DisplayName("IN { SELECT … } is reported as IN (SELECT …) is, at the inner SELECT (#197)")
    public void reportsBracedSubquery() {
        assertParseError("SELECT room_id IN { SELECT room_id (Rooms) } (devices)")
                .hasMessageContaining("IN takes a set such as {1, 2}; to keep rows with a match in another"
                        + " relation write devices SEMI devices.room_id = Rooms.room_id Rooms")
                .at(1, 21);
        assertParseError("SELECT room_id NOT IN { SELECT room_id FROM Rooms } (devices)")
                .hasMessageContaining("with no match in another relation write"
                        + " devices ANTI devices.room_id = Rooms.room_id Rooms")
                .at(1, 25);
        assertParseError("SELECT a = 1 AND b ∈ { SELECT b (S) } (T)")
                .hasMessageContaining("write R SEMI R.x = S.y S")
                .at(1, 24);
        assertThat(parse("SELECT b IN {1, 2} (T)")).isNode(SelectionNode.class);
    }

    @Test
    @DisplayName("IN (SELECT …) writes the semi-join out only over plain names (#188)")
    public void subqueryJoinOverPlainNames() {
        String general = "write R SEMI R.x = S.y S";
        for (String script : List.of(
                "SELECT b IN (SELECT b FROM S) (DISTINCT (T))",
                "SELECT b IN (SELECT b (DISTINCT (S))) (T)",
                "SELECT b + 1 IN (SELECT b FROM S) (T)",
                "SELECT b IN (SELECT b + 1 FROM S) (T)",
                "SELECT b IN (SELECT b FROM S) AND a = 1 (T)",
                "T >< T.b IN (SELECT b FROM S) S")) {
            assertParseError(script).as(script).hasMessageContaining(general);
        }
        assertParseError("SELECT T.b IN (SELECT S.b FROM S) (ns.T)")
                .hasMessageContaining("write ns.T SEMI T.b = S.b S");
    }

    @Test
    @DisplayName("a join's right input inside its condition's parentheses is reported at the name, moved after them")
    public void reportsRightInputInCondition() {
        assertThatThrownBy(() -> parse("T >< (age_group = M.age_group M)"))
                .hasMessage("the right relation goes after the condition, outside its parentheses:"
                        + " write T >< (age_group = M.age_group) M at line 1, column 31");
        assertParseError("(T >< (T.a = M.a) M) >< (T.age_group = M.age_group M)")
                .hasMessageContaining(": write (T >< (T.a = M.a) M) >< (T.age_group = M.age_group) M")
                .at(1, 52);
        assertParseError("T LJOIN (T.age_group = M.age_group M)")
                .hasMessageContaining(": write T LJOIN (T.age_group = M.age_group) M")
                .at(1, 36);
        assertParseError("σ s = a OR s = b ((F >< (g = A.g) A) >< (g = B.g B))")
                .hasMessageContaining(": write (F >< (g = A.g) A) >< (g = B.g) B")
                .at(1, 50);
        // A name inside a nested parenthesis is not the right input.
        assertParseError("T >< ((g = M.g M)) M").hasMessageContaining("Expected ')' after predicate");
        assertThat(parse("T >< (age_group = M.age_group) M")).isNode(ThetaJoinNode.class);
    }

    @Test
    @DisplayName("a join's right input written before its condition is reported at the name, moved after it")
    public void reportsRightInputBeforeCondition() {
        assertThatThrownBy(() -> parse("customers LJOIN orders customer_id = customer_id"))
                .hasMessage("the right relation goes after the condition:"
                        + " write customers LJOIN customer_id = customer_id orders at line 1, column 17");
        assertParseError("customers LJOIN orders customers.customer_id = orders.customer_id")
                .hasMessageContaining(": write customers LJOIN customers.customer_id = orders.customer_id orders")
                .at(1, 17);
        assertParseError("customers LJOIN orders customers.customer_id = orders.customer_id orders")
                .hasMessageContaining(": write customers LJOIN customers.customer_id = orders.customer_id orders")
                .at(1, 17);
        assertParseError("customers >< orders customers.customer_id = orders.customer_id")
                .hasMessageContaining(": write customers >< customers.customer_id = orders.customer_id orders")
                .at(1, 14);
        assertParseError("customers SEMI orders customers.customer_id = orders.customer_id")
                .hasMessageContaining(": write customers SEMI customers.customer_id = orders.customer_id orders")
                .at(1, 16);
        assertParseError("customers ANTI orders customer_id = customer_id")
                .hasMessageContaining(": write customers ANTI customer_id = customer_id orders")
                .at(1, 16);
        assertThat(parse("customers LJOIN customers.customer_id = orders.customer_id orders"))
                .isNode(LeftOuterJoinNode.class);
        assertThat(parse("T >< a BETWEEN 1 AND 2 M")).isNode(ThetaJoinNode.class);
        // Two names with nothing compared after them are not taken for a misplaced relation.
        assertParseError("customers LJOIN orders customer_id").hasMessageContaining("Expected comparison operator");
    }

    @Test
    @DisplayName("SQL's AND or WHERE before a condition written after the right relation is dropped from the rewrite (#227)")
    public void reportsRightInputBeforeAndOrWhere() {
        String rewrite = "SC1 >< SC1.issue_id = SC2.issue_id AND SC2.changed_at < SC1.changed_at SC2";
        assertParseError("GROUP SC1.issue_id, MAX(SC2.changed_at) -> prev"
                + " (SC1 >< SC2 AND SC1.issue_id = SC2.issue_id AND SC2.changed_at < SC1.changed_at)")
                .hasMessageContaining("the right relation goes after the condition: write " + rewrite)
                .at(1, 57);
        parse(rewrite);
        assertParseError("A >< B WHERE A.id = B.bid")
                .hasMessageContaining(": write A >< A.id = B.bid B")
                .at(1, 6);
        for (String op : List.of("><", "LJOIN", "RJOIN", "FJOIN", "SEMI", "ANTI")) {
            assertParseError("A " + op + " B AND A.id = B.bid").as(op)
                    .hasMessageContaining(": write A " + op + " A.id = B.bid B");
            parse("A " + op + " A.id = B.bid B");
        }
    }

    @Test
    @DisplayName("a backslash before a string's quotes says strings need none, naming the string (#229)")
    public void reportsBackslashBeforeQuotes() {
        assertParseError("PROJECT id, \\\"optimistic\\\" -> scenario (R)")
                .hasMessageContaining("Strings need no backslash before their quotes:"
                        + " write \"optimistic\" (or 'optimistic')")
                .at(1, 13);
        assertParseError("PROJECT id, \\'optimistic\\' -> scenario (R)")
                .hasMessageContaining("write 'optimistic' (or \"optimistic\")");
        parse("PROJECT id, \"optimistic\" -> scenario (R)");
        parse("PROJECT id, 'optimistic' -> scenario (R)");
        // No closing quote on the line: the slip, without a string to name.
        assertParseError("PROJECT id, \\\"optimistic -> scenario (R)")
                .hasMessageContaining("Strings need no backslash before their quotes at line 1");
        // A backslash before anything else stays an unexpected character.
        assertParseError("PROJECT id \\ (R)").hasMessageContaining("Unexpected character");
        // In a string a backslash escapes, as before.
        parse("PROJECT \"a\\\"b\" -> s (R)");
        // The line ends before the string closes, or the input before a quote: no string to name.
        assertParseError("PROJECT id, \\\"optimistic\n\" -> s (R)")
                .hasMessageContaining("Strings need no backslash before their quotes at line 1");
        assertParseError("PROJECT id \\").hasMessageContaining("Unexpected character");
        // A string holding the other quote is named in its own quotes only.
        assertParseError("PROJECT \\\"it's\\\" -> s (R)")
                .hasMessageContaining("Strings need no backslash before their quotes at line 1");
    }

    @Test
    @DisplayName("SELECT TRUE (R) says a condition compares something and names R, never PROJECT (#229)")
    public void reportsBooleanLiteralCondition() {
        assertParseError("PROJECT 201 -> room_id, 0 -> depth (SELECT true (rooms))")
                .hasMessageContaining("'SELECT' (σ) needs a condition that compares something, and true"
                        + " alone does not. Every row of the input is the input itself: write rooms")
                .at(1, 44);
        parse("PROJECT 201 -> room_id, 0 -> depth (rooms)");
        assertParseError("σ TRUE (σ v > 1 (R))")
                .hasMessageContaining("'σ' needs a condition that compares something, and TRUE alone"
                        + " does not. Every row of the input is the input itself: write σ v > 1 (R)");
        assertParseError("SELECT false (R)")
                .hasMessageContaining("and false alone does not. To keep no row, write SELECT 1 = 0 (R)");
        parse("SELECT 1 = 0 (R)");
        // A column is still a column list, and names PROJECT.
        assertParseError("SELECT active (R)").hasMessageContaining("write PROJECT active (R)");
    }

    @Test
    @DisplayName("SQL's WHERE inside an operator's input names the SELECT that filters it (#230)")
    public void reportsWhereInInput() {
        String rewrite = "PROJECT id (SELECT kind = 'a' (R))";
        assertParseError("PROJECT id (R WHERE kind = 'a')")
                .hasMessageContaining("Relix has no WHERE: filter the input with SELECT: write " + rewrite)
                .at(1, 15);
        assertParseError("SELECT id (R WHERE kind = 'a')")
                .hasMessageContaining("Relix has no WHERE: filter the input with SELECT: write " + rewrite)
                .at(1, 14);
        parse(rewrite);
        assertParseError("π id (R WHERE kind = 'a')").hasMessageContaining("write π id (σ kind = 'a' (R))");
        parse("π id (σ kind = 'a' (R))");
        assertParseError("GROUP COUNT(*) -> n (R WHERE kind = 'a')")
                .hasMessageContaining("write GROUP COUNT(*) -> n (SELECT kind = 'a' (R))");
        parse("GROUP COUNT(*) -> n (SELECT kind = 'a' (R))");
        // A condition the parenthesis does not close after: SELECT is named without a rewrite.
        assertThatThrownBy(() -> parse("PROJECT id (R WHERE kind = 'a' x)"))
                .hasMessage("Relix has no WHERE: filter the input with SELECT at line 1, column 15");
        // A condition that does not parse: SELECT is named without a rewrite.
        assertThatThrownBy(() -> parse("PROJECT id (R WHERE kind)"))
                .hasMessage("Relix has no WHERE: filter the input with SELECT at line 1, column 15");
    }

    @Test
    @DisplayName("ON after an operator's right input gets the ON message, as after a name (#230)")
    public void reportsOnAfterRenamedRelation() {
        for (String op : List.of("><", "LJOIN")) {
            String rewrite = "RENAME a (R) " + op + " a.id = b.id RENAME b (R)";
            assertParseError("RENAME a (R) " + op + " RENAME b (R) ON a.id = b.id").as(op)
                    .hasMessageContaining("'" + op + "' takes its condition between the two relations,"
                            + " not after them with ON: write " + rewrite);
            parse(rewrite);
        }
        assertParseError("A >< SELECT x = 1 (B) ON A.id = B.id")
                .hasMessageContaining("write A >< A.id = B.id SELECT x = 1 (B)");
        parse("A >< A.id = B.id SELECT x = 1 (B)");
        assertParseError("A >< RENAME b ((R)) ON a.id = b.id").hasMessageContaining("write A >< a.id = b.id RENAME b ((R))");
        assertParseError("A >< SELECT x = 1 (B UNION C) ON A.id = B.id")
                .hasMessageContaining("write A >< A.id = B.id SELECT x = 1 (B UNION C)");
        // No ON after the operator: the error it had before.
        for (String script : List.of("A >< RENAME b (R)", "A >< RENAME b (R) x", "A >< SELECT x = 1 (B) UNION C",
                "(A >< RENAME b (R))")) {
            assertThatThrownBy(() -> parse(script)).as(script).hasMessageNotContaining("not after them with ON");
        }
    }

    @Test
    @DisplayName("a qualified name after -> or in TOP's PER keys is written unqualified (#230)")
    public void reportsQualifiedNewNames() {
        assertParseError("PROJECT id -> R.ident (R)")
                .hasMessageContaining("The name after -> is a new column's, and takes no relation:"
                        + " write id -> ident")
                .at(1, 16);
        parse("PROJECT id -> ident (R)");
        assertParseError("PROJECT id -> a.b.c (R)").hasMessageContaining("write id -> c");
        assertParseError("PROJECT id -> a.b. (R)").hasMessageContaining("write id -> b");
        assertThatThrownBy(() -> parse("PROJECT id -> a. (R)")).hasMessageNotContaining("new column");
        assertParseError("π id, v + 1 → R.w (R)").hasMessageContaining("The name after → is a new column's,"
                + " and takes no relation: write v + 1 → w");
        String top = "TOP 1 WITH TIES R.at DESC PER grp (R)";
        assertParseError("TOP 1 WITH TIES R.at DESC PER R.grp (R)")
                .hasMessageContaining("TOP's PER keys are its input's columns, written without a relation:"
                        + " write " + top)
                .at(1, 32);
        parse(top);
        assertParseError("TOP 1 x DESC PER R.g, S.h (R)").hasMessageContaining("write TOP 1 x DESC PER g, h (R)");
        assertThatThrownBy(() -> parse("TOP 1 x DESC PER R. (R)")).hasMessageNotContaining("PER keys");
        assertThatThrownBy(() -> parse("TOP 1 x DESC PER R.g")).hasMessage(
                "TOP's PER keys are its input's columns, written without a relation at line 1, column 19");
    }

    @Test
    @DisplayName("an alias after a LATERAL call is dropped: its columns keep their own names (#230)")
    public void reportsLateralAlias() {
        for (String alias : List.of(" H", " AS H")) {
            assertParseError("S LATERAL hot(S.grp)" + alias).as(alias)
                    .hasMessageContaining("Relix has no table aliases: LATERAL adds hot's columns under"
                            + " their own names, so name them unqualified: write S LATERAL hot(S.grp)")
                    .at(1, 22);
        }
        assertParseError("S LATERAL hot(S.grp) H WHERE H.x = 1")
                .hasMessageContaining("write S LATERAL hot(S.grp)");
        assertParseError("R >< R.id = H.id (S LATERAL hot(S.grp) H)")
                .hasMessageContaining("write S LATERAL hot(S.grp)");
        parse("S LATERAL hot(S.grp)");
        // Not an alias: AS without a name, SQL's words, or a name that does not end the relation.
        for (String script : List.of("S LATERAL hot(S.grp) AS", "S LATERAL hot(S.grp) WHERE x = 1",
                "S LATERAL hot(S.grp) H K")) {
            assertThatThrownBy(() -> parse(script)).as(script).hasMessageNotContaining("table aliases");
        }
    }

    @Test
    @DisplayName("SQL's OVER in a projection names ROLLING or WINDOW under the projection (#230)")
    public void reportsOverInProjection() {
        String rolling = "PROJECT running (ROLLING SUM(v) OVER ALL ROWS SORT at ASC PER grp AS running (R))";
        assertParseError("PROJECT SUM(v) OVER (SORT at ASC PER grp) -> running (R)")
                .hasMessageContaining("A running or sliding aggregate is ROLLING, its own operator: write "
                        + rolling)
                .at(1, 16);
        parse(rolling);
        String sql = "PROJECT id, running, kind (ROLLING SUM(v) OVER ALL ROWS SORT at PER grp AS running (R))";
        assertParseError("PROJECT id, SUM(v) OVER (PARTITION BY grp ORDER BY at) AS running, kind (R)")
                .hasMessageContaining("write " + sql);
        parse(sql);
        String total = "PROJECT id, total (ROLLING SUM(v) OVER PARTITION PER grp AS total (R))";
        assertParseError("PROJECT id, SUM(v) OVER (PER grp) -> total (R)").hasMessageContaining("write " + total);
        parse(total);
        String window = "PROJECT id, prev (WINDOW LAG(v) SORT at ASC PER grp AS prev (R))";
        assertParseError("PROJECT id, LAG(v) OVER (SORT at ASC PER grp) -> prev (R)")
                .hasMessageContaining("A ranking or offset function is WINDOW, its own operator: write " + window);
        parse(window);
        assertParseError("PROJECT SUM(v) OVER (SORT at) (R)")
                .hasMessageContaining("write PROJECT running (ROLLING SUM(v) OVER ALL ROWS SORT at AS running (R))");
        parse("PROJECT running (ROLLING SUM(v) OVER ALL ROWS SORT at AS running (R))");
        assertThatThrownBy(() -> parse("PROJECT LAG(v) OVER (PER grp) -> p (R)"))
                .hasMessage("A ranking or offset function is WINDOW, its own operator at line 1, column 16");
        for (String window2 : List.of("ORDER at", "PARTITION grp")) {
            assertThatThrownBy(() -> parse("PROJECT SUM(v) OVER (" + window2 + ") -> s (R)")).as(window2)
                    .hasMessage("A running or sliding aggregate is ROLLING, its own operator at line 1, column 16");
        }
        // A window that does not read: the operators are named without a rewrite.
        assertThatThrownBy(() -> parse("PROJECT Len(s) OVER (SORT at) -> n (R)"))
                .hasMessage("Relix has no OVER in a projection: a running or sliding aggregate is ROLLING,"
                        + " a ranking or offset function WINDOW at line 1, column 16");
    }

    @Test
    @DisplayName("WINDOW's name written before its ordering is shown in WINDOW's order (#230)")
    public void reportsEarlyWindowName() {
        String rewrite = "WINDOW LAG(v) SORT at ASC PER grp AS prev (R)";
        assertParseError("WINDOW LAG(v) AS prev SORT at ASC PER grp (R)")
                .hasMessageContaining("WINDOW names its column last, after SORT and PER: write " + rewrite)
                .at(1, 15);
        parse(rewrite);
        assertThatThrownBy(() -> parse("WINDOW LAG(v) AS prev (R)"))
                .hasMessage("WINDOW names its column last, after SORT and PER at line 1, column 15");
        assertParseError("WINDOW LAG(v) AS (R)").hasMessageContaining("Expected 'SORT'");
    }

    @Test
    @DisplayName("SQL's CASE names IIf or Switch, written with the CASE's own conditions and values (#230)")
    public void reportsCase() {
        assertParseError("PROJECT id, CASE WHEN v > 1 THEN 'big' ELSE 'small' END -> size (R)")
                .hasMessageContaining("Relix has no CASE: write IIf(v > 1, 'big', 'small')")
                .at(1, 13);
        parse("PROJECT id, IIf(v > 1, 'big', 'small') -> size (R)");
        String many = "Switch(v > 1, 'big', v > 0, 'mid', 'small')";
        assertParseError("PROJECT id, CASE WHEN v > 1 THEN 'big' WHEN v > 0 THEN 'mid' ELSE 'small' END -> size (R)")
                .hasMessageContaining("Relix has no CASE: write " + many);
        parse("PROJECT id, " + many + " -> size (R)");
        assertParseError("PROJECT id, CASE WHEN v > 1 THEN 'big' END -> size (R)")
                .hasMessageContaining("Relix has no CASE: write Switch(v > 1, 'big')");
        parse("PROJECT id, Switch(v > 1, 'big') -> size (R)");
        assertParseError("PROJECT CASE WHEN v > 1 'big' END -> size (R)")
                .hasMessageContaining("Relix has no CASE: write IIf(condition, a, b)");
        String simple = "Switch(kind = 'a', 1, kind = 'b', 2)";
        assertParseError("PROJECT id, CASE kind WHEN 'a' THEN 1 WHEN 'b' THEN 2 END -> k (R)")
                .hasMessageContaining("Relix has no CASE: write " + simple);
        parse("PROJECT id, " + simple + " -> k (R)");
        assertParseError("SELECT CASE WHEN v > 1 THEN 1 ELSE 0 END = 1 (R)")
                .hasMessageContaining("Relix has no CASE: write IIf(v > 1, 1, 0)");
        parse("SELECT IIf(v > 1, 1, 0) = 1 (R)");
        // A CASE with no END: both functions are named.
        assertParseError("PROJECT CASE WHEN v > 1 THEN 1 -> k (R)")
                .hasMessageContaining("Relix has no CASE: write IIf(condition, a, b), or Switch(");
        // A column named case is still a column.
        parse("PROJECT case (R)");
    }

    @Test
    @DisplayName("a join that ends after its condition is reported where it ends, naming the right relation (#199)")
    public void reportsMissingRightInput() {
        assertThatThrownBy(() -> parse("SELECT home_members.home_id = 104"
                + " (home_members >< home_members.user_id = users.user_id)"))
                .hasMessage("a join takes its right relation after the condition: write home_members ><"
                        + " home_members.user_id = users.user_id users at line 1, column 88");
        assertParseError("customers LJOIN customers.id = orders.customer_id")
                .hasMessageContaining(": write customers LJOIN customers.id = orders.customer_id orders")
                .at(1, 50);
        assertParseError("customers ANTI orders.customer_id = customers.id AND orders.total > 1")
                .hasMessageContaining(": write customers ANTI orders.customer_id = customers.id"
                        + " AND orders.total > 1 orders");
        assertParseError("ρ c (customers) ⋉ c.id = ns.orders.customer_id")
                .hasMessageContaining(": write ρ c (customers) ⋉ c.id = ns.orders.customer_id ns.orders");
        for (String op : List.of("><", "⨝", "RJOIN", "FJOIN", "SEMI", "ASOF")) {
            assertParseError("T " + op + " T.a = S.a").as(op)
                    .hasMessageContaining(": write T " + op + " T.a = S.a S");
        }
        // No relation, or more than one, named apart from the left input: the general form.
        assertParseError("T >< a = b")
                .hasMessageContaining("a join takes its right relation after the condition: write A >< cond B");
        assertParseError("T >< T.a = S.a AND T.b = U.b")
                .hasMessageContaining(": write A >< cond B");
        assertParseError("T >< T.a = S.a }")
                .hasMessageContaining(": write T >< T.a = S.a S")
                .at(1, 16);
        // What still reads.
        assertThat(parse("SELECT a = 1 (T >< T.a = S.a S)")).isNode(SelectionNode.class);
    }

    @Test
    @DisplayName("SQL's two-word joins are reported at the first word, naming the Relix operator")
    public void reportsSqlJoinKeywords() {
        assertThatThrownBy(() -> parse("members LEFT JOIN loans ON members.member_id = loans.member_id"))
                .hasMessage("'LEFT JOIN' is SQL's spelling of LJOIN, which takes its condition"
                        + " between the two relations, not after them with ON: write members LJOIN"
                        + " members.member_id = loans.member_id loans at line 1, column 9");
        assertParseError("loans INNER JOIN members ON loans.member_id = members.member_id")
                .hasMessageContaining("'INNER JOIN' is SQL's spelling of ><, which takes its"
                        + " condition between the two relations, not after them with ON: write"
                        + " loans >< loans.member_id = members.member_id members")
                .at(1, 7);
        assertParseError("A right outer join B ON x = y")
                .hasMessageContaining("'right outer join' is SQL's spelling of RJOIN")
                .hasMessageContaining(": write A RJOIN x = y B")
                .at(1, 3);
        assertParseError("A FULL OUTER JOIN B ON x = y").hasMessageContaining(": write A FJOIN x = y B");
        assertParseError("A FULL JOIN B ON x = y").hasMessageContaining(": write A FJOIN x = y B");
        assertParseError("A CROSS JOIN B")
                .hasMessageContaining("'CROSS JOIN' is SQL's spelling of ×: write A × B")
                .at(1, 3);
        assertParseError("A NATURAL JOIN B").hasMessageContaining(": write A JOIN B, or A >< condition B");
        assertParseError("A INNER JOIN B").hasMessageContaining(": write A JOIN B, or A >< condition B");
        assertParseError("A LEFT JOIN B").hasMessageContaining(": write A LJOIN condition B");
        assertParseError("A LEFT JOIN B USING (id)")
                .hasMessageContaining("'LEFT JOIN' is SQL's spelling of LJOIN: write A LJOIN B USING (id)");
        assertParseError("A INNER JOIN B USING (id, k)").hasMessageContaining(": write A JOIN B USING (id, k)");
        assertParseError("A ∪ B LEFT JOIN C ON x = y").hasMessageContaining(": write B LJOIN x = y C");
        assertParseError("A LEFT JOIN").hasMessageContaining(": write A LJOIN condition B").at(1, 3);
        assertThat(parse("A LJOIN B USING (id)")).as("the USING rewrite parses").isNode(UsingJoinNode.class);
        assertThat(parse("left ⋈ B")).as("a relation named left").isNode(NaturalJoinNode.class);
    }

    @Test
    public void callOnAJoinsRightStillParses() {
        assertThat(parse("A JOIN f(1)")).isNode(NaturalJoinNode.class);
        assertThat(parse("A JOIN B (x)")).as("a call with no condition in it").isNode(NaturalJoinNode.class);
        assertThat(parse("A JOIN f(g(x = 1))")).as("a condition inside a nested argument").isNode(NaturalJoinNode.class);
        assertThat(parse("A >< f(x) = 1 B")).as("a condition starting with a call").isNode(ThetaJoinNode.class);
        assertThat(parse("A >< IIf(x > 1, 1, 0) = y B"))
                .as("a condition starting with a call whose argument compares")
                .isNode(ThetaJoinNode.class);
        assertThat(parse("A SEMI IIf(x > 1, 1, 0) = y B ∪ C"))
                .as("the same, with an operator after the right input")
                .isNode(UnionNode.class);
        assertParseError("A JOIN B (x = y")
                .as("an unclosed group is left to the call's own error")
                .hasMessageContaining("Expected ')' after function call arguments");
    }

    @Test
    @DisplayName("SQL's select list after SELECT is rewritten as PROJECT, with AS, FROM, WHERE and * (#213)")
    public void reportsSqlSelectList() {
        assertParseError("SELECT a AS x, b FROM T")
                .hasMessageContaining("'SELECT' (σ) filters rows by a condition, and found 'AS' where its"
                        + " comparison belongs. To choose or compute columns, use PROJECT (π):"
                        + " write PROJECT a -> x, b (T)")
                .at(1, 10);
        assertParseError("SELECT room_a -> src, room_b -> dst, meters (room_links)")
                .hasMessageContaining("write PROJECT room_a -> src, room_b -> dst, meters (room_links)")
                .at(1, 15);
        assertParseError("SELECT home_id, name (homes)")
                .hasMessageContaining("found ',' where its comparison belongs. To choose or compute"
                        + " columns, use PROJECT (π): write PROJECT home_id, name (homes)")
                .at(1, 15);
        assertParseError("SELECT from_issue AS src, to_issue AS dst (SELECT kind = 'blocks' (issue_links))")
                .hasMessageContaining("write PROJECT from_issue -> src, to_issue -> dst"
                        + " (SELECT kind = 'blocks' (issue_links))");
        assertParseError("SELECT a FROM T WHERE a > 1")
                .hasMessageContaining("found 'FROM' where its comparison belongs. To choose or compute"
                        + " columns, use PROJECT (π): write PROJECT a (SELECT a > 1 (T))")
                .at(1, 10);
        assertParseError("σ a AS x FROM T")
                .hasMessageContaining("'σ' filters rows by a condition, and found 'AS' where its"
                        + " comparison belongs. To choose or compute columns, use PROJECT (π): write π a → x (T)");
        // An input that does not parse is echoed as written, for its own error next.
        assertParseError("SELECT P.total AS prev_total, O.order_id FROM orders P JOIN orders O ON P.id = O.id")
                .hasMessageContaining("write PROJECT P.total -> prev_total, O.order_id"
                        + " (orders P JOIN orders O ON P.id = O.id)");
        // An aggregate among the items is GROUP's.
        assertParseError("SELECT region, COUNT(*) -> n FROM T")
                .hasMessageContaining("'SELECT' filters rows; an aggregate belongs in GROUP:"
                        + " write GROUP region, COUNT(*) -> n (T)");
        // SELECT * FROM is the input, filtered by a WHERE.
        assertParseError("SELECT * FROM components WHERE 0 = 1")
                .hasMessageContaining("Relix has no '*' column list, and a relation already has all its"
                        + " columns; SQL's FROM is the input: write SELECT 0 = 1 (components)")
                .at(1, 8);
        assertParseError("PROJECT n (SELECT a AS x FROM orders o JOIN (p) q WHERE b IN {1, 2})")
                .hasMessageContaining("write PROJECT a -> x (orders o JOIN (p) q WHERE b IN {1, 2})")
                .at(1, 21);
        // AS with no name after it, or after more than one operand, is not a select list.
        assertParseError("SELECT a AS (T)").hasMessageContaining("Expected comparison operator");
        assertParseError("SELECT a = 1 AND b AS x (T)").hasMessageContaining("Expected comparison operator");
        // A condition that goes on past its first operand keeps the general advice.
        assertParseError("SELECT a = 1 AND b, c (T)")
                .hasMessageContaining("To choose or compute columns, use PROJECT (π): PROJECT a, b * 2 -> c (R)");
        assertThat(parse("PROJECT a -> x, b (SELECT a > 1 (T))")).isNode(ProjectionNode.class);
    }

    @Test
    @DisplayName("a table alias after a relation names the RENAME that gives it that name (#212)")
    public void reportsTableAlias() {
        String advice = "Relix has no table aliases: to refer to order_items as oi, write RENAME oi"
                + " (order_items). A self-join is RENAME x (T) >< x.a = y.b RENAME y (T)";
        assertParseError("order_items oi JOIN orders o ON oi.order_id = o.order_id")
                .hasMessageContaining(advice)
                .at(1, 13);
        assertParseError("PROJECT oi.a (order_items AS oi)")
                .hasMessageContaining(advice)
                .at(1, 27);
        assertParseError("device_loads d1 >< d1.device_id < d2.device_id device_loads d2")
                .hasMessageContaining("to refer to device_loads as d1, write RENAME d1 (device_loads)");
        assertParseError("issues I LEFT JOIN issues J ON I.sprint_id = J.sprint_id")
                .hasMessageContaining("to refer to issues as I, write RENAME I (issues)");
        assertParseError("Grouped >< TotalOrders B")
                .hasMessageContaining("to refer to TotalOrders as B, write RENAME B (TotalOrders)")
                .at(1, 24);
        assertParseError("issues LJOIN status_changes AS prevSC ON issues.id = prevSC.issue_id")
                .hasMessageContaining("to refer to status_changes as prevSC, write RENAME prevSC (status_changes)")
                .at(1, 29);
        assertParseError("(SELECT a = 1 (T)) as children")
                .hasMessageContaining("to refer to SELECT a = 1 (T) as children, write RENAME children"
                        + " (SELECT a = 1 (T))");
        // A name as long as the relation's is an alias only where it qualifies a column.
        assertParseError("T x >< x.a = y.b T y")
                .hasMessageContaining("to refer to T as x, write RENAME x (T)");
        assertParseError("Users Orders").hasMessageContaining("Expected end of input");
        assertParseError("PROJECT x.a, y.b (T x JOIN T y ON x.a = y.b)")
                .hasMessageContaining("to refer to T as x, write RENAME x (T)")
                .at(1, 21);
        assertThatThrownBy(() -> parse("PROJECT x (T x)")).hasMessageNotContaining("alias");
        assertThatThrownBy(() -> parse("T AS")).hasMessageNotContaining("alias");
        // RENAME is the spelling that works.
        assertThat(parse("RENAME x (T) >< x.a = y.b RENAME y (T)")).isNode(ThetaJoinNode.class);
    }

    @Test
    @DisplayName("'*' as a column list says Relix has none (#215)")
    public void reportsStarColumnList() {
        assertParseError("PROJECT i.* (SELECT r.src = 5016 (Reach))")
                .hasMessageContaining("Relix has no '*' column list: PROJECT names the columns it keeps."
                        + " To keep all of i's columns, leave PROJECT out, or list them")
                .at(1, 11);
        assertParseError("PROJECT a, * (T)")
                .hasMessageContaining("Relix has no '*' column list: PROJECT names the columns it keeps."
                        + " List them, or leave PROJECT out to keep them all")
                .at(1, 12);
        assertParseError("PROJECT * (T)")
                .hasMessageContaining("Relix has no '*' column list, and a relation already has all its"
                        + " columns: write T")
                .at(1, 9);
        assertParseError("PROJECT a, *, b (T)").hasMessageContaining("List them, or leave PROJECT out");
        assertParseError("PROJECT a, * FROM T").hasMessageContaining("List them, or leave PROJECT out");
        // Multiplication is not a column list.
        assertThatThrownBy(() -> parse("PROJECT a, * 2 (T)")).hasMessageNotContaining("column list");
        assertThatThrownBy(() -> parse("PROJECT * 2 (T)")).hasMessageNotContaining("column list");
        assertThat(parse("PROJECT a * 2 -> b, a * b -> c (T)")).isNode(ProjectionNode.class);
    }

    @Test
    public void reportsTrailingInput() {
        assertParseError("Users Orders")
                .hasMessageContaining("Expected end of input")
                .at(1, 7);
    }

    @Test
    public void reportsMissingClosingParen() {
        assertParseError("(Users")
                .hasMessageContaining("Expected ')' after relational expression")
                .at(1, 7);
    }

    @Test
    public void reportsMissingPredicateClosingParen() {
        assertParseError("σ (age > 18 (Users)")
                .hasMessageContaining("Expected ')' after predicate")
                .at(1, 13);
    }

    @Test
    public void reportsMissingRenameTarget() {
        assertParseError("ρ (Users)")
                .hasMessageContaining("Expected a new relation name or a rename list after 'ρ'")
                .at(1, 3);
    }

    @Test
    public void reportsMissingRenamedAttribute() {
        assertParseError("ρ U(id,) (Users)")
                .hasMessageContaining("Expected ')' after relational expression")
                .at(1, 7);
    }

    @Test
    public void reportsMixedRenameBareAndPairForms() {
        assertParseError("ρ U (a, b → c) (Users)")
                .hasMessageContaining("cannot mix bare column names and 'old → new' pairs");
    }

    @Test
    public void reportsUnterminatedString() {
        assertParseError("σ name = \"Alice (Users)")
                .hasMessageContaining("Unterminated string literal")
                .at(1, 10);
    }

    @Test
    public void reportsInvalidDecimal() {
        assertParseError("σ price = 10. (Products)")
                .hasMessageContaining("Expected digit after decimal point")
                .at(1, 11);
    }

    @Test
    public void reportsLineAndColumn() {
        assertParseError("""
                π id (
                  Users
                """)
                .hasMessageContaining("Expected ')' after relational expression")
                .at(3, 1);
    }

    @Test
    public void reportsUnsupportedStringEscape() {
        assertParseError("σ text = \"hello\\z\" (Messages)")
                .hasMessageContaining("Unsupported string escape")
                .at(1, 17);
    }

    @Test
    public void reportsOnlyCommentInput() {
        assertParseError("/* unterminated comment")
                .hasMessageContaining("Expected relation name")
                .at(1, 24);
    }

    @Test
    public void reportsMissingIdentifierAfterDot() {
        assertParseError("σ Users. = Orders.user_id (Users)")
                .hasMessageContaining("Expected identifier after '.'")
                .at(1, 10);
    }

    @Test
    public void reportsUnterminatedStringEscape() {
        assertParseError("σ text = \"hello\\")
                .hasMessageContaining("Unterminated string escape")
                .at(1, 10);
    }

    @Test
    public void reportsGroupedArithmeticWithoutComparisonInPredicate() {
        assertParseError("σ (a + b) (R)")
                .hasMessageContaining("To choose columns, use PROJECT (π): write π (a + b) → c (R)")
                .at(1, 11);
        assertParseError("σ (a + b) (")
                .hasMessageContaining("Expected comparison operator")
                .at(1, 11);
    }

    @Test
    public void reportsLineAndColumnAfterMultiLineComment() {
        assertParseError("""
                /* comment
                   still comment */
                @
                """)
                .hasMessageContaining("Unexpected character")
                .at(3, 1);
    }

    @Test
    public void reportsFoundTokenAndDiagnostic() {
        assertParseError("@")
                .found("'@'")
                .diagnostic("""
                    @
                    ^""");
    }
}
