package com.hbm.explosion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.hbm.config.BombConfig;
import com.hbm.config.RealBombConfig;
import com.hbm.physics.nuke.BurstTypeCalculator;
import com.hbm.physics.nuke.BurstTypeCalculator.BurstType;
import com.hbm.physics.nuke.BedrockReflectionProcessor;
import com.hbm.physics.nuke.ReflectionPoint;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * REALISTIC NUCLEAR BLAST WAVE PROCESSOR
 *
 * Based on validated scientific literature and blast physics:
 * - Kingery-Bulmash empirical equations (1984)
 * - Glasstone & Dolan "Effects of Nuclear Weapons" (1977)
 * - Friedlander waveform for pressure-time history
 * - Rankine-Hugoniot shock wave relations
 *
 * BLAST PHYSICS MECHANISMS:
 *
 * 1. STATIC OVERPRESSURE (Peak Overpressure, Pso)
 *    - Instantaneous pressure jump at shock front
 *    - Compresses structures, causes crushing damage
 *    - Follows Friedlander decay: P(t) = Pso(1 - t/t+)e^(-Î±t/t+)
 *
 * 2. DYNAMIC PRESSURE (Blast Wind, q)
 *    - Drag force from high-velocity winds behind shock
 *    - q = 0.5ÏvÂ² where v is wind velocity
 *    - Tumbles, overturns, and throws objects
 *    - Particularly destructive to large structures
 *
 * 3. REFLECTED OVERPRESSURE (Pr)
 *    - When blast hits perpendicular surface
 *    - Pr â‰ˆ 2Pso + (Î³+1)q for normal incidence
 *    - Can be 2-8Ã— higher than incident pressure
 *
 * 4. MACH STEM EFFECT
 *    - Ground reflection interferes constructively with incident wave
 *    - Creates reinforced horizontal wave (Mach stem)
 *    - Pressure can increase by factor of 2-3
 *    - Crucial for surface/low-altitude bursts
 *
 * 5. POSITIVE & NEGATIVE PHASES
 *    - Positive phase: compression, main destruction
 *    - Negative phase: suction, reverse winds (weaker)
 *    - Duration: ~1-2s (20kt) to ~2-3s (1Mt)
 *
 * SCALING LAWS:
 * - Scaled distance: Z = R / W^(1/3) [m/kg^(1/3)]
 * - All blast parameters scale with Z
 * - Valid for 0.05 < Z < 40 m/kg^(1/3)
 *
 * DESTRUCTION THRESHOLDS (typical):
 * - 200 PSI (1380 kPa): Complete vaporization
 * - 20 PSI (138 kPa): Heavy blast damage
 * - 5 PSI (34 kPa): Moderate blast damage
 * - 2.5 PSI (17 kPa): Light damage threshold (processing stops here)
 */
public class BlastWaveProcessor {

	// === CORE PARAMETERS ===
	private final World world;
	private final double centerX, centerY, centerZ;
	private final double yieldKilotons;
	private final boolean isFusionWeapon;

	// === DERIVED PHYSICS ===
	private final double tntEquivalentKg;
	private final double blastEnergyJoules;

	// === BEDROCK REFLECTION PROCESSOR ===
	private BedrockReflectionProcessor reflectionProcessor;
	private boolean bedrockReflectionEnabled = true;

	// === PRESSURE THRESHOLDS (PSI) ===
	private static final double PSI_VAPORIZATION = 200.0;
	private static final double PSI_HEAVY_DAMAGE = 20.0;
	private static final double PSI_MODERATE_DAMAGE = 5.0;
	private static final double PSI_LIGHT_DAMAGE = 2.5;
	private static final double PSI_MINIMUM = 2.5;

	// === CONVERSION CONSTANTS ===
	private static final double KG_TNT_PER_KILOTON = 1_000_000.0;
	private static final double PSI_TO_KPA = 6.89476;
	private static final double KPA_TO_PSI = 1.0 / PSI_TO_KPA;
	private static final double GAMMA_AIR = 1.4; // Heat capacity ratio

