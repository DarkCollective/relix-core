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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.lang.ast.EndpointSpec;
import com.darkcollective.relix.lang.ast.InlineTableBody;
import com.darkcollective.relix.lang.ast.RelateStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScriptParser — relate statements and references: blocks (ADR-0024)")
final class ScriptParserRelateTest {

    private static Script parse(String source) {
        return ScriptParser.parse(source);
    }

    private static RelateStatement relate(String source) {
        Script script = parse(source);
        assertThat(script.statements()).hasSize(1);
        assertThat(script.statements().get(0)).isInstanceOf(RelateStatement.class);
        return (RelateStatement) script.statements().get(0);
    }

    @Nested
    @DisplayName("relate statement")
    class RelateStatements {

        @Test
        @DisplayName("named edge with target-side bounds")
        void namedEdgeWithBounds() {
            RelateStatement stmt = relate(
                    "relate \"Order Line Items\" Orders.order_id -> OrderItems.order_id [1..50];");

            assertThat(stmt.name()).isEqualTo("Order Line Items");
            assertThat(stmt.inverseName()).isEmpty();
            assertThat(stmt.symmetric()).isFalse();

            EndpointSpec source = stmt.source();
            assertThat(source.relationRef()).isEqualTo("Orders");
            assertThat(source.columns()).containsExactly("order_id");
            assertThat(source.min()).isZero();
            assertThat(source.max()).isEmpty();

            EndpointSpec target = stmt.target();
            assertThat(target.relationRef()).isEqualTo("OrderItems");
            assertThat(target.columns()).containsExactly("order_id");
            assertThat(target.min()).isEqualTo(1);
            assertThat(target.max()).isEqualTo(OptionalLong.of(50));
        }

        @Test
        @DisplayName("inverse name after '/' marks distinct roles")
        void inverseName() {
            RelateStatement stmt = relate(
                    "relate \"Manages\" / \"Reports To\" Employees.id -> Employees.manager_id;");

            assertThat(stmt.name()).isEqualTo("Manages");
            assertThat(stmt.inverseName()).contains("Reports To");
            assertThat(stmt.symmetric()).isFalse();
            assertThat(stmt.source().relationRef()).isEqualTo("Employees");
            assertThat(stmt.target().relationRef()).isEqualTo("Employees");
        }

        @Test
        @DisplayName("symmetric modifier")
        void symmetricEdge() {
            RelateStatement stmt = relate(
                    "relate symmetric \"Cross Sells\" Products.id -> Products.related_id;");

            assertThat(stmt.symmetric()).isTrue();
            assertThat(stmt.name()).isEqualTo("Cross Sells");
            assertThat(stmt.inverseName()).isEmpty();
        }

        @Test
        @DisplayName("composite-key endpoints use parenthesised column lists")
        void compositeEndpoints() {
            RelateStatement stmt = relate(
                    "relate \"Slot Product\" WarehouseSlot(tenant_id, product_id)"
                    + " -> Product(tenant_id, id);");

            assertThat(stmt.source().relationRef()).isEqualTo("WarehouseSlot");
            assertThat(stmt.source().columns()).containsExactly("tenant_id", "product_id");
            assertThat(stmt.target().relationRef()).isEqualTo("Product");
            assertThat(stmt.target().columns()).containsExactly("tenant_id", "id");
        }

        @Test
        @DisplayName("bounds may appear on both endpoints; '*' is unbounded")
        void boundsOnBothEndpoints() {
            RelateStatement stmt = relate(
                    "relate \"Assignment\" Drivers.id [0..*] -> Manifests.driver_id [2..2];");

            assertThat(stmt.source().min()).isZero();
            assertThat(stmt.source().max()).isEmpty();
            assertThat(stmt.target().min()).isEqualTo(2);
            assertThat(stmt.target().max()).isEqualTo(OptionalLong.of(2));
        }

        @Test
        @DisplayName("parenthesised form takes a namespace-qualified relation")
        void qualifiedRelationInParenForm() {
            RelateStatement stmt = relate(
                    "relate \"X\" analytics.Orders(order_id) -> OrderItems.order_id;");

            assertThat(stmt.source().relationRef()).isEqualTo("analytics.Orders");
            assertThat(stmt.source().columns()).containsExactly("order_id");
        }

        @Test
        @DisplayName("in dot form, the last segment is the column")
        void qualifiedRelationInDotForm() {
            RelateStatement stmt = relate(
                    "relate \"X\" analytics.Orders.order_id -> OrderItems.order_id;");

            assertThat(stmt.source().relationRef()).isEqualTo("analytics.Orders");
            assertThat(stmt.source().columns()).containsExactly("order_id");
        }

