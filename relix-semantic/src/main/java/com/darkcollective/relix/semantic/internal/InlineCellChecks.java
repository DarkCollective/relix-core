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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.internal.TemporalLiterals;
import com.darkcollective.relix.lang.ast.table.InlineTable;
import com.darkcollective.relix.lang.ast.table.MarkdownInlineTable;

import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the inline-table cells that do not hold what they appear to.
 *
 * <p>A text column is a text column, so no finding is an error. A cell of a markdown
 * table written in quotes, such as {@code "45%"}, keeps its quotes, because cells are
 * bare values; string functions then see them. The CSV form is left alone, since
 * quoting is how CSV escapes a comma.
 *
 * <p>The other findings are each a cell that a later comparison, arithmetic or
 * {@code SESSIONIZE} reads as a temporal and cannot, and that then fails with an
 * error naming two types and no cell:
 * <ul>
 *   <li>a cell written as a literal, such as {@code TIMESTAMP '2026-03-01T09:00:00Z'}.
 *       Cells are bare values, so this one is the whole text and never parses;</li>
 *   <li>a cell in a column whose other cells are all one temporal kind, which does not
 *       parse as that kind: a typo such as {@code 2026-03-1T10:45:00Z} in a column of
 *       timestamps.</li>
 * </ul>
 */
final class InlineCellChecks {

    private InlineCellChecks() {}

    /** The temporal kinds a text cell is read as, in the order the evaluator tries them. */
    enum Kind {
        TIMESTAMP("ISO timestamps", TemporalLiterals::parseTimestamp),
        DATE("ISO dates", TemporalLiterals::parseDate),
        TIME("ISO times", TemporalLiterals::parseTime),
        DURATION("ISO durations", TemporalLiterals::parseDuration);

        final String plural;
        private final Consumer<String> parser;

        Kind(String plural, Consumer<String> parser) {
            this.plural = plural;
            this.parser = parser;
        }

        boolean reads(String cell) {
            try {
                parser.accept(cell);
                return true;
            } catch (DateTimeParseException e) {
                return false;
            }
        }
    }

    private static final Pattern LITERAL = Pattern.compile(
            "(?i)(TIMESTAMP|DATE|TIME|DURATION)\\s*'(.*)'");

    /**
     * The warnings for the inline table {@code name}.
     *
     * @param name  the table's name, as messages qualify its columns
     * @param table the table
     * @return the warning messages, column by column
     */
    static List<String> warnings(String name, InlineTable table) {
        List<String> warnings = new ArrayList<>();
        for (int c = 0; c < table.headers().size(); c++) {
            String column = name + "." + table.headers().get(c);
            Optional<String> quoted = table instanceof MarkdownInlineTable
                    ? quotedCell(column, table, c) : Optional.empty();
            quoted.ifPresent(warnings::add);
            literalCell(column, table, c).ifPresent(warnings::add);
            if (quoted.isEmpty()) {
                strayCell(column, table, c).ifPresent(warnings::add);
            }
        }
        return warnings;
    }

    /**
     * The first cell of column {@code c} that starts and ends with the same quote mark,
     * reported once. A column already flagged here is not also checked for a stray
     * temporal, since its quoted cells are the reason they do not read.
     */
    private static Optional<String> quotedCell(String column, InlineTable table, int c) {
        int count = 0;
        String first = null;
        int firstRow = 0;
        for (int r = 0; r < table.rows().size(); r++) {
            String cell = cell(table, r, c);
            if (isQuoted(cell) && count++ == 0) {
                first = cell;
                firstRow = r + 1;
            }
        }
        if (count == 0) {
            return Optional.empty();
        }
        String more = count == 1 ? "" : " (and " + (count - 1) + " more cell" + (count == 2 ? "" : "s") + ")";
        return Optional.of(column + ", row " + firstRow + ": the cell " + first
                + " keeps its quotes, because table cells are bare values; write "
                + first.substring(1, first.length() - 1) + more);
    }

    private static boolean isQuoted(String cell) {
        if (cell.length() < 2) {
            return false;
        }
        char open = cell.charAt(0);
        return (open == '"' || open == '\'') && cell.charAt(cell.length() - 1) == open;
    }

    /** The first cell of column {@code c} written as a typed literal, reported once. */
    private static Optional<String> literalCell(String column, InlineTable table, int c) {
        int count = 0;
        String first = null;
        Matcher firstMatch = null;
        int firstRow = 0;
        for (int r = 0; r < table.rows().size(); r++) {
            String cell = cell(table, r, c);
            Matcher m = LITERAL.matcher(cell);
            if (m.matches()) {
                if (count++ == 0) {
                    first = cell;
                    firstMatch = m;
                    firstRow = r + 1;
                }
            }
        }
        if (count == 0) {
            return Optional.empty();
        }
        String more = count == 1 ? "" : ", and " + (count - 1) + " more cell" + (count == 2 ? "" : "s");
        return Optional.of(column + ": table cells are bare values: write " + firstMatch.group(2)
                + ", not " + first + " (row " + firstRow + more + ")");
    }

    /**
     * A cell of column {@code c} that does not read as the temporal kind every other
     * cell does. Two cells of one kind are the least that make a column of that kind;
     * a column already flagged for a literal cell is left to that message.
     */
    private static Optional<String> strayCell(String column, InlineTable table, int c) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        List<Integer> present = new ArrayList<>();
        for (int r = 0; r < table.rows().size(); r++) {
            String cell = cell(table, r, c);
            if (isNullMarker(cell)) {
                continue;
            }
            if (LITERAL.matcher(cell).matches()) {
                return Optional.empty();
            }
            present.add(r);
            for (Kind kind : Kind.values()) {
                if (kind.reads(cell)) {
                    counts.merge(kind, 1, Integer::sum);
                    break;
                }
            }
        }
        for (Map.Entry<Kind, Integer> entry : counts.entrySet()) {
            int readable = entry.getValue();
            if (readable >= 2 && readable < present.size() && readable * 2 > present.size()) {
                Kind kind = entry.getKey();
                for (int r : present) {
                    String cell = cell(table, r, c);
                    if (!kind.reads(cell)) {
                        int others = present.size() - readable - 1;
                        return Optional.of(column + ": " + readable + " of " + present.size()
                                + " cells are " + kind.plural + "; row " + (r + 1) + ", '" + cell
                                + "', is not" + (others == 0 ? "" : ", nor are " + others + " more"));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String cell(InlineTable table, int r, int c) {
        List<String> row = table.rows().get(r);
        return c < row.size() ? row.get(c).trim() : "";
    }

    /** As the inline-table reader reads a missing value: empty, {@code ⊥} or {@code NULL}. */
    private static boolean isNullMarker(String cell) {
        return cell.isEmpty() || cell.equals("⊥") || cell.toLowerCase(Locale.ROOT).equals("null");
    }
}