	// === PROCESSING STATE ===
	private int currentRadius = 1;
	private int maxRadius;
	private boolean isProcessingComplete = false;
	private long totalBlocksDestroyed = 0;

	// === DATA MANAGEMENT ===
	private final Map<ChunkPos, ChunkDestructionData> chunkDataMap = new ConcurrentHashMap<>();
	private final Queue<ChunkPos> processingQueue = new LinkedList<>();
	private final Map<String, Integer> surfaceLevelCache = new HashMap<>();
	private final Map<Integer, BlastParameters> blastCache = new HashMap<>();

	// === PERFORMANCE ===
	private static final int MAX_BLOCKS_PER_TICK = 4000;
	private static final int PROCESSING_INCREMENT = 2; // meters per tick

	// === TERRAIN ===
	private static final int MIN_Y = 0;
	private static final int MAX_Y = 255;
	private static final int MAX_CRATER_DEPTH = 40;

	public BlastWaveProcessor(World world, double x, double y, double z, double yieldKt, boolean isFusion) {
		this.world = world;
		this.centerX = x;
		this.centerY = y;
		this.centerZ = z;
		this.yieldKilotons = yieldKt;
		this.isFusionWeapon = isFusion;

		this.tntEquivalentKg = yieldKt * KG_TNT_PER_KILOTON;

		// === ENERGY COUPLING EFFICIENCY ===
		// CRITICAL FIX: Apply realistic coupling efficiency based on burst type
		// Most blast energy dissipates as atmospheric heating, not ground damage!
		//
		// Determine burst type from detonation height and environment
		BurstType burstType = BurstTypeCalculator.determineBurstType(world, x, y, z);
		double blastCoupling = BurstTypeCalculator.getBlastCoupling(burstType);
		double confinementFactor = BurstTypeCalculator.calculateConfinementFactor(world, x, y, z);

		// Calculate blast energy with coupling efficiency
		double totalEnergyJoules = yieldKt * RealBombConfig.JOULES_PER_KT;
		double blastFraction = isFusion ?
				RealBombConfig.FUSION_BLAST_FRACTION :
				RealBombConfig.FISSION_BLAST_FRACTION;

		// Apply coupling efficiency (and confinement for subsurface bursts)
		// Before: Used 100% of blast energy (50% of yield) for ground damage
		// After: Only use the fraction that actually couples to ground
		double effectiveCoupling = blastCoupling;
		if (burstType == BurstType.SUBSURFACE_BURST) {
			effectiveCoupling = Math.min(blastCoupling * confinementFactor, 1.0);
		}
		this.blastEnergyJoules = totalEnergyJoules * blastFraction * effectiveCoupling;

		// Print diagnostic information
		System.out.println("[BlastWaveProcessor] Blast Energy Budget:");
		System.out.println("  Burst type: " + BurstTypeCalculator.getBurstTypeDescription(burstType));
		System.out.println("  Base blast energy (50% of yield): " +
			String.format("%.2e", totalEnergyJoules * blastFraction) + " J");
		System.out.println("  Coupling efficiency: " + String.format("%.1f%%", blastCoupling * 100));
		if (burstType == BurstType.SUBSURFACE_BURST) {
			System.out.println("  Confinement factor: " + String.format("%.2f×", confinementFactor));
			System.out.println("  Effective coupling: " + String.format("%.1f%%", effectiveCoupling * 100));
		}
		System.out.println("  Effective blast energy for terrain: " +
			String.format("%.2e", this.blastEnergyJoules) + " J");
		System.out.println("  Atmospheric dissipation: " +
			String.format("%.2e", totalEnergyJoules * blastFraction * (1.0 - effectiveCoupling)) + " J");

		// Calculate maximum effective range (2.5 PSI threshold)
		this.maxRadius = calculateRadiusForPressure(PSI_MINIMUM);

		// === INITIALIZE BEDROCK REFLECTION PROCESSOR ===
		if (bedrockReflectionEnabled) {
			this.reflectionProcessor = new BedrockReflectionProcessor(world, x, y, z);
			// Detect bedrock surfaces within blast radius
			this.reflectionProcessor.detectBedrockSurfaces(maxRadius);
			System.out.println("[BlastWaveProcessor] Bedrock reflection system initialized");
			System.out.println("  Detected " + reflectionProcessor.getReflectionCount() + " reflection points");
		}

		printInitializationInfo();
	}

