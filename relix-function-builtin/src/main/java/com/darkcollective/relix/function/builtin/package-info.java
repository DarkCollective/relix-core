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
 * The default function library: one definition per built-in scalar function, carrying its
 * form and its processing together.
 *
 * <p>{@link com.darkcollective.relix.function.builtin.BuiltinFunctionLibrary} is the
 * discovered entry point; the rest of the package is the definitions, grouped by the
 * category they are documented under.
 *
 * <p>Each definition states in one place what used to be stated in three — the signature
 * the analyser reasons about, the body the executor runs, and the spelling a backend that
 * could evaluate the call itself would use. A function whose declaration and behaviour
 * disagreed was the failure mode that split arrangement produced, and a single definition
 * is what removes it.
 */
package com.darkcollective.relix.function.builtin;
