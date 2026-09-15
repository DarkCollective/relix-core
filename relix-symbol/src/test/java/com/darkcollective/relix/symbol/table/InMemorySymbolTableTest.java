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
package com.darkcollective.relix.symbol.table;

import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.RegistrationResult;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.SymbolError;
import com.darkcollective.relix.symbol.SymbolTestSupport;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemorySymbolTable — registration, lookup, shadow policy, overloading")
final class InMemorySymbolTableTest extends SymbolTestSupport {

    private SymbolTable table;

    @BeforeEach
    void setUp() {
        table = emptyTable();
    }

    // =========================================================================
    // Relation registration
    // =========================================================================

    @Nested
    @DisplayName("Relation registration")
    class RelationRegistration {

        @Test
        @DisplayName("Registers a relation and returns success result")
        void registersRelationSuccessfully() {
            RegistrationResult result = table.register(dbRelation("Users"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.registered()).isTrue();
        }

        @Test
        @DisplayName("Registers a relation in a custom namespace")
        void registersRelationInCustomNamespace() {
            DatabaseRelationSymbol sym = new DatabaseRelationSymbol(
                    "myschema", "Orders", Provenance.USER, ShadowPolicy.PERMITTED, schema("id"));
            registerOk(table, sym);
            assertThat(table.lookupRelation("myschema", "Orders")).isPresent();
        }

        @Test
        @DisplayName("allSymbols() reflects registered relation")
        void allSymbolsContainsRegisteredRelation() {
            registerOk(table, dbRelation("Users"));
            assertThat(table.allSymbols()).hasSize(1);
        }
    }

    // =========================================================================
    // Relation lookup
    // =========================================================================

    @Nested
    @DisplayName("Relation lookup")
    class RelationLookup {

        @Test
        @DisplayName("lookupRelation(name) finds symbol in default namespace")
        void lookupByNameFindsInDefaultNamespace() {
            registerOk(table, dbRelation("Users"));
            assertThat(table.lookupRelation("Users")).isPresent();
        }

        @Test
        @DisplayName("lookupRelation(name) falls back to builtin namespace")
        void lookupByNameFallsBackToBuiltin() {
            registerOk(table, DatabaseRelationSymbol.builtin("SysLog", schema("id")));
            assertThat(table.lookupRelation("SysLog")).isPresent();
        }

        @Test
        @DisplayName("lookupRelation(name) falls through to custom (non-default/non-builtin) namespace")
        void lookupByNameFallsThroughToCustomNamespace() {
            // Register in a custom namespace (not "default" or "builtin")
            var sym = new DatabaseRelationSymbol("analytics", "Reports",
                    com.darkcollective.relix.symbol.Provenance.USER,
                    ShadowPolicy.PERMITTED, schema("id"));
            registerOk(table, sym);
            // Single-arg lookup should find it via the fallthrough loop
            assertThat(table.lookupRelation("Reports")).isPresent();
        }