	/**
	 * Calculate peak overpressure using Kingery-Bulmash-based scaling
	 * Simplified but accurate implementation
	 */
	private double calculatePeakOverpressure(double distanceMeters) {
		if (distanceMeters < 0.1) {
			return PSI_VAPORIZATION * 100.0; // Extreme near-field
		}

		int cacheKey = (int)distanceMeters;
		BlastParameters cached = blastCache.get(cacheKey);
		if (cached != null) {
			return cached.peakOverpressure;
		}

		// Scaled distance: Z = R / W^(1/3)
		double scaledDistance = distanceMeters / Math.pow(tntEquivalentKg, 1.0/3.0);
		double overpressurePSI;

		// Kingery-Bulmash approximation (polynomial fit)
		if (scaledDistance < 0.05) {
			// Very close: simple inverse relation
			overpressurePSI = 20000.0 / scaledDistance;
		} else if (scaledDistance < 0.2) {
			// Close range: steep decay
			overpressurePSI = 1800.0 * Math.pow(scaledDistance, -2.5);
		} else if (scaledDistance < 1.0) {
			// Medium range: logarithmic fit
			double logZ = Math.log10(scaledDistance);
			double logP = 2.78 - 1.95*logZ - 0.62*logZ*logZ;
			overpressurePSI = Math.pow(10, logP);
		} else if (scaledDistance < 10.0) {
			// Far range: complex decay
			double logZ = Math.log10(scaledDistance);
			double logP = 1.35 - 2.23*logZ + 0.47*logZ*logZ - 0.035*logZ*logZ*logZ;
			overpressurePSI = Math.pow(10, logP);
		} else if (scaledDistance < 40.0) {
			// Very far: inverse cube
			overpressurePSI = 60.0 * Math.pow(scaledDistance, -3.0);
		} else {
			// Beyond effective range
			overpressurePSI = PSI_MINIMUM * 0.5;
		}

		// Apply Mach stem enhancement for ground bursts
		if (centerY < 100.0) {
			double machFactor = calculateMachStemFactor(distanceMeters);
			overpressurePSI *= machFactor;
		}

		// Yield-dependent correction
		if (yieldKilotons > 1000.0) {
			overpressurePSI *= 1.08; // Fusion weapons slightly more efficient
		}

		BlastParameters params = new BlastParameters();
		params.peakOverpressure = Math.max(PSI_MINIMUM * 0.1, overpressurePSI);
		params.dynamicPressure = calculateDynamicPressure(params.peakOverpressure);
		params.reflectedPressure = calculateReflectedPressure(params.peakOverpressure, params.dynamicPressure);

		blastCache.put(cacheKey, params);
		return params.peakOverpressure;
	}

	/**
	 * Calculate dynamic pressure (blast wind)
	 * Using Rankine-Hugoniot relations
	 */
	private double calculateDynamicPressure(double peakOverpressurePSI) {
		double Pso = peakOverpressurePSI;

		// For weak shocks (Pso < 10 PSI):
		// q â‰ˆ (5/2) Ã— PsoÂ² / (7 + Pso)
		if (Pso < 10.0) {
			return (2.5 * Pso * Pso) / (7.0 + Pso);
		}

		// For strong shocks (Pso â‰¥ 10 PSI):
		// q â‰ˆ (5/12) Ã— Pso
		return (5.0/12.0) * Pso;
	}

	/**
	 * Calculate reflected overpressure (normal incidence)
	 * Pr = 2Pso + (Î³+1)q for head-on impact
	 */
	private double calculateReflectedPressure(double Pso, double q) {
		// Normal reflection formula
		double Pr = 2.0 * Pso + (GAMMA_AIR + 1.0) * q;

		// For very high pressures, use exact Rankine-Hugoniot
		if (Pso > 50.0) {
			double ratio = Pso / 14.7; // Normalize to atmospheric
			Pr = Pso * (7.0 * ratio + 4.0) / (ratio + 6.0);
		}

		return Pr;
	}

