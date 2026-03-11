package com.hbm.entity.missile.autopilot;

import com.hbm.physics.Vector3D;
import com.hbm.physics.Quaternion;
import com.hbm.physics.CoordinateTransform;

/**
 * Three-Loop Autopilot for SM-6 Missile Flight Control.
 *
 * Implements hierarchical control architecture to convert guidance commands into
 * aerodynamic control surface deflections.
 *
 * Architecture (Hawley & Blauwkamp, Figure 8):
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * OUTER LOOP: Acceleration Command → Angle-of-Attack Command
 *   Input:  a_cmd (m/s²) from guidance law (inertial frame)
 *   Output: α_cmd (rad), β_cmd (rad) - desired aero angles
 *
 *   Transformation:
 *     1. Convert a_cmd from inertial to body frame
 *     2. Compute required lift: L = m × a_n
 *     3. Compute required C_L: C_L = L / (q × S)
 *     4. Compute required α: α = C_L / C_L_α
 *
 * MIDDLE LOOP: Angle-of-Attack → Angular Rate Command
 *   Input:  α_cmd (rad) - desired angle-of-attack
 *   Output: q_cmd (rad/s) - desired pitch rate
 *
 *   PD Controller:
 *     q_cmd = K_p × (α_cmd - α) + K_d × dα/dt
 *
 * INNER LOOP: Angular Rate → Control Surface Deflection
 *   Input:  q_cmd (rad/s) - desired pitch rate
 *   Output: δ (rad) - fin deflection angle
 *
 *   PI Controller with rate limiting:
 *     δ = K_q × (q_cmd - q) + K_i × ∫(q_cmd - q) dt
 *     Subject to: |dδ/dt| < δ_max_rate, |δ| < δ_max
 *
 * Gain Scheduling:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Control gains must adapt to flight conditions:
 *
 * Dynamic Pressure Scaling:
 *   As speed increases, aerodynamic forces increase → reduce gains
 *   K_effective = K_nominal / (1 + q/q_ref)
 *
 * Altitude Scaling:
 *   As altitude increases, air density decreases → increase gains
 *   K_effective = K_nominal × (ρ_0 / ρ)^0.5
 *
 * Phase-Based Gains:
 *   BOOST:    Low gains (vehicle accelerating, stability priority)
 *   CRUISE:   Moderate gains (efficient tracking)
 *   TERMINAL: High gains (aggressive target pursuit)
 *
 * Stability Margins:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * The autopilot must maintain stability across wide operating envelope:
 *
 * Gain Margin: >6 dB (factor of 2×)
 * Phase Margin: >45° (ensures damped response)
 * Bandwidth: 2-10 rad/s (depending on phase)
 *
 * Anti-Windup Protection:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Integral terms can wind up during saturation, causing overshoot.
 *
 * Back-Calculation Method:
 *   When δ saturates:
 *     1. Compute excess: δ_excess = δ_desired - δ_saturated
 *     2. Feed back to integrator: dI/dt = -K_b × δ_excess
 *     3. Prevents integrator from growing during saturation
 *
 * Performance Characteristics:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Acceleration Command Response:
 *   - Rise time: <0.2s (90% of commanded acceleration)
 *   - Overshoot: <10%
 *   - Settling time: <0.5s (within 2% of steady-state)
 *
 * Maximum Acceleration:
 *   - Boost phase: 15G (limited by thrust vectoring)
 *   - Cruise phase: 25G (aerodynamic limit)
 *   - Terminal phase: 30G (max maneuverability)
 *
 * @author SM6 Flight Control System
 */
public class ThreeLoopAutopilot {

    // ========== PHYSICAL PARAMETERS ==========

    /** Reference area for aerodynamic forces (m²) */
    private final double referenceArea;

    /** Missile mass (kg) - may vary with fuel consumption */
    private double mass;

    /** Lift coefficient slope (per radian) */
    private static final double CL_ALPHA = 3.5;

    /** Maximum angle of attack (rad) - stall limit */
    private static final double MAX_ALPHA = Math.toRadians(20.0);

    /** Maximum fin deflection (rad) */
    private static final double MAX_FIN_DEFLECTION = Math.toRadians(25.0);

    /** Maximum fin deflection rate (rad/s) */
    private static final double MAX_FIN_RATE = Math.toRadians(200.0); // 200°/s

    // ========== CONTROL GAINS (BASELINE) ==========

    /** Outer loop: Proportional gain for alpha tracking */
    private double K_alpha_p;

    /** Outer loop: Derivative gain for alpha damping */
    private double K_alpha_d;

    /** Middle loop: Rate command gain */
    private double K_rate_p;

    /** Inner loop: Fin deflection proportional gain */
    private double K_fin_p;

