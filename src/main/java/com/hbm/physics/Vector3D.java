package com.hbm.physics;

/**
 * Enhanced 3D vector class for physics calculations.
 *
 * Provides comprehensive vector operations needed for 6DOF missile simulation:
 * - Basic arithmetic (add, subtract, scale)
 * - Vector products (dot, cross)
 * - Geometric operations (normalize, project, reflect)
 * - Utility methods (length, distance, angle)
 *
 * Immutable design for thread safety and clarity.
 *
 * @author SM6 Missile Physics Engine
 */
public class Vector3D {

    public final double x, y, z;

    // ========== CONSTRUCTORS ==========

    public Vector3D(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Zero vector constructor.
     */
    public static Vector3D zero() {
        return new Vector3D(0, 0, 0);
    }

    /**
     * Unit vector along X axis.
     */
    public static Vector3D unitX() {
        return new Vector3D(1, 0, 0);
    }

    /**
     * Unit vector along Y axis.
     */
    public static Vector3D unitY() {
        return new Vector3D(0, 1, 0);
    }

    /**
     * Unit vector along Z axis.
     */
    public static Vector3D unitZ() {
        return new Vector3D(0, 0, 1);
    }

    // ========== BASIC ARITHMETIC ==========

    /**
     * Add two vectors.
     */
    public Vector3D add(Vector3D other) {
        return new Vector3D(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    /**
     * Subtract another vector from this one.
     */
    public Vector3D subtract(Vector3D other) {
        return new Vector3D(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    /**
     * Multiply vector by scalar.
     */
    public Vector3D scale(double scalar) {
        return new Vector3D(x * scalar, y * scalar, z * scalar);
    }

    /**
     * Component-wise multiplication.
     */
    public Vector3D multiply(Vector3D other) {
        return new Vector3D(this.x * other.x, this.y * other.y, this.z * other.z);
    }

    /**
     * Negate vector (reverse direction).
     */
    public Vector3D negate() {
        return new Vector3D(-x, -y, -z);
    }

    // ========== VECTOR PRODUCTS ==========

    /**
     * Dot product (scalar product).
     *
     * a · b = |a| |b| cos(θ)
     *
     * Returns: Scalar value
     */
    public double dot(Vector3D other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    /**
     * Cross product (vector product).
     *
     * a × b = |a| |b| sin(θ) n̂
     *
     * Right-hand rule: fingers curl from a to b, thumb points in direction of result
     *
     * CRITICAL for guidance: LOS rate = (R × V_rel) / |R|²
     *
     * @param other vector to cross with
     * @return vector perpendicular to both inputs
     */
    public Vector3D cross(Vector3D other) {
        return new Vector3D(
            this.y * other.z - this.z * other.y,
            this.z * other.x - this.x * other.z,
            this.x * other.y - this.y * other.x
        );
    }

    // ========== GEOMETRIC OPERATIONS ==========

    /**
     * Vector length (magnitude, norm).
     *
     * ||v|| = sqrt(x² + y² + z²)
     */
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Squared length (avoids sqrt, faster for comparisons).
     */
    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    /**
     * Distance to another vector.
     */
    public double distanceTo(Vector3D other) {
        return this.subtract(other).length();
    }

    /**
     * Squared distance (faster for comparisons).
     */
    public double distanceSquaredTo(Vector3D other) {
        return this.subtract(other).lengthSquared();
    }

    /**
     * Normalize vector to unit length.
     *
     * Returns zero vector if length is near zero.
     */
    public Vector3D normalize() {
        double len = length();
        if (len < 1e-12) {
            return Vector3D.zero();
        }
        return scale(1.0 / len);
    }

    /**
     * Project this vector onto another vector.
     *
     * proj_b(a) = (a · b̂) b̂
     *
     * CRITICAL for APN: projecting target acceleration onto LOS
     */
    public Vector3D projectOnto(Vector3D onto) {
        double lenSq = onto.lengthSquared();
        if (lenSq < 1e-12) {
            return Vector3D.zero();
        }
        double scale = this.dot(onto) / lenSq;
        return onto.scale(scale);
    }

    /**
     * Get component perpendicular to a direction.
     *
     * v_perp = v - proj_d(v)
     *
     * CRITICAL for APN: target acceleration perpendicular to LOS
     */
    public Vector3D perpendicularTo(Vector3D direction) {
        return this.subtract(this.projectOnto(direction));
    }

    /**
     * Reflect vector across a normal.
     *
     * v_reflected = v - 2(v · n̂)n̂
     */
    public Vector3D reflect(Vector3D normal) {
        Vector3D n = normal.normalize();
        return this.subtract(n.scale(2.0 * this.dot(n)));
    }

    /**
     * Linear interpolation between two vectors.
     *
     * @param target target vector
     * @param t interpolation parameter [0, 1]
     * @return interpolated vector
     */
    public Vector3D lerp(Vector3D target, double t) {
        return new Vector3D(
            this.x + t * (target.x - this.x),
            this.y + t * (target.y - this.y),
            this.z + t * (target.z - this.z)
        );
    }

    /**
     * Angle between two vectors (radians).
     *
     * θ = arccos((a · b) / (|a| |b|))
     *
     * @param other vector to measure angle to
     * @return angle in radians [0, π]
     */
    public double angleTo(Vector3D other) {
        double lenProduct = this.length() * other.length();
        if (lenProduct < 1e-12) {
            return 0.0;
        }
        double cosAngle = this.dot(other) / lenProduct;
        // Clamp to valid range for acos
        cosAngle = Math.max(-1.0, Math.min(1.0, cosAngle));
        return Math.acos(cosAngle);
    }

    /**
     * Signed angle around an axis (radians).
     *
     * Uses atan2 for full [-π, π] range.
     *
     * @param other vector to measure angle to
     * @param axis rotation axis (must be normalized)
     * @return signed angle in radians [-π, π]
     */
    public double signedAngleTo(Vector3D other, Vector3D axis) {
        Vector3D cross = this.cross(other);
        double angle = Math.atan2(cross.length(), this.dot(other));

        // Determine sign from axis
        if (cross.dot(axis) < 0) {
            angle = -angle;
        }

        return angle;
    }

    /**
     * Clamp vector length to maximum value.
     *
     * CRITICAL for autopilot: limiting acceleration commands
     */
    public Vector3D clampLength(double maxLength) {
        double len = length();
        if (len > maxLength && len > 1e-12) {
            return scale(maxLength / len);
        }
        return this;
    }

    /**
     * Rotate vector by angle around axis (Rodrigues' rotation formula).
     *
     * v_rot = v cos(θ) + (k × v) sin(θ) + k(k · v)(1 - cos(θ))
     *
     * @param axis rotation axis (will be normalized)
     * @param angleRad rotation angle in radians
     * @return rotated vector
     */
    public Vector3D rotateAroundAxis(Vector3D axis, double angleRad) {
        Vector3D k = axis.normalize();
        double cosTheta = Math.cos(angleRad);
        double sinTheta = Math.sin(angleRad);

        return this.scale(cosTheta)
            .add(k.cross(this).scale(sinTheta))
            .add(k.scale(k.dot(this) * (1.0 - cosTheta)));
    }

    // ========== COORDINATE FRAME CONVERSIONS ==========

    /**
     * Convert from Minecraft coordinates to physics coordinates.
     *
     * Minecraft: Y is up, 1 block = 1 meter
     * Physics: Same convention (Y up, meters)
     *
     * @return vector in physics coordinates
     */
    public Vector3D toPhysics() {
        return this; // Same coordinate system
    }

    /**
     * Convert from physics coordinates to Minecraft coordinates.
     *
     * @return vector in Minecraft coordinates
     */
    public Vector3D toMinecraft() {
        return this; // Same coordinate system
    }

    /**
     * Convert velocity from m/s to blocks/tick.
     *
     * 1 tick = 1/20 second = 0.05 seconds
     * blocks/tick = (m/s) × 0.05
     */
    public Vector3D velocityToMinecraft() {
        return scale(0.05); // m/s to blocks/tick
    }

    /**
     * Convert velocity from blocks/tick to m/s.
     *
     * m/s = (blocks/tick) × 20
     */
    public Vector3D velocityFromMinecraft() {
        return scale(20.0); // blocks/tick to m/s
    }

    // ========== UTILITY METHODS ==========

    /**
     * Check if vector is approximately zero.
     */
    public boolean isZero() {
        return lengthSquared() < 1e-12;
    }

    /**
     * Check if vector is approximately equal to another (within epsilon).
     */
    public boolean isApproximately(Vector3D other, double epsilon) {
        return this.subtract(other).length() < epsilon;
    }

    /**
     * Get array representation [x, y, z].
     */
    public double[] toArray() {
        return new double[] { x, y, z };
    }

    /**
     * Convert to horizontal (XZ plane) vector (y = 0).
     */
    public Vector3D toHorizontal() {
        return new Vector3D(x, 0, z);
    }

    /**
     * Get horizontal (XZ) length.
     */
    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    @Override
    public String toString() {
        return String.format("Vector3D[x=%.6f, y=%.6f, z=%.6f]", x, y, z);
    }

    /**
     * Detailed string with magnitude and direction.
     */
    public String toDetailedString() {
        double mag = length();
        if (mag < 1e-12) {
            return "Vector3D[zero vector]";
        }
        Vector3D normalized = normalize();
        return String.format("Vector3D[mag=%.6f, dir=(%.6f, %.6f, %.6f)]",
            mag, normalized.x, normalized.y, normalized.z);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Vector3D)) return false;
        Vector3D other = (Vector3D) obj;
        return Double.compare(x, other.x) == 0 &&
               Double.compare(y, other.y) == 0 &&
               Double.compare(z, other.z) == 0;
    }

    @Override
    public int hashCode() {
        long bits = 1L;
        bits = 31L * bits + Double.doubleToLongBits(x);
        bits = 31L * bits + Double.doubleToLongBits(y);
        bits = 31L * bits + Double.doubleToLongBits(z);
        return (int) (bits ^ (bits >>> 32));
    }
}
