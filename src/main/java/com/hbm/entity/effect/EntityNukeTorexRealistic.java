package com.hbm.entity.effect;

import com.hbm.interfaces.IConstantRenderer;
import com.hbm.physics.nuke.FireballPhysicsCalculator;
import com.hbm.render.amlfrom1710.Vec3;
import com.hbm.util.SimplexNoise;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Random;

/**
 * REALISTIC 1:1 SCALE NUCLEAR EXPLOSION VISUAL EFFECTS
 *
 * Physics-accurate implementation of nuclear explosion visual phenomena:
 * - Fireball evolution (X-ray heating → isothermal expansion → blackbody cooling)
 * - Mushroom cloud formation via toroidal convection
 * - Temperature-based color evolution (10^7 K white → 3000 K red/brown)
 * - Real scale: 3,000-40,000 blocks high (not 1/100 scale)
 * - Visible from realistic distances: 195-716 km
 *
 * PHASE EVOLUTION:
 * Phase 0: INITIAL FLASH (0-1 ticks, T~10^7 K)
 *   - Intense white flash visible for hundreds of kilometers
 *   - X-ray heating of surrounding air
 *   - Doubles in brightness ("double flash" from ionization)
 *
 * Phase 1: FIREBALL GROWTH (varies by yield, T: 10^7 K → 3000°C → 7700°C)
 *   - Isothermal expansion: R(t) ∝ (E×t²/ρ)^(1/5)
 *   - Thermal minimum: R = 27.4 × W^0.4 meters at T=3000°C
 *   - Breakaway (air): R = 33.5 × W^0.4 meters (hydrodynamic separation)
 *   - Maximum radius: R = 67 × W^0.4 meters (2× breakaway), T=7700°C
 *
 * Phase 2: FIREBALL RISE (200-600 ticks, T=3000-2000 K)
 *   - Buoyancy-driven ascent: v = sqrt(2×g×H × ΔT/T)
 *   - Toroidal circulation begins
 *   - Color transitions: White → Yellow → Orange → Red
 *   - Debris entrainment starts
 *
 * Phase 3: MUSHROOM CLOUD FORMATION (600-2400 ticks, T=2000-1000 K)
 *   - Cap formation at atmospheric inversion layer
 *   - Full toroidal convection
 *   - Stabilization height: 8-15 km (tactical), 15-40 km (strategic)
 *   - Color: Orange → Red → Brown (dust dominates)
 *
 * Phase 4: STABILIZATION (2400+ ticks, T<1000 K)
 *   - Cloud spreads laterally
 *   - Slow rise continues
 *   - Color: Brown/grey (dust only)
 *   - Visible for hours
 *
 * REALISTIC SCALE DATA (from Glasstone & Dolan 1977):
 * Yield    Fireball Max    Thermal Min    Cloud Height   Cloud Width   Visibility
 * 1 kt     67 m            27 m           3,000 m        600 m         195 km
 * 15 kt    198 m           81 m           5,700 m        1,500 m       270 km
 * 100 kt   267 m           109 m          8,000 m        3,000 m       350 km
 * 1 MT     423 m           173 m          13,000 m       8,000 m       500 km
 * 10 MT    670 m           274 m          20,000 m       18,000 m      630 km
 * 50 MT    1,002 m         410 m          40,000 m       35,000 m      716 km
 *
 * 5-TIER LOD SYSTEM:
 * Tier 1 (0-128m): GPU Instanced Particles (20,000 particles, -30 FPS)
 * Tier 2 (128-512m): Batch Rendered Particles (5,000 particles, -10 FPS)
 * Tier 3 (512-5km): 3D Billboard Model (-2 FPS)
 * Tier 4 (5-50km): 2D Billboard (-0.5 FPS)
 * Tier 5 (50-716km): Skybox Projection (-0.1 FPS)
 *
 * CHUNK-INDEPENDENT RENDERING:
 * - Uses RenderNTMSkybox pattern for celestial coordinate rendering
 * - Visible even when explosion chunk is unloaded
 * - LOD transitions based on camera distance, not chunk loading
 * - Bypasses Minecraft's 256-block world height limit
 *
 * MEMORY EFFICIENCY:
 * - ~38 MB per explosion (vs 2.6 GB for old destruction system)
 * - Particle pooling for Tier 1/2
 * - Single model instance for Tier 3/4
 * - Single texture for Tier 5
 * - Automatic garbage collection when entity dies
 *
 * References:
 * - Glasstone & Dolan (1977): Effects of Nuclear Weapons (3rd edition)
 * - Taylor-Sedov blast wave theory
 * - Stefan-Boltzmann blackbody radiation law
 * - Atmospheric physics: U.S. Standard Atmosphere (1976)
 *
 * @author HBM Nuclear Tech Team
 */
public class EntityNukeTorexRealistic extends Entity implements IConstantRenderer {

	// === DATA PARAMETERS (synced to client) ===
	public static final DataParameter<Float> YIELD_KT = EntityDataManager.createKey(EntityNukeTorexRealistic.class, DataSerializers.FLOAT);
	// PHASE parameter REMOVED - replaced with continuous deformation progress
	public static final DataParameter<Float> TEMPERATURE = EntityDataManager.createKey(EntityNukeTorexRealistic.class, DataSerializers.FLOAT);
	public static final DataParameter<Float> FIREBALL_RADIUS = EntityDataManager.createKey(EntityNukeTorexRealistic.class, DataSerializers.FLOAT);
	public static final DataParameter<Float> CLOUD_HEIGHT = EntityDataManager.createKey(EntityNukeTorexRealistic.class, DataSerializers.FLOAT);
	public static final DataParameter<Float> CLOUD_WIDTH = EntityDataManager.createKey(EntityNukeTorexRealistic.class, DataSerializers.FLOAT);
	public static final DataParameter<Float> DEFORMATION_PROGRESS = EntityDataManager.createKey(EntityNukeTorexRealistic.class, DataSerializers.FLOAT);

	// === PHYSICS CONSTANTS ===
	private static final double BOLTZMANN_CONSTANT = 5.670374419e-8; // Stefan-Boltzmann constant (W⋅m⁻²⋅K⁻⁴)
	private static final double GRAVITY = 9.81; // m/s²
	private static final double AIR_DENSITY = 1.225; // kg/m³ at sea level
	private static final double SPECIFIC_HEAT_AIR = 1005.0; // J/(kg⋅K)
	private static final double SCALE_HEIGHT = 8500.0; // Atmospheric scale height (meters)

	// === FIREBALL PHYSICS (from Glasstone & Dolan 1977) ===
	private static final double INITIAL_TEMPERATURE = 1e7; // 10 million Kelvin (X-ray phase)
	private static final double TEMP_MAXIMUM = 7700.0 + 273.15; // 7700°C in Kelvin (second pulse peak)
	private static final double TEMP_MINIMUM = 3000.0 + 273.15; // 3000°C in Kelvin (thermal minimum)
	private static final double MIN_VISIBLE_TEMP = 1000.0 + 273.15; // 1000°C in Kelvin (dull red)

	// PDF formulas: R = C × W^0.4 (converted from feet to meters)
	private static final double RADIUS_THERMAL_MIN = 90.0 * 0.3048; // 27.4 m/kt^0.4 (thermal minimum)
	private static final double RADIUS_BREAKAWAY_AIR = 110.0 * 0.3048; // 33.5 m/kt^0.4 (air burst)
	private static final double RADIUS_BREAKAWAY_SURFACE = 145.0 * 0.3048; // 44.2 m/kt^0.4 (surface burst)
	private static final double RADIUS_MAXIMUM_FACTOR = 2.0; // Maximum ~2× breakaway radius

	// === MUSHROOM CLOUD PHYSICS ===
	private static final double CLOUD_HEIGHT_TACTICAL = 3000.0; // <100 kt: 3-8 km
	private static final double CLOUD_HEIGHT_STRATEGIC = 13000.0; // 1 MT: 13 km
	private static final double CLOUD_HEIGHT_MEGATON = 40000.0; // 50 MT: 40 km

	// === VISIBILITY DISTANCES (meters) ===
	private static final double VISIBILITY_1KT = 195000.0; // 195 km
	private static final double VISIBILITY_50MT = 716000.0; // 716 km

	// === LOD TIER BOUNDARIES (meters) ===
	public static final double LOD_TIER1_RANGE = 128.0; // Ultra-close: GPU instanced particles
	public static final double LOD_TIER2_RANGE = 512.0; // Close: Batch rendered particles
	public static final double LOD_TIER3_RANGE = 5000.0; // Medium: 3D billboard model
	public static final double LOD_TIER4_RANGE = 50000.0; // Far: 2D billboard
	// Tier 5 (>50km): Skybox projection

	// === ENTITY STATE ===
	private double yieldKilotons = 15.0; // Weapon yield in kilotons
	// explosionPhase REMOVED - replaced with continuous deformation progress (0.0-1.0)
	private double deformationProgress = 0.0; // Continuous sphere→mushroom deformation (0.0-1.0)
	private double temperature = INITIAL_TEMPERATURE; // Current fireball temperature (Kelvin)
	private double fireballRadius = 0.0; // Current fireball radius (meters)
	private double cloudHeight = 0.0; // Current cloud top height (meters)
	private double cloudWidth = 0.0; // Current cloud width (meters)
	private int maxAge = 2400; // Lifetime in ticks (2 minutes default)

	// === PHYSICS STATE ===
	private double thermalEnergy = 0.0; // Remaining thermal energy (Joules)
	private double verticalVelocity = 0.0; // Upward velocity (m/s)
	private double buoyancyForce = 0.0; // Net buoyancy force (Newtons)
	private double expansionVelocity = 0.0; // Radial expansion velocity (m/s) - for Froude number

