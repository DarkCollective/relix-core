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
package com.darkcollective.relix.lang.ast.source;

/**
 * Extracts tabular data from a CSV response body.
 *
 * <p>Syntax: {@code extract: csv(header: true)}
 *
 * @param hasHeader {@code true} if the first row of the CSV contains column
 *                  names (which are matched against the schema's column names);
 *                  {@code false} if columns are matched positionally
 */
public record CsvExtractSpec(boolean hasHeader) implements ExtractSpec {

    /** Convenience constant: CSV with a header row (the common case). */
    public static final CsvExtractSpec WITH_HEADER = new CsvExtractSpec(true);

    /** Convenience constant: CSV without a header row (positional matching). */
    public static final CsvExtractSpec WITHOUT_HEADER = new CsvExtractSpec(false);
}
