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

import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.FunctionSignature;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * How a function call is resolved: against the installed libraries, through the
 * {@link FunctionCatalog} the analyser was given.
 *
 * <p>The three things that separate this from asking a fixed table of built-ins are
 * all asserted here — a declared arity is a <em>range</em>, a definition may compute
 * its return type from its arguments, and the set of callable functions is whatever
 * the supplied catalogue holds rather than whatever the engine was compiled with.
 * Registration stays lazy, which is what keeps an analysed script's symbols the
 * functions it calls.
 */
@DisplayName("Function calls resolve against the supplied catalogue")
final class FunctionResolutionTest {

    private static final String USERS =
            "Users := [| id | name  | nick |\n"
            + "         | 1  | ada   | a    |];\n";

    /** Analyses {@code src} with exactly the functions {@code functions} offers. */
    private static SemanticResult analyzeWith(FunctionCatalog functions, String src) {
        var analyzer = new SemanticAnalyzer(new InMemoryScriptLoader(Map.of()),
                BuiltinProvider.none(), CatalogProvider.NONE, GeneratorCatalog.NONE, functions);
        return analyzer.analyze(ScriptParser.parse(src, SemanticAnalyzer.STDIN_PATH),
                SemanticAnalyzer.STDIN_PATH);
    }
    // =========================================================================
    // Arity ranges
    // =========================================================================

    @Nested
    @DisplayName("declared argument counts are ranges")
    final class Arities {

        @Test
        @DisplayName("both forms of a function with an optional argument are accepted")
        void optionalTrailingArgument() {
            assertThat(analyze(USERS + "query { π Mid(name, 2) → m (Users) };").hasErrors())
                    .isFalse();
            assertThat(analyze(USERS + "query { π Mid(name, 2, 1) → m (Users) };").hasErrors())
                    .isFalse();
        }

        @Test
        @DisplayName("a call outside the range names the range it missed")
        void outsideTheRange() {
            assertThat(analyze(USERS + "query { π Mid(name) → m (Users) };")).messages()
                    .anyMatch(e -> e.contains("'Mid' called with 1 argument(s) "
                            + "but expects 2 to 3"));
        }

        @Test
        @DisplayName("a second reference is checked against the range, not the symbol")
        void secondReferenceKeepsTheRange() {
            // The first reference registers Mid as a symbol carrying its three named
            // parameters. A check that read the symbol would reject the two-argument
            // form from here on.
            String src = USERS
                    + "First  := { π Mid(name, 2, 1) → m (Users) };\n"
                    + "Second := { π Mid(name, 2) → m (Users) };\n"
                    + "query { Second };";
            assertThat(analyze(src)).hasNoErrors();
        }

        @Test
        @DisplayName("a function taking any number of arguments accepts three")
        void unbounded() {
            assertThat(analyze(USERS
                    + "query { π Coalesce(name, nick, \"?\") → n (Users) };").hasErrors())
                    .isFalse();
        }

        @Test
        @DisplayName("a user def is still checked against its parameter count")
        void userDefinedArity() {
            String src = "def double(x: NUMBER): NUMBER := { x * 2 };\n"
                    + USERS + "query { π double(id, id) → d (Users) };";
            assertThat(analyze(src)).messages()
                    .anyMatch(e -> e.contains("'double' called with 2 argument(s) "
                            + "but expects 1"));
        }
    }

    // =========================================================================
    // Return types
    // =========================================================================

    @Nested
    @DisplayName("a definition may compute its return type")
    final class ReturnTypes {

        private Schema schemaOf(String expression) {
            SemanticModel m = model(USERS + "Out := { " + expression + " };\nquery { Out };");
            return m.symbolTable().resolveRelation("Out").orElseThrow().schema();
        }