        @Test
        @DisplayName("lookupRelation(name) returns empty when not found in either namespace")
        void lookupByNameReturnsEmptyWhenNotFound() {
            assertThat(table.lookupRelation("Nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("lookupRelation(namespace, name) finds symbol in specified namespace")
        void lookupByNamespaceAndName() {
            registerOk(table, dbRelation("Users"));
            assertThat(table.lookupRelation("default", "Users")).isPresent();
        }

        @Test
        @DisplayName("lookupRelation(namespace, name) returns empty for wrong namespace")
        void lookupByNamespaceAndNameReturnsEmptyForWrongNamespace() {
            registerOk(table, dbRelation("Users"));
            assertThat(table.lookupRelation("builtin", "Users")).isEmpty();
        }
    }

    // =========================================================================
    // Case-insensitive lookup
    // =========================================================================

    @Nested
    @DisplayName("Case-insensitive lookup")
    class CaseInsensitiveLookup {

        @Test
        @DisplayName("Relation registered as 'Users' is found by 'users'")
        void relationLookupIsCaseInsensitiveLowercase() {
            registerOk(table, dbRelation("Users"));
            assertThat(table.lookupRelation("users")).isPresent();
        }

        @Test
        @DisplayName("Relation registered as 'Users' is found by 'USERS'")
        void relationLookupIsCaseInsensitiveUppercase() {
            registerOk(table, dbRelation("Users"));
            assertThat(table.lookupRelation("USERS")).isPresent();
        }

        @Test
        @DisplayName("Relation registered as 'Users' is found by mixed case")
        void relationLookupIsCaseInsensitiveMixed() {
            registerOk(table, dbRelation("Users"));
            assertThat(table.lookupRelation("uSeRs")).isPresent();
        }

        @Test
        @DisplayName("Function registered as 'Length' is found by 'length'")
        void functionLookupIsCaseInsensitive() {
            registerOk(table, function("Length", ScalarType.STRING));
            assertThat(table.lookupFunction("length")).isNotEmpty();
        }

        @Test
        @DisplayName("Namespace lookup is case-insensitive")
        void namespaceLookupIsCaseInsensitive() {
            DatabaseRelationSymbol sym = new DatabaseRelationSymbol(
                    "MySchema", "T", Provenance.USER, ShadowPolicy.PERMITTED, schema("id"));
            registerOk(table, sym);
            assertThat(table.lookupRelation("MYSCHEMA", "T")).isPresent();
            assertThat(table.lookupRelation("myschema", "t")).isPresent();
        }
    }

    // =========================================================================
    // Shadow policy
    // =========================================================================

    @Nested
    @DisplayName("Shadow policy")
    class ShadowPolicyBehaviour {

        @Test
        @DisplayName("PERMITTED: second registration replaces first, no error")
        void permittedAllowsSilentReplacement() {
            DatabaseRelationSymbol first = dbRelation("Users");
            DatabaseRelationSymbol second = new DatabaseRelationSymbol(
                    "default", "Users", Provenance.USER, ShadowPolicy.PERMITTED, schema("id", "name"));
            registerOk(table, first);
            RegistrationResult result = table.register(second);
            assertThat(result.isSuccess()).isTrue();
            assertThat(table.lookupRelation("Users"))
                    .isPresent()
                    .get()
                    .satisfies(r -> assertThat(r.schema().width()).isEqualTo(2));
        }

        @Test
        @DisplayName("FORBIDDEN: second registration is rejected with SHADOW_FORBIDDEN error")
        void forbiddenRejectsReplacement() {
            // Register a FORBIDDEN symbol in the builtin namespace
            registerOk(table, DatabaseRelationSymbol.builtin("SysLog", schema("id")));
            // Attempt to register another symbol with the same name in the same namespace
            DatabaseRelationSymbol attempt = new DatabaseRelationSymbol(
                    "builtin", "SysLog", Provenance.USER, ShadowPolicy.PERMITTED, schema("id", "msg"));
            RegistrationResult result = table.register(attempt);
            assertThat(result.isRejected()).isTrue();
            assertThat(result.errors()).hasSize(1)
                    .first()
                    .satisfies(e -> assertThat(e.kind()).isEqualTo(SymbolError.Kind.SHADOW_FORBIDDEN));
        }

        @Test
        @DisplayName("FORBIDDEN: original symbol unchanged after rejected registration")
        void forbiddenLeavesOriginalSymbolUnchanged() {
            registerOk(table, DatabaseRelationSymbol.builtin("SysLog", schema("id")));
            DatabaseRelationSymbol attempt = new DatabaseRelationSymbol(
                    "builtin", "SysLog", Provenance.USER, ShadowPolicy.PERMITTED, schema("id", "extra"));
            table.register(attempt);
            assertThat(table.lookupRelation("builtin", "SysLog"))
                    .isPresent()
                    .get()
                    .satisfies(r -> assertThat(r.schema().width()).isEqualTo(1));
        }

        @Test
        @DisplayName("WARN_AND_PERMIT: registration succeeds with SHADOW_WARNING error")
        void warnAndPermitRegistersWithWarning() {
            DatabaseRelationSymbol first = new DatabaseRelationSymbol(
                    "default", "T", Provenance.USER, ShadowPolicy.WARN_AND_PERMIT, schema("id"));
            registerOk(table, first);
            DatabaseRelationSymbol second = DatabaseRelationSymbol.of("T", schema("id", "name"));
            RegistrationResult result = table.register(second);
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.registered()).isTrue();
            assertThat(result.errors()).hasSize(1)
                    .first()
                    .satisfies(e -> assertThat(e.kind()).isEqualTo(SymbolError.Kind.SHADOW_WARNING));
        }

        @Test
        @DisplayName("WARN_AND_PERMIT: new symbol replaces old one in the table")
        void warnAndPermitReplacesSymbol() {
            DatabaseRelationSymbol first = new DatabaseRelationSymbol(
                    "default", "T", Provenance.USER, ShadowPolicy.WARN_AND_PERMIT, schema("id"));
            registerOk(table, first);
            DatabaseRelationSymbol second = DatabaseRelationSymbol.of("T", schema("id", "name"));
            table.register(second);
            assertThat(table.lookupRelation("T"))
                    .isPresent()
                    .get()
                    .satisfies(r -> assertThat(r.schema().width()).isEqualTo(2));
        }
    }

    // =========================================================================
    // Function registration and lookup
    // =========================================================================

    @Nested
    @DisplayName("Function registration and lookup")
    class FunctionRegistrationAndLookup {

        @Test
        @DisplayName("Registers a function and returns success result")
        void registersFunctionSuccessfully() {
            RegistrationResult result = table.register(function("abs", ScalarType.NUMBER));
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("lookupFunction(name) returns all overloads from default namespace")
        void lookupFunctionByNameReturnsAllOverloads() {
            registerOk(table, function("f", ScalarType.NUMBER));
            registerOk(table, function("f", ScalarType.STRING));
            assertThat(table.lookupFunction("f")).hasSize(2);
        }

        @Test
        @DisplayName("lookupFunction(name) falls back to builtin namespace when not in default")
        void lookupFunctionFallsBackToBuiltin() {
            registerOk(table, builtinFunction("now"));
            assertThat(table.lookupFunction("now")).hasSize(1);
        }

        @Test
        @DisplayName("lookupFunction(name) returns empty list when not found")
        void lookupFunctionReturnsEmptyListWhenNotFound() {
            assertThat(table.lookupFunction("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("lookupFunction(name, paramTypes) finds exact overload")
        void lookupFunctionBySignatureFindsExactOverload() {
            registerOk(table, function("f", ScalarType.NUMBER));
            registerOk(table, function("f", ScalarType.STRING));
            assertThat(table.lookupFunction("f", List.of(ScalarType.NUMBER))).isPresent();
            assertThat(table.lookupFunction("f", List.of(ScalarType.STRING))).isPresent();
        }

        @Test
        @DisplayName("lookupFunction(name, paramTypes) returns empty for unknown signature")
        void lookupFunctionBySignatureReturnsEmptyForUnknownSignature() {
            registerOk(table, function("f", ScalarType.NUMBER));
            assertThat(table.lookupFunction("f", List.of(ScalarType.BOOLEAN))).isEmpty();
        }

        @Test
        @DisplayName("lookupFunction(name, paramTypes) returns empty for wrong arity")
        void lookupFunctionBySignatureReturnsEmptyForWrongArity() {
            registerOk(table, function("f", ScalarType.NUMBER));
            assertThat(table.lookupFunction("f", List.of())).isEmpty();
        }

        @Test
        @DisplayName("lookupFunction(name, paramTypes) falls back to the builtin namespace")
        void lookupFunctionBySignatureFallsBackToBuiltin() {
            registerOk(table, ScalarFunctionSymbol.builder("now")
                    .namespace("builtin")
                    .provenance(Provenance.BUILTIN)
                    .shadowPolicy(ShadowPolicy.FORBIDDEN)
                    .build());
            assertThat(table.lookupFunction("now", List.of())).isPresent();
        }

        @Test
        @DisplayName("the fallthrough skips default and builtin, which it has already searched")
        void fallthroughSkipsTheTwoAlreadySearchedNamespaces() {
            // With entries in all three namespaces the loop actually visits "builtin" and
            // must pass over it — the arm a table holding only a third namespace never
            // reaches, because the loop never sees a key it has to skip.
            registerOk(table, builtinFunction("now"));
            registerOk(table, function("local", ScalarType.NUMBER));
            registerOk(table, ScalarFunctionSymbol.builder("zscore")
                    .namespace("analytics")
                    .parameter("x", ScalarType.NUMBER)
                    .build());

            assertThat(table.lookupFunction("zscore", List.of(ScalarType.NUMBER))).isPresent();
            assertThat(table.lookupFunction("zscore")).hasSize(1);
        }

        @Test
        @DisplayName("lookupFunction(name, paramTypes) falls through to any other namespace")
        void lookupFunctionBySignatureFallsThroughToOtherNamespace() {
            // Neither "default" nor "builtin": the fallthrough loop is the only way to
            // reach it, and it is what lets a script declaring `namespace analytics;`
            // call its own functions.
            registerOk(table, ScalarFunctionSymbol.builder("zscore")
                    .namespace("analytics")
                    .parameter("x", ScalarType.NUMBER)
                    .build());
            assertThat(table.lookupFunction("zscore", List.of(ScalarType.NUMBER))).isPresent();
        }

        @Test
        @DisplayName("The fallthrough matches on signature too, not on name alone")
        void fallthroughStillRequiresASignatureMatch() {
            registerOk(table, ScalarFunctionSymbol.builder("zscore")
                    .namespace("analytics")
                    .parameter("x", ScalarType.NUMBER)
                    .build());
            assertThat(table.lookupFunction("zscore", List.of(ScalarType.STRING))).isEmpty();
        }

        @Test
        @DisplayName("Lookup by signature is case-insensitive in the fallthrough namespaces")
        void fallthroughIsCaseInsensitive() {
            registerOk(table, ScalarFunctionSymbol.builder("zScore")
                    .namespace("analytics")
                    .parameter("x", ScalarType.NUMBER)
                    .build());
            assertThat(table.lookupFunction("ZSCORE", List.of(ScalarType.NUMBER))).isPresent();
        }
    }

    // =========================================================================
    // Dotted function resolution (resolveFunction — the TVF analogue of
    // resolveRelation; powers the relix.* introspection stdlib, #301)
    // =========================================================================

    @Nested
    @DisplayName("resolveFunction (dotted)")
    class ResolveFunction {

        @Test
        @DisplayName("resolves a namespace-qualified name (ns.fn) to that namespace's overloads")
        void resolvesQualified() {
            registerOk(table, ScalarFunctionSymbol.builder("impact")
                    .namespace("relix").parameter("r", ScalarType.STRING).build());
            assertThat(table.resolveFunction("relix.impact")).hasSize(1);
        }

        @Test
        @DisplayName("a bare name still resolves a non-default namespace via the flat fallback")
        void resolvesBareNameAcrossNamespaces() {
            registerOk(table, ScalarFunctionSymbol.builder("impact")
                    .namespace("relix").parameter("r", ScalarType.STRING).build());
            assertThat(table.resolveFunction("impact")).hasSize(1);
        }

        @Test
        @DisplayName("a qualified miss falls back to the flat lookup")
        void qualifiedMissFallsBack() {
            registerOk(table, function("f", ScalarType.NUMBER));
            // "default.f" — namespace-qualified lookup misses (no "default.f"),
            // but the flat fallback finds f in the default namespace.
            assertThat(table.resolveFunction("default.f")).hasSize(1);
        }

        @Test
        @DisplayName("a dot at either end is not a qualifier — the split is bounds-checked")
        void aDotAtTheEdgeIsNotAQualifier() {
            // Both halves of `dot > 0 && dot < length - 1` reject a degenerate spelling,
            // and each rejects a different one. Neither may split, because substring
            // would hand the lookup an empty namespace or an empty name.
            registerOk(table, function("f", ScalarType.NUMBER));
            assertThat(table.resolveFunction(".f")).as("leading dot").isEmpty();
            assertThat(table.resolveFunction("ns.")).as("trailing dot").isEmpty();
            assertThat(table.resolveRelation(".Users")).as("leading dot").isEmpty();
            assertThat(table.resolveRelation("ns.")).as("trailing dot").isEmpty();
        }

        @Test
        @DisplayName("returns empty when neither qualified nor flat lookup matches")
        void unknownIsEmpty() {
            assertThat(table.resolveFunction("relix.nope")).isEmpty();
        }
    }

    // =========================================================================
    // Function overloading
    // =========================================================================

    @Nested
    @DisplayName("Function overloading")
    class FunctionOverloading {

        @Test
        @DisplayName("Two overloads with different arities coexist")
        void twoOverloadsDifferentAritiesCoexist() {
            registerOk(table, function("f", ScalarType.NUMBER));
            registerOk(table, function("f", ScalarType.NUMBER, ScalarType.NUMBER));
            assertThat(table.lookupFunction("f")).hasSize(2);
        }

        @Test
        @DisplayName("Two overloads with different parameter types coexist")
        void twoOverloadsDifferentTypesCoexist() {
            registerOk(table, function("f", ScalarType.NUMBER));
            registerOk(table, function("f", ScalarType.STRING));
            assertThat(table.lookupFunction("f")).hasSize(2);
        }

        @Test
        @DisplayName("Same signature in different namespaces coexist independently")
        void sameSignatureDifferentNamespacesCoexist() {
            ScalarFunctionSymbol defaultFn = function("f", ScalarType.NUMBER);
            ScalarFunctionSymbol builtinFn = ScalarFunctionSymbol.builder("f")
                    .namespace("builtin")
                    .provenance(Provenance.BUILTIN)
                    .shadowPolicy(ShadowPolicy.FORBIDDEN)
                    .parameter("x", ScalarType.NUMBER)
                    .build();
            registerOk(table, defaultFn);
            registerOk(table, builtinFn);
            assertThat(table.lookupFunction("default", "f")).hasSize(1);
            assertThat(table.lookupFunction("builtin", "f")).hasSize(1);
        }

        @Test
        @DisplayName("PERMITTED: same-signature function is silently replaced")
        void permittedAllowsSilentFunctionReplacement() {
            ScalarFunctionSymbol first = ScalarFunctionSymbol.builder("f")
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.NUMBER)
                    .build();
            ScalarFunctionSymbol second = ScalarFunctionSymbol.builder("f")
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.STRING)
                    .build();
            registerOk(table, first);
            RegistrationResult result = table.register(second);
            assertThat(result.isSuccess()).isTrue();
            assertThat(table.lookupFunction("f", List.of(ScalarType.NUMBER)))
                    .isPresent()
                    .get()
                    .satisfies(fn -> assertThat(fn.returnType()).isEqualTo(ScalarType.STRING));
        }

        @Test
        @DisplayName("WARN_AND_PERMIT: same-signature function replacement produces SHADOW_WARNING")
        void warnAndPermitFunctionReplacementProducesWarning() {
            ScalarFunctionSymbol first = ScalarFunctionSymbol.builder("f")
                    .shadowPolicy(ShadowPolicy.WARN_AND_PERMIT)
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.NUMBER)
                    .build();
            ScalarFunctionSymbol second = ScalarFunctionSymbol.builder("f")
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.STRING)
                    .build();
            registerOk(table, first);
            RegistrationResult result = table.register(second);
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.errors().get(0).kind()).isEqualTo(SymbolError.Kind.SHADOW_WARNING);
            assertThat(table.lookupFunction("f", List.of(ScalarType.NUMBER)))
                    .isPresent()
                    .get()
                    .satisfies(fn -> assertThat(fn.returnType()).isEqualTo(ScalarType.STRING));
        }

        @Test
        @DisplayName("lookupFunction(namespace, name) returns empty when namespace exists but name does not")
        void lookupFunctionReturnsEmptyWhenNamespaceExistsButNameDoesNot() {
            registerOk(table, function("f", ScalarType.NUMBER));
            assertThat(table.lookupFunction("default", "g")).isEmpty();
        }

        @Test
        @DisplayName("lookupFunction(name, paramTypes) returns empty when namespace exists but name does not")
        void lookupFunctionBySignatureReturnsEmptyWhenNameDoesNotExist() {
            registerOk(table, function("f", ScalarType.NUMBER));
            assertThat(table.lookupFunction("g", List.of(ScalarType.NUMBER))).isEmpty();
        }

        @Test
        @DisplayName("Function FORBIDDEN policy blocks same-signature replacement")
        void forbiddenFunctionPolicyBlocksReplacement() {
            ScalarFunctionSymbol builtinFn = ScalarFunctionSymbol.builder("abs")
                    .namespace("builtin")
                    .provenance(Provenance.BUILTIN)
                    .shadowPolicy(ShadowPolicy.FORBIDDEN)
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.NUMBER)
                    .build();
            registerOk(table, builtinFn);
            ScalarFunctionSymbol attempt = ScalarFunctionSymbol.builder("abs")
                    .namespace("builtin")
                    .parameter("x", ScalarType.NUMBER)
                    .build();
            RegistrationResult result = table.register(attempt);
            assertThat(result.isRejected()).isTrue();
            assertThat(result.errors().get(0).kind()).isEqualTo(SymbolError.Kind.SHADOW_FORBIDDEN);
        }
    }

