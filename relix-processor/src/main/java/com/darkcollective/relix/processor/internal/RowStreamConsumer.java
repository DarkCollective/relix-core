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
package com.darkcollective.relix.processor.internal;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.Schema;

import java.util.stream.Stream;

/**
 * Callback that receives one query's result as a lazy {@link Stream} of rows.
 *
 * <p>Used by {@link QueryExecutor}'s streaming API to hand each {@code query}
 * statement's output to the caller without first materialising it into a list.
 * The executor owns the stream's lifecycle: the stream is opened immediately
 * before {@link #accept} is invoked and closed immediately after it returns.
 *
 * <p>Consequently the consumer must finish reading the stream (or deliberately
 * stop) <em>within</em> the callback — it must not retain the {@code rows}
 * stream for later use, as it will be closed once {@code accept} returns.  Rows
 * are pulled lazily, so any {@link com.darkcollective.relix.processor.EvaluationException}
 * raised during evaluation surfaces while the consumer is reading.
 */
@FunctionalInterface
public interface RowStreamConsumer {

    /**
     * Consumes one query result.
     *
     * @param label  the query's display label (relation name or {@code "<expression N>"})
     * @param schema the query's output schema
     * @param rows   a lazy, single-use stream of the result rows, closed by the
     *               executor after this method returns
     */
    void accept(String label, Schema schema, Stream<Row> rows);
}
