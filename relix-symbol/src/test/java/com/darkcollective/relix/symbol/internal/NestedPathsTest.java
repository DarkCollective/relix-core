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
package com.darkcollective.relix.symbol.internal;

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule two modules got wrong independently, now stated once.
 *
 * <p>Its subject is not path resolution but path resolution used as <em>evidence</em>
 * about which of two headings was meant. Those differ exactly where a schema is open,
 * which is the case both the optimizer and the executor mishandled.
 */
@DisplayName("NestedPaths — which side of a join a dotted reference reaches into")
final class NestedPathsTest {

    private static Schema nested(String column, String... fields) {
        return new Schema(List.of(new ColumnDefinition(column,
                new StructType(java.util.Arrays.stream(fields)
                        .map(f -> new StructType.Field(f, ScalarType.STRING))
                        .toList()))));
    }

    private static Schema flat(String... columns) {
        return new Schema(java.util.Arrays.stream(columns)
                .map(c -> new ColumnDefinition(c, ScalarType.STRING))
                .toList());
    }

    @Nested
    @DisplayName("between two closed headings")
    final class Closed {

        @Test
        @DisplayName("the side whose column is the path's head owns it")
        void headDecides() {
            assertThat(NestedPaths.ownerOf("location.city", nested("location", "city"), flat("city")))
                    .as("the tail is a column of the right, and that is not what decides")
                    .isEqualTo(NestedPaths.Owner.LEFT);
            assertThat(NestedPaths.ownerOf("location.city", flat("city"), nested("location", "city")))
                    .isEqualTo(NestedPaths.Owner.RIGHT);
        }

        @Test
        @DisplayName("a path both sides carry says nothing about which was meant")
        void both() {
            assertThat(NestedPaths.ownerOf("location.city",
                    nested("location", "city"), nested("location", "city")))
                    .isEqualTo(NestedPaths.Owner.BOTH);
        }

        @Test
        @DisplayName("a name that is not a path at all belongs to neither")
        void neither() {
            assertThat(NestedPaths.ownerOf("location.postcode",
                    nested("location", "city"), flat("postcode")))
                    .as("the struct has no such field, so this is not a path into it")
                    .isEqualTo(NestedPaths.Owner.NEITHER);
            assertThat(NestedPaths.ownerOf("city", flat("city"), flat("other")))
                    .as("an undotted name is not a path either, however plainly it is a column")
                    .isEqualTo(NestedPaths.Owner.LEFT);
        }
    }

    @Nested
    @DisplayName("with a schema-on-read heading")
    final class Open {

        @Test
        @DisplayName("an open side owns nothing, and does not take the other's path")
        void openOwnsNothing() {
            assertThat(NestedPaths.ownerOf("location.city", nested("location", "city"), Schema.open()))
                    .as("an open heading resolves every name, so its yes is no evidence")
                    .isEqualTo(NestedPaths.Owner.LEFT);
            assertThat(NestedPaths.ownerOf("location.city", Schema.open(), nested("location", "city")))
                    .isEqualTo(NestedPaths.Owner.RIGHT);
        }

        @Test
        @DisplayName("two open sides resolve nothing between them")
        void bothOpen() {
            assertThat(NestedPaths.ownerOf("location.city", Schema.open(), Schema.open()))
                    .isEqualTo(NestedPaths.Owner.NEITHER);
        }

        @Test
        @DisplayName("asking one open heading is still right, and is a different question")
        void singleSchemaResolutionIsUnchanged() {
            assertThat(Schema.open().resolvePath("location.city"))
                    .as("an open source really can carry it, and it types as ANY — which is "
                            + "what inference needs and what this class must not change")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("every argument is required")
    void nullArguments() {
        Schema any = flat("a");
        assertThatThrownBy(() -> NestedPaths.ownerOf(null, any, any))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NestedPaths.ownerOf("a.b", null, any))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NestedPaths.ownerOf("a.b", any, null))
                .isInstanceOf(NullPointerException.class);
    }
}