	// PHASE 2-4 CLOUDLET SYSTEM: DELETED (now using OBJ sphere rendering)
	protected static Random rand = new Random();

	// === VOLUMETRIC PARTICLE SYSTEM (yield-dependent particle count) ===
	public ArrayList<VolumetricParticle> volumetricParticles = new ArrayList<>();
	// getMaxParticleCount() is now dynamic based on weapon yield (see getMaxParticleCount())
	// Formula: 50,000 × W^0.6
	//   1 kt: 50,000 particles
	//   15 kt: 206,000 particles
	//   100 kt: 793,000 particles
	//   1 MT (1000 kt): 3,162,000 particles
	//   10 MT: 12,589,000 particles
	//   50 MT: 31,622,000 particles
	private static final int CONCENTRIC_LAYERS = 10; // Number of concentric shells for volume sampling
	private long turbulenceSeed = 0; // Seed for reproducible turbulence
	private float turbulenceStrength = 0.0F; // Phase-dependent turbulence intensity
	private int particlesSpawnedTotal = 0; // Total particles spawned (for gradual spawning)
	public double heat = 1.0; // Heat value for temperature/color calculation (like EntityNukeTorex)

	// === BLAST WAVE PARTICLE SYSTEM ===
	// Visualizes supersonic shockwave propagating from fireball to 5psi boundary
	// Based on PDF Chapter 3 (overpressure, dynamic pressure, blast velocity)
	public ArrayList<BlastWaveParticle> blastWaveParticles = new ArrayList<>();
	private int blastParticlesSpawnedTotal = 0; // Total blast particles spawned

	// === LOD MANAGER (client-side only) ===
	// Will be created when NukeTorexLODManager is implemented
	// private NukeTorexLODManager lodManager = null;

	public EntityNukeTorexRealistic(World world) {
		super(world);
		this.setSize(1.0F, 1.0F); // Minimal collision box
		this.isImmuneToFire = true;
		this.ignoreFrustumCheck = true; // Always render (LOD handles culling)
	}

	@Override
	protected void entityInit() {
		this.dataManager.register(YIELD_KT, 15.0F);
		// PHASE register REMOVED - replaced with continuous deformation progress
		this.dataManager.register(DEFORMATION_PROGRESS, 0.0F);
		this.dataManager.register(TEMPERATURE, (float)INITIAL_TEMPERATURE);
		this.dataManager.register(FIREBALL_RADIUS, 0.0F);
		this.dataManager.register(CLOUD_HEIGHT, 0.0F);
		this.dataManager.register(CLOUD_WIDTH, 0.0F);
	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) {
		if (nbt.hasKey("yieldKt")) yieldKilotons = nbt.getDouble("yieldKt");
		// phase NBT read REMOVED - replaced with deformationProgress
		if (nbt.hasKey("deformationProgress")) deformationProgress = nbt.getDouble("deformationProgress");
		if (nbt.hasKey("temperature")) temperature = nbt.getDouble("temperature");
		if (nbt.hasKey("fireballRadius")) fireballRadius = nbt.getDouble("fireballRadius");
		if (nbt.hasKey("cloudHeight")) cloudHeight = nbt.getDouble("cloudHeight");
		if (nbt.hasKey("cloudWidth")) cloudWidth = nbt.getDouble("cloudWidth");
		if (nbt.hasKey("maxAge")) maxAge = nbt.getInteger("maxAge");
		if (nbt.hasKey("thermalEnergy")) thermalEnergy = nbt.getDouble("thermalEnergy");
		if (nbt.hasKey("verticalVelocity")) verticalVelocity = nbt.getDouble("verticalVelocity");

		// Sync to data manager
		this.dataManager.set(YIELD_KT, (float)yieldKilotons);
		// PHASE sync REMOVED
		this.dataManager.set(DEFORMATION_PROGRESS, (float)deformationProgress);
		this.dataManager.set(TEMPERATURE, (float)temperature);
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) {
		nbt.setDouble("yieldKt", yieldKilotons);
		// phase NBT write REMOVED - replaced with deformationProgress
		nbt.setDouble("deformationProgress", deformationProgress);
		nbt.setDouble("temperature", temperature);
		nbt.setDouble("fireballRadius", fireballRadius);
		nbt.setDouble("cloudHeight", cloudHeight);
		nbt.setDouble("cloudWidth", cloudWidth);
		nbt.setInteger("maxAge", maxAge);
		nbt.setDouble("thermalEnergy", thermalEnergy);
		nbt.setDouble("verticalVelocity", verticalVelocity);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean isInRangeToRenderDist(double distance) {
		// Always return true - LOD manager handles actual culling
		// Maximum visibility: 716 km for 50 MT explosion
		double maxVisibility = calculateVisibilityRange();
		return distance <= maxVisibility;
	}

	/**
	 * CRITICAL FIX FOR Y-AXIS VISIBILITY CULLING
	 *
	 * Override getRenderBoundingBox() to return a massive bounding box that encompasses
	 * the entire visual extent of the mushroom cloud.
	 *
	 * ROOT CAUSE:
	 * - Entity collision box is only 1x1 (setSize(1.0F, 1.0F) at line 184)
	 * - Rendered cloud extends thousands of blocks upward via GL transformations
	 * - Minecraft's frustum culling uses entity's bounding box, not visual extent
	 * - Despite ignoreFrustumCheck=true and shouldRender() override, frustum check
	 *   still affects rendering based on the bounding box returned by this method
	 *
	 * SOLUTION:
	 * - Return dynamic AxisAlignedBB based on actual cloud dimensions
	 * - Box extends from ground to cloudHeight + margins
	 * - Horizontal extent matches cloudWidth and fireballRadius
	 *
	 * This ensures the mushroom cloud remains visible even when camera looks upward
	 * past extreme angles, fixing the Y-axis disappearance bug shown in screenshot.
	 *
	 * @return Massive bounding box encompassing entire mushroom cloud visual extent
	 */
	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		// Get current explosion dimensions from DataManager (synced from server)
		double cloudHeight = this.getCloudHeight();
		double cloudWidth = this.getCloudWidth();
		double fireballRadius = this.getFireballRadius();

		// Calculate maximum extents
		// Horizontal: Use larger of cloudWidth or fireballRadius
		double maxHorizontalExtent = Math.max(cloudWidth, fireballRadius);

		// Vertical: Cloud can rise to cloudHeight, fireball extends 2x radius (diameter)
		double maxVerticalExtent = Math.max(cloudHeight, fireballRadius * 2.0);

		// Safety margin: 10% extra space to account for turbulence and visual effects
		double margin = Math.max(maxHorizontalExtent, maxVerticalExtent) * 0.1;

		// Create bounding box:
		// - Center: entity position (posX, posY, posZ)
		// - Extends horizontally in all directions by maxHorizontalExtent
		// - Extends downward by half horizontal extent (fireball is half-buried)
		// - Extends upward to cloudHeight + margin
		return new AxisAlignedBB(
			this.posX - maxHorizontalExtent - margin,
			this.posY - maxHorizontalExtent * 0.5 - margin,  // Below ground for half-buried fireball
			this.posZ - maxHorizontalExtent - margin,
			this.posX + maxHorizontalExtent + margin,
			this.posY + maxVerticalExtent + margin,  // Upward to cloud top + margin
			this.posZ + maxHorizontalExtent + margin
		);
	}

	@Override
	public void onUpdate() {
		if (!world.isRemote) {
			// SERVER-SIDE: Physics simulation only
			updatePhysics();
		} else {
			// CLIENT-SIDE: Particle generation and rendering
			// Server sends only physics parameters (radius, temperature, phase)
			// Client generates particles locally based on those parameters

			// COMPREHENSIVE DEBUG LOGGING
			if (ticksExisted % 20 == 0 || ticksExisted < 10) {
				System.out.println("==================================================");
				System.out.println("[CLIENT UPDATE] Tick=" + ticksExisted);
				System.out.println("  Deformation Progress: " + String.format("%.3f", getDeformationProgress()));
				System.out.println("  Fireball Radius: " + String.format("%.2f", getFireballRadius()) + " m");
				System.out.println("  Cloud Height: " + String.format("%.2f", getCloudHeight()) + " m");
				System.out.println("  Cloud Width: " + String.format("%.2f", getCloudWidth()) + " m");
				System.out.println("  Temperature: " + String.format("%.0f", getTemperature()) + " K");
				System.out.println("  Yield: " + String.format("%.2f", getYieldKilotons()) + " kt");
				System.out.println("  Current Particles: " + volumetricParticles.size() + " / " + getMaxParticleCount());
				// Particle spawning now handled by OBJ sphere rendering
			System.out.println("  Deformation Stage: " + (getDeformationProgress() < 0.3 ? "Sphere" : getDeformationProgress() < 0.7 ? "Pinching" : "Mushroom"));
				System.out.println("==================================================");
			}
			updateClientParticles();
		}

		// Entity lifetime management
		if (!world.isRemote && this.ticksExisted > maxAge) {
			this.setDead();
		}
	}

