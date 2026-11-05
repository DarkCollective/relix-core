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

import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The step the constructive COVER takes when a greedy choice has no valid completion. */
@DisplayName("COVER's stall step")
final class CoverStallStepTest {

    private static Set<List<Value>> demanded(List<Value>... tuples) {
        return new LinkedHashSet<>(List.of(tuples));
    }

    @Test
    @DisplayName("drops one tuple from the first non-empty demanded set, so the universe shrinks")
    void dropsOneFromTheFirstNonEmptySet() {
        List<Value> first = List.of(NullValue.INSTANCE);
        List<Value> second = List.of(NullValue.INSTANCE, NullValue.INSTANCE);
        List<Set<List<Value>>> universe = new ArrayList<>();
        universe.add(demanded());
        universe.add(demanded(first, second));
        universe.add(demanded(first));

        CoverExecutor.removeFirstUniverseTuple(universe);

        assertThat(universe.get(0)).isEmpty();
        assertThat(universe.get(1)).containsExactly(second);
        assertThat(universe.get(2)).containsExactly(first);
    }

    @Test
    @DisplayName("an empty universe is left as it is")
    void emptyUniverse() {
        List<Set<List<Value>>> universe = new ArrayList<>(List.of(demanded(), demanded()));
        CoverExecutor.removeFirstUniverseTuple(universe);
        assertThat(universe).allSatisfy(set -> assertThat(set).isEmpty());
    }
}
