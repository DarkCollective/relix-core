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
 * Sealed interface describing how a column maps to the underlying transport.
 *
 * <p>For {@link ColumnDirection#IN} columns:
 * <ul>
 *   <li>{@link QueryParamBinding} — the column value is appended as a URL query
 *       parameter: {@code as query("q")} → {@code ?q=value}</li>
 *   <li>{@link PathParamBinding} — the column value is substituted into the URL
 *       path template: {@code as path("id")} → {@code /items/{id}}</li>
 *   <li>{@link HeaderBinding} — the column value is sent as a request header:
 *       {@code as header("X-City")}</li>
 * </ul>
 *
 * <p>For {@link ColumnDirection#OUT} columns:
 * <ul>
 *   <li>{@link ExtractPathBinding} — the column value is extracted from the
 *       response body using a JSONPath expression: {@code at "$.main.temp"}</li>
 * </ul>
 *
 * <p>The binding is optional; an {@code OUT} column without a binding is mapped
 * by matching the column name against top-level response fields.
 */
public sealed interface ColumnBinding
        permits QueryParamBinding, PathParamBinding, HeaderBinding, ExtractPathBinding {
}
