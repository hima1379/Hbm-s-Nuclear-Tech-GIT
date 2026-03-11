package com.hbm.entity.missile.ins;

import com.hbm.physics.Quaternion;
import com.hbm.physics.Vector3D;

/**
 * Navigation State - Stores current estimated navigation parameters from INS
 *
 * This class represents the complete navigation solution provided by the
 * Inertial Navigation System (INS). It contains all state variables needed
 * for missile guidance and control.
 *
 * Reference: Bezick et al. (2010) "Inertial Navigation for Guided Missile Systems"
 *
 * @author SM-6 INS Implementation
 */
public class NavigationState {

    /** Position in navigation frame [x, y, z] (meters) */
    public final Vector3D position;

    /** Velocity in navigation frame [vx, vy, vz] (m/s) */
    public final Vector3D velocity;

    /** Attitude quaternion (body → navigation frame transformation) */
    public final Quaternion attitude;

    /** Angular velocity in body frame [p, q, r] (rad/s)
     *  p = roll rate, q = pitch rate, r = yaw rate */
    public final Vector3D angularRate;

    /** Specific force (acceleration minus gravity) in body frame [ax, ay, az] (m/s²) */
    public final Vector3D acceleration;

    /**
     * Constructor - Initialize navigation state
     *
     * @param position Position vector (meters)
     * @param velocity Velocity vector (m/s)
     * @param attitude Attitude quaternion
     * @param angularRate Angular velocity (rad/s)
     * @param acceleration Specific force (m/s²)
     */
    public NavigationState(
        Vector3D position,
        Vector3D velocity,
        Quaternion attitude,
        Vector3D angularRate,
        Vector3D acceleration
    ) {
        this.position = position;
        this.velocity = velocity;
        this.attitude = attitude;
        this.angularRate = angularRate;
        this.acceleration = acceleration;
    }

    /**
     * Get Euler angles from attitude quaternion
     * @return Vector3D [yaw, pitch, roll] in radians
     */
    public Vector3D getEulerAngles() {
        return attitude.toEulerZYX();
    }

    /**
     * Get pitch angle (elevation)
     * @return Pitch angle in radians
     */
    public double getPitch() {
        return getEulerAngles().y;
    }

    /**
     * Get yaw angle (azimuth)
     * @return Yaw angle in radians
     */
    public double getYaw() {
        return getEulerAngles().x;
    }

    /**
     * Get roll angle
     * @return Roll angle in radians
     */
    public double getRoll() {
        return getEulerAngles().z;
    }

    /**
     * Get speed (velocity magnitude)
     * @return Speed in m/s
     */
    public double getSpeed() {
        return Math.sqrt(velocity.x * velocity.x + velocity.y * velocity.y + velocity.z * velocity.z);
    }

    @Override
    public String toString() {
        return String.format(
            "NavState[pos=(%.1f, %.1f, %.1f) m, vel=(%.1f, %.1f, %.1f) m/s, " +
            "pitch=%.1f°, yaw=%.1f°, roll=%.1f°]",
            position.x, position.y, position.z,
            velocity.x, velocity.y, velocity.z,
            Math.toDegrees(getPitch()),
            Math.toDegrees(getYaw()),
            Math.toDegrees(getRoll())
        );
    }
}
