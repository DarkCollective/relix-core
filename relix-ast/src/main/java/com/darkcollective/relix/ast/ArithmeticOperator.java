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
package com.darkcollective.relix.ast;

/**
 * Binary arithmetic operators used in {@link BinaryArithmeticExpression}.
 *
 * <p>Precedence (higher value binds tighter): {@code MULTIPLY} and
 * {@code DIVIDE} (×2) bind more tightly than {@code PLUS} and {@code MINUS} (×1).
 */
public enum ArithmeticOperator {
    /** Addition ({@code +}). */
    PLUS,
    /** Subtraction ({@code −}). */
    MINUS,
    /** Multiplication ({@code *}). */
    MULTIPLY,
    /** Division ({@code /}). */
    DIVIDE
}
