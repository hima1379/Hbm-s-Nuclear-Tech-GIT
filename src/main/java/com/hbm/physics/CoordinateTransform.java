package com.hbm.physics;

/**
 * Coordinate frame transformation utilities for 6DOF missile simulation.
 *
 * Key coordinate frames:
 * - Inertial frame: Fixed to Earth, Y-up (Minecraft convention)
 * - Body frame: Fixed to missile, X-forward, Y-up (relative), Z-right
 * - Wind frame: Aligned with velocity vector
 *
 * @author SM6 Missile Physics Engine
 */
public class CoordinateTransform {

    /**
     * Transform vector from body frame to inertial frame using quaternion.
     *
     * v_inertial = q × v_body × q*
     *
     * @param vBody vector in body frame
     * @param attitude quaternion (body to inertial)
     * @return vector in inertial frame
     */
    public static Vector3D bodyToInertial(Vector3D vBody, Quaternion attitude) {
        return attitude.rotateVector(vBody);
    }

    /**
     * Transform vector from inertial frame to body frame using quaternion.
     *
     * v_body = q* × v_inertial × q
     *
     * @param vInertial vector in inertial frame
     * @param attitude quaternion (body to inertial)
     * @return vector in body frame
     */
    public static Vector3D inertialToBody(Vector3D vInertial, Quaternion attitude) {
        return attitude.inverseRotateVector(vInertial);
    }

    /**
     * Compute angle of attack (α) and sideslip angle (β) from body-frame velocity.
     *
     * α = atan2(v_z, v_x)  - angle in XZ plane (pitch)
     * β = asin(v_y / |v|)  - angle out of XZ plane (yaw)
     *
     * @param velocityBody velocity in body frame
     * @return Vector3D with [α, β, 0] in radians
     */
    public static Vector3D getAeroAngles(Vector3D velocityBody) {
        double V = velocityBody.length();
        if (V < 0.1) {
            return Vector3D.zero(); // No meaningful angles at very low speed
        }

        double alpha = Math.atan2(velocityBody.z, velocityBody.x);
        double beta = Math.asin(Math.max(-1.0, Math.min(1.0, velocityBody.y / V)));

        return new Vector3D(alpha, beta, 0);
    }

    /**
     * Transform vector from wind frame to body frame.
     *
     * Wind frame: X aligned with velocity, Z perpendicular (down when wings level)
     *
     * @param vWind vector in wind frame
     * @param alpha angle of attack (rad)
     * @param beta sideslip angle (rad)
     * @return vector in body frame
     */
    public static Vector3D windToBody(Vector3D vWind, double alpha, double beta) {
        // Rotation matrices for α and β
        double ca = Math.cos(alpha);
        double sa = Math.sin(alpha);
        double cb = Math.cos(beta);
        double sb = Math.sin(beta);

        return new Vector3D(
            ca * cb * vWind.x - ca * sb * vWind.y - sa * vWind.z,
            sb * vWind.x + cb * vWind.y,
            sa * cb * vWind.x - sa * sb * vWind.y + ca * vWind.z
        );
    }

    /**
     * Transform vector from body frame to wind frame.
     *
     * @param vBody vector in body frame
     * @param alpha angle of attack (rad)
     * @param beta sideslip angle (rad)
     * @return vector in wind frame
     */
    public static Vector3D bodyToWind(Vector3D vBody, double alpha, double beta) {
        double ca = Math.cos(alpha);
        double sa = Math.sin(alpha);
        double cb = Math.cos(beta);
        double sb = Math.sin(beta);

        return new Vector3D(
            ca * cb * vBody.x + sb * vBody.y + sa * cb * vBody.z,
            -ca * sb * vBody.x + cb * vBody.y - sa * sb * vBody.z,
            -sa * vBody.x + ca * vBody.z
        );
    }
}
