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

/**
 * How many arguments a function accepts — an inclusive range, with
 * {@link #UNBOUNDED} standing for "no upper limit".
 *
 * <p>Arity is declared once, on the signature, and checked once, by the engine,
 * before a function is invoked. An implementation therefore never has to count its
 * own arguments: by the time it runs, the list it is handed is a length the
 * declaration allows.
 *
 * <p>A range is what lets one definition serve what would otherwise be several.
 * {@code Round(x)} and {@code Round(x, places)} are one function of arity
 * {@code between(1, 2)}, not two functions that differ only in how many arguments
 * they take, and {@code Coalesce} is {@code atLeast(1)} rather than a fixed pair.
 *
 * @param min the smallest number of arguments accepted; zero or more
 * @param max the largest number accepted, or {@link #UNBOUNDED} for no limit;
 *            when bounded, never smaller than {@code min}
 */
public record Arity(int min, int max) {

    /** The value {@link #max()} takes when a function accepts any number of arguments. */
    public static final int UNBOUNDED = -1;

    /**
     * @throws IllegalArgumentException if {@code min} is negative, or {@code max} is
     *                                  neither {@link #UNBOUNDED} nor at least {@code min}
     */
    public Arity {
        if (min < 0) {
            throw new IllegalArgumentException("Minimum arity must not be negative: " + min);
        }
        if (max != UNBOUNDED && max < min) {
            throw new IllegalArgumentException(
                    "Maximum arity " + max + " is below the minimum " + min);
        }
    }

    /**
     * Exactly {@code count} arguments — the common case.
     *
     * @param count the required number of arguments
     * @return the fixed arity
     */
    public static Arity exactly(int count) {
        return new Arity(count, count);
    }

    /**
     * Between {@code min} and {@code max} arguments, inclusive.
     *
     * @param min the smallest number accepted
     * @param max the largest number accepted
     * @return the bounded range
     */
    public static Arity between(int min, int max) {
        return new Arity(min, max);
    }

    /**
     * At least {@code min} arguments, with no upper limit.
     *
     * @param min the smallest number accepted
     * @return the open-ended range
     */
    public static Arity atLeast(int min) {
        return new Arity(min, UNBOUNDED);
    }

    /**
     * @return {@code true} when there is no upper limit on the argument count
     */
    public boolean isUnbounded() {
        return max == UNBOUNDED;
    }

    /**
     * @param count a candidate number of arguments
     * @return {@code true} when a call with {@code count} arguments is within range
     */
    public boolean accepts(int count) {
        return count >= min && (isUnbounded() || count <= max);
    }

    /**
     * Renders the range the way an error message wants it — {@code "2"},
     * {@code "1 to 3"}, {@code "at least 1"}.
     *
     * @return a human-readable description of the accepted argument count
     */
    public String describe() {
        if (isUnbounded()) {
            return "at least " + min;
        }
        return min == max ? String.valueOf(min) : min + " to " + max;
    }
}
