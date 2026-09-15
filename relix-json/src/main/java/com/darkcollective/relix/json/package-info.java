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
/**
 * A minimal, dependency-free streaming JSON writer.
 *
 * <p>{@link com.darkcollective.relix.json.JsonWriter} is a fluent builder that
 * emits compact, spec-correct JSON: it escapes strings, rejects non-finite
 * numbers, and enforces structural well-formedness (balanced objects/arrays,
 * a name before every object value) so malformed output fails fast at the call
 * site rather than silently producing broken JSON.
 *
 * <p>It is intentionally small — there is no parsing, no reflection, and no
 * object mapping. Higher-level serializers walk their own domain types
 * (RA trees, physical plans, optimization records) and drive a
 * {@code JsonWriter} directly.
 */
package com.darkcollective.relix.json;
