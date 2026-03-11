package com.hbm.physics.nuke;

import net.minecraft.util.math.Vec3d;

/**
 * REFLECTION POINT DATA STRUCTURE
 *
 * Stores information about a blast wave or thermal ray reflection point
 * when it encounters an impenetrable surface (bedrock).
 *
 * PHYSICAL MODEL:
 * When a blast wave hits a rigid surface at normal incidence,
 * the reflected overpressure is given by the Rankine-Hugoniot relation:
 *
 *   Pr = 2×Pi + (γ+1)×q
 *
 * where:
 *   Pr = reflected overpressure
 *   Pi = incident overpressure
 *   q = dynamic pressure (0.5×ρ×v²)
 *   γ = 1.4 (heat capacity ratio for air)
 *
 * For oblique incidence, the reflection is reduced by cos²(θ) where
 * θ is the angle between incident direction and surface normal.
 *
 * Reference: Glasstone & Dolan (1977) Chapter III, Section 3.73
 *
 * @author HBM Nuclear Physics Team
 */
public class ReflectionPoint {

	// === POSITION DATA ===

	/** Position of the reflection point in world coordinates */
	public Vec3d position;

	/** Surface normal vector at reflection point (unit length) */
	public Vec3d surfaceNormal;

	// === INCIDENT WAVE DATA ===

	/** Direction of incident wave (unit vector) */
	public Vec3d incidentDirection;

	/** Incident overpressure (PSI or Pa, depending on context) */
	public double incidentPressure;

	/** Incident dynamic pressure (PSI or Pa) */
	public double incidentDynamicPressure;

	/** Incident wave velocity (m/s) */
	public double incidentVelocity;

	// === REFLECTED WAVE DATA ===

	/** Direction of reflected wave (unit vector) */
	public Vec3d reflectedDirection;

	/** Reflected overpressure (PSI or Pa) */
	public double reflectedPressure;

	/** Reflected dynamic pressure (PSI or Pa) */
	public double reflectedDynamicPressure;

	/** Reflected wave velocity (m/s) */
	public double reflectedVelocity;

	// === REFLECTION COEFFICIENT ===

	/** Angle of incidence (radians, 0 = normal, π/2 = grazing) */
	public double incidenceAngle;

	/** Reflection coefficient (0.0 to 1.0, 1.0 = perfect reflection) */
	public double reflectionCoefficient;

	// === CONSTRUCTORS ===

	/**
	 * Default constructor
	 */
	public ReflectionPoint() {
		this.position = new Vec3d(0, 0, 0);
		this.surfaceNormal = new Vec3d(0, 1, 0);
		this.incidentDirection = new Vec3d(0, -1, 0);
		this.reflectedDirection = new Vec3d(0, 1, 0);
		this.reflectionCoefficient = 1.0;
	}

	/**
	 * Constructor with position and surface normal
	 *
	 * @param position The position of the reflection point
	 * @param surfaceNormal The surface normal vector (will be normalized)
	 */
	public ReflectionPoint(Vec3d position, Vec3d surfaceNormal) {
		this.position = position;
		this.surfaceNormal = surfaceNormal.normalize();
		// Initialize with default values to prevent null pointer exceptions
		this.incidentDirection = new Vec3d(0, -1, 0);
		this.reflectedDirection = this.surfaceNormal; // Default: reflect along normal
		this.reflectionCoefficient = 1.0;
	}

	/**
	 * Full constructor with all incident wave parameters
	 *
	 * @param position The position of the reflection point
	 * @param surfaceNormal The surface normal vector
	 * @param incidentDirection The incident wave direction
	 * @param incidentPressure The incident overpressure
	 * @param incidentDynamicPressure The incident dynamic pressure
	 * @param incidentVelocity The incident wave velocity
	 */
	public ReflectionPoint(Vec3d position, Vec3d surfaceNormal,
	                       Vec3d incidentDirection,
	                       double incidentPressure,
	                       double incidentDynamicPressure,
	                       double incidentVelocity) {
		this.position = position;
		this.surfaceNormal = surfaceNormal.normalize();
		this.incidentDirection = incidentDirection.normalize();
		this.incidentPressure = incidentPressure;
		this.incidentDynamicPressure = incidentDynamicPressure;
		this.incidentVelocity = incidentVelocity;

		// Calculate reflection properties
		calculateReflection();
	}

