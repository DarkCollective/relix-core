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
package com.darkcollective.relix.lang;

/**
 * All token types produced by the {@link LangLexer} for the relix scripting language.
 *
 * <p>Keyword tokens are matched case-sensitively; HTTP method tokens ({@link #GET},
 * {@link #POST}, etc.) are uppercase, all other keywords are lowercase.
 *
 * <p>When parsing names in structural positions (column names, field keys), the
 * {@link ScriptParser} uses {@link #isNameCompatible()} to accept both
 * {@link #IDENTIFIER} tokens and keyword tokens that were used as identifiers.
 */
public enum LangTokenType {

    // -------------------------------------------------------------------------
    // Statement-level keywords
    // -------------------------------------------------------------------------

    /** {@code namespace} */
    NAMESPACE,
    /** {@code env} */
    ENV,
    /** {@code from} */
    FROM,
    /** {@code using} */
    USING,
    /** {@code import} */
    IMPORT,
    /** {@code source} */
    SOURCE,
    /** {@code connection} */
    CONNECTION,
    /** {@code relation} */
    RELATION,
    /** {@code function} */
    FUNCTION,
    /** {@code private} */
    PRIVATE,
    /** {@code def} */
    DEF,
    /** {@code query} */
    QUERY,
    /** {@code relate} — schema relationship declaration */
    RELATE,
    /** {@code symmetric} — self-referential relationship modifier */
    SYMMETRIC,
    /** {@code references} — FK block on sources / suffix clause on inline tables */
    REFERENCES,

    // -------------------------------------------------------------------------
    // Source-kind keywords
    // -------------------------------------------------------------------------

    /** {@code http} */
    HTTP,
    /** {@code database} */
    DATABASE,
    /** {@code csv} */
    CSV,
    /** {@code json} */
    JSON,
    /** {@code generator} — a code-backed generator source */
    GENERATOR,

    // -------------------------------------------------------------------------
    // Schema direction keywords
    // -------------------------------------------------------------------------

    /** {@code in} — marks an IN column (pushdown parameter) */
    IN,
    /** {@code out} — marks an OUT column (output field) */
    OUT,

    // -------------------------------------------------------------------------
    // Column binding / modifier keywords
    // -------------------------------------------------------------------------

    /** {@code as} — precedes a column binding specification */
    AS,
    /** {@code at} — precedes a JSONPath extract-path binding */
    AT,
    /** {@code path} — binding type: path-parameter */
    PATH,
    /** {@code header} — binding type: HTTP header; also CSV {@code header:} flag */
    HEADER,
    /** {@code headers} — HTTP body field for request headers map */
    HEADERS,
    /** {@code required} — marks an IN column as mandatory */
    REQUIRED,
    /** {@code default} — introduces a default value for an optional IN column or paginate entry */
    DEFAULT,

    // -------------------------------------------------------------------------
    // HTTP source body field keywords
    // -------------------------------------------------------------------------

    /** {@code url} */
    URL,
    /** {@code method} */
    METHOD,
    /** {@code extract} */
    EXTRACT,
    /** {@code paginate} */
    PAGINATE,
    /** {@code schema} */
    SCHEMA,
    /** {@code table} — database source field */
    TABLE,

    // -------------------------------------------------------------------------
    // HTTP method enum values (uppercase).  Relix is read-only by design, so only
    // the read methods exist — PUT/PATCH/DELETE/HEAD are not tokens. The third read
    // method, QUERY, is the `query` statement keyword and needs no token here.
    // -------------------------------------------------------------------------

    GET, POST,

    // -------------------------------------------------------------------------
    // Scalar type keywords
    // -------------------------------------------------------------------------

    /** {@code NUMBER} type keyword */
    NUMBER,
    /** {@code STRING} type keyword */
    STRING,
    /** {@code BOOLEAN} type keyword */
    BOOLEAN,
    /** {@code ANY} type keyword */
    ANY,
    /** {@code DATE} type keyword */
    DATE,
    /** {@code TIME} type keyword */
    TIME,
    /** {@code TIMESTAMP} type keyword */
    TIMESTAMP,
    /** {@code DURATION} type keyword */
    DURATION,

    // -------------------------------------------------------------------------
    // Boolean literals
    // -------------------------------------------------------------------------

    /** {@code true} */
    TRUE,
    /** {@code false} */
    FALSE,

    // -------------------------------------------------------------------------
    // Literals and generic identifiers
    // -------------------------------------------------------------------------

    /** An unrecognised identifier: a name that is not a reserved keyword. */
    IDENTIFIER,
    /**
     * A backtick-delimited name, such as {@code `unit-price`}. The {@link LangToken#value()}
     * holds the name without the backticks, a doubled backtick read as one. Accepted only
     * where a {@code schema:} block names a column or a struct field.
     */
    DELIMITED_IDENTIFIER,
    /** A double-quoted string literal.  The {@link LangToken#value()} holds the
     *  content without the enclosing quotes (escape sequences resolved). */
    STRING_LIT,
    /** An integer or decimal numeric literal. */
    NUMBER_LIT,

    // -------------------------------------------------------------------------
    // Punctuation / operators
    // -------------------------------------------------------------------------

    /** {@code :=} — assignment operator */
    ASSIGN,
    /** {@code :} — colon, field separator */
    COLON,
    /** {@code ;} — statement terminator */
    SEMICOLON,
    /** {@code ,} — list separator */
    COMMA,
    /** {@code (} */
    LPAREN,
    /** {@code )} */
    RPAREN,
    /** <code>&#123;</code> */
    LBRACE,
    /** <code>&#125;</code> */
    RBRACE,
    /** {@code [} */
    LBRACKET,
    /** {@code ]} */
    RBRACKET,
    /** {@code |} — markdown table cell separator */
    PIPE,
    /** {@code -} — used in markdown separator rows */
    DASH,
    /** {@code .} — relation/column separator in relationship endpoints */
    DOT,
    /** {@code ..} — multiplicity-bound range separator */
    RANGE,
    /** {@code ->} — relationship endpoint arrow */
    ARROW,
    /** {@code /} — separator between a relationship name and its inverse name */
    SLASH,
    /** {@code *} — unbounded upper multiplicity bound */
    STAR,

    /** End of input. */
    EOF;

    /**
     * Returns {@code true} if this token type can appear where an unquoted
     * name (column name, field key, etc.) is expected.
     *
     * <p>This allows users to name columns {@code url}, {@code table},
     * {@code source}, etc., without quoting them.
     */
    public boolean isNameCompatible() {
        return this == IDENTIFIER
                || this == NAMESPACE || this == ENV || this == FROM || this == USING
                || this == IMPORT || this == SOURCE || this == CONNECTION
                || this == RELATION || this == FUNCTION
                || this == PRIVATE || this == DEF || this == QUERY
                || this == RELATE || this == SYMMETRIC || this == REFERENCES
                || this == HTTP || this == DATABASE || this == CSV || this == JSON
                || this == GENERATOR
                || this == IN || this == OUT
                || this == AS || this == AT || this == PATH || this == HEADER || this == HEADERS
                || this == REQUIRED || this == DEFAULT
                || this == URL || this == METHOD || this == EXTRACT || this == PAGINATE
                || this == SCHEMA || this == TABLE
                || this == NUMBER || this == STRING || this == BOOLEAN || this == ANY
                || this == DATE || this == TIME || this == TIMESTAMP || this == DURATION
                || this == TRUE || this == FALSE;
    }
}
