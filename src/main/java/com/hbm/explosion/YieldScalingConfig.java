package com.hbm.explosion;

/**
 * YIELD-BASED PERFORMANCE SCALING
 *
 * Automatically adjusts processing parameters based on weapon yield
 * to prevent freezing and memory overflow for large detonations.
 *
 * SCALING PHILOSOPHY:
 * - Small yields (<100kt): Full detail, block-by-block processing
 * - Medium yields (100kt-1MT): Moderate detail, reduced sampling
 * - Large yields (1MT-50MT): Simplified processing, chunk-based
 * - Mega yields (>50MT): Aggressive optimization, statistical sampling
 *
 * USER REQUIREMENT:
 * "威力が大きいほど処理をゆっくりにする" (Slower processing for larger yields)
 */
public class YieldScalingConfig {

	// === YIELD CATEGORIES ===
	public enum YieldCategory {
		TACTICAL(0, 100, "Tactical", 1.0),           // <100kt: Full detail
		STRATEGIC(100, 1000, "Strategic", 0.5),      // 100kt-1MT: Moderate
		MEGATON(1000, 50000, "Megaton", 0.1),        // 1MT-50MT: Simplified
		TSAR(50000, Double.MAX_VALUE, "Tsar", 0.02); // >50MT: Ultra-optimized

		public final double minYieldKt;
		public final double maxYieldKt;
		public final String name;
		public final double samplingRate; // % of blocks to actually calculate

		YieldCategory(double minYieldKt, double maxYieldKt, String name, double samplingRate) {
			this.minYieldKt = minYieldKt;
			this.maxYieldKt = maxYieldKt;
			this.name = name;
			this.samplingRate = samplingRate;
		}
	}

	private final double yieldKilotons;
	private final YieldCategory category;

	// === PERFORMANCE PARAMETERS ===
	private final int blocksPerTick;
	private final double samplingRate;
	private final int maxRangeMeters;
	private final boolean useChunkBased;

	public YieldScalingConfig(double yieldKilotons) {
		this.yieldKilotons = yieldKilotons;
		this.category = determineCategory(yieldKilotons);

		// Calculate scaled parameters
		this.blocksPerTick = calculateBlocksPerTick();
		this.samplingRate = category.samplingRate;
		this.maxRangeMeters = calculateMaxRange();
		this.useChunkBased = (yieldKilotons >= 1000); // 1MT+ uses chunk-based
	}

	/**
	 * Determine yield category
	 */
	private YieldCategory determineCategory(double yieldKt) {
		for (YieldCategory cat : YieldCategory.values()) {
			if (yieldKt >= cat.minYieldKt && yieldKt < cat.maxYieldKt) {
				return cat;
			}
		}
		return YieldCategory.TSAR;
	}

	/**
	 * Calculate blocks per tick (slower for larger yields)
	 *
	 * USER REQUIREMENT: "威力が大きいほど処理をゆっくりにする"
	 */
	private int calculateBlocksPerTick() {
		double baseRate = 15000.0; // 15k blocks/tick for tactical

		// Scale down for larger yields
		if (yieldKilotons < 100) {
			// Tactical: Full speed
			return 15000;
		} else if (yieldKilotons < 1000) {
			// Strategic: 100kt = 7500, 1MT = 1500
			double scaleFactor = 1.0 - Math.log10(yieldKilotons / 100.0);
			return (int) (baseRate * Math.max(0.1, scaleFactor));
		} else if (yieldKilotons < 50000) {
			// Megaton: 1MT = 1000, 50MT = 300
			double scaleFactor = 1000.0 / yieldKilotons;
			return (int) (baseRate * Math.max(0.02, scaleFactor));
		} else {
			// Tsar: 100MT = 150 blocks/tick
			return 150;
		}
	}

	/**
	 * Calculate maximum processing range (prevent infinite grids)
	 *
	 * SCALING PHILOSOPHY:
	 * - <100kt: Fixed 1000m cap (tactical weapons)
	 * - >=100kt: Dynamic scaling proportional to yield using W^0.3 law
	 *
	 * Physical basis: Blast overpressure range ∝ W^(1/3) from Kingery-Bulmash
	 */
	private int calculateMaxRange() {
		if (yieldKilotons < 100) {
			// Tactical weapons: Fixed 1km range
			return 1000;
		} else {
			// Strategic/Megaton weapons: Dynamic scaling proportional to yield
			// Base: 100kt = 2000m
			// Formula: R = 2000 × (W / 100)^0.3
			double scaleFactor = Math.pow(yieldKilotons / 100.0, 0.3);
			int range = (int) (2000.0 * scaleFactor);

			// Safety cap at 20km to prevent memory overflow
			// (50MT Tsar Bomba → ~16km, 100MT → ~20km)
			return Math.min(range, 20000);
		}
	}

	/**
	 * Should we sample this block? (statistical sampling for large yields)
	 */
	public boolean shouldSampleBlock(int x, int y, int z, double centerX, double centerY, double centerZ) {
		if (samplingRate >= 1.0) {
			return true; // Always sample for small yields
		}

		// Deterministic sampling based on position hash
		long hash = ((long) x * 73856093) ^ ((long) y * 19349663) ^ ((long) z * 83492791);
		double rand = ((hash & 0xFFFFFF) / (double) 0xFFFFFF);

		return rand < samplingRate;
	}

	// === GETTERS ===

	public double getYieldKilotons() {
		return yieldKilotons;
	}

	public YieldCategory getCategory() {
		return category;
	}

	public int getBlocksPerTick() {
		return blocksPerTick;
	}

	public double getSamplingRate() {
		return samplingRate;
	}

	public int getMaxRangeMeters() {
		return maxRangeMeters;
	}

	public boolean isChunkBased() {
		return useChunkBased;
	}

	/**
	 * Print scaling info to console
	 */
	public void printScalingInfo() {
		System.out.println("=== YIELD-BASED SCALING ===");
		System.out.println("Weapon yield: " + String.format("%.1f", yieldKilotons) + " kt");
		System.out.println("Category: " + category.name);
		System.out.println("Performance settings:");
		System.out.println("  Blocks per tick: " + blocksPerTick);
		System.out.println("  Sampling rate: " + String.format("%.1f%%", samplingRate * 100));
		System.out.println("  Max range: " + maxRangeMeters + " m");
		System.out.println("  Processing mode: " + (useChunkBased ? "Chunk-based" : "Block-based"));
		System.out.println("===========================");
	}
}
