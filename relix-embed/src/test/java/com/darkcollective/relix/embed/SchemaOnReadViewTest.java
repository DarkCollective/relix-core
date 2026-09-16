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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * A view over a join with a schema-on-read side, through the whole pipeline (#971).
 *
 * <p>An inlined view is {@code ρ View (body)}, and every piece of that shape had a hole
 * an open input fell through: the rename restated each document under a heading that
 * knew only the declared side's columns and failed on width; its heading kept the
 * qualifiers the rename had hidden; and a σ over the view was pushed into the open input
 * under a name only the view defines, where it matched nothing. The files are real
 * because the optimizer is what moves the σ, and only a full run takes that path.
 */
@DisplayName("A view over a join with a schema-on-read side (#971)")
final class SchemaOnReadViewTest {

    private static Relix session(Path directory) throws IOException {
        Files.writeString(directory.resolve("orders.csv"), """
                product_id,quantity,price
                1,2,10
                1,1,10
                2,3,5.5
                3,1,999
                """);
        Files.writeString(directory.resolve("products.json"), """
                [{"product_id": 1, "product_name": "Novel", "details": {"maker": "Acme"}},
                 {"product_id": 2, "product_name": "Trowel", "details": {"maker": "Hoe Co"}},
                 {"product_id": 3, "product_name": "Laptop", "details": {"maker": "Byte"}}]
                """);
        Relix relix = Relix.builder().baseDirectory(directory).build();
        relix.define("""
                source orders from csv("orders.csv") {
                    header: true, schema: { product_id: NUMBER, quantity: NUMBER, price: NUMBER }
                };
                source products from json("products.json");
                J := { orders ⨝ orders.product_id = products.product_id products };
                """);
        return relix;
    }

    @Test
    @DisplayName("a qualified projection through the view reads each field — the renamed one included")
    void qualifiedProjection(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation(
                    "π J.product_id → a, J.product_id_r → b, J.product_name → n, J.quantity → q (J)"))
                    .rows()
                    .hasRowCount(4)
                    .hasRow("1", "1", "Novel", "2")
                    .hasRow("2", "2", "Trowel", "3")
                    .hasRow("3", "3", "Laptop", "1");
        }
    }

    /**
     * The documented anomaly (#974): a qualifier the view hides is not refused over a
     * schema-on-read input, because a dotted name there may be a path into a document
     * field. It reads NULL. The theta-join, rename and assignment reference pages state
     * this, so a change that starts refusing it must change them too.
     */
    @Test
    @DisplayName("a relation name the view hides is accepted and reads NULL, as the reference documents")
    void hiddenQualifierReadsNull(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.validate("query { π orders.product_id → stale (J) };")).isEmpty();
            assertThat(relix.relation("π orders.product_id → stale, J.product_id → id (J)"))
                    .rows()
                    .hasRowCount(4)
                    .hasRow("NULL", "1")
                    .hasNoRow("1", "1");
        }
    }

    @Test
    @DisplayName("the view itself returns every field of both sides")
    void wholeView(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation("J")).hasRowCount(4);
            assertThat(relix.relation("π product_name, quantity (J)"))
                    .rows().hasRow("Trowel", "3");
        }
    }

    @Test
    @DisplayName("a qualified grouping key through the view groups by its own field")
    void groupingKey(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation("γ J.product_id → pid, COUNT(*) → n (J)"))
                    .rows()
                    .hasRowCount(3)
                    .hasRow("1", "2");
        }
    }

    @Test
    @DisplayName("a σ on the view's qualifier matches the rows it names")
    void selectionOnTheViewQualifier(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation("π J.quantity → q (σ J.product_name = \"Novel\" (J))"))
                    .rows().hasRowCount(2).hasRow("2").hasRow("1");
            assertThat(relix.relation("π J.quantity → q (σ J.details.maker = \"Byte\" (J))"))
                    .rows().hasRowCount(1).hasRow("1");
        }
    }

    @Test
    @DisplayName("a σ on a name the join invented is not pushed where that name does not exist")
    void selectionOnACollisionName(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation("π J.quantity → q (σ J.product_id_r = 2 (J))"))
                    .rows().hasRowCount(1).hasRow("3");
            assertThat(relix.relation(
                    "π quantity (σ product_id_r = 3 (orders ⨝ orders.product_id = products.product_id products))"))
                    .rows().hasRowCount(1).hasRow("1");
        }
    }

    @Test
    @DisplayName("a σ on the open side's own qualifier stays above the join")
    void selectionOnTheOpenSideQualifier(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation(
                    "π orders.quantity → q (σ products.product_name = \"Novel\" "
                    + "(orders ⨝ orders.product_id = products.product_id products))"))
                    .rows().hasRowCount(2);
        }
    }
}
