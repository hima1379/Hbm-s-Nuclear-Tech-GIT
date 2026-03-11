package com.hbm.explosion;

import java.util.HashSet;
import java.util.Set;

import com.hbm.config.RealBombConfig;

/**
 * ENERGY BUDGET TRACKER FOR REALISTIC NUCLEAR EXPLOSIONS
 *
 * This class implements finite energy conservation for nuclear explosions,
 * preventing unrealistic infinite destruction.
 *
 * PROBLEM SOLVED:
 * - Previous implementation: Blast and thermal systems independently destroyed blocks
 * - Result: ~200,000 blocks destroyed for 15kt (2× too many)
 *
 * SOLUTION:
 * - Finite energy pools based on yield (thermal: 35%, blast: 50%)
 * - Each block destruction consumes energy
 * - Destruction stops when budget exhausted
 * - Prevents double-destruction (same block by both systems)
 *
 * SCIENTIFIC BASIS:
 * - Glasstone & Dolan (1977): "The Effects of Nuclear Weapons"
 * - Energy distribution: 35% thermal, 50% blast, 5% radiation, 10% fallout
 *
 * EXAMPLE (15kt explosion):
 * - Total energy: 6.276 × 10^13 J
 * - Thermal pool: 2.196 × 10^13 J (35%)
 * - Blast pool: 3.138 × 10^13 J (50%)
 * - Expected destruction: ~80,000-120,000 blocks
 */
public class EnergyBudgetTracker {

	// === ENERGY POOLS ===
	private final double initialThermalEnergy;
	private final double initialBlastEnergy;
	private double thermalEnergyRemaining;
	private double blastEnergyRemaining;

	// === TRACKING ===
	private final Set<Long> destroyedBlockCoords;
	private int blocksDestroyedThermal = 0;
	private int blocksDestroyedBlast = 0;

	// === STATISTICS ===
	private double totalThermalEnergyConsumed = 0;
	private double totalBlastEnergyConsumed = 0;

	/**
	 * Initialize energy budget for nuclear explosion
	 *
	 * @param yieldKilotons Weapon yield in kilotons
	 */
	public EnergyBudgetTracker(double yieldKilotons) {
		double totalEnergy = yieldKilotons * RealBombConfig.JOULES_PER_KT;

		// Calculate energy pools based on physics
		this.initialThermalEnergy = totalEnergy * RealBombConfig.THERMAL_FRACTION;
		this.initialBlastEnergy = totalEnergy * RealBombConfig.BLAST_FRACTION;

		// Initialize remaining energy
		this.thermalEnergyRemaining = initialThermalEnergy;
		this.blastEnergyRemaining = initialBlastEnergy;

		// Initialize tracking set
		this.destroyedBlockCoords = new HashSet<>();

		System.out.println("=== ENERGY BUDGET INITIALIZED ===");
		System.out.println("Yield: " + String.format("%.1f", yieldKilotons) + " kt");
		System.out.println("Total energy: " + String.format("%.2e", totalEnergy) + " J");
		System.out.println("Thermal pool: " + String.format("%.2e", initialThermalEnergy) + " J (35%)");
		System.out.println("Blast pool: " + String.format("%.2e", initialBlastEnergy) + " J (50%)");
		System.out.println("==================================");
	}

	/**
	 * Attempt to consume thermal energy for block destruction
	 *
	 * @param blockKey Packed coordinate key (prevents double-destruction)
	 * @param energyCost Energy required to destroy block (Joules)
	 * @return true if energy was consumed, false if budget exhausted or already destroyed
	 */
	public boolean consumeThermalEnergy(long blockKey, double energyCost) {
		// Check if already destroyed by either system
		if (destroyedBlockCoords.contains(blockKey)) {
			return false; // Already destroyed, don't count again
		}

		// Check if sufficient energy remains
		if (thermalEnergyRemaining < energyCost) {
			return false; // Budget exhausted
		}

		// Consume energy
		thermalEnergyRemaining -= energyCost;
		totalThermalEnergyConsumed += energyCost;
		destroyedBlockCoords.add(blockKey);
		blocksDestroyedThermal++;

		return true;
	}

