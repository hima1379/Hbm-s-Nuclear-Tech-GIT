package com.hbm.entity.missile.ins;

import com.hbm.physics.Vector3D;
import java.util.Random;

/**
 * IMU Sensor Model - Tactical Grade Inertial Measurement Unit
 *
 * Simulates a realistic tactical-grade IMU with:
 * - 3-axis ring-laser or fiber-optic gyroscopes
 * - 3-axis MEMS accelerometers
 * - Bias errors, scale factors, and white noise
 *
 * Specifications based on:
 * Bezick et al. (2010) "Inertial Navigation for Guided Missile Systems" Table 1
 * - Gyro bias: 1-10 °/h (tactical grade)
 * - Accel bias: ~1 mg
 * - Random walk noise on both sensors
 *
 * @author SM-6 INS Implementation
 */
public class IMUSensor {

    // ======================== TACTICAL-GRADE IMU SPECIFICATIONS ========================

    /** Gyro bias stability (rad/s) - Tactical grade: 5 °/h */
    private static final double GYRO_BIAS_STABILITY = Math.toRadians(5.0 / 3600.0);

    /** Gyro random walk (rad/s/√s) */
    private static final double GYRO_RANDOM_WALK = Math.toRadians(0.01 / 60.0);

    /** Gyro scale factor error (ppm - parts per million) */
    private static final double GYRO_SCALE_FACTOR_ERROR = 100.0e-6;  // 100 ppm

    /** Accelerometer bias stability (m/s²) - Tactical grade: 1 mg */
    private static final double ACCEL_BIAS_STABILITY = 0.001 * 9.80665;

    /** Accelerometer random walk (m/s²/√s) */
    private static final double ACCEL_RANDOM_WALK = 0.0001;

    /** Accelerometer scale factor error (ppm) */
    private static final double ACCEL_SCALE_FACTOR_ERROR = 200.0e-6;  // 200 ppm

    // ======================== SENSOR ERROR STATES ========================

    /** Gyroscope bias vector [bx, by, bz] (rad/s) */
    private Vector3D gyroBias;

    /** Accelerometer bias vector [bx, by, bz] (m/s²) */
    private Vector3D accelBias;

    /** Random number generator for sensor noise */
    private Random random;

    /** Gyro scale factor errors [ex, ey, ez] (dimensionless) */
    private Vector3D gyroScaleFactor;

    /** Accel scale factor errors [ex, ey, ez] (dimensionless) */
    private Vector3D accelScaleFactor;

    /**
     * Constructor - Initialize IMU sensor with random biases
     */
    public IMUSensor() {
        this.random = new Random();
        initializeBiases();
    }

    /**
     * Constructor with seed for reproducible testing
     * @param seed Random seed for deterministic bias generation
     */
    public IMUSensor(long seed) {
        this.random = new Random(seed);
        initializeBiases();
    }

    /**
     * Initialize sensor biases at startup
     * Biases are drawn from Gaussian distribution with specified stability
     */
    private void initializeBiases() {
        // Gyro biases (each axis independent)
        double bx_gyro = random.nextGaussian() * GYRO_BIAS_STABILITY;
        double by_gyro = random.nextGaussian() * GYRO_BIAS_STABILITY;
        double bz_gyro = random.nextGaussian() * GYRO_BIAS_STABILITY;
        this.gyroBias = new Vector3D(bx_gyro, by_gyro, bz_gyro);

        // Accelerometer biases
        double bx_accel = random.nextGaussian() * ACCEL_BIAS_STABILITY;
        double by_accel = random.nextGaussian() * ACCEL_BIAS_STABILITY;
        double bz_accel = random.nextGaussian() * ACCEL_BIAS_STABILITY;
        this.accelBias = new Vector3D(bx_accel, by_accel, bz_accel);

        // Scale factor errors (small, typically < 0.01%)
        this.gyroScaleFactor = new Vector3D(
            random.nextGaussian() * GYRO_SCALE_FACTOR_ERROR,
            random.nextGaussian() * GYRO_SCALE_FACTOR_ERROR,
            random.nextGaussian() * GYRO_SCALE_FACTOR_ERROR
        );

        this.accelScaleFactor = new Vector3D(
            random.nextGaussian() * ACCEL_SCALE_FACTOR_ERROR,
            random.nextGaussian() * ACCEL_SCALE_FACTOR_ERROR,
            random.nextGaussian() * ACCEL_SCALE_FACTOR_ERROR
        );

        System.out.println("[IMU INIT] Tactical-grade IMU initialized");
        System.out.println("[IMU INIT] Gyro bias: (" +
            String.format("%.2e", gyroBias.x) + ", " +
            String.format("%.2e", gyroBias.y) + ", " +
            String.format("%.2e", gyroBias.z) + ") rad/s");
        System.out.println("[IMU INIT] Accel bias: (" +
            String.format("%.4f", accelBias.x) + ", " +
            String.format("%.4f", accelBias.y) + ", " +
            String.format("%.4f", accelBias.z) + ") m/s²");
    }

