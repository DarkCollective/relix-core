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
 * How the text of an {@link Input} is written.
 *
 * @since 1.0
 */
public enum InputFormat {

    /**
     * Comma-separated values with a header row naming the columns, read as a
     * {@code csv("…")} source reads a file: RFC 4180 quoting, an empty field a NULL, a row
     * that ends early refused.
     */
    CSV,

    /**
     * Tab-separated values with a header row. A field holds no tab or line break; a
     * backslash escapes one ({@code \t}, {@code \n}, {@code \r}, {@code \\}).
     */
    TSV,

    /** One JSON array of objects, each object a row. Read whole, so it cannot stream. */
    JSON,

    /**
     * One JSON object per line — JSON lines, NDJSON — each a row. The streaming form of
     * JSON, and what {@code jq -c} writes.
     */
    NDJSON
}
