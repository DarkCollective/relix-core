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
package com.darkcollective.relix.json;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class JsonWriterTest {

    // ─── helpers ──────────────────────────────────────────────────────────────
    private static JsonWriter writer() {
        return new JsonWriter();
    }

    @Nested
    class Scalars {

        @Test
        void writesStringValue() {
            assertThat(writer().value("hello").toJson()).isEqualTo("\"hello\"");
        }

        @Test
        void writesNullForNullString() {
            assertThat(writer().value((String) null).toJson()).isEqualTo("null");
        }

        @Test
        void writesExplicitNull() {
            assertThat(writer().nullValue().toJson()).isEqualTo("null");
        }

        @Test
        void writesLong() {
            assertThat(writer().value(42L).toJson()).isEqualTo("42");
        }

        @Test
        void writesNegativeLong() {
            assertThat(writer().value(-7L).toJson()).isEqualTo("-7");
        }

        @Test
        void writesDouble() {
            assertThat(writer().value(3.5).toJson()).isEqualTo("3.5");
        }

        @Test
        void writesIntegralDoubleWithTrailingZero() {
            assertThat(writer().value(2.0).toJson()).isEqualTo("2.0");
        }

        @Test
        void writesTrue() {
            assertThat(writer().value(true).toJson()).isEqualTo("true");
        }

        @Test
        void writesFalse() {
            assertThat(writer().value(false).toJson()).isEqualTo("false");
        }

        @Test
        void rejectsNaN() {
            assertThatThrownBy(() -> writer().value(Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-finite");
        }

        @Test
        void rejectsInfinity() {
            assertThatThrownBy(() -> writer().value(Double.POSITIVE_INFINITY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Escaping {

        @Test
        void escapesQuoteAndBackslash() {
            assertThat(writer().value("a\"b\\c").toJson())
                    .isEqualTo("\"a\\\"b\\\\c\"");
        }

        @Test
        void escapesWhitespaceControls() {
            assertThat(writer().value("\n\r\t\b\f").toJson())
                    .isEqualTo("\"\\n\\r\\t\\b\\f\"");
        }

        @Test
        void escapesOtherControlCharsAsUnicode() {
            assertThat(writer().value("\u0000\u001f").toJson())
                    .isEqualTo("\"\\u0000\\u001f\"");
        }

        @Test
        void passesThroughPrintableUnicode() {
            // σ, ⋈ and other non-ASCII printables are emitted verbatim (valid JSON).
            assertThat(writer().value("σ ⋈ é").toJson()).isEqualTo("\"σ ⋈ é\"");
        }

        @Test
        void escapesNamesToo() {
            String json = writer().beginObject().name("a\"b").value(1L).endObject().toJson();
            assertThat(json).isEqualTo("{\"a\\\"b\":1}");
        }
    }

    @Nested
    class Containers {

        @Test
        void emptyObject() {
            assertThat(writer().beginObject().endObject().toJson()).isEqualTo("{}");
        }

        @Test
        void emptyArray() {
            assertThat(writer().beginArray().endArray().toJson()).isEqualTo("[]");
        }

        @Test
        void singleMemberObject() {
            assertThat(writer().beginObject().name("k").value("v").endObject().toJson())
                    .isEqualTo("{\"k\":\"v\"}");
        }

        @Test
        void multiMemberObjectIsCommaSeparated() {
            String json = writer().beginObject()
                    .name("a").value(1L)
                    .name("b").value(true)
                    .name("c").value("x")
                    .endObject().toJson();
            assertThat(json).isEqualTo("{\"a\":1,\"b\":true,\"c\":\"x\"}");
        }

        @Test
        void arrayOfScalarsIsCommaSeparated() {
            String json = writer().beginArray()
                    .value(1L).value(2L).value(3L)
                    .endArray().toJson();
            assertThat(json).isEqualTo("[1,2,3]");
        }

        @Test
        void arrayOfObjects() {
            String json = writer().beginArray()
                    .beginObject().name("n").value("id").endObject()
                    .beginObject().name("n").value("amount").endObject()
                    .endArray().toJson();
            assertThat(json).isEqualTo("[{\"n\":\"id\"},{\"n\":\"amount\"}]");
        }

        @Test
        void nestedArrays() {
            String json = writer().beginArray()
                    .beginArray().value(1L).endArray()
                    .beginArray().endArray()
                    .endArray().toJson();
            assertThat(json).isEqualTo("[[1],[]]");
        }

        @Test
        void deeplyNestedBundleShape() {
            String json = writer().beginObject()
                    .name("op").value("Projection")
                    .name("schema").beginArray()
                        .beginObject().name("name").value("id").name("type").value("N").endObject()
                    .endArray()
                    .name("children").beginArray().endArray()
                    .endObject().toJson();
            assertThat(json).isEqualTo(
                    "{\"op\":\"Projection\","
                  + "\"schema\":[{\"name\":\"id\",\"type\":\"N\"}],"
                  + "\"children\":[]}");
        }

        @Test
        void topLevelArray() {
            assertThat(writer().beginArray().value("a").endArray().toJson())
                    .isEqualTo("[\"a\"]");
        }
    }

    @Nested
    class StructuralErrors {

        @Test
        void nameNullThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> writer().beginObject().name(null));
        }

        @Test
        void nameAtRootThrows() {
            assertThatThrownBy(() -> writer().name("k"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inside an object");
        }

        @Test
        void nameInsideArrayThrows() {
            assertThatThrownBy(() -> writer().beginArray().name("k"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inside an object");
        }

        @Test
        void twoNamesWithoutValueThrows() {
            assertThatThrownBy(() ->
                    writer().beginObject().name("a").name("b"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without a value");
        }

        @Test
        void valueInObjectWithoutNameThrows() {
            assertThatThrownBy(() -> writer().beginObject().value(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("preceded by name");
        }

        @Test
        void endObjectWithDanglingNameThrows() {
            assertThatThrownBy(() ->
                    writer().beginObject().name("a").endObject())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no value");
        }

        @Test
        void endObjectWhenArrayOpenThrows() {
            assertThatThrownBy(() -> writer().beginArray().endObject())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("endObject");
        }

        @Test
        void endArrayWhenObjectOpenThrows() {
            assertThatThrownBy(() -> writer().beginObject().endArray())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("endArray");
        }

        @Test
        void endObjectAtRootThrows() {
            assertThatThrownBy(() -> writer().endObject())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void secondTopLevelValueThrows() {
            assertThatThrownBy(() -> writer().value(1L).value(2L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("second top-level value");
        }
    }

    @Nested
    class Completeness {

        @Test
        void toJsonWithUnclosedObjectThrows() {
            assertThatThrownBy(() -> writer().beginObject().toJson())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unclosed");
        }

        @Test
        void toJsonWithNoValueThrows() {
            assertThatThrownBy(() -> writer().toJson())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no value");
        }

        @Test
        void toStringExposesPartialBuffer() {
            JsonWriter w = writer().beginObject().name("a").value(1L);
            assertThat(w.toString()).isEqualTo("{\"a\":1");
        }
    }
}
