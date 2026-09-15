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
package com.darkcollective.relix.lang.ast;

/**
 * Sealed interface for the right-hand side of an assignment statement.
 *
 * <p>An assignment body is either:
 * <ul>
 *   <li>A {@link QueryAssignmentBody} — a relational algebra expression wrapped
 *       in braces: {@code { σ age > 18 (Users) }}</li>
 *   <li>An {@link InlineTableBody} — an inline data table in Markdown or CSV
 *       format: {@code [ | id | name | ... ]} or {@code csv[ ... ]}</li>
 * </ul>
 */
public sealed interface AssignmentBody
        permits QueryAssignmentBody, InlineTableBody {
}
