package com.hbm.entity.missile.guidance;

import com.hbm.physics.Vector3D;

/**
 * Proportional Navigation (PN) Guidance Law Implementation.
 *
 * The foundation of modern missile guidance. PN generates acceleration commands
 * that are proportional to the line-of-sight (LOS) angular rate.
 *
 * Mathematical Foundation (Hawley & Blauwkamp, 2010):
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Command Acceleration:
 *   a_cmd = N × V_c × λ̇
 *
 * Where:
 *   N     = Navigation constant (dimensionless, typically 3-5)
 *   V_c   = Closing velocity (m/s)
 *   λ̇     = Line-of-sight angular rate (rad/s)
 *
 * Detailed Derivation:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * 1. Relative Position Vector:
 *    R = R_target - R_missile
 *
 * 2. Line-of-Sight Unit Vector:
 *    û_los = R / |R|
 *
 * 3. Relative Velocity:
 *    V_rel = V_target - V_missile
 *
 * 4. Closing Velocity (scalar):
 *    V_c = -V_rel · û_los
 *    (Negative sign makes V_c positive when approaching)
 *
 * 5. LOS Angular Rate (vector):
 *    λ̇ = (R × V_rel) / |R|²
 *
 *    Physical Interpretation:
 *    - Magnitude: How fast the LOS is rotating (rad/s)
 *    - Direction: Axis of rotation (perpendicular to engagement plane)
 *
 * 6. Command Acceleration (vector):
 *    a_cmd = N × V_c × λ̇
 *
 *    This acceleration is perpendicular to the LOS and proportional to both
 *    the closing rate and the LOS rotation rate.
 *
 * Collision Triangle Geometry:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * PN works because of a fundamental geometric principle:
 * - If λ̇ = 0 (LOS not rotating), missile and target are on collision course
 * - If λ̇ ≠ 0, PN generates acceleration to drive λ̇ → 0
 * - Navigation constant N controls aggressiveness of convergence
 *
 * Optimal N Selection:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * N = 3:
 *   - Minimum energy solution for non-maneuvering targets
 *   - Slower convergence, graceful trajectory
 *   - Best for long-range cruise phase
 *
 * N = 4:
 *   - Good balance between energy and response time
 *   - Standard for most engagement phases
 *
 * N = 5:
 *   - Aggressive pursuit, faster convergence
 *   - Higher energy consumption
 *   - Best for terminal phase and maneuvering targets
 *
 * Numerical Stability Considerations:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * 1. Small Range (|R| → 0):
 *    - LOS rate calculation becomes numerically unstable
 *    - Solution: Switch to bang-bang control or impact anticipation
 *
 * 2. Nearly Parallel Vectors (R ∥ V_rel):
 *    - Cross product magnitude → 0
 *    - This is actually good! Means we're on collision course
 *
 * 3. Low Closing Velocity:
 *    - Can occur during stern chase or evasion
 *    - Consider time-to-go based guidance modifications
 *
 * Performance Characteristics:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Miss Distance: Proportional to (target maneuver / N)
 * Lead Angle: Automatically computed from geometry
 * Energy Efficiency: Optimal for N = 3
 * Robustness: Excellent for targets with |a_target| < |a_missile| / N
 *
 * @author SM6 Missile Guidance System
 */
public class ProportionalNavigation {

    /** Navigation constant - controls guidance aggressiveness */
    private final double navigationConstant;

    /** Minimum safe range for stable LOS rate calculation (meters) */
    private static final double MIN_SAFE_RANGE = 10.0;

    /** Maximum command acceleration magnitude (m/s²) */
    private final double maxAcceleration;

    // ========== CONSTRUCTORS ==========

    /**
     * Constructs PN guidance with specified navigation constant.
     *
     * @param navigationConstant N value (typically 3-5)
     * @param maxAcceleration maximum command acceleration (m/s²)
     */
    public ProportionalNavigation(double navigationConstant, double maxAcceleration) {
        if (navigationConstant < 2.0 || navigationConstant > 10.0) {
            throw new IllegalArgumentException("Navigation constant should be in range [2, 10]");
        }
        this.navigationConstant = navigationConstant;
        this.maxAcceleration = maxAcceleration;
    }

