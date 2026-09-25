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

import com.darkcollective.relix.processor.internal.QueryResult;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.Value;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;

import java.util.ArrayList;
import java.util.List;

/**
 * Assertions on a {@link QueryResult} — or on a bare list of {@link Row}s — whose
 * failures print the table.
 *
 * <p>The shape being replaced is one assertion per cell:
 *
 * {@snippet lang = "java":
 * assertThat(rows.get(0).get("id").asDisplayString()).isEqualTo("1");
 * assertThat(rows.get(0).get("name").asDisplayString()).isEqualTo("Apple");
 * assertThat(rows.get(0).get("price").asDisplayString()).isEqualTo("0.99");
 * }
 *
 * <p>Three claims about one row, and when the connector returns the wrong row the
 * failure names one cell — so the reader learns that {@code id} was {@code 2} and
 * nothing about what the query actually returned.  Stated as
 * {@code hasRowAt(0, "1", "Apple", "0.99")} the failure prints the whole result,
 * which is the only form in which "the rows are in the wrong order" is visible at
 * all.
 *
 * <p>Cells are compared as {@link Value#asDisplayString()}, the rendering the CLI
 * prints, so an expectation is written the way the value is read.  Assert on the
 * typed {@link Value} through {@link #row(int)} where the distinction matters — a
 * NULL and the string {@code "NULL"} display alike and {@link RowAssert#isNullAt}
 * separates them.
 *
 * <p>Obtain one from {@link ProcessorAssertions#assertThat(QueryResult)} or
 * {@link ProcessorAssertions#assertThatRows(List)}.
 *
 * @see ProcessorAssertions
 */
public final class QueryResultAssert extends AbstractAssert<QueryResultAssert, QueryResult> {

    /** Rows rendered into the failure message before it is truncated. */
    private static final int MAX_RENDERED_ROWS = 20;

    QueryResultAssert(QueryResult actual) {
        super(actual, QueryResultAssert.class);
        if (actual != null) {
            as("result:%n%s", describe(actual));
        }
    }

    /**
     * Asserts the result's columns are exactly {@code names}, in order.
     *
     * <p>Read from the rows rather than the schema when the heading is
     * {@linkplain Schema#isOpen() open}, so a schema-on-read result is asserted on
     * the fields it actually produced.
     *
     * @param names the expected column names, in order
     * @return this assert, for chaining
     */
    public QueryResultAssert hasColumns(String... names) {
        isNotNull();
        List<String> actualNames = columnNames();
        if (actualNames.size() != names.length
                || !java.util.stream.IntStream.range(0, names.length)
                        .allMatch(i -> actualNames.get(i).equalsIgnoreCase(names[i]))) {
            failWithMessage("expected columns %s but were %s", List.of(names), actualNames);
        }
        return this;
    }

    /**
     * Asserts the result has exactly {@code count} rows.
     *
     * @param count the expected row count
     * @return this assert, for chaining
     */
    public QueryResultAssert hasRowCount(int count) {
        isNotNull();
        if (actual.rowCount() != count) {
            failWithMessage("expected %d row(s) but there are %d", count, actual.rowCount());
        }
        return this;
    }

    /**
     * Asserts the result has no rows.
     *
     * @return this assert, for chaining
     */
    public QueryResultAssert isEmpty() {
        isNotNull();
        if (!actual.isEmpty()) {
            failWithMessage("expected no rows but there are %d", actual.rowCount());
        }
        return this;
    }

    /**
     * Asserts the row at {@code index} displays exactly {@code cells}, left to right.
     *
     * @param index the zero-based row position
     * @param cells the expected {@link Value#asDisplayString()} of each column
     * @return this assert, for chaining
     */
    public QueryResultAssert hasRowAt(int index, String... cells) {
        isNotNull();
        if (index >= actual.rowCount()) {
            failWithMessage("expected a row at index %d but there are only %d",
                    index, actual.rowCount());
        }
        List<String> row = displayRow(actual.rows().get(index));
        if (!row.equals(List.of(cells))) {
            failWithMessage("expected row %d to be %s but it is %s", index, List.of(cells), row);
        }
        return this;
    }

    /**
     * Asserts some row displays exactly {@code cells} — the claim to make when the
     * rows are a set and their order is not guaranteed.
     *
     * @param cells the expected {@link Value#asDisplayString()} of each column
     * @return this assert, for chaining
     */
    public QueryResultAssert hasRow(String... cells) {
        isNotNull();
        List<String> expected = List.of(cells);
        if (displayRows().stream().noneMatch(expected::equals)) {
            failWithMessage("expected a row %s, but no row matches", expected);
        }
        return this;
    }