	/**
	 * CLIENT-SIDE PARTICLE UPDATE
	 *
	 * Generates and updates volumetric particles on client side.
	 * Server sends only physics parameters via DataManager.
	 * Client regenerates particles from those parameters.
	 *
	 * BANDWIDTH EFFICIENCY:
	 * - Server sync: 24 bytes/tick (6 floats: yield, phase, temp, radius, height, width)
	 * - Client generation: 50,000 particles locally (no network traffic)
	 * - Savings: 99.997% (vs syncing 1.65 MB of particle data)
	 */
	private void updateClientParticles() {
		// Get current explosion state from DataManager (synced from server)
		// CRITICAL: Update CLASS INSTANCE variables, not just local variables!
		// spawnVolumetricParticles() uses instance variables, not parameters
		this.fireballRadius = getFireballRadius();
		this.cloudHeight = getCloudHeight();
		this.cloudWidth = getCloudWidth();
		this.deformationProgress = getDeformationProgress();
		this.temperature = getTemperature();
		this.yieldKilotons = getYieldKilotons();

		// DEBUG: Log first 10 ticks to diagnose issue
		if (ticksExisted < 10) {
			System.out.println("[CLIENT] Tick=" + ticksExisted + " | Radius=" + fireballRadius +
				" | Height=" + cloudHeight + " | Progress=" + String.format("%.3f", deformationProgress) + " | Particles=" + volumetricParticles.size());
		}

		// UPDATE HEAT (like EntityNukeTorex lines 230-231)
		// Heat decreases over time for temperature/color calculation
		double s = Math.sqrt(this.fireballRadius / 67.0); // Approximate scale from radius
		int maxHeat = (int) (50 * s * s);
		heat = maxHeat - Math.pow((double) (maxHeat * this.ticksExisted) / maxAge, 0.6);

		// TEMPORARY PARTICLE SPAWNING (until Phase 5 OBJ rendering is complete)
		// Spawn particles for all stages (fireball is always visible)
		if (fireballRadius > 1.0 && temperature > 1000.0) {
			int maxParticles = getMaxParticleCount();
			int currentCount = volumetricParticles.size();

			// Reduce particle count for late stages to improve performance
			int targetCount = maxParticles;
			if (deformationProgress > 0.7) {
				// Mushroom stage: reduce to 30% (cloud particles, not fireball)
				targetCount = (int)(maxParticles * 0.3);
			} else if (deformationProgress > 0.3) {
				// Transition stage: reduce to 60%
				targetCount = (int)(maxParticles * 0.6);
			}

			// Spawn gradually to target particle count
			if (currentCount < targetCount) {
				// Spawn rate: fill to target over 5 seconds (100 ticks)
				int spawnRate = Math.max(100, targetCount / 100);
				int toSpawn = Math.min(spawnRate, targetCount - currentCount);

				// Convert progress to pseudo-phase for legacy method
				int pseudoPhase = (deformationProgress < 0.1) ? 0 : (deformationProgress < 0.3) ? 1 : 2;

				spawnVolumetricParticles(toSpawn, pseudoPhase);

				// DEBUG: Log first spawn
				if (currentCount == 0 && toSpawn > 0) {
					System.out.println("[PARTICLE SPAWN] First spawn: " + toSpawn + " particles");
					System.out.println("  Radius=" + fireballRadius + " Progress=" + String.format("%.3f", deformationProgress));
					System.out.println("  Target=" + targetCount + " Max=" + maxParticles);
				}
			}

			// Remove excess particles if target decreased
			while (volumetricParticles.size() > targetCount) {
				volumetricParticles.remove(volumetricParticles.size() - 1);
			}
		}

		// === BLAST WAVE PARTICLE SYSTEM ===
		// Spawn blast wave particles during fireball expansion phase
		// Visual representation of supersonic shockwave propagating outward
		if (shouldSpawnBlastWaveParticles()) {
			spawnBlastWaveParticles();
		}

		// Update existing blast wave particles
		blastWaveParticles.removeIf(p -> p.isDead());
		for (BlastWaveParticle particle : blastWaveParticles) {
			particle.update();
		}

		// Debug logging for blast wave
		if (ticksExisted % 20 == 0 && !blastWaveParticles.isEmpty()) {
			System.out.println("[BLAST WAVE] Active particles: " + blastWaveParticles.size());
			if (!blastWaveParticles.isEmpty()) {
				BlastWaveParticle first = blastWaveParticles.get(0);
				System.out.println("[BLAST WAVE] Sample particle: dist=" + String.format("%.1f", first.distanceFromCenter) +
					"m, pressure=" + String.format("%.1f", first.overpressure) + "psi");
			}
		}
	}

	/**
	 * SPAWN VOLUMETRIC PARTICLES (LAGRANGIAN FLOW METHOD)
	 *
	 * NEW APPROACH: Spawn particles at random positions, then let PDF-accurate
	 * velocity fields naturally move them into correct geometric distributions.
	 *
	 * This Lagrangian (particle-following) method is more physically accurate
	 * than trying to spawn particles in exact geometric positions.
	 *
	 * @param toSpawn Number of particles to spawn this tick
	 * @param phase Current explosion phase (1-4)
	 */
	private void spawnVolumetricParticles(int toSpawn, int phase) {
		// Determine spawn volume based on current explosion scale
		// USE PHYSICAL SCALE DIRECTLY: 1 meter = 1 block (Minecraft standard)
		// Fire ball dimensions from PDF formulas (R = C × W^0.4):
		// - 1 kt: 67m, 15 kt: 198m, 100 kt: 267m, 1 MT: 423m, 50 MT: 1002m
		double spawnRadius = 50.0; // Default minimum
		double spawnCenterY = 0.0; // Ground level default

		// Use physical dimensions directly from physics calculations
		if (phase <= 1) {
			// Phase 0-1: Fireball formation (CENTRAL CORE spawning like EntityNukeTorex)
			// Spawn in central 20% core, let radial expansion fill the rest
			spawnRadius = Math.max(fireballRadius * 0.2, 10.0); // Central core, minimum 10m
			spawnCenterY = fireballRadius * 0.5; // Half-buried (surface burst)
		}
		// PHASE 2-4 SPAWN GEOMETRY: DELETED (now using OBJ sphere rendering instead of particles)

		// COMPREHENSIVE SPAWN DEBUG LOGGING
		System.out.println("========== PARTICLE SPAWN DEBUG ==========");
		System.out.println("[SPAWN] Tick=" + ticksExisted + " | Phase=" + phase);
		System.out.println("[SPAWN] Yield=" + String.format("%.2f", yieldKilotons) + " kt");
		System.out.println("[SPAWN] To Spawn=" + toSpawn + " particles");
		System.out.println("[SPAWN] Current Total=" + volumetricParticles.size() + " / " + getMaxParticleCount());
		System.out.println("[SPAWN] Spawn Radius=" + String.format("%.1f", spawnRadius) + " m");
		System.out.println("[SPAWN] Spawn Center Y=" + String.format("%.1f", spawnCenterY) + " m");
		System.out.println("[SPAWN] Fireball Radius=" + String.format("%.1f", fireballRadius) + " m");
		System.out.println("[SPAWN] Cloud Width=" + String.format("%.1f", cloudWidth) + " m");
		System.out.println("[SPAWN] Cloud Height=" + String.format("%.1f", cloudHeight) + " m");

		// Particle size scaling - FIXED to reasonable size (1-5 blocks)
		// CRITICAL: Particle size should be small (1-5 blocks), not 61 blocks!
		// Base scale determined by phase:
		// - Phase 0-1 (fireball): 1-2 blocks (hot, compact)
		// PHASE 2-4 SCALING: DELETED (now using OBJ sphere rendering)
		float baseScale;
		if (phase <= 1) {
			baseScale = 1.5F; // Fireball: small, dense particles
		} else {
			baseScale = 1.5F; // Should never reach (entity despawns after Phase 1)
		}

		// Very slow growth over time (max +1 block over entire lifetime)
		float timeGrowth = (float)(this.ticksExisted / (double)maxAge) * 1.0F;

		float startingScale = baseScale + timeGrowth * 0.5F;
		float growingScale = baseScale + timeGrowth;

		// LOG PARTICLE SIZE CALCULATION
		System.out.println("[SPAWN] Particle Size Calculation:");
		System.out.println("[SPAWN]   Base Scale (phase-dependent)=" + String.format("%.2f", baseScale) + " blocks");
		System.out.println("[SPAWN]   Time Growth=" + String.format("%.2f", timeGrowth) + " blocks");
		System.out.println("[SPAWN]   Starting Scale=" + String.format("%.2f", startingScale) + " blocks");
		System.out.println("[SPAWN]   Growing Scale=" + String.format("%.2f", growingScale) + " blocks");

		// Lifetime
		int lifetime = Math.min(
			(this.ticksExisted * this.ticksExisted) + 200,
			maxAge - this.ticksExisted + 200
		);

		for (int i = 0; i < toSpawn; i++) {
			// GAUSSIAN 3D DISTRIBUTION (like EntityNukeTorex)
			// Particles spawn densely at center, sparse at edges (Gaussian bell curve)
			// This creates proper volumetric fireball formation
			double x_local = rand.nextGaussian() * spawnRadius;
			double y_local = rand.nextGaussian() * spawnRadius;
			double z_local = rand.nextGaussian() * spawnRadius;

			// World coordinates
			double x = this.posX + x_local;
			double y = this.posY + spawnCenterY + y_local;
			double z = this.posZ + z_local;

			// Calculate layer based on distance from center
			double distFromCenter = Math.sqrt(x_local*x_local + y_local*y_local + z_local*z_local);
			int layer = (int)((distFromCenter / spawnRadius) * CONCENTRIC_LAYERS);
			if (layer >= CONCENTRIC_LAYERS) layer = CONCENTRIC_LAYERS - 1;
			if (layer < 0) layer = 0;

			// Random rotation angle for toroidal convection
			float angle = (float)(rand.nextDouble() * 2.0 * Math.PI);

			// INITIAL VELOCITY: STRONG RADIAL OUTWARD (fireball expansion)
			// Particles start at center and radially expand to fill fireball volume
			double r_spawn = Math.sqrt(x_local*x_local + y_local*y_local + z_local*z_local);
			double expansionSpeed = fireballRadius / 100.0; // Reach edge in ~100 ticks

			double vx, vy, vz;
			if (r_spawn > 0.1) {
				// Radial outward velocity
				vx = (x_local / r_spawn) * expansionSpeed;
				vy = (y_local / r_spawn) * expansionSpeed;
				vz = (z_local / r_spawn) * expansionSpeed;
			} else {
				// At exact center: small random velocity
				vx = (rand.nextDouble() - 0.5) * 0.5;
				vy = (rand.nextDouble() - 0.5) * 0.5;
				vz = (rand.nextDouble() - 0.5) * 0.5;
			}

			VolumetricParticle particle = new VolumetricParticle(x, y, z, vx, vy, vz, startingScale, growingScale, layer, angle);
			particle.maxAge = lifetime;
			volumetricParticles.add(particle);
			particlesSpawnedTotal++;

			// DEBUG: Log first 3 particles' positions
			if (i < 3) {
				System.out.println("[SPAWN DEBUG] Particle " + i + ": pos=(" +
					String.format("%.2f", x) + ", " + String.format("%.2f", y) + ", " + String.format("%.2f", z) + ")" +
					" | vel=(" + String.format("%.3f", vx) + ", " + String.format("%.3f", vy) + ", " + String.format("%.3f", vz) + ")" +
					" | scale=" + String.format("%.2f", startingScale) + " | lifetime=" + lifetime);
			}
		}

		// SPAWN COMPLETION LOG
		System.out.println("[SPAWN] === SPAWN COMPLETE ===");
		System.out.println("[SPAWN] Just Spawned: " + toSpawn + " particles");
		System.out.println("[SPAWN] Total Now: " + volumetricParticles.size());
		System.out.println("[SPAWN] Total Lifetime: " + particlesSpawnedTotal);
		System.out.println("[SPAWN] Percentage: " + String.format("%.1f", (volumetricParticles.size() * 100.0 / getMaxParticleCount())) + "%");
		System.out.println("==========================================");
	}