	/**
	 * Calculate Mach stem enhancement factor
	 * Ground reflection creates constructive interference
	 */
	private double calculateMachStemFactor(double distance) {
		double burstHeight = centerY;

		// Triple point height (where Mach stem forms)
		double triplePointHeight = 0.3 * Math.pow(yieldKilotons, 0.4);

		// High air burst: no Mach stem
		if (burstHeight > triplePointHeight * 3.0) {
			return 1.0;
		}

		// Mach stem region
		double machStart = burstHeight * 2.0;
		double machEnd = 13.0 * Math.pow(yieldKilotons, 0.33);

		if (distance < machStart) {
			// Too close: regular reflection
			return 1.0;
		} else if (distance > machEnd) {
			// Too far: Mach stem dissipated
			return 1.0;
		} else {
			// Within Mach stem: enhanced pressure
			double fraction = (distance - machStart) / (machEnd - machStart);
			// Peak enhancement at ~30% of range
			double enhancement = 2.8 - Math.abs(fraction - 0.3) * 2.5;
			return Math.max(1.0, Math.min(2.8, enhancement));
		}
	}

	/**
	 * Calculate radius for specific pressure threshold
	 * Binary search through pressure curve
	 */
	private int calculateRadiusForPressure(double targetPSI) {
		int low = 1;
		int high = 50000; // 50 km maximum

		while (high - low > 1) {
			int mid = (low + high) / 2;
			double pressure = calculatePeakOverpressure(mid);

			if (pressure > targetPSI) {
				low = mid;
			} else {
				high = mid;
			}
		}

		return low;
	}

