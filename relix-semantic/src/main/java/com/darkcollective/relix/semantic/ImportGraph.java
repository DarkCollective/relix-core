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

import com.darkcollective.relix.lang.ast.ImportStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.Statement;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Directed dependency graph of all {@code .relix} files reachable from a root.
 *
 * <p>Built by {@link #build} which loads files transitively via a
 * {@link ScriptLoader}, detects import cycles, and records missing-file errors.
 * After construction the graph is immutable.
 *
 * <p>Use {@link #processingOrder()} to obtain the files in dependency-first
 * order — every file appears after all files it imports — suitable as the
 * iteration order for the symbol-collection pass.
 *
 * <h2>Cycle detection</h2>
 * Cycles are detected during loading via a DFS in-progress set.  When a cycle
 * is found the cycle path is recorded as a {@link SemanticError} (line 0,
 * col 0), loading of that branch is abandoned, and loading continues from
 * other branches.  All errors are accessible via {@link #loadErrors()}.
 *
 * <h2>Path resolution</h2>
 * Import paths written in source ({@code import './helpers.relix'}) are
 * resolved relative to the importing file's directory before being used as
 * graph node keys.  See {@link #resolvePath(String, String)}.
 */
final class ImportGraph {

    private final String rootPath;

    /** Successfully loaded scripts, in load order (insertion-ordered). */
    private final Map<String, Script> scripts;

    /** For each loaded file: the set of resolved paths it directly imports. */
    private final Map<String, Set<String>> dependencies;

    /** Errors accumulated during loading (cycles, missing files). */
    private final List<SemanticError> loadErrors;

    private ImportGraph(String rootPath,
                        Map<String, Script> scripts,
                        Map<String, Set<String>> dependencies,
                        List<SemanticError> loadErrors) {
        this.rootPath     = rootPath;
        this.scripts      = scripts;
        this.dependencies = dependencies;
        this.loadErrors   = loadErrors;
    }

    // =========================================================================
    // Factory
    // =========================================================================

    /**
     * Loads the root file (and all transitive imports) and builds the
     * dependency graph.
     *
     * @param rootPath      the canonical path used as the root node key;
     *                      may be {@link SemanticAnalyzer#STDIN_PATH}
     * @param preloadedRoot the already-parsed root script, or {@code null} to
     *                      load it via {@code loader}
     * @param loader        used to load all imported files (and the root when
     *                      {@code preloadedRoot} is {@code null})
     * @return the built graph; never null
     */
    static ImportGraph build(String rootPath,
                             Script preloadedRoot,
                             ScriptLoader loader) {
        var scripts      = new LinkedHashMap<String, Script>();
        var dependencies = new LinkedHashMap<String, LinkedHashSet<String>>();
        var errors       = new ArrayList<SemanticError>();
        var inProgress   = new LinkedHashSet<String>();

        loadRecursive(rootPath, preloadedRoot, loader,
                      scripts, dependencies, errors, inProgress);

        // Freeze: make each dependency set unmodifiable
        var frozenDeps = new LinkedHashMap<String, Set<String>>(dependencies.size());
        dependencies.forEach((k, v) -> frozenDeps.put(k, Collections.unmodifiableSet(v)));

        return new ImportGraph(
                rootPath,
                Collections.unmodifiableMap(scripts),
                Collections.unmodifiableMap(frozenDeps),
                List.copyOf(errors));
    }

    // =========================================================================
    // Recursive loader
    // =========================================================================

    private static void loadRecursive(
            String path,
            Script preloaded,
            ScriptLoader loader,
            Map<String, Script> scripts,
            Map<String, LinkedHashSet<String>> dependencies,
            List<SemanticError> errors,
            LinkedHashSet<String> inProgress) {

        // Cycle detected — path is currently being loaded higher in the call stack.
        // Must be checked BEFORE the scripts map because a node is added to scripts
        // before it finishes processing (while still in inProgress).
        if (inProgress.contains(path)) {
            errors.add(SemanticError.error(path, 0, 0,
                    "Import cycle detected: " + buildCyclePath(path, inProgress)));
            return;
        }

        // Already fully loaded — nothing to do
        if (scripts.containsKey(path)) return;

        // Load the script (either from the preloaded value or via the loader)
        Script script;
        if (preloaded != null) {
            script = preloaded;
        } else {
            try {
                script = loader.load(path);
            } catch (IOException e) {
                errors.add(SemanticError.error(path, 0, 0,
                        "Cannot load '" + path + "': " + e.getMessage()));
                return;
            }
        }

        // Register the script and open a (currently empty) dependency set
        scripts.put(path, script);
        var deps = new LinkedHashSet<String>();
        dependencies.put(path, deps);

        // DFS: recurse into each imported file
        inProgress.add(path);
        for (Statement stmt : script.statements()) {
            if (stmt instanceof ImportStatement imp) {
                String resolved = resolvePath(path, imp.sourcePath());
                deps.add(resolved);
                loadRecursive(resolved, null, loader,
                              scripts, dependencies, errors, inProgress);
            }
        }
        inProgress.remove(path);
    }

    /**
     * Builds a human-readable cycle description from the in-progress path set.
     * Example: for cycle A→B→C→A the result is {@code "A → B → C → A"}.
     */
    private static String buildCyclePath(String cyclePath,
                                         LinkedHashSet<String> inProgress) {
        var cycle = new ArrayList<String>();
        boolean collecting = false;
        for (String p : inProgress) {
            if (p.equals(cyclePath)) collecting = true;
            if (collecting) cycle.add(p);
        }
        cycle.add(cyclePath); // close the cycle
        return String.join(" → ", cycle);
    }

    // =========================================================================
    // Query API
    // =========================================================================

    /**
     * The canonical path of the root file (may be
     * {@link SemanticAnalyzer#STDIN_PATH} when reading from stdin).
     */
    String rootPath() { return rootPath; }

    /**
     * Returns the successfully-parsed script for {@code path}, or
     * {@link Optional#empty()} if loading failed (cycle or missing file).
     */
    Optional<Script> script(String path) {
        return Optional.ofNullable(scripts.get(path));
    }

    /**
     * Returns all successfully loaded paths, in load order (root first,
     * then imports discovered depth-first).
     */
    Set<String> paths() { return scripts.keySet(); }

    /**
     * Returns errors encountered during loading (cycles, missing files).
     * Empty when all files loaded successfully.
     */
    List<SemanticError> loadErrors() { return loadErrors; }

    /**
     * Returns {@code true} if any load errors were recorded.
     */
    boolean hasLoadErrors() { return !loadErrors.isEmpty(); }

    /**
     * Returns all successfully loaded paths in <em>dependency-first</em> order:
     * every file appears after all files it transitively imports.
     *
     * <p>This is the correct iteration order for the symbol-collection pass —
     * when processing file {@code A}, all symbols exported by files that
     * {@code A} imports are already registered.
     *
     * <p>Implemented as a DFS post-order traversal starting from the root.
     * Files that failed to load (due to cycles or missing files) are excluded.
     */
    List<String> processingOrder() {
        var result  = new ArrayList<String>();
        var visited = new LinkedHashSet<String>();
        for (String path : scripts.keySet()) {
            dfsPostOrder(path, visited, result);
        }
        return Collections.unmodifiableList(result);
    }

    private void dfsPostOrder(String path,
                               Set<String> visited,
                               List<String> result) {
        if (visited.contains(path)) return;
        if (!scripts.containsKey(path)) return; // failed to load, skip

        visited.add(path);
        for (String dep : dependencies.getOrDefault(path, Set.of())) {
            dfsPostOrder(dep, visited, result);
        }
        result.add(path); // post-order: add AFTER visiting all dependencies
    }

    // =========================================================================
    // Path resolution
    // =========================================================================

    /**
     * Resolves an import path written in source relative to the importing
     * file's directory, producing a normalized path suitable as a graph key.
     *
     * <p>Examples:
     * <pre>
     *   resolvePath("scripts/a.relix", "./b.relix")         → "scripts/b.relix"
     *   resolvePath("scripts/a.relix", "../common/b.relix") → "common/b.relix"
     *   resolvePath("a.relix",         "./b.relix")         → "b.relix"
     *   resolvePath("&lt;stdin&gt;",  "./b.relix")         → "b.relix"
     *   resolvePath("&lt;ast&gt;",    "./b.relix")         → "b.relix"
     *   resolvePath("any",             "/abs/b.relix")      → "/abs/b.relix"
     * </pre>
     *
     * @param basePath   the path of the importing file
     * @param importPath the path as written in the {@code import} statement
     * @return the normalized, resolved path
     */
    static String resolvePath(String basePath, String importPath) {
        // Absolute paths are used as-is
        if (importPath.startsWith("/")) {
            return importPath;
        }
        // A synthetic root path has no directory component — and is not a legal
        // file name on every platform, so it must never reach Paths.get.
        if (isSyntheticRoot(basePath)) {
            return Paths.get(importPath).normalize().toString();
        }
        try {
            var parent = Paths.get(basePath).getParent();
            if (parent == null) {
                // basePath has no directory component (e.g. bare "a.relix")
                return Paths.get(importPath).normalize().toString();
            }
            return parent.resolve(importPath).normalize().toString();
        } catch (Exception e) {
            return importPath; // unexpected: return unchanged as a safe fallback
        }
    }

    /**
     * Returns {@code true} for the placeholder root paths the analyser uses when
     * the root script did not come from a file — {@code "<stdin>"} and
     * {@code "<ast>"}.
     */
    private static boolean isSyntheticRoot(String basePath) {
        return basePath.equals(SemanticAnalyzer.STDIN_PATH)
                || basePath.equals(SemanticAnalyzer.AST_PATH);
    }
}
