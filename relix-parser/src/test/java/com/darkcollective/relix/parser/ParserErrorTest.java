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
                .hasMessageContaining("Expected '(' after projection attribute list")
                .at(1, 10);
    }

    @Test
    public void reportsTrailingProjectionComma() {
        assertParseError("π id, (Users)")
                .hasMessageContaining("Expected '(' after projection attribute list")
                .at(1, 15);
    }

    @Test
    public void reportsMissingProjectionInputParen() {
        assertParseError("π id Users")
                .hasMessageContaining("Expected '(' after projection attribute list")
                .at(1, 6);
    }

    @Test
    public void reportsMissingSelectionInputParen() {
        assertParseError("σ age > 18 Users")
                .hasMessageContaining("Expected '(' after selection predicate")
                .at(1, 12);
    }

    @Test
    public void reportsMissingSelectionComparison() {
        assertParseError("σ age (Users)")
                .hasMessageContaining("Expected comparison operator")
                .at(1, 7);
    }

    @Test
    @DisplayName("SELECT followed by SQL's column list says that SELECT filters, and names PROJECT")
    public void reportsColumnListAfterSelect() {
        assertParseError("SELECT 'TOTAL' -> Item, SUM(Value) -> Value (Selection)")
                .hasMessageContaining("'SELECT' (σ) filters rows by a condition, and found '->'"
                        + " where its comparison belongs. To choose or compute columns, use"
                        + " PROJECT (π): PROJECT a, b * 2 -> c (R)")
                .at(1, 16);
        assertParseError("σ a, b (R)")
                .hasMessageContaining("'σ' filters rows by a condition, and found ','")
                .at(1, 4);
        assertParseError("select a → b (R)")
                .hasMessageContaining("'select' (σ) filters rows by a condition, and found '→'");
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
        assertParseError("PROJECT 1 = a (loans)")
                .hasMessageContaining("Expected '(' after projection attribute list");
        assertParseError("PROJECT a + 1 = b (loans)")
                .hasMessageContaining("Expected '(' after projection attribute list");
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
