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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.JsonValues;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocumentRow — open document-backed row with path access")
final class DocumentRowTest {

    private static DocumentRow doc(String json) {
        return new DocumentRow((StructValue) JsonValues.parse(json));
    }

    @Test
    @DisplayName("schema is open and get() resolves field paths null-propagatingly")
    void pathAccess() {
        DocumentRow row = doc("{\"id\": 1, \"user\": {\"name\": \"Alice\"}, \"tags\": [\"x\", \"y\"]}");
        assertThat(row.schema().isOpen()).isTrue();
        assertThat(row.get("id")).isEqualTo(new NumberValue(new BigDecimal("1")));
        assertThat(row.get("user.name")).isEqualTo(new StringValue("Alice"));
        assertThat(row.get("tags[1]")).isEqualTo(new StringValue("y"));
        assertThat(row.get("missing")).isEqualTo(NullValue.INSTANCE);       // miss → NULL, no throw
        assertThat(row.get("user.absent")).isEqualTo(NullValue.INSTANCE);
    }

    @Test
    @DisplayName("positional access and width follow the document's field order")
    void positionalAccessAndWidth() {
        DocumentRow row = doc("{\"a\": 1, \"b\": 2}");
        assertThat(row.width()).isEqualTo(2);
        assertThat(row.get(0)).isEqualTo(new NumberValue(new BigDecimal("1")));
        assertThat(row.get(1)).isEqualTo(new NumberValue(new BigDecimal("2")));
    }

    @Test
    @DisplayName("with() replaces an existing field (case-insensitively) or adds a new one")
    void withReplacesOrAdds() {
        DocumentRow row = doc("{\"Tag\": [\"x\", \"y\"], \"id\": 1}");
        DocumentRow replaced = row.with("tag", new StringValue("x"));   // case-insensitive replace
        assertThat(replaced.get("tag")).isEqualTo(new StringValue("x"));
        assertThat(replaced.get("id")).isEqualTo(new NumberValue(new BigDecimal("1")));   // untouched
        assertThat(row.get("Tag")).isInstanceOf(ArrayValue.class);       // original unchanged

        DocumentRow added = row.with("extra", new StringValue("z"));
        assertThat(added.get("extra")).isEqualTo(new StringValue("z"));
    }

    @Test
    @DisplayName("columnNames() reports the document's top-level fields in order")
    void columnNamesFollowDocumentOrder() {
        DocumentRow row = doc("{\"id\": 1, \"user\": {\"name\": \"Alice\"}, \"tags\": [\"x\"]}");
        assertThat(row.columnNames()).containsExactly("id", "user", "tags");
    }

    @Test
    @DisplayName("a document with an array field carries the array as a Value")
    void arrayFieldCarried() {
        DocumentRow row = doc("{\"items\": [10, 20]}");
        assertThat(row.get("items")).isEqualTo(new ArrayValue(List.of(
                new NumberValue(new BigDecimal("10")), new NumberValue(new BigDecimal("20")))));
    }

    @Test
    @DisplayName("document() hands back the backing struct, not a copy of its fields")
    void documentReturnsTheBackingStruct() {
        StructValue doc = new StructValue(Map.of("id", new NumberValue(BigDecimal.ONE)));
        assertThat(new DocumentRow(doc).document()).isSameAs(doc);
    }

    /**
     * The row a join assembles from {@code orders} (declared) and {@code products}
     * (open), both carrying {@code product_id} — so the right one is renamed
     * {@code product_id_r}, exactly as {@code ExecSupport.concatRows} names it (#970).
     */
    @Nested
    @DisplayName("field origins — a qualified reference into a joined document (#970)")
    class Origins {

