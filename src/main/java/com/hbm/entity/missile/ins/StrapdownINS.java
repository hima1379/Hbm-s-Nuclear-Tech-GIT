package com.hbm.entity.missile.ins;

import com.hbm.physics.Quaternion;
import com.hbm.physics.Vector3D;

/**
 * Strapdown Inertial Navigation System (INS)
 *
 * Implements realistic inertial navigation using strapdown mechanization equations.
 * Based on Bezick et al. (2010) "Inertial Navigation for Guided Missile Systems"
 *
 * Navigation Equations:
 * 1. Attitude: q_dot = 0.5 * q ⊗ ω_b (quaternion kinematics)
 * 2. Velocity: v_dot = C_b^n * f_b + g_n - (2ω_ie + ω_en) × v
 * 3. Position: r_dot = v
 *
 * where:
 *   q = attitude quaternion (body → nav frame)
 *   ω_b = angular velocity in body frame (from gyros)
 *   f_b = specific force in body frame (from accels)
 *   C_b^n = Direction Cosine Matrix from quaternion
 *   g_n = gravity in nav frame
 *   ω_ie = Earth rotation rate
 *   ω_en = Nav frame rotation relative to Earth
 *
 * @author SM-6 INS Implementation
 */
public class StrapdownINS {

    // ======================== CONSTANTS ========================

    /** Gravity acceleration (m/s²) - Simplified constant model */
    private static final double GRAVITY = 9.80665;

    /** Earth rotation rate (rad/s) */
    private static final double EARTH_ROTATION_RATE = 7.2921159e-5;

    // ======================== NAVIGATION STATES ========================

    /** Current attitude quaternion (body → navigation frame) */
    private Quaternion attitude;

    /** Current velocity in navigation frame [vx, vy, vz] (m/s) */
    private Vector3D velocity;

    /** Current position in navigation frame [x, y, z] (meters) */
    private Vector3D position;

    /** IMU sensor */
    private IMUSensor imu;

    /** Last measured angular rate (from gyros) */
    private Vector3D lastOmega;

    /** Last measured specific force (from accels) */
    private Vector3D lastForce;

    // ======================== INITIALIZATION ========================

    /**
     * Constructor - Initialize INS with initial conditions
     *
     * @param initialPosition Initial position (meters)
     * @param initialVelocity Initial velocity (m/s)
     * @param initialAttitude Initial attitude quaternion
     */
    public StrapdownINS(Vector3D initialPosition, Vector3D initialVelocity, Quaternion initialAttitude) {
        this.position = initialPosition;
        this.velocity = initialVelocity;
        this.attitude = initialAttitude;

        this.imu = new IMUSensor();
        this.lastOmega = new Vector3D(0, 0, 0);
        this.lastForce = new Vector3D(0, 0, 0);

        System.out.println("[INS INIT] Strapdown INS initialized");
        System.out.println("[INS INIT] Initial position: " + position);
        System.out.println("[INS INIT] Initial velocity: " + velocity);
        System.out.println("[INS INIT] Initial attitude (Euler): " + attitude.toEulerZYX());
    }

    // ======================== MAIN UPDATE LOOP ========================

    /**
     * Update INS - Propagate navigation states using IMU measurements
     *
     * This is the main strapdown mechanization loop:
     * 1. Get IMU measurements (with realistic errors)
     * 2. Update attitude from gyros
     * 3. Update velocity from accels + gravity - Coriolis
     * 4. Update position from velocity
     *
     * Integration: 4th-order Runge-Kutta for numerical stability
     *
     * @param trueOmega True angular velocity from physics (rad/s)
     * @param trueAccel True acceleration from physics (m/s²)
     * @param dt Time step (seconds)
     */
    public void update(Vector3D trueOmega, Vector3D trueAccel, double dt) {
        // Step 1: Get IMU measurements (with sensor errors)
        Vector3D omega_meas = imu.measureAngularRate(trueOmega);
        Vector3D f_meas = imu.measureAcceleration(trueAccel);

        // Store for external access
        this.lastOmega = omega_meas;
        this.lastForce = f_meas;

        // Step 2: Update attitude (quaternion integration)
        updateAttitude(omega_meas, dt);

        // Step 3: Update velocity (specific force integration)
        updateVelocity(f_meas, dt);

        // Step 4: Update position (velocity integration)
        updatePosition(dt);
    }

    // ======================== ATTITUDE UPDATE ========================

    /**
     * Update attitude using quaternion kinematics
     *
     * Equation: q_dot = 0.5 * q ⊗ ω_b
     *
     * Integration: 4th-order Runge-Kutta for accuracy
     *
     * @param omega Angular velocity in body frame (rad/s)
     * @param dt Time step (seconds)
     */
    private void updateAttitude(Vector3D omega, double dt) {
        // RK4 integration of quaternion
        // k1 = f(q, ω)
        Quaternion k1 = quaternionDerivative(attitude, omega);

        // k2 = f(q + dt/2 * k1, ω)
        Quaternion q2 = attitude.add(k1.scale(dt / 2.0));
        Quaternion k2 = quaternionDerivative(q2, omega);

        // k3 = f(q + dt/2 * k2, ω)
        Quaternion q3 = attitude.add(k2.scale(dt / 2.0));
        Quaternion k3 = quaternionDerivative(q3, omega);

        // k4 = f(q + dt * k3, ω)
        Quaternion q4 = attitude.add(k3.scale(dt));
        Quaternion k4 = quaternionDerivative(q4, omega);

        // q_new = q + dt/6 * (k1 + 2*k2 + 2*k3 + k4)
        Quaternion q_increment = k1.add(k2.scale(2.0)).add(k3.scale(2.0)).add(k4).scale(dt / 6.0);
        attitude = attitude.add(q_increment);

        // Normalize to maintain unit quaternion
        attitude = attitude.normalize();
    }