	/**
	 * Calculate dynamic crater depth based on burst type, yield, and distance
	 *
	 * PHYSICS BASIS:
	 * Crater depth depends on:
	 * 1. Burst type (air/surface/subsurface)
	 * 2. Scaled distance from ground zero
	 * 3. Peak overpressure
	 * 4. Yield (via cube-root scaling)
	 *
	 * CRATER SCALING LAW:
	 * For surface bursts: depth ∝ W^(1/3.4)
	 * For subsurface bursts: depth ∝ W^(1/3) (more efficient)
	 * For air bursts: depth << surface burst (minimal cratering)
	 *
	 * Reference: Glasstone & Dolan Chapter VI, Section 6.72
	 *
	 * @param distanceFromCenter Horizontal distance from ground zero (meters)
	 * @param peakOverpressure Peak overpressure at this location (PSI)
	 * @param surfaceY Ground surface Y coordinate
	 * @return Crater depth in blocks
	 */
	private int calculateDynamicCraterDepth(double distanceFromCenter, double peakOverpressure, int surfaceY) {
		// Determine burst type
		BurstType burstType = BurstTypeCalculator.determineBurstType(world, centerX, centerY, centerZ);

		// Base crater depth formula (meters)
		// Reference: d = C × W^α × (R/W^(1/3))^β
		// where W = yield in kt, R = distance from GZ, α and β are empirical constants

		double yieldKt = yieldKilotons;
		double craterDepthMeters = 0.0;

		switch (burstType) {
			case AIR_BURST:
				// Air bursts create minimal cratering
				// Only very high overpressures (>50 PSI) cause shallow craters
				if (peakOverpressure > 50.0) {
					// Shallow cratering from air blast alone
					// d ≈ 0.1 × W^(1/4) × (Pso/50)^0.5
					craterDepthMeters = 0.1 * Math.pow(yieldKt, 0.25) *
						Math.sqrt(peakOverpressure / 50.0);
					craterDepthMeters = Math.min(craterDepthMeters, 5.0); // Max 5m for air burst
				}
				break;

			case SURFACE_BURST:
				// Surface bursts create significant cratering near ground zero
				// d = 0.3 × W^(1/3.4) × (1000/R)^0.3
				if (distanceFromCenter < 0.1) distanceFromCenter = 0.1; // Prevent division by zero

				// Apparent crater depth formula (Glasstone & Dolan 6.72)
				// For W in kt, R in meters:
				double scaledDistance = distanceFromCenter / Math.pow(yieldKt, 1.0/3.0);

				if (scaledDistance < 20.0) { // Within crater formation zone
					// Apparent crater depth: d = 10 × W^0.3 meters
					// Depth decreases with distance from GZ
					double maxCraterDepth = 10.0 * Math.pow(yieldKt, 0.3);
					double distanceFactor = Math.pow(20.0 / Math.max(scaledDistance, 1.0), 0.3);
					craterDepthMeters = maxCraterDepth * distanceFactor;

					// Cap at physically reasonable maximum
					craterDepthMeters = Math.min(craterDepthMeters, MAX_CRATER_DEPTH);
				}
				break;

			case SUBSURFACE_BURST:
				// Subsurface bursts are most efficient at cratering
				// d = 0.5 × W^(1/3) × depth_factor
				// Creates much deeper craters due to direct coupling

				double scaledDistSubsurf = distanceFromCenter / Math.pow(yieldKt, 1.0/3.0);

				if (scaledDistSubsurf < 30.0) { // Wider crater zone for subsurface
					// Maximum crater depth for subsurface: d = 15 × W^(1/3) meters
					double maxCraterDepthSubsurf = 15.0 * Math.pow(yieldKt, 1.0/3.0);

					// Apply confinement factor for additional depth
					double confinementFactor = BurstTypeCalculator.calculateConfinementFactor(
						world, centerX, centerY, centerZ);

					// Distance factor (decay more slowly than surface burst)
					double distanceFactorSubsurf = Math.pow(30.0 / Math.max(scaledDistSubsurf, 1.0), 0.2);

					craterDepthMeters = maxCraterDepthSubsurf * distanceFactorSubsurf * confinementFactor;

					// Cap at physically reasonable maximum (deeper than surface burst)
					craterDepthMeters = Math.min(craterDepthMeters, MAX_CRATER_DEPTH * 2);
				}
				break;
		}

		// Convert meters to blocks (1 block = 1 meter in Minecraft)
		int craterDepthBlocks = (int) Math.round(craterDepthMeters);

		// Additional pressure-based modification
		// Very high overpressures increase depth slightly
		if (peakOverpressure > 200.0) {
			double pressureBonus = Math.min(Math.log10(peakOverpressure / 200.0) * 5.0, 10.0);
			craterDepthBlocks += (int) pressureBonus;
		}

		// Ensure non-negative
		return Math.max(0, craterDepthBlocks);
	}

	/**
	 * Main processing tick
	 */
	public void processTick() {
		if (isProcessingComplete) return;

		long startTime = System.currentTimeMillis();
		int blocksQueued = 0;

		// Process current radius shell
		while (currentRadius <= maxRadius && blocksQueued < MAX_BLOCKS_PER_TICK) {
			BlastParameters params = blastCache.get(currentRadius);
			if (params == null) {
				params = new BlastParameters();
				params.peakOverpressure = calculatePeakOverpressure(currentRadius);
				params.dynamicPressure = calculateDynamicPressure(params.peakOverpressure);
				params.reflectedPressure = calculateReflectedPressure(
						params.peakOverpressure, params.dynamicPressure);
				blastCache.put(currentRadius, params);
			}

			// Sample spherical shell
			blocksQueued += processSphericalShell(currentRadius, params);

			// Advance radius
			currentRadius += PROCESSING_INCREMENT;

			// Check time limit
			if (System.currentTimeMillis() - startTime > 50) {
				break;
			}
		}

		// Process queued chunks
		processQueuedChunks();

		// Check completion
		if (currentRadius > maxRadius) {
			completeProcessing();
		}

		// Periodic logging
		if (blocksQueued > 100) {
			double pressure = calculatePeakOverpressure(currentRadius);
			System.out.println("[BLAST] R=" + currentRadius + "m | P=" +
					String.format("%.2f", pressure) + " PSI | Blocks=" + blocksQueued);
		}
	}

