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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import com.darkcollective.relix.lang.ast.source.JsonExtractSpec;
import com.darkcollective.relix.lang.ast.source.PaginateSpec;
import com.darkcollective.relix.lang.ast.source.PaginateEntry;
import com.darkcollective.relix.lang.ast.source.BearerAuth;
import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.source.SourceConfig;
import com.darkcollective.relix.symbol.ScalarType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One instance of every concrete {@link Statement} and {@link SourceConfig} kind, built
 * through {@link ScriptBuilders}.
 *
 * <p>The statement-level counterpart of {@code RelNodeCorpus}, and it exists for the
 * same reason: a whole-hierarchy test is written once and reaches a new kind the day its
 * corpus entry lands. {@code ScriptCorpusTest} keeps the corpus complete by walking the
 * sealed hierarchies reflectively, so a new {@code permits} entry fails the build rather
 * than quietly going untested.
 *
 * <p>Entries populate their optional components wherever the record's invariants allow —
 * an entry that left everything empty would exercise the easy half of every printer arm
 * and prove very little.
 */
public final class ScriptCorpus extends ScriptBuilders {

    private ScriptCorpus() {
    }

    private static final ColumnSpec ID = column("id", ScalarType.NUMBER);
    private static final ColumnSpec NAME = column("name", ScalarType.STRING);
    /** A boolean column, so the round trip covers every scalar the grammar can spell. */
    private static final ColumnSpec ACTIVE = column("active", ScalarType.BOOLEAN);

    /**
     * One statement of every concrete kind, in {@link Statement}'s own {@code permits}
     * order.
     *
     * @return 9 statements, one per kind; never empty
     */
    public static List<Statement> everyStatement() {
        return List.of(
                env(Optional.of(".env.local"), Optional.of("staging")),
                importNames(ImportKind.UNQUALIFIED, List.of("Users", "Orders"), "./db.relix"),
                connection("warehouse", "jdbc", Map.of("url", "jdbc:postgresql://h/db")),
                source("Orders", databaseSource("jdbc:h2:mem:x", "orders", ID, NAME)),
                relate("places", endpoint("Orders", List.of("customer_id")),
                        endpoint("Customers", List.of("customer_id"))),
                assign("Open", select(cmp(attr("status"), com.darkcollective.relix.ast
                        .ComparisonOperator.EQUAL, str("OPEN")), rel("Orders"))),
                def("double", List.of(param("x", ScalarType.NUMBER)), ScalarType.NUMBER,
                        arith(attr("x"), com.darkcollective.relix.ast.ArithmeticOperator.MULTIPLY,
                                num("2"))),
                defRelation("ordersFor", List.of(param("cid", ScalarType.NUMBER)),
                        select(cmp(attr("customer_id"),
                                com.darkcollective.relix.ast.ComparisonOperator.EQUAL,
                                attr("cid")), rel("Orders"))),
                query(rel("Open")));
    }

    /**
     * One source configuration of every concrete kind, in {@link SourceConfig}'s own
     * {@code permits} order.
     *
     * @return 6 configurations, one per kind; never empty
     */
    public static List<SourceConfig> everySourceConfig() {
        return List.of(
                httpSource("https://api.example.com/orders", ID, NAME),
                databaseSource("jdbc:h2:mem:x", "orders", ID, NAME, ACTIVE),
                csvSource("./orders.csv", true, List.of(ID, NAME)),
                jsonSource("./orders.json", Optional.of("records")),
                connectionTable("warehouse", "orders", ID, NAME),
                generatorSource("range", Map.of("from", "1", "to", "10")));
    }