        @Test
        @DisplayName("location points at the relate keyword")
        void location() {
            RelateStatement stmt = relate(
                    "relate \"X\" A.id -> B.a_id;");
            assertThat(stmt.location().line()).isEqualTo(1);
            assertThat(stmt.location().column()).isEqualTo(1);
        }

        @Test
        @DisplayName("relate coexists with other statements")
        void coexistsWithOtherStatements() {
            Script script = parse("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER } };
                    relate "X" Orders.order_id -> OrderItems.order_id;
                    """);
            assertThat(script.statements()).hasSize(2);
            assertThat(script.statements().get(1)).isInstanceOf(RelateStatement.class);
        }
    }

    @Nested
    @DisplayName("relate statement errors")
    class RelateErrors {

        @Test
        @DisplayName("name is required")
        void nameRequired() {
            assertThatThrownBy(() -> parse("relate Orders.order_id -> OrderItems.order_id;"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("relationship name");
        }

        @Test
        @DisplayName("missing arrow is rejected")
        void missingArrow() {
            assertThatThrownBy(() -> parse("relate \"X\" Orders.order_id OrderItems.order_id;"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("bare relation without column is rejected")
        void bareRelation() {
            assertThatThrownBy(() -> parse("relate \"X\" Orders -> OrderItems.order_id;"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Expected '.' or '(");
        }

        @Test
        @DisplayName("empty parenthesised column list is rejected")
        void emptyColumnList() {
            assertThatThrownBy(() -> parse("relate \"X\" Orders() -> OrderItems.order_id;"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("decimal bounds are rejected")
        void decimalBound() {
            assertThatThrownBy(() -> parse(
                    "relate \"X\" A.id -> B.a_id [1.5..3];"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("integer");
        }

        @Test
        @DisplayName("'*' as a lower bound is rejected")
        void starLowerBound() {
            assertThatThrownBy(() -> parse("relate \"X\" A.id -> B.a_id [*..5];"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("missing semicolon is rejected")
        void missingSemicolon() {
            assertThatThrownBy(() -> parse("relate \"X\" A.id -> B.a_id"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining(";");
        }
    }

    @Nested
    @DisplayName("references: block")
    class ReferencesBlocks {

        @Test
        @DisplayName("single-column FK on a database source")
        void singleColumnReference() {
            Script script = parse("""
                    source Orders from database {
                        url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER },
                        references: { customer_id -> Customers.customer_id }
                    };
                    """);
            SourceDeclaration decl = (SourceDeclaration) script.statements().get(0);
            DatabaseSourceConfig config = (DatabaseSourceConfig) decl.config();

            assertThat(config.references()).hasSize(1);
            ColumnReference ref = config.references().get(0);
            assertThat(ref.sourceColumns()).containsExactly("customer_id");
            assertThat(ref.targetRelation()).isEqualTo("Customers");
            assertThat(ref.targetColumns()).containsExactly("customer_id");
            assertThat(ref.defaultName()).isEqualTo("customer_id");
        }

        @Test
        @DisplayName("composite FK uses parenthesised lists on both sides")
        void compositeReference() {
            Script script = parse("""
                    source Slots from database {
                        url: "${DB}", table: "slots",
                        schema: { tenant_id: NUMBER, product_id: NUMBER },
                        references: { (tenant_id, product_id) -> Product(tenant_id, id) }
                    };
                    """);
            DatabaseSourceConfig config = (DatabaseSourceConfig)
                    ((SourceDeclaration) script.statements().get(0)).config();

            ColumnReference ref = config.references().get(0);
            assertThat(ref.sourceColumns()).containsExactly("tenant_id", "product_id");
            assertThat(ref.targetRelation()).isEqualTo("Product");
            assertThat(ref.targetColumns()).containsExactly("tenant_id", "id");
            assertThat(ref.defaultName()).isEqualTo("tenant_id, product_id");
        }

        @Test
        @DisplayName("multiple entries separated by commas")
        void multipleReferences() {
            Script script = parse("""
                    source Issues from database {
                        url: "${DB}", table: "issues",
                        schema: { id: NUMBER, assignee_id: NUMBER, reporter_id: NUMBER },
                        references: {
                            assignee_id -> Users.id,
                            reporter_id -> Users.id
                        }
                    };
                    """);
            DatabaseSourceConfig config = (DatabaseSourceConfig)
                    ((SourceDeclaration) script.statements().get(0)).config();

            assertThat(config.references()).hasSize(2);
            assertThat(config.references().get(0).sourceColumns()).containsExactly("assignee_id");
            assertThat(config.references().get(1).sourceColumns()).containsExactly("reporter_id");
        }

        @Test
        @DisplayName("references block on a connection-table source")
        void connectionTableReferences() {
            Script script = parse("""
                    source Orders from sales {
                        table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER },
                        references: { customer_id -> Customers.customer_id }
                    };
                    """);
            ConnectionTableSourceConfig config = (ConnectionTableSourceConfig)
                    ((SourceDeclaration) script.statements().get(0)).config();

            assertThat(config.references()).hasSize(1);
            assertThat(config.references().get(0).targetRelation()).isEqualTo("Customers");
        }

        @Test
        @DisplayName("absent references block defaults to empty")
        void absentReferencesBlock() {
            Script script = parse("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER } };
                    """);
            DatabaseSourceConfig config = (DatabaseSourceConfig)
                    ((SourceDeclaration) script.statements().get(0)).config();
            assertThat(config.references()).isEmpty();
        }

        @Test
        @DisplayName("references entry without an arrow is rejected")
        void missingArrowInReference() {
            assertThatThrownBy(() -> parse("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER },
                        references: { customer_id Customers.customer_id } };
                    """))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("references block on a CSV file source")
        void csvSourceReferences() {
            Script script = parse("""
                    source T from csv("data.csv") {
                        schema: { id: NUMBER, other_id: NUMBER },
                        references: { other_id -> Other.id }
                    };
                    """);
            CsvFileSourceConfig config = (CsvFileSourceConfig)
                    ((SourceDeclaration) script.statements().get(0)).config();

            assertThat(config.references()).hasSize(1);
            assertThat(config.references().get(0).targetRelation()).isEqualTo("Other");
        }

        @Test
        @DisplayName("references block on a JSON file source")
        void jsonSourceReferences() {
            Script script = parse("""
                    source Docs from json("docs.json") {
                        records: "data.items",
                        references: { owner_id -> Users.id }
                    };
                    """);
            JsonFileSourceConfig config = (JsonFileSourceConfig)
                    ((SourceDeclaration) script.statements().get(0)).config();

            assertThat(config.records()).contains("data.items");
            assertThat(config.references()).hasSize(1);
            assertThat(config.references().get(0).sourceColumns()).containsExactly("owner_id");
        }
    }

    @Nested
    @DisplayName("inline-table references suffix")
    class InlineTableReferences {

        @Test
        @DisplayName("markdown inline table with a trailing references clause")
        void markdownInlineTableReferences() {
            Script script = parse("""
                    Cities := [
                    | name    | country |
                    |---------|---------|
                    | Chicago | US      |
                    ] references { country -> Countries.code };
                    """);
            AssignmentStatement asgn = (AssignmentStatement) script.statements().get(0);
            InlineTableBody body = (InlineTableBody) asgn.body();

            assertThat(body.references()).hasSize(1);
            ColumnReference ref = body.references().get(0);
            assertThat(ref.sourceColumns()).containsExactly("country");
            assertThat(ref.targetRelation()).isEqualTo("Countries");
            assertThat(ref.targetColumns()).containsExactly("code");
        }

        @Test
        @DisplayName("csv inline table with a trailing references clause")
        void csvInlineTableReferences() {
            Script script = parse("""
                    StatusCodes := csv[
                        code, label
                        200, OK
                    ] references { code -> HttpCodes.code };
                    """);
            InlineTableBody body = (InlineTableBody)
                    ((AssignmentStatement) script.statements().get(0)).body();

            assertThat(body.references()).hasSize(1);
            assertThat(body.references().get(0).targetRelation()).isEqualTo("HttpCodes");
        }

        @Test
        @DisplayName("inline table without the clause has no references")
        void absentSuffix() {
            Script script = parse("""
                    Regions := [
                    | region |
                    |--------|
                    | EMEA   |
                    ];
                    """);
            InlineTableBody body = (InlineTableBody)
                    ((AssignmentStatement) script.statements().get(0)).body();
            assertThat(body.references()).isEmpty();
        }

        @Test
        @DisplayName("a references clause on a view assignment is rejected with guidance")
        void viewAssignmentRejectsReferences() {
            assertThatThrownBy(() -> parse("""
                    V := { π name (Users) } references { name -> Other.name };
                    """))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("relate a view with a standalone 'relate' statement");
        }
    }
}