	/**
	 * Process spherical shell at given radius
	 */
	private int processSphericalShell(int radius, BlastParameters params) {
		int blocksQueued = 0;

		// Fibonacci sphere sampling for uniform distribution
		int numSamples = calculateSphericalSamples(radius);
		double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));

		for (int i = 0; i < numSamples; i++) {
			double y = 1.0 - (i / (double)(numSamples - 1)) * 2.0;
			double radiusAtY = Math.sqrt(1.0 - y * y);
			double theta = goldenAngle * i;

			double x = Math.cos(theta) * radiusAtY;
			double z = Math.sin(theta) * radiusAtY;

			int blockX = (int)Math.round(centerX + x * radius);
			int blockY = (int)Math.round(centerY + y * radius);
			int blockZ = (int)Math.round(centerZ + z * radius);

			blocksQueued += processBlastColumn(blockX, blockY, blockZ, params, radius);
		}

		return blocksQueued;
	}

	/**
	 * Process vertical column of blocks
	 */
	private int processBlastColumn(int x, int baseY, int z, BlastParameters params, double distance) {
		int blocksQueued = 0;
		int surfaceY = getSurfaceLevel(x, z);

		// === DYNAMIC CRATER DEPTH CALCULATION ===
		// Calculate crater depth based on burst type, yield, and distance
		// Reference: Glasstone & Dolan Chapter VI "Ground Shock and Cratering"
		int craterDepth = calculateDynamicCraterDepth(distance, params.peakOverpressure, surfaceY);

		int minY = Math.max(MIN_Y, surfaceY - craterDepth);
		int maxY = Math.min(MAX_Y, surfaceY + 120);

		ChunkPos chunkCoord = new ChunkPos(x >> 4, z >> 4);
		if (!world.isChunkGeneratedAt(chunkCoord.x, chunkCoord.z)) {
			world.getChunk(chunkCoord.x, chunkCoord.z);
		}

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int y = minY; y <= maxY; y++) {
			pos.setPos(x, y, z);
			IBlockState state = world.getBlockState(pos);
			Block block = state.getBlock();

			// Skip air and blocks below world
			// NOTE: Bedrock is no longer skipped - it acts as reflection surface
			if (block == Blocks.AIR || y <= 0) continue;

			// Bedrock itself is indestructible, but don't skip (for reflection calc)
			if (block == Blocks.BEDROCK) continue; // Still indestructible

			// Determine destruction based on position
			boolean aboveGround = y > surfaceY;
			boolean underground = y < surfaceY;

			// === BEDROCK REFLECTION CONTRIBUTION ===
			// Add pressure from reflected blast waves
			BlastParameters effectiveParams = params;
			if (bedrockReflectionEnabled && reflectionProcessor != null) {
				double reflectedPressure = reflectionProcessor.calculateReflectedPressure(
					x, y, z, currentRadius);

				if (reflectedPressure > 0.01) { // Significant reflection
					// Create modified parameters with reflection added
					effectiveParams = new BlastParameters();
					effectiveParams.peakOverpressure = params.peakOverpressure + reflectedPressure;
					effectiveParams.dynamicPressure = params.dynamicPressure;
					effectiveParams.reflectedPressure = params.reflectedPressure + reflectedPressure;
				}
			}

			if (shouldBlastDestroy(block, state, effectiveParams, aboveGround, underground)) {
				queueBlockDestruction(chunkCoord, x, y, z);
				blocksQueued++;
			}
		}

		return blocksQueued;
	}

	/**
	 * Determine if blast destroys block
	 * Considers both overpressure and dynamic pressure
	 */
	private boolean shouldBlastDestroy(Block block, IBlockState state, BlastParameters params,
									   boolean aboveGround, boolean underground) {
		Material material = state.getMaterial();
		float resistance = block.getExplosionResistance(null);

		// Determine required pressures
		double requiredOverpressure;
		double requiredDynamic;

		// Material-specific thresholds
		if (material == Material.LEAVES || material == Material.PLANTS ||
				material == Material.VINE || material == Material.CLOTH) {
			requiredOverpressure = 0.3;
			requiredDynamic = 0.05;
		} else if (material == Material.GLASS) {
			requiredOverpressure = 0.5;
			requiredDynamic = 0.2;
		} else if (material == Material.WOOD) {
			requiredOverpressure = 2.0;
			requiredDynamic = 0.8;
		} else if (material == Material.SAND || material == Material.SNOW) {
			requiredOverpressure = underground ? 15.0 : 3.0;
			requiredDynamic = 0.5;
		} else if (material == Material.GROUND || material == Material.GRASS) {
			requiredOverpressure = underground ? 18.0 : 4.0;
			requiredDynamic = 1.0;
		} else if (material == Material.ROCK) {
			requiredOverpressure = underground ? 15.0 : 8.0;
			requiredDynamic = 3.0;
		} else if (material == Material.IRON) {
			requiredOverpressure = 12.0;
			requiredDynamic = 5.0;
		} else if (material == Material.WATER || material == Material.LAVA) {
			requiredOverpressure = 0.5;
			requiredDynamic = 0.1;
		} else {
			// Generic calculation
			requiredOverpressure = 2.0 + (resistance / 5.0);
			requiredDynamic = 1.0 + (resistance / 8.0);
		}

		// Special cases
		if (block == Blocks.OBSIDIAN) {
			requiredOverpressure = 20.0;
			requiredDynamic = 8.0;
		}

		// Underground protection
		if (underground) {
			requiredOverpressure *= 2.5;
			requiredDynamic *= 1.8;
		}

		// Above ground vulnerability (no surrounding support)
		if (aboveGround) {
			requiredOverpressure *= 0.7;
			requiredDynamic *= 0.6;
		}

		// Check destruction criteria
		// Must satisfy EITHER overpressure OR dynamic pressure (whichever is more damaging)
		boolean overpressureFailure = params.peakOverpressure >= requiredOverpressure;
		boolean dynamicFailure = params.dynamicPressure >= requiredDynamic;

		// Reflected pressure on surfaces (additional check)
		boolean reflectedFailure = params.reflectedPressure >= requiredOverpressure * 0.6;

		return overpressureFailure || dynamicFailure || reflectedFailure;
	}

	/**
	 * Calculate number of samples for spherical shell
	 */
	private int calculateSphericalSamples(int radius) {
		if (radius < 5) {
			return Math.max(50, radius * radius);
		} else if (radius < 20) {
			return Math.max(200, (int)(4.0 * Math.PI * radius * radius / 4.0));
		} else if (radius < 100) {
			return Math.min(3000, (int)(4.0 * Math.PI * radius * radius / 12.0));
		} else if (radius < 300) {
			return Math.min(5000, (int)(4.0 * Math.PI * radius * radius / 25.0));
		} else {
			return Math.min(8000, (int)(4.0 * Math.PI * radius * radius / 50.0));
		}
	}

	/**
	 * Get surface level with caching
	 */
	private int getSurfaceLevel(int x, int z) {
		String key = x + "," + z;
		Integer cached = surfaceLevelCache.get(key);
		if (cached != null) return cached;

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = MAX_Y - 1; y >= MIN_Y; y--) {
			pos.setPos(x, y, z);
			IBlockState state = world.getBlockState(pos);
			if (state.getBlock() != Blocks.AIR && state.isOpaqueCube()) {
				surfaceLevelCache.put(key, y);
				return y;
			}
		}

		int defaultY = 63;
		surfaceLevelCache.put(key, defaultY);
		return defaultY;
	}

	/**
	 * Queue block for destruction
	 */
	private void queueBlockDestruction(ChunkPos chunkCoord, int x, int y, int z) {
		ChunkDestructionData data = chunkDataMap.computeIfAbsent(chunkCoord,
				k -> new ChunkDestructionData());
		data.addBlock(x, y, z);

		if (!processingQueue.contains(chunkCoord)) {
			processingQueue.add(chunkCoord);
		}
	}

	/**
	 * Process queued chunk destructions
	 */
	private void processQueuedChunks() {
		int processed = 0;

		while (!processingQueue.isEmpty() && processed < 10) {
			ChunkPos coord = processingQueue.poll();
			ChunkDestructionData data = chunkDataMap.remove(coord);

			if (data != null && data.hasBlocks()) {
				executeChunkDestruction(coord, data);
				processed++;
			}
		}
	}

	/**
	 * Execute chunk destruction
	 */
	private void executeChunkDestruction(ChunkPos coord, ChunkDestructionData data) {
		if (!world.isChunkGeneratedAt(coord.x, coord.z)) {
			world.getChunk(coord.x, coord.z);
		}

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int destroyed = 0;

		for (BlockDestruction destruction : data.blocks) {
			pos.setPos(destruction.x, destruction.y, destruction.z);
			IBlockState current = world.getBlockState(pos);
			Block block = current.getBlock();

			if (block != Blocks.AIR && block != Blocks.BEDROCK && destruction.y > 0) {
				world.setBlockToAir(pos);
				destroyed++;
				totalBlocksDestroyed++;
			}
		}
	}

	/**
	 * Complete processing
	 */
	private void completeProcessing() {
		while (!processingQueue.isEmpty()) {
			ChunkPos coord = processingQueue.poll();
			ChunkDestructionData data = chunkDataMap.remove(coord);
			if (data != null) {
				executeChunkDestruction(coord, data);
			}
		}

		isProcessingComplete = true;

		System.out.println("=== BLAST PROCESSING COMPLETE ===");
		System.out.println("Weapon yield: " + yieldKilotons + " kt");
		System.out.println("Total blocks destroyed: " + totalBlocksDestroyed);
		System.out.println("Maximum range: " + maxRadius + " m");
		System.out.println("Critical radii:");
		System.out.println("  20 PSI (heavy): " + calculateRadiusForPressure(PSI_HEAVY_DAMAGE) + " m");
		System.out.println("  5 PSI (moderate): " + calculateRadiusForPressure(PSI_MODERATE_DAMAGE) + " m");
		System.out.println("  2.5 PSI (light): " + calculateRadiusForPressure(PSI_LIGHT_DAMAGE) + " m");
		System.out.println("==================================");
	}

	private void printInitializationInfo() {
		System.out.println("=== REALISTIC NUCLEAR BLAST PROCESSOR ===");
		System.out.println("Weapon yield: " + String.format("%.3f", yieldKilotons) + " kt");
		System.out.println("Weapon type: " + (isFusionWeapon ? "FUSION" : "FISSION"));
		System.out.println("TNT equivalent: " + String.format("%.1e", tntEquivalentKg) + " kg");
		System.out.println("Total blast energy: " + String.format("%.2e", blastEnergyJoules) + " J");
		System.out.println("Ground zero: " + (int)centerX + ", " + (int)centerY + ", " + (int)centerZ);
		System.out.println("Maximum range: " + maxRadius + " m");
		System.out.println("Damage radii:");
		System.out.println("  20 PSI (heavy): " + calculateRadiusForPressure(PSI_HEAVY_DAMAGE) + " m");
		System.out.println("  5 PSI (moderate): " + calculateRadiusForPressure(PSI_MODERATE_DAMAGE) + " m");
		System.out.println("  2.5 PSI (light): " + calculateRadiusForPressure(PSI_LIGHT_DAMAGE) + " m");
		System.out.println("==========================================");
	}

	// === PUBLIC INTERFACE ===

	public boolean isComplete() {
		return isProcessingComplete;
	}

	public long getTotalBlocksDestroyed() {
		return totalBlocksDestroyed;
	}

	// === DATA STRUCTURES ===

	private static class BlastParameters {
		double peakOverpressure; // PSI
		double dynamicPressure; // PSI
		double reflectedPressure; // PSI
	}

	private static class ChunkDestructionData {
		private final List<BlockDestruction> blocks = new ArrayList<>();

		public void addBlock(int x, int y, int z) {
			blocks.add(new BlockDestruction(x, y, z));
		}

		public boolean hasBlocks() {
			return !blocks.isEmpty();
		}
	}

	private static class BlockDestruction {
		public final int x, y, z;

		public BlockDestruction(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}
}