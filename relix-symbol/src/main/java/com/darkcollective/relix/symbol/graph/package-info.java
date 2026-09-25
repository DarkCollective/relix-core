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
 * The schema graph: how relations join.
 *
 * <p>A {@link com.darkcollective.relix.symbol.graph.SchemaGraph} holds named
 * {@link com.darkcollective.relix.symbol.graph.Relationship}s between two
 * {@link com.darkcollective.relix.symbol.graph.Endpoint}s, each declared, introspected or
 * learned ({@link com.darkcollective.relix.symbol.graph.EdgeOrigin}). Resolving an
 * expression's joins over the graph answers a
 * {@link com.darkcollective.relix.symbol.graph.JoinResolution}.
 */
package com.darkcollective.relix.symbol.graph;
