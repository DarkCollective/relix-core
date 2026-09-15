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
package com.darkcollective.relix.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two numbers are the same value when they are numerically equal.
 *
 * <p>The record's own {@code equals} is {@link BigDecimal#equals}, which is
 * scale-sensitive, and comparison has always been numeric. Leaving identity to the
 * record left the engine holding two incompatible answers to <em>are these the same
 * number</em>, and which one applied depended on the operator: a σ kept both rows while
 * a δ kept them apart, so DISTINCT returned the same displayed number twice.
 *
 * <p>What is asserted here is the agreement itself, at the level everything that hashes
 * a value reads it — a set, a map key, a row.
 */
@DisplayName("NumberValue — identity is numeric, not textual")
final class NumberIdentityTest {

    private static NumberValue n(String text) {
        return NumberValue.of(text);
    }

    @Nested
    @DisplayName("equality")
    final class Equality {

        @Test
        @DisplayName("scale is not part of it")
        void scaleIsNotIdentity() {
            assertThat(n("5")).isEqualTo(n("5.0")).isEqualTo(n("5.00")).isEqualTo(n("05"));
            assertThat(n("5")).hasSameHashCodeAs(n("5.0"));
            assertThat(n("0")).isEqualTo(n("0.0")).isEqualTo(n("0.000"));
            assertThat(n("0")).hasSameHashCodeAs(n("0.000"));
            assertThat(n("500")).isEqualTo(n("5E+2"));
            assertThat(n("500")).hasSameHashCodeAs(n("5E+2"));
        }

        @Test
        @DisplayName("different numbers are still different")
        void differentNumbersDiffer() {
            assertThat(n("5")).isNotEqualTo(n("5.1")).isNotEqualTo(n("-5")).isNotEqualTo(n("50"));
        }

        @Test
        @DisplayName("nothing of another kind is a number")
        void otherKinds() {
            assertThat(n("5")).isNotEqualTo(new StringValue("5")).isNotEqualTo(null);
            assertThat(n("5")).isEqualTo(n("5"));
        }

        @Test
        @DisplayName("the scale itself survives — it is not identity, and not discarded either")
        void scaleIsKept() {
            assertThat(n("5.00").value().scale())
                    .as("what the source supplied round-trips; it simply does not decide identity")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("what reads it")
    final class Consumers {

        @Test
        @DisplayName("a set holds one of them")
        void set() {
            assertThat(new java.util.HashSet<>(List.of(n("5"), n("5.0"), n("5.00")))).hasSize(1);
        }

        @Test
        @DisplayName("a map keyed by one finds the other")
        void mapKey() {
            assertThat(new java.util.HashMap<>(Map.of(n("5.0"), "found")).get(n("5")))
                    .isEqualTo("found");
        }

        @Test
        @DisplayName("a nested value inherits it, so a struct and an array agree too")
        void nested() {
            assertThat(new StructValue(Map.of("x", n("5"))))
                    .isEqualTo(new StructValue(Map.of("x", n("5.000"))));
            assertThat(new ArrayValue(List.of(n("5"), n("2"))))
                    .isEqualTo(new ArrayValue(List.of(n("5.0"), n("2.00"))));
        }
    }
}
