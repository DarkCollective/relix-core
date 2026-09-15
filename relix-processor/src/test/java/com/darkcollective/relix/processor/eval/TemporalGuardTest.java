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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.ast.TemporalLiterals;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #721 — the shape gate in front of the temporal parsers is a cost optimisation,
 * and this holds it to the thing it optimises.
 *
 * <p>{@code ValueComparator} canonicalises a string once per <em>comparison</em>, so the
 * four parse probes behind {@code coerceToAnyTemporal} run on the hot path of every sort
 * and merge join. Each probe that fails does so by throwing, which is why a gate exists:
 * sorting 100k ordinary strings was 300× slower without one.
 *
 * <p>The gate is a hand-written shape test, so it can disagree with the parsers in two
 * directions, and they are not equally bad. Admitting a string no parser accepts costs
 * the exceptions the gate was meant to save — slower, still correct. <strong>Rejecting a
 * string a parser would accept changes what that value means</strong>: the text stops
 * denoting a timestamp and becomes ordinary text, silently, in the one class that decides
 * when two values are equal.
 *
 * <p>So the claim is an equivalence, checked against {@link TemporalLiterals} itself
 * rather than against the gate's own reasoning: a string is canonicalised exactly when one
 * of those parsers accepts it. Nothing here names the gate — it is deliberately a test of
 * the observable behaviour, so the gate can be retuned freely and only a change in meaning
 * fails.
 */
@DisplayName("#721 — the temporal shape gate agrees with the parsers")
final class TemporalGuardTest {

    /** Whether any of the four parsers reads {@code s} as a temporal value. */
    private static boolean anyParserAccepts(String s) {
        for (Parse p : List.<Parse>of(TemporalLiterals::parseTimestamp, TemporalLiterals::parseDate,
                TemporalLiterals::parseTime, TemporalLiterals::parseDuration)) {
            try {
                p.apply(s);
                return true;
            } catch (RuntimeException ignored) {
                // not this one
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface Parse {
        Object apply(String s);
    }

    /** Whether the engine reads {@code s} as a temporal value rather than as text. */
    private static boolean canonicalisesToTemporal(String s) {
        Value coerced = TemporalValueArithmetic.coerceToAnyTemporal(new StringValue(s));
        return !(coerced instanceof StringValue);
    }

    /**
     * Shapes chosen to sit on both sides of the gate's conditions — valid forms of all four
     * types, the near-misses the positional test exists to reject (an order number, a
     * postcode, a phone number), expanded years with and without the sign ISO-8601 requires
     * for them, and the degenerate strings.
     */
    private static List<String> corpus() {
        List<String> all = new ArrayList<>(List.of(
                "2024-01-15", "2024-12-31", "0001-01-01", "9999-12-31",
                "+12024-01-15", "-2024-01-15", "+2024-01-15",
                "10:30", "10:30:00", "10:30:00.500", "00:00", "23:59:59",
                "2024-01-15T10:00:00", "2024-01-15T10:00:00Z",
                "2024-01-15T11:00:00+01:00", "2024-01-15T10:00:00.500Z",
                "PT5M", "P1D", "PT1H30M", "-PT5M", "+PT5M", "P1DT2H",
                "pt5m", "p1d",
                "12345-ACME", "555-1234", "12345-6789", "1-2", "123-45-6789",
                "2024", "12345", "5", "5.0", "0",
                "pending", "north", "alice@example.com", "N/A", "",
                " ", "-", "+", "P", "PT", "T5", "1:30", "24:00", "99:99",
                "2024-13-45", "2024-01-15T99:99:99", "2024-1-5", "20240115"));
        Random r = new Random(721);
        String alphabet = "0123456789-:+TPZ.abz ";
        for (int i = 0; i < 4000; i++) {
            StringBuilder b = new StringBuilder();
            for (int j = 0, n = r.nextInt(24); j < n; j++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
            all.add(b.toString());
        }
        return all;
    }

    @Test
    @DisplayName("a string is read as a temporal exactly when a parser accepts it")
    void gateAgreesWithTheParsers() {
        List<String> wronglyRejected = new ArrayList<>();
        List<String> wronglyAccepted = new ArrayList<>();
        for (String s : corpus()) {
            boolean parsers = anyParserAccepts(s);
            boolean engine = canonicalisesToTemporal(s);
            if (parsers && !engine) wronglyRejected.add("\"" + s + "\"");
            if (!parsers && engine) wronglyAccepted.add("\"" + s + "\"");
        }

        assertThat(wronglyRejected)
                .as("""
                        strings a parser accepts but the engine reads as plain text. This is \
                        the direction that matters: the gate has narrowed what a value means, \
                        so text that denoted a temporal now denotes only itself — and the \
                        comparator decides equality on that. Widen mayBeTemporal""")
                .isEmpty();
        assertThat(wronglyAccepted)
                .as("strings no parser accepts but the engine read as a temporal")
                .isEmpty();
    }

    /**
     * The gate must not become so tight that it stops admitting anything — a version that
     * rejected everything would satisfy neither list above only because the parsers would
     * then be asked nothing. This states the corpus is doing work.
     */
    @Test
    @DisplayName("the corpus exercises both answers")
    void corpusCoversBothSides() {
        long temporal = corpus().stream().filter(TemporalGuardTest::canonicalisesToTemporal).count();
        assertThat(temporal).as("strings read as temporals").isGreaterThan(20);
        assertThat(corpus().size() - temporal).as("strings read as text").isGreaterThan(20);
    }
}
