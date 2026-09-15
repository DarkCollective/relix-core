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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Unit tests for the {@link JsonReader} parser. */
final class JsonReaderTest {

    @Test
    void parsesStringWithEscapes() {
        assertThat(JsonReader.parse("\"a\\\"b\\n\\u0041\"")).isEqualTo("a\"b\nA");
    }

    @Test
    void parsesNumbersAsRawStrings() {
        assertThat(JsonReader.parse("42")).isEqualTo("42");
        assertThat(JsonReader.parse("-3.14e2")).isEqualTo("-3.14e2");
    }

    @Test
    void parsesLiterals() {
        assertThat(JsonReader.parse("true")).isEqualTo(Boolean.TRUE);
        assertThat(JsonReader.parse("false")).isEqualTo(Boolean.FALSE);
        assertThat(JsonReader.parse("null")).isNull();
    }

    @Test
    void parsesEmptyContainers() {
        assertThat(JsonReader.parse("{}")).isEqualTo(Map.of());
        assertThat(JsonReader.parse("[]")).isEqualTo(List.of());
    }

    @Test
    void parsesNestedStructure() {
        Object root = JsonReader.parse("""
                [ { "name": "PG", "artifacts": [ { "url": "u", "sha256": "h" } ] } ]
                """);
        assertThat(root).isInstanceOf(List.class);
        List<?> array = (List<?>) root;
        Map<?, ?> entry = (Map<?, ?>) array.get(0);
        assertThat(entry.get("name")).isEqualTo("PG");
        List<?> artifacts = (List<?>) entry.get("artifacts");
        assertThat(((Map<?, ?>) artifacts.get(0)).get("url")).isEqualTo("u");
    }

    @Test
    void rejectsTrailingCharacters() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("{} junk"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("\"abc"));
    }

    @Test
    void rejectsMissingComma() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("[1 2]"));
    }

    @Test
    void rejectsInvalidEscape() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("\"\\x\""));
    }

    @Test
    void rejectsEmptyInput() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("   "));
    }

    // -- the malformed shapes a hand-edited catalog manifest actually takes ---

    @Test
    void rejectsMissingCommaBetweenObjectMembers() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("{\"a\":1 \"b\":2}"))
                .withMessageContaining("expected ',' or '}'");
    }

    @Test
    void rejectsTruncatedUnicodeEscape() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("\"\\u12\""))
                .withMessageContaining("truncated");
    }

    @Test
    void rejectsTruncatedTrueLiteral() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("tru"))
                .withMessageContaining("invalid literal");
    }

    @Test
    void rejectsTruncatedNullLiteral() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("nul"))
                .withMessageContaining("invalid literal");
    }

    @Test
    void rejectsACharacterThatBeginsNoValue() {
        // Anything not {, [, ", t, f or n is read as a number, and '@' is in no
        // number's alphabet -- so the number scanner is where the report comes from.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("@"))
                .withMessageContaining("unexpected character '@'");
    }

    @Test
    void rejectsAnObjectThatEndsAfterItsBrace() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("{"))
                .withMessageContaining("unexpected end of input");
    }

    @Test
    void rejectsAnArrayThatEndsAfterItsBracket() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("["))
                .withMessageContaining("unexpected end of input");
    }

    @Test
    void rejectsAnObjectThatEndsAfterItsKey() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("{\"a\""))
                .withMessageContaining("unexpected end of input");
    }

    @Test
    void rejectsAKeyNotFollowedByAColon() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> JsonReader.parse("{\"a\" 1}"))
                .withMessageContaining("expected ':'");
    }

    // -- round trip against this module's own writer ------------------------

    /** Every string shape {@link JsonStrings} escapes, plus the classic parser traps. */
    private static final List<String> CORPUS = List.of(
            "",
            "plain text",
            "quote \" inside",
            "backslash \\ inside",
            "newline \n tab \t return \r",
            "backspace \b formfeed \f",
            "low control \u0001 and \u001f",
            "accented \u00e9 and CJK \u4e2d\u6587",
            "outside the BMP \ud83d\ude00 done",
            "solidus / bare",
            "a literal \\u0041 sequence");

    /**
     * The reader and the writer now share a module precisely so this can be
     * asserted rather than assumed. They agreed before the move too -- but by
     * coincidence, in the state where a change to one side breaks the other with
     * nothing to catch it.
     */
    @Test
    void readsBackEveryStringTheWriterEscapes() {
        for (String original : CORPUS) {
            assertThat(JsonReader.parse(JsonStrings.quote(original)))
                    .as("round trip of %s", original)
                    .isEqualTo(original);
        }
    }

    @Test
    void readsBackAWholeWrittenDocument() {
        JsonWriter writer = new JsonWriter().beginObject();
        for (int i = 0; i < CORPUS.size(); i++) {
            writer.name("k" + i).value(CORPUS.get(i));
        }
        writer.name("flag").value(true)
              .name("missing").nullValue()
              .name("nested").beginArray().value("a \" b").value("c \\ d").endArray()
              .endObject();

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) JsonReader.parse(writer.toJson());

        for (int i = 0; i < CORPUS.size(); i++) {
            assertThat(root.get("k" + i)).as("field k%d", i).isEqualTo(CORPUS.get(i));
        }
        assertThat(root.get("flag")).isEqualTo(Boolean.TRUE);
        assertThat(root.get("missing")).isNull();
        assertThat(root.get("nested")).isEqualTo(List.of("a \" b", "c \\ d"));
    }
}