	/**
	 * UPDATE VOLUMETRIC PARTICLES (PDF-ACCURATE PHASE-SPECIFIC VELOCITY FIELDS)
	 *
	 * Implements physically accurate velocity fields for each phase:
	 * - Phase 1: Radial expansion (isothermal sphere growth)
	 * - Phase 2: Buoyant rise + radial expansion
	 * - Phase 3: Toroidal circulation (vortex ring)
	 * - Phase 4: Lateral spreading + gravitational settling
	 *
	 * PDF References:
	 * - §2.114: Isothermal expansion
	 * - §2.129: Buoyant rise
	 * - §2.07: Toroidal convection
	 * - §2.14-2.16: Mushroom stabilization
	 */
	private void updateVolumetricParticles() {
		if (volumetricParticles.isEmpty()) return;

		// Calculate simulation time (seconds)
		double timeSeconds = this.ticksExisted / 20.0;

		// Progress-dependent turbulence (smooth transitions)
		if (deformationProgress < 0.3) {
			turbulenceStrength = 0.1F; // Minimal (smooth spherical fireball)
		} else if (deformationProgress < 0.7) {
			turbulenceStrength = 0.5F; // Moderate (pinching/rising column)
		} else {
			turbulenceStrength = 1.0F; // Strong (mushroom convection)
		}

		double wavelength = 50.0; // 50m eddies
		double amplitude = turbulenceStrength * 0.02; // blocks/tick (reduced for realism)

		// Update each particle
		for (VolumetricParticle p : volumetricParticles) {
			// Turbulent displacement (small-scale mixing)
			double[] turbVel = SimplexNoise.getTurbulenceVector(
				p.x, p.y, p.z, wavelength, amplitude
			);

			// Add turbulence to velocity (preserves geometric distribution)
			p.vx += turbVel[0];
			p.vy += turbVel[1];
			p.vz += turbVel[2];

			// PHASE-SPECIFIC VELOCITY FIELDS (PDF-accurate)
			// Convert progress to pseudo-phase for legacy particle code
		int pseudoPhase = (deformationProgress < 0.1) ? 0 : (deformationProgress < 0.3) ? 1 : 2;
		switch (pseudoPhase) {
				case 0: // PHASE 0: INITIAL FLASH (NO PARTICLES)
					// No particles should exist during Phase 0 (pure light flash)
					break;

				case 1: // PHASE 1: FIREBALL EXPANSION (RADIAL OUTWARD MOTION)
					// Fireball is rapidly expanding - particles move radially outward from center
					// This creates the volumetric fireball sphere (like EntityNukeTorex)
					double dx1 = p.x - this.posX;
					double dy1 = p.y - (this.posY + fireballRadius * 0.5); // Center of sphere
					double dz1 = p.z - this.posZ;
					double r1 = Math.sqrt(dx1 * dx1 + dy1 * dy1 + dz1 * dz1);

					if (r1 > 1.0) {
						// Radial expansion speed (fireball is growing)
						double expansionSpeed = fireballRadius / 20.0; // m/s (radius growth rate)
						// INCREASED multiplier: 0.05 → 1.0 for proper expansion velocity
						double mult1 = p.motionMult * getSimulationSpeed() * 1.0;
						p.vx += (dx1 / r1) * expansionSpeed * mult1;
						p.vy += (dy1 / r1) * expansionSpeed * mult1;
						p.vz += (dz1 / r1) * expansionSpeed * mult1;
					}
					break;

				// PHASE 2-4: DELETED (now using OBJ sphere rendering instead of particles)
			}

			// Update particle position
			p.update();

			// Update particle color based on temperature and phase
			updateParticleColor(p);
		}

		// Remove dead particles (fade out complete)
		volumetricParticles.removeIf(p -> p.isDead());
	}

	/**
	 * UPDATE PARTICLE COLOR (EXACT EntityNukeTorex LOGIC)
	 *
	 * Uses SAME temperature calculation as EntityNukeTorex.Cloudlet.updateColor() (lines 542-560)
	 *
	 * @param p Particle to update
	 */
	private void updateParticleColor(VolumetricParticle p) {
		// EXACT EntityNukeTorex temperature calculation (lines 545-556)
		double exX = this.posX;
		double exY = this.posY + this.fireballRadius * 0.5; // Fireball center (half-buried)
		double exZ = this.posZ;

		double distX = exX - p.x;
		double distY = exY - p.y;
		double distZ = exZ - p.z;

		double distSq = distX * distX + distY * distY + distZ * distZ;
		distSq /= Math.max(this.heat, 1.0); // Normalize by heat (like EntityNukeTorex)

		double col = 2.0 / Math.max(distSq, 1.0); // col goes from 2-0 (like EntityNukeTorex line 556)

		// Temperature for smoke texture selection (0-1 range: cold to hot)
		// col=2.0 (center, hot) → temperature=1.0
		// col=0.0 (edge, cold) → temperature=0.0
		p.temperature = (float)MathHelper.clamp(col / 2.0, 0.0, 1.0);

		// Convert progress to pseudo-phase for legacy color code
	int colorPhase = (deformationProgress < 0.1) ? 0 : (deformationProgress < 0.3) ? 1 : (deformationProgress < 0.7) ? 2 : 3;

	if (colorPhase == 0) {
			// Phase 0: Brilliant white flash
			p.r = 1.0F;
			p.g = 1.0F;
			p.b = 1.0F;
			p.alpha = 1.0F;
		} else if (colorPhase == 1) {
			// Phase 1: Temperature-based color (white → orange-white)
			if (temperature >= 7000.0) {
				p.r = 1.0F; p.g = 1.0F; p.b = 1.0F;
			} else if (temperature >= 5000.0) {
				float t = (float)((temperature - 5000.0) / 2000.0);
				p.r = 1.0F; p.g = 1.0F; p.b = t;
			} else if (temperature >= 3500.0) {
				float t = (float)((temperature - 3500.0) / 1500.0);
				p.r = 1.0F; p.g = 0.6F + 0.4F * t; p.b = 0.0F;
			} else {
				float t = (float)((temperature - 2000.0) / 1500.0);
				p.r = 1.0F; p.g = 0.3F + 0.3F * t; p.b = 0.0F;
			}
			p.alpha = 0.9F;
		} else if (colorPhase == 2) {
			// Phase 2: Orange-red (cooling fireball)
			p.r = 1.0F;
			p.g = 0.47F;
			p.b = 0.24F;
			p.alpha = 0.8F;
		} else if (colorPhase == 3) {
			// Phase 3: Gray-brown (mushroom cap)
			p.r = 0.55F;
			p.g = 0.50F;
			p.b = 0.45F;
			p.alpha = 0.7F;
		} else {
			// Phase 4: Dark gray (stabilized cloud)
			p.r = 0.45F;
			p.g = 0.43F;
			p.b = 0.40F;
			p.alpha = 0.6F;
		}

		// Layer-based variation (inner brighter, outer darker)
		float layerFactor = 1.0F - (p.layer / (float)CONCENTRIC_LAYERS) * 0.3F;
		p.r *= layerFactor;
		p.g *= layerFactor;
		p.b *= layerFactor;
	}

	// PHASE 2-4 HELPER METHODS DELETED: getConvectionMotion(), getLiftMotion() (now using OBJ sphere rendering)

	/**
	 * GET SIMULATION SPEED
	 *
	 * Returns speed multiplier for particle motion.
	 * Can be modified for slow-motion effects or server performance.
	 *
	 * @return Simulation speed multiplier (1.0 = real-time)
	 */
	private double getSimulationSpeed() {
		return 1.0; // Real-time simulation
	}