        @Test
        @DisplayName("a conditional takes the type its branches agree on")
        void conditionalFollowsItsBranches() {
            assertThat(schemaOf("π IIf(id > 0, id, 0) → v (Users)").columns())
                    .extracting(ColumnDefinition::type)
                    .containsExactly(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("branches that disagree leave the call dynamically typed")
        void mixedBranchesAreAny() {
            assertThat(schemaOf("π IIf(id > 0, name, id) → v (Users)").columns())
                    .extracting(ColumnDefinition::type)
                    .containsExactly(ScalarType.ANY);
        }

        @Test
        @DisplayName("a fixed return type is unaffected by the arguments")
        void fixedReturnType() {
            assertThat(schemaOf("π Len(name) → v (Users)").columns())
                    .extracting(ColumnDefinition::type)
                    .containsExactly(ScalarType.NUMBER);
        }
    }

    // =========================================================================
    // The catalogue is the authority
    // =========================================================================

    @Nested
    @DisplayName("the supplied catalogue decides what is callable")
    final class CatalogueIsTheAuthority {

        @Test
        @DisplayName("a function only a supplied library offers is callable and typed")
        void aSuppliedLibraryIsEnough() {
            SemanticResult result = analyzeWith(FunctionCatalog.of(new TinyLibrary()),
                    USERS + "Out := { π triple(id) → t (Users) };\nquery { Out };");

            assertThat(result).hasNoErrors();
            assertThat(result.model().orElseThrow().symbolTable()
                    .resolveRelation("Out").orElseThrow().schema().columns())
                    .extracting(ColumnDefinition::name, ColumnDefinition::type)
                    .containsExactly(org.assertj.core.api.Assertions.tuple("t", ScalarType.NUMBER));
        }

        @Test
        @DisplayName("a function no installed library offers is unknown")
        void absentFromTheCatalogue() {
            assertThat(analyzeWith(FunctionCatalog.of(new TinyLibrary()),
                    USERS + "query { π UCase(name) → n (Users) };")).messages()
                    .anyMatch(e -> e.contains("Unknown function: 'UCase'"));
        }

        @Test
        @DisplayName("with no library installed, every function call is unknown")
        void emptyCatalogue() {
            assertThat(analyzeWith(FunctionCatalog.empty(),
                    USERS + "query { π Len(name) → n (Users) };")).messages()
                    .anyMatch(e -> e.startsWith("Unknown function: 'Len'"));
        }

        @Test
        @DisplayName("with no library installed the diagnostic says so, rather than "
                + "trailing off after the name")
        void emptyCatalogueSaysWhy() {
            assertThat(analyzeWith(FunctionCatalog.empty(),
                    USERS + "query { π Len(name) → n (Users) };")).messages()
                    .anyMatch(e -> e.contains("no function library is installed")
                            && e.contains("com.darkcollective.relix.function.builtin"));
        }

        @Test
        @DisplayName("suggestions are drawn from the catalogue that is installed")
        void suggestionsComeFromTheCatalogue() {
            assertThat(analyzeWith(FunctionCatalog.of(new TinyLibrary()),
                    USERS + "query { π tripl(id) → t (Users) };")).messages()
                    .anyMatch(e -> e.contains("did you mean 'triple'"));
        }

        @Test
        @DisplayName("the model carries the catalogue it was analysed against")
        void theModelCarriesIt() {
            FunctionCatalog functions = FunctionCatalog.of(new TinyLibrary());
            SemanticModel m = analyzeWith(functions, USERS + "query { Users };")
                    .model().orElseThrow();

            assertThat(m.functions()).isSameAs(functions);
        }
    }

    // =========================================================================
    // Lazy registration
    // =========================================================================

    @Nested
    @DisplayName("a function enters the symbol table on first reference")
    final class LazyRegistration {

        private List<String> functionNames(String src) {
            return model(src).symbolTable().allSymbols().stream()
                    .filter(FunctionSymbol.class::isInstance)
                    .map(FunctionSymbol.class::cast)
                    // The relix.* introspection stdlib is built-in too, and lives in
                    // its own namespace; the library registers into "builtin".
                    .filter(fn -> fn.namespace().equals("builtin"))
                    .map(FunctionSymbol::declaredName)
                    .toList();
        }

        @Test
        @DisplayName("only the functions the script calls are registered")
        void onlyWhatIsCalled() {
            assertThat(functionNames(USERS + "query { π UCase(name) → n (Users) };"))
                    .containsExactly("UCase");
        }

        @Test
        @DisplayName("a script calling nothing registers nothing")
        void nothingCalled() {
            assertThat(functionNames(USERS + "query { Users };")).isEmpty();
        }

        @Test
        @DisplayName("a repeated call registers one symbol")
        void repeatedCall() {
            assertThat(functionNames(USERS
                    + "A := { π UCase(name) → n (Users) };\n"
                    + "B := { π UCase(nick) → n (Users) };\n"
                    + "query { A ∪ B };"))
                    .containsExactly("UCase");
        }
    }

    // =========================================================================
    // The resolution itself
    // =========================================================================

    @Nested
    @DisplayName("an unresolved name answers as an unknown")
    final class Unresolved {

        private final ResolvedFunction unknown = ResolvedFunction.of(
                FunctionCatalog.empty(), "nope", new InMemorySymbolTable());

        @Test
        @DisplayName("it is unknown, accepts no call, and describes no arity")
        void unknownName() {
            assertThat(unknown.isUnknown()).isTrue();
            assertThat(unknown.accepts(0)).isFalse();
            assertThat(unknown.accepts(2)).isFalse();
            assertThat(unknown.expectedArity()).isEqualTo("0");
        }

        @Test
        @DisplayName("its result type is the dynamic one")
        void unknownReturnType() {
            assertThat(unknown.returnType(List.of())).isEqualTo(ScalarType.ANY);
        }
    }

    /** A library offering one function, so a test can say exactly what is callable. */
    private static final class TinyLibrary implements FunctionLibrary {

        @Override
        public String name() {
            return "tiny";
        }

        @Override
        public List<ScalarFunction> scalarFunctions() {
            return List.of(new Triple());
        }
    }

    /** {@code triple(x)} — three times a number, and nothing else. */
    private record Triple() implements StrictScalarFunction {

        @Override
        public FunctionSignature signature() {
            return new FunctionSignature("triple",
                    List.of(param("x", ScalarType.NUMBER)),
                    Arity.exactly(1), ScalarType.NUMBER,
                    Set.of(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC),
                    "math", Optional.empty());
        }

        @Override
        public Value invoke(FunctionContext context, List<Value> arguments) {
            return new NumberValue(((NumberValue) arguments.get(0)).value()
                    .multiply(BigDecimal.valueOf(3)));
        }
    }
}
