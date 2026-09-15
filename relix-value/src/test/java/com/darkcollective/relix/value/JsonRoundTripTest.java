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

import com.darkcollective.relix.json.JsonStrings;
import com.darkcollective.relix.json.JsonWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON this project writes must be JSON this project can read back.
 *
 * <p>The two halves of the grammar live in different modules and were written at
 * different times: {@code relix-json} escapes on the way out, {@link JsonValues}
 * unescapes on the way in, and until this test nothing asserted that the two sets
 * agree. They did agree -- but by coincidence rather than by contract, which is
 * the state in which a change to one side breaks the other silently.
 *
 * <p>The corpus is built from exactly what {@link JsonStrings} treats specially:
 * the two mandatory escapes, the five short forms, the numeric fallback for other
 * control characters, and the two cases a hand-written parser is most likely to get
 * wrong -- a character outside the BMP (JSON spells it as a surrogate pair) and a
 * forward slash (legal escaped or bare, and the writer leaves it bare).
 */
@DisplayName("relix-json writer -> JsonValues reader round trip")
final class JsonRoundTripTest {

    /** Every string shape the writer escapes, plus the two classic parser traps. */
    static final List<String> CORPUS = List.of(
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

    @Nested
    @DisplayName("string literals")
    class Strings {

        @Test
        @DisplayName("every escaped form the writer emits parses back to the original")
        void quotedStringsRoundTrip() {
            for (String original : CORPUS) {
                String json = JsonStrings.quote(original);
                Value parsed = JsonValues.parse(json);

                assertThat(parsed).as("%s parses as a string", json)
                        .isInstanceOf(StringValue.class);
                assertThat(((StringValue) parsed).value()).as("round trip of %s", json)
                        .isEqualTo(original);
            }
        }

        @Test
        @DisplayName("the writer emits no raw control character")
        void writerEscapesEveryControlCharacter() {
            // Round-tripping is not on its own enough. This module's reader accepts a
            // raw control character inside a string, so dropping the writer's numeric
            // escape would still read back cleanly here while emitting JSON that RFC
            // 8259 forbids -- and the consumers that would reject it are the ones
            // outside this project (the Python grader, jq, a browser), which no test
            // of ours would ever run. So assert the output is valid, not merely that
            // we can read it.
            for (String original : CORPUS) {
                String json = JsonStrings.quote(original);
                for (int i = 0; i < json.length(); i++) {
                    assertThat(json.charAt(i))
                            .as("char %d of %s must be escaped, not raw", i, original)
                            .isGreaterThanOrEqualTo((char) 0x20);
                }
            }
        }

        @Test
        @DisplayName("a surrogate pair survives as one code point, not two chars")
        void surrogatePairSurvives() {
            String emoji = "\ud83d\ude00";
            String parsed = ((StringValue) JsonValues.parse(JsonStrings.quote(emoji))).value();

            assertThat(parsed).isEqualTo(emoji);
            assertThat(parsed.codePointCount(0, parsed.length())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("whole documents")
    class Documents {

        @Test
        @DisplayName("a document built by JsonWriter parses back with its values intact")
        void writtenDocumentRoundTrips() {
            JsonWriter writer = new JsonWriter().beginObject();
            for (int i = 0; i < CORPUS.size(); i++) {
                writer.name("k" + i).value(CORPUS.get(i));
            }
            writer.name("flag").value(true)
                  .name("missing").nullValue()
                  .name("nested").beginArray().value("a \" b").value("c \\ d").endArray()
                  .endObject();

            Value parsed = JsonValues.parse(writer.toJson());
            assertThat(parsed).isInstanceOf(StructValue.class);
            StructValue root = (StructValue) parsed;

            for (int i = 0; i < CORPUS.size(); i++) {
                assertThat(root.fields().get("k" + i)).as("field k%d", i)
                        .isEqualTo(new StringValue(CORPUS.get(i)));
            }
            assertThat(root.fields().get("flag")).isEqualTo(BooleanValue.TRUE);
            assertThat(root.fields().get("missing")).isEqualTo(NullValue.INSTANCE);
            assertThat(root.fields().get("nested")).isEqualTo(new ArrayValue(List.of(
                    new StringValue("a \" b"), new StringValue("c \\ d"))));
        }
    }
}
