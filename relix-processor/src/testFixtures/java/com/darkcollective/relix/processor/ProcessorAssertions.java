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

import com.darkcollective.relix.plan.PlanAssertions;
import com.darkcollective.relix.symbol.Schema;

import java.util.List;

/**
 * The relix assertion entry point at the execution layer — {@link PlanAssertions}
 * plus {@link QueryResult} and {@link Row}.
 *
 * <p>The counterpart of {@link ProcessorTestSupport}: that class is how a test
 * <em>builds</em> rows, this is how it asserts on the ones that came back.
 *
 * {@snippet lang = "java":
 * import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
 * import static com.darkcollective.relix.processor.ProcessorAssertions.assertThatRows;
 *
 * assertThat(result).hasColumns("id", "name").hasRowAt(0, "1", "Alice");
 * assertThatRows(rows).hasRowCount(3).row(0).isNullAt("discount");
 * }
 *
 * <p>Note {@link #assertThatRows(List)} rather than another {@code assertThat}
 * overload: a {@code List<Row>} is a {@link List}, and AssertJ's own
 * {@code assertThat(List)} is the right assert for the claims a list assert makes
 * about it.  A distinct name keeps both reachable instead of shadowing one.
 *
 * @see QueryResultAssert
 * @see RowAssert
 */
public class ProcessorAssertions extends PlanAssertions {

    /** Not instantiable directly; extend it, or import its members statically. */
    protected ProcessorAssertions() {
    }

    /**
     * Begins an assertion on an executed query's result.
     *
     * @param actual the result under test; may be null (the assert reports it)
     * @return the assert
     */
    public static QueryResultAssert assertThat(QueryResult actual) {
        return new QueryResultAssert(actual);
    }

    /**
     * Begins an assertion on a single row.
     *
     * @param actual the row under test; may be null (the assert reports it)
     * @return the assert
     */
    public static RowAssert assertThat(Row actual) {
        return new RowAssert(actual);
    }

    /**
     * Begins a {@link QueryResultAssert} on a bare list of rows — what an operator or
     * connector test holds, having never built a {@link QueryResult}.
     *
     * <p>The heading is taken from the first row.  Use
     * {@link #assertThatRows(Schema, List)} where the rows may be empty and the
     * columns are part of the claim.
     *
     * @param rows the rows under test; must not be null
     * @return the assert
     */
    public static QueryResultAssert assertThatRows(List<? extends Row> rows) {
        Schema schema = rows.isEmpty() ? Schema.open() : rows.get(0).schema();
        return assertThatRows(schema, rows);
    }

    /**
     * Begins a {@link QueryResultAssert} on rows under an explicitly stated heading.
     *
     * @param schema the heading the rows are read under; must not be null
     * @param rows   the rows under test; must not be null
     * @return the assert
     */
    public static QueryResultAssert assertThatRows(Schema schema, List<? extends Row> rows) {
        // Wildcarded because a caller may hold a List of some Row subtype — the embedding
        // API's readable tuple, most obviously — and an assertion helper that refused one
        // would send that caller off to write the projection this class exists to be.
        return new QueryResultAssert(new QueryResult("<rows>", schema, List.copyOf(rows)));
    }
}
