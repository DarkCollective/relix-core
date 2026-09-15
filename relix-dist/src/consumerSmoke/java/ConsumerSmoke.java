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
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.Tuple;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an embedder gets from one dependency, run against the <em>published</em>
 * artifact rather than the project that produced it.
 *
 * <p>It exists because a broken POM is invisible to every other test here. The build
 * resolves projects, not coordinates, so a dependency declared with the wrong scope —
 * a battery left `runtimeOnly` where a consumer needs it to compile, an `api` that
 * should have been `implementation` — compiles and passes everything and then fails for
 * the first person to depend on the release. That is the same class of failure
 * {@code distSmokeTest} and {@code jlinkSmokeTest} exist for, one layer further out.
 *
 * <p>So this is deliberately written the way a stranger would write it: it names
 * {@code relix-embed} and nothing else, and it uses the parts that only work if the
 * batteries came along — a built-in function, a provider inventory, an execution
 * terminal.
 */
public final class ConsumerSmoke {

    private ConsumerSmoke() {
    }

    public static void main(String[] args) {
        try (Relix relix = Relix.open()) {
            relix.table("Orders", List.of(
                    row("customer", "Ada", "amount", 100),
                    row("customer", "Grace", "amount", 250)));

            // Composing and rendering, which run nothing.
            Relation large = relix.relation("σ amount > 150 (Orders)");
            expect(large.render(), "σ amount > 150 (Orders)");

            // Executing, which needs the whole assembled stack.
            List<Tuple> rows = large.toList();
            expect(String.valueOf(rows.size()), "1");
            expect(rows.getFirst().string("customer"), "Grace");
            expect(String.valueOf(relix.relation("Orders").count()), "2");

            // A built-in function: present only if the default library came with the
            // facade rather than having to be named separately.
            expect(relix.relation("π UCase(customer) → c (Orders)").toList()
                    .getFirst().string("c"), "ADA");

            // And the inventory, which reports what actually got installed.
            List<String> kinds = relix.relation("π kind (relix.version)").toList().stream()
                    .map(r -> r.string("kind")).distinct().sorted().toList();
            for (String required : List.of("engine", "facade", "function-library", "solver")) {
                if (!kinds.contains(required)) {
                    throw new AssertionError(
                            "the published facade did not bring a " + required + ": " + kinds);
                }
            }
        }
        System.out.println("consumer smoke: ok");
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
