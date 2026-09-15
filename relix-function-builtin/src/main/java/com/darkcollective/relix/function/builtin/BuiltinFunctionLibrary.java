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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.ScalarFunction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The functions relix ships with: the scalars — string, numeric, temporal, conditional,
 * type-check, conversion and nested-data — and the eight aggregates.
 *
 * <p>It is an ordinary {@link FunctionLibrary}, discovered the same way any other is and
 * built out of the same published seam. Nothing about it is privileged: a library that
 * declares a higher {@linkplain FunctionLibrary#priority() priority} replaces any
 * function here by name, and one that declares a lower priority fills gaps around it.
 *
 * <p>This class exists to be found by {@link java.util.ServiceLoader}, so it has a public
 * no-argument constructor and is declared as a provider in both of the two places
 * discovery looks.
 */
public final class BuiltinFunctionLibrary implements FunctionLibrary {

    /** The name this library reports, including in a message about a name clash. */
    public static final String NAME = "relix-builtin";

    private static final List<ScalarFunction> SCALARS = scalars();

    private static final List<AggregateFunction> AGGREGATES = AggregateFunctions.all();

    /**
     * The documentation keys this library will answer for: exactly the ones its own
     * signatures declare.  Serving only these is what keeps
     * {@link #documentation(String)} from turning into a general reader of whatever sits
     * under {@code /docs/reference/} — including anything a caller composes a key out of.
     */
    private static final Set<String> DOC_KEYS = declaredDocKeys();

    /** Creates the library. Discovery calls this; there is nothing to configure. */
    public BuiltinFunctionLibrary() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ScalarFunction> scalarFunctions() {
        return SCALARS;
    }

    @Override
    public List<AggregateFunction> aggregateFunctions() {
        return AGGREGATES;
    }

    /**
     * The reference page for one of this library's functions, read from the library's own
     * resources.
     *
     * <p>The pages are the repository's {@code docs/reference/functions} tree, copied in
     * at build time rather than moved: they are still read from the source tree by the
     * PDF build and the training-corpus extractor, and a page has one home.
     */
    @Override
    public Optional<String> documentation(String docKey) {
        if (docKey == null || !DOC_KEYS.contains(docKey)) {
            return Optional.empty();
        }
        try (InputStream page = BuiltinFunctionLibrary.class
                .getResourceAsStream("/docs/reference/" + docKey)) {
            return page == null ? Optional.empty()
                    : Optional.of(new String(page.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static Set<String> declaredDocKeys() {
        Set<String> keys = new HashSet<>();
        SCALARS.forEach(fn -> fn.signature().docKey().ifPresent(keys::add));
        AGGREGATES.forEach(fn -> fn.signature().docKey().ifPresent(keys::add));
        return Set.copyOf(keys);
    }

    private static List<ScalarFunction> scalars() {
        List<ScalarFunction> all = new ArrayList<>();
        all.addAll(StringFunctions.all());
        all.addAll(MathFunctions.all());
        all.addAll(DateTimeFunctions.all());
        all.addAll(ConditionalFunctions.all());
        all.addAll(TypeCheckFunctions.all());
        all.addAll(ConversionFunctions.all());
        all.addAll(NestedFunctions.all());
        return List.copyOf(all);
    }
}
