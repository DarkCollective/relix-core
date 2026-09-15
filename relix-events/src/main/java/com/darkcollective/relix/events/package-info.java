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
 * Neutral query-pipeline events and the listener seam that carries them.
 *
 * <p>{@link com.darkcollective.relix.events.QueryEvent} is a display-oriented
 * record (a {@link com.darkcollective.relix.events.QueryEvent.Stage}, a code, a
 * human description, and an optional target) that the optimizer and planner emit
 * to a {@link com.darkcollective.relix.events.QueryEventListener}.  Because the
 * event carries no producer-internal types, one listener can observe the whole
 * pipeline as a single chronological feed.
 */
package com.darkcollective.relix.events;
