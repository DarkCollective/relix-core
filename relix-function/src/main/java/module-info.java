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
 * The function SPI: the description of a scalar function or aggregate that the engine
 * reasons about, and the seam through which an implementation is supplied.
 *
 * <p>A {@link com.darkcollective.relix.function.FunctionLibrary} is discovered with
 * {@link java.util.ServiceLoader} and offers
 * {@link com.darkcollective.relix.function.ScalarFunction}s and
 * {@link com.darkcollective.relix.function.AggregateFunction}s;
 * {@link com.darkcollective.relix.function.FunctionCatalog} indexes them by name. The
 * {@code uses} declaration lives here, with the discovery it belongs to, so a consumer
 * asks the catalogue rather than repeating the service declaration.
 *
 * <p>The module requires both halves of what a function definition is written in: the
 * type system a signature is stated in ({@code relix-symbol}) and the values an
 * implementation takes and returns ({@code relix-value}). That pair is why the SPI is a
 * module of its own — a value reports the scalar type it maps onto, so the value
 * hierarchy already depends on the type system, and hosting the SPI there would need
 * the edge back.
 */
module com.darkcollective.relix.function {
    requires transitive com.darkcollective.relix.symbol;  // ScalarType, ParameterDefinition
    requires transitive com.darkcollective.relix.value;   // Value

    exports com.darkcollective.relix.function;

    uses com.darkcollective.relix.function.FunctionLibrary;
}
