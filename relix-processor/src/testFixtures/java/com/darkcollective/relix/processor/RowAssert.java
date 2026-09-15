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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ObjectAssert;

import java.util.ArrayList;
import java.util.List;

/**
 * Assertions on a single {@link Row}, whose failures print the row.
 *
 * <p>Reached from {@link QueryResultAssert#row(int)}, which keeps the surrounding
 * result in the failure message, or directly from
 * {@link ProcessorAssertions#assertThat(Row)}.
 *
 * <p>Cell claims come in two forms deliberately.  {@link #hasValue(String, String)}
 * compares {@link Value#asDisplayString()}, which is how a result is read and how an
 * expectation is naturally written; {@link #value(String)} hands over the typed
 * {@link Value}, for the tests whose subject is the type rather than the rendering —
 * a NULL and the three-letter string display alike, and only one of them is a
 * {@link NullValue}.
 *
 * @see ProcessorAssertions
 */
public final class RowAssert extends AbstractAssert<RowAssert, Row> {

    RowAssert(Row actual) {
        super(actual, RowAssert.class);
        if (actual != null) {
            as("row: %s", describe(actual));
        }
    }

    /**
     * Asserts the row's columns are exactly {@code names}, in order.
     *
     * @param names the expected column names, in order
     * @return this assert, for chaining
     */
    public RowAssert hasColumns(String... names) {
        isNotNull();
        List<String> actualNames = actual.columnNames();
        if (actualNames.size() != names.length
                || !java.util.stream.IntStream.range(0, names.length)
                        .allMatch(i -> actualNames.get(i).equalsIgnoreCase(names[i]))) {
            failWithMessage("expected columns %s but were %s", List.of(names), actualNames);
        }
        return this;
    }

    /**
     * Asserts the named column displays as {@code expected}.
     *
     * @param column   the column name, matched case-insensitively
     * @param expected the expected {@link Value#asDisplayString()}
     * @return this assert, for chaining
     */
    public RowAssert hasValue(String column, String expected) {
        isNotNull();
        String display = valueOf(column).asDisplayString();
        if (!display.equals(expected)) {
            failWithMessage("expected column %s to display as %s but it displays as %s",
                    column, "\"" + expected + "\"", "\"" + display + "\"");
        }
        return this;
    }

    /**
     * Asserts the row displays exactly {@code cells}, left to right.
     *
     * @param cells the expected {@link Value#asDisplayString()} of each column
     * @return this assert, for chaining
     */
    public RowAssert hasValues(String... cells) {
        isNotNull();
        List<String> display = displayCells(actual);
        if (!display.equals(List.of(cells))) {
            failWithMessage("expected the row to be %s but it is %s", List.of(cells), display);
        }
        return this;
    }

    /**
     * Asserts the named column holds SQL NULL — the claim
     * {@link #hasValue(String, String)} cannot make, since a NULL and the string
     * {@code "NULL"} display identically.
     *
     * @param column the column name, matched case-insensitively
     * @return this assert, for chaining
     */
    public RowAssert isNullAt(String column) {
        isNotNull();
        Value value = valueOf(column);
        if (!(value instanceof NullValue)) {
            failWithMessage("expected column %s to be NULL but it holds a %s displaying as %s",
                    column, value.getClass().getSimpleName(), "\"" + value.asDisplayString() + "\"");
        }
        return this;
    }

    /**
     * Asserts the named column does <em>not</em> hold SQL NULL.
     *
     * @param column the column name, matched case-insensitively
     * @return this assert, for chaining
     */
    public RowAssert isNotNullAt(String column) {
        isNotNull();
        if (valueOf(column) instanceof NullValue) {
            failWithMessage("expected column %s not to be NULL", column);
        }
        return this;
    }

    /**
     * Hands the typed value in {@code column} to AssertJ, keeping the row as the
     * failure's context — for claims about the {@link Value} itself rather than its
     * rendering.
     *
     * @param column the column name, matched case-insensitively
     * @return an assert on that column's value
     */
    public ObjectAssert<Value> value(String column) {
        isNotNull();
        return Assertions.assertThat(valueOf(column)).as("%s of the %s", column, descriptionText());
    }

    /**
     * Resolves {@code column} through the row itself, failing with the row's columns if
     * it does not.
     *
     * <p>Deliberately <em>not</em> a check against {@link Row#columnNames()}: a row
     * resolves a relation-qualified reference ({@code Users.name}) through column
     * provenance, and that name is not in the list — so a name-list pre-check rejects a
     * reference the row would have answered.
     */
    private Value valueOf(String column) {
        try {
            return actual.get(column);
        } catch (IllegalArgumentException e) {
            failWithMessage("expected a column %s, but the row has %s", column, actual.columnNames());
            return null;   // unreachable; failWithMessage throws
        }
    }

    private static List<String> displayCells(Row row) {
        List<String> cells = new ArrayList<>();
        for (String name : row.columnNames()) {
            cells.add(row.get(name).asDisplayString());
        }
        return cells;
    }

    /** Renders a row as {@code name=value} pairs — short enough to sit on one line. */
    static String render(Row row) {
        List<String> names = row.columnNames();
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(names.get(i)).append('=').append(row.get(names.get(i)).asDisplayString());
        }
        return out.append(']').toString();
    }

    /**
     * Renders {@code a row} for the failure message, and never throws.
     *
     * <p>A description is not the claim under test.  Some of the subjects a test builds
     * are deliberately malformed — a pass is asked what it does with a null component,
     * and {@code PrettyPrinter} throws on one — so a renderer that propagated that would
     * turn a <em>passing</em> assertion into a red test whose stack trace points at the
     * assert rather than at the code.  Rendering is best-effort for exactly that reason.
     */
    static String describe(Row row) {
        try {
            return render(row);
        } catch (RuntimeException e) {
            return "<unrenderable row: " + e + ">";
        }
    }
}
