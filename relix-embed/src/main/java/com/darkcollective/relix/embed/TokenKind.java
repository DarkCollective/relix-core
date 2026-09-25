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
package com.darkcollective.relix.embed;

/**
 * What a {@link Token} is, in the handful of categories a syntax highlighter colours.
 *
 * <p>Deliberately coarse: an editor wants one colour for every relational operator,
 * not one per operator, and a category here does not change when the language gains
 * an operator.
 *
 * @since 1.0
 */
public enum TokenKind {

    /** A relational operator, in either spelling ({@code σ} or {@code SELECT}), or arithmetic. */
    OPERATOR,

    /** A reserved word that is not an operator, comparison or connective: {@code COUNT}, {@code ASC}, {@code TRUE}. */
    KEYWORD,

    /** A name, bare or dotted: a relation, a column, a function. */
    IDENTIFIER,

    /** A string literal, including its quotes. */
    STRING,

    /** A numeric literal. */
    NUMBER,

    /** A comparison or membership operator: {@code = ≠ < ≤ > ≥}, {@code LIKE}, {@code IS}, {@code ∈}, {@code ∉}. */
    COMPARISON,

    /** A logical connective: {@code ∧}, {@code ∨}, {@code ¬}, or their keywords. */
    LOGICAL,

    /** A parenthesis, brace or square bracket. */
    BRACKET,

    /** A separator or binder: a comma, a dot, an arrow, {@code ↔}, a colon. */
    PUNCTUATION,

    /** A line ({@code --}) or block comment. */
    COMMENT,

    /**
     * The rest of the text from the first point it cannot be tokenized: an unterminated
     * string, a half-typed operator, a stray character. Always the last token.
     */
    INCOMPLETE
}
