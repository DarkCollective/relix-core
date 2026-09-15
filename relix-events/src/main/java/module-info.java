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
/**
 * Observability events for the relix query pipeline.
 *
 * <p>This module defines a single, neutral event vocabulary
 * ({@link com.darkcollective.relix.events.QueryEvent}) and a
 * {@link com.darkcollective.relix.events.QueryEventListener} seam.  Both the
 * optimizer (rule firings) and the planner (physical decisions) translate their
 * internal data into {@code QueryEvent}s and emit them to a listener, so a
 * consumer — the CLI's {@code --trace} output today, a UI later — sees one
 * chronological feed of the decisions that shaped how a query runs, without ever
 * touching the producers' internal types.
 *
 * <p>The module deliberately has no relix dependencies, so it can sit at the
 * bottom of the module graph and be required by every producer and consumer
 * without introducing a cycle.
 */
module com.darkcollective.relix.events {
    exports com.darkcollective.relix.events;
}