	// === REFLECTION CALCULATION ===

	/**
	 * Calculate reflected wave properties using Rankine-Hugoniot relations
	 *
	 * For a rigid boundary (bedrock), the reflection coefficient is high.
	 * The reflected pressure depends on the incident angle.
	 */
	public void calculateReflection() {
		// Calculate incidence angle (angle between incident direction and surface normal)
		double dotProduct = -incidentDirection.dotProduct(surfaceNormal);
		this.incidenceAngle = Math.acos(Math.max(-1.0, Math.min(1.0, dotProduct)));

		// Reflection coefficient varies with angle
		// Normal incidence (0°): Full reflection
		// Grazing incidence (90°): Minimal reflection
		this.reflectionCoefficient = Math.cos(incidenceAngle) * Math.cos(incidenceAngle);

		// Calculate reflected direction using Snell's law: r = d - 2(d·n)n
		Vec3d reflection = incidentDirection.subtract(
			surfaceNormal.scale(2.0 * dotProduct)
		);
		this.reflectedDirection = reflection.normalize();

		// Calculate reflected pressure using Rankine-Hugoniot relation
		// Pr = 2×Pi + (γ+1)×q
		// For air: γ = 1.4, so (γ+1) = 2.4
		double normalReflectedPressure = 2.0 * incidentPressure + 2.4 * incidentDynamicPressure;

		// Apply angular correction
		this.reflectedPressure = normalReflectedPressure * reflectionCoefficient;

		// Dynamic pressure scales with pressure
		this.reflectedDynamicPressure = incidentDynamicPressure * reflectionCoefficient;

		// Velocity reduces slightly due to energy loss (5% dissipation)
		this.reflectedVelocity = incidentVelocity * 0.95;
	}

	/**
	 * Check if this is a strong reflection (> 50% coefficient)
	 *
	 * @return True if reflection coefficient > 0.5
	 */
	public boolean isStrongReflection() {
		return reflectionCoefficient > 0.5;
	}

	/**
	 * Check if this is near-normal incidence (< 30°)
	 *
	 * @return True if incidence angle < 30 degrees
	 */
	public boolean isNearNormalIncidence() {
		return incidenceAngle < Math.toRadians(30.0);
	}

	/**
	 * Get a human-readable description of this reflection point
	 *
	 * @return Description string
	 */
	@Override
	public String toString() {
		return String.format("ReflectionPoint[pos=(%.1f, %.1f, %.1f), " +
				"angle=%.1f°, Pi=%.1f, Pr=%.1f, coeff=%.2f]",
			position.x, position.y, position.z,
			Math.toDegrees(incidenceAngle),
			incidentPressure,
			reflectedPressure,
			reflectionCoefficient);
	}

	// === UTILITY METHODS ===

	/**
	 * Calculate distance from this reflection point to a target position
	 *
	 * @param target The target position
	 * @return Distance in blocks/meters
	 */
	public double distanceTo(Vec3d target) {
		return position.distanceTo(target);
	}

	/**
	 * Calculate distance from this reflection point to target coordinates
	 *
	 * @param x Target X coordinate
	 * @param y Target Y coordinate
	 * @param z Target Z coordinate
	 * @return Distance in blocks/meters
	 */
	public double distanceTo(double x, double y, double z) {
		double dx = position.x - x;
		double dy = position.y - y;
		double dz = position.z - z;
		return Math.sqrt(dx*dx + dy*dy + dz*dz);
	}

	/**
	 * Check if a point is in the direction of the reflected wave
	 *
	 * @param x Target X coordinate
	 * @param y Target Y coordinate
	 * @param z Target Z coordinate
	 * @return True if target is roughly in reflection direction
	 */
	public boolean isInReflectionDirection(double x, double y, double z) {
		// Null safety: if reflection hasn't been calculated yet, assume no directionality
		if (reflectedDirection == null) {
			return false;
		}

		Vec3d toTarget = new Vec3d(
			x - position.x,
			y - position.y,
			z - position.z
		);

		// Check if toTarget is zero vector (at reflection point)
		if (toTarget.lengthSquared() < 0.001) {
			return true; // At reflection point, always affected
		}

		toTarget = toTarget.normalize();
		double alignment = toTarget.dotProduct(reflectedDirection);
		return alignment > 0.5; // Within ~60° cone
	}
}
