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
 * Binary comparison operators used in {@link ComparisonPredicate}.
 */
public enum ComparisonOperator {
    /** Equality ({@code =}). */
    EQUAL("="),
    /** Inequality ({@code ≠}). */
    NOT_EQUAL("≠"),
    /** Strict less-than ({@code <}). */
    LESS("<"),
    /** Less-than-or-equal ({@code ≤}). */
    LESS_EQUAL("≤"),
    /** Strict greater-than ({@code >}). */
    GREATER(">"),
    /** Greater-than-or-equal ({@code ≥}). */
    GREATER_EQUAL("≥");

    private final String symbol;

    ComparisonOperator(String symbol) {
        this.symbol = symbol;
    }

    /**
     * The operator's canonical display symbol, in the Unicode form the language
     * reference uses — {@code =}, {@code ≠}, {@code <}, {@code ≤}, {@code >},
     * {@code ≥}.
     *
     * <p>This is the single source of truth for <em>rendering an operator to a
     * human</em>: the pretty-printer, the IR report ({@code :tree}), the physical
     * plan printer ({@code :explain}), and optimizer trace messages all use it, so
     * one predicate reads the same everywhere. These previously carried separate
     * copies that had drifted — {@code :tree} showed {@code a ≤ 5} while
     * {@code :explain} showed {@code a <= 5} for the same predicate.
     *
     * <p>Not for wire formats. A backend's spelling is that backend's concern:
     * {@code SqlExpressions} emits SQL {@code <=} and {@code MongoExpressions}
     * emits {@code $lte}, and both deliberately keep their own mapping.
     *
     * @return the display symbol; never null
     */
    public String symbol() {
        return symbol;
    }
}
