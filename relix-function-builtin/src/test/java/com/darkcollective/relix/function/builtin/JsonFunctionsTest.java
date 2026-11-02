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

import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.function;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JSON_VALUE, JSON_QUERY and JSON_EXTRACT over JSON text (#263). */
@DisplayName("JSON functions")
final class JsonFunctionsTest {

    private static final String ORDER = """
            {"customer": {"name": "Ada", "vip": true, "Name": "other"},
             "items": [{"sku": "A1", "qty": 2}, {"sku": "B2", "qty": 1.50}],
             "note": null, "total": 1E2, "odd key": "x", "quote\\"d": "q", "snake_case": "s"}""";

    private static Value value(String path) {
        return call("JSON_VALUE", s(ORDER), s(path));
    }

    @Test
    @DisplayName("JSON_VALUE gives a scalar as text: a string, a number as written, a boolean")
    void scalars() {
        assertThat(value("$.customer.name")).isEqualTo(new StringValue("Ada"));
        assertThat(value("$.items[1].qty")).isEqualTo(new StringValue("1.50"));
        assertThat(value("$.total")).isEqualTo(new StringValue("100"));
        assertThat(value("$.customer.vip")).isEqualTo(new StringValue("true"));
        assertThat(value(" $.items[0].sku ")).isEqualTo(new StringValue("A1"));
    }

    @Test
    @DisplayName("a key is matched exactly, and a quoted key may hold any character")
    void keys() {
        assertThat(value("$.customer.Name")).isEqualTo(new StringValue("other"));
        assertThat(value("$.\"odd key\"")).isEqualTo(new StringValue("x"));
        assertThat(value("$.\"quote\\\"d\"")).isEqualTo(new StringValue("q"));
        assertThat(value("$.CUSTOMER.name")).isEqualTo(NullValue.INSTANCE);
        assertThat(value("$.snake_case")).isEqualTo(new StringValue("s"));
    }

    @ParameterizedTest(name = "JSON_VALUE at {0} is NULL")
    @ValueSource(strings = {"$.customer", "$.items", "$.note", "$.missing", "$.items[2]",
            "$.customer[0]", "$.items.sku", "$.customer.name.first", "$"})
    @DisplayName("JSON_VALUE is NULL for an object, an array, JSON null, and a path that reaches nothing")
    void valueIsNullOtherwise(String path) {
        assertThat(value(path)).isEqualTo(NullValue.INSTANCE);
    }

    @Test
    @DisplayName("text that is not JSON, and a NULL document or path, give NULL")
    void notJson() {
        for (String fn : List.of("JSON_VALUE", "JSON_QUERY", "JSON_EXTRACT")) {
            assertThat(call(fn, s("{\"a\": 1"), s("$.a"))).as(fn).isEqualTo(NullValue.INSTANCE);
            assertThat(call(fn, s("not json"), s("$.a"))).as(fn).isEqualTo(NullValue.INSTANCE);
            assertThat(call(fn, NullValue.INSTANCE, s("$.a"))).as(fn).isEqualTo(NullValue.INSTANCE);
            assertThat(call(fn, s(ORDER), NullValue.INSTANCE)).as(fn).isEqualTo(NullValue.INSTANCE);
        }
    }

    @Test
    @DisplayName("JSON_QUERY gives an object as a struct and an array as an array, and NULL for a scalar")
    void query() {
        Value customer = call("JSON_QUERY", s(ORDER), s("$.customer"));
        assertThat(customer).isInstanceOf(StructValue.class);
        assertThat(((StructValue) customer).fields()).containsEntry("name", new StringValue("Ada"));
        Value items = call("JSON_QUERY", s(ORDER), s("$.items"));
        assertThat(items).isInstanceOf(ArrayValue.class);
        assertThat(((ArrayValue) items).elements()).hasSize(2);
        assertThat(call("JSON_QUERY", s(ORDER), s("$")).getClass()).isEqualTo(StructValue.class);
        assertThat(call("JSON_QUERY", s(ORDER), s("$.customer.name"))).isEqualTo(NullValue.INSTANCE);
        assertThat(call("JSON_QUERY", s(ORDER), s("$.missing"))).isEqualTo(NullValue.INSTANCE);
    }

    @Test
    @DisplayName("JSON_EXTRACT gives the value at the path, of whatever kind")
    void extract() {
        assertThat(call("JSON_EXTRACT", s(ORDER), s("$.items[0].qty")))
                .isEqualTo(new NumberValue(new BigDecimal("2")));
        assertThat(call("JSON_EXTRACT", s(ORDER), s("$.customer.name"))).isEqualTo(new StringValue("Ada"));
        assertThat(call("JSON_EXTRACT", s(ORDER), s("$.items[1]"))).isInstanceOf(StructValue.class);
        assertThat(call("JSON_EXTRACT", s(ORDER), s("$.note"))).isEqualTo(NullValue.INSTANCE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"customer.name", "$customer", "$.", "$.1a", "$.\"open", "$[x]", "$[-1]", "$[0",
            "$..a", "$.a b", "$.\"a\\"})
    @DisplayName("a malformed path is refused, saying what a path is, whatever the document")
    void malformedPaths(String path) {
        assertThatThrownBy(() -> call("JSON_VALUE", s("not json"), s(path)))
                .hasMessageStartingWith("JSON_VALUE: '" + path + "' is not a JSON path: ")
                .hasMessageEndingWith("as in '$.customer.name' or '$.items[0].sku'");
        assertThatThrownBy(() -> call("JSON_QUERY", s(ORDER), s(path)))
                .hasMessageStartingWith("JSON_QUERY: ");
    }

    @Test
    @DisplayName("JSON_VALUE is offered to PostgreSQL alone, as a strict path whose errors answer nothing")
    void pushdown() {
        PushdownTarget postgres = PushdownTarget.sql("postgres");
        assertThat(function("JSON_VALUE").pushdown().render(postgres, List.of("doc", "'$.a'")))
                .hasValue("CASE WHEN pg_input_is_valid(doc, 'jsonb') THEN (SELECT CASE WHEN"
                        + " jsonb_array_length(v) = 1 AND jsonb_typeof(v -> 0) IN ('string', 'number', 'boolean')"
                        + " THEN v ->> 0 END FROM (SELECT jsonb_path_query_array(CAST(doc AS jsonb),"
                        + " CAST('strict ' || '$.a' AS jsonpath), '{}', true) AS v) AS j) END");
        for (String dialect : List.of("mysql", "duckdb", "sqlite", "sqlserver", "")) {
            assertThat(function("JSON_VALUE").pushdown().render(PushdownTarget.sql(dialect), List.of("doc", "'$.a'")))
                    .as(dialect).isEmpty();
        }
        assertThat(function("JSON_QUERY").pushdown().render(postgres, List.of("doc", "'$.a'"))).isEmpty();
        assertThat(function("JSON_EXTRACT").pushdown().render(postgres, List.of("doc", "'$.a'"))).isEmpty();
    }
}
