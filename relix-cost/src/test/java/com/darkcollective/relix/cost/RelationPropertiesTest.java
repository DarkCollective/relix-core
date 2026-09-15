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
package com.darkcollective.relix.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelationProperties — distinctness and boundedness carrier")
final class RelationPropertiesTest {

    @Test
    @DisplayName("none() is not duplicate-free and carries no keys")
    void none() {
        RelationProperties p = RelationProperties.none();
        assertThat(p.isDuplicateFree()).isFalse();
        assertThat(p.wholeRowDistinct()).isFalse();
        assertThat(p.keys()).isEmpty();
    }

    @Test
    @DisplayName("wholeRow() is duplicate-free via whole-row distinctness, with no enumerated key")
    void wholeRow() {
        RelationProperties p = RelationProperties.wholeRow();
        assertThat(p.isDuplicateFree()).isTrue();
        assertThat(p.wholeRowDistinct()).isTrue();
        assertThat(p.keys()).isEmpty();
    }

    @Test
    @DisplayName("key(cols) is duplicate-free with that single candidate key, lowercased")
    void key() {
        RelationProperties p = RelationProperties.key(List.of("Customer_Id", "REGION"));
        assertThat(p.isDuplicateFree()).isTrue();
        assertThat(p.wholeRowDistinct()).isFalse();
        assertThat(p.keys()).containsExactly(Set.of("customer_id", "region"));
    }

    @Test
    @DisplayName("an empty key denotes an at-most-one-row relation and is still duplicate-free")
    void emptyKey() {
        RelationProperties p = RelationProperties.key(List.of());
        assertThat(p.isDuplicateFree()).isTrue();
        assertThat(p.keys()).containsExactly(Set.of());
    }

    @Test
    @DisplayName("equals and hashCode are value-based")
    void valueEquality() {
        assertThat(RelationProperties.wholeRow()).isEqualTo(RelationProperties.wholeRow());
        assertThat(RelationProperties.key(List.of("a"))).isEqualTo(RelationProperties.key(List.of("A")));
        assertThat(RelationProperties.key(List.of("a")).hashCode())
                .isEqualTo(RelationProperties.key(List.of("A")).hashCode());
        assertThat(RelationProperties.none()).isNotEqualTo(RelationProperties.wholeRow());
        assertThat(RelationProperties.wholeRow()).isNotEqualTo(RelationProperties.key(List.of("a")));
        assertThat(RelationProperties.none()).isNotEqualTo("not a properties");
    }

    @Test
    @DisplayName("equals reads the keys and the boundedness, not only whole-row distinctness")
    void equalityReadsEveryComponent() {
        // wholeRow() vs key(…) differ in the first component too, so the && short-circuits
        // and never shows that the later components are compared at all.
        assertThat(RelationProperties.key(List.of("a")))
                .as("same distinctness, different keys")
                .isNotEqualTo(RelationProperties.key(List.of("b")));
        assertThat(RelationProperties.key(List.of("a")))
                .as("same distinctness and keys, different boundedness")
                .isNotEqualTo(RelationProperties.key(List.of("a"))
                        .withBoundedness(Boundedness.UNBOUNDED));
        assertThat(RelationProperties.none()).isEqualTo(RelationProperties.none());
    }

    @Test
    @DisplayName("toString names all three components")
    void toStringNamesEveryComponent() {
        assertThat(RelationProperties.key(List.of("a")).toString())
                .contains("wholeRowDistinct=")
                .contains("keys=")
                .contains("boundedness=BOUNDED");
    }

    @Test
    @DisplayName("the distinctness factories default boundedness to BOUNDED")
    void defaultsToBounded() {
        assertThat(RelationProperties.none().boundedness()).isEqualTo(Boundedness.BOUNDED);
        assertThat(RelationProperties.wholeRow().boundedness()).isEqualTo(Boundedness.BOUNDED);
        assertThat(RelationProperties.key(List.of("a")).boundedness()).isEqualTo(Boundedness.BOUNDED);
    }

    @Test
    @DisplayName("withBoundedness overlays the boundedness, preserving distinctness")
    void withBoundedness() {
        RelationProperties unbounded =
                RelationProperties.wholeRow().withBoundedness(Boundedness.UNBOUNDED);
        assertThat(unbounded.boundedness()).isEqualTo(Boundedness.UNBOUNDED);
        assertThat(unbounded.wholeRowDistinct()).isTrue();          // distinctness preserved
        assertThat(unbounded.isDuplicateFree()).isTrue();
        // boundedness participates in equality
        assertThat(unbounded).isNotEqualTo(RelationProperties.wholeRow());
        // setting the same boundedness returns the same instance
        RelationProperties same = RelationProperties.wholeRow();
        assertThat(same.withBoundedness(Boundedness.BOUNDED)).isSameAs(same);
    }
}
