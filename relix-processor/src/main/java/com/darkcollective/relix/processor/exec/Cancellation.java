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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;

import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * How a caller stops a query it started: {@linkplain Thread#interrupt() interrupt the
 * thread running it}.
 *
 * <h2>Why the interrupt flag and not an API of its own</h2>
 *
 * There was no way at all to stop a running query — no deadline, no cancel, and nothing
 * in the executor that looked at the interrupt flag. A stream closed early does stop the
 * work, because the engine is pull-based end to end, but that requires the consuming
 * thread to be alive and in control, which is exactly what it is not while a blocking
 * operator drains its input inside a single {@code tryAdvance}.
 *
 * <p>Interruption needs no new API, and it is the mechanism the platform already routes
 * everything through: {@code Future.cancel(true)}, {@code ExecutorService.shutdownNow},
 * and a thread pool being torn down all raise the same flag. That is not hypothetical
 * here — the REPL runs a command on a worker and cancels it with {@code cancel(true)},
 * which raised a flag the engine never read, so the query carried on running on a daemon
 * thread while the user was returned to the prompt.
 *
 * <h2>What it does not cover, and why that is said out loud</h2>
 *
 * A thread blocked inside a JDBC driver is not interruptible. Where a query has been
 * pushed down, the engine is waiting on {@code executeQuery} and will notice the flag
 * only when the database returns. Stopping <em>that</em> needs
 * {@code Statement.cancel()} from another thread, which means reaching the live statement
 * from outside the pipeline — a different piece of work, and a larger one.
 *
 * <p>So this is cancellation of the engine's own work, promptly and everywhere it does
 * any. It is not a deadline and it is not a guarantee about a remote server.
 */
final class Cancellation {

    private Cancellation() {
    }

    /**
     * Wraps {@code rows} so that each pull first asks whether this thread has been
     * interrupted, raising if it has.
     *
     * <p>Checked per row rather than on a timer, which is what makes it prompt without a
     * clock: every operator in a pull-based engine does its work inside somebody's
     * {@code tryAdvance}, so a row boundary is the one place that is reached often and is
     * always between two units of work rather than inside one.
     *
     * @param rows the stream to guard; must not be null
     * @return a stream that stops when the thread is interrupted
     */
    static Stream<Row> interruptible(Stream<Row> rows) {
        Spliterator<Row> source = rows.spliterator();
        Spliterator<Row> guarded = new Spliterators.AbstractSpliterator<>(
                source.estimateSize(), source.characteristics()) {
            @Override
            public boolean tryAdvance(Consumer<? super Row> action) {
                checkNotInterrupted();
                return source.tryAdvance(action);
            }
        };
        return StreamSupport.stream(guarded, false).onClose(rows::close);
    }

    /**
     * Raises when the current thread has been interrupted.
     *
     * <p>The flag is <em>cleared</em> as it is read, and then restored before throwing:
     * an {@link EvaluationException} is what every other refusal in the executor is, so a
     * caller's {@code catch} already covers this, and restoring the flag leaves the thread
     * in the state the platform expects — a caller that wants to interrupt again, or to
     * pass the thread back to a pool, finds it as it left it.
     */
    static void checkNotInterrupted() {
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new EvaluationException(
                    "query cancelled: the thread running it was interrupted");
        }
    }
}
