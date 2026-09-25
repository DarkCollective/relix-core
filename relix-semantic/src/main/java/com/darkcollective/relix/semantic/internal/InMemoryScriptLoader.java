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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.semantic.ScriptLoader;
import com.darkcollective.relix.lang.ast.Script;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link ScriptLoader} that serves pre-parsed {@link Script}s from an
 * in-memory map, keyed by path string.
 *
 * <p>It serves {@code Script}s that are <em>already built</em> and so needs no
 * grammar of its own — which is why it stays in the engine while the text
 * frontend's {@code FileSystemScriptLoader} moved to {@code relix-console}. Where the scripts in
 * the map came from is the caller's
 * business: parsed {@code .relix}, deserialized, or hand-built.
 *
 * <p>Primarily intended for tests and embedded sessions — it lets you build
 * multi-file import scenarios without touching the file system:
 * <pre>
 *   Script main    = ScriptParser.parse("import './helpers.relix'; ...");
 *   Script helpers = ScriptParser.parse("def double(x: NUMBER): NUMBER := { x * 2 };");
 *
 *   var loader = new InMemoryScriptLoader(Map.of(
 *       "main.relix",    main,
 *       "helpers.relix", helpers));
 *
 *   SemanticResult result = new SemanticAnalyzer(loader).analyze("main.relix");
 * </pre>
 *
 * <p>Path keys should be <em>normalized</em> (no {@code .} or {@code ..}
 * components).  The loader performs exact key lookup and does not normalize
 * paths before matching.  Use {@link ImportGraph#resolvePath} to normalize
 * import paths when constructing the map.
 *
 * <p>The map is defensively copied at construction; subsequent changes to the
 * original map do not affect this loader.  This class is thread-safe.
 */
public final class InMemoryScriptLoader implements ScriptLoader {

    private final Map<String, Script> scripts;

    /**
     * Creates a loader backed by the given path-to-script map.
     *
     * @param scripts the registered scripts; must not be null
     * @throws NullPointerException if {@code scripts} is null
     */
    public InMemoryScriptLoader(Map<String, Script> scripts) {
        this.scripts = Map.copyOf(Objects.requireNonNull(scripts, "scripts"));
    }

    /**
     * Returns the pre-parsed script registered for {@code path}.
     *
     * @param path the exact key to look up (case-sensitive, not normalized)
     * @return the script for that path
     * @throws IOException          if no script is registered under {@code path}
     * @throws NullPointerException if {@code path} is null
     */
    @Override
    public Script load(String path) throws IOException {
        Objects.requireNonNull(path, "path");
        Script script = scripts.get(path);
        if (script == null) {
            throw new IOException("No script registered for path: '" + path + "'");
        }
        return script;
    }

    /**
     * Returns an unmodifiable view of the registered path→script map.
     *
     * @return the script map; never null
     */
    public Map<String, Script> scripts() {
        return scripts;
    }
}