    /**
     * Constructs PN guidance with default N=4.0 (balanced performance).
     *
     * @param maxAcceleration maximum command acceleration (m/s²)
     */
    public ProportionalNavigation(double maxAcceleration) {
        this(4.0, maxAcceleration);
    }

    // ========== MAIN GUIDANCE COMPUTATION ==========

    /**
     * Compute PN guidance command.
     *
     * @param missilePos missile position (m)
     * @param missileVel missile velocity (m/s)
     * @param targetPos target position (m)
     * @param targetVel target velocity (m/s)
     * @return commanded acceleration vector (m/s²)
     */
    public GuidanceCommand computeCommand(
        Vector3D missilePos,
        Vector3D missileVel,
        Vector3D targetPos,
        Vector3D targetVel
    ) {
        // 1. Compute relative position vector
        Vector3D R = targetPos.subtract(missilePos);
        double range = R.length();

        // Check for numerical stability
        if (range < MIN_SAFE_RANGE) {
            // Too close for stable PN - use alternative guidance
            return computeEndgameCommand(missilePos, missileVel, targetPos, targetVel, range);
        }

        // 2. Compute line-of-sight unit vector
        Vector3D u_los = R.normalize();

        // 3. Compute relative velocity
        Vector3D V_rel = targetVel.subtract(missileVel);

        // 4. Compute closing velocity (scalar)
        double V_c = -V_rel.dot(u_los);

        // Check if we're receding (negative closing velocity)
        if (V_c < 0.1) {
            // Receding or very slow approach - use alternative logic
            return computeRecedingTargetCommand(R, V_rel, range);
        }

        // 5. Compute LOS angular rate vector
        // λ̇ = (R × V_rel) / |R|²
        Vector3D lambda_dot = R.cross(V_rel).scale(1.0 / (range * range));

        // 6. Compute commanded acceleration
        // a_cmd = N × V_c × λ̇
        Vector3D a_cmd = lambda_dot.scale(navigationConstant * V_c);

        // 7. Apply acceleration limit
        a_cmd = a_cmd.clampLength(maxAcceleration);

        // 8. Compute diagnostics
        double losMagnitude = lambda_dot.length();
        double timeToGo = estimateTimeToGo(range, V_c, V_rel.length());

        return new GuidanceCommand(
            a_cmd,
            range,
            V_c,
            losMagnitude,
            timeToGo,
            u_los
        );
    }

    // ========== ENDGAME GUIDANCE (SHORT RANGE) ==========

    /**
     * Endgame guidance for very short ranges (<10m).
     *
     * At short ranges, LOS rate calculation becomes unstable.
     * Switch to impact point prediction and direct pursuit.
     *
     * @param missilePos missile position
     * @param missileVel missile velocity
     * @param targetPos target position
     * @param targetVel target velocity
     * @param range current range
     * @return guidance command
     */
    private GuidanceCommand computeEndgameCommand(
        Vector3D missilePos,
        Vector3D missileVel,
        Vector3D targetPos,
        Vector3D targetVel,
        double range
    ) {
        // Predict impact point assuming constant target velocity
        Vector3D R = targetPos.subtract(missilePos);
        Vector3D V_rel = targetVel.subtract(missileVel);

        double V_missile = missileVel.length();
        if (V_missile < 1.0) {
            // Emergency: just point at target
            return new GuidanceCommand(
                R.normalize().scale(maxAcceleration),
                range, 0, 0, 0.1, R.normalize()
            );
        }

        // Time to intercept (approximate)
        double t_go = range / V_missile;

        // Predicted impact point
        Vector3D impactPoint = targetPos.add(targetVel.scale(t_go));

        // Direction to impact point
        Vector3D toImpact = impactPoint.subtract(missilePos);
        Vector3D desiredVel = toImpact.normalize().scale(V_missile);

        // Acceleration to achieve desired velocity
        Vector3D a_cmd = desiredVel.subtract(missileVel).scale(1.0 / Math.max(t_go, 0.05));
        a_cmd = a_cmd.clampLength(maxAcceleration);

        return new GuidanceCommand(
            a_cmd,
            range,
            V_missile,
            0,
            t_go,
            R.normalize()
        );
    }

