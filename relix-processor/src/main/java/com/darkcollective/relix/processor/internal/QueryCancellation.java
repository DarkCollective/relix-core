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

import com.darkcollective.relix.processor.EvaluationException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Whether one execution has been cancelled, and how to stop the work it has handed to
 * someone else.
 *
 * <p>The engine's own work stops by itself once this is cancelled: every row that moves
 * between two operators passes {@link #check()}, as it passes the interrupt check. What
 * the engine cannot stop that way is work it is <em>waiting on</em> — a statement a
 * database is executing, a thread blocked in a driver that ignores interruption. A
 * source doing such work registers how to stop it with {@link #onCancel(Runnable)}, and
 * {@link #cancel()} runs it, from whichever thread asked.
 *
 * <p>Safe to use from any thread: {@code cancel()} is meant to be called by a thread
 * other than the one running the query.
 */
public final class QueryCancellation {

    /** For an execution nothing can cancel: {@link #cancel()} does nothing. */
    public static final QueryCancellation NONE = new QueryCancellation(false);

    private final boolean cancellable;
    /** Guarded by {@code this}; each stays until its registration is closed. */
    private final Set<Runnable> actions = new LinkedHashSet<>();
    private volatile boolean cancelled;

    private QueryCancellation(boolean cancellable) {
        this.cancellable = cancellable;
    }

    /** {@return a signal for one execution, not yet cancelled} */
    public static QueryCancellation create() {
        return new QueryCancellation(true);
    }

    /** A registered action, which its source removes once the work it stops is done. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {

        /** Removes the action; it will not run. */
        @Override
        void close();
    }

    /** {@return whether {@link #cancel()} has been called} */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Raises if this execution has been cancelled.
     *
     * @throws EvaluationException if it has
     */
    public void check() {
        if (cancelled) {
            throw new EvaluationException("query cancelled");
        }
    }

    /**
     * Registers how to stop work this execution is waiting on.
     *
     * <p>If the execution is already cancelled the action also runs at once. A source
     * should still check {@link #isCancelled()} before it starts the work: an action run
     * before the work began may have had nothing to stop.
     *
     * @param action what stops the work, such as {@code statement::cancel}; must not be
     *               null, and must not throw anything the caller needs to see
     * @return the registration, to close once the work is finished
     */
    public Registration onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!cancellable) {
            return () -> { };
        }
        boolean late;
        synchronized (this) {
            actions.add(action);
            late = cancelled;
        }
        if (late) {
            runQuietly(action);
        }
        return () -> {
            synchronized (this) {
                actions.remove(action);
            }
        };
    }

    /**
     * Cancels the execution: every later {@link #check()} raises, and every registered
     * action runs.
     *
     * <p>Calling it again runs the actions still registered again. That is not
     * redundancy: a driver ignores a cancel that reaches a statement in the instant before
     * it starts executing, and a second request is what reaches it then. An action stays
     * registered until the work it stops is finished and its registration is closed.
     *
     * <p>The actions run outside the lock, so one that blocks — a driver's
     * {@code cancel()} can wait on the database — holds up nothing but this call.
     */
    public void cancel() {
        if (!cancellable) {
            return;
        }
        List<Runnable> toRun;
        synchronized (this) {
            cancelled = true;
            toRun = List.copyOf(actions);
        }
        toRun.forEach(QueryCancellation::runQuietly);
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Stopping work is best effort: a driver that cannot cancel a statement leaves
            // it to finish, and the execution still stops at its next row.
        }
    }
}