        private static DocumentRow joined() {
            StructValue fields = (StructValue) JsonValues.parse("""
                    {"product_id": 1, "quantity": 2, "product_id_r": 7,
                     "details": {"maker": "Acme", "sizes": [3, 4]}, "orders": {"product_id": 99}}""");
            Map<String, List<ColumnProvenance>> origins = new LinkedHashMap<>();
            origins.put("product_id", List.of(new ColumnProvenance("orders", "product_id")));
            origins.put("quantity", List.of(new ColumnProvenance("orders", "quantity")));
            origins.put("product_id_r", List.of(new ColumnProvenance("products", "product_id")));
            origins.put("details", List.of(new ColumnProvenance("products", "details")));
            origins.put("orders", List.of(new ColumnProvenance("products", "orders")));
            return new DocumentRow(fields, origins);
        }

        private static NumberValue num(long n) {
            return new NumberValue(BigDecimal.valueOf(n));
        }

        @Test
        @DisplayName("a qualified name reads the field whose origin it is, not a struct path")
        void qualifiedNameReadsItsOwnField() {
            // Without origins this was a path into a field called `orders` — which this
            // document even has, holding 99. The relation reading wins, as it does for a
            // declared row.
            assertThat(joined().get("orders.product_id")).isEqualTo(num(1));
            assertThat(joined().get("ORDERS.Product_Id")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("a field the join renamed is still found under its own relation")
        void renamedFieldFoundByOrigin() {
            assertThat(joined().get("products.product_id")).isEqualTo(num(7));
        }

        @Test
        @DisplayName("a path continues into the field the qualifier names")
        void pathIntoQualifiedField() {
            assertThat(joined().get("products.details.maker")).isEqualTo(new StringValue("Acme"));
            assertThat(joined().get("products.details.sizes[1]")).isEqualTo(num(4));
        }

        @Test
        @DisplayName("an index follows the qualified field directly")
        void indexAfterQualifiedField() {
            StructValue fields = (StructValue) JsonValues.parse("{\"tags\": [\"a\", \"b\"]}");
            DocumentRow row = new DocumentRow(fields,
                    Map.of("tags", List.of(new ColumnProvenance("products", "tags"))));
            assertThat(row.get("products.tags[1]")).isEqualTo(new StringValue("b"));
        }

        @Test
        @DisplayName("a relation in scope with no such field reads as NULL, like any absent field")
        void relationInScopeWithoutField() {
            assertThat(joined().get("orders.price")).isEqualTo(NullValue.INSTANCE);
            // …even when the document happens to carry a top-level field of that name
            assertThat(joined().get("orders.details")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a name whose head is no relation in scope is still a path")
        void unknownHeadIsAPath() {
            assertThat(joined().get("details.maker")).isEqualTo(new StringValue("Acme"));
            assertThat(joined().get("quantity")).isEqualTo(num(2));
            assertThat(joined().get("shipments.id")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a relation name may itself contain a dot")
        void dottedRelationName() {
            StructValue fields = (StructValue) JsonValues.parse("{\"id\": 5}");
            DocumentRow row = new DocumentRow(fields,
                    Map.of("id", List.of(new ColumnProvenance("warehouse.orders", "id"))));
            assertThat(row.get("warehouse.orders.id")).isEqualTo(num(5));
            assertThat(row.get("warehouse.id")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("when two fields answer to one qualified name the first wins")
        void firstOfTwoOwnersWins() {
            // A self-join with no rename: both sides are `docs`.
            StructValue fields = (StructValue) JsonValues.parse("{\"id\": 1, \"id_r\": 2}");
            Map<String, List<ColumnProvenance>> origins = new LinkedHashMap<>();
            origins.put("id", List.of(new ColumnProvenance("docs", "id")));
            origins.put("id_r", List.of(new ColumnProvenance("docs", "id")));
            assertThat(new DocumentRow(fields, origins).get("docs.id")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("a field may answer to several relations")
        void severalRelationsPerField() {
            StructValue fields = (StructValue) JsonValues.parse("{\"id\": 3}");
            DocumentRow row = new DocumentRow(fields, Map.of("id", List.of(
                    new ColumnProvenance("a", "id"), new ColumnProvenance("b", "id"))));
            assertThat(row.get("a.id")).isEqualTo(num(3));
            assertThat(row.get("b.id")).isEqualTo(num(3));
        }

        @Test
        @DisplayName("an origin naming a field the document lacks reads as NULL")
        void originForAbsentField() {
            StructValue fields = (StructValue) JsonValues.parse("{\"id\": 3}");
            DocumentRow row = new DocumentRow(fields,
                    Map.of("gone", List.of(new ColumnProvenance("a", "gone"))));
            assertThat(row.get("a.gone")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a name ending or starting in a dot is never a qualified reference")
        void degenerateDots() {
            // Both fall through to path navigation, which ignores the stray dot.
            assertThat(joined().get("orders.")).isEqualTo(joined().get("orders"));
            assertThat(joined().get(".product_id")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("origins are exposed, copied, and kept by with()")
        void originsExposedAndKept() {
            DocumentRow row = joined();
            assertThat(row.origins()).containsKeys("product_id", "product_id_r");
            DocumentRow updated = row.with("quantity", num(5));
            assertThat(updated.origins()).isEqualTo(row.origins());
            assertThat(updated.get("orders.quantity")).isEqualTo(num(5));

            Map<String, List<ColumnProvenance>> mutable = new LinkedHashMap<>();
            mutable.put("id", new java.util.ArrayList<>(List.of(new ColumnProvenance("a", "id"))));
            DocumentRow copied = new DocumentRow(new StructValue(Map.of("id", num(1))), mutable);
            mutable.clear();
            assertThat(copied.origins()).containsKey("id");
        }

        @Test
        @DisplayName("reanchored() makes every field answer to the new relation, and only to it (#971)")
        void reanchored() {
            DocumentRow row = joined().reanchored("J");

            assertThat(row.get("J.product_id")).isEqualTo(num(1));
            assertThat(row.get("J.product_id_r")).isEqualTo(num(7));
            assertThat(row.get("J.details.maker")).isEqualTo(new StringValue("Acme"));
            // The relations the rename hid no longer answer — `orders` is a path again,
            // into the document's own `orders` field.
            assertThat(row.get("orders.product_id")).isEqualTo(num(99));
            assertThat(row.get("products.details")).isEqualTo(NullValue.INSTANCE);

            DocumentRow base = joined();
            assertThat(base.reanchored("K").document()).as("the document is shared, not copied")
                    .isSameAs(base.document());
        }

        @Test
        @DisplayName("reanchored() anchors a row read straight from a source to its relation (#972)")
        void reanchoredSourceRow() {
            DocumentRow row = doc("{\"id\": 4, \"addr\": {\"city\": \"Oslo\"}, \"tags\": [\"a\"]}")
                    .reanchored("P");

            assertThat(row.owner()).contains("P");
            assertThat(row.origins()).as("an owner, not a map per row").isEmpty();
            assertThat(row.get("P.id")).isEqualTo(num(4));
            assertThat(row.get("p.ID")).isEqualTo(num(4));
            assertThat(row.get("P.addr.city")).isEqualTo(new StringValue("Oslo"));
            assertThat(row.get("P.tags[0]")).isEqualTo(new StringValue("a"));
            assertThat(row.get("P.absent")).isEqualTo(NullValue.INSTANCE);
            assertThat(row.get("addr.city")).as("a path still reads as a path")
                    .isEqualTo(new StringValue("Oslo"));
            assertThat(row.get("Q.id")).as("another qualifier is a path, and misses")
                    .isEqualTo(NullValue.INSTANCE);
            assertThat(row.originsOf("id")).containsExactly(new ColumnProvenance("P", "id"));
        }

        @Test
        @DisplayName("a row with neither owner nor origins answers to no relation")
        void noOwner() {
            DocumentRow row = doc("{\"id\": 4}");
            assertThat(row.owner()).isEmpty();
            assertThat(row.originsOf("id")).isEmpty();
        }

        @Test
        @DisplayName("re-anchoring a joined row replaces the origins the join recorded")
        void reanchoringReplacesJoinOrigins() {
            StructValue fields = (StructValue) JsonValues.parse("{\"id\": 1, \"name\": \"x\"}");
            DocumentRow joined = new DocumentRow(fields,
                    Map.of("id", List.of(new ColumnProvenance("orders", "id"))));
            assertThat(joined.owner()).isEmpty();
            assertThat(joined.originsOf("id")).containsExactly(new ColumnProvenance("orders", "id"));
            assertThat(joined.originsOf("name")).isEmpty();

            DocumentRow owned = joined.reanchored("J").with("extra", num(9));

            assertThat(owned.owner()).contains("J");
            assertThat(owned.origins()).isEmpty();
            assertThat(owned.get("J.id")).isEqualTo(num(1));
            assertThat(owned.get("J.extra")).isEqualTo(num(9));
            assertThat(owned.get("orders.id")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("with() keeps the owner")
        void withKeepsOwner() {
            DocumentRow owned = doc("{\"id\": 1}").reanchored("P").with("id", num(2));
            assertThat(owned.owner()).contains("P");
            assertThat(owned.get("P.id")).isEqualTo(num(2));
        }

        @Test
        @DisplayName("renamed() renames the fields it names, in place, case-insensitively (#977)")
        void renamedRenamesInPlace() {
            DocumentRow row = doc("{\"id\": 1, \"Name\": \"Ada\", \"age\": 3}")
                    .renamed(Map.of("name", "full_name", "missing", "whatever"));

            assertThat(row.columnNames()).containsExactly("id", "full_name", "age");
            assertThat(row.get("full_name")).isEqualTo(new StringValue("Ada"));
            assertThat(row.get("Name")).isEqualTo(NullValue.INSTANCE);
            assertThat(row.get("whatever")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("renamed() returns the row itself when it carries nothing to rename")
        void renamedNothingToRename() {
            DocumentRow row = doc("{\"id\": 1}");
            assertThat(row.renamed(Map.of("missing", "x"))).isSameAs(row);
        }

        @Test
        @DisplayName("renamed() allows a swap, and refuses a name the document still carries")
        void renamedCollisions() {
            DocumentRow swapped = doc("{\"a\": 1, \"b\": 2}").renamed(Map.of("a", "b", "b", "a"));
            assertThat(swapped.get("a")).isEqualTo(num(2));
            assertThat(swapped.get("b")).isEqualTo(num(1));

            DocumentRow row = doc("{\"a\": 1, \"B\": 2}");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> row.renamed(Map.of("a", "b")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("renaming 'a' to 'b' collides with the field 'B' the document carries");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> doc("{\"b\": 2, \"a\": 1}").renamed(Map.of("a", "B")))
                    .as("the rename is named whichever field comes first")
                    .hasMessage("renaming 'a' to 'B' collides with the field 'b' the document carries");
        }

        @Test
        @DisplayName("renamed() carries an explicit origin to the new name, and keeps the owner")
        void renamedCarriesOrigins() {
            DocumentRow joined = joined().renamed(Map.of("product_id_r", "pid", "quantity", "qty"));

            assertThat(joined.get("products.pid")).isEqualTo(num(7));
            assertThat(joined.get("orders.qty")).isEqualTo(num(2));
            assertThat(joined.get("orders.quantity")).isEqualTo(NullValue.INSTANCE);
            assertThat(joined.get("orders.product_id")).as("an untouched field keeps its origin")
                    .isEqualTo(num(1));

            DocumentRow owned = doc("{\"id\": 1}").reanchored("P").renamed(Map.of("id", "key"));
            assertThat(owned.owner()).contains("P");
            assertThat(owned.get("P.key")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("reanchored() refuses a missing or blank relation")
        void reanchoredRefusesBlank() {
            DocumentRow row = doc("{\"id\": 1}");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> row.reanchored(null))
                    .isInstanceOf(NullPointerException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> row.reanchored(" "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a row read straight from a source has no origins")
        void sourceRowHasNone() {
            assertThat(doc("{\"id\": 1}").origins()).isEmpty();
        }
    }
}
