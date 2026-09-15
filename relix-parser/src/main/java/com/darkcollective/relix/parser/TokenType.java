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
package com.darkcollective.relix.parser;

/**
 * Syntactic categories recognised by the {@link Lexer}.
 *
 * <p>Constants are grouped by semantic role:
 * <ul>
 *   <li><b>Literals and identifiers</b> — {@code IDENTIFIER}, {@code STRING},
 *       {@code NUMBER}, {@code TRUE}, {@code FALSE}, and the nullary
 *       truth-relation literals {@code UNIT} (alias {@code DEE}) and
 *       {@code EMPTY} (alias {@code DUM}).</li>
 *   <li><b>Unary RA operators</b> — {@code PROJECT} (π), {@code SELECT} (σ),
 *       {@code RENAME} (ρ), {@code AGGREGATION} (γ), {@code SORT} (τ),
 *       {@code LIMIT} (λ), {@code DISTINCT} (δ).</li>
 *   <li><b>Kleene postfix glyphs</b> — {@code KLEENE_PLUS} (⁺) and the
 *       contextual-{@code MULTIPLY} (*) together with {@code OVER} implement the
 *       postfix closure syntax {@code R⁺ OVER (from, to)} /
 *       {@code R* OVER (from, to)}, producing the same AST as the prefix
 *       {@code CLOSURE} / {@code RCLOSURE} keywords.</li>
 *   <li><b>Sort direction</b> — {@code ASC}, {@code DESC}.</li>
 *   <li><b>Binary RA operators</b> — joins (natural, theta, outer, semi, anti),
 *       set operations (union, union-all, difference, intersection, division),
 *       and Cartesian product.</li>
 *   <li><b>Logical operators</b> — {@code AND} (∧), {@code OR} (∨),
 *       {@code NOT} (¬).</li>
 *   <li><b>Aggregate functions</b> — {@code SUM}, {@code AVG}, {@code COUNT},
 *       {@code MIN}, {@code MAX}.</li>
 *   <li><b>Comparison operators</b> — {@code EQUAL} (=), {@code NOT_EQUAL} (≠),
 *       {@code LESS} (&lt;), {@code LESS_EQUAL} (≤), {@code GREATER} (&gt;),
 *       {@code GREATER_EQUAL} (≥), {@code NULL} (⊥).</li>
 *   <li><b>Arithmetic operators</b> — {@code PLUS}, {@code MINUS},
 *       {@code MULTIPLY}, {@code DIVIDE}.</li>
 *   <li><b>Punctuation</b> — {@code LPAREN}, {@code RPAREN}, {@code COMMA},
 *       {@code DOT}, {@code ARROW} (→), {@code LBRACE}, {@code RBRACE}.</li>
 *   <li><b>Set membership</b> — {@code ELEMENT_OF} (∈),
 *       {@code NOT_ELEMENT_OF} (∉).</li>
 *   <li><b>Sentinel</b> — {@code EOF}.</li>
 * </ul>
 */
public enum TokenType {
    IDENTIFIER,
    STRING,
    NUMBER,
    TRUE,
    FALSE,

    // Nullary truth-relation literals in relation position — the two relations
    // with the empty (zero-column) heading. UNIT (alias DEE) holds the empty
    // tuple; EMPTY (alias DUM) holds no tuple.
    UNIT,
    EMPTY,

    // Typed temporal literal keywords — each introduces a quoted ISO-8601
    // payload: DATE '…', TIME '…', TIMESTAMP '…', DURATION '…' (ADR-0013).
    DATE,
    TIME,
    TIMESTAMP,
    DURATION,

    PROJECT,
    SELECT,
    RENAME,
    AGGREGATION,
    SORT,
    LIMIT,
    DISTINCT,
    UNNEST,
    CLOSURE,
    RCLOSURE,
    CLUSTER,
    AS,
    PATH,
    HOPS,
    FIX,
    KLEENE_PLUS,
    OVER,

    ASC,
    DESC,

    NATURAL_JOIN,
    THETA_JOIN,
    LEFT_OUTER_JOIN,
    RIGHT_OUTER_JOIN,
    FULL_OUTER_JOIN,
    SEMI_JOIN,
    ANTI_JOIN,
    UNIVERSAL_SEMI_JOIN,
    ASOF_JOIN,
    IJOIN,
    WITHIN,
    TIES,
    PRODUCT,
    UNION,
    UNION_ALL,
    OUTER_UNION,
    DIFFERENCE,
    INTERSECTION,
    DIVISION,
    SYMMETRIC_DIFFERENCE,
    COMPOSITION,
    FORALL,
    SAMPLE,
    SEED,
    SOLVE,
    OPTIMIZE,
    ALLOCATE,
    MAXIMIZE,
    MINIMIZE,
    SUBJECT,
    TO,
    TOP,
    PER,
    ROWS,
    WITH,
    ORDINALITY,
    COVER,
    EXACT,
    DOWNSAMPLE,
    BY,
    USING,
    FOR,
    LATERAL,
    ROLLING,
    WINDOW,
    SESSIONIZE,
    GAP,
    TRACE,
    VIA,
    PIVOT,
    UNPIVOT,
    TREE,
    ORDER,
    WHY,

    AND,
    OR,
    NOT,

    SUM,
    AVG,
    COUNT,
    MIN,
    MAX,
    COLLECT,
    ARGMAX,
    ARGMIN,

    EQUAL,
    NOT_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    NULL,
    IS,
    LIKE,

    PLUS,
    MINUS,
    MULTIPLY,
    DIVIDE,

    LPAREN,
    RPAREN,
    COMMA,
    DOT,
    ARROW,
    EOF,

    ELEMENT_OF,
    NOT_ELEMENT_OF,
    LBRACE,
    RBRACE,
    LBRACKET,
    RBRACKET,
    COLON
}
