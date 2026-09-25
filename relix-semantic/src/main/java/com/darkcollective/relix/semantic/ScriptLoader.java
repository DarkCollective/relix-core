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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.semantic.internal.SemanticAnalyzer;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptParseException;

import java.io.IOException;

/**
 * Strategy for resolving a path to a {@link Script} — the engine's pluggable
 * "where does a {@code Script} come from" seam.
 *
 * <p>This abstraction decouples the semantic analyser from both the file system
 * and any concrete syntax: an implementation may parse {@code .relix} text, read
 * a serialized query, or serve pre-built ASTs from a map.  The engine only ever
 * sees the resulting {@code Script}, which is why a failure to produce one is
 * reported as the core-level {@link ScriptParseException} rather than any one
 * frontend's own exception type.
 *
 * <p>Implementations must be thread-safe if the analyser is used concurrently.
 *
 * <p>Example — a text frontend's file-system loader (it parses {@code .relix},
 * so it lives in {@code relix-console}, not here):
 * <pre>
 *   ScriptLoader loader = new FileSystemScriptLoader(Paths.get("/project/scripts"));
 *   SemanticAnalyzer analyzer = new SemanticAnalyzer(loader);
 * </pre>
 *
 * <p>Example — serving an AST held in memory:
 * <pre>
 *   Script script = ...;                    // parsed, or built programmatically
 *   ScriptLoader loader = path -> script;
 *   SemanticAnalyzer analyzer = new SemanticAnalyzer(loader);
 * </pre>
 */
@FunctionalInterface
public interface ScriptLoader {

    /**
     * Loads the script at the given path.
     *
     * @param path the file path as written in the {@code import} or root-file
     *             argument; interpretation (absolute, relative, logical) is
     *             implementation-defined
     * @return the loaded script; never null
     * @throws IOException            if the source cannot be read
     * @throws ScriptParseException   if the source cannot be turned into a
     *                                {@code Script} (for a text frontend: a
     *                                syntax error)
     */
    Script load(String path) throws IOException;
}
