package com.hbm.entity.missile.autopilot;

/**
 * Control Gain Sets for Different Flight Phases.
 *
 * The SM-6 autopilot must adapt its control gains throughout the engagement
 * to maintain stability and performance across widely varying flight conditions:
 *
 * - Dynamic pressure: 1 kPa (high altitude) to 100 kPa (sea level, high speed)
 * - Mach number: 0.5 (boost initiation) to 3.5 (terminal dive)
 * - Mass: 1500 kg (launch) to 650 kg (fuel exhausted)
 * - Maneuver requirement: Gentle cruise to 30G terminal
 *
 * Gain Scheduling Strategy:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * BOOST PHASE (0-10s):
 *   Priorities: Stability, climb establishment
 *   Characteristics: Rapidly changing mass/thrust, high acceleration
 *   Gains: LOW (conservative, stability-focused)
 *
 * CRUISE PHASE (10-60s):
 *   Priorities: Efficiency, gentle trajectory corrections
 *   Characteristics: Moderate speed, sustainer burn
 *   Gains: MODERATE (balanced)
 *
 * COAST PHASE (60-80s):
 *   Priorities: Energy management, precision positioning
 *   Characteristics: No thrust, ballistic + aero control
 *   Gains: MODERATE-HIGH (responsive but efficient)
 *
 * TERMINAL PHASE (<5km range):
 *   Priorities: Maximum agility, target interception
 *   Characteristics: High speed, high dynamic pressure
 *   Gains: HIGH (aggressive, precision-focused)
 *
 * @author SM6 Flight Control System
 */
public class ControlGains {

    /** Outer loop: Proportional gain for alpha tracking */
    public final double K_alpha_p;

    /** Outer loop: Derivative gain for alpha damping */
    public final double K_alpha_d;

    /** Middle loop: Rate command gain */
    public final double K_rate_p;

    /** Inner loop: Fin deflection proportional gain */
    public final double K_fin_p;

    /** Inner loop: Fin deflection integral gain */
    public final double K_fin_i;

    /** Gain set name */
    public final String name;

    /**
     * Constructs control gain set.
     */
    public ControlGains(
        String name,
        double K_alpha_p,
        double K_alpha_d,
        double K_rate_p,
        double K_fin_p,
        double K_fin_i
    ) {
        this.name = name;
        this.K_alpha_p = K_alpha_p;
        this.K_alpha_d = K_alpha_d;
        this.K_rate_p = K_rate_p;
        this.K_fin_p = K_fin_p;
        this.K_fin_i = K_fin_i;
    }

    // ========== PREDEFINED GAIN SETS ==========

    /**
     * VLS (Vertical Launch System) phase gains - Ultra-conservative for low-speed vertical launch.
     *
     * Used during LIFT phase (first 0-3 seconds) where dynamic pressure is extremely low (10-50 Pa).
     * Control effectiveness is severely limited, so gains must be reduced to ~50% of BOOST values
     * to prevent actuator saturation and maintain stability.
     *
     * Time constant target: 0.5-1.0s (vs normal 0.2s)
     *
     * References: Cronvich PDF Section 3.4 (VLS control challenges at low q)
     */
    public static final ControlGains VLS = new ControlGains(
        "VLS",
        1.25, // K_alpha_p: Ultra-low proportional (50% of BOOST, weak control authority)
        0.25, // K_alpha_d: Light damping (50% of BOOST)
        0.75, // K_rate_p: Very conservative rate tracking (50% of BOOST)
        1.0,  // K_fin_p: Low fin authority (50% of BOOST, prevent saturation)
        0.4   // K_fin_i: Minimal integral (50% of BOOST, prevent windup at low q)
    );

    /**
     * BOOST phase gains - Conservative, stability-focused.
     */
    public static final ControlGains BOOST = new ControlGains(
        "BOOST",
        2.5,  // K_alpha_p: Low proportional (gentle response)
        0.5,  // K_alpha_d: Moderate damping
        1.5,  // K_rate_p: Conservative rate tracking
        2.0,  // K_fin_p: Moderate fin authority
        0.8   // K_fin_i: Low integral (prevent windup during boost)
    );

    /**
     * CRUISE phase gains - Balanced performance and efficiency.
     */
    public static final ControlGains CRUISE = new ControlGains(
        "CRUISE",
        4.0,  // K_alpha_p: Moderate proportional
        0.8,  // K_alpha_d: Good damping
        2.0,  // K_rate_p: Balanced rate tracking
        3.0,  // K_fin_p: Good responsiveness
        1.5   // K_fin_i: Moderate integral for steady-state accuracy
    );

    /**
     * COAST phase gains - Energy-efficient maneuvering.
     */
    public static final ControlGains COAST = new ControlGains(
        "COAST",
        5.0,  // K_alpha_p: Higher proportional (no thrust compensation)
        1.0,  // K_alpha_d: Strong damping
        2.5,  // K_rate_p: Responsive rate tracking
        3.5,  // K_fin_p: High fin authority
        2.0   // K_fin_i: Strong integral (precision positioning)
    );

    /**
     * TERMINAL phase gains - Maximum agility and precision.
     */
    public static final ControlGains TERMINAL = new ControlGains(
        "TERMINAL",
        6.0,  // K_alpha_p: High proportional (aggressive tracking)
        1.2,  // K_alpha_d: Strong damping (prevent oscillation)
        3.0,  // K_rate_p: Fast rate response
        4.5,  // K_fin_p: Maximum fin authority
        2.5   // K_fin_i: Strong integral (precision intercept)
    );

    /**
     * EMERGENCY gains - Ultra-aggressive for last-ditch maneuvers.
     */
    public static final ControlGains EMERGENCY = new ControlGains(
        "EMERGENCY",
        8.0,  // K_alpha_p: Maximum proportional
        1.5,  // K_alpha_d: Maximum damping
        4.0,  // K_rate_p: Maximum rate response
        6.0,  // K_fin_p: Maximum fin authority
        3.0   // K_fin_i: Maximum integral
    );

    @Override
    public String toString() {
        return String.format(
            "ControlGains[%s: Kp_α=%.1f, Kd_α=%.1f, Kp_q=%.1f, Kp_δ=%.1f, Ki_δ=%.1f]",
            name, K_alpha_p, K_alpha_d, K_rate_p, K_fin_p, K_fin_i
        );
    }

    /**
     * Interpolate between two gain sets.
     *
     * Useful for smooth transitions between flight phases.
     *
     * @param gains1 first gain set
     * @param gains2 second gain set
     * @param t interpolation parameter [0, 1]
     * @return interpolated gains
     */
    public static ControlGains interpolate(ControlGains gains1, ControlGains gains2, double t) {
        t = Math.max(0.0, Math.min(1.0, t)); // Clamp to [0, 1]

        return new ControlGains(
            String.format("%s→%s(%.2f)", gains1.name, gains2.name, t),
            gains1.K_alpha_p + t * (gains2.K_alpha_p - gains1.K_alpha_p),
            gains1.K_alpha_d + t * (gains2.K_alpha_d - gains1.K_alpha_d),
            gains1.K_rate_p + t * (gains2.K_rate_p - gains1.K_rate_p),
            gains1.K_fin_p + t * (gains2.K_fin_p - gains1.K_fin_p),
            gains1.K_fin_i + t * (gains2.K_fin_i - gains1.K_fin_i)
        );
    }
}