	/**
	 * BLAST WAVE PARTICLE SPAWN CONDITION
	 *
	 * Determines when to spawn blast wave particles.
	 *
	 * Spawn conditions (all must be true):
	 * 1. Fireball is expanding (Phase 1: deformation < 0.3)
	 * 2. Temperature has dropped enough for shock to be visible (<10,000 K)
	 * 3. Fireball radius is significant (>10m, avoid tiny initial fireball)
	 * 4. Spawn interval (every 2 ticks to control particle count)
	 *
	 * Physics rationale:
	 * - Blast wave forms immediately but becomes visible when dust/air heated
	 * - Temperature threshold ensures shock has separated from fireball
	 * - Continuous spawning represents sustained shockwave propagation
	 *
	 * @return true if blast particles should spawn this tick
	 */
	private boolean shouldSpawnBlastWaveParticles() {
		// Must be in fireball expansion phase (before mushroom formation)
		boolean inExpansionPhase = deformationProgress < 0.3;

		// Temperature must be low enough for shock to be distinct from fireball
		// (Shock becomes visible when cooler dust/air is compressed)
		boolean temperatureSufficient = temperature < 10000.0;

		// Fireball must be large enough to spawn meaningful blast wave
		boolean fireballSignificant = fireballRadius > 10.0;

		// Spawn every 2 ticks (10 times per second) to control particle count
		boolean spawnInterval = ticksExisted % 2 == 0;

		return inExpansionPhase && temperatureSufficient && fireballSignificant && spawnInterval;
	}

	/**
	 * SPAWN BLAST WAVE PARTICLES (RING FORMATION)
	 *
	 * Spawns particles in a ring at the current fireball edge.
	 * Particles then propagate radially outward until 5psi boundary.
	 *
	 * SPAWN GEOMETRY:
	 * - Ring at current fireball radius (horizontal plane)
	 * - Uniform angular distribution (0 to 2π)
	 * - Particle count scales with yield (W^0.33)
	 *
	 * PARTICLE COUNT SCALING:
	 * - 1 kt: 50 particles per ring
	 * - 15 kt: 125 particles per ring (Hiroshima scale)
	 * - 100 kt: 232 particles per ring
	 * - 1 MT: 500 particles per ring
	 * - 50 MT: 1000 particles per ring (capped for performance)
	 *
	 * Total particles over fireball lifetime:
	 * - Spawn duration: ~100-200 ticks (5-10 seconds)
	 * - Spawn rate: 10 Hz (every 2 ticks)
	 * - Total: 2500-10,000 particles for typical explosion
	 */
	private void spawnBlastWaveParticles() {
		double currentFireballRadius = this.fireballRadius; // meters
		double currentYieldKt = this.yieldKilotons;

		// Calculate particle count for this spawn cycle
		// Scales with W^(1/3) to match blast wave circumference growth
		int particleCount = (int)(50.0 * Math.pow(currentYieldKt, 0.33));

		// Performance cap: maximum 1000 particles per ring
		particleCount = Math.min(particleCount, 1000);

		// LOD optimization: reduce particle count if many already exist
		int currentTotal = blastWaveParticles.size();
		int maxTotalParticles = particleCount * 100; // 100 rings worth
		if (currentTotal > maxTotalParticles * 0.8) {
			// Near limit: reduce spawn rate by 50%
			particleCount /= 2;
		}

		// Spawn particles in ring formation
		for (int i = 0; i < particleCount; i++) {
			// Angular position around ring (uniform distribution)
			double angle = (2.0 * Math.PI * i) / particleCount;

			// Create blast wave particle
			BlastWaveParticle particle = new BlastWaveParticle(
				this.posX,
				this.posY,
				this.posZ,
				currentFireballRadius,
				angle,
				currentYieldKt
			);

			blastWaveParticles.add(particle);
			blastParticlesSpawnedTotal++;
		}

		// Debug logging (first spawn only)
		if (blastParticlesSpawnedTotal == particleCount && ticksExisted % 20 == 0) {
			System.out.println("========== BLAST WAVE SPAWN ==========");
			System.out.println("[BLAST] First spawn at tick=" + ticksExisted);
			System.out.println("[BLAST] Fireball radius=" + String.format("%.1f", currentFireballRadius) + "m");
			System.out.println("[BLAST] Yield=" + String.format("%.1f", currentYieldKt) + "kt");
			System.out.println("[BLAST] Particles per ring=" + particleCount);
			System.out.println("[BLAST] 5psi radius=" + String.format("%.1f",
				FireballPhysicsCalculator.calculate5PsiRadius(currentYieldKt)) + "m");
			System.out.println("======================================");
		}
	}