    /**
     * Measure angular velocity with gyroscopes
     *
     * Model: ω_meas = (1 + s) * ω_true + b + n
     * where:
     *   s = scale factor error
     *   b = bias
     *   n = white noise (random walk)
     *
     * @param trueOmega True angular velocity in body frame [p, q, r] (rad/s)
     * @return Measured angular velocity with errors (rad/s)
     */
    public Vector3D measureAngularRate(Vector3D trueOmega) {
        // Apply scale factor error
        double omega_x = trueOmega.x * (1.0 + gyroScaleFactor.x);
        double omega_y = trueOmega.y * (1.0 + gyroScaleFactor.y);
        double omega_z = trueOmega.z * (1.0 + gyroScaleFactor.z);

        // Add bias
        omega_x += gyroBias.x;
        omega_y += gyroBias.y;
        omega_z += gyroBias.z;

        // Add white noise (random walk)
        omega_x += random.nextGaussian() * GYRO_RANDOM_WALK;
        omega_y += random.nextGaussian() * GYRO_RANDOM_WALK;
        omega_z += random.nextGaussian() * GYRO_RANDOM_WALK;

        return new Vector3D(omega_x, omega_y, omega_z);
    }

    /**
     * Measure specific force with accelerometers
     *
     * Model: f_meas = (1 + s) * f_true + b + n
     * where:
     *   s = scale factor error
     *   b = bias
     *   n = white noise (random walk)
     *
     * Note: Accelerometers measure specific force (f = a - g), not acceleration directly!
     *
     * @param trueAccel True specific force in body frame [fx, fy, fz] (m/s²)
     * @return Measured specific force with errors (m/s²)
     */
    public Vector3D measureAcceleration(Vector3D trueAccel) {
        // Apply scale factor error
        double f_x = trueAccel.x * (1.0 + accelScaleFactor.x);
        double f_y = trueAccel.y * (1.0 + accelScaleFactor.y);
        double f_z = trueAccel.z * (1.0 + accelScaleFactor.z);

        // Add bias
        f_x += accelBias.x;
        f_y += accelBias.y;
        f_z += accelBias.z;

        // Add white noise
        f_x += random.nextGaussian() * ACCEL_RANDOM_WALK;
        f_y += random.nextGaussian() * ACCEL_RANDOM_WALK;
        f_z += random.nextGaussian() * ACCEL_RANDOM_WALK;

        return new Vector3D(f_x, f_y, f_z);
    }

    /**
     * Get current gyro bias (for Kalman filter estimation)
     * @return Gyro bias vector (rad/s)
     */
    public Vector3D getGyroBias() {
        return gyroBias;
    }

    /**
     * Get current accel bias (for Kalman filter estimation)
     * @return Accel bias vector (m/s²)
     */
    public Vector3D getAccelBias() {
        return accelBias;
    }

    /**
     * Update gyro bias (typically called by Kalman filter)
     * @param newBias Updated bias estimate (rad/s)
     */
    public void updateGyroBias(Vector3D newBias) {
        this.gyroBias = newBias;
    }

    /**
     * Update accel bias (typically called by Kalman filter)
     * @param newBias Updated bias estimate (m/s²)
     */
    public void updateAccelBias(Vector3D newBias) {
        this.accelBias = newBias;
    }
}
