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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * A minimal, dependency-free streaming writer for JSON text.
 *
 * <p>The writer is a fluent builder. Callers open and close containers with
 * {@link #beginObject()}/{@link #endObject()} and
 * {@link #beginArray()}/{@link #endArray()}, label object members with
 * {@link #name(String)}, and emit scalars with the {@code value} overloads.
 * Output is compact (no insignificant whitespace) and every string — both names
 * and string values — is escaped per the JSON specification.
 *
 * <h2>Structural guarantees</h2>
 * The writer fails fast with an {@link IllegalStateException} on misuse, so a
 * buggy serializer cannot silently produce malformed JSON:
 * <ul>
 *   <li>{@link #name(String)} is only valid inside an object, and must precede
 *       each member value (no two names in a row, no name without a value).</li>
 *   <li>A value inside an object must be preceded by a {@link #name(String)}.</li>
 *   <li>{@link #endObject()}/{@link #endArray()} must match the currently open
 *       container, and an object may not be closed with a dangling name.</li>
 *   <li>At most one value may be written at the top level.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * String json = new JsonWriter()
 *     .beginObject()
 *         .name("op").value("Projection")
 *         .name("schema").beginArray()
 *             .beginObject().name("name").value("id").name("type").value("N").endObject()
 *         .endArray()
 *         .name("children").beginArray().endArray()
 *     .endObject()
 *     .toJson();
 * // {"op":"Projection","schema":[{"name":"id","type":"N"}],"children":[]}
 * }</pre>
 *
 * <p>Instances are not thread-safe and are intended for single-pass use.
 */
public final class JsonWriter {

    private enum ScopeType { OBJECT, ARRAY }

    /** A currently-open container and how many members it has so far. */
    private static final class Scope {
        final ScopeType type;
        int size;
        Scope(ScopeType type) { this.type = type; }
    }

    private final StringBuilder out = new StringBuilder(256);
    private final Deque<Scope> stack = new ArrayDeque<>();

    /** Inside an object, a {@link #name(String)} has been written and its value is due. */
    private boolean expectingValue;

    /** A top-level value has been completed; no further top-level value is allowed. */
    private boolean rootWritten;

    /** Creates an empty writer. */
    public JsonWriter() {
        // no initial state beyond field defaults
    }

    // =========================================================================
    // Containers
    // =========================================================================

    /**
     * Begins a JSON object (<code>&#123;&#125;</code>).
     *
     * @return this writer, for chaining
     * @throws IllegalStateException if a value is not permitted at this position
     */
    public JsonWriter beginObject() {
        beforeValue();
        stack.push(new Scope(ScopeType.OBJECT));
        // The new scope starts fresh; the parent's pending name (if any) is
        // satisfied by this container and cleared by afterValue() on close.
        expectingValue = false;
        out.append('{');
        return this;
    }

    /**
     * Ends the current JSON object.
     *
     * @return this writer, for chaining
     * @throws IllegalStateException if no object is open, or a name was written
     *                               without a following value
     */
    public JsonWriter endObject() {
        requireTop(ScopeType.OBJECT, "endObject");
        if (expectingValue) {
            throw new IllegalStateException("endObject() with a name but no value");
        }
        stack.pop();
        out.append('}');
        afterValue();
        return this;
    }

    /**
     * Begins a JSON array ({@code []}).
     *
     * @return this writer, for chaining
     * @throws IllegalStateException if a value is not permitted at this position
     */
    public JsonWriter beginArray() {
        beforeValue();
        stack.push(new Scope(ScopeType.ARRAY));
        // The new scope starts fresh; the parent's pending name (if any) is
        // satisfied by this container and cleared by afterValue() on close.
        expectingValue = false;
        out.append('[');
        return this;
    }

    /**
     * Ends the current JSON array.
     *
     * @return this writer, for chaining
     * @throws IllegalStateException if no array is open
     */
    public JsonWriter endArray() {
        requireTop(ScopeType.ARRAY, "endArray");
        stack.pop();
        out.append(']');
        afterValue();
        return this;
    }

    // =========================================================================
    // Names and values
    // =========================================================================

    /**
     * Writes an object member name.  Must be followed by exactly one value.
     *
     * @param name the member name; must not be null
     * @return this writer, for chaining
     * @throws IllegalStateException if not currently inside an object, or a name
     *                               was already written without a value
     */
    public JsonWriter name(String name) {
        Objects.requireNonNull(name, "name");
        Scope top = stack.peek();
        if (top == null || top.type != ScopeType.OBJECT) {
            throw new IllegalStateException("name() is only valid inside an object");
        }
        if (expectingValue) {
            throw new IllegalStateException("name() called twice without a value");
        }
        if (top.size > 0) {
            out.append(',');
        }
        writeString(name);
        out.append(':');
        expectingValue = true;
        return this;
    }

    /**
     * Writes a string value, or JSON {@code null} if {@code value} is null.
     *
     * @param value the string, or null for JSON null
     * @return this writer, for chaining
     */
    public JsonWriter value(String value) {
        beforeValue();
        if (value == null) {
            out.append("null");
        } else {
            writeString(value);
        }
        afterValue();
        return this;
    }

    /**
     * Writes an integral number value.
     *
     * @param value the number
     * @return this writer, for chaining
     */
    public JsonWriter value(long value) {
        beforeValue();
        out.append(value);
        afterValue();
        return this;
    }

    /**
     * Writes a floating-point number value.
     *
     * @param value the number; must be finite
     * @return this writer, for chaining
     * @throws IllegalArgumentException if {@code value} is NaN or infinite
     *                                  (JSON has no representation for these)
     */
    public JsonWriter value(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(
                    "JSON cannot represent a non-finite number: " + value);
        }
        beforeValue();
        out.append(Double.toString(value));
        afterValue();
        return this;
    }

    /**
     * Writes a boolean value.
     *
     * @param value the boolean
     * @return this writer, for chaining
     */
    public JsonWriter value(boolean value) {
        beforeValue();
        out.append(value ? "true" : "false");
        afterValue();
        return this;
    }

    /**
     * Writes an explicit JSON {@code null} value.
     *
     * @return this writer, for chaining
     */
    public JsonWriter nullValue() {
        beforeValue();
        out.append("null");
        afterValue();
        return this;
    }

    // =========================================================================
    // Output
    // =========================================================================

    /**
     * Returns the completed JSON document.
     *
     * @return the JSON text
     * @throws IllegalStateException if any object/array is still open, or no
     *                               value has been written
     */
    public String toJson() {
        if (!stack.isEmpty()) {
            throw new IllegalStateException(
                    "incomplete JSON: " + stack.size() + " unclosed container(s)");
        }
        if (!rootWritten) {
            throw new IllegalStateException("incomplete JSON: no value written");
        }
        return out.toString();
    }

    /**
     * Returns the JSON written so far, without checking for completeness.
     * Useful for diagnostics; prefer {@link #toJson()} for finished output.
     *
     * @return the current buffer contents
     */
    @Override
    public String toString() {
        return out.toString();
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /** Validates position and emits any required separator before a value. */
    private void beforeValue() {
        Scope top = stack.peek();
        if (top == null) {
            if (rootWritten) {
                throw new IllegalStateException(
                        "a second top-level value is not allowed");
            }
            return;
        }
        if (top.type == ScopeType.ARRAY) {
            if (top.size > 0) {
                out.append(',');
            }
        } else if (!expectingValue) { // OBJECT scope awaiting a value
            throw new IllegalStateException(
                    "a value inside an object must be preceded by name()");
        }
    }

    /** Updates bookkeeping after a value (or closed container) is written. */
    private void afterValue() {
        Scope top = stack.peek();
        if (top == null) {
            rootWritten = true;
            return;
        }
        top.size++;
        if (top.type == ScopeType.OBJECT) {
            expectingValue = false;
        }
    }

    private void requireTop(ScopeType type, String op) {
        Scope top = stack.peek();
        if (top == null || top.type != type) {
            throw new IllegalStateException(
                    op + "() called outside a matching " + type + " scope");
        }
    }

    /** Appends {@code s} as a quoted, JSON-escaped string. */
    private void writeString(String s) {
        JsonStrings.appendQuoted(out, s);
    }
}
