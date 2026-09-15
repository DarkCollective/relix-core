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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JsonStrings} — the shared JSON string-literal escaper.
 *
 * <p>These pin the escaping contract at its own level rather than only through
 * {@link JsonWriter}, because the direct callers (the MongoDB pipeline renderer,
 * the {@code :ask} failure log, the {@code --format json} row writer) use it
 * without going through the writer.
 *
 * <p>Control characters are built with {@code (char)} casts rather than source
 * unicode escapes, so what the test feeds in is unambiguous.
 */
@DisplayName("JsonStrings — shared JSON string-literal escaping")
final class JsonStringsTest {

    /** The character with code point {@code cp} as a one-character string. */
    private static String ch(int cp) {
        return String.valueOf((char) cp);
    }

    @Nested
    @DisplayName("quote")
    class Quote {

        @Test
        @DisplayName("wraps in double quotes")
        void wrapsInQuotes() {
            assertThat(JsonStrings.quote("abc")).isEqualTo("\"abc\"");
        }

        @Test
        @DisplayName("escapes the two mandatory escapes")
        void escapesQuoteAndBackslash() {
            assertThat(JsonStrings.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
        }

        @Test
        @DisplayName("uses the short forms for the five named control characters")
        void escapesNamedControls() {
            assertThat(JsonStrings.quote("\n\r\t\b\f")).isEqualTo("\"\\n\\r\\t\\b\\f\"");
        }

        @Test
        @DisplayName("escapes other sub-0x20 control characters as a unicode escape")
        void escapesOtherControlsAsUnicode() {
            assertThat(JsonStrings.quote(ch(0x00) + ch(0x1f))).isEqualTo("\"\\u0000\\u001f\"");
        }

        @Test
        @DisplayName("passes printable non-ASCII through verbatim")
        void passesThroughPrintableUnicode() {
            // Relix glyphs travel through plan JSON and the ask log; they are
            // valid unescaped in a UTF-8 document and must not be mangled.
            assertThat(JsonStrings.quote("σ ⋈ é")).isEqualTo("\"σ ⋈ é\"");
        }

        @Test
        @DisplayName("handles the empty string")
        void handlesEmpty() {
            assertThat(JsonStrings.quote("")).isEqualTo("\"\"");
        }

        @Test
        @DisplayName("0x20 is the boundary — 0x1f escapes, space does not")
        void spaceIsTheBoundary() {
            assertThat(JsonStrings.quote(ch(0x1f) + " ")).isEqualTo("\"\\u001f \"");
        }
    }

    @Nested
    @DisplayName("appendQuoted")
    class AppendQuoted {

        @Test
        @DisplayName("appends to an existing buffer without disturbing it")
        void appendsToBuffer() {
            StringBuilder sb = new StringBuilder("prefix:");
            JsonStrings.appendQuoted(sb, "a\"b");
            assertThat(sb.toString()).isEqualTo("prefix:\"a\\\"b\"");
        }

        @Test
        @DisplayName("agrees with quote for the same input")
        void agreesWithQuote() {
            String awkward = "a\"b\\c\n\tσ";
            StringBuilder sb = new StringBuilder();
            JsonStrings.appendQuoted(sb, awkward);
            assertThat(sb.toString()).isEqualTo(JsonStrings.quote(awkward));
        }
    }

    @Nested
    @DisplayName("JsonWriter delegates here, so the two never diverge")
    class WriterParity {

        @Test
        @DisplayName("a written string value matches quote(...) exactly")
        void writerMatchesQuote() {
            String awkward = "a\"b\\c\n\r\t\b\f " + ch(0x01) + " σ ⋈";
            assertThat(new JsonWriter().value(awkward).toJson())
                    .isEqualTo(JsonStrings.quote(awkward));
        }

        @Test
        @DisplayName("an object name is escaped the same way as a value")
        void namesMatchQuote() {
            String awkward = "a\"b\n";
            assertThat(new JsonWriter().beginObject().name(awkward).value(1L).endObject().toJson())
                    .isEqualTo("{" + JsonStrings.quote(awkward) + ":1}");
        }
    }
}
