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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bound parameters: a value reaches a query from outside its text, and never becomes part
 * of it.
 */
@DisplayName("Relix — bound parameters ($name)")
final class ParameterTest {

    private static final String ORDERS = """
            Orders := [
            | order_id | customer |
            |----------|----------|
            | 1        | ada      |
            | 2        | grace    |
            | 3        | ada      |
            ];
            """;

    private static Relix orders() {
        Relix relix = Relix.open();
        relix.define(ORDERS);
        return relix;
    }

    /** The relation's {@code order_id}s, asserted with the query attached. */
    private static AbstractListAssert<?, List<? extends String>, String, ObjectAssert<String>> ids(
            Relation relation) {
        return EmbedAssertions.assertThat(relation).tuples()
                .extracting(t -> t.get("order_id").asDisplayString());
    }

    @Nested
    @DisplayName("in the engine")
    final class InTheEngine {

        @Test
        @DisplayName("a bound value selects, and the query still prints the parameter")
        void bindsAValue() {
            try (Relix relix = orders()) {
                Relation query = relix.relation("σ order_id = $id (Orders)");
                Relation bound = query.bind("id", 2);

                ids(bound).containsExactly("2");
                EmbedAssertions.assertThat(bound).renders().isEqualTo(query.render())
                        .contains("$id").doesNotContain("2");
            }
        }

        @Test
        @DisplayName("a string holding quotes is a value, not syntax")
        void aQuoteIsAValue() {
            try (Relix relix = orders()) {
                Relation query = relix.relation("σ customer = $who (Orders)");

                ids(query.bind("who", "ada")).containsExactly("1", "3");
                ids(query.bind("who", "x\" ∨ customer = \"ada")).isEmpty();
            }
        }

        @Test
        @DisplayName("each of the Java kinds a parameter holds is read as its engine value")
        void javaKinds() {
            try (Relix relix = orders()) {
                Relation dated = relix.relation("σ DATE '2026-02-01' < $before (Orders)");
                ids(dated.bind("before", LocalDate.parse("2026-03-01"))).hasSize(3);
                ids(dated.bind("before", LocalDate.parse("2026-01-01"))).isEmpty();
                ids(relix.relation("σ order_id > $n (Orders)")
                        .bind("n", new BigDecimal("1.5"))).containsExactly("2", "3");
                ids(relix.relation("σ order_id ≤ $n (Orders)").bind("n", 1L))
                        .containsExactly("1");
                ids(relix.relation("σ order_id = $n (Orders)").bind("n", null))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a leading $ is accepted, and the name is matched ignoring case")
        void nameSpelling() {
            try (Relix relix = orders()) {
                ids(relix.relation("σ order_id = $Id (Orders)").bind("$ID", 3))
                        .containsExactly("3");
            }
        }

        @Test
        @DisplayName("the session's values serve every query that does not bind its own")
        void sessionValues() {
            try (Relix relix = Relix.builder().parameters(Map.of("who", "grace")).build()) {
                relix.define(ORDERS);
                Relation query = relix.relation("σ customer = $who (Orders)");

                ids(query).containsExactly("2");
                ids(query.bind("who", "ada")).containsExactly("1", "3");
            }
        }

        @Test
        @DisplayName("a parameter in a view is the query's to bind")
        void throughAView() {
            try (Relix relix = orders()) {
                relix.define("ByCustomer := { σ customer = $who (Orders) };");

                ids(relix.relation("ByCustomer").bind("who", "grace"))
                        .containsExactly("2");
            }
        }

        @Test
        @DisplayName("a binding survives composition")
        void survivesComposition() {
            try (Relix relix = orders()) {
                Relation bound = relix.relation("σ customer = $who (Orders)").bind("who", "ada");

                EmbedAssertions.assertThat(bound.project("order_id").limit(1)).hasRowCount(1);
                ids(bound.optimized()).containsExactly("1", "3");
            }
        }
    }

    @Nested
    @DisplayName("refused")
    final class Refused {

        @Test
        @DisplayName("running with a parameter unbound, naming it — even with no rows to read")
        void unbound() {
            try (Relix relix = orders()) {
                Relation query = relix.relation(
                        "σ order_id = $id ∧ order_id > $floor (σ order_id > 99 (Orders))");

                assertThatThrownBy(query::toList).isInstanceOf(RelixException.class)
                        .hasMessageContaining("parameters $id, $floor are not bound");
                assertThatThrownBy(() -> query.bind("id", 1).toList())
                        .hasMessageContaining("parameter $floor is not bound");
            }
        }

        @Test
        @DisplayName("binding a name the query does not use")
        void unknownName() {
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> relix.relation("σ order_id = $id (Orders)").bind("od", 1))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("no parameter $od; its parameters are [$id]");
                assertThatThrownBy(() -> relix.relation("Orders").bind("id", 1))
                        .hasMessage("this relation has no parameter $id");
            }
        }

