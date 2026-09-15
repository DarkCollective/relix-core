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

import java.util.ArrayList;
import java.util.List;

/**
 * Null-propagating navigation into nested {@link Value}s — the runtime engine
 * behind path access like {@code user.name} or {@code items[0].price}.
 *
 * <p>Navigation is <strong>optional-chaining</strong>: a missing field, an
 * out-of-range index, or a step into a value of the wrong shape yields
 * {@link NullValue}, never an error. This lets heterogeneous, schemaless documents
 * be queried without crashing — matching Mongo / {@code jq} semantics.
 *
 * <p>The path syntax accepted by {@link #parse(String)} is dotted field names with
 * bracketed array indices: {@code "a.b[0].c"}, {@code "[2].name"}. This is the
 * utility's own syntax; the query language's surface syntax for paths is a
 * separate concern wired in later.
 */
public final class ValuePath {

    private ValuePath() {
    }

    /** A single navigation step: into a named struct field, or an array index. */
    public sealed interface Step permits Step.Field, Step.Index {
        /** Navigate into the struct field named {@code name} (case-insensitive). */
        record Field(String name) implements Step { }
        /** Navigate into the array element at zero-based {@code index}. */
        record Index(int index) implements Step { }
    }

    /**
     * Navigates {@code root} through {@code steps}, propagating {@link NullValue}
     * on any miss.
     *
     * @param root  the starting value; must not be null
     * @param steps the steps to follow, in order; must not be null
     * @return the value reached, or {@link NullValue} if any step misses
     */
    public static Value navigate(Value root, List<Step> steps) {
        Value current = root;
        for (Step step : steps) {
            if (current instanceof NullValue) {
                return NullValue.INSTANCE;   // propagate
            }
            current = switch (step) {
                case Step.Field f -> current instanceof StructValue s
                        ? s.field(f.name()).orElse(NullValue.INSTANCE)
                        : NullValue.INSTANCE;
                case Step.Index i -> current instanceof ArrayValue a
                        ? a.at(i.index()).orElse(NullValue.INSTANCE)
                        : NullValue.INSTANCE;
            };
        }
        return current;
    }

    /**
     * Parses {@code path} and navigates {@code root} through it.
     *
     * @param root the starting value; must not be null
     * @param path a path like {@code "user.name"} or {@code "items[0].price"}
     * @return the value reached, or {@link NullValue} if any step misses
     */
    public static Value navigate(Value root, String path) {
        return navigate(root, parse(path));
    }

    /**
     * Parses a path string into navigation steps.
     *
     * @param path a path like {@code "a.b[0].c"}; empty/blank yields no steps
     * @return the ordered steps
     * @throws IllegalArgumentException if a {@code [} is unterminated or an index
     *                                  is not an integer
     */
    public static List<Step> parse(String path) {
        List<Step> steps = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        int i = 0;
        int n = path.length();
        while (i < n) {
            char c = path.charAt(i);
            if (c == '.') {
                flushField(name, steps);
                i++;
            } else if (c == '[') {
                flushField(name, steps);
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("Unterminated '[' in path: '" + path + "'");
                }
                String inner = path.substring(i + 1, close).trim();
                try {
                    steps.add(new Step.Index(Integer.parseInt(inner)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Array index must be an integer in path '" + path + "': '" + inner + "'");
                }
                i = close + 1;
            } else {
                name.append(c);
                i++;
            }
        }
        flushField(name, steps);
        return steps;
    }

    private static void flushField(StringBuilder name, List<Step> steps) {
        if (name.length() > 0) {
            steps.add(new Step.Field(name.toString()));
            name.setLength(0);
        }
    }
}
