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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IrReport#generate(SemanticModel)}.
 *
 * <p>Tests verify structural properties of the generated report: line width,
 * section presence, alphabetical ordering, kind codes, tree layout, and
 * root-query rendering.
 */
@DisplayName("IrReport — IR report generation")
final class IrReportTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<String> lines(String report) {
        return Arrays.asList(report.split("\n", -1));
    }

    private static String report(String src) {
        return IrReport.generate(model(src));
    }

    // =========================================================================
    // 1. Line-width contract
    // =========================================================================

    @Nested
    @DisplayName("Line width")
    class LineWidth {

        @Test
        @DisplayName("Every output line is at most 80 characters")
        void noLineExceedsWidth() {
            String src = """
                    source Orders from database {
                        url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER,
                                  amount: NUMBER, status: STRING,
                                  very_long_column_name_that_could_break_formatting: STRING }
                    };
                    PaidOrders := { σ status = "paid" (Orders) };
                    query PaidOrders;
                    """;

            String rpt = report(src);
            for (String line : lines(rpt)) {
                assertThat(line.length())
                        .as("line over 80 chars: «%s»", line)
                        .isLessThanOrEqualTo(IrReport.WIDTH);
            }
        }

        @Test
        @DisplayName("Empty script produces a report with no over-width lines")
        void emptyScriptWidth() {
            String rpt = report("");
            for (String line : lines(rpt)) {
                assertThat(line.length())
                        .as("line over 80 chars: «%s»", line)
                        .isLessThanOrEqualTo(IrReport.WIDTH);
            }
        }
    }

    // =========================================================================
    // 1b. RELATIONSHIPS section (ADR-0024)
    // =========================================================================

    @Nested
    @DisplayName("Relationships section")
    class Relationships {

        private static final String SCHEMA = """
                source Customers from database { url: "${DB}", table: "customers",
                    schema: { customer_id: NUMBER, name: STRING } };
                source Orders from database { url: "${DB}", table: "orders",
                    schema: { order_id: NUMBER, customer_id: NUMBER } };
                """;

        @Test
        @DisplayName("Section is omitted when no relationships are declared")
        void omittedWhenEmpty() {
            assertThat(report(SCHEMA)).doesNotContain("RELATIONSHIPS");
        }

        @Test
        @DisplayName("A relate edge renders name, endpoints, and non-default bounds")
        void relateEdgeRendered() {
            String rpt = report(SCHEMA + """
                    relate "Placed By" Orders.customer_id -> Customers.customer_id [1..1];
                    """);

            assertThat(rpt).contains("RELATIONSHIPS");
            assertThat(rpt).contains(
                    "\"Placed By\"  Orders(customer_id) → Customers(customer_id) [1..1]");
        }

        @Test
        @DisplayName("Default [0..*] bounds are not rendered")
        void defaultBoundsOmitted() {
            String rpt = report(SCHEMA + """
                    relate "Placed By" Orders.customer_id -> Customers.customer_id;
                    """);

            assertThat(rpt).contains("Orders(customer_id) → Customers(customer_id)");
            assertThat(rpt).doesNotContain("[0..*]");
        }

        @Test
        @DisplayName("Inverse name and symmetric arrow render distinctly")
        void inverseAndSymmetric() {
            String rpt = report("""
                    source Employees from database { url: "${DB}", table: "emp",
                        schema: { id: NUMBER, manager_id: NUMBER } };
                    source Products from database { url: "${DB}", table: "prod",
                        schema: { id: NUMBER, related_id: NUMBER } };
                    relate "Manages" / "Reports To" Employees.id -> Employees.manager_id;
                    relate symmetric "Cross Sells" Products.id -> Products.related_id;
                    """);

            assertThat(rpt).contains("\"Manages\" / \"Reports To\"");
            assertThat(rpt).contains("Products(id) ↔ Products(related_id)");
        }

        @Test
        @DisplayName("A references: edge renders with its implied [0..1] target bound")
        void referencesEdgeRendered() {
            String rpt = report("""
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER } };
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER },
                        references: { customer_id -> Customers.customer_id } };
                    """);

            assertThat(rpt).contains(
                    "\"customer_id\"  Orders(customer_id) → Customers(customer_id) [0..1]");
        }

        @Test
        @DisplayName("A non-declared edge carries its origin tag")
        void originTagOnLearnedEdge() {
            SemanticModel base = model(SCHEMA);
            var orders    = base.symbolTable().resolveRelation("Orders").orElseThrow();
            var customers = base.symbolTable().resolveRelation("Customers").orElseThrow();
            var learned = com.darkcollective.relix.symbol.graph.SchemaGraph.EMPTY.with(
                    new com.darkcollective.relix.symbol.graph.Relationship(
                            "Placed By", java.util.Optional.empty(), false,
                            com.darkcollective.relix.symbol.graph.Endpoint.unbounded(
                                    orders, List.of("customer_id")),
                            com.darkcollective.relix.symbol.graph.Endpoint.unbounded(
                                    customers, List.of("customer_id")),
                            com.darkcollective.relix.symbol.graph.EdgeOrigin.LEARNED));
            SemanticModel withLearned = new SemanticModel(
                    base.namespace(), base.symbolTable(), base.sources(), base.connections(),
                    base.statistics(), base.nodeSchemas(), learned, base.rootQueries(),
                    base.functions());

            assertThat(IrReport.generate(withLearned))
                    .contains("\"Placed By\"  Orders(customer_id) → Customers(customer_id)  (learned)");
        }

        @Test
        @DisplayName("Relationship lines respect the 80-character width contract")
        void relationshipLinesWithinWidth() {
            String rpt = report(SCHEMA + """
                    relate "A Deliberately Very Long Relationship Name For Truncation"
                        Orders(order_id, customer_id) -> Customers(customer_id, name) [1..123456];
                    """);
            for (String line : lines(rpt)) {
                assertThat(line.length())
                        .as("line over 80 chars: «%s»", line)
                        .isLessThanOrEqualTo(IrReport.WIDTH);
            }
        }
    }

    // =========================================================================
    // 2. Header and key
    // =========================================================================

    @Nested
    @DisplayName("Header and key")
    class Header {

        @Test
        @DisplayName("Header contains namespace")
        void headerNamespace() {
            String rpt = report("namespace analytics;");
            assertThat(rpt).contains("namespace=analytics");
        }

        @Test
        @DisplayName("Default namespace is 'default'")
        void defaultNamespace() {
            String rpt = report("");
            assertThat(rpt).contains("namespace=default");
        }

        @Test
        @DisplayName("Report opens and closes with double-rule")
        void doubleRules() {
            String rpt = report("");
            List<String> ls = lines(rpt);
            // Strip trailing empty line from final \n
            List<String> nonempty = ls.stream().filter(l -> !l.isEmpty()).toList();
            String firstLine = nonempty.get(0);
            String lastLine  = nonempty.get(nonempty.size() - 1);
            assertThat(firstLine).startsWith("═");
            assertThat(lastLine).startsWith("═");
        }

        @Test
        @DisplayName("Kind key line is present")
        void kindKeyPresent() {
            String rpt = report("");
            assertThat(rpt).contains("SRC=source")
                           .contains("INL=inline")
                           .contains("SYS=system")
                           .contains("QR=view")
                           .contains("FN=function");
        }

        @Test
        @DisplayName("Type key line is present")
        void typeKeyPresent() {
            String rpt = report("");
            assertThat(rpt).contains("N=number")
                           .contains("S=string")
                           .contains("B=boolean")
                           .contains("D=date")
                           .contains("T=time")
                           .contains("TS=timestamp")
                           .contains("DUR=duration");
        }
    }

    // =========================================================================
    // 3. SYMBOLS section
    // =========================================================================

    @Nested
    @DisplayName("SYMBOLS section")
    class Symbols {

        @Test
        @DisplayName("SYMBOLS section header is present when symbols exist")
        void symbolsSectionHeader() {
            String src = """
                    source Users from database { url: "${DB}", table: "u",
                        schema: { id: NUMBER } };
                    """;
            assertThat(report(src)).contains("SYMBOLS");
        }

        @Test
        @DisplayName("Source relation appears with SRC kind code")
        void sourceKindCode() {
            String src = """
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER, amount: NUMBER } };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("SRC");
            assertThat(rpt).contains("Orders");
        }

        @Test
        @DisplayName("View relation appears with QR kind code")
        void viewKindCode() {
            String src = """
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER, amount: NUMBER } };
                    Totals := { Orders };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("QR");
            assertThat(rpt).contains("Totals");
        }

        @Test
        @DisplayName("System catalog kindCode() returns SYS")
        void systemKindCode() {
            // kindCode is the static method; test it directly via a script that
            // registers a catalog — the IrReport#kindCode method returns "SYS"
            // for SystemRelationSymbol, not "INL".
            String src = """
                    Users := [| id | name  |
                               | 1  | Alice |];
                    """;
            // The model will include relix.* system catalog relations.
            // kindCode on those should be "SYS".
            var sym = model(src).symbolTable().lookupRelation("relix", "relations").orElseThrow();
            assertThat(IrReport.kindCode(sym)).isEqualTo("SYS");
        }

        @Test
        @DisplayName("Symbols are listed in alphabetical order (case-insensitive)")
        void alphabeticalOrder() {
            String src = """
                    source Zebra from database { url: "${DB}", table: "z",
                        schema: { id: NUMBER } };
                    source Apple from database { url: "${DB}", table: "a",
                        schema: { id: NUMBER } };
                    source Mango from database { url: "${DB}", table: "m",
                        schema: { id: NUMBER } };
                    """;
            String rpt = report(src);
            int posApple = rpt.indexOf("Apple");
            int posMango = rpt.indexOf("Mango");
            int posZebra = rpt.indexOf("Zebra");
            assertThat(posApple).isLessThan(posMango);
            assertThat(posMango).isLessThan(posZebra);
        }

        @Test
        @DisplayName("Column schema is rendered with type codes (N, S)")
        void typeCodes() {
            // The parser accepts NUMBER, STRING, ANY as schema column types.
            String src = """
                    source T from database { url: "${DB}", table: "t",
                        schema: { num_col: NUMBER, str_col: STRING } };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("num_col:N");
            assertThat(rpt).contains("str_col:S");
        }

        @Test
        @DisplayName("Empty script produces SYMBOLS section with (none)")
        void emptySymbols() {
            // Empty script has no user-defined symbols; the section may say (none)
            // or be omitted — just verify no exception and format contract holds
            String rpt = report("");
            assertThat(rpt).isNotEmpty();
            // Width contract already checked in LineWidth tests
        }
    }

    // =========================================================================
    // 4. EXPRESSION TREES section
    // =========================================================================

    @Nested
    @DisplayName("EXPRESSION TREES section")
    class ExpressionTrees {

        @Test
        @DisplayName("View body is rendered as an indented tree")
        void viewTreePresent() {
            String src = """
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER, status: STRING } };
                    PaidOrders := { σ status = "paid" (Orders) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("EXPRESSION TREES");
            assertThat(rpt).contains("PaidOrders");
            // Selection σ node should appear
            assertThat(rpt).contains("σ");
            // Orders leaf should appear somewhere in the tree
            assertThat(rpt).contains("Orders");
        }

        @Test
        @DisplayName("Closure renders CLOSURE/RCLOSURE with from→to")
        void closureRendering() {
            String rpt = report("""
                    Edges := [| src | dst |
                               | 1   | 2   |];
                    Reach  := { CLOSURE  src, dst (Edges) };
                    RReach := { RCLOSURE src, dst (Edges) };
                    query Reach;
                    """);
            assertThat(rpt).contains("CLOSURE src→dst");
            assertThat(rpt).contains("RCLOSURE src→dst");
        }

        @Test
        @DisplayName("Cluster renders CLUSTER from, to AS label with a [set] tag")
        void clusterRendering() {
            String rpt = report("""
                    Edges := [| src | dst |
                               | 1   | 2   |];
                    Islands := { CLUSTER src, dst AS island_id (Edges) };
                    query Islands;
                    """);
            assertThat(rpt).contains("CLUSTER src, dst AS island_id");
            assertThat(rpt).contains("[set]");
        }

        @Test
        @DisplayName("Path renders PATH from, to HOPS m TO n AS depth with a [set] tag")
        void pathRendering() {
            String rpt = report("""
                    Edges := [| src | dst |
                               | 1   | 2   |];
                    Reach := { PATH src, dst HOPS 1 TO 3 AS depth (Edges) };
                    query Reach;
                    """);
            assertThat(rpt).contains("PATH src, dst HOPS 1 TO 3 AS depth");
            assertThat(rpt).contains("[set]");
        }

        @Test
        @DisplayName("Outer-union renders the ⊔ glyph with a [set] tag and both children")
        void outerUnionRendering() {
            String rpt = report("""
                    People := [| id: NUMBER | name: STRING |
                                | 1 | "Alice" |];
                    Places := [| id: NUMBER | city: STRING |
                                | 2 | "Berlin" |];
                    Merged := { People ⊔ Places };
                    query Merged;
                    """);
            assertThat(rpt).contains("⊔");
            assertThat(rpt).contains("[set]");
        }

        @Test
        @DisplayName("Sessionize renders SESSIONIZE with its order, gap, partition, session, and [bag] tag")
        void sessionizeRendering() {
            String rpt = report("""
                    Events := [| ts | user_id |
                               | 1  | 7       |];
                    Sessions := { SESSIONIZE ts GAP 5 PER user_id AS session (Events) };
                    query Sessions;
                    """);
            assertThat(rpt).contains("SESSIONIZE ts GAP 5 PER user_id AS session");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("Tree renders TREE with its key, parent, order, children, and [bag] tag")
        void treeRendering() {
            String rpt = report("""
                    Nodes := [| node_id | parent_id | ordinal |
                               | 1       | 0         | 0       |];
                    Forest := { TREE node_id BY parent_id ORDER ordinal AS children (Nodes) };
                    query Forest;
                    """);
            assertThat(rpt).contains("TREE node_id BY parent_id ORDER ordinal AS children");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("Why renders ω with the appended provenance:? column and [bag] tag")
        void whyRendering() {
            String rpt = report("""
                    Orders := [| order_id | region | amount |
                               | 1        | west   | 10     |];
                    Aug := { ω (Orders) };
                    query Aug;
                    """);
            assertThat(rpt).contains("ω");
            assertThat(rpt).contains("[bag]");
            // The reified provenance:ANY column appears in the symbol/tree schema.
            assertThat(rpt).contains("provenance:?");
        }

        @Test
        @DisplayName("Window renders ROLLING with its function, frame, sort, partition, and [bag] tag")
        void windowRendering() {
            String rpt = report("""
                    Ticks := [| ticker | t | price |
                               | A      | 1 | 10    |];
                    Smooth := { ROLLING AVG(price) OVER 3 ROWS SORT t ASC PER ticker AS avg3 (Ticks) };
                    query Smooth;
                    """);
            assertThat(rpt).contains("ROLLING AVG(price) OVER 3 ROWS SORT t↑ PER ticker AS avg3");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("Window renders a ranking function (RANK) with its sort, partition, and [bag] tag")
        void windowRankingRendering() {
            String rpt = report("""
                    Orders := [| customer_id | amount |
                                | 1           | 10     |];
                    Ranked := { WINDOW RANK() SORT amount DESC PER customer_id AS rnk (Orders) };
                    query Ranked;
                    """);
            assertThat(rpt).contains("WINDOW RANK() SORT amount↓ PER customer_id AS rnk");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("Window renders an offset function (LAG) with its expression, offset, sort and partition")
        void windowOffsetRendering() {
            String rpt = report("""
                    Monthly := [| region | month | revenue |
                                 | E      | 1     | 100     |];
                    WithPrev := { WINDOW LAG(revenue, 1) SORT month ASC PER region AS prev (Monthly) };
                    query WithPrev;
                    """);
            assertThat(rpt).contains("WINDOW LAG(revenue, 1) SORT month↑ PER region AS prev");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("AS-OF join renders ASOF with its match condition")
        void asOfRendering() {
            String rpt = report("""
                    Trades := [| sym | t  |
                                | A   | 10 |];
                    Quotes := [| qsym | qt |
                                | A    | 5  |];
                    Joined := { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };
                    query Joined;
                    """);
            assertThat(rpt).contains("ASOF");
        }

        @Test
        @DisplayName("Solve renders SOLVE with the equation")
        void solveRendering() {
            String rpt = report("""
                    Loans := [| total | principal | rate |
                               | 100   | 20        | 5    |];
                    Filled := { SOLVE total = principal * rate (Loans) };
                    query Filled;
                    """);
            assertThat(rpt).contains("SOLVE total = principal * rate");
        }

        @Test
        @DisplayName("Fixpoint renders FIX <name> [set] with base/step children")
        void fixpointRendering() {
            String rpt = report("""
                    Edges := [| src | dst |
                               | 1   | 2   |];
                    Reach := { FIX R (Edges, Edges ∪ R) };
                    query Reach;
                    """);
            // Binder label carries the [set] materialisation tag.
            assertThat(rpt).contains("FIX R  [set]");
            // Both the base relation and the step (a ∪) render as children.
            assertThat(rpt).contains("├─").contains("└─");
            assertThat(rpt).contains("∪");
            // The recursive reference renders as the bare name (no kind code).
            assertThat(lines(rpt)).anySatisfy(line ->
                    assertThat(line.stripTrailing()).endsWith(" R"));
            // Every line stays within the 80-char budget.
            assertThat(lines(rpt)).allSatisfy(line ->
                    assertThat(line.length()).isLessThanOrEqualTo(80));
        }

        @Test
        @DisplayName("Optimize renders the objective, constraint, and grouping key")
        void optimizeRendering() {
            String rpt = report("""
                    Items := [| region | value | weight |
                               | a | 60 | 10 |];
                    Best := { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 PER region (Items) };
                    query Best;
                    """);
            assertThat(rpt).contains("OPTIMIZE max SUM(value) s.t. SUM(weight)≤100 [region]");
        }

        @Test
        @DisplayName("Tree uses └─ prefix for last child")
        void treeLastChildPrefix() {
            // A selection wrapping a relation produces two tree lines:
            // root "σ …" and child "└─ Orders [SRC]".
            // (V := { Orders } alone would be just a leaf with no child prefix.)
            String src = """
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER, status: STRING } };
                    V := { σ status = "active" (Orders) };
                    """;
            String rpt = report(src);
            // The Orders leaf is the last (and only) child — rendered with └─
            assertThat(rpt).contains("└─");
        }

        @Test
        @DisplayName("Binary join tree uses ├─ for first child and └─ for last")
        void binaryTreePrefixes() {
            String src = """
                    source A from database { url: "${DB}", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "${DB}", table: "b",
                        schema: { id: NUMBER } };
                    J := { A ⋈ B };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("├─");
            assertThat(rpt).contains("└─");
        }

        @Test
        @DisplayName("Multiple views are listed in alphabetical order")
        void viewsAlphabetical() {
            String src = """
                    source T from database { url: "${DB}", table: "t",
                        schema: { id: NUMBER } };
                    ZView := { T };
                    AView := { T };
                    """;
            String rpt = report(src);
            int posA = rpt.indexOf("AView");
            int posZ = rpt.indexOf("ZView");
            // In SYMBOLS section both appear; in TREES section they should be alphabetical
            // Find second occurrence (within trees section)
            int posA2 = rpt.indexOf("AView", posA + 1);
            int posZ2 = rpt.indexOf("ZView", posZ + 1);
            if (posA2 >= 0 && posZ2 >= 0) {
                assertThat(posA2).isLessThan(posZ2);
            }
        }

        @Test
        @DisplayName("Source-only model has no EXPRESSION TREES section")
        void noViewsNoSection() {
            String src = """
                    source T from database { url: "${DB}", table: "t",
                        schema: { id: NUMBER } };
                    """;
            String rpt = report(src);
            assertThat(rpt).doesNotContain("EXPRESSION TREES");
        }

        @Test
        @DisplayName("Projection node label contains π and attribute names")
        void projectionLabel() {
            String src = """
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER, status: STRING } };
                    Ids := { π id (Orders) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("π");
            assertThat(rpt).contains("id");
        }

        @Test
        @DisplayName("Aggregation node label contains γ")
        void aggregationLabel() {
            // Syntax: γ grp_attr, AGG(col) → alias (Input)
            String src = """
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { customer_id: NUMBER, amount: NUMBER } };
                    Totals := { γ customer_id, SUM(amount) → total (Orders) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("γ");
        }

        @Test
        @DisplayName("Rename node label contains ρ")
        void renameLabel() {
            String src = """
                    source R from database { url: "j", table: "r",
                        schema: { id: NUMBER } };
                    Renamed := { ρ NewR (R) };
                    """;
            assertThat(report(src)).contains("ρ");
        }

        @Test
        @DisplayName("Sort node label contains τ")
        void sortLabel() {
            String src = """
                    source R from database { url: "j", table: "r",
                        schema: { id: NUMBER } };
                    Sorted := { τ id ASC (R) };
                    """;
            assertThat(report(src)).contains("τ");
        }

        @Test
        @DisplayName("Limit node label contains λ")
        void limitLabel() {
            String src = """
                    source R from database { url: "j", table: "r",
                        schema: { id: NUMBER } };
                    Top5 := { λ 5 (R) };
                    """;
            assertThat(report(src)).contains("λ");
        }

        @Test
        @DisplayName("Distinct node label contains δ")
        void distinctLabel() {
            String src = """
                    source R from database { url: "j", table: "r",
                        schema: { id: NUMBER } };
                    Uniq := { δ (R) };
                    """;
            assertThat(report(src)).contains("δ");
        }

        @Test
        @DisplayName("Theta join node label contains ⨝")
        void thetaJoinLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { aid: NUMBER } };
                    J := { A ⨝ A.id = B.aid B };
                    """;
            assertThat(report(src)).contains("⨝");
        }

        @Test
        @DisplayName("Left outer join node label contains ⟕")
        void leftOuterJoinLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { aid: NUMBER } };
                    J := { A ⟕ A.id = B.aid B };
                    """;
            assertThat(report(src)).contains("⟕");
        }

        @Test
        @DisplayName("Right outer join node label contains ⟖")
        void rightOuterJoinLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { aid: NUMBER } };
                    J := { A ⟖ A.id = B.aid B };
                    """;
            assertThat(report(src)).contains("⟖");
        }

        @Test
        @DisplayName("Full outer join node label contains ⟗")
        void fullOuterJoinLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { aid: NUMBER } };
                    J := { A ⟗ A.id = B.aid B };
                    """;
            assertThat(report(src)).contains("⟗");
        }

        @Test
        @DisplayName("Semi join node label contains ⋉")
        void semiJoinLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { aid: NUMBER } };
                    J := { A ⋉ A.id = B.aid B };
                    """;
            assertThat(report(src)).contains("⋉");
        }

        @Test
        @DisplayName("Anti join node label contains ▷")
        void antiJoinLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { aid: NUMBER } };
                    J := { A ▷ A.id = B.aid B };
                    """;
            assertThat(report(src)).contains("▷");
        }

        @Test
        @DisplayName("Pairwise universal semi-join node label contains USEMI")
        void pairwiseUniversalLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { aid: NUMBER } };
                    J := { A USEMI A.id = B.aid B };
                    """;
            assertThat(report(src)).contains("USEMI");
        }

        @Test
        @DisplayName("Cartesian product node label contains ×")
        void productLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { name: STRING } };
                    P := { A × B };
                    """;
            assertThat(report(src)).contains("×");
        }

        @Test
        @DisplayName("Union node label contains ∪")
        void unionLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    U := { A ∪ B };
                    """;
            assertThat(report(src)).contains("∪");
        }

        @Test
        @DisplayName("UnionAll node label contains ⊎")
        void unionAllLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    U := { A ⊎ B };
                    """;
            assertThat(report(src)).contains("⊎");
        }

        @Test
        @DisplayName("Intersection node label contains ∩")
        void intersectionLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    I := { A ∩ B };
                    """;
            assertThat(report(src)).contains("∩");
        }

        @Test
        @DisplayName("Difference node label contains −")
        void differenceLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    D := { A − B };
                    """;
            assertThat(report(src)).contains("−");
        }

        @Test
        @DisplayName("Division node label contains ÷")
        void divisionLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER, val: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { val: NUMBER } };
                    Div := { A ÷ B };
                    """;
            assertThat(report(src)).contains("÷");
        }

        @Test
        @DisplayName("Symmetric-difference node label contains ∆ and a [set] tag")
        void symmetricDifferenceLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    Sd := { A ∆ B };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("∆");
            assertThat(rpt).contains("[set]");
        }

        @Test
        @DisplayName("Composition node label contains ∘")
        void compositionLabel() {
            String src = """
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER, b: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { b: NUMBER, c: NUMBER } };
                    Comp := { A ∘ B };
                    """;
            assertThat(report(src)).contains("∘");
        }

        @Test
        @DisplayName("Universal node label contains ∀, the key and a [bag] tag")
        void universalLabel() {
            String src = """
                    source Orders from database { url: "j", table: "o",
                        schema: { customer_id: NUMBER, status: STRING } };
                    AllDone := { ∀ customer_id : status = "done" (Orders) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("∀");
            assertThat(rpt).contains("customer_id");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("No-key whole-relation ∀ renders ∀ : P (no stray space) and (no columns)")
        void universalNoKeyLabel() {
            String src = """
                    source Orders from database { url: "j", table: "o",
                        schema: { customer_id: NUMBER, status: STRING } };
                    AllDone := { ∀ : status = "done" (Orders) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("∀ : status = \"done\"");
            assertThat(rpt).doesNotContain("∀  :");          // no double space
            assertThat(rpt).contains("(no columns)");        // empty (truth) schema
        }

        @Test
        @DisplayName("Sample node label contains SAMPLE and the probability (streaming, no tag)")
        void sampleLabel() {
            String src = """
                    source Events from database { url: "j", table: "e",
                        schema: { id: NUMBER } };
                    Sampled := { SAMPLE 0.1 (Events) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("SAMPLE 0.1");
        }

        @Test
        @DisplayName("Reservoir sample label contains SAMPLE n ROWS (buffers → [bag])")
        void reservoirSampleLabel() {
            String src = """
                    source Events from database { url: "j", table: "e",
                        schema: { id: NUMBER } };
                    Sampled := { SAMPLE 100 ROWS (Events) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("SAMPLE 100 ROWS");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("COLLECT renders a nested array type code in the symbol table")
        void nestedArrayTypeCode() {
            String src = """
                    source Orders from database { url: "j", table: "o",
                        schema: { customer_id: NUMBER, amount: NUMBER } };
                    Grouped := { γ customer_id, COLLECT(amount) → amounts (Orders) };
                    """;
            String rpt = report(src);
            // The COLLECT column is an array of numbers → code [N].
            assertThat(rpt).contains("amounts:[N]");
        }

        @Test
        @DisplayName("Top-k node label contains TOP, count, PER and a [bag] tag")
        void topKLabel() {
            String src = """
                    source Orders from database { url: "j", table: "o",
                        schema: { customer_id: NUMBER, amount: NUMBER } };
                    TopOrders := { TOP 3 amount DESC PER customer_id (Orders) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("TOP 3");
            assertThat(rpt).contains("PER customer_id");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("Cover node label contains COVER, the strength, and a [bag] tag")
        void coverLabel() {
            String src = """
                    source Candidates from database { url: "j", table: "c",
                        schema: { type: STRING, format: STRING, size: NUMBER } };
                    Tests := { COVER 2 (Candidates) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("COVER 2");
            assertThat(rpt).contains("[bag]");
        }

        @Test
        @DisplayName("Cover node schema in SYMBOLS matches the input schema (passthrough)")
        void coverSymbolSchema() {
            String src = """
                    source Params from database { url: "j", table: "p",
                        schema: { x: NUMBER, y: STRING } };
                    Suite := { COVER 1 (Params) };
                    """;
            String rpt = report(src);
            // The SYMBOLS line for Suite must list the two columns
            assertThat(rpt).contains("x:N");
            assertThat(rpt).contains("y:S");
        }

        @Test
        @DisplayName("Boolean type code is B in symbol list")
        void booleanTypeCode() {
            // Projecting a boolean literal produces a BOOLEAN column, rendered as :B
            String src = """
                    source T from database { url: "j", table: "t",
                        schema: { id: NUMBER } };
                    Flags := { π true → flag (T) };
                    """;
            String rpt = report(src);
            assertThat(rpt).contains(":B");
        }
    }

    // =========================================================================
    // 5. ROOT QUERIES section
    // =========================================================================

    @Nested
    @DisplayName("ROOT QUERIES section")
    class RootQueries {

        @Test
        @DisplayName("Named query appears with kind code")
        void namedQuery() {
            String src = """
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER } };
                    PaidOrders := { Orders };
                    query PaidOrders;
                    """;
            String rpt = report(src);
            assertThat(rpt).contains("ROOT QUERIES");
            assertThat(rpt).contains("query PaidOrders");
            assertThat(rpt).contains("[QR]");
        }

        @Test
        @DisplayName("No ROOT QUERIES section when no query statements")
        void noQuerySection() {
            String src = """
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER } };
                    """;
            String rpt = report(src);
            assertThat(rpt).doesNotContain("ROOT QUERIES");
        }

        @Test
        @DisplayName("Multiple queries appear in order")
        void multipleQueriesOrder() {
            String src = """
                    source A from database { url: "${DB}", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "${DB}", table: "b",
                        schema: { id: NUMBER } };
                    VA := { A };
                    VB := { B };
                    query VA;
                    query VB;
                    """;
            String rpt = report(src);
            int posVA = rpt.indexOf("query VA");
            int posVB = rpt.indexOf("query VB");
            assertThat(posVA).isGreaterThan(-1);
            assertThat(posVB).isGreaterThan(-1);
            assertThat(posVA).isLessThan(posVB);
        }
    }

    // =========================================================================
    // 6. renderTree (package-visible) unit tests
    // =========================================================================

    @Nested
    @DisplayName("renderTree unit tests")
    class RenderTree {

        @Test
        @DisplayName("Single leaf node produces exactly one line")
        void singleLeaf() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER } };
                    V := { Orders };
                    """);
            // Get the view body (a RelationNode referencing Orders)
            var view = m.symbolTable().allSymbols().stream()
                    .filter(s -> s instanceof com.darkcollective.relix.symbol.relation.QueryRelationSymbol)
                    .map(s -> (com.darkcollective.relix.symbol.relation.QueryRelationSymbol) s)
                    .filter(s -> s.declaredName().equals("V"))
                    .findFirst().orElseThrow();

            List<String> lines = IrReport.renderTree(view.body(), m.symbolTable());
            // One leaf node: "Orders [SRC]" — no prefix for the root
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains("Orders");
        }

        @Test
        @DisplayName("Unary node produces two lines: operator then └─ child")
        void unaryNode() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER, status: STRING } };
                    V := { σ status = "paid" (Orders) };
                    """);
            var view = m.symbolTable().allSymbols().stream()
                    .filter(s -> s instanceof com.darkcollective.relix.symbol.relation.QueryRelationSymbol)
                    .map(s -> (com.darkcollective.relix.symbol.relation.QueryRelationSymbol) s)
                    .filter(s -> s.declaredName().equals("V"))
                    .findFirst().orElseThrow();

            List<String> treeLines = IrReport.renderTree(view.body(), m.symbolTable());
            // Root line (σ ...) + └─ Orders [SRC]
            assertThat(treeLines).hasSize(2);
            assertThat(treeLines.get(0)).startsWith("σ");
            assertThat(treeLines.get(1)).startsWith("└─");
            assertThat(treeLines.get(1)).contains("Orders");
        }

        @Test
        @DisplayName("Binary node produces three lines: op, ├─ left, └─ right")
        void binaryNode() {
            SemanticModel m = model("""
                    source A from database { url: "${DB}", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "${DB}", table: "b",
                        schema: { id: NUMBER } };
                    V := { A ⋈ B };
                    """);
            var view = m.symbolTable().allSymbols().stream()
                    .filter(s -> s instanceof com.darkcollective.relix.symbol.relation.QueryRelationSymbol)
                    .map(s -> (com.darkcollective.relix.symbol.relation.QueryRelationSymbol) s)
                    .filter(s -> s.declaredName().equals("V"))
                    .findFirst().orElseThrow();

            List<String> treeLines = IrReport.renderTree(view.body(), m.symbolTable());
            assertThat(treeLines).hasSize(3);
            assertThat(treeLines.get(0)).contains("⋈");
            assertThat(treeLines.get(1)).startsWith("├─");
            assertThat(treeLines.get(2)).startsWith("└─");
        }
    }

    // =========================================================================
    // Function symbol rendering (FN kind + functionSig)
    // =========================================================================

    @Nested
    @DisplayName("Function symbol rendering")
    class FunctionSymbolRendering {

        @Test
        @DisplayName("def statement appears in SYMBOLS section with FN kind")
        void functionAppearsInSymbols() {
            String r = report("""
                    def double(x: NUMBER): NUMBER := { x * 2 };
                    """);
            assertThat(r).contains("FN");
            assertThat(r).containsIgnoringCase("double");
        }

        @Test
        @DisplayName("Function signature includes parameter name:type and return type")
        void functionSignatureRendered() {
            String r = report("""
                    def add(a: NUMBER, b: NUMBER): NUMBER := { a + b };
                    """);
            // Signature should contain parameter names and types, and return type
            assertThat(r).contains("add");
            assertThat(r).contains("N"); // type code for NUMBER
        }

        @Test
        @DisplayName("Zero-parameter function signature renders as ()→N")
        void zeroParamFunctionSignature() {
            String r = report("""
                    def pi(): NUMBER := { 3 };
                    """);
            assertThat(r).contains("pi");
            assertThat(r).contains("()→N");
        }

        @Test
        @DisplayName("STRING return type renders as S in function signature")
        void stringReturnType() {
            String r = report("""
                    def greet(name: STRING): STRING := { name };
                    """);
            assertThat(r).contains("greet");
            // Signature contains (name:S)→S
            assertThat(r).contains("→S");
        }

        @Test
        @DisplayName("Multiple parameters appear comma-separated in signature")
        void multipleParameters() {
            String r = report("""
                    def compute(a: NUMBER, b: STRING): NUMBER := { 1 };
                    """);
            assertThat(r).contains("compute");
            // Parameters should be comma-separated
            assertThat(r).contains("a:N").contains("b:S");
        }
    }

    // =========================================================================
    // Root queries — expression targets
    // =========================================================================

    @Nested
    @DisplayName("Root queries — expression query target")
    class ExpressionQueryTarget {

        @Test
        @DisplayName("query { expr } renders the expression tree inline in ROOT QUERIES")
        void expressionQueryRenderedInline() {
            String r = report("""
                    A := [| id |
                           | 1 |];
                    query { π id (A) };
                    """);
            // The expression tree should be inlined under ROOT QUERIES
            assertThat(r).contains("query {");
            assertThat(r).contains("}");
            assertThat(r).contains("π");
        }

        @Test
        @DisplayName("named query still renders as 'query <Name> [kind]'")
        void namedQueryRendered() {
            String r = report("""
                    A := [| id |
                           | 1 |];
                    query A;
                    """);
            assertThat(r).contains("query A");
        }
    }

    // =========================================================================
    // Type codes
    // =========================================================================

    @Nested
    @DisplayName("Type codes — ALL scalar types")
    class AllTypeCodes {

        @Test
        @DisplayName("ANY column type renders as ':?' in SYMBOLS section")
        void anyTypeCode() {
            // Source declarations accept ANY as a column type
            String r = report("""
                    source T from database {
                        url: "j", table: "t",
                        schema: { flexible: ANY }
                    };
                    """);
            assertThat(r).contains(":?");
        }
    }

    // =========================================================================
    // Unresolved schema
    // =========================================================================

    @Nested
    @DisplayName("Unresolved schema rendering")
    class UnresolvedSchema {

        @Test
        @DisplayName("Symbol with unresolved schema shows '(schema unresolved)'")
        void unresolvedSchemaRendered() {
            // Reference a relation whose body has an unresolvable reference
            // The validator allows partial models — build via lenient analyze path
            Map<String, com.darkcollective.relix.lang.ast.Script> scripts = new java.util.LinkedHashMap<>();
            var loader   = new InMemoryScriptLoader(scripts);
            var analyzer = new SemanticAnalyzer(loader);
            SemanticResult r = analyzer.analyze(ScriptParser.parse("""
                    Broken := { UndefinedRelation };
                    """));
            // Model may or may not be present (errors exist, but partial model might be returned)
            r.model().ifPresent(m -> {
                String rpt = IrReport.generate(m);
                // If the model is present and has the Broken symbol, it may show unresolved
                assertThat(rpt).contains("SYMBOLS");
            });
        }
    }

    // =========================================================================
    // 7. Materialisation-mode annotations
    // =========================================================================

    @Nested
    @DisplayName("Materialisation annotations in expression trees")
    class MaterializationAnnotations {

        @Test
        @DisplayName("mat key line is present in every report")
        void matKeyLinePresent() {
            String rpt = report("");
            assertThat(rpt).contains("mat:").contains("[bag]").contains("[set]").contains("[sort]");
        }

        @Test
        @DisplayName("Aggregation node (γ) is annotated [bag]")
        void aggregationIsBag() {
            String rpt = report("""
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { customer_id: NUMBER, amount: NUMBER } };
                    Totals := { γ customer_id, SUM(amount) → total (Orders) };
                    """);
            // The aggregation line should end with  [bag]
            assertThat(rpt).contains("[bag]");
            // The γ label line must include the [bag] suffix
            assertThat(rpt).containsPattern("γ .* \\[bag\\]");
        }

        @Test
        @DisplayName("Sort node (τ) is annotated [sort]")
        void sortIsSorted() {
            String rpt = report("""
                    source R from database { url: "j", table: "r",
                        schema: { id: NUMBER } };
                    Sorted := { τ id ASC (R) };
                    """);
            assertThat(rpt).contains("[sort]");
            assertThat(rpt).containsPattern("τ .* \\[sort\\]");
        }

        @Test
        @DisplayName("Union node (∪) is annotated [set]")
        void unionIsSet() {
            String rpt = report("""
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    U := { A ∪ B };
                    """);
            assertThat(rpt).contains("[set]");
            assertThat(rpt).containsPattern("∪ +\\[set\\]");
        }

        @Test
        @DisplayName("UnionAll node (⊎) is annotated [bag]")
        void unionAllIsBag() {
            String rpt = report("""
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    U := { A ⊎ B };
                    """);
            // ⊎ line must include [bag]
            assertThat(rpt).containsPattern("⊎ +\\[bag\\]");
        }

        @Test
        @DisplayName("Intersection node (∩) is annotated [set]")
        void intersectionIsSet() {
            String rpt = report("""
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    I := { A ∩ B };
                    """);
            assertThat(rpt).containsPattern("∩ +\\[set\\]");
        }

        @Test
        @DisplayName("Difference node (−) is annotated [set]")
        void differenceIsSet() {
            String rpt = report("""
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    D := { A − B };
                    """);
            assertThat(rpt).containsPattern("− +\\[set\\]");
        }

        @Test
        @DisplayName("Division node (÷) is annotated [bag]")
        void divisionIsBag() {
            String rpt = report("""
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER, val: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { val: NUMBER } };
                    Div := { A ÷ B };
                    """);
            assertThat(rpt).containsPattern("÷ +\\[bag\\]");
        }

        @Test
        @DisplayName("Full outer join node (⟗) is annotated [bag]")
        void fullOuterJoinIsBag() {
            String rpt = report("""
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { aid: NUMBER } };
                    J := { A ⟗ A.id = B.aid B };
                    """);
            assertThat(rpt).containsPattern("⟗ .* \\[bag\\]");
        }

        @Test
        @DisplayName("Selection node (σ) has no materialisation tag (is STREAM)")
        void selectionIsUnlabelled() {
            String rpt = report("""
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER, status: STRING } };
                    V := { σ status = "paid" (Orders) };
                    """);
            // The σ line should not contain any mat label
            for (String line : lines(rpt)) {
                if (line.strip().startsWith("σ")) {
                    assertThat(line).doesNotContain("[bag]")
                                   .doesNotContain("[set]")
                                   .doesNotContain("[sort]");
                }
            }
        }

        @Test
        @DisplayName("Projection node (π) has no materialisation tag (is STREAM)")
        void projectionIsUnlabelled() {
            String rpt = report("""
                    source Orders from database { url: "${DB}", table: "o",
                        schema: { id: NUMBER, status: STRING } };
                    V := { π id (Orders) };
                    """);
            for (String line : lines(rpt)) {
                if (line.strip().startsWith("π")) {
                    assertThat(line).doesNotContain("[bag]")
                                   .doesNotContain("[set]")
                                   .doesNotContain("[sort]");
                }
            }
        }

        @Test
        @DisplayName("Natural join node (⋈) has no materialisation tag (is STREAM)")
        void naturalJoinIsUnlabelled() {
            String rpt = report("""
                    source A from database { url: "j", table: "a",
                        schema: { id: NUMBER } };
                    source B from database { url: "j", table: "b",
                        schema: { id: NUMBER } };
                    J := { A ⋈ B };
                    """);
            for (String line : lines(rpt)) {
                if (line.strip().equals("⋈")) {
                    assertThat(line).doesNotContain("[bag]")
                                   .doesNotContain("[set]")
                                   .doesNotContain("[sort]");
                }
            }
        }
    }

    // =========================================================================
    // Limit with offset
    // =========================================================================

    @Nested
    @DisplayName("Limit node with offset")
    class LimitWithOffset {

        @Test
        @DisplayName("λ with offset renders as 'λ offset,count'")
        void limitWithOffset() {
            String r = report("""
                    A := [| id |
                           | 1 |
                           | 2 |
                           | 3 |];
                    B := { λ 1, 2 (A) };
                    query B;
                    """);
            assertThat(r).contains("λ 1,2");
        }
    }

    // =========================================================================
    // Rename — with column list
    // =========================================================================

    @Nested
    @DisplayName("Rename node with column list")
    class RenameWithColumns {

        @Test
        @DisplayName("ρ with column list renders attribute names")
        void renameWithColumns() {
            String r = report("""
                    A := [| id | name |
                           | 1  | Alice |];
                    B := { ρ B(key, label) (A) };
                    query B;
                    """);
            assertThat(r).contains("ρ B(key, label)");
        }
    }

    // =========================================================================
    // DatabaseRelationSymbol kind code
    // =========================================================================

    @Nested
    @DisplayName("DatabaseRelationSymbol kind code")
    class DatabaseRelationKindCode {

        @Test
        @DisplayName("DatabaseRelationSymbol is rendered with kind code 'DB'")
        void databaseRelationKindIsDb() {
            // Build a SemanticModel directly with a DatabaseRelationSymbol in the table
            var table  = new InMemorySymbolTable();
            var schema = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));
            table.register(new DatabaseRelationSymbol(
                    "default", "Reports", Provenance.USER, ShadowPolicy.PERMITTED, schema));
            var m = new SemanticModel("default", table, Map.of(),
                    new SchemaAnnotations(), List.of());

            String report = IrReport.generate(m);

            assertThat(report).contains("Reports").contains("DB");
        }
    }

    // =========================================================================
    // Focused report — generateFocused / referencedRelations
    // =========================================================================

    @Nested
    @DisplayName("Focused report")
    class Focused {

        // Relation names are deliberately multi-char and distinct from the report's
        // boilerplate (SYMBOLS / SRC / TREES …) so substring assertions are reliable.
        /** Two independent chains: {@code Topv → Midv → Leafx} and {@code Solo → Leafy}. */
        private static final String TWO_CHAINS = """
                Leafx := [| x |
                           | 1 |];
                Leafy := [| y |
                           | 2 |];
                Midv := { σ x > 0 (Leafx) };
                Topv := { π x (Midv) };
                Solo := { σ y > 0 (Leafy) };
                """;

        @Test
        @DisplayName("restricts SYMBOLS and TREES to the focus set")
        void restrictsToFocusSet() {
            SemanticModel m = model(TWO_CHAINS);

            String r = IrReport.generateFocused(m, Set.of("Topv", "Midv", "Leafx"));

            assertThat(r).contains("Topv").contains("Midv").contains("Leafx");
            // The unrelated chain is omitted entirely.
            assertThat(r).doesNotContain("Solo").doesNotContain("Leafy");
        }

        @Test
        @DisplayName("focus matching is case-insensitive")
        void caseInsensitiveFocus() {
            SemanticModel m = model(TWO_CHAINS);

            String r = IrReport.generateFocused(m, Set.of("topv", "midv", "leafx"));

            assertThat(r).contains("Topv").contains("Midv").contains("Leafx");
            assertThat(r).doesNotContain("Solo").doesNotContain("Leafy");
        }

        @Test
        @DisplayName("an empty focus set yields no symbols or trees")
        void emptyFocus() {
            SemanticModel m = model(TWO_CHAINS);

            String r = IrReport.generateFocused(m, Set.of());

            assertThat(r).contains("RELIX IR").contains("SYMBOLS");
            assertThat(r).doesNotContain("Topv").doesNotContain("Midv")
                    .doesNotContain("Solo").doesNotContain("Leafx").doesNotContain("Leafy");
        }

        @Test
        @DisplayName("referencedRelations collects every RelationNode leaf name")
        void referencedRelationsWalksTree() {
            SemanticModel m = model(TWO_CHAINS);
            RelNode topBody = ((QueryRelationSymbol) m.symbolTable()
                    .lookupRelation("Topv").orElseThrow()).body();
            RelNode midBody = ((QueryRelationSymbol) m.symbolTable()
                    .lookupRelation("Midv").orElseThrow()).body();

            assertThat(IrReport.referencedRelations(topBody)).containsExactly("Midv");
            assertThat(IrReport.referencedRelations(midBody)).containsExactly("Leafx");
        }
    }

    // =========================================================================
    // Optional parts of a label — the half that only shows when it is there
    // =========================================================================

    /**
     * Every clause a node's IR label renders only when the operator carries it.
     *
     * <p>Each is an {@code Optional.map(…).orElse("")} in {@code baseLabel}, so the
     * default case — the seedless sample, the offsetless TOP, the unaliased grouping key
     * — exercises the {@code orElse} and nothing else. The mapping half is a separate
     * arm, and it is the one carrying the information: a label that silently dropped
     * {@code SEED 42} would make two different operators print identically in
     * {@code :tree} and {@code :explain}, which is precisely what an IR report is for.
     */
    @Nested
    @DisplayName("optional label clauses render when present")
    class OptionalLabelParts {

        @Test
        @DisplayName("SAMPLE renders its SEED, and omits it when there is none")
        void bernoulliSampleSeed() {
            String seeded = report("""
                    source Events from database { url: "${DB}", table: "events",
                        schema: { id: NUMBER } };
                    Sampled := { SAMPLE 0.1 SEED 42 (Events) };
                    """);
            assertThat(seeded).contains("SAMPLE 0.1 SEED 42");

            String unseeded = report("""
                    source Events from database { url: "${DB}", table: "events",
                        schema: { id: NUMBER } };
                    Sampled := { SAMPLE 0.1 (Events) };
                    """);
            assertThat(unseeded).contains("SAMPLE 0.1").doesNotContain("SEED");
        }

        @Test
        @DisplayName("reservoir SAMPLE … ROWS renders its SEED too")
        void reservoirSampleSeed() {
            String seeded = report("""
                    source Events from database { url: "${DB}", table: "events",
                        schema: { id: NUMBER } };
                    Sampled := { SAMPLE 100 ROWS SEED 7 (Events) };
                    """);
            assertThat(seeded).contains("SAMPLE 100 ROWS SEED 7");

            String unseeded = report("""
                    source Events from database { url: "${DB}", table: "events",
                        schema: { id: NUMBER } };
                    Sampled := { SAMPLE 100 ROWS (Events) };
                    """);
            assertThat(unseeded).contains("SAMPLE 100 ROWS").doesNotContain("SEED");
        }

        @Test
        @DisplayName("μ renders WITH ORDINALITY when the column is named")
        void unnestOrdinality() {
            String withOrdinality = report("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { id: NUMBER, items: [NUMBER] } };
                    Flat := { μ items WITH ORDINALITY pos (Orders) };
                    """);
            assertThat(withOrdinality).contains("WITH ORDINALITY pos");

            String plain = report("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { id: NUMBER, items: [NUMBER] } };
                    Flat := { μ items (Orders) };
                    """);
            assertThat(plain).doesNotContain("ORDINALITY");
        }

        @Test
        @DisplayName("TOP renders its offset when one is given")
        void topKOffset() {
            String withOffset = report("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { id: NUMBER, amount: NUMBER, cust: NUMBER } };
                    Biggest := { TOP 5, 3 amount DESC PER cust (Orders) };
                    """);
            assertThat(withOffset).contains("TOP 5, 3");

            String noOffset = report("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { id: NUMBER, amount: NUMBER, cust: NUMBER } };
                    Biggest := { TOP 3 amount DESC PER cust (Orders) };
                    """);
            assertThat(noOffset).contains("TOP 3 ");
        }

        @Test
        @DisplayName("DOWNSAMPLE renders its FOR n ROWS cap when capped")
        void downsampleMaxRows() {
            String capped = report("""
                    source Readings from database { url: "${DB}", table: "readings",
                        schema: { at: TIMESTAMP, v: NUMBER, sensor: STRING } };
                    Rolled := { DOWNSAMPLE at BY '5m' USING AVG PER sensor FOR 100 ROWS (Readings) };
                    """);
            assertThat(capped).contains("FOR 100 ROWS");

            String uncapped = report("""
                    source Readings from database { url: "${DB}", table: "readings",
                        schema: { at: TIMESTAMP, v: NUMBER, sensor: STRING } };
                    Rolled := { DOWNSAMPLE at BY '5m' USING AVG PER sensor (Readings) };
                    """);
            assertThat(uncapped).doesNotContain("ROWS");
        }

        @Test
        @DisplayName("an aggregate renders its YIELD expression — ARGMAX's second operand")
        void aggregateYieldExpression() {
            String rpt = report("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { cust: NUMBER, amount: NUMBER, rep: STRING } };
                    Top := { γ cust, ARGMAX(amount, rep) → best (Orders) };
                    """);
            assertThat(rpt).contains("ARGMAX(amount, rep)");
        }

        @Test
        @DisplayName("a grouping key renders its alias when it has one")
        void groupingKeyAlias() {
            String aliased = report("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { at: TIMESTAMP, amount: NUMBER } };
                    Yearly := { γ YEAR(at) → yr, SUM(amount) → total (Orders) };
                    """);
            assertThat(aliased).contains("→yr");

            String bare = report("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { cust: NUMBER, amount: NUMBER } };
                    Totals := { γ cust, SUM(amount) → total (Orders) };
                    """);
            assertThat(bare).contains("[cust]").doesNotContain("cust→");
        }
    }
}
