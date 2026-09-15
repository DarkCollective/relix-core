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

import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.lang.ast.Script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared test fixtures for running the semantic-analysis pipeline over inline
 * {@code .relix} source — the single home for the {@code analyze()}/{@code model()}
 * helpers that test classes across every module previously copy-pasted.
 *
 * <p>Published via Gradle test fixtures; consume from another module with
 * {@code testImplementation testFixtures(project(':relix-semantic'))} and import
 * statically:
 * <pre>{@code
 * import static com.darkcollective.relix.semantic.SemanticFixtures.*;
 * SemanticModel m = model("π name (Users) …");
 * }</pre>
 *
 * <p>These helpers are a <em>frontend</em> composition — parse with
 * {@link ScriptParser}, then hand the AST to the engine — which is exactly what
 * ADR-0025 Decision 3 keeps available to tests after Decision 2 took the grammar
 * off {@code relix-semantic}'s {@code main} classpath.  The signatures are
 * unchanged, so no calling test had to move.
 */
public final class SemanticFixtures {

    /**
     * The functions these fixtures analyse against: every installed
     * {@link com.darkcollective.relix.function.FunctionLibrary}, discovered once.
     *
     * <p>Held as a constant rather than discovered per call for the reason the engine
     * passes one catalogue down its whole pipeline — two catalogues built separately
     * can disagree — and because discovery is a service-loader scan that a test suite
     * has no reason to repeat.  A test that constructs a phase directly (a
     * {@code SchemaInferenceVisitor}, a validator) should hand it this, so it resolves
     * the same names {@link #analyze(String)} does.
     */
    public static final FunctionCatalog FUNCTIONS = FunctionCatalog.discover();

    private SemanticFixtures() {}

    /**
     * Analyzes {@code src} with no imports and returns the full result (model and
     * any errors), so callers can assert on diagnostics.
     *
     * @param src the {@code .relix} source
     * @return the semantic analysis result
     */
    public static SemanticResult analyze(String src) {
        return analyze(src, Map.of());
    }

    /**
     * Analyzes {@code src}, resolving the given imports.
     *
     * @param src     the {@code .relix} source
     * @param imports import path → import source text; each is parsed with
     *                {@link ScriptParser}
     * @return the semantic analysis result
     */
    public static SemanticResult analyze(String src, Map<String, String> imports) {
        Map<String, Script> scripts = new LinkedHashMap<>();
        imports.forEach((path, text) -> scripts.put(path, ScriptParser.parse(text)));
        var analyzer = analyzer(new InMemoryScriptLoader(scripts));
        return analyzer.analyze(ScriptParser.parse(src, SemanticAnalyzer.STDIN_PATH),
                SemanticAnalyzer.STDIN_PATH);
    }

    /**
     * Analyzes {@code src} with {@code sessionEvents} supplied as the previous
     * run's observability feed — the extent {@code relix.events} is built from.
     *
     * <p>The feed is a property of the <em>analyzer</em>, not of the source, so a
     * test that wants a non-empty {@code relix.events} cannot express it in the
     * script; this is the seam for it, and the reason it belongs here rather than
     * being rebuilt in each module's test class (a downstream module's test
     * classpath does not carry the {@code .relix} grammar).
     *
     * @param src           the {@code .relix} source
     * @param sessionEvents the previous run's events, in arrival order
     * @return the semantic analysis result
     */
    public static SemanticResult analyzeObserving(String src, List<QueryEvent> sessionEvents) {
        var analyzer = analyzer(new InMemoryScriptLoader(Map.of()))
                .withSessionEvents(sessionEvents);
        return analyzer.analyze(ScriptParser.parse(src, SemanticAnalyzer.STDIN_PATH),
                SemanticAnalyzer.STDIN_PATH);
    }

    /**
     * The diagnostic messages of {@code result}, in report order.
     *
     * <p>Ten test classes across two modules each defined this projection under a name
     * of their own.  Most of them were making an assertion and now state it through
     * {@code SemanticResultAssert}; this is for the two that are not — they fold the
     * messages into a failure <em>string</em> they accumulate, which no assert can
     * express — so the projection still has exactly one home.
     *
     * @param result the analysis result; must not be null
     * @return the messages of every diagnostic, errors and warnings alike
     */
    public static List<String> errorMessages(SemanticResult result) {
        return result.errors().stream().map(SemanticError::message).toList();
    }

    /**
     * An analyser over {@code loader} resolving calls against {@link #FUNCTIONS}.
     *
     * @param loader the script loader used to resolve imports
     * @return the analyser
     */
    public static SemanticAnalyzer analyzer(ScriptLoader loader) {
        return new SemanticAnalyzer(loader, BuiltinProvider.none(), CatalogProvider.NONE,
                GeneratorCatalog.NONE, FUNCTIONS);
    }

    /**
     * Analyzes {@code src} and returns its {@link SemanticModel}, failing if the
     * pipeline produced no model.  Use for valid scripts where the model is the
     * point; use {@link #analyze(String)} when asserting on errors.
     *
     * @param src the {@code .relix} source
     * @return the semantic model
     */
    public static SemanticModel model(String src) {
        return model(src, Map.of());
    }

    /**
     * Analyzes {@code src} with imports and returns its {@link SemanticModel}.
     *
     * @param src     the {@code .relix} source
     * @param imports import path → import source text
     * @return the semantic model
     */
    public static SemanticModel model(String src, Map<String, String> imports) {
        SemanticResult result = analyze(src, imports);
        return result.model().orElseThrow(() -> new IllegalStateException(
                "expected a model but analysis produced none; errors: " + result.errors()));
    }
}
