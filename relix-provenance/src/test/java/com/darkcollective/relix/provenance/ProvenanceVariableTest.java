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
package com.darkcollective.relix.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The identity rule the {@code ⊕}/{@code ⊗} algebra keys on.
 *
 * <p>A variable is its {@code name} and nothing else: a variable minted from a bare
 * label and one carrying a full {@link SourceRef} are the <em>same</em> variable when
 * their names agree. That is what lets the cheap weighted-closure path and the
 * capturing lift path meet in one polynomial, and it is the reason
 * {@link ProvenanceVariable#equals} is written by hand rather than left to the record.
 * Nothing tested it, so a record's default {@code equals} — which would compare the
 * source too, and silently split one variable in two — was indistinguishable from
 * the intended rule.
 */
@DisplayName("ProvenanceVariable")
final class ProvenanceVariableTest {

    private static SourceRef ref(String source, int ordinal, String key, String value) {
        TreeMap<String, String> columns = new TreeMap<>();
        columns.put(key, value);
        return new SourceRef(source, ordinal, columns);
    }

    @Nested
    @DisplayName("identity is the name alone")
    final class Identity {

        @Test
        @DisplayName("a captured source does not split a variable in two")
        void sourceIsNotIdentity() {
            ProvenanceVariable bare = new ProvenanceVariable("Orders#1");
            ProvenanceVariable captured = ProvenanceVariable.of(ref("Orders", 1, "id", "42"));

            assertThat(captured.name()).isEqualTo(bare.name());
            assertThat(captured).isEqualTo(bare);
            assertThat(bare).isEqualTo(captured);
            assertThat(captured).hasSameHashCodeAs(bare);
        }

        @Test
        @DisplayName("two captures of the same row differing only in columns are one variable")
        void columnsAreNotIdentity() {
            assertThat(ProvenanceVariable.of(ref("Orders", 1, "id", "42")))
                    .isEqualTo(ProvenanceVariable.of(ref("Orders", 1, "status", "OPEN")));
        }

        @Test
        @DisplayName("a different name is a different variable")
        void nameSeparates() {
            assertThat(new ProvenanceVariable("Orders#1"))
                    .isNotEqualTo(new ProvenanceVariable("Orders#2"))
                    .isNotEqualTo(new ProvenanceVariable("Customers#1"));
        }

        @Test
        @DisplayName("nothing of another type is a variable, null included")
        void otherTypes() {
            ProvenanceVariable x = new ProvenanceVariable("Orders#1");
            assertThat(x).isNotEqualTo("Orders#1").isNotEqualTo(null);
            assertThat(x).isEqualTo(x);
        }
    }

    @Nested
    @DisplayName("ordering is by name, which is what renders a monomial canonically")
    final class Ordering {

        @Test
        @DisplayName("orders by name and agrees with equality")
        void byName() {
            ProvenanceVariable a = new ProvenanceVariable("Alpha#1");
            ProvenanceVariable b = new ProvenanceVariable("Beta#1");

            assertThat(a).isLessThan(b);
            assertThat(b).isGreaterThan(a);
            assertThat(a.compareTo(ProvenanceVariable.of(ref("Alpha", 1, "id", "7"))))
                    .as("a source is no more part of the order than it is of the identity")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("a label")
    final class Labels {

        @Test
        @DisplayName("is the bare name unless the detail is asked for and captured")
        void detail() {
            ProvenanceVariable captured = ProvenanceVariable.of(ref("Orders", 1, "id", "42"));
            assertThat(captured.label(false)).isEqualTo("Orders#1");
            assertThat(captured.label(true)).isEqualTo("Orders#1{id: 42}");

            ProvenanceVariable bare = new ProvenanceVariable("Orders#1");
            assertThat(bare.label(true))
                    .as("there is no detail to add, so the detailed label is the plain one")
                    .isEqualTo("Orders#1");
        }
    }

    @Nested
    @DisplayName("a variable must be nameable")
    final class Validation {

        @Test
        @DisplayName("a blank or absent name is refused")
        void blankName() {
            assertThatThrownBy(() -> new ProvenanceVariable("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
            assertThatThrownBy(() -> new ProvenanceVariable(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ProvenanceVariable("x", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
