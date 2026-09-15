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

import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.JsonValues;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
}
