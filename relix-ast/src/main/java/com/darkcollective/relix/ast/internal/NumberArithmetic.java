package com.darkcollective.relix.ast.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one definition of NUMBER division, shared by the executor and the optimizer's
 * constant folding.
 *
 * <p>The two used to carry a rule each, so {@code π 1 / 3} answered
 * {@code 0.3333333333333333} once the optimizer had folded it and {@code 0.3333333333}
 * when it ran as written. A rewrite must preserve the answer, so there is one routine
 * and both call it.
 *
 * <p>The rule is the documented one: ten decimal places, half-up, and the caller strips
 * trailing zeros. A zero divisor is the caller's to refuse, since the two callers
 * refuse it differently (the executor raises, the optimizer leaves the expression to
 * raise at run time).
 */
public final class NumberArithmetic {

    /** The number of decimal places a quotient keeps. */
    public static final int DIVISION_SCALE = 10;

    private NumberArithmetic() {
    }

    /**
     * Divides {@code dividend} by a non-zero {@code divisor}, keeping
     * {@value #DIVISION_SCALE} places, rounding half up, trailing zeros stripped.
     *
     * @param dividend the numerator
     * @param divisor  the denominator, not zero
     * @return the quotient
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        return dividend.divide(divisor, DIVISION_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