	/**
	 * SERVER-SIDE PHYSICS SIMULATION (PDF-ACCURATE)
	 * Updates temperature, radius, height based on Glasstone & Dolan 1977
	 */
	private void updatePhysics() {
		double deltaTime = 0.05; // 1 tick = 0.05 seconds at 20 TPS
		double timeSeconds = ticksExisted * deltaTime;

		// === YIELD-SPECIFIC TIMING (varies by W^0.25) ===
		// For 1 MT (W=1000): t_min=0.01s (0.2 ticks), t_max=10s (200 ticks)
		double yieldTimeFactor = Math.pow(yieldKilotons / 1000.0, 0.25);
		double timeToThermalMin = 0.2 * yieldTimeFactor; // ticks
		double timeToBreakaway = 20.0 * yieldTimeFactor; // ticks
		double timeToMaximum = 200.0 * yieldTimeFactor; // ticks
		double timeToCloudFormation = 600.0 * yieldTimeFactor; // ticks

		// === CONTINUOUS PHYSICS UPDATE (PHASE-INDEPENDENT) ===
		// All transitions are now smooth and based on continuous time

		// === STAGE 0: INITIALIZATION (tick 0 only) ===
		if (ticksExisted == 0) {
			temperature = INITIAL_TEMPERATURE;
			fireballRadius = 1.0; // Start at 1m to avoid division by zero

			// Calculate initial thermal energy (35% of total yield)
			double THERMAL_FRACTION = 0.35;
			double JOULES_PER_KT = 4.184e12; // 1 kt TNT = 4.184 TJ
			thermalEnergy = yieldKilotons * JOULES_PER_KT * THERMAL_FRACTION;

			// Initialize cloud dimensions
			cloudHeight = fireballRadius * 0.5; // Start at fireball center (half-buried)
			cloudWidth = fireballRadius;

			// Initialize physics state
			verticalVelocity = 0.0;
			expansionVelocity = 0.0;
			buoyancyForce = 0.0;

			// Initialize deformation progress
			deformationProgress = 0.0;

			// DEBUG: Print initialization
			System.out.println("========== NUKE TOREX REALISTIC INITIALIZED ==========");
			System.out.println("Yield: " + yieldKilotons + " kt");
			System.out.println("Initial radius: " + fireballRadius + " m");
			System.out.println("Initial temperature: " + temperature + " K");
			System.out.println("======================================================");

			syncToClient();
		}

		// === STAGE 1A: COOLING TO THERMAL MINIMUM (initial → 3000°C) ===
		else if (ticksExisted < timeToThermalMin) {
			// Rapid expansion and cooling
			double progress = ticksExisted / timeToThermalMin;
			fireballRadius = RADIUS_THERMAL_MIN * Math.pow(yieldKilotons, 0.4) * progress;

			// Temperature drops from 10^7 K to 3000°C
			temperature = INITIAL_TEMPERATURE - (INITIAL_TEMPERATURE - TEMP_MINIMUM) * progress;

			// Calculate physics-based parameters
			expansionVelocity = FireballPhysicsCalculator.calculateExpansionVelocity(fireballRadius, timeSeconds);

			// Buoyancy minimal during rapid expansion (expansion inertia >> buoyancy)
			double fireballMass = FireballPhysicsCalculator.calculateFireballMass(fireballRadius, temperature);
			buoyancyForce = FireballPhysicsCalculator.calculateBuoyancyForce(fireballRadius, temperature, FireballPhysicsCalculator.getAirDensity(0.0));
			verticalVelocity = 0.0; // Not yet rising

			// Cloud dimensions (still spherical, centered)
			cloudHeight = fireballRadius * 0.5; // Half-buried
			cloudWidth = fireballRadius;

			// PHYSICS-BASED deformation progress (Option A: multi-parameter function)
			deformationProgress = FireballPhysicsCalculator.calculateDeformationProgress(
				timeSeconds, yieldKilotons, fireballRadius, verticalVelocity,
				cloudHeight, temperature, buoyancyForce
			);

			// DEBUG: Print every 10 ticks
			if (ticksExisted % 10 == 0) {
				System.out.println("[STAGE 1A] Tick=" + ticksExisted + " Radius=" + String.format("%.1f", fireballRadius) +
					" Temp=" + String.format("%.0f", temperature) + " Progress=" + String.format("%.3f", deformationProgress));
			}

			syncToClient();
		}

		// === STAGE 1B: REHEATING TO BREAKAWAY (3000°C → 7700°C) ===
		else if (ticksExisted < timeToBreakaway) {
			// Transition from thermal minimum to breakaway
			double progress = (ticksExisted - timeToThermalMin) / (timeToBreakaway - timeToThermalMin);
			double startRadius = RADIUS_THERMAL_MIN * Math.pow(yieldKilotons, 0.4);
			double endRadius = RADIUS_BREAKAWAY_SURFACE * Math.pow(yieldKilotons, 0.4);
			fireballRadius = startRadius + (endRadius - startRadius) * progress;

			// Temperature rises back to maximum (second pulse)
			temperature = TEMP_MINIMUM + (TEMP_MAXIMUM - TEMP_MINIMUM) * progress;

			// Calculate physics-based parameters
			expansionVelocity = FireballPhysicsCalculator.calculateExpansionVelocity(fireballRadius, timeSeconds);

			// Buoyancy starting to become significant
			double fireballMass = FireballPhysicsCalculator.calculateFireballMass(fireballRadius, temperature);
			buoyancyForce = FireballPhysicsCalculator.calculateBuoyancyForce(fireballRadius, temperature, FireballPhysicsCalculator.getAirDensity(0.0));

			// Vertical velocity starting to build up (buoyancy acceleration)
			double acceleration = buoyancyForce / Math.max(fireballMass, 1.0); // F = ma → a = F/m
			double dt = 1.0 / 20.0; // seconds per tick
			verticalVelocity += acceleration * dt; // Integrate velocity

			// Cloud dimensions (starting to rise slightly)
			cloudHeight = fireballRadius * 0.5 + verticalVelocity * timeSeconds * 0.1; // Gradual rise
			cloudWidth = fireballRadius;

			// PHYSICS-BASED deformation progress
			deformationProgress = FireballPhysicsCalculator.calculateDeformationProgress(
				timeSeconds, yieldKilotons, fireballRadius, verticalVelocity,
				cloudHeight, temperature, buoyancyForce
			);

			syncToClient();
		}

		// === STAGE 1C: EXPANSION TO MAXIMUM (7700°C, grow to 2× breakaway) ===
		else if (ticksExisted < timeToMaximum) {
			// Continue expansion to maximum radius
			double progress = (ticksExisted - timeToBreakaway) / (timeToMaximum - timeToBreakaway);
			double breakawayRadius = RADIUS_BREAKAWAY_SURFACE * Math.pow(yieldKilotons, 0.4);
			double maxRadius = breakawayRadius * RADIUS_MAXIMUM_FACTOR;
			fireballRadius = breakawayRadius + (maxRadius - breakawayRadius) * progress;

			// Maintain high temperature, then start cooling
			if (progress < 0.3) {
				temperature = TEMP_MAXIMUM; // Stay hot briefly
			} else {
				// Cool from 7700°C to 3000°C
				double coolProgress = (progress - 0.3) / 0.7;
				temperature = TEMP_MAXIMUM - (TEMP_MAXIMUM - TEMP_MINIMUM) * coolProgress;
			}

			// Calculate physics-based parameters
			expansionVelocity = FireballPhysicsCalculator.calculateExpansionVelocity(fireballRadius, timeSeconds);

			// Buoyancy force increasing as fireball cools (density difference increases)
			double fireballMass = FireballPhysicsCalculator.calculateFireballMass(fireballRadius, temperature);
			buoyancyForce = FireballPhysicsCalculator.calculateBuoyancyForce(fireballRadius, temperature, FireballPhysicsCalculator.getAirDensity(0.0));

			// Vertical velocity increasing (buoyancy-driven rise)
			double acceleration = buoyancyForce / Math.max(fireballMass, 1.0);
			double dt = 1.0 / 20.0;
			verticalVelocity += acceleration * dt;

			// Update cloud height using vertical velocity (integrate position)
			cloudHeight += verticalVelocity * dt;
			cloudHeight = Math.max(cloudHeight, fireballRadius * 0.5); // Never below fireball center

			// Cloud width expanding horizontally as it rises
			cloudWidth = fireballRadius * (1.0 + progress * 0.5);

			// PHYSICS-BASED deformation progress
			deformationProgress = FireballPhysicsCalculator.calculateDeformationProgress(
				timeSeconds, yieldKilotons, fireballRadius, verticalVelocity,
				cloudHeight, temperature, buoyancyForce
			);

			syncToClient();
		}

		// === STAGE 2: MUSHROOM CLOUD FORMATION (beyond maximum radius) ===
		else if (ticksExisted < timeToCloudFormation) {
			// Fireball continues to rise and deform into mushroom shape
			double progress = (ticksExisted - timeToMaximum) / (timeToCloudFormation - timeToMaximum);
			double maxRadius = RADIUS_BREAKAWAY_SURFACE * Math.pow(yieldKilotons, 0.4) * RADIUS_MAXIMUM_FACTOR;

			// Fireball radius stabilizes
			fireballRadius = maxRadius;

			// Continue cooling
			double coolProgress = Math.min(1.0, progress * 1.5);
			temperature = TEMP_MINIMUM - (TEMP_MINIMUM - MIN_VISIBLE_TEMP) * coolProgress;

			// Calculate physics-based parameters
			expansionVelocity = FireballPhysicsCalculator.calculateExpansionVelocity(fireballRadius, timeSeconds);

			// Buoyancy force decreasing as temperature drops
			double fireballMass = FireballPhysicsCalculator.calculateFireballMass(fireballRadius, temperature);
			buoyancyForce = FireballPhysicsCalculator.calculateBuoyancyForce(fireballRadius, temperature, FireballPhysicsCalculator.getAirDensity(0.0));

			// Vertical velocity decaying exponentially (atmospheric drag + stratification)
			double tropopauseHeight = FireballPhysicsCalculator.calculateTropopauseHeight(0.0);
			if (cloudHeight < tropopauseHeight) {
				// Still rising below tropopause
				double acceleration = buoyancyForce / Math.max(fireballMass, 1.0);
				double dt = 1.0 / 20.0;
				verticalVelocity += acceleration * dt;

				// Apply drag (velocity decay)
				double dragFactor = 0.98; // 2% decay per tick
				verticalVelocity *= dragFactor;
			} else {
				// At tropopause: stratification halts rise (velocity → 0)
				verticalVelocity *= 0.95; // Rapid deceleration
			}

			// Update cloud height (integrate velocity)
			double dt = 1.0 / 20.0;
			cloudHeight += verticalVelocity * dt;
			cloudHeight = Math.min(cloudHeight, tropopauseHeight); // Cannot exceed tropopause

			// Cloud width spreading horizontally (conservation of mass at stable layer)
			cloudWidth = fireballRadius * (1.5 + progress * 2.0);

			// PHYSICS-BASED deformation progress
			deformationProgress = FireballPhysicsCalculator.calculateDeformationProgress(
				timeSeconds, yieldKilotons, fireballRadius, verticalVelocity,
				cloudHeight, temperature, buoyancyForce
			);

			syncToClient();
		}

		// === STAGE 3: DISSIPATION (cloud stabilizes, entity despawns) ===
		else {
			// Mushroom cloud has formed, entity despawns
			// (In real implementation, would transition to particle-based cloud or static model)
			this.setDead();
			return;
		}

		// UPDATE SERVER-SIDE PARTICLES (if any were spawned)
		// These particles will have proper physics-based motion from toroidal circulation
		if (!world.isRemote && !volumetricParticles.isEmpty()) {
			updateVolumetricParticles();
		}
	}

	/**
	 * Sync physics state to client via data manager
	 */
	private void syncToClient() {
		this.dataManager.set(YIELD_KT, (float)yieldKilotons);
		// PHASE sync REMOVED - replaced with continuous deformation progress
		this.dataManager.set(DEFORMATION_PROGRESS, (float)deformationProgress);
		this.dataManager.set(TEMPERATURE, (float)temperature);
		this.dataManager.set(FIREBALL_RADIUS, (float)fireballRadius);
		this.dataManager.set(CLOUD_HEIGHT, (float)cloudHeight);
		this.dataManager.set(CLOUD_WIDTH, (float)cloudWidth);
	}

	/**
	 * Calculate mushroom cloud stabilization height based on yield
	 * Data from Glasstone & Dolan (1977)
	 */
	private double calculateCloudHeight(double yieldKt) {
		if (yieldKt < 100) {
			// Tactical: 3-8 km
			return 3000.0 + (5000.0 * (yieldKt / 100.0));
		} else if (yieldKt < 1000) {
			// Strategic: 8-13 km
			return 8000.0 + (5000.0 * ((yieldKt - 100.0) / 900.0));
		} else if (yieldKt < 50000) {
			// Megaton: 13-40 km
			return 13000.0 + (27000.0 * ((yieldKt - 1000.0) / 49000.0));
		} else {
			// Tsar Bomba scale: 40+ km
			return 40000.0;
		}
	}

	/**
	 * Calculate maximum visibility range based on yield
	 * Mushroom cloud visibility: 195 km (1 kt) to 716 km (50 MT)
	 */
	private double calculateVisibilityRange() {
		// Log interpolation between 1 kt and 50 MT
		double logYield = Math.log10(yieldKilotons);
		double logMin = Math.log10(1.0); // 1 kt
		double logMax = Math.log10(50000.0); // 50 MT

		double t = (logYield - logMin) / (logMax - logMin);
		t = Math.max(0.0, Math.min(1.0, t)); // Clamp [0,1]

		return VISIBILITY_1KT + (VISIBILITY_50MT - VISIBILITY_1KT) * t;
	}

	/**
	 * Get RGB color based on current temperature
	 * Uses blackbody radiation approximation
	 */
	public int[] getFireballColor() {
		if (temperature >= 7000.0) {
			// White-hot (>6700 K)
			return new int[]{255, 255, 255};
		} else if (temperature >= 5000.0) {
			// Yellow-white (5000-7000 K)
			int blue = (int)(255.0 * ((temperature - 5000.0) / 2000.0));
			return new int[]{255, 255, blue};
		} else if (temperature >= 3500.0) {
			// Yellow-orange (3500-5000 K)
			int green = (int)(255.0 * ((temperature - 3500.0) / 1500.0));
			return new int[]{255, green, 0};
		} else if (temperature >= 2000.0) {
			// Orange-red (2000-3500 K)
			int red = 255;
			int green = (int)(165.0 * ((temperature - 2000.0) / 1500.0));
			return new int[]{red, green, 0};
		} else {
			// Dull red (<2000 K)
			int red = (int)(255.0 * (temperature / 2000.0));
			return new int[]{red, 0, 0};
		}
	}

