package com.hbm.physics;

/**
 * Quaternion class for 3D rotations and attitude representation.
 *
 * Quaternion representation: q = w + xi + yj + zk
 * where w² + x² + y² + z² = 1 for unit quaternions (rotations)
 *
 * This implementation follows aerospace conventions:
 * - Hamilton convention for multiplication
 * - Passive (coordinate transformation) interpretation
 * - Body-to-inertial frame transformation
 *
 * Based on: "Six-Degree-of-Freedom Digital Simulations for Missile GNC"
 * (Hawley & Blauwkamp, Johns Hopkins APL Technical Digest, 2010)
 *
 * @author SM6 Missile Physics Engine
 */
public class Quaternion {

    // Quaternion components (w is scalar part, x/y/z are vector part)
    public final double w, x, y, z;

    // ========== CONSTRUCTORS ==========

    /**
     * Constructs a quaternion from components.
     * @param w scalar component
     * @param x i component
     * @param y j component
     * @param z k component
     */
    public Quaternion(double w, double x, double y, double z) {
        this.w = w;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Constructs an identity quaternion (no rotation).
     */
    public static Quaternion identity() {
        return new Quaternion(1.0, 0.0, 0.0, 0.0);
    }

    /**
     * Constructs a quaternion from axis-angle representation.
     * @param axis rotation axis (will be normalized)
     * @param angleRad rotation angle in radians
     * @return quaternion representing this rotation
     */
    public static Quaternion fromAxisAngle(Vector3D axis, double angleRad) {
        Vector3D normAxis = axis.normalize();
        double halfAngle = angleRad * 0.5;
        double sinHalf = Math.sin(halfAngle);
        return new Quaternion(
            Math.cos(halfAngle),
            normAxis.x * sinHalf,
            normAxis.y * sinHalf,
            normAxis.z * sinHalf
        );
    }

    /**
     * Constructs a quaternion from Euler angles (intrinsic Z-Y-X / yaw-pitch-roll).
     *
     * Convention: Rotate around Z (yaw), then Y' (pitch), then X'' (roll)
     * This is the most common aerospace convention.
     *
     * @param yawRad rotation around Z axis (radians)
     * @param pitchRad rotation around Y axis (radians)
     * @param rollRad rotation around X axis (radians)
     * @return quaternion representing this orientation
     */
    public static Quaternion fromEulerZYX(double yawRad, double pitchRad, double rollRad) {
        double cy = Math.cos(yawRad * 0.5);
        double sy = Math.sin(yawRad * 0.5);
        double cp = Math.cos(pitchRad * 0.5);
        double sp = Math.sin(pitchRad * 0.5);
        double cr = Math.cos(rollRad * 0.5);
        double sr = Math.sin(rollRad * 0.5);

        return new Quaternion(
            cr * cp * cy + sr * sp * sy,
            sr * cp * cy - cr * sp * sy,
            cr * sp * cy + sr * cp * sy,
            cr * cp * sy - sr * sp * cy
        );
    }

    /**
     * Converts quaternion to Euler angles (intrinsic Z-Y-X).
     *
     * @return Vector3D with [yaw, pitch, roll] in radians
     */
    public Vector3D toEulerZYX() {
        // Yaw (Z-axis rotation)
        double yaw = Math.atan2(
            2.0 * (w * z + x * y),
            1.0 - 2.0 * (y * y + z * z)
        );

        // Pitch (Y-axis rotation)
        double sinPitch = 2.0 * (w * y - z * x);
        double pitch;
        if (Math.abs(sinPitch) >= 1.0) {
            // Gimbal lock case
            pitch = Math.copySign(Math.PI / 2.0, sinPitch);
        } else {
            pitch = Math.asin(sinPitch);
        }

        // Roll (X-axis rotation)
        double roll = Math.atan2(
            2.0 * (w * x + y * z),
            1.0 - 2.0 * (x * x + y * y)
        );

        return new Vector3D(yaw, pitch, roll);
    }

    // ========== QUATERNION OPERATIONS ==========

    /**
     * Quaternion multiplication (Hamilton product).
     *
     * Convention: q1 * q2 means "first rotate by q2, then rotate by q1"
     *
     * @param other quaternion to multiply with
     * @return product quaternion
     */
    public Quaternion multiply(Quaternion other) {
        return new Quaternion(
            this.w * other.w - this.x * other.x - this.y * other.y - this.z * other.z,
            this.w * other.x + this.x * other.w + this.y * other.z - this.z * other.y,
            this.w * other.y - this.x * other.z + this.y * other.w + this.z * other.x,
            this.w * other.z + this.x * other.y - this.y * other.x + this.z * other.w
        );
    }

    /**
     * Quaternion conjugate.
     * For unit quaternions, conjugate = inverse.
     *
     * @return conjugate quaternion
     */
    public Quaternion conjugate() {
        return new Quaternion(w, -x, -y, -z);
    }

    /**
     * Quaternion norm (magnitude).
     *
     * @return ||q|| = sqrt(w² + x² + y² + z²)
     */
    public double norm() {
        return Math.sqrt(w * w + x * x + y * y + z * z);
    }

    /**
     * Normalize quaternion to unit length.
     *
     * @return normalized quaternion
     */
    public Quaternion normalize() {
        double n = norm();
        if (n < 1e-12) {
            // Degenerate case - return identity
            return identity();
        }
        return new Quaternion(w / n, x / n, y / n, z / n);
    }

    /**
     * Quaternion dot product.
     *
     * @param other quaternion
     * @return dot product
     */
    public double dot(Quaternion other) {
        return this.w * other.w + this.x * other.x + this.y * other.y + this.z * other.z;
    }

    /**
     * Add quaternion (component-wise).
     * Used in integration: q_new = q_old + dq
     *
     * @param other quaternion to add
     * @return sum
     */
    public Quaternion add(Quaternion other) {
        return new Quaternion(
            this.w + other.w,
            this.x + other.x,
            this.y + other.y,
            this.z + other.z
        );
    }

    /**
     * Subtract quaternion (component-wise).
     *
     * @param other quaternion to subtract
     * @return difference
     */
    public Quaternion subtract(Quaternion other) {
        return new Quaternion(
            this.w - other.w,
            this.x - other.x,
            this.y - other.y,
            this.z - other.z
        );
    }

    /**
     * Scale quaternion by scalar.
     *
     * @param scalar scaling factor
     * @return scaled quaternion
     */
    public Quaternion scale(double scalar) {
        return new Quaternion(w * scalar, x * scalar, y * scalar, z * scalar);
    }

    /**
     * Spherical Linear Interpolation (SLERP).
     *
     * Provides smooth interpolation between two quaternions.
     * Essential for animation and trajectory smoothing.
     *
     * @param target target quaternion
     * @param t interpolation parameter [0, 1]
     * @return interpolated quaternion
     */
    public Quaternion slerp(Quaternion target, double t) {
        // Compute dot product
        double dotProd = this.dot(target);

        // If quaternions are very close, use linear interpolation
        if (dotProd > 0.9995) {
            Quaternion result = new Quaternion(
                this.w + t * (target.w - this.w),
                this.x + t * (target.x - this.x),
                this.y + t * (target.y - this.y),
                this.z + t * (target.z - this.z)
            );
            return result.normalize();
        }

        // Ensure shortest path (dot product should be positive)
        Quaternion q2 = target;
        if (dotProd < 0.0) {
            q2 = target.scale(-1.0);
            dotProd = -dotProd;
        }

        // Clamp dot product to valid range for acos
        dotProd = Math.max(-1.0, Math.min(1.0, dotProd));

        // Calculate angle between quaternions
        double theta = Math.acos(dotProd);
        double sinTheta = Math.sin(theta);

        // Calculate interpolation coefficients
        double w1 = Math.sin((1.0 - t) * theta) / sinTheta;
        double w2 = Math.sin(t * theta) / sinTheta;

        return new Quaternion(
            w1 * this.w + w2 * q2.w,
            w1 * this.x + w2 * q2.x,
            w1 * this.y + w2 * q2.y,
            w1 * this.z + w2 * q2.z
        );
    }

    /**
     * Rotate a 3D vector by this quaternion.
     *
     * Formula: v' = q * v * q*
     * where v is treated as a pure quaternion (0, vx, vy, vz)
     *
     * @param v vector to rotate
     * @return rotated vector
     */
    public Vector3D rotateVector(Vector3D v) {
        // Convert vector to pure quaternion
        Quaternion vecQuat = new Quaternion(0, v.x, v.y, v.z);

        // Perform rotation: q * v * q*
        Quaternion result = this.multiply(vecQuat).multiply(this.conjugate());

        return new Vector3D(result.x, result.y, result.z);
    }

    /**
     * Inverse rotate a vector (rotate from rotated frame back to original).
     *
     * Formula: v = q* * v' * q
     *
     * @param v vector in rotated frame
     * @return vector in original frame
     */
    public Vector3D inverseRotateVector(Vector3D v) {
        return this.conjugate().rotateVector(v);
    }

    /**
     * Compute quaternion derivative from angular velocity.
     *
     * Formula from Hawley & Blauwkamp paper:
     * dq/dt = 0.5 * q ⊗ ω
     *
     * where ω is angular velocity in body frame as quaternion (0, ωx, ωy, ωz)
     *
     * @param angularVelocity angular velocity in body frame [rad/s]
     * @return quaternion derivative
     */
    public Quaternion derivative(Vector3D angularVelocity) {
        // Convert angular velocity to pure quaternion
        Quaternion omegaQuat = new Quaternion(0, angularVelocity.x, angularVelocity.y, angularVelocity.z);

        // dq/dt = 0.5 * q * omega
        return this.multiply(omegaQuat).scale(0.5);
    }

    /**
     * Convert quaternion to Direction Cosine Matrix (DCM).
     *
     * This DCM transforms vectors from body frame to inertial frame.
     *
     * @return 3x3 rotation matrix
     */
    public Matrix3x3 toDCM() {
        double w2 = w * w;
        double x2 = x * x;
        double y2 = y * y;
        double z2 = z * z;

        double[][] elements = {
            {
                1.0 - 2.0 * (y2 + z2),
                2.0 * (x * y - w * z),
                2.0 * (x * z + w * y)
            },
            {
                2.0 * (x * y + w * z),
                1.0 - 2.0 * (x2 + z2),
                2.0 * (y * z - w * x)
            },
            {
                2.0 * (x * z - w * y),
                2.0 * (y * z + w * x),
                1.0 - 2.0 * (x2 + y2)
            }
        };

        return new Matrix3x3(elements);
    }

    /**
     * Construct quaternion from Direction Cosine Matrix.
     *
     * Uses Shepperd's method for numerical stability.
     *
     * @param dcm rotation matrix
     * @return quaternion
     */
    public static Quaternion fromDCM(Matrix3x3 dcm) {
        double trace = dcm.get(0, 0) + dcm.get(1, 1) + dcm.get(2, 2);

        if (trace > 0) {
            double s = Math.sqrt(trace + 1.0) * 2.0; // s = 4 * w
            return new Quaternion(
                0.25 * s,
                (dcm.get(2, 1) - dcm.get(1, 2)) / s,
                (dcm.get(0, 2) - dcm.get(2, 0)) / s,
                (dcm.get(1, 0) - dcm.get(0, 1)) / s
            );
        } else if (dcm.get(0, 0) > dcm.get(1, 1) && dcm.get(0, 0) > dcm.get(2, 2)) {
            double s = Math.sqrt(1.0 + dcm.get(0, 0) - dcm.get(1, 1) - dcm.get(2, 2)) * 2.0; // s = 4 * x
            return new Quaternion(
                (dcm.get(2, 1) - dcm.get(1, 2)) / s,
                0.25 * s,
                (dcm.get(0, 1) + dcm.get(1, 0)) / s,
                (dcm.get(0, 2) + dcm.get(2, 0)) / s
            );
        } else if (dcm.get(1, 1) > dcm.get(2, 2)) {
            double s = Math.sqrt(1.0 + dcm.get(1, 1) - dcm.get(0, 0) - dcm.get(2, 2)) * 2.0; // s = 4 * y
            return new Quaternion(
                (dcm.get(0, 2) - dcm.get(2, 0)) / s,
                (dcm.get(0, 1) + dcm.get(1, 0)) / s,
                0.25 * s,
                (dcm.get(1, 2) + dcm.get(2, 1)) / s
            );
        } else {
            double s = Math.sqrt(1.0 + dcm.get(2, 2) - dcm.get(0, 0) - dcm.get(1, 1)) * 2.0; // s = 4 * z
            return new Quaternion(
                (dcm.get(1, 0) - dcm.get(0, 1)) / s,
                (dcm.get(0, 2) + dcm.get(2, 0)) / s,
                (dcm.get(1, 2) + dcm.get(2, 1)) / s,
                0.25 * s
            );
        }
    }

    @Override
    public String toString() {
        return String.format("Quaternion[w=%.6f, x=%.6f, y=%.6f, z=%.6f]", w, x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Quaternion)) return false;
        Quaternion other = (Quaternion) obj;
        return Double.compare(w, other.w) == 0 &&
               Double.compare(x, other.x) == 0 &&
               Double.compare(y, other.y) == 0 &&
               Double.compare(z, other.z) == 0;
    }

    @Override
    public int hashCode() {
        long bits = 1L;
        bits = 31L * bits + Double.doubleToLongBits(w);
        bits = 31L * bits + Double.doubleToLongBits(x);
        bits = 31L * bits + Double.doubleToLongBits(y);
        bits = 31L * bits + Double.doubleToLongBits(z);
        return (int) (bits ^ (bits >>> 32));
    }
}
