package com.hbm.explosion;

/**
 * DESTRUCTION TYPE ENUM
 *
 * Defines the different types of destruction mechanisms and their priorities.
 * Higher priority effects override lower priority effects when multiple
 * destruction sources affect the same block.
 *
 * PRIORITY SYSTEM:
 * - FIREBALL (3): Complete vaporization, creates deep crater
 * - THERMAL (2): Surface scorching/melting, shallow erosion
 * - BLAST (1): Structural damage, surface destruction only
 */
public enum DestructionType {

	/**
	 * BLAST: Pressure wave structural damage
	 * - Destroys buildings and structures
	 * - Surface effects only (no deep excavation)
	 * - Affects largest radius but weakest penetration
	 * - Priority: 1 (lowest)
	 */
	BLAST(1),

	/**
	 * THERMAL: Thermal radiation heating/melting
	 * - Surface scorching and ablation
	 * - Shallow penetration (few meters)
	 * - Medium range, medium penetration
	 * - Priority: 2 (medium)
	 */
	THERMAL(2),

	/**
	 * FIREBALL: Direct fireball contact vaporization
	 * - Complete material vaporization
	 * - Deep crater excavation
	 * - Smallest radius but strongest effect
	 * - Priority: 3 (highest)
	 */
	FIREBALL(3);

	private final int priority;

	DestructionType(int priority) {
		this.priority = priority;
	}

	/**
	 * Get priority level (higher = takes precedence)
	 */
	public int getPriority() {
		return priority;
	}

	/**
	 * Check if this type has higher priority than another
	 */
	public boolean hasHigherPriorityThan(DestructionType other) {
		return this.priority > other.priority;
	}
}
