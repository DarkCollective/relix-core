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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON parser producing plain Java objects — the
 * reading counterpart to {@link JsonWriter}, for callers that want a document
 * back rather than a typed domain object.
 *
 * <p>Despite the name it is not an incremental, pull-style reader over a stream:
 * {@link #parse} takes the whole document as text and hands back the whole tree.
 * The name pairs with {@link JsonWriter} because reading and writing are this
 * module's two directions, not because the two mirror each other call for call.
 *
 * <p>Its callers are the catalog manifests: the connector-plugin one the engine
 * reads, and the JDBC driver one a connector provider keeps ({@code DriverCatalog},
 * in {@code relix-connectors-std}). Both want a small, controlled document as maps
 * and lists, which is why this produces those rather than the relix {@code Value}
 * tree that {@code JsonValues} builds for data arriving from a source.
 *
 * <p>It lives here, beside {@link JsonStrings}, because escaping is the half of the
 * JSON grammar most easily gotten subtly wrong, and a parser that unescapes in a
 * different module from the writer that escapes is a contract nothing checks.
 *
 * <p>Parses the standard JSON value grammar into plain Java objects:
 * <ul>
 *   <li>object → {@link LinkedHashMap}{@code <String,Object>} (insertion order)</li>
 *   <li>array → {@link List}{@code <Object>}</li>
 *   <li>string → {@link String} (with the standard JSON backslash and 4-hex-digit escapes)</li>
 *   <li>number → {@link String} (kept verbatim — the catalog needs no arithmetic)</li>
 *   <li>{@code true}/{@code false} → {@link Boolean}; {@code null} → {@code null}</li>
 * </ul>
 *
 * <p>Malformed input raises {@link IllegalArgumentException}.  Used by both the
 * driver and connector catalogs.
 */
public final class JsonReader {

    private final String src;
    private int pos;

    private JsonReader(String src) {
        this.src = src;
    }

    /**
     * Parses a single JSON document.
     *
     * @param json the JSON text
     * @return the parsed value (Map / List / String / Boolean / null)
     * @throws IllegalArgumentException if the text is not well-formed JSON
     */
    public static Object parse(String json) {
        JsonReader parser = new JsonReader(json);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos != json.length()) {
            throw new IllegalArgumentException("trailing characters at position " + parser.pos);
        }
        return value;
    }

    private Object parseValue() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, parseValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at position " + (pos - 1));
            }
        }
    }

    private List<Object> parseArray() {
        List<Object> array = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(parseValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return array;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at position " + (pos - 1));
            }
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new IllegalArgumentException("truncated \\u escape");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("invalid escape '\\" + esc + "'");
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Boolean parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("invalid literal at position " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("invalid literal at position " + pos);
    }

    private String parseNumber() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (pos == start) {
            throw new IllegalArgumentException("unexpected character '" + src.charAt(pos) + "' at position " + pos);
        }
        return src.substring(start, pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        return src.charAt(pos++);
    }

    private void expect(char c) {
        char actual = next();
        if (actual != c) {
            throw new IllegalArgumentException("expected '" + c + "' but found '" + actual + "' at position " + (pos - 1));
        }
    }
}
