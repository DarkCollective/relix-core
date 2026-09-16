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

import com.darkcollective.relix.optimizer.OptimizationCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * A qualified reference to a schema-on-read source's own field, through the whole
 * pipeline (#972).
 *
 * <p>A declared row answers {@code products.name} through its heading's provenance. A
 * document has no heading, so it read the name as a path into a field called
 * {@code products} and found nothing — on a bare source, and wherever the optimizer had
 * pushed a σ onto one. A scan now anchors each document to the name the query read it by.
 */
@DisplayName("A qualified reference to a schema-on-read source's own field (#972)")
final class SchemaOnReadSourceTest {

    private static Relix session(Path directory) throws IOException {
        Files.writeString(directory.resolve("orders.csv"), """
                product_id,quantity,price
                1,2,10
                1,1,10
                2,3,5.5
                """);
        Files.writeString(directory.resolve("products.json"), """
                [{"product_id": 1, "product_name": "Novel", "details": {"maker": "Acme"}},
                 {"product_id": 2, "product_name": "Trowel", "details": {"maker": "Hoe Co"}}]
                """);
        Relix relix = Relix.builder().baseDirectory(directory).build();
        relix.define("""
                source orders from csv("orders.csv") {
                    header: true, schema: { product_id: NUMBER, quantity: NUMBER, price: NUMBER }
                };
                source products from json("products.json");
                """);
        return relix;
    }

    @Test
    @DisplayName("π and σ over the bare source read its fields by qualified name")
    void bareSource(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation("π products.product_name → n, products.details.maker → m (products)"))
                    .rows().hasRowCount(2).hasRow("Novel", "Acme").hasRow("Trowel", "Hoe Co");
            assertThat(relix.relation("σ products.product_name = \"Novel\" (products)"))
                    .hasRowCount(1);
        }
    }

    @Test
    @DisplayName("a renamed source answers to its new name")
    void renamedSource(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation("π P.product_name → n (σ P.product_id = 2 (ρ P (products)))"))
                    .rows().hasRowCount(1).hasRow("Trowel");
        }
    }

    @Test
    @DisplayName("a σ on the source's qualifier is pushed beneath the join, and still matches")
    void pushedBeneathAJoin(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            Relation both = relix.relation(
                    "π orders.quantity → q (σ products.product_name = \"Novel\" "
                    + "(orders ⨝ orders.product_id = products.product_id products))");
            assertThat(both.optimized()).rewrote(OptimizationCode.SEL_005)
                    .renders().contains("(σ products.product_name = \"Novel\" (products))");
            assertThat(both).rows().hasRowCount(2).hasRow("2").hasRow("1");

            Relation reversed = relix.relation(
                    "π orders.price → p (σ products.product_id = 2 "
                    + "(products ⨝ products.product_id = orders.product_id orders))");
            // Pushed by JOIN-002 on this side of the pipeline; which rule does it is not
            // the claim, where the σ lands is.
            assertThat(reversed.optimized()).renders()
                    .contains("(σ products.product_id = 2 (products))");
            assertThat(reversed).rows().hasRowCount(1).hasRow("5.5");
        }
    }

    @Test
    @DisplayName("a pair rename renames a document's field, and the old name is gone (#977)")
    void pairRename(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation(
                    "π title, products.title → q, product_name → old (ρ (product_name → title) (products))"))
                    .rows().hasRowCount(2).hasRow("Novel", "Novel", "NULL").hasRow("Trowel", "Trowel", "NULL");
            assertThat(relix.relation(
                    "π title (σ title = \"Trowel\" (ρ P (product_name → title) (products)))"))
                    .rows().hasRowCount(1).hasRow("Trowel");
        }
    }

    @Test
    @DisplayName("a pair rename over a join renames each side's field under its own relation")
    void pairRenameOverAJoin(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation(
                    "π products.t → t, orders.q → q (σ q = 3 (ρ (product_name → t, quantity → q) "
                    + "(orders ⨝ orders.product_id = products.product_id products)))"))
                    .rows().hasRowCount(1).hasRow("Trowel", "3");
        }
    }

    @Test
    @DisplayName("a pair rename onto a field the document carries is refused as the query runs")
    void pairRenameCollision(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.validate("query { ρ (product_name → product_id) (products) };")).isEmpty();
            assertThatThrownBy(() -> relix.relation("ρ (product_name → product_id) (products)").toList())
                    .hasMessageContaining("renaming 'product_name' to 'product_id' collides");
        }
    }

    @Test
    @DisplayName("an aggregate keyed by the source's qualifier groups its own values")
    void groupedByQualifiedKey(@TempDir Path directory) throws IOException {
        try (Relix relix = session(directory)) {
            assertThat(relix.relation(
                    "γ products.product_name → n, SUM(orders.quantity) → q "
                    + "(orders ⨝ orders.product_id = products.product_id products)"))
                    .rows().hasRowCount(2).hasRow("Novel", "3").hasRow("Trowel", "3");
        }
    }
}