    /**
     * The optional components of the two richest kinds, each populated.
     *
     * <p>An HTTP source declares eight components and the plain corpus entry carries
     * three of them; a {@code relate} carries an inverse name, symmetry and cardinality
     * bounds that its entry leaves at their defaults. A corpus of one instance per kind
     * says nothing about a component, which is how {@code auth:} and {@code paginate:}
     * came to be readable by the grammar and unwritable by the printer — a source whose
     * credentials were dropped on the way through, with every kind round-tripping.
     *
     * <p>A second HTTP source carries the one {@link HttpMethod} the first does not reach
     * beyond the default: {@code QUERY}, which exists only with a body.
     *
     * @return four declarations, two HTTP sources and two relates, nothing left at default
     */
    public static List<Statement> everyPopulatedComponent() {
        HttpSourceConfig http = httpSource(
                "https://api.example.com/orders",
                HttpMethod.POST,
                Map.of("Accept", "application/json"),
                Optional.of(new JsonExtractSpec("$.records")),
                Optional.of(new PaginateSpec(List.of(
                        new PaginateEntry("page", "page_number", Optional.empty()),
                        new PaginateEntry("size", "page_size", Optional.of(100L))))),
                List.of(column("id", ScalarType.NUMBER, "$.id"), NAME),
                Optional.of("{\"since\": \"2020-01-01\"}"),
                Optional.of(new BearerAuth("s3cret")));

        HttpSourceConfig query = httpSource(
                "https://api.example.com/orders",
                HttpMethod.QUERY,
                Map.of("Content-Type", "application/sql"),
                Optional.empty(), Optional.empty(), List.of(),
                Optional.of("SELECT id FROM orders"),
                Optional.empty());

        return List.of(
                source("Remote", http),
                source("Searched", query),
                relate("places", Optional.of("placed by"), true,
                        endpoint("Orders", List.of("customer_id")),
                        endpoint("Customers", List.of("id"), 1, java.util.OptionalLong.of(1))),
                // A bound above with none below: printed only when the pair is not the
                // implicit [0..*], and a zero minimum is not on its own a default.
                relate("covers", endpoint("Orders", List.of("id")),
                        endpoint("Items", List.of("order_id"), 0, java.util.OptionalLong.of(5))));
    }

    /**
     * The four configurations that can carry a {@code references:} block, each carrying
     * one — a single-column edge and a composite one.
     *
     * <p>Separate from {@link #everySourceConfig()} rather than folded into it: that list
     * is one instance per <em>kind</em>, and this is a <em>component</em> four of those
     * kinds share. Keeping both is what makes the round trip a claim about components
     * rather than about kinds — the printer dropped this one on three of the four while
     * every kind round-tripped, because a kind was present and its edges were not.
     *
     * @return four configurations, each with references
     */
    public static List<SourceConfig> everyReferenceCarryingSourceConfig() {
        List<ColumnReference> single = List.of(reference("customer_id", "Customers", "id"));
        List<ColumnReference> composite = List.of(
                reference(List.of("region", "sku"), "Stock", List.of("region", "sku")));
        return List.of(
                databaseSource("jdbc:h2:mem:x", "orders", List.of(ID, NAME), single),
                connectionTable("warehouse", "orders", List.of(ID, NAME), composite),
                csvSource("./orders.csv", true, List.of(ID, NAME), single),
                jsonSource("./orders.json", Optional.of("records"), composite));
    }

    /**
     * Every source configuration wrapped in the {@code source} declaration that carries
     * it, since a configuration has no text form on its own.
     *
     * @return one declaration per configuration kind, then one per reference-carrying kind
     */
    public static List<Statement> everySourceDeclaration() {
        return Stream.concat(everySourceConfig().stream(),
                        everyReferenceCarryingSourceConfig().stream())
                .map(config -> (Statement) source("S", config))
                .toList();
    }

    /**
     * The assignment forms, which differ by {@link AssignmentBody} rather than by
     * statement kind — a markdown inline table, a csv one, and a view.
     *
     * @return one assignment per body form
     */
    public static List<Statement> everyAssignmentForm() {
        return List.of(
                assign("Open", rel("Orders")),
                assign("Regions", markdownTable(
                        List.of("region", "country"),
                        List.of(List.of("EMEA", "United Kingdom"),
                                List.of("APAC", "Australia")))),
                assign("Prices", csvTable(
                        List.of("sku", "price"),
                        List.of(List.of("A-1", "9.99")))));
    }
}
