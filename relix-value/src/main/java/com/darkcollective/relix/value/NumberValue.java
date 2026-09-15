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
package com.darkcollective.relix.value;

import com.darkcollective.relix.symbol.ScalarType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A runtime numeric value, stored as an arbitrary-precision {@link BigDecimal}.
 *
 * <p>Use {@link #of(String)} to parse a numeric literal string (as produced
 * by {@link com.darkcollective.relix.ast.NumberOperand#value()}).
 *
 * <p><b>Two numbers are the same value when they are numerically equal</b>, whatever
 * scale they were written or computed at: {@code 5} and {@code 5.0} are one value. That
 * is not what a record gives — {@link BigDecimal#equals} is scale-sensitive — and the
 * difference is the whole reason this is written out. Comparison has always been
 * numeric, so leaving identity to the record left the engine holding two incompatible
 * answers to <em>are these the same number</em>, and which one applied depended on the
 * operator: {@code σ x = 5} kept both rows while {@code δ} kept them apart, so DISTINCT
 * returned the same displayed number twice. Everything that hashes or sets a value —
 * {@code δ}, {@code γ} keys, the deduplicating set operations, join hash keys, a
 * closure's nodes — reads this, and every one of them now agrees with {@code =}.
 *
 * <p>The scale itself is kept. It is what the source supplied and it survives a round
 * trip; it simply does not decide identity, exactly as it does not decide order.
 *
 * @param value the numeric value; must not be {@code null}
 */
public record NumberValue(BigDecimal value) implements Value {

    public NumberValue {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Parses a numeric literal string into a {@code NumberValue}.
     *
     * @param text the numeric literal, e.g. {@code "42"} or {@code "3.14"}
     * @return a new {@code NumberValue}
     * @throws NumberFormatException if {@code text} is not a valid decimal
     */
    public static NumberValue of(String text) {
        return new NumberValue(new BigDecimal(Objects.requireNonNull(text, "text")));
    }

    /**
     * {@return whether {@code obj} is a numerically equal number} Scale is not part of
     * the answer, so {@code 5} equals {@code 5.0}.
     *
     * @param obj the value to compare against
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof NumberValue other && value.compareTo(other.value) == 0;
    }

    /** {@return a hash over the scale-normalised number, agreeing with {@link #equals}} */
    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public ScalarType type() {
        return ScalarType.NUMBER;
    }

    /**
     * Returns the number as a plain decimal string with no trailing zeros and
     * no scientific notation, e.g. {@code "42"}, {@code "3.14"}, {@code "0"}.
     *
     * @return plain decimal string
     */
    @Override
    public String asDisplayString() {
        BigDecimal stripped = value.stripTrailingZeros();
        // stripTrailingZeros on an integer produces negative scale (e.g. 1E+2),
        // so toPlainString ensures we never emit scientific notation.
        return stripped.toPlainString();
    }
}
