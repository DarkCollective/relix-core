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
package com.darkcollective.relix.events;

/**
 * Receives {@link QueryEvent}s as the optimizer and planner make decisions.
 *
 * <p>This is a pure observer: an implementation must not alter the pipeline's
 * behaviour, only react to it (collect for a report, print a trace, push a UI
 * update).  The default {@link #NONE} ignores everything, so emitting events is
 * free until a consumer opts in.
 */
@FunctionalInterface
public interface QueryEventListener {

    /**
     * Called once per observed decision.  Implementations should be fast and must
     * not throw — an emitter is not expected to guard against listener failures.
     *
     * @param event the event; never null
     */
    void onEvent(QueryEvent event);

    /** A listener that ignores every event. */
    QueryEventListener NONE = event -> { };
}
