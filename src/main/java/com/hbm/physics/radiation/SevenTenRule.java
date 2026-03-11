package com.hbm.physics.radiation;

/**
 * Implements the 7:10 Rule for estimating nuclear fallout dose rate decay.
 *
 * Source: The Effects of Nuclear Weapons, 3rd edition (1977)
 *         Samuel Glasstone & Philip J. Dolan
 *         Section 9.15
 *
 * Rule statement (§9.15):
 *   For every 7-fold increase in time after detonation,
 *   the dose rate decreases by a factor of 10.
 *
 *   R(7·t) = R(t) / 10
 *
 * General form derived from the rule:
 *
 *   R(t) = R(t₀) · (1/10)^[log₇(t/t₀)]
 *
 * Expanding the exponent:
 *
 *   (1/10)^[log₇(t/t₀)]
 *     = exp{ -ln(10) · ln(t/t₀) / ln(7) }
 *     = (t/t₀)^[ -ln(10)/ln(7) ]
 *     = (t/t₀)^( -log₇(10) )
 *     ≈ (t/t₀)^(-1.18329...)
 *
 * This effective exponent (-1.183) approximates the Way-Wigner exponent (-1.2),
 * making the 7:10 Rule a convenient practical shorthand.
 *
 * Accuracy (§9.15):
 *   Within ±25% for up to approximately 2 weeks (336 hours) after detonation.
 *   Beyond 2 weeks the actual decay accelerates and the rule overestimates.
 */
public final class SevenTenRule {

    private SevenTenRule() {}

    // -----------------------------------------------------------------------
    // Physical constants
    // -----------------------------------------------------------------------

    /**
     * Time multiplier in the 7:10 Rule.
     * Every TIME_MULTIPLIER-fold increase in time yields a DOSE_MULTIPLIER reduction.
     * Source: §9.15
     */
    public static final double TIME_MULTIPLIER = 7.0;

    /**
     * Dose rate multiplier per 7-fold time interval.
     * Source: §9.15
     */
    public static final double DOSE_MULTIPLIER = 0.1;

    /**
     * Effective power-law exponent of the 7:10 Rule.
     *
     * Derived as: -ln(10) / ln(7) ≈ -1.18329
     *
     * This is the exponent n such that R(t) ∝ t^n under the 7:10 approximation.
     * Compare with the Way-Wigner exponent of exactly -1.2.
     */
    public static final double EFFECTIVE_EXPONENT = -Math.log(10.0) / Math.log(7.0);

    /**
     * Maximum time for which the 7:10 Rule gives results within ±25% accuracy.
     * 14 days × 24 hours = 336 hours.
     * Source: §9.15
     */
    public static final double MAX_VALID_HOURS = 14.0 * 24.0;

    // -----------------------------------------------------------------------
    // Core physics methods
    // -----------------------------------------------------------------------

    /**
     * Estimates the dose rate at time t, given a known dose rate at reference time t₀.
     *
     * R(t) = R(t₀) · (1/10)^[log₇(t/t₀)]
     *       = R(t₀) · (t/t₀)^(-log₇(10))
     *       ≈ R(t₀) · (t/t₀)^(-1.183)
     *
     * @param r0      Known dose rate at reference time t₀ [rads/hr]; must be >= 0
     * @param t0Hours Reference time since detonation [hours]; must be > 0
     * @param tHours  Target time since detonation [hours]; must be > 0
     * @return Estimated dose rate at time t [rads/hr]
     * @throws IllegalArgumentException if either time is <= 0
     */
    public static double getDoseRate(double r0, double t0Hours, double tHours) {
        if (t0Hours <= 0.0 || tHours <= 0.0) {
            throw new IllegalArgumentException(
                "Times must be positive: t0=" + t0Hours + ", t=" + tHours);
        }
        if (r0 <= 0.0) {
            return 0.0;
        }
        double ratio = tHours / t0Hours;
        // n = log₇(ratio) = ln(ratio) / ln(7)
        double intervals = Math.log(ratio) / Math.log(TIME_MULTIPLIER);
        return r0 * Math.pow(DOSE_MULTIPLIER, intervals);
    }

    /**
     * Returns the decay factor for a given time ratio t/t₀.
     *
     * factor = (1/10)^[log₇(ratio)]
     *        = ratio^(-log₇(10))
     *        ≈ ratio^(-1.183)
     *
     * Example: ratio = 7 → factor = 0.1 (one 7-fold interval, dose drops by 10×)
     * Example: ratio = 49 → factor = 0.01 (two 7-fold intervals, dose drops by 100×)
     *
     * @param timeRatio Ratio of target time to reference time (t/t₀); must be > 0
     * @return Dimensionless decay factor
     * @throws IllegalArgumentException if timeRatio <= 0
     */
    public static double getDecayFactor(double timeRatio) {
        if (timeRatio <= 0.0) {
            throw new IllegalArgumentException("Time ratio must be positive, got: " + timeRatio);
        }
        double intervals = Math.log(timeRatio) / Math.log(TIME_MULTIPLIER);
        return Math.pow(DOSE_MULTIPLIER, intervals);
    }

    /**
     * Returns the number of 7-fold time intervals elapsed between t₀ and t.
     *
     * n = log₇(t/t₀) = ln(t/t₀) / ln(7)
     *
     * Interpretation:
     *   n = 0: no change (t = t₀)
     *   n = 1: one 7-fold increase (t = 7·t₀), dose rate × 0.1
     *   n = 2: two 7-fold increases (t = 49·t₀), dose rate × 0.01
     *   n = -1: inverse (t = t₀/7), dose rate × 10
     *
     * @param t0Hours Reference time [hours]; must be > 0
     * @param tHours  Target time [hours]; must be > 0
     * @return Number of 7-fold intervals (may be fractional or negative)
     * @throws IllegalArgumentException if either time is <= 0
     */
    public static double getIntervalCount(double t0Hours, double tHours) {
        if (t0Hours <= 0.0 || tHours <= 0.0) {
            throw new IllegalArgumentException(
                "Times must be positive: t0=" + t0Hours + ", t=" + tHours);
        }
        return Math.log(tHours / t0Hours) / Math.log(TIME_MULTIPLIER);
    }

    // -----------------------------------------------------------------------
    // Validity check
    // -----------------------------------------------------------------------

    /**
     * Checks whether the given time falls within the valid range of the 7:10 Rule.
     *
     * Valid range: 0 to 336 hours (14 days), ±25% accuracy (§9.15).
     * Beyond 2 weeks, actual decay is faster than the rule predicts.
     *
     * @param tHours Time since detonation [hours]
     * @return true if within the valid range (0 < t <= 336 h)
     */
    public static boolean isInValidRange(double tHours) {
        return tHours > 0.0 && tHours <= MAX_VALID_HOURS;
    }
}
