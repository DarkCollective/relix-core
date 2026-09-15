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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Allen's thirteen relations, against a fixed left interval, at every boundary.
 *
 * <p>What separates one relation from another is almost entirely <b>strictness</b>:
 * {@code DURING} is {@code r.start < ℓ.start} where {@code STARTS} is {@code =}, and
 * {@code OVERLAPS} stops exactly where {@code MEETS} begins. Mutation testing found all
 * thirteen boundaries unasserted — every {@code <} could have been {@code <=} and the
 * suite would still have passed — which is what happens when the cases are chosen to
 * illustrate a relation rather than to sit on its edge.
 *
 * <p>So the cases are the edges. Every right interval here shares an endpoint with the
 * left one, or misses it by exactly one, and each is asked of <em>all thirteen</em>
 * relations rather than the one it was written for: a relation that wrongly accepts is
 * as much a defect as one that wrongly rejects, and only the full row shows it.
 */
@DisplayName("Allen relations — the truth table at the boundaries")
final class AllenRelationTruthTableTest {

    /** The left interval every case is measured against: {@code [10, 20)}. */
    private static final Value LS = num(10);
    private static final Value LE = num(20);

    private static NumberValue num(long n) {
        return NumberValue.of(Long.toString(n));
    }

    /**
     * One right interval and the complete set of relations that hold between the fixed
     * left interval and it. Anything not named must be false.
     */
    private record Case(String name, long rs, long re, Set<AllenRelation> holds) {}

    private static Case at(String name, long rs, long re, AllenRelation... holds) {
        return new Case(name, rs, re, holds.length == 0
                ? EnumSet.noneOf(AllenRelation.class)
                : EnumSet.copyOf(Arrays.asList(holds)));
    }

    /**
     * The right intervals, chosen so that each pair of neighbouring rows differs by one
     * endpoint moving across a boundary.
     */
    private static List<Case> cases() {
        return List.of(
                at("[0, 5) — ends before ℓ starts", 0, 5,
                        AllenRelation.PRECEDED_BY),
                at("[0, 10) — ends exactly where ℓ starts", 0, 10,
                        AllenRelation.MET_BY),
                at("[0, 11) — reaches one past ℓ's start", 0, 11,
                        AllenRelation.INTERSECTS, AllenRelation.OVERLAPPED_BY),
                at("[0, 20) — ends exactly where ℓ ends", 0, 20,
                        AllenRelation.INTERSECTS, AllenRelation.FINISHES),
                at("[0, 21) — outlives ℓ at both ends", 0, 21,
                        AllenRelation.INTERSECTS, AllenRelation.DURING),
                at("[10, 15) — shares ℓ's start, ends inside", 10, 15,
                        AllenRelation.INTERSECTS, AllenRelation.STARTED_BY),
                at("[10, 20) — the same interval", 10, 20,
                        AllenRelation.INTERSECTS, AllenRelation.EQUALS),
                at("[10, 21) — shares ℓ's start, outlives it", 10, 21,
                        AllenRelation.INTERSECTS, AllenRelation.STARTS),
                at("[11, 19) — strictly inside ℓ", 11, 19,
                        AllenRelation.INTERSECTS, AllenRelation.CONTAINS),
                at("[11, 20) — inside ℓ, sharing its end", 11, 20,
                        AllenRelation.INTERSECTS, AllenRelation.FINISHED_BY),
                at("[11, 21) — starts inside ℓ, outlives it", 11, 21,
                        AllenRelation.INTERSECTS, AllenRelation.OVERLAPS),
                at("[19, 21) — one unit of overlap at ℓ's end", 19, 21,
                        AllenRelation.INTERSECTS, AllenRelation.OVERLAPS),
                at("[20, 25) — starts exactly where ℓ ends", 20, 25,
                        AllenRelation.MEETS),
                at("[21, 25) — starts after ℓ ends", 21, 25,
                        AllenRelation.PRECEDES));
    }

    @TestFactory
    @DisplayName("every right interval against every relation")
    Stream<DynamicTest> truthTable() {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            Map<AllenRelation, Boolean> actual = new LinkedHashMap<>();
            for (AllenRelation rel : AllenRelation.values()) {
                actual.put(rel, JoinExecutor.allenTest(rel, LS, LE, num(c.rs()), num(c.re())));
            }
            List<AllenRelation> holding = new ArrayList<>();
            actual.forEach((rel, held) -> {
                if (held) {
                    holding.add(rel);
                }
            });
            assertThat(holding)
                    .as("ℓ = [10, 20), r = %s", c.name())
                    .containsExactlyInAnyOrderElementsOf(c.holds());
        }));
    }

    @Test
    @DisplayName("every relation is exercised by at least one case, in both directions")
    void theTableCoversEveryRelation() {
        // A truth table proves nothing about a relation no row makes true. Each must
        // hold somewhere and fail somewhere, or its boundary is still unasserted.
        for (AllenRelation rel : AllenRelation.values()) {
            assertThat(cases()).as("%s holds in no case", rel)
                    .anySatisfy(c -> assertThat(c.holds()).contains(rel));
            assertThat(cases()).as("%s holds in every case", rel)
                    .anySatisfy(c -> assertThat(c.holds()).doesNotContain(rel));
        }
    }
}