    /** Inner loop: Fin deflection integral gain */
    private double K_fin_i;

    /** Anti-windup gain */
    private static final double K_BACK_CALC = 10.0;

    // ========== STATE VARIABLES ==========

    /** Previous angle of attack for derivative calculation */
    private double alpha_prev;

    /** Previous time for dt calculation */
    private double time_prev;

    /** Integral of rate error (for inner loop PI control) */
    private double rateErrorIntegral;

    /** Previous fin deflection (for rate limiting) */
    private double finDeflection_prev;

    /** Current fin deflection */
    private double currentFinDeflection;

    // ========== CONSTRUCTOR ==========

    /**
     * Constructs autopilot with specified parameters.
     *
     * @param referenceArea aerodynamic reference area (m²)
     * @param initialMass initial missile mass (kg)
     */
    public ThreeLoopAutopilot(double referenceArea, double initialMass) {
        this.referenceArea = referenceArea;
        this.mass = initialMass;

        // Initialize with moderate baseline gains (will be scheduled)
        this.K_alpha_p = 4.0;
        this.K_alpha_d = 0.8;
        this.K_rate_p = 2.0;
        this.K_fin_p = 3.0;
        this.K_fin_i = 1.5;

        // Initialize state
        this.alpha_prev = 0;
        this.time_prev = 0;
        this.rateErrorIntegral = 0;
        this.finDeflection_prev = 0;
        this.currentFinDeflection = 0;
    }

    // ========== MAIN AUTOPILOT UPDATE ==========

    /**
     * Compute autopilot commands for current state.
     *
     * @param a_cmd_inertial commanded acceleration (inertial frame, m/s²)
     * @param attitude current missile attitude quaternion
     * @param velocity_body current velocity in body frame (m/s)
     * @param angularVelocity_body current angular velocity (rad/s)
     * @param airDensity air density at current altitude (kg/m³)
     * @param currentTime current time (s)
     * @return autopilot command
     */
    public AutopilotCommand computeCommand(
        Vector3D a_cmd_inertial,
        Quaternion attitude,
        Vector3D velocity_body,
        Vector3D angularVelocity_body,
        double airDensity,
        double currentTime
    ) {
        double dt = currentTime - time_prev;
        if (dt < 0.001) dt = 0.001; // Prevent division by zero

        // ========== OUTER LOOP: ACCELERATION → ANGLE-OF-ATTACK ==========

        // 1. Transform commanded acceleration to body frame
        Vector3D a_cmd_body = CoordinateTransform.inertialToBody(a_cmd_inertial, attitude);

        // 2. Compute current flight state
        double V = velocity_body.length();
        if (V < 1.0) V = 1.0; // Prevent division by zero at launch

        double dynamicPressure = 0.5 * airDensity * V * V;

        // 3. Compute current aerodynamic angles
        Vector3D aeroAngles = CoordinateTransform.getAeroAngles(velocity_body);
        double alpha_current = aeroAngles.x; // Angle of attack
        double beta_current = aeroAngles.y;  // Sideslip angle

        // 4. Compute required normal acceleration (perpendicular to velocity)
        // For now, focus on pitch plane (Z-axis in body frame)
        double a_normal = a_cmd_body.z;

        // 5. Compute required lift coefficient
        double qS = dynamicPressure * referenceArea;
        double C_L_required = (qS > 1.0) ? (mass * a_normal) / qS : 0;

        // 6. Compute required angle of attack
        double alpha_cmd = C_L_required / CL_ALPHA;

        // 7. Limit to stall angle
        alpha_cmd = Math.max(-MAX_ALPHA, Math.min(MAX_ALPHA, alpha_cmd));

        // ========== MIDDLE LOOP: ANGLE-OF-ATTACK → ANGULAR RATE ==========

        // 8. Compute angle-of-attack error
        double alpha_error = alpha_cmd - alpha_current;

        // 9. Compute angle-of-attack rate (derivative)
        double alpha_dot = (dt > 0.001) ? (alpha_current - alpha_prev) / dt : 0;

        // 10. Apply gain scheduling based on dynamic pressure
        double q_ref = 50000.0; // Reference dynamic pressure (Pa)
        double gainScale = 1.0 / (1.0 + dynamicPressure / q_ref);

        double K_p_effective = K_alpha_p * gainScale;
        double K_d_effective = K_alpha_d * gainScale;

        // 11. Compute commanded pitch rate (PD controller)
        double q_cmd = K_p_effective * alpha_error - K_d_effective * alpha_dot;

        // 12. Limit rate command to reasonable values
        double MAX_RATE_CMD = Math.toRadians(60.0); // 60°/s
        q_cmd = Math.max(-MAX_RATE_CMD, Math.min(MAX_RATE_CMD, q_cmd));

        // ========== INNER LOOP: ANGULAR RATE → FIN DEFLECTION ==========

        // 13. Get current pitch rate (Y-axis in body frame)
        double q_current = angularVelocity_body.y;

        // 14. Compute rate error
        double rate_error = q_cmd - q_current;

        // 15. Update integral term
        rateErrorIntegral += rate_error * dt;

        // 16. Compute desired fin deflection (PI controller)
        double K_p_fin_effective = K_fin_p * gainScale;
        double K_i_fin_effective = K_fin_i * gainScale;

        double delta_desired = K_p_fin_effective * rate_error +
                               K_i_fin_effective * rateErrorIntegral;

        // 17. Apply rate limiting
        double delta_rate = (delta_desired - finDeflection_prev) / dt;
        if (Math.abs(delta_rate) > MAX_FIN_RATE) {
            double sign = (delta_rate > 0) ? 1.0 : -1.0;
            delta_desired = finDeflection_prev + sign * MAX_FIN_RATE * dt;
        }

        // 18. Apply position limiting (saturation)
        double delta_saturated = Math.max(-MAX_FIN_DEFLECTION,
                                          Math.min(MAX_FIN_DEFLECTION, delta_desired));

        // 19. Anti-windup: Back-calculation
        double saturation_error = delta_desired - delta_saturated;
        rateErrorIntegral -= K_BACK_CALC * saturation_error * dt;

        // 20. Store for next iteration
        currentFinDeflection = delta_saturated;
        finDeflection_prev = delta_saturated;
        alpha_prev = alpha_current;
        time_prev = currentTime;

        // ========== RETURN COMMAND ==========

        return new AutopilotCommand(
            alpha_cmd,              // Commanded angle of attack
            q_cmd,                  // Commanded pitch rate
            delta_saturated,        // Fin deflection
            alpha_current,          // Current alpha
            alpha_error,            // Alpha error
            rate_error,             // Rate error
            dynamicPressure,        // Dynamic pressure
            gainScale              // Gain scaling factor
        );
    }

