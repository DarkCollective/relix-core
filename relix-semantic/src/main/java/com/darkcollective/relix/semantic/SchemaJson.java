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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.json.JsonWriter;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

/**
 * Serializes a {@link Schema} to JSON, shared by the logical-plan
 * ({@link LogicalPlanJson}) and physical-plan
 * ({@code relix-plan}'s {@code PhysicalPlanJson}) serializers so the encoding is
 * defined once.
 *
 * <p>A schema is rendered as an object:
 * <pre>{@code { "open": false, "columns": [ { "name": "id", "type": "N" }, … ] } }</pre>
 * An open (schema-on-read) schema has {@code "open": true} and an empty
 * {@code columns} array.  Column type codes use {@link
 * com.darkcollective.relix.symbol.ScalarType#code()}.
 */
public final class SchemaJson {

    private SchemaJson() {}

    /**
     * Writes {@code schema} as a JSON object value into {@code writer}.
     *
     * @param writer the writer to emit into; must not be null
     * @param schema the schema to serialize; must not be null
     */
    public static void write(JsonWriter writer, Schema schema) {
        writer.beginObject();
        writer.name("open").value(schema.isOpen());
        writer.name("columns").beginArray();
        for (ColumnDefinition col : schema.columns()) {
            writer.beginObject()
                    .name("name").value(col.name())
                    .name("type").value(col.type().code())
                    .endObject();
        }
        writer.endArray();
        writer.endObject();
    }
}