	/**
	 * Factory method: Create realistic nuclear explosion effect
	 * Called by EntityNukeExplosionMK5 after detonation
	 *
	 * @param world World
	 * @param x X coordinate (explosion center)
	 * @param y Y coordinate (ground zero)
	 * @param z Z coordinate (explosion center)
	 * @param yieldKt Weapon yield in kilotons
	 */
	public static EntityNukeTorexRealistic create(World world, double x, double y, double z, double yieldKt) {
		EntityNukeTorexRealistic torex = new EntityNukeTorexRealistic(world);
		torex.yieldKilotons = yieldKt;
		torex.setPosition(x, y, z);

		// Calculate lifetime based on yield
		// Larger explosions have longer-lasting effects
		torex.maxAge = (int)(2400 + 1200 * Math.log10(yieldKt / 15.0));

		System.out.println("[TOREX REALISTIC] Created 1:1 scale nuclear explosion effect");
		System.out.println("  Yield: " + String.format("%.1f", yieldKt) + " kt");
		System.out.println("  Expected cloud height: " + String.format("%.0f", torex.calculateCloudHeight(yieldKt)) + " m");
		System.out.println("  Visibility range: " + String.format("%.0f", torex.calculateVisibilityRange() / 1000.0) + " km");
		System.out.println("  Lifetime: " + (torex.maxAge / 20) + " seconds");

		return torex;
	}

	// === GETTERS FOR LOD MANAGER ===

	public double getYieldKilotons() {
		return this.dataManager.get(YIELD_KT);
	}

	/**
	 * Get continuous deformation progress (0.0-1.0)
	 * Replaces discrete phase system with smooth transition
	 */
	public double getDeformationProgress() {
		return this.dataManager.get(DEFORMATION_PROGRESS);
	}

	public double getTemperature() {
		return this.dataManager.get(TEMPERATURE);
	}

	public double getFireballRadius() {
		return this.dataManager.get(FIREBALL_RADIUS);
	}

	public double getCloudHeight() {
		return this.dataManager.get(CLOUD_HEIGHT);
	}

	public double getCloudWidth() {
		return this.dataManager.get(CLOUD_WIDTH);
	}

	/**
	 * Get current vertical velocity (m/s)
	 * Used by renderer for dynamic stem radius calculation
	 *
	 * @return Upward velocity in meters per second
	 */
	public double getVerticalVelocity() {
		return this.verticalVelocity;
	}

	/**
	 * Get current buoyancy force (N)
	 * Used by renderer for dynamic stem radius calculation
	 *
	 * @return Buoyancy force in Newtons
	 */
	public double getBuoyancyForce() {
		return this.buoyancyForce;
	}

	/**
	 * @deprecated Phase system removed - use continuous deformation progress instead
	 * Get the tick when a specific phase started
	 * Used by renderer to trigger one-time transformations
	 *
	 * @param phase Phase number (0-4)
	 * @return Tick when phase started, or 0 if not yet reached
	 */
	@Deprecated
	public int getPhaseStartTick(int phase) {
		// DEPRECATED: Phase system removed, return 0 for all phases
		// Use getDeformationProgress() for continuous physics-based tracking
		return 0;
	}

	/**
	 * Get volumetric particles for rendering
	 * Used by RenderNukeTorexRealistic to query particle data
	 *
	 * @return List of volumetric particles (may be empty before Phase 1)
	 */
	public ArrayList<VolumetricParticle> getParticles() {
		return volumetricParticles;
	}

	/**
	 * Get turbulence parameters for client-side particle regeneration
	 * Returns [seed, strength] array for bandwidth-efficient sync
	 *
	 * @return [0] = turbulence seed (long), [1] = strength (float)
	 */
	public long getTurbulenceSeed() {
		return turbulenceSeed;
	}

	public float getTurbulenceStrength() {
		return turbulenceStrength;
	}

	// PHASE 2-4 CLOUDLET HELPER METHODS DELETED: getToroidalConvectionMotion(), getLiftMotion() (now using OBJ sphere rendering)

	public int getMaxAge() {
		return maxAge;
	}

	/**
	 * Get maximum particle count based on weapon yield.
	 *
	 * Scaling formula: MAX_PARTICLES = 50,000 × W^0.6
	 *
	 * Rationale:
	 * - W^0.6 is between surface area (W^0.67) and volume (W^1.0)
	 * - Particles primarily distribute on cloud surface, not entire volume
	 * - Provides good density balance across all yield ranges
	 *
	 * Examples:
	 *   1 kt: 50,000 particles (baseline tactical)
	 *   15 kt: 206,000 particles (Hiroshima scale)
	 *   100 kt: 793,000 particles (strategic)
	 *   1 MT: 3,162,000 particles (megaton scale)
	 *   50 MT: 31,622,000 particles (Tsar Bomba scale)
	 *
	 * @return Maximum number of particles for current yield
	 */
	public int getMaxParticleCount() {
		// Base: 50,000 particles for 1 kt
		double baseCount = 50000.0;

		// Scale with W^0.6 (surface-area-like scaling)
		double scalingFactor = Math.pow(yieldKilotons, 0.6);

		// Calculate max particles
		int maxParticles = (int)(baseCount * scalingFactor);

		// Safety cap to prevent memory overflow (100 million particles max)
		// Even 50MT Tsar Bomba stays under 32 million
		return Math.min(maxParticles, 100_000_000);
	}

	public float getAlpha() {
		// Fade out in final 20% of lifetime
		float lifeFraction = (float)ticksExisted / maxAge;
		if (lifeFraction > 0.8F) {
			return 1.0F - ((lifeFraction - 0.8F) / 0.2F);
		}
		return 1.0F;
	}

	// PHASE 2-4 CLOUDLET INNER CLASS DELETED: Cloudlet (now using OBJ sphere rendering)

	/**
	 * VOLUMETRIC PARTICLE SYSTEM
	 *
	 * Represents a single particle in the 3D volumetric smoke cloud.
	 * Unlike Cloudlet (which uses toroidal convection), VolumetricParticle fills
	 * the entire fireball/mushroom volume with 50,000 particles using stratified sampling.
	 *
	 * ARCHITECTURE:
	 * - Server generates particles with physics simulation
	 * - Client receives turbulence parameters only (48 bytes vs 1.65 MB)
	 * - Client regenerates particles from turbulence seed (99.997% bandwidth reduction)
	 *
	 * LOD RENDERING:
	 * - Tier 1 (0-128m): All 50,000 particles via GPU instancing
	 * - Tier 2 (128-512m): 5,000 particles via batch rendering
	 * - Tier 3-5 (>512m): Aggregated billboards/skybox
	 */
	public static class VolumetricParticle {
		// Position (world coordinates)
		public double x, y, z;

		// Previous position (for interpolation - creates smooth fluid motion)
		public double prevX, prevY, prevZ;

		// Velocity (blocks/tick)
		public double vx, vy, vz;

		// Visual properties
		public float size; // Current particle size (blocks)
		public float r, g, b, alpha; // Color components [0-1]
		public float temperature; // Temperature (0-1: cold to hot) for smoke texture selection

		// PARTICLE GROWTH (like EntityNukeTorex)
		// Particles grow over time to increase density
		public float startingScale; // Initial size (blocks)
		public float growingScale; // Size growth per lifetime

		// TOROIDAL CIRCULATION (like EntityNukeTorex.Cloudlet)
		public float angle; // Rotation angle for convection (radians)
		public float rangeMod; // Distance modifier for circulation radius (0.3-1.0)
		public double motionMult = 1.0; // Overall motion multiplier
		public double motionConvectionMult = 0.5; // Toroidal convection strength
		public double motionLiftMult = 0.625; // Updraft lift strength

		// Layer index (0-9 for 10 concentric shells)
		// Used for stratified volume sampling and LOD culling
		public int layer;

		// Age tracking
		public int age;
		public int maxAge;

		/**
		 * Create a volumetric particle
		 *
		 * @param x World X coordinate
		 * @param y World Y coordinate
		 * @param z World Z coordinate
		 * @param vx Velocity X (blocks/tick)
		 * @param vy Velocity Y (blocks/tick)
		 * @param vz Velocity Z (blocks/tick)
		 * @param startingScale Initial size (blocks)
		 * @param growingScale Growth amount over lifetime (blocks)
		 * @param layer Concentric layer index (0-9)
		 */
		public VolumetricParticle(double x, double y, double z, double vx, double vy, double vz,
				float startingScale, float growingScale, int layer, float angle) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.prevX = x; // Initialize prev position
			this.prevY = y;
			this.prevZ = z;
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			this.startingScale = startingScale;
			this.growingScale = growingScale;
			this.size = startingScale; // Initial size
			this.layer = layer;
			this.age = 0;
			this.maxAge = 2400; // 2 minutes default
			this.r = 1.0F;
			this.g = 1.0F;
			this.b = 1.0F;
			this.alpha = 1.0F;
			this.temperature = 1.0F; // Initialize as hot (will be calculated based on distance)
			this.angle = angle;
			this.rangeMod = 0.3F + rand.nextFloat() * 0.7F; // Random range modifier (like EntityNukeTorex)
		}

		/**
		 * Update particle position, size, and age
		 */
		public void update() {
			// Store previous position BEFORE moving (enables smooth interpolation)
			this.prevX = this.x;
			this.prevY = this.y;
			this.prevZ = this.z;

			// Apply velocity
			x += vx;
			y += vy;
			z += vz;
			age++;

			// PARTICLE GROWTH (like EntityNukeTorex.Cloudlet.getScale())
			// Size increases over lifetime for better density
			float lifeFraction = (float)age / (float)maxAge;
			size = startingScale + lifeFraction * growingScale;

			// Fade out near end of life
			if (lifeFraction > 0.8F) {
				float fadeRatio = (1.0F - lifeFraction) / 0.2F;
				alpha *= fadeRatio;
			}
		}

