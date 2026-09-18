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
package com.darkcollective.relix.provenance;

import java.math.BigDecimal;

/**
 * A semiring the engine has never heard of: the <strong>kinship coefficient</strong>.
 *
 * <p>Two people's coefficient of relationship is the sum, over every line of descent
 * connecting them, of {@code (1/2)ⁿ} for a line of {@code n} generations — so alternative
 * lines add and successive generations multiply. That is the sum-product semiring over the
 * reals, and threading it through a weighted closure over a parent→child edge relation
 * computes the coefficient directly, with pedigree collapse (an ancestor reached by two
 * distinct lines) falling out of {@code ⊕} rather than needing to be looked for.
 *
 * <p>It exists here to hold the provider seam to its claim. Nothing in the engine mentions
 * kinship, and nothing about this class is privileged: it declares four operations and how
 * a base tuple weighs, which is the whole of what a built-in semiring declares.
 */
public final class KinshipSemiring implements Semiring<Double> {

    /** The single instance. */
    public static final KinshipSemiring INSTANCE = new KinshipSemiring();

    /** One generation of descent, for an edge that does not carry its own weight. */
    private static final double PER_GENERATION = 0.5d;

    private KinshipSemiring() {
    }

    @Override
    public Double zero() {
        return 0.0d;
    }

    @Override
    public Double one() {
        return 1.0d;
    }

    @Override
    public Double plus(Double a, Double b) {
        return a + b;
    }

    @Override
    public Double times(Double a, Double b) {
        return a * b;
    }

    /**
     * {@inheritDoc}
     *
     * <p>An edge's weight is the fraction of ancestry it carries. An edge with none is one
     * generation of ordinary descent, {@code 1/2} — which is what makes an unweighted
     * parent→child relation compute the textbook coefficient with no weight column at all.
     */
    @Override
    public Double base(BaseTuple tuple) {
        return tuple.weight().map(BigDecimal::doubleValue).orElse(PER_GENERATION);
    }
}