    // =========================================================================
    // allSymbols()
    // =========================================================================

    @Nested
    @DisplayName("allSymbols()")
    class AllSymbols {

        @Test
        @DisplayName("Returns empty collection from fresh table")
        void returnsEmptyFromFreshTable() {
            assertThat(table.allSymbols()).isEmpty();
        }

        @Test
        @DisplayName("Returns all registered relations and functions")
        void returnsAllRegisteredSymbols() {
            registerOk(table, dbRelation("Users"));
            registerOk(table, dbRelation("Orders"));
            registerOk(table, function("abs", ScalarType.NUMBER));
            assertThat(table.allSymbols()).hasSize(3);
        }

        @Test
        @DisplayName("allSymbols() result is unmodifiable")
        void resultIsUnmodifiable() {
            registerOk(table, dbRelation("Users"));
            assertThatThrownBy(() ->
                    ((List<Symbol>) table.allSymbols()).add(dbRelation("Extra")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // Dotted-reference resolution (resolveRelation) — shared by inference + plan
    // =========================================================================

    @Nested
    @DisplayName("resolveRelation — flat and dotted references")
    class DottedResolution {

        @Test
        @DisplayName("a dotted name resolves to a namespace-qualified symbol")
        void resolvesNamespaceQualified() {
            // A relation in a non-default namespace, as the relix.* catalog registers.
            registerOk(table, new DatabaseRelationSymbol("relix", "relations",
                    Provenance.BUILTIN, ShadowPolicy.FORBIDDEN, schema("id")));
            assertThat(table.resolveRelation("relix.relations")).isPresent();
        }

        @Test
        @DisplayName("a flat name resolves via the default-namespace lookup")
        void resolvesFlat() {
            registerOk(table, dbRelation("Users"));
            assertThat(table.resolveRelation("Users")).isPresent();
        }

        @Test
        @DisplayName("a dotted name falls back to a flat symbol when the namespace misses")
        void fallsBackToFlatDottedName() {
            // Connection-table convention: registered under the full dotted name.
            registerOk(table, new DatabaseRelationSymbol("default", "conn.orders",
                    Provenance.USER, ShadowPolicy.PERMITTED, schema("id")));
            assertThat(table.resolveRelation("conn.orders")).isPresent();
        }

        @Test
        @DisplayName("relation fallthrough skips default and builtin too")
        void relationFallthroughSkipsSearchedNamespaces() {
            registerOk(table, new DatabaseRelationSymbol("builtin", "Catalog",
                    Provenance.BUILTIN, ShadowPolicy.FORBIDDEN, schema("id")));
            registerOk(table, new DatabaseRelationSymbol("default", "Users",
                    Provenance.USER, ShadowPolicy.PERMITTED, schema("id")));
            registerOk(table, new DatabaseRelationSymbol("analytics", "Sessions",
                    Provenance.USER, ShadowPolicy.PERMITTED, schema("id")));

            assertThat(table.lookupRelation("Sessions")).isPresent();
        }

        @Test
        @DisplayName("an unknown reference resolves to empty")
        void unknownIsEmpty() {
            assertThat(table.resolveRelation("nope.missing")).isEmpty();
            assertThat(table.resolveRelation("missing")).isEmpty();
        }
    }
}
