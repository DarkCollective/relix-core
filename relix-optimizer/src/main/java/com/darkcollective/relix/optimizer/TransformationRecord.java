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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.optimizer.internal.OptimizationContext;
import com.darkcollective.relix.ast.SourceLocation;

import java.util.Objects;

/**
 * An immutable record of a single optimization transformation applied during
 * query planning.
 *
 * <p>Each record captures exactly one rule firing:
 * <ul>
 *   <li>The {@link OptimizationCode} identifying which rule fired.</li>
 *   <li>The name of the relation or query being optimized at the time.</li>
 *   <li>A short human-readable {@code detail} string describing what
 *       specifically changed (e.g.
 *       {@code "σ (a > 0 ∧ b > 0) split into two stacked selections"}).</li>
 *   <li>The {@link SourceLocation} of the original AST node that was
 *       rewritten, for traceability back to the source file.</li>
 * </ul>
 *
 * <p>Records are produced exclusively by
 * {@link OptimizationContext#record(OptimizationCode, String, String, SourceLocation)}
 * and are never mutated after creation.
 *
 * @param code         the optimization rule that produced this record
 * @param relationName the name of the relation/query being optimized;
 *                     must not be blank
 * @param detail       free-text description of what changed; must not be blank
 * @param location     source location of the transformed AST node; must not be null
 */
public record TransformationRecord(
        OptimizationCode code,
        String           relationName,
        String           detail,
        SourceLocation   location
) {
    public TransformationRecord {
        Objects.requireNonNull(code,         "code");
        Objects.requireNonNull(relationName, "relationName");
        if (relationName.isBlank()) {
            throw new IllegalArgumentException("relationName must not be blank");
        }
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        Objects.requireNonNull(location, "location");
    }

    /**
     * Returns a single-line summary suitable for display in logs and reports.
     * Format: {@code "[CODE] relationName — detail @ location}.
     *
     * @return formatted string; never null
     */
    @Override
    public String toString() {
        return "[" + code.code() + "] " + relationName + " — " + detail
                + " @ " + location;
    }
}
