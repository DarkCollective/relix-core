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
package com.darkcollective.relix.processor.eval;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Dense linear algebra for {@code SOLVE}: Gaussian elimination with partial pivoting,
 * carried at {@link Dual#CONTEXT}'s precision.
 *
 * <p>A system is <em>singular</em> when a pivot is no larger than
 * {@link #SINGULAR} times the largest coefficient of the matrix — relative, so that
 * the answer does not depend on the units the data is in. A singular system has no
 * unique solution, which is a property of the question rather than a failure, so it is
 * reported as an empty result for the caller to act on.
 */
final class LinearSystems {

    /** The relative pivot size below which a matrix is treated as singular: 10⁻²⁰. */
    static final BigDecimal SINGULAR = new BigDecimal("1E-20");

    private LinearSystems() {
    }

    /**
     * Solves the square system {@code a · x = b}.
     *
     * @param a the coefficients, {@code n × n}; not modified
     * @param b the right-hand side, length {@code n}; not modified
     * @return {@code x}, or empty when the system is singular
     */
    static Optional<BigDecimal[]> solve(BigDecimal[][] a, BigDecimal[] b) {
        int n = b.length;
        BigDecimal[][] m = new BigDecimal[n][];
        BigDecimal scale = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            m[i] = new BigDecimal[n + 1];
            for (int j = 0; j < n; j++) {
                m[i][j] = a[i][j];
                scale = scale.max(a[i][j].abs());
            }
            m[i][n] = b[i];
        }
        BigDecimal threshold = scale.multiply(SINGULAR, Dual.CONTEXT);

        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (m[row][col].abs().compareTo(m[pivot][col].abs()) > 0) {
                    pivot = row;
                }
            }
            if (m[pivot][col].abs().compareTo(threshold) <= 0) {
                return Optional.empty();
            }
            BigDecimal[] swap = m[col];
            m[col] = m[pivot];
            m[pivot] = swap;
            for (int row = col + 1; row < n; row++) {
                BigDecimal factor = m[row][col].divide(m[col][col], Dual.CONTEXT);
                if (factor.signum() == 0) {
                    continue;
                }
                for (int k = col; k <= n; k++) {
                    m[row][k] = m[row][k].subtract(factor.multiply(m[col][k], Dual.CONTEXT),
                            Dual.CONTEXT);
                }
            }
        }

        BigDecimal[] x = new BigDecimal[n];
        for (int row = n - 1; row >= 0; row--) {
            BigDecimal sum = m[row][n];
            for (int k = row + 1; k < n; k++) {
                sum = sum.subtract(m[row][k].multiply(x[k], Dual.CONTEXT), Dual.CONTEXT);
            }
            x[row] = sum.divide(m[row][row], Dual.CONTEXT);
        }
        return Optional.of(x);
    }
}
