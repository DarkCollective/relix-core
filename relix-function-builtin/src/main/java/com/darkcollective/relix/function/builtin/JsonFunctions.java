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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.internal.JsonValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Arguments.string;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The JSON built-ins: a value read out of JSON text held in a string column, located by
 * a path such as {@code '$.customer.name'} or {@code '$.items[0].sku'}.
 *
 * <p>{@code JSON_VALUE} gives a scalar as text, {@code JSON_QUERY} an object or array as
 * the engine's struct or array, and {@code JSON_EXTRACT} whatever value is there. Each
 * answers NULL, as SQL's lax mode does, for text that is not JSON, a path that reaches
 * nothing, and a value of the wrong kind; a path that is not one is refused.
 *
 * <h2>The path</h2>
 * {@code $} is the document, {@code .key} or {@code ."key"} a member of an object, and
 * {@code [n]} the element of an array at a zero-based position. A key is matched exactly,
 * case included, as every database's JSON functions match it. A step that does not fit
 * the value it reaches, a member of an array or an element of an object, reaches nothing:
 * the path is followed as written, as MySQL and SQLite follow one.
 *
 * <h2>Which of these a backend is offered</h2>
 * {@code JSON_VALUE} on PostgreSQL, through {@code jsonb_path_query_array} over a
 * {@code strict} path with its errors silenced, behind {@code pg_input_is_valid}, which
 * PostgreSQL has from version 16. jsonb keeps a number's text as written, so
 * {@code 1.50} is {@code '1.50'} there and here. MySQL writes a decimal back as a double
 * ({@code 1.5}), so it is offered none. {@code JSON_QUERY} and {@code JSON_EXTRACT} are
 * not offered: a database answers JSON text where these answer a struct or an array.
 */
final class JsonFunctions {

    private static final Category JSON = Category.of("json");

    private JsonFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                JSON.fn("JSON_VALUE", STRING, PURE_DETERMINISTIC, List.of(p("doc", STRING), p("path", STRING)),
                        Spellings.on(Spellings.POSTGRES, 2, JsonFunctions::postgresValue),
                        args -> value(args.get(0), args.get(1))),
                JSON.fn("JSON_QUERY", ANY, PURE_DETERMINISTIC, List.of(p("doc", STRING), p("path", STRING)),
                        args -> query(args.get(0), args.get(1))),
                JSON.fn("JSON_EXTRACT", ANY, PURE_DETERMINISTIC, List.of(p("doc", STRING), p("path", STRING)),
                        args -> extract(args.get(0), args.get(1))));
    }

    /**
     * PostgreSQL's reading of {@code JSON_VALUE(d, p)}: the items {@code strict p} finds
     * in {@code d}, with a structural error answering none rather than raising, and the
     * one item's text when it is a single scalar. Text that is not JSON is NULL.
     */
    private static String postgresValue(List<String> arguments) {
        String doc = arguments.get(0);
        String path = arguments.get(1);
        return "CASE WHEN pg_input_is_valid(" + doc + ", 'jsonb') THEN (SELECT CASE WHEN jsonb_array_length(v) = 1"
                + " AND jsonb_typeof(v -> 0) IN ('string', 'number', 'boolean') THEN v ->> 0 END FROM (SELECT"
                + " jsonb_path_query_array(CAST(" + doc + " AS jsonb), CAST('strict ' || " + path
                + " AS jsonpath), '{}', true) AS v) AS j) END";
    }

    /** {@code JSON_VALUE(doc, path)}: the scalar at the path as text, NULL for anything else. */
    private static Value value(Value doc, Value path) {
        return located(doc, path, "JSON_VALUE").map(found -> switch (found) {
            case StringValue s -> (Value) s;
            case NumberValue n -> new StringValue(n.value().toPlainString());
            case BooleanValue b -> new StringValue(String.valueOf(b.value()));
            default -> NullValue.INSTANCE;
        }).orElse(NullValue.INSTANCE);
    }

    /** {@code JSON_QUERY(doc, path)}: the object or array at the path, NULL for a scalar. */
    private static Value query(Value doc, Value path) {
        return located(doc, path, "JSON_QUERY")
                .filter(found -> found instanceof StructValue || found instanceof ArrayValue)
                .orElse(NullValue.INSTANCE);
    }

    /** {@code JSON_EXTRACT(doc, path)}: the value at the path, of whatever kind. */
    private static Value extract(Value doc, Value path) {
        return located(doc, path, "JSON_EXTRACT").orElse(NullValue.INSTANCE);
    }

    /**
     * The value {@code path} reaches in the JSON text {@code doc}, or empty when either is
     * NULL, the text is not JSON or the path reaches nothing. A malformed path is refused
     * whatever the document, so a mistake in it is not mistaken for a missing value.
     */
    private static Optional<Value> located(Value doc, Value path, String function) {
        if (doc.isNull() || path.isNull()) {
            return Optional.empty();
        }
        List<Object> steps = parsePath(string(path, function), function);
        Value current;
        try {
            current = JsonValues.parse(string(doc, function));
        } catch (JsonValues.JsonParseException notJson) {
            return Optional.empty();
        }
        for (Object step : steps) {
            if (step instanceof String key && current instanceof StructValue struct
                    && struct.fields().containsKey(key)) {
                current = struct.fields().get(key);
            } else if (step instanceof Integer index && current instanceof ArrayValue array
                    && index < array.elements().size()) {
                current = array.elements().get(index);
            } else {
                return Optional.empty();
            }
        }
        return current.isNull() ? Optional.empty() : Optional.of(current);
    }

    /**
     * The steps of a path: a {@code String} for each member, an {@code Integer} for each
     * element.
     */
    static List<Object> parsePath(String path, String function) {
        String text = path.strip();
        if (!text.startsWith("$")) {
            throw badPath(path, function, "it starts with $, the document");
        }
        List<Object> steps = new ArrayList<>();
        int i = 1;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '.') {
                i++;
                if (i < n && text.charAt(i) == '"') {
                    StringBuilder key = new StringBuilder();
                    i++;
                    while (i < n && text.charAt(i) != '"') {
                        if (text.charAt(i) == '\\' && i + 1 < n) {
                            i++;
                        }
                        key.append(text.charAt(i++));
                    }
                    if (i >= n) {
                        throw badPath(path, function, "a quoted key ends with \"");
                    }
                    i++;
                    steps.add(key.toString());
                } else {
                    int start = i;
                    while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                        i++;
                    }
                    if (start == i || Character.isDigit(text.charAt(start))) {
                        throw badPath(path, function, "a member is .name or .\"any key\"");
                    }
                    steps.add(text.substring(start, i));
                }
            } else if (c == '[') {
                int close = text.indexOf(']', i);
                String inner = close < 0 ? "" : text.substring(i + 1, close).strip();
                if (!inner.matches("\\d{1,9}")) {
                    throw badPath(path, function, "an element is [n], a position counted from 0");
                }
                steps.add(Integer.parseInt(inner));
                i = close + 1;
            } else {
                throw badPath(path, function, "each step is .name, .\"any key\" or [n]");
            }
        }
        return steps;
    }

    private static IllegalArgumentException badPath(String path, String function, String rule) {
        return reject(function + ": '" + path + "' is not a JSON path: " + rule
                + ", as in '$.customer.name' or '$.items[0].sku'");
    }
}
