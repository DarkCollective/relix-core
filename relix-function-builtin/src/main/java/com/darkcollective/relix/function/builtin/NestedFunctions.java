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

import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The nested-data built-ins: reaching into a value whose shape the heading does not
 * describe.
 *
 * <p>{@code Entries} is the one that makes an object whose <em>keys are data</em>
 * relationally queryable. The unnest operator explodes arrays, so an array-valued field
 * reaches the algebra directly; a field holding {@code {"us": 3, "gb": 7}} did not reach
 * it at all, because nothing turned a set of field names into a set of rows. Turning the
 * object into an array of entries is what hands it to the operator that already exists.
 */
final class NestedFunctions {

    /** The entry field holding a field's name. */
    static final String KEY_FIELD = "key";

    /** The entry field holding a field's value. */
    static final String VALUE_FIELD = "value";

    private static final Category NESTED = Category.of("nested");

    private NestedFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                // No backend spelling, and Mongo is the interesting refusal rather than
                // the obvious one: $objectToArray is this function except that it names
                // the entry fields k and v. A spelling claims the backend computes the
                // same value, and a document with differently-named fields is a
                // different value, so the call stays in the engine until a $map renames
                // them.
                NESTED.fn("Entries", ANY, PURE_DETERMINISTIC, List.of(p("object", ANY)),
                        args -> entries(args.get(0))),

                // An array's elements are written as the engine displays them, which no
                // backend matches for numbers and dates, so this runs here only.
                NESTED.fn("ARRAY_TO_STRING", STRING, PURE_DETERMINISTIC,
                        List.of(p("array", ANY), p("separator", STRING), p("null_text", STRING)),
                        Arity.between(2, 3), NestedFunctions::arrayToString));
    }

    /**
     * {@code ARRAY_TO_STRING(array, separator [, null_text])}: the elements' text joined
     * by the separator, as PostgreSQL's {@code array_to_string}. A NULL element is left
     * out, or written as {@code null_text} where one is given; a NULL array or separator
     * is a NULL result.
     */
    private static Value arrayToString(List<Value> args) {
        Value array = args.get(0);
        Value separator = args.get(1);
        if (array.isNull() || separator.isNull()) {
            return NullValue.INSTANCE;
        }
        if (!(array instanceof ArrayValue elements)) {
            throw Arguments.reject("ARRAY_TO_STRING: expected an array, got " + array.type());
        }
        Value nullText = args.size() > 2 ? args.get(2) : NullValue.INSTANCE;
        List<String> parts = new ArrayList<>(elements.elements().size());
        for (Value element : elements.elements()) {
            if (!element.isNull()) {
                parts.add(element.asDisplayString());
            } else if (!nullText.isNull()) {
                parts.add(nullText.asDisplayString());
            }
        }
        return new StringValue(String.join(separator.asDisplayString(), parts));
    }

    /**
     * Reads an object as an array of one {@code {key, value}} struct per field.
     *
     * <p>Anything that is not an object is {@code NULL}, which is navigation's rule
     * rather than a convenience: a path step through a missing or wrong-typed value
     * yields NULL so that heterogeneous data cannot crash a query, and this is the same
     * step spelled as a call. An inner unnest then drops the row, which is what the
     * caller wanted from a document that has no such object. An array is excluded for a
     * different reason — it already has the unnest operator, so reading one here would
     * be a second spelling of the same thing.
     *
     * <p>The entries come in the object's own field order, {@link StructValue} preserving
     * it, which is what lets the function declare itself deterministic.
     */
    private static Value entries(Value value) {
        if (!(value instanceof StructValue object)) {
            return NullValue.INSTANCE;
        }
        List<Value> entries = new ArrayList<>(object.fields().size());
        for (Map.Entry<String, Value> field : object.fields().entrySet()) {
            Map<String, Value> entry = new LinkedHashMap<>(2);
            entry.put(KEY_FIELD, new StringValue(field.getKey()));
            entry.put(VALUE_FIELD, field.getValue());
            entries.add(new StructValue(entry));
        }
        return new ArrayValue(entries);
    }
}
