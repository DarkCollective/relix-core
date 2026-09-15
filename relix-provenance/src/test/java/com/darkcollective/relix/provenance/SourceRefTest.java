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
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SourceRef")
final class SourceRefTest {

    private static TreeMap<String, String> columns(String... kv) {
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("canonical name is <source>#<ordinal>")
    void canonicalName() {
        SourceRef ref = new SourceRef("Orders", 3, columns("id", "42"));
        assertThat(ref.canonicalName()).isEqualTo("Orders#3");
    }

    @Test
    @DisplayName("detailed label appends captured columns in key order")
    void detailedLabel() {
        SourceRef ref = new SourceRef("Orders", 1, columns("status", "OPEN", "id", "42"));
        assertThat(ref.detailedLabel()).isEqualTo("Orders#1{id: 42, status: OPEN}");
    }

    @Test
    @DisplayName("detailed label falls back to the canonical name with no columns")
    void detailedLabelNoColumns() {
        SourceRef ref = new SourceRef("Aggregation", 2, new TreeMap<>());
        assertThat(ref.detailedLabel()).isEqualTo("Aggregation#2");
    }

    @Test
    @DisplayName("a null column value renders as null in the detailed label")
    void nullColumnValue() {
        TreeMap<String, String> cols = new TreeMap<>();
        cols.put("note", null);
        SourceRef ref = new SourceRef("Orders", 1, cols);
        assertThat(ref.detailedLabel()).isEqualTo("Orders#1{note: null}");
        assertThat(ref.columns().get("note")).isNull();
    }

    @Test
    @DisplayName("columns are copied defensively and unmodifiable")
    void defensiveCopy() {
        TreeMap<String, String> src = columns("id", "42");
        SourceRef ref = new SourceRef("Orders", 1, src);
        src.put("id", "99");                       // mutate the original
        assertThat(ref.columns().get("id")).isEqualTo("42");
        assertThatThrownBy(() -> ref.columns().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects a null/blank source or null columns")
    void validation() {
        assertThatThrownBy(() -> new SourceRef(null, 1, new TreeMap<>()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceRef("  ", 1, new TreeMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> new SourceRef("Orders", 1, null))
                .isInstanceOf(NullPointerException.class);
    }
}
