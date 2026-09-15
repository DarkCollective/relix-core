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
 * A library that cannot be instantiated, standing in for a third-party library compiled
 * against a dependency the host does not have.
 *
 * <p>The failure is modelled where a real one occurs. {@code ServiceLoader} loads a
 * provider class with initialisation deferred, so a class that merely <em>mentions</em> a
 * missing type survives discovery and fails later; what makes a provider fail during
 * discovery is a static field of the absent library's type, which forces initialisation
 * when the provider is constructed. This raises the same error from the same place.
 */
public final class UnloadableTestLibrary implements FunctionLibrary {

    static {
        if (Boolean.parseBoolean("true")) {
            throw new NoClassDefFoundError("org/example/AbsentLibraryType");
        }
    }

    /** Required by {@link java.util.ServiceLoader}. */
    public UnloadableTestLibrary() {}

    @Override
    public String name() {
        return "unloadable-test-library";
    }

    @Override
    public List<ScalarFunction> scalarFunctions() {
        return List.of();
    }
}
