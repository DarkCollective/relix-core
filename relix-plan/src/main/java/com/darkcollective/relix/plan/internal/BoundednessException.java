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
package com.darkcollective.relix.plan.internal;

/**
 * Thrown at plan time when a query would materialise a provably-unbounded relation
 * — a blocking operator over an unbounded input, or a
 * hash join with no boundable build side.
 *
 * <p>This is a user-facing planning error, not a bug: the fix is to add a bound
 * (e.g. {@code λ n}, or a range/equality predicate a generator can answer) below the
 * offending operator. The CLI surfaces the message directly.
 */
public final class BoundednessException extends RuntimeException {

    /**
     * @param message the (already user-facing) diagnostic
     */
    public BoundednessException(String message) {
        super(message);
    }
}
