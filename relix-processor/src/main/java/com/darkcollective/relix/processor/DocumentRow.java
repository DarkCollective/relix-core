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

import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.ValuePath;
import com.darkcollective.relix.symbol.Schema;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link Row} backed by a nested {@link StructValue} document — the row shape
 * produced by an <em>open</em> (schema-on-read) source such as the JSON file
 * connector.
 *
 * <p>Unlike {@link ArrayRow} (fixed positional columns under a closed schema), a
 * document row has no fixed columns: {@link #schema()} is {@linkplain Schema#open()
 * open}, and {@link #get(String)} resolves a name as a <strong>null-propagating
 * path</strong> into the document ({@code user.name}, {@code items[0]}), returning
 * {@link com.darkcollective.relix.value.NullValue} for any miss rather
 * than throwing.  This is what lets heterogeneous documents be queried by path
 * without a declared schema.
 */
public final class DocumentRow implements Row {

    private static final Schema OPEN = Schema.open();

    private final StructValue document;

    /**
     * @param document the backing document; must not be null
     */
    public DocumentRow(StructValue document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    /** {@return the backing document} */
    public StructValue document() {
        return document;
    }

    @Override
    public Schema schema() {
        return OPEN;
    }

    /**
     * Resolves {@code name} as a path into the document — {@code "field"},
     * {@code "a.b"}, {@code "items[0].price"} — yielding {@code NullValue} on any miss.
     */
    @Override
    public Value get(String name) {
        return ValuePath.navigate(document, name);
    }

    @Override
    public Value get(int index) {
        int i = 0;
        for (Value value : document.fields().values()) {
            if (i++ == index) {
                return value;
            }
        }
        throw new IndexOutOfBoundsException("index " + index + " for a " + width() + "-field document");
    }

    @Override
    public int width() {
        return document.fields().size();
    }

    /**
     * Returns the document's top-level field names in document order — the
     * dynamically-discovered columns of this open row.
     */
    @Override
    public java.util.List<String> columnNames() {
        return java.util.List.copyOf(document.fields().keySet());
    }

    /**
     * Returns a copy of this document row with the top-level field {@code field}
     * set to {@code value} (replacing an existing field case-insensitively, or
     * adding it).  Used by unnest to bind the exploded element back into the row.
     *
     * @param field the field name
     * @param value the new value
     * @return a new document row
     */
    public DocumentRow with(String field, Value value) {
        Map<String, Value> fields = new LinkedHashMap<>(document.fields());
        String existing = null;
        String target = field.toLowerCase(Locale.ROOT);
        for (String key : fields.keySet()) {
            if (key.toLowerCase(Locale.ROOT).equals(target)) {
                existing = key;
                break;
            }
        }
        fields.put(existing != null ? existing : field, value);
        return new DocumentRow(new StructValue(fields));
    }
}
