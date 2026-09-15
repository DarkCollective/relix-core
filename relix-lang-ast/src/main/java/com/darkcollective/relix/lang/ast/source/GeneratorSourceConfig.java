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
package com.darkcollective.relix.lang.ast.source;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration for a <em>generator</em> source — a leaf relation whose rows are
 * produced by engine code rather than read from a file or database.
 *
 * <p>Introduced by a {@code source R from generator { name: "Range", … }}
 * declaration. The generator <em>owns its schema</em> (it is resolved from the
 * generator registry by {@code generatorName}, not declared in the script), so
 * unlike the other source kinds this config carries no column list — only the
 * generator name and its raw string arguments (e.g. {@code lo}/{@code hi}/
 * {@code step} for {@code Range}). Each generator interprets the args it understands.
 *
 * @param generatorName the registered generator name (e.g. {@code "Range"}); must not be blank
 * @param args          the raw, generator-specific arguments; must not be null
 */
public record GeneratorSourceConfig(
        String generatorName,
        Map<String, String> args
) implements SourceConfig {

    public GeneratorSourceConfig {
        Objects.requireNonNull(generatorName, "generatorName");
        if (generatorName.isBlank()) {
            throw new IllegalArgumentException("generator name must not be blank");
        }
        args = Map.copyOf(Objects.requireNonNull(args, "args"));
    }
}