	/**
	 * Attempt to consume blast energy for block destruction
	 *
	 * @param blockKey Packed coordinate key (prevents double-destruction)
	 * @param energyCost Energy required to destroy block (Joules)
	 * @return true if energy was consumed, false if budget exhausted or already destroyed
	 */
	public boolean consumeBlastEnergy(long blockKey, double energyCost) {
		// Check if already destroyed by either system
		if (destroyedBlockCoords.contains(blockKey)) {
			return false; // Already destroyed, don't count again
		}

		// Check if sufficient energy remains
		if (blastEnergyRemaining < energyCost) {
			return false; // Budget exhausted
		}

		// Consume energy
		blastEnergyRemaining -= energyCost;
		totalBlastEnergyConsumed += energyCost;
		destroyedBlockCoords.add(blockKey);
		blocksDestroyedBlast++;

		return true;
	}

	/**
	 * Check if thermal energy budget is exhausted
	 */
	public boolean isThermalBudgetExhausted() {
		return thermalEnergyRemaining <= 0;
	}

	/**
	 * Check if blast energy budget is exhausted
	 */
	public boolean isBlastBudgetExhausted() {
		return blastEnergyRemaining <= 0;
	}

	/**
	 * Check if a block has already been destroyed
	 */
	public boolean isBlockDestroyed(long blockKey) {
		return destroyedBlockCoords.contains(blockKey);
	}

	// === GETTERS FOR STATISTICS ===

	public double getInitialThermalEnergy() {
		return initialThermalEnergy;
	}

	public double getInitialBlastEnergy() {
		return initialBlastEnergy;
	}

	public double getThermalEnergyRemaining() {
		return thermalEnergyRemaining;
	}

	public double getBlastEnergyRemaining() {
		return blastEnergyRemaining;
	}

	public double getThermalEnergyFraction() {
		return thermalEnergyRemaining / initialThermalEnergy;
	}

	public double getBlastEnergyFraction() {
		return blastEnergyRemaining / initialBlastEnergy;
	}

	public int getBlocksDestroyedThermal() {
		return blocksDestroyedThermal;
	}

	public int getBlocksDestroyedBlast() {
		return blocksDestroyedBlast;
	}

	public int getTotalBlocksDestroyed() {
		return destroyedBlockCoords.size();
	}

	public double getTotalThermalEnergyConsumed() {
		return totalThermalEnergyConsumed;
	}

	public double getTotalBlastEnergyConsumed() {
		return totalBlastEnergyConsumed;
	}

	/**
	 * Print final statistics
	 */
	public void printFinalStatistics() {
		System.out.println("=== ENERGY BUDGET FINAL STATISTICS ===");
		System.out.println("Total blocks destroyed: " + getTotalBlocksDestroyed());
		System.out.println();
		System.out.println("THERMAL SYSTEM:");
		System.out.println("  Blocks destroyed: " + blocksDestroyedThermal);
		System.out.println("  Energy consumed: " + String.format("%.2e", totalThermalEnergyConsumed) + " J");
		System.out.println("  Energy remaining: " + String.format("%.2e", thermalEnergyRemaining) + " J");
		System.out.println("  Efficiency: " + String.format("%.1f%%",
			(totalThermalEnergyConsumed / initialThermalEnergy) * 100));
		System.out.println();
		System.out.println("BLAST SYSTEM:");
		System.out.println("  Blocks destroyed: " + blocksDestroyedBlast);
		System.out.println("  Energy consumed: " + String.format("%.2e", totalBlastEnergyConsumed) + " J");
		System.out.println("  Energy remaining: " + String.format("%.2e", blastEnergyRemaining) + " J");
		System.out.println("  Efficiency: " + String.format("%.1f%%",
			(totalBlastEnergyConsumed / initialBlastEnergy) * 100));
		System.out.println("======================================");
	}
}
