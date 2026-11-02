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
package com.darkcollective.relix.ast;

/**
 * What a {@link LateralJoinNode} does with a left row for which its right side yields no
 * rows.
 */
public enum LateralKind {
    /** {@code L LATERAL …}: the row is dropped, as an inner join drops it. */
    INNER,
    /** {@code L OUTER LATERAL …}: the row is kept once, its right columns NULL. */
    OUTER
}