    /**
     * Asserts no row displays {@code cells} — the claim a filter or difference test
     * makes about a row that should have been removed.
     *
     * @param cells the {@link Value#asDisplayString()} of a row that must be absent
     * @return this assert, for chaining
     */
    public QueryResultAssert hasNoRow(String... cells) {
        isNotNull();
        List<String> unwanted = List.of(cells);
        if (displayRows().stream().anyMatch(unwanted::equals)) {
            failWithMessage("expected no row %s, but one is present", unwanted);
        }
        return this;
    }

    /**
     * Begins an assertion on one row, keeping the whole result as the failure's
     * context.
     *
     * @param index the zero-based row position
     * @return an assert on that row
     */
    public RowAssert row(int index) {
        isNotNull();
        if (index >= actual.rowCount()) {
            failWithMessage("expected a row at index %d but there are only %d",
                    index, actual.rowCount());
        }
        return new RowAssert(actual.rows().get(index))
                .as("row %d of the result:%n%s", index, describe(actual));
    }

    /**
     * Hands the rows, rendered cell by cell, to AssertJ — for the claims a list
     * assert already states well ({@code containsExactlyInAnyOrder} over a set-valued
     * result, {@code containsSubsequence} over an ordered one).
     *
     * @return an assert on the display strings of each row, in result order
     */
    public ListAssert<List<String>> displayedRows() {
        isNotNull();
        return Assertions.assertThat(displayRows()).as(descriptionText());
    }

    private List<List<String>> displayRows() {
        return actual.rows().stream().map(QueryResultAssert::displayRow).toList();
    }

    private static List<String> displayRow(Row row) {
        List<String> cells = new ArrayList<>();
        for (String name : row.columnNames()) {
            cells.add(row.get(name).asDisplayString());
        }
        return cells;
    }

    private List<String> columnNames() {
        if (!actual.schema().isOpen() && actual.schema().width() > 0) {
            return actual.schema().columns().stream()
                    .map(com.darkcollective.relix.symbol.ColumnDefinition::name).toList();
        }
        return actual.rows().isEmpty() ? List.of() : actual.rows().get(0).columnNames();
    }

    /**
     * Renders the result as an indented, column-aligned table — the subject every
     * failure here reports.
     */
    private static String render(QueryResult result) {
        List<String> header = result.schema().isOpen() || result.schema().width() == 0
                ? (result.rows().isEmpty() ? List.of() : result.rows().get(0).columnNames())
                : result.schema().columns().stream()
                        .map(com.darkcollective.relix.symbol.ColumnDefinition::name).toList();
        List<List<String>> body = result.rows().stream().limit(MAX_RENDERED_ROWS)
                .map(QueryResultAssert::displayRow).toList();
        if (header.isEmpty() && body.isEmpty()) {
            return "    <no columns, no rows>";
        }

        int width = header.size();
        for (List<String> row : body) {
            width = Math.max(width, row.size());
        }
        int[] widths = new int[width];
        for (int i = 0; i < header.size(); i++) {
            widths[i] = header.get(i).length();
        }
        for (List<String> row : body) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        StringBuilder out = new StringBuilder();
        appendLine(out, header, widths);
        out.append("    ");
        for (int i = 0; i < width; i++) {
            out.append("-".repeat(Math.max(1, widths[i]))).append(i == width - 1 ? "" : "  ");
        }
        out.append(System.lineSeparator());
        for (List<String> row : body) {
            appendLine(out, row, widths);
        }
        if (result.rowCount() > body.size()) {
            out.append("    … ").append(result.rowCount() - body.size()).append(" more row(s)")
                    .append(System.lineSeparator());
        }
        if (result.rowCount() == 0) {
            out.append("    <no rows>").append(System.lineSeparator());
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, List<String> cells, int[] widths) {
        out.append("    ");
        for (int i = 0; i < cells.size(); i++) {
            out.append(pad(cells.get(i), widths[i]));
            if (i < cells.size() - 1) {
                out.append("  ");
            }
        }
        out.append(System.lineSeparator());
    }

    private static String pad(String cell, int width) {
        return cell.length() >= width ? cell : cell + " ".repeat(width - cell.length());
    }

    /**
     * Renders {@code a result table} for the failure message, and never throws.
     *
     * <p>A description is not the claim under test.  Some of the subjects a test builds
     * are deliberately malformed — a pass is asked what it does with a null component,
     * and {@code PrettyPrinter} throws on one — so a renderer that propagated that would
     * turn a <em>passing</em> assertion into a red test whose stack trace points at the
     * assert rather than at the code.  Rendering is best-effort for exactly that reason.
     */
    static String describe(QueryResult result) {
        try {
            return render(result);
        } catch (RuntimeException e) {
            return "    <unrenderable result: " + e + ">";
        }
    }
}