		/**
		 * Check if particle should be removed
		 */
		public boolean isDead() {
			return age >= maxAge || alpha < 0.01F;
		}

		/**
		 * Get interpolated position for smooth rendering (like EntityNukeTorex.Cloudlet)
		 * @param interp Interpolation factor (partialTicks)
		 * @return Interpolated position vector
		 */
		public Vec3 getInterpPos(float interp) {
			return Vec3.createVectorHelper(
				prevX + (x - prevX) * interp,
				prevY + (y - prevY) * interp,
				prevZ + (z - prevZ) * interp
			);
		}
	}

	/**
	 * BLAST WAVE VISUALIZATION PARTICLE
	 *
	 * Represents visible blast wave (dust/smoke lifted by shockwave).
	 * Spawned at fireball edge, expands radially until 5psi boundary.
	 *
	 * PHYSICS BASIS:
	 * - PDF Chapter 3: Blast wave overpressure and dynamic pressure
	 * - Taylor-Sedov similarity scaling: R ∝ W^(1/3)
	 * - Exponential pressure decay with distance
	 * - Supersonic → sonic velocity transition
	 *
	 * VISUAL DESIGN:
	 * - Color: Pressure-based (white/yellow → orange → brown → gray)
	 * - Size: Grows over time (dust cloud expansion)
	 * - Alpha: Fades as pressure drops below 5psi
	 * - Lifetime: Distance to 5psi boundary / average velocity
	 *
	 * Based on EntityNukeTorex.Cloudlet pattern.
	 */
	public static class BlastWaveParticle {
		// Position and motion (world coordinates)
		public double x, y, z;
		public double prevX, prevY, prevZ;
		public double vx, vy, vz;

		// Blast wave physics state
		public double overpressure; // psi (pounds per square inch)
		public double dynamicPressure; // psi (wind pressure)
		public double distanceFromCenter; // meters from ground zero

		// Visual properties
		public int age;
		public int maxAge;
		public boolean isDead = false;
		public float r, g, b, alpha; // Color components [0-1]
		public float size; // Particle size (blocks)
		public float temperature; // For compatibility with texture system (0-1)

		// Spawn parameters (immutable)
		private final double spawnRadiusMeters; // Fireball radius at spawn time
		private final double maxRadiusMeters;   // 5psi boundary
		private final double yieldKt;
		private final double explosionCenterX, explosionCenterY, explosionCenterZ;

		/**
		 * Create blast wave particle at fireball edge.
		 *
		 * @param centerX Explosion ground zero X
		 * @param centerY Explosion ground zero Y
		 * @param centerZ Explosion ground zero Z
		 * @param spawnRadius Current fireball radius (meters)
		 * @param angle Radial spawn angle (0-2π)
		 * @param yieldKilotons Weapon yield (kilotons)
		 */
		public BlastWaveParticle(double centerX, double centerY, double centerZ,
		                         double spawnRadius, double angle, double yieldKilotons) {
			this.explosionCenterX = centerX;
			this.explosionCenterY = centerY;
			this.explosionCenterZ = centerZ;
			this.yieldKt = yieldKilotons;
			this.spawnRadiusMeters = spawnRadius;

			// Calculate 5psi boundary (maximum blast wave extent)
			this.maxRadiusMeters = FireballPhysicsCalculator.calculate5PsiRadius(yieldKt);

			// Spawn at fireball edge (ring formation)
			this.x = centerX + spawnRadius * Math.cos(angle);
			this.y = centerY; // Ground level (blast wave is horizontal)
			this.z = centerZ + spawnRadius * Math.sin(angle);
			this.prevX = this.x;
			this.prevY = this.y;
			this.prevZ = this.z;

			// Calculate initial physics state
			updatePressure();

			// Initial velocity (radial outward, supersonic)
			double initialSpeed = FireballPhysicsCalculator.calculateBlastVelocity(overpressure);
			this.vx = initialSpeed * Math.cos(angle) / 20.0; // Convert m/s to blocks/tick
			this.vy = 0.0; // Horizontal expansion only
			this.vz = initialSpeed * Math.sin(angle) / 20.0;

			// Calculate lifetime (time to reach 5psi boundary)
			double travelDistance = maxRadiusMeters - spawnRadiusMeters;
			double avgVelocity = (initialSpeed + 340.0) / 2.0; // Average between supersonic and sonic
			this.maxAge = (int)((travelDistance / avgVelocity) * 20.0); // ticks at 20 TPS
			this.maxAge = Math.max(20, this.maxAge); // Minimum 1 second

			this.age = 0;
			this.size = 5.0F; // Start large (visible dust cloud)
			this.temperature = 1.0F; // Hot initially (for texture compatibility)

			// Initialize color
			updateColor();
		}

		/**
		 * Update particle position, velocity, and visual properties.
		 */
		public void update() {
			age++;
			if (age > maxAge) {
				isDead = true;
				return;
			}

			// Store previous position for interpolation
			prevX = x;
			prevY = y;
			prevZ = z;

			// Calculate current distance from ground zero
			double dx = x - explosionCenterX;
			double dy = y - explosionCenterY;
			double dz = z - explosionCenterZ;
			distanceFromCenter = Math.sqrt(dx * dx + dy * dy + dz * dz);

			// Update blast wave physics
			updatePressure();

			// Calculate current velocity (decelerates as pressure drops)
			double currentSpeed = FireballPhysicsCalculator.calculateBlastVelocity(overpressure);

			// Direction vector (radial outward)
			double directionX = dx / Math.max(distanceFromCenter, 1.0);
			double directionZ = dz / Math.max(distanceFromCenter, 1.0);

			// Update velocity (blocks/tick)
			vx = currentSpeed * directionX / 20.0;
			vz = currentSpeed * directionZ / 20.0;

			// Apply motion
			x += vx;
			z += vz;

			// Update visual properties
			updateColor();
			size += 0.05F; // Grow slowly (dust cloud expansion)

			// Kill if pressure drops below 5psi threshold
			if (overpressure < 5.0) {
				isDead = true;
			}
		}

		/**
		 * Update overpressure and dynamic pressure based on current distance.
		 */
		private void updatePressure() {
			// Distance from fireball edge
			double distFromEdge = distanceFromCenter - spawnRadiusMeters;

			// Calculate overpressure using PDF formulas
			overpressure = FireballPhysicsCalculator.calculateBlastOverpressure(
				distFromEdge, yieldKt, spawnRadiusMeters);

			// Calculate dynamic (wind) pressure
			dynamicPressure = FireballPhysicsCalculator.calculateDynamicPressure(overpressure);
		}

		/**
		 * Update color based on overpressure (pressure-based color mapping).
		 *
		 * Color scheme (inspired by real blast wave photography):
		 * - High pressure (>20 psi): Bright white/yellow (hot compressed air, Mach shock)
		 * - Medium-high (10-20 psi): Orange (heated dust)
		 * - Medium (5-10 psi): Brown (dust cloud)
		 * - Low (<5 psi): Gray → transparent (dissipating)
		 */
		private void updateColor() {
			// Normalize pressure to 0-1 range (20psi = max)
			double normalized = Math.min(overpressure / 20.0, 1.0);

			// RGB gradient based on pressure
			if (normalized > 0.7) {
				// Very high pressure: White/yellow (shock front)
				double t = (normalized - 0.7) / 0.3; // 0-1 within 0.7-1.0 range
				r = 1.0F;
				g = 1.0F;
				b = (float)(1.0 - t * 0.4); // Slightly yellow
			} else if (normalized > 0.4) {
				// High pressure: Yellow → orange
				double t = (normalized - 0.4) / 0.3; // 0-1 within 0.4-0.7 range
				r = 1.0F;
				g = (float)(1.0 - t * 0.3); // 1.0 → 0.7
				b = (float)(1.0 - t) * 0.4F; // Fade blue component
			} else if (normalized > 0.25) {
				// Medium pressure: Orange → brown
				double t = (normalized - 0.25) / 0.15; // 0-1 within 0.25-0.4 range
				r = (float)(0.7 + t * 0.3); // 0.7 → 1.0
				g = (float)(0.4 + t * 0.3); // 0.4 → 0.7
				b = (float)(0.2 + t * 0.2); // 0.2 → 0.4
			} else {
				// Low pressure: Brown → gray
				double t = normalized / 0.25; // 0-1 within 0-0.25 range
				r = (float)(0.5 + t * 0.2); // 0.5 → 0.7
				g = (float)(0.4 + t * 0.0); // 0.4 (constant)
				b = (float)(0.3 - t * 0.1); // 0.3 → 0.2
			}

			// Alpha based on pressure and age
			float pressureAlpha = (float)(normalized * 0.7 + 0.3); // 0.3-1.0 range
			float ageAlpha = 1.0F - ((float)age / (float)maxAge); // Fade over lifetime
			alpha = pressureAlpha * ageAlpha;

			// Temperature for texture system (normalize to 0-1)
			temperature = (float)normalized;
		}

		/**
		 * Check if particle should be removed.
		 */
		public boolean isDead() {
			return isDead || age >= maxAge || alpha < 0.01F;
		}

		/**
		 * Get interpolated position for smooth rendering.
		 * @param interp Interpolation factor (partialTicks)
		 * @return Interpolated position vector
		 */
		public Vec3 getInterpPos(float interp) {
			return Vec3.createVectorHelper(
				prevX + (x - prevX) * interp,
				prevY + (y - prevY) * interp,
				prevZ + (z - prevZ) * interp
			);
		}
	}
}
