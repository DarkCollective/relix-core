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
package com.darkcollective.relix.symbol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Schema — column list validation and case-insensitive lookup")
final class SchemaTest extends SymbolTestSupport {

    @Test
    @DisplayName("Stores columns in declaration order")
    void storesColumnsInOrder() {
        Schema schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        assertThat(schema.columns()).hasSize(2);
        assertThat(schema.columns().get(0).name()).isEqualTo("id");
        assertThat(schema.columns().get(1).name()).isEqualTo("name");
    }

    @Test
    @DisplayName("width() returns column count")
    void widthReturnsColumnCount() {
        assertThat(schema("a", "b").width()).isEqualTo(2);
        assertThat(schema("x").width()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejects empty column list")
    void rejectsEmptyColumnList() {
        assertThatThrownBy(() -> new Schema(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one column");
    }

    @Test
    @DisplayName("Rejects null column list")
    void rejectsNullColumnList() {
        assertThatThrownBy(() -> new Schema(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects duplicate column names (case-insensitive)")
    void rejectsDuplicateColumnNamesCaseInsensitive() {
        assertThatThrownBy(() -> new Schema(List.of(
                new ColumnDefinition("UserId", ScalarType.NUMBER),
                new ColumnDefinition("userid", ScalarType.STRING))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate column name");
    }

    @Test
    @DisplayName("column() finds by exact name")
    void columnLookupByExactName() {
        Schema schema = schema(col("id", ScalarType.NUMBER));
        assertThat(schema.column("id")).isPresent()
                .get().extracting(ColumnDefinition::name).isEqualTo("id");
    }

    @Test
    @DisplayName("column() lookup is case-insensitive")
    void columnLookupIsCaseInsensitive() {
        Schema schema = schema(col("UserId", ScalarType.NUMBER));
        assertThat(schema.column("userid")).isPresent();
        assertThat(schema.column("USERID")).isPresent();
        assertThat(schema.column("UserId")).isPresent();
    }

    @Test
    @DisplayName("column() returns empty for unknown name")
    void columnLookupReturnsEmptyForUnknown() {
        Schema schema = schema("id");
        assertThat(schema.column("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("columns list is unmodifiable")
    void columnsListIsUnmodifiable() {
        Schema schema = schema("id");
        assertThatThrownBy(() -> schema.columns().add(new ColumnDefinition("x", ScalarType.ANY)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Mutating the source list does not affect schema")
    void sourceListMutationDoesNotAffectSchema() {
        List<ColumnDefinition> source = new ArrayList<>();
        source.add(new ColumnDefinition("id", ScalarType.NUMBER));
        Schema schema = new Schema(source);
        source.add(new ColumnDefinition("extra", ScalarType.STRING));
        assertThat(schema.width()).isEqualTo(1);
    }

    @Test
    @DisplayName("indexOf returns the column position, case-insensitively, or -1")
    void returnsColumnPositionOrMinusOne() {
        Schema schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        assertThat(schema.indexOf("id")).isEqualTo(0);
        assertThat(schema.indexOf("NAME")).isEqualTo(1);
        assertThat(schema.indexOf("missing")).isEqualTo(-1);
    }

    @Test
    @DisplayName("equal column lists imply equal schemas and equal hash codes")
    void equalColumnsImplyEqualSchemasAndHashCodes() {
        Schema a = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        Schema b = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        Schema different = schema(col("id", ScalarType.NUMBER));

        assertThat(a).isEqualTo(b).isNotEqualTo(different);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("hashCode is stable across repeated calls (caching is transparent)")
    void returnsStableHashCodeAcrossCalls() {
        Schema schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        int first = schema.hashCode();
        assertThat(schema.hashCode()).isEqualTo(first);
        assertThat(schema.hashCode()).isEqualTo(first);
        assertThat(schema.hashCode()).isEqualTo(schema.columns().hashCode());
    }

    // ── open (schema-on-read) schemas ────────────────────────────────────────

    @Test
    @DisplayName("an open schema has no fixed columns and resolves every name to ANY")
    void openSchemaResolvesAnyName() {
        Schema open = Schema.open();
        assertThat(open.isOpen()).isTrue();
        assertThat(open.columns()).isEmpty();
        assertThat(open.width()).isZero();
        assertThat(open.column("anything")).isPresent()
                .map(ColumnDefinition::type).contains(ScalarType.ANY);
        assertThat(open.column("user.name")).isPresent()
                .map(ColumnDefinition::type).contains(ScalarType.ANY);
        assertThat(open.indexOf("anything")).isEqualTo(-1);   // no fixed position
    }

    @Test
    @DisplayName("a closed schema is not open and still reports missing columns")
    void closedSchemaUnaffected() {
        Schema closed = schema(col("id", ScalarType.NUMBER));
        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.column("missing")).isEmpty();
    }

    @Test
    @DisplayName("open schemas are equal to each other and distinct from any closed schema")
    void openEquality() {
        assertThat(Schema.open()).isEqualTo(Schema.open());
        assertThat(Schema.open()).isNotEqualTo(schema(col("id", ScalarType.NUMBER)));
        // distinct from the single-ANY-column 'unresolved' placeholder shape, too
        assertThat(Schema.open()).isNotEqualTo(schema(col("*", ScalarType.ANY)));
    }

    // ── empty (closed, zero-column) schema — the nullary truth heading ────────

    @Test
    @DisplayName("the empty schema is closed, has no columns, and resolves no name")
    void emptySchemaHasNoColumns() {
        Schema empty = Schema.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.isOpen()).isFalse();
        assertThat(empty.columns()).isEmpty();
        assertThat(empty.width()).isZero();
        assertThat(empty.column("anything")).isEmpty();   // resolves nothing (unlike open)
        assertThat(empty.indexOf("anything")).isEqualTo(-1);
    }

    @Test
    @DisplayName("the empty schema is distinct from the open schema and from any populated schema")
    void emptyIsDistinctFromOpenAndPopulated() {
        assertThat(Schema.empty()).isEqualTo(Schema.empty());
        // Both are zero-column, but they mean opposite things and must not be equal.
        assertThat(Schema.empty()).isNotEqualTo(Schema.open());
        assertThat(Schema.open()).isNotEqualTo(Schema.empty());
        assertThat(Schema.empty()).isNotEqualTo(schema(col("id", ScalarType.NUMBER)));
    }

    @Test
    @DisplayName("isEmpty is false for open and for populated schemas")
    void isEmptyOnlyForTheEmptySchema() {
        assertThat(Schema.open().isEmpty()).isFalse();
        assertThat(schema(col("id", ScalarType.NUMBER)).isEmpty()).isFalse();
    }

    // ── concat of column-less headings ──────────────────────────────────────────

    @Test
    @DisplayName("concat of two open schemas is open, not a rejected empty column list")
    void concatOfTwoOpenSchemasIsOpen() {
        Schema joined = Schema.open().concat(Schema.open());
        assertThat(joined.isOpen()).isTrue();
        assertThat(joined.column("anything")).isPresent();
    }

    @Test
    @DisplayName("concat keeps openness whichever side carries it")
    void concatWithOneOpenSideIsOpen() {
        assertThat(Schema.open().concat(Schema.empty()).isOpen()).isTrue();
        assertThat(Schema.empty().concat(Schema.open()).isOpen()).isTrue();
    }

    @Test
    @DisplayName("concat of two empty schemas is the empty schema — a join of nullary relations")
    void concatOfTwoEmptySchemasIsEmpty() {
        Schema joined = Schema.empty().concat(Schema.empty());
        assertThat(joined.isEmpty()).isTrue();
        assertThat(joined.isOpen()).isFalse();
        assertThat(joined.column("anything")).isEmpty();
    }

    @Test
    @DisplayName("concat with a column-less side still yields the other side's columns")
    void concatWithOneColumnLessSideKeepsColumns() {
        Schema typed = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        assertThat(Schema.empty().concat(typed).columns()).isEqualTo(typed.columns());
        assertThat(typed.concat(Schema.empty()).columns()).isEqualTo(typed.columns());
    }

    @Test
    @DisplayName("concat of an open side with a typed one stays open — the columns are known, not all of them")
    void concatOfOpenWithTypedStaysOpen() {
        // What a join between a schema-on-read source and a declared relation
        // produces. Dropping the flag here made every reference to a column of the
        // dynamic side an analysis error, and made the join fail at runtime: the
        // executor concatenates both rows and the heading claimed only one side's
        // width.
        Schema typed = schema(col("dst", ScalarType.NUMBER), col("depth", ScalarType.NUMBER));

        Schema joined = Schema.open().concat(typed);

        assertThat(joined.isOpen()).isTrue();
        assertThat(joined.columns()).isEqualTo(typed.columns());
        assertThat(typed.concat(Schema.open()).isOpen()).isTrue();
    }

    @Test
    @DisplayName("a declared column of an open heading keeps its own type")
    void openHeadingAnswersForItsDeclaredColumnsFirst() {
        Schema typed = schema(col("depth", ScalarType.NUMBER));

        Schema joined = Schema.open().concat(typed);

        assertThat(joined.column("depth")).get()
                .as("a heading that knows a column's type must not forget it because "
                    + "the relation beside it is dynamic")
                .extracting(ColumnDefinition::type).isEqualTo(ScalarType.NUMBER);
        assertThat(joined.column("holder")).get()
                .as("anything it does not name comes from the document, so it resolves "
                    + "at runtime")
                .extracting(ColumnDefinition::type).isEqualTo(ScalarType.ANY);
    }

    @Test
    @DisplayName("an open heading declares the columns it knows — appending one of them would collide")
    void openHeadingDeclaresItsKnownColumns() {
        Schema joined = Schema.open().concat(schema(col("depth", ScalarType.NUMBER)));

        assertThat(joined.declares("depth")).isTrue();
        assertThat(joined.declares("holder"))
                .as("resolving a name is not declaring it — an operator that appends a "
                    + "column may still use this one")
                .isFalse();
        assertThat(Schema.open().declares("anything")).isFalse();
    }

    // ── column provenance & qualified resolution (#454) ─────────────────────────

    private static ColumnDefinition col(String name, ScalarType type, String rel, String origin) {
        return new ColumnDefinition(name, type, new ColumnProvenance(rel, origin));
    }

    @Test
    @DisplayName("concat preserves right-side provenance while renaming the physical name")
    void concatPreservesProvenance() {
        Schema devices = schema(
                col("device_id", ScalarType.NUMBER, "devices", "device_id"),
                col("name",      ScalarType.STRING, "devices", "name"));
        Schema rooms = schema(
                col("room_id", ScalarType.NUMBER, "rooms", "room_id"),
                col("name",    ScalarType.STRING, "rooms", "name"));

        Schema joined = devices.concat(rooms);

        // The right-side `name` is physically renamed to `name_r` …
        assertThat(joined.columns().stream().map(ColumnDefinition::name))
                .containsExactly("device_id", "name", "room_id", "name_r");
        // … but its origin is still (rooms, name).
        ColumnDefinition nameR = joined.columns().get(3);
        assertThat(nameR.name()).isEqualTo("name_r");
        assertThat(nameR.provenance()).isEqualTo(new ColumnProvenance("rooms", "name"));
    }

    @Test
    @DisplayName("qualifiedIndices resolves each side of a collision precisely")
    void qualifiedIndicesResolvesCollision() {
        Schema joined = schema(
                col("name", ScalarType.STRING, "devices", "name"),
                col("name_r", ScalarType.STRING, "rooms", "name"));

        assertThat(joined.qualifiedIndices("devices", "name")).containsExactly(0);
        assertThat(joined.qualifiedIndices("rooms", "name")).containsExactly(1);
        assertThat(joined.qualifiedIndices("rooms", "NAME")).containsExactly(1); // case-insensitive
        assertThat(joined.qualifiedIndices("kitchen", "name")).isEmpty();        // stale qualifier
    }

    @Test
    @DisplayName("hasProvenance reflects whether any column carries an origin")
    void hasProvenance() {
        assertThat(schema(col("id", ScalarType.NUMBER)).hasProvenance()).isFalse();
        assertThat(schema(col("id", ScalarType.NUMBER, "T", "id")).hasProvenance()).isTrue();
    }

    @Test
    @DisplayName("qualifiedIndices on an open schema is empty (no provenance to match)")
    void qualifiedIndicesOpenSchema() {
        assertThat(Schema.open().qualifiedIndices("R", "c")).isEmpty();
    }

    @Nested
    @DisplayName("declares — is this name taken?")
    class Declares {

        @Test
        @DisplayName("true for a column the schema names, false for one it does not")
        void closedSchema() {
            Schema schema = new Schema(List.of(
                    new ColumnDefinition("id", ScalarType.NUMBER),
                    new ColumnDefinition("name", ScalarType.STRING)));
            assertThat(schema.declares("id")).isTrue();
            assertThat(schema.declares("ID")).isTrue();          // names are case-insensitive
            assertThat(schema.declares("missing")).isFalse();
        }

        @Test
        @DisplayName("false for everything on an open schema, where column() answers ANY")
        void openSchema() {
            // The distinction this method exists for. An open relation resolves any
            // name for a *read*, because the shape arrives with the row — but it
            // declares nothing, so no name is taken and an operator may add one.
            assertThat(Schema.open().column("anything")).isPresent();
            assertThat(Schema.open().declares("anything")).isFalse();
        }

        @Test
        @DisplayName("false on the empty schema, which declares nothing either")
        void emptySchema() {
            assertThat(Schema.empty().declares("anything")).isFalse();
        }
    }
}