    // ========== RECEDING TARGET HANDLING ==========

    /**
     * Handle case where target is receding (stern chase or evasion).
     *
     * Use pure pursuit with energy management considerations.
     *
     * @param R relative position
     * @param V_rel relative velocity
     * @param range current range
     * @return guidance command
     */
    private GuidanceCommand computeRecedingTargetCommand(
        Vector3D R,
        Vector3D V_rel,
        double range
    ) {
        // Pure pursuit: accelerate toward current target position
        Vector3D u_los = R.normalize();
        Vector3D a_cmd = u_los.scale(maxAcceleration);

        double V_c = -V_rel.dot(u_los);

        return new GuidanceCommand(
            a_cmd,
            range,
            V_c,
            0,
            Double.POSITIVE_INFINITY, // Unknown time-to-go
            u_los
        );
    }

    // ========== UTILITY METHODS ==========

    /**
     * Estimate time-to-go based on current geometry.
     *
     * Uses approximation: t_go ≈ range / V_c
     *
     * This is exact for constant-velocity pursuit, approximate for PN.
     *
     * @param range current range
     * @param V_c closing velocity
     * @param V_rel_mag relative velocity magnitude
     * @return estimated time-to-go (seconds)
     */
    private double estimateTimeToGo(double range, double V_c, double V_rel_mag) {
        if (V_c > 1.0) {
            return range / V_c;
        } else if (V_rel_mag > 1.0) {
            // Fallback: use relative velocity magnitude
            return range / V_rel_mag;
        } else {
            return Double.POSITIVE_INFINITY;
        }
    }

    /**
     * Get current navigation constant.
     */
    public double getNavigationConstant() {
        return navigationConstant;
    }

    /**
     * Compute Zero-Effort Miss (ZEM) distance.
     *
     * ZEM is the predicted miss distance if no further guidance commands are issued.
     *
     * Formula:
     *   ZEM = |R + V_rel × t_go|
     *
     * where t_go ≈ -R·V_rel / |V_rel|²
     *
     * @param R relative position
     * @param V_rel relative velocity
     * @return ZEM magnitude (meters)
     */
    public static double computeZEM(Vector3D R, Vector3D V_rel) {
        double V_rel_sq = V_rel.lengthSquared();
        if (V_rel_sq < 1e-6) {
            return R.length(); // No relative velocity
        }

        double t_go = -R.dot(V_rel) / V_rel_sq;
        if (t_go < 0) {
            return Double.POSITIVE_INFINITY; // Already past CPA
        }

        Vector3D missVector = R.add(V_rel.scale(t_go));
        return missVector.length();
    }

    /**
     * Guidance command data structure.
     */
    public static class GuidanceCommand {
        /** Commanded acceleration vector (m/s²) */
        public final Vector3D acceleration;

        /** Current range to target (m) */
        public final double range;

        /** Closing velocity (m/s) */
        public final double closingVelocity;

        /** LOS rate magnitude (rad/s) */
        public final double losRate;

        /** Estimated time-to-go (s) */
        public final double timeToGo;

        /** Line-of-sight unit vector */
        public final Vector3D losVector;

        public GuidanceCommand(
            Vector3D acceleration,
            double range,
            double closingVelocity,
            double losRate,
            double timeToGo,
            Vector3D losVector
        ) {
            this.acceleration = acceleration;
            this.range = range;
            this.closingVelocity = closingVelocity;
            this.losRate = losRate;
            this.timeToGo = timeToGo;
            this.losVector = losVector;
        }

        @Override
        public String toString() {
            return String.format(
                "GuidanceCommand[a=%.1f m/s², range=%.1fm, Vc=%.1fm/s, λ̇=%.4frad/s, tgo=%.2fs]",
                acceleration.length(), range, closingVelocity, losRate, timeToGo
            );
        }
    }
}
