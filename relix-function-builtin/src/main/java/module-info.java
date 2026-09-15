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
/**
 * The default function library — the built-in scalar functions, supplied through the
 * same seam a third party would use.
 *
 * <p>The module requires the SPI and nothing else. That single edge is the claim the
 * design rests on: the shipped library reaches no engine internal, so a function it can
 * express is one anybody can express. It is asserted against the compiled descriptor,
 * not merely intended.
 *
 * <p>The library is declared as a provider twice over — {@code provides} here, and a
 * {@code META-INF/services} entry in the resources. A module path reads the first and a
 * class path reads the second, and declaring only one gives discovery that works in a
 * packaged image and fails in a plain unit test, or the reverse.
 */
module com.darkcollective.relix.function.builtin {
    requires com.darkcollective.relix.function;

    exports com.darkcollective.relix.function.builtin;

    provides com.darkcollective.relix.function.FunctionLibrary
            with com.darkcollective.relix.function.builtin.BuiltinFunctionLibrary;
}
