package com.hbm.physics;

/**
 * Aerodynamic Moment Calculations for SM-6 Missile.
 *
 * Computes pitching, yawing, and rolling moments acting on missile body
 * due to aerodynamic forces and control surface deflections.
 *
 * Moment Equations:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Total moment vector (body frame):
 *   M = M_aero + M_control + M_damping
 *
 * Where:
 *   M_aero    = moments from angle of attack / sideslip
 *   M_control = moments from fin deflections
 *   M_damping = moments from angular rates (damping)
 *
 * Aerodynamic Moments:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Pitch moment:
 *   M_pitch = q × S × d × C_m
 *   C_m = C_m_α × α + C_m_q × (q × d)/(2V) + C_m_δ × δ_pitch
 *
 * Yaw moment:
 *   M_yaw = q × S × d × C_n
 *   C_n = C_n_β × β + C_n_r × (r × d)/(2V) + C_n_δ × δ_yaw
 *
 * Roll moment:
 *   M_roll = q × S × d × C_l
 *   C_l = C_l_p × (p × d)/(2V) + C_l_δ × δ_roll
 *
 * Where:
 *   q = dynamic pressure (Pa)
 *   S = reference area (m²)
 *   d = reference diameter (m)
 *   α = angle of attack (rad)
 *   β = sideslip angle (rad)
 *   p, q, r = roll, pitch, yaw rates (rad/s)
 *   δ = control surface deflection (rad)
 *   V = velocity (m/s)
 *
 * SM-6 Aerodynamic Coefficients:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Static Stability:
 *   C_m_α = -8.0  (pitch moment per radian of AoA, stabilizing)
 *   C_n_β = -6.0  (yaw moment per radian of sideslip, stabilizing)
 *
 * Damping Derivatives:
 *   C_m_q = -25.0 (pitch damping)
 *   C_n_r = -20.0 (yaw damping)
 *   C_l_p = -3.0  (roll damping)
 *
 * Control Effectiveness:
 *   C_m_δ = -2.5  (pitch control power)
 *   C_n_δ = -2.5  (yaw control power)
 *   C_l_δ = -1.0  (roll control power)
 *
 * Reference Dimensions:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Length: 6.55 m
 * Diameter: 0.34 m
 * Span: 1.57 m (with fins deployed)
 * Reference area: 0.0908 m² (πr²)
 *
 * @author SM6 6DOF Aerodynamics
 */
public class AerodynamicMoments {

    // ========== SM-6 AERODYNAMIC COEFFICIENTS ==========

    /** Pitch moment coefficient per AoA (C_m_α) */
    private static final double CM_ALPHA = -8.0;

    /** Yaw moment coefficient per sideslip (C_n_β) */
    private static final double CN_BETA = -6.0;

    /** Pitch damping coefficient (C_m_q) */
    private static final double CM_Q = -25.0;

    /** Yaw damping coefficient (C_n_r) */
    private static final double CN_R = -20.0;

    /** Roll damping coefficient (C_l_p) */
    private static final double CL_P = -3.0;

    /** Pitch control coefficient (C_m_δ) */
    private static final double CM_DELTA = -2.5;

    /** Yaw control coefficient (C_n_δ) */
    private static final double CN_DELTA = -2.5;

    /** Roll control coefficient (C_l_δ) */
    private static final double CL_DELTA = -1.0;

    // ========== REFERENCE DIMENSIONS ==========

    /** Reference area (m²) - circular cross-section */
    private static final double REFERENCE_AREA = 0.0908; // π × (0.17)²

    /** Reference diameter (m) */
    private static final double REFERENCE_DIAMETER = 0.34;

    /** Moment arm for control surfaces (m) - from CG to tail fins */
    private static final double MOMENT_ARM = 3.0; // Approx 3m from CG to tail

    /**
     * Compute total aerodynamic moment acting on missile.
     *
     * @param velocity velocity vector (body frame, m/s)
     * @param angularVelocity angular velocity (body frame, rad/s)
     * @param airDensity air density (kg/m³)
     * @param finDeflectionPitch pitch fin deflection (rad)
     * @param finDeflectionYaw yaw fin deflection (rad)
     * @param finDeflectionRoll roll fin deflection (rad)
     * @return moment vector (body frame, N·m)
     */
    public static Vector3D computeMoment(
        Vector3D velocity,
        Vector3D angularVelocity,
        double airDensity,
        double finDeflectionPitch,
        double finDeflectionYaw,
        double finDeflectionRoll
    ) {
        double V = velocity.length();
        if (V < 1.0) V = 1.0; // Prevent division by zero

        // Dynamic pressure
        double q = 0.5 * airDensity * V * V;

        // Get aerodynamic angles
        Vector3D aeroAngles = CoordinateTransform.getAeroAngles(velocity);
        double alpha = aeroAngles.x; // Angle of attack
        double beta = aeroAngles.y;  // Sideslip angle

        // Extract angular rates
        double p = angularVelocity.x; // Roll rate
        double q_rate = angularVelocity.y; // Pitch rate
        double r = angularVelocity.z; // Yaw rate

        // ========== PITCH MOMENT (Y-axis, body frame) ==========

        double C_m = CM_ALPHA * alpha +
                    CM_Q * (q_rate * REFERENCE_DIAMETER) / (2.0 * V) +
                    CM_DELTA * finDeflectionPitch;

        double M_pitch = q * REFERENCE_AREA * REFERENCE_DIAMETER * C_m;

        // ========== YAW MOMENT (Z-axis, body frame) ==========

        double C_n = CN_BETA * beta +
                    CN_R * (r * REFERENCE_DIAMETER) / (2.0 * V) +
                    CN_DELTA * finDeflectionYaw;

        double M_yaw = q * REFERENCE_AREA * REFERENCE_DIAMETER * C_n;

        // ========== ROLL MOMENT (X-axis, body frame) ==========

        double C_l = CL_P * (p * REFERENCE_DIAMETER) / (2.0 * V) +
                    CL_DELTA * finDeflectionRoll;

        double M_roll = q * REFERENCE_AREA * REFERENCE_DIAMETER * C_l;

        // Return total moment vector (body frame)
        return new Vector3D(M_roll, M_pitch, M_yaw);
    }

    /**
     * Compute control surface deflections from autopilot command.
     *
     * Simplified model: Maps single fin deflection to pitch/yaw/roll controls.
     * Real SM-6 has 4 tail fins with individual control.
     *
     * @param finDeflection total fin deflection command (rad)
     * @param pitchCommand pitch command fraction [-1, 1]
     * @param yawCommand yaw command fraction [-1, 1]
     * @return array of [pitch, yaw, roll] deflections (rad)
     */
    public static double[] mapFinDeflections(
        double finDeflection,
        double pitchCommand,
        double yawCommand
    ) {
        // Map autopilot command to individual control surfaces
        double pitch_deflection = finDeflection * pitchCommand;
        double yaw_deflection = finDeflection * yawCommand;
        double roll_deflection = 0.0; // SM-6 uses tail control, minimal roll control

        return new double[] {pitch_deflection, yaw_deflection, roll_deflection};
    }

    /**
     * Get reference area.
     */
    public static double getReferenceArea() {
        return REFERENCE_AREA;
    }

    /**
     * Get reference diameter.
     */
    public static double getReferenceDiameter() {
        return REFERENCE_DIAMETER;
    }

    /**
     * Get moment arm.
     */
    public static double getMomentArm() {
        return MOMENT_ARM;
    }
}
