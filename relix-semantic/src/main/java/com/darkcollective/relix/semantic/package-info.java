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
 * The result of analysing a script, and the seams analysis reads through.
 *
 * <p>{@link com.darkcollective.relix.semantic.SemanticModel} is the analysed script:
 * its symbols, every node's inferred heading
 * ({@link com.darkcollective.relix.semantic.SchemaAnnotations}), its root queries and
 * its schema graph. A problem the analyser found is a
 * {@link com.darkcollective.relix.semantic.SemanticError} of some
 * {@link com.darkcollective.relix.semantic.Severity}.
 *
 * <p>Two seams supply what a script does not carry itself: a
 * {@link com.darkcollective.relix.semantic.ScriptLoader} reads the files an
 * {@code import} names, and a {@link com.darkcollective.relix.semantic.CatalogProvider}
 * describes the tables a connection holds. A
 * {@link com.darkcollective.relix.semantic.CatalogSnapshot} is a catalog captured once and
 * replayed with nothing reachable.
 */
package com.darkcollective.relix.semantic;
