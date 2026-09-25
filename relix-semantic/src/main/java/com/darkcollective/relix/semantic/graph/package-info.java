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
 * Join-path resolution over the schema graph.
 *
 * <p>{@link com.darkcollective.relix.semantic.graph.JoinPathResolver} takes a
 * generated query and the session's
 * {@link com.darkcollective.relix.symbol.graph.SchemaGraph}, finds the base-
 * relation join at the program's core, and searches the graph for the minimal
 * connected path spanning those relations. It returns a
 * {@link com.darkcollective.relix.symbol.graph.JoinResolution}: a
 * {@code Resolved} program whose join conditions the graph corrected, an
 * {@code Ambiguous} enumeration of equally-minimal paths (the two-FK
 * "did you mean?" case), or a {@code Passthrough} when the graph cannot
 * improve on the model's program.
 *
 * <p>This inverts the model's role: the model extracts <em>what</em> the user
 * wants; the engine assembles <em>how</em> to join it. The rewrite is applied
 * only where it is provably semantics-preserving (a pure join over the same base
 * relations, correcting conditions only); everything else passes through.
 */
package com.darkcollective.relix.semantic.graph;
