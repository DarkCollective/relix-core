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
package com.darkcollective.relix.symbol;

import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;

import java.util.List;
import java.util.Optional;

/**
 * Assertions on a {@link Schema}, whose failures print the whole heading.
 *
 * <p>A schema's default rendering is a {@code List<ColumnDefinition>} dump, so a
 * mismatch on one column is reported inside a wall of records.  Every assertion
 * here describes its subject as the compact {@code name:type} heading the IR report
 * uses, which is the form a reader can scan:
 *
 * <pre>
 *   heading: id:N  name:S  addr:{city:S}
 * </pre>
 *
 * <p>{@link #hasColumn(String, Type)} takes a <em>dotted path</em>, resolved through
 * {@link Schema#resolvePath(String)}.  That is the part which is not sugar: nested
 * column types are walked by hand at every site that asserts on one today, and a
 * hand-walk asserts the shape it expected rather than the shape that is there.
 *
 * <p>Obtain one from {@link SymbolAssertions#assertThat(Schema)}.
 *
 * @see SymbolAssertions
 */
public final class SchemaAssert extends AbstractAssert<SchemaAssert, Schema> {

    SchemaAssert(Schema actual) {
        super(actual, SchemaAssert.class);
        if (actual != null) {
            as("heading: %s", describe(actual));
        }
    }

    /**
     * Asserts the schema's columns are exactly {@code names}, in order.
     *
     * <p>Comparison is case-insensitive, matching how the schema itself resolves a
     * column name.
     *
     * @param names the expected column names, in schema order
     * @return this assert, for chaining
     */
    public SchemaAssert hasColumnNames(String... names) {
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
     * Asserts the schema declares {@code name}, without constraining its type.
     *
     * <p>{@code name} may be a dotted path into a nested column
     * ({@code "addr.city"}).
     *
     * @param name the column name or dotted path
     * @return this assert, for chaining
     */
    public SchemaAssert hasColumn(String name) {
        isNotNull();
        if (actual.resolvePath(name).isEmpty()) {
            failWithMessage("expected a column %s, but the heading declares %s",
                    name, columnNames());
        }
        return this;
    }

    /**
     * Asserts the schema declares {@code name} with exactly {@code type}.
     *
     * <p>{@code name} may be a dotted path into a nested column, so
     * {@code hasColumn("addr.city", ScalarType.STRING)} states in one line the claim
     * a struct walk states in five — and states it against
     * {@link Schema#resolvePath(String)}, the same resolution the analyser performs,
     * rather than against a re-implementation of it.
     *
     * @param name the column name or dotted path
     * @param type the expected type at that path
     * @return this assert, for chaining
     */
    public SchemaAssert hasColumn(String name, Type type) {
        isNotNull();
        Optional<Type> resolved = actual.resolvePath(name);
        if (resolved.isEmpty()) {
            failWithMessage("expected a column %s : %s, but the heading declares %s",
                    name, type.display(), columnNames());
        } else if (!resolved.get().equals(type)) {
            failWithMessage("expected column %s to be %s but it is %s",
                    name, type.display(), resolved.get().display());
        }
        return this;
    }

    /**
     * Asserts the schema does not resolve {@code name} — the claim a projection or
     * pruning test makes about a column it dropped.
     *
     * @param name the column name or dotted path that must not resolve
     * @return this assert, for chaining
     */
    public SchemaAssert hasNoColumn(String name) {
        isNotNull();
        actual.resolvePath(name).ifPresent(type -> failWithMessage(
                "expected no column %s, but it resolves to %s", name, type.display()));
        return this;
    }

    /**
     * Asserts the schema has {@code width} columns.
     *
     * @param width the expected column count
     * @return this assert, for chaining
     */
    public SchemaAssert hasWidth(int width) {
        isNotNull();
        if (actual.width() != width) {
            failWithMessage("expected %d column(s) but the heading has %d",
                    width, actual.width());
        }
        return this;
    }

    /**
     * Asserts this is an {@linkplain Schema#open() open} (schema-on-read) heading.
     *
     * <p>Distinct from {@link #isEmptyHeading()}: both have zero columns, and they
     * mean opposite things.
     *
     * @return this assert, for chaining
     */
    public SchemaAssert isOpen() {
        isNotNull();
        if (!actual.isOpen()) {
            failWithMessage("expected an open (schema-on-read) heading but it declares %s",
                    columnNames());
        }
        return this;
    }

    /**
     * Asserts this is the {@linkplain Schema#empty() empty} heading — closed, with no
     * columns, the heading of the nullary truth relations.
     *
     * @return this assert, for chaining
     */
    public SchemaAssert isEmptyHeading() {
        isNotNull();
        if (actual.isOpen() || actual.width() != 0) {
            failWithMessage("expected the empty (closed, zero-column) heading but was %s",
                    heading(actual));
        }
        return this;
    }

    /**
     * Hands the column names to AssertJ, for the claims a list assert already states
     * well ({@code containsExactlyInAnyOrder}, {@code startsWith}).
     *
     * @return an assert on the ordered column names
     */
    public org.assertj.core.api.ListAssert<String> columnNamesAssert() {
        isNotNull();
        return Assertions.assertThat(columnNames()).as(descriptionText());
    }

    private List<String> columnNames() {
        return actual.columns().stream().map(ColumnDefinition::name).toList();
    }

    /** The compact {@code name:type} rendering the IR report uses for a heading. */
    static String heading(Schema schema) {
        if (schema.isOpen()) {
            return "<open>";
        }
        if (schema.width() == 0) {
            return "<empty>";
        }
        return schema.columns().stream()
                .map(c -> c.name() + ":" + c.type().code())
                .reduce((a, b) -> a + "  " + b)
                .orElse("<empty>");
    }

    /**
     * Renders {@code a heading} for the failure message, and never throws.
     *
     * <p>A description is not the claim under test.  Some of the subjects a test builds
     * are deliberately malformed — a pass is asked what it does with a null component,
     * and {@code PrettyPrinter} throws on one — so a renderer that propagated that would
     * turn a <em>passing</em> assertion into a red test whose stack trace points at the
     * assert rather than at the code.  Rendering is best-effort for exactly that reason.
     */
    static String describe(Schema schema) {
        try {
            return heading(schema);
        } catch (RuntimeException e) {
            return "<unrenderable heading: " + e + ">";
        }
    }
}
