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
 * A minimal, dependency-free JSON reader and writer for the relix toolchain.
 *
 * <p>This module exposes three types:
 * {@link com.darkcollective.relix.json.JsonWriter} — a small streaming builder
 * that produces compact, correctly-escaped JSON text —
 * {@link com.darkcollective.relix.json.JsonStrings}, the string-literal escaping
 * primitive the writer is built on, for callers that emit a JSON fragment by
 * hand (a MongoDB pipeline stage, a JSON-Lines log record) and need only that
 * piece, and {@link com.darkcollective.relix.json.JsonReader}, which reads a
 * document back as plain maps and lists. Together they exist so that the various
 * machine-readable serializers (the IR/plan/optimizer JSON emitters and the CLI's
 * query bundle for the playground) share one correct JSON primitive rather than
 * each hand-rolling string concatenation.
 *
 * <p>Reading and writing sit together deliberately. Escaping is the half of the
 * JSON grammar most easily gotten subtly wrong, and a parser that unescapes in a
 * different module from the writer that escapes is a contract nothing checks —
 * which is what {@code JsonReaderTest}'s round-trip cases now assert.
 *
 * <p>Not every JSON reader belongs here: {@code JsonValues} in {@code relix-value}
 * builds the relix {@code Value} tree that data arriving from a source becomes,
 * and returning that type would cost this module its leaf position.
 *
 * <p>The module deliberately has no relix dependencies, so it can sit at the
 * bottom of the module graph and be required by every producer without
 * introducing a cycle — the same role {@code relix-events} plays for
 * observability.
 */
module com.darkcollective.relix.json {
    exports com.darkcollective.relix.json;
}
