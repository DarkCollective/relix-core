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
package com.darkcollective.relix.semantic;

/**
 * Severity level of a {@link SemanticError}.
 *
 * <ul>
 *   <li>{@link #ERROR} — a violation that prevents the script from being used
 *       safely; the semantic model may be absent or partial.</li>
 *   <li>{@link #WARNING} — a suspicious condition that does not prevent analysis
 *       from completing; the model is still fully usable.</li>
 * </ul>
 */
public enum Severity {

    /** A diagnostic that blocks safe use of the script. */
    ERROR,

    /** A suspicious condition; the model is still usable. */
    WARNING
}