        @Test
        @DisplayName("a value of another type than the parameter is compared with")
        void wrongType() {
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> relix.relation("σ order_id = $id (Orders)").bind("id", "2"))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("parameter $id is compared with a NUMBER,"
                                + " so it cannot be bound to the STRING 2");
            }
        }

        @Test
        @DisplayName("a Java value no parameter can hold")
        void unsupportedKind() {
            try (Relix relix = orders()) {
                Relation query = relix.relation("σ order_id = $id (Orders)");
                assertThatThrownBy(() -> query.bind("id", List.of(1)))
                        .hasMessageContaining("parameter $id cannot hold a");
                assertThatThrownBy(() -> query.bind("id", Double.NaN))
                        .hasMessageContaining("parameter $id cannot hold a java.lang.Double");
            }
        }

        @Test
        @DisplayName("composing two relations that bind one parameter differently")
        void conflictingBindings() {
            try (Relix relix = orders()) {
                Relation ada = relix.relation("σ customer = $who (Orders)").bind("who", "ada");
                Relation grace = relix.relation("σ customer = $who (Orders)").bind("who", "grace");

                EmbedAssertions.assertThat(ada.union(ada)).hasRowCount(2);
                EmbedAssertions.assertThat(relix.relation("σ customer = \"grace\" (Orders)")
                        .union(ada)).hasRowCount(3);
                assertThatThrownBy(() -> ada.union(grace))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("bind parameter $who to different values, ada and grace");
            }
        }
    }

    @Test
    @DisplayName("the model reports each parameter with the type it is compared as")
    void modelReportsTypes() {
        try (Relix relix = orders()) {
            relix.define("Recent := { σ order_id > $since ∧ customer = $who (Orders) };");
            relix.define("Echo := { π order_id, $label → tag (Orders) };");

            assertThat(relix.model().parameters()).containsExactly(
                    Map.entry("since", ScalarType.NUMBER),
                    Map.entry("who", ScalarType.STRING),
                    Map.entry("label", ScalarType.ANY));
        }
    }

    /** The same queries against a database, where a parameter becomes a JDBC bind parameter. */
    @Nested
    @DisplayName("pushed to a database")
    final class Pushed {

        private static final String URL = "jdbc:h2:mem:embed_parameters;DB_CLOSE_DELAY=-1";

        private static final String PREAMBLE =
                "connection db from jdbc { url: \"" + URL + "\" };\n"
                + "source Customers from db {\n"
                + "    table: \"customers\",\n"
                + "    schema: { id: NUMBER, name: STRING, joined: TIMESTAMP }\n"
                + "};\n";

        @BeforeAll
        static void seed() throws SQLException {
            try (Connection c = DriverManager.getConnection(URL)) {
                var st = c.createStatement();
                st.execute("DROP TABLE IF EXISTS customers");
                st.execute("CREATE TABLE customers (id INT, name VARCHAR(32), joined TIMESTAMP)");
                st.execute("INSERT INTO customers VALUES (1, 'ada', '2026-01-01 00:00:00'),"
                        + " (2, 'o''brien', '2026-02-01 00:00:00'), (3, 'grace', NULL)");
            }
        }

        private static Relix database() {
            Relix relix = Relix.open();
            relix.define(PREAMBLE);
            return relix;
        }

        private static AbstractListAssert<?, List<? extends String>, String, ObjectAssert<String>> names(
                Relation relation) {
            return EmbedAssertions.assertThat(relation).tuples()
                    .extracting(t -> t.get("name").asDisplayString());
        }

        @Test
        @DisplayName("the SQL sent carries a ? where the parameter is, and the plan names it")
        void sendsAPlaceholder() {
            try (Relix relix = database()) {
                Relation query = relix.relation("σ id = $id (Customers)");

                EmbedAssertions.assertThat(query).explains().contains("WHERE", "= ?", "← $id")
                        .doesNotContain("$id)");
                names(query.bind("id", 2)).containsExactly("o'brien");
            }
        }

        @Test
        @DisplayName("a quote in a bound string cannot change the statement")
        void injectionIsInert() {
            try (Relix relix = database()) {
                Relation query = relix.relation("σ name = $who (Customers)");

                names(query.bind("who", "o'brien")).containsExactly("o'brien");
                names(query.bind("who", "x' OR '1'='1")).isEmpty();
                names(query.bind("who", "x'; DROP TABLE customers; --")).isEmpty();
                names(relix.relation("Customers")).hasSize(3);
            }
        }

        @Test
        @DisplayName("several placeholders bind in the order they appear in the statement")
        void severalInOrder() {
            try (Relix relix = database()) {
                Relation query = relix.relation(
                        "σ joined ≥ $from ∧ id ≠ $skip ∧ name ≠ $not (Customers)")
                        .bind("skip", 9).bind("not", "zed")
                        .bind("from", Instant.parse("2026-01-15T00:00:00Z"));

                EmbedAssertions.assertThat(query).explains().contains("← $from, $skip, $not");
                names(query).containsExactly("o'brien");
            }
        }

        @Test
        @DisplayName("a parameter the database could not type is evaluated by the engine")
        void notPushedWhereUntyped() {
            try (Relix relix = database()) {
                Relation query = relix.relation("σ id = $base + 1 (Customers)").bind("base", 1);

                EmbedAssertions.assertThat(query).explains().doesNotContain("?");
                names(query).containsExactly("o'brien");
            }
        }

        @Test
        @DisplayName("an unbound parameter is refused before the database is asked")
        void unboundBeforeTheDatabase() {
            try (Relix relix = database()) {
                assertThatThrownBy(() -> relix.relation("σ id = $id (Customers)").toList())
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("parameter $id is not bound");
            }
        }
    }
}
