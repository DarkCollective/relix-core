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
package com.darkcollective.relix.value.internal;

import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses JSON text into the relix {@link Value} hierarchy — the boundary at which
 * a nested document becomes native relix data:
 *
 * <ul>
 *   <li>object → {@link StructValue}</li>
 *   <li>array → {@link ArrayValue}</li>
 *   <li>string → {@link StringValue}</li>
 *   <li>number → {@link NumberValue} (a {@link BigDecimal})</li>
 *   <li>{@code true}/{@code false} → {@link BooleanValue}</li>
 *   <li>{@code null} → {@link NullValue}</li>
 * </ul>
 *
 * <p>A hand-rolled recursive-descent parser (the project avoids third-party JSON
 * dependencies).  Malformed input throws {@link JsonParseException}.
 */
public final class JsonValues {

    private JsonValues() {
    }

    /** Thrown when the input is not well-formed JSON. */
    public static final class JsonParseException extends RuntimeException {
        public JsonParseException(String message) {
            super(message);
        }
    }

    /**
     * Parses a single JSON value (object, array, string, number, boolean, or null).
     *
     * @param json the JSON text; must not be null
     * @return the parsed value
     * @throws JsonParseException if {@code json} is not well-formed, or has trailing content
     */
    public static Value parse(String json) {
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        Value value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonParseException("Trailing content after JSON value at position " + parser.pos);
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        Value parseValue() {
            if (atEnd()) {
                throw new JsonParseException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new StringValue(parseString());
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private StructValue parseObject() {
            expect('{');
            Map<String, Value> fields = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return new StructValue(fields);
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                fields.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return new StructValue(fields);
                }
                if (c != ',') {
                    throw new JsonParseException("Expected ',' or '}' in object at position " + (pos - 1));
                }
            }
        }

        private ArrayValue parseArray() {
            expect('[');
            List<Value> elements = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return new ArrayValue(elements);
            }
            while (true) {
                skipWhitespace();
                elements.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return new ArrayValue(elements);
                }
                if (c != ',') {
                    throw new JsonParseException("Expected ',' or ']' in array at position " + (pos - 1));
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonParseException("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    sb.append(parseEscape());
                } else {
                    sb.append(c);
                }
            }
        }

        private char parseEscape() {
            if (atEnd()) {
                throw new JsonParseException("Unterminated escape");
            }
            char e = s.charAt(pos++);
            return switch (e) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> parseUnicodeEscape();
                default -> throw new JsonParseException("Invalid escape '\\" + e + "'");
            };
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > s.length()) {
                throw new JsonParseException("Truncated \\u escape");
            }
            String hex = s.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException ex) {
                throw new JsonParseException("Invalid \\u escape '" + hex + "'");
            }
        }

        private Value parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return BooleanValue.of(true);
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return BooleanValue.of(false);
            }
            throw new JsonParseException("Invalid literal at position " + pos);
        }

        private Value parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return NullValue.INSTANCE;
            }
            throw new JsonParseException("Invalid literal at position " + pos);
        }

        private Value parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!atEnd() && isNumberChar(s.charAt(pos))) {
                pos++;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty() || num.equals("-")) {
                throw new JsonParseException("Invalid value at position " + start);
            }
            try {
                return new NumberValue(new BigDecimal(num));
            } catch (NumberFormatException ex) {
                throw new JsonParseException("Invalid number '" + num + "' at position " + start);
            }
        }

        private static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonParseException("Unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw new JsonParseException("Unexpected end of JSON");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            if (atEnd() || s.charAt(pos) != c) {
                throw new JsonParseException("Expected '" + c + "' at position " + pos);
            }
            pos++;
        }
    }
}