    // ========== GAIN ADJUSTMENT ==========

    /**
     * Set control gains for different flight phases.
     *
     * @param gains control gain set
     */
    public void setGains(ControlGains gains) {
        this.K_alpha_p = gains.K_alpha_p;
        this.K_alpha_d = gains.K_alpha_d;
        this.K_rate_p = gains.K_rate_p;
        this.K_fin_p = gains.K_fin_p;
        this.K_fin_i = gains.K_fin_i;
    }

    /**
     * Update missile mass (changes with fuel consumption).
     *
     * @param newMass updated mass (kg)
     */
    public void updateMass(double newMass) {
        this.mass = newMass;
    }

    /**
     * Reset autopilot state (for initialization or mode changes).
     */
    public void reset() {
        alpha_prev = 0;
        time_prev = 0;
        rateErrorIntegral = 0;
        finDeflection_prev = 0;
        currentFinDeflection = 0;
    }

    /**
     * Get current fin deflection.
     */
    public double getCurrentFinDeflection() {
        return currentFinDeflection;
    }

    // ========== AUTOPILOT COMMAND CLASS ==========

    /**
     * Autopilot command output.
     */
    public static class AutopilotCommand {
        /** Commanded angle of attack (rad) */
        public final double alphaCmd;

        /** Commanded pitch rate (rad/s) */
        public final double rateCmd;

        /** Fin deflection command (rad) */
        public final double finDeflection;

        /** Current angle of attack (rad) */
        public final double alphaCurrent;

        /** Angle of attack error (rad) */
        public final double alphaError;

        /** Rate error (rad/s) */
        public final double rateError;

        /** Dynamic pressure (Pa) */
        public final double dynamicPressure;

        /** Gain scaling factor */
        public final double gainScale;

        public AutopilotCommand(
            double alphaCmd,
            double rateCmd,
            double finDeflection,
            double alphaCurrent,
            double alphaError,
            double rateError,
            double dynamicPressure,
            double gainScale
        ) {
            this.alphaCmd = alphaCmd;
            this.rateCmd = rateCmd;
            this.finDeflection = finDeflection;
            this.alphaCurrent = alphaCurrent;
            this.alphaError = alphaError;
            this.rateError = rateError;
            this.dynamicPressure = dynamicPressure;
            this.gainScale = gainScale;
        }

        @Override
        public String toString() {
            return String.format(
                "Autopilot[α_cmd=%.1f°, q_cmd=%.1f°/s, δ=%.1f°, α_err=%.2f°, q=%.0fPa]",
                Math.toDegrees(alphaCmd),
                Math.toDegrees(rateCmd),
                Math.toDegrees(finDeflection),
                Math.toDegrees(alphaError),
                dynamicPressure
            );
        }
    }
}