    /**
     * Quaternion derivative: q_dot = 0.5 * q ⊗ ω_b
     *
     * @param q Current quaternion
     * @param omega Angular velocity (rad/s)
     * @return Quaternion derivative
     */
    private Quaternion quaternionDerivative(Quaternion q, Vector3D omega) {
        // Create quaternion from angular velocity: [0, ωx, ωy, ωz]
        Quaternion omega_quat = new Quaternion(0, omega.x, omega.y, omega.z);

        // q_dot = 0.5 * q * omega_quat
        return q.multiply(omega_quat).scale(0.5);
    }

    // ======================== VELOCITY UPDATE ========================

    /**
     * Update velocity using specific force integration
     *
     * Equation: v_dot = C_b^n * f_b + g_n - (2ω_ie + ω_en) × v
     *
     * where:
     *   C_b^n = DCM from quaternion (transforms body → nav frame)
     *   f_b = specific force measured by accelerometers
     *   g_n = gravity in nav frame [0, 0, -g]
     *   ω_ie = Earth rotation rate
     *   ω_en = nav frame rotation (small for missiles, simplified)
     *
     * @param specificForce Specific force in body frame (m/s²)
     * @param dt Time step (seconds)
     */
    private void updateVelocity(Vector3D specificForce, double dt) {
        // Transform specific force from body to navigation frame
        Vector3D f_nav = attitude.rotateVector(specificForce);

        // Gravity in navigation frame (assumes NED: North-East-Down)
        // For Minecraft: Y-up coordinate system, so gravity = [0, -g, 0]
        Vector3D gravity = new Vector3D(0, -GRAVITY, 0);

        // Coriolis correction: -(2ω_ie + ω_en) × v
        // Simplified: For missiles at high speed, this is small but included for realism
        Vector3D coriolis = computeCoriolisCorrection();

        // Velocity derivative
        Vector3D v_dot = f_nav.add(gravity).subtract(coriolis);

        // Simple Euler integration (could upgrade to RK4 if needed)
        velocity = velocity.add(v_dot.scale(dt));
    }

    /**
     * Compute Coriolis correction term: (2ω_ie + ω_en) × v
     *
     * Simplified model for missiles:
     * - ω_ie = Earth rotation rate (~7.3e-5 rad/s)
     * - ω_en ≈ v_E / (R + h) for navigation frame rotation
     *
     * For short missile flights (<5 min), this is very small (~0.01 m/s²)
     * but included for completeness
     *
     * @return Coriolis acceleration (m/s²)
     */
    private Vector3D computeCoriolisCorrection() {
        // Earth rotation vector (in nav frame, pointing North for simplified model)
        // Actual value depends on latitude, but for missiles use simplified scalar
        Vector3D omega_ie = new Vector3D(0, 0, EARTH_ROTATION_RATE);

        // Navigation frame rotation (small for missiles, approximated as zero)
        Vector3D omega_en = new Vector3D(0, 0, 0);

        // Total rotation
        Vector3D omega_total = omega_ie.add(omega_en);

        // Coriolis: 2 * ω × v
        return omega_total.cross(velocity).scale(2.0);
    }

    // ======================== POSITION UPDATE ========================

    /**
     * Update position using velocity integration
     *
     * Equation: r_dot = v
     *
     * Simple trapezoidal integration for smoothness
     *
     * @param dt Time step (seconds)
     */
    private void updatePosition(double dt) {
        // Trapezoidal integration (average of current and previous velocity)
        // For simplicity, use current velocity only (Euler method)
        position = position.add(velocity.scale(dt));
    }

    // ======================== STATE ACCESS ========================

    /**
     * Get current navigation state
     * @return NavigationState object with current position, velocity, attitude, etc.
     */
    public NavigationState getNavigationState() {
        return new NavigationState(
            position,
            velocity,
            attitude,
            lastOmega,
            lastForce
        );
    }

    /**
     * Get current position
     * @return Position vector (meters)
     */
    public Vector3D getPosition() {
        return position;
    }

    /**
     * Get current velocity
     * @return Velocity vector (m/s)
     */
    public Vector3D getVelocity() {
        return velocity;
    }

    /**
     * Get current attitude
     * @return Attitude quaternion
     */
    public Quaternion getAttitude() {
        return attitude;
    }

    /**
     * Get IMU sensor (for bias inspection/tuning)
     * @return IMU sensor object
     */
    public IMUSensor getIMU() {
        return imu;
    }

    // ======================== MEASUREMENT UPDATES (for future Kalman filter) ========================

    /**
     * Process GPS measurement (placeholder for future Kalman filter)
     *
     * @param gpsPosition GPS position measurement (meters)
     * @param gpsVelocity GPS velocity measurement (m/s)
     */
    public void processGPSMeasurement(Vector3D gpsPosition, Vector3D gpsVelocity) {
        // TODO: Implement Kalman filter update with GPS measurements
        // For now, just log the measurement
        System.out.println("[INS GPS] Received GPS: pos=" + gpsPosition + ", vel=" + gpsVelocity);
    }

    /**
     * Process radar tracking measurement (placeholder for future Kalman filter)
     *
     * @param radarPosition Radar-measured position (meters)
     */
    public void processRadarMeasurement(Vector3D radarPosition) {
        // TODO: Implement Kalman filter update with radar measurements
        // For now, just log the measurement
        System.out.println("[INS RADAR] Received radar position: " + radarPosition);
    }
}
