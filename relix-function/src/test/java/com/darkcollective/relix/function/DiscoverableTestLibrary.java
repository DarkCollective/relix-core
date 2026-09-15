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
package com.darkcollective.relix.function;

import java.util.List;

/**
 * A library registered in {@code src/test/resources/META-INF/services}, so
 * {@link FunctionCatalog#discover()} is exercised through the real
 * {@link java.util.ServiceLoader} path rather than a hand-built list.
 *
 * <p>Must be public with a public no-argument constructor — that is the contract every
 * provider is held to, and a fixture that cheated on it would not test the contract.
 */
public final class DiscoverableTestLibrary implements FunctionLibrary {

    /** Required by {@link java.util.ServiceLoader}. */
    public DiscoverableTestLibrary() {}

    @Override
    public String name() {
        return "discoverable-test-library";
    }

    @Override
    public List<ScalarFunction> scalarFunctions() {
        return List.of(new TestFunctions.Marker("DiscoveredFn", "discovered"));
    }
}
