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
package com.darkcollective.relix.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.darkcollective.relix.provenance.SecurityLevel.CONFIDENTIAL;
import static com.darkcollective.relix.provenance.SecurityLevel.PUBLIC;
import static com.darkcollective.relix.provenance.SecurityLevel.SECRET;
import static com.darkcollective.relix.provenance.SecurityLevel.TOP_SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commutative-semiring laws, asserted for every semiring {@link Semirings}
 * <em>registers</em> rather than for every one somebody wrote a test class for.
 *
 * <p>{@link SemiringLaws} was already the right shape — the algebraic contract written
 * once and called per semiring — but the call sites were six separate
 * {@code XSemiringTest.satisfiesSemiringLaws} methods with nothing joining them to the
 * registry. All six built-ins happened to have one; a seventh would get law coverage
 * only if its author remembered, and no test could have said it was missing.
 *
 * <p>{@code Semirings.names()} is the domain. The samples are hand-written, because a
 * {@code Semiring<K>}'s carrier type is erased and its representative values cannot be
 * synthesised from the interface — which is what makes
 * {@link #everyRegisteredSemiringHasASample()} the load-bearing half: a name with no
 * sample fails this suite rather than silently testing five semirings out of six.
 *
 * <p>{@code Semiring} is deliberately not sealed — it is an extension point, and a
 * third party's implementation is outside any domain this repository can enumerate.
 * The registry is the line: what the engine resolves by name is what it ships, and
 * that is what must obey the laws.
 */
@DisplayName("Every registered semiring obeys the commutative-semiring laws")
final class RegisteredSemiringLawsTest {

    /**
     * A representative sample per registered semiring, including its own {@code zero}
     * and {@code one} and enough values to exercise commutativity, associativity and
     * distributivity. These are the samples the per-semiring suites used to pass;
     * they moved here so that the registry, rather than a file naming convention,
     * decides which get run.
     */
    private static final Map<String, List<?>> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("boolean", List.of(Boolean.FALSE, Boolean.TRUE));
        SAMPLES.put("counting", List.of(
                BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(7)));
        SAMPLES.put("tropical", List.of(Double.POSITIVE_INFINITY, 0.0d, 2.0d, 5.0d));
        SAMPLES.put("security", List.of(PUBLIC, CONFIDENTIAL, SECRET, TOP_SECRET));
        SAMPLES.put("lineage", polynomials());
        SAMPLES.put("cheapest-route", pathCosts());
        // Discovered, not built in — which is the point: a semiring installed through the
        // provider seam is held to the same laws as one that ships. The values are dyadic
        // rationals so that sum-product over double is exact and the laws hold literally.
        SAMPLES.put("kinship", List.of(0.0d, 1.0d, 0.5d, 0.25d));
    }

    private static List<Polynomial> polynomials() {
        PolynomialSemiring s = PolynomialSemiring.INSTANCE;
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        return List.of(
                s.zero(), s.one(), x, y,
                s.plus(x, y),    // x + y
                s.times(x, y),   // x·y
                s.plus(x, x));   // 2·x
    }

    private static List<PathCost> pathCosts() {
        PathCostSemiring s = PathCostSemiring.INSTANCE;
        PathCost a = PathCost.of(1.0, Route.of("a"));
        PathCost b = PathCost.of(2.0, Route.of("b"));
        PathCost c = PathCost.of(1.0, Route.of("c"));   // a's cost, another route
        return List.of(s.zero(), s.one(), a, b, c,
                s.times(a, b),   // (3.0, {a·b})
                s.plus(a, c));   // (1.0, {a, c}) — a tie union
    }

    static List<String> registeredNames() {
        return Semirings.names();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registeredNames")
    @DisplayName("satisfies the commutative-semiring laws over its sample")
    void satisfiesTheLaws(String name) {
        Semiring<?> semiring = Semirings.byName(name).orElseThrow(
                () -> new AssertionError("Semirings.names() lists '" + name
                        + "', which byName does not resolve"));
        List<?> sample = SAMPLES.get(name);
        assertThat(sample).as("sample for '%s' — see everyRegisteredSemiringHasASample", name)
                .isNotNull();

        assertLaws(semiring, sample);
    }

    /**
     * The carrier type is erased at the registry, so the sample is matched to the
     * semiring by name rather than by the compiler. A mismatch is not silent: the
     * semiring's own operations throw {@code ClassCastException} on the first call.
     */
    @SuppressWarnings("unchecked")
    private static <K> void assertLaws(Semiring<?> semiring, List<?> sample) {
        SemiringLaws.assertLaws((Semiring<K>) semiring, (List<K>) sample);
    }

    // ── The completeness guards ──────────────────────────────────────────────

    @Test
    @DisplayName("every name Semirings registers has a sample to test it with")
    void everyRegisteredSemiringHasASample() {
        Set<String> missing = new LinkedHashSet<>(Semirings.names());
        missing.removeAll(SAMPLES.keySet());
        assertThat(missing)
                .as("registered semirings with no sample here — add one covering zero, "
                        + "one and enough values to distinguish the operations, or the "
                        + "semiring ships with its algebra unchecked")
                .isEmpty();

        Set<String> stale = new LinkedHashSet<>(SAMPLES.keySet());
        Semirings.names().forEach(stale::remove);
        assertThat(stale)
                .as("samples naming a semiring the registry no longer lists")
                .isEmpty();
    }

    @Test
    @DisplayName("names() lists each built-in once — no built-in is left unnameable")
    void everyCanonicalNameIsADistinctSemiring() {
        // names() is a hand-written list beside the map byName resolves against, so the
        // two can drift. A duplicate here means one built-in answers to two canonical
        // names, which — the list being the same length as the set of built-ins — means
        // another answers to none and is reachable only by an alias.
        Map<Semiring<?>, String> seen = new IdentityHashMap<>();
        List<String> duplicates = new java.util.ArrayList<>();
        for (String name : Semirings.names()) {
            Semiring<?> semiring = Semirings.byName(name).orElseThrow();
            String previous = seen.put(semiring, name);
            if (previous != null) {
                duplicates.add(previous + " and " + name + " are the same semiring");
            }
        }
        assertThat(duplicates)
                .as("canonical names resolving to the same semiring instance")
                .isEmpty();
    }
}
