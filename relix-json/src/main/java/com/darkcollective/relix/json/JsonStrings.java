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

/**
 * JSON string-literal escaping — the single source of truth for turning a Java
 * {@code String} into a quoted, correctly-escaped JSON string.
 *
 * <p>This exists because escaping is needed in two shapes: {@link JsonWriter}
 * builds structured documents, but several callers emit a fragment of JSON by
 * hand (a MongoDB pipeline stage, a JSON-Lines log record, a {@code --format
 * json} result row) and need only the string-literal primitive. Before this was
 * extracted, five such callers each carried their own copy of the escape switch;
 * they agreed by luck rather than construction. {@code JsonWriter} delegates
 * here too, so there is exactly one implementation.
 *
 * <p>The escaping follows RFC 8259: the two mandatory escapes ({@code "} and
 * {@code \}), the short forms for the five named control characters, and
 * {@code \ u00XX} for every other character below {@code 0x20}. Characters at or
 * above {@code 0x20} — including non-ASCII — are emitted as-is, which is valid
 * in a UTF-8 document.
 */
public final class JsonStrings {

    private JsonStrings() {
    }

    /**
     * Returns {@code value} as a quoted, escaped JSON string literal, including
     * the surrounding double quotes.
     *
     * @param value the raw string; must not be null
     * @return the JSON string literal, e.g. {@code a"b} → {@code "a\"b"}
     */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        appendQuoted(out, value);
        return out.toString();
    }

    /**
     * Appends {@code value} to {@code out} as a quoted, escaped JSON string
     * literal. Equivalent to {@link #quote(String)} without the intermediate
     * string, for callers already building into a buffer.
     *
     * @param out   the buffer to append to; must not be null
     * @param value the raw string; must not be null
     */
    public static void appendQuoted(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default   -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
