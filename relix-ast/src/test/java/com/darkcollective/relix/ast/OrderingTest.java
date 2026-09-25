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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ordering — delivered/required sort order with prefix satisfaction")
final class OrderingTest {

    private static Ordering ord(SortSpecification... keys) { return Ordering.of(List.of(keys)); }

    @Test
    @DisplayName("none() is the empty ordering")
    void none() {
        assertThat(Ordering.none().keys()).isEmpty();
        assertThat(Ordering.of(List.of())).isEqualTo(Ordering.none());
    }

    @Test
    @DisplayName("a delivered order satisfies a required order that is a prefix of it")
    void prefixSatisfies() {
        Ordering deliveredXY = ord(asc("x"), asc("y"));
        assertThat(deliveredXY.satisfies(ord(asc("x")))).isTrue();           // prefix
        assertThat(deliveredXY.satisfies(ord(asc("x"), asc("y")))).isTrue(); // equal
        assertThat(deliveredXY.satisfies(Ordering.none())).isTrue();         // empty prefix
    }

    @Test
    @DisplayName("a shorter delivered order does not satisfy a longer requirement")
    void notSatisfiedWhenRequirementLonger() {
        assertThat(ord(asc("x")).satisfies(ord(asc("x"), asc("y")))).isFalse();
    }

    @Test
    @DisplayName("direction must match — ASC does not satisfy a DESC requirement")
    void directionMustMatch() {
        assertThat(ord(asc("x")).satisfies(ord(desc("x")))).isFalse();
    }

    @Test
    @DisplayName("a different lead column does not satisfy")
    void differentColumn() {
        assertThat(ord(asc("x"), asc("y")).satisfies(ord(asc("y")))).isFalse();
    }

    @Test
    @DisplayName("clusters() is true iff every column is an ordering key (direction irrelevant)")
    void clusters() {
        Ordering deliveredXY = ord(asc("x"), desc("y"));
        assertThat(deliveredXY.clusters(List.of("x", "y"))).isTrue();   // both covered
        assertThat(deliveredXY.clusters(List.of("y", "x"))).isTrue();   // order irrelevant
        assertThat(deliveredXY.clusters(List.of("x"))).isTrue();        // subset covered
        assertThat(deliveredXY.clusters(List.of())).isTrue();           // nothing to cover
        assertThat(deliveredXY.clusters(List.of("x", "z"))).isFalse();  // z not a key
        assertThat(Ordering.none().clusters(List.of("x"))).isFalse();   // empty order covers nothing
    }

    @Test
    @DisplayName("groupsBy() is true iff the leading keys are exactly the grouping-key set")
    void groupsBy() {
        Ordering deliveredXYZ = ord(asc("x"), asc("y"), asc("z"));
        assertThat(deliveredXYZ.groupsBy(List.of("x"))).isTrue();         // leading {x}
        assertThat(deliveredXYZ.groupsBy(List.of("x", "y"))).isTrue();    // leading {x,y}
        assertThat(deliveredXYZ.groupsBy(List.of("y", "x"))).isTrue();    // order irrelevant
        assertThat(deliveredXYZ.groupsBy(List.of("x", "z"))).isFalse();   // leading prefix is {x,y}, not {x,z}
        assertThat(deliveredXYZ.groupsBy(List.of())).isFalse();           // scalar aggregate never groups
        assertThat(ord(asc("x")).groupsBy(List.of("x", "y"))).isFalse();  // fewer keys than required
        assertThat(Ordering.none().groupsBy(List.of("x"))).isFalse();
    }

    @Test
    @DisplayName("equals and hashCode are value-based over the key list")
    void valueEquality() {
        assertThat(ord(asc("x"), desc("y"))).isEqualTo(ord(asc("x"), desc("y")));
        assertThat(ord(asc("x"), desc("y")).hashCode()).isEqualTo(ord(asc("x"), desc("y")).hashCode());
        assertThat(ord(asc("x"))).isNotEqualTo(ord(desc("x")));
        assertThat(Ordering.none()).isNotEqualTo("not an ordering");
    }

    @Test
    @DisplayName("toString names the keys — it is what a plan dump prints")
    void toStringNamesTheKeys() {
        assertThat(Ordering.none().toString()).isEqualTo("Ordering[]");
        assertThat(Ordering.of(List.of(asc("a"))).toString())
                .contains("a");
    }
}
