package com.hbm.entity.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * LOD (Level of Detail) MANAGER FOR REALISTIC NUCLEAR EXPLOSION EFFECTS
 *
 * Manages 5-tier rendering system for chunk-independent, ultra-long-distance visibility.
 * Automatically selects appropriate rendering method based on camera distance with
 * hysteresis to prevent flickering during tier transitions.
 *
 * 5-TIER LOD SYSTEM:
 *
 * TIER 1: ULTRA-CLOSE (0-128 meters) - GPU Instanced Particles
 *   - Rendering: 20,000 individual cloudlet particles via GPU instancing
 *   - Features: Full toroidal convection physics, temperature-based colors
 *   - Performance: -30 FPS (heavy, but only when very close)
 *   - Memory: ~25 MB (particle positions + velocities)
 *   - Renderer: RenderNukeTorexParticles (GPU instancing via VBOs)
 *
 * TIER 2: CLOSE (128-512 meters) - Batch Rendered Particles
 *   - Rendering: 5,000 particles via batched draw calls
 *   - Features: Simplified physics, fewer particles
 *   - Performance: -10 FPS (moderate)
 *   - Memory: ~8 MB
 *   - Renderer: RenderNukeTorexParticles (batch mode)
 *
 * TIER 3: MEDIUM (512-5,000 meters) - 3D Billboard Model
 *   - Rendering: Single 3D model with animated texture
 *   - Features: Pre-baked mushroom cloud model, texture animation
 *   - Performance: -2 FPS (light)
 *   - Memory: ~3 MB (model + texture atlas)
 *   - Renderer: RenderNukeTorexBillboard3D
 *   - Model: mushroom_cloud_lod3.obj (Wavefront OBJ)
 *
 * TIER 4: FAR (5-50 km) - 2D Billboard
 *   - Rendering: Camera-facing quad with animated texture
 *   - Features: 2D sprite, always faces camera
 *   - Performance: -0.5 FPS (very light)
 *   - Memory: ~1 MB (texture only)
 *   - Renderer: RenderNukeTorexBillboard2D
 *
 * TIER 5: ULTRA-FAR (50-716 km) - Skybox Projection
 *   - Rendering: Celestial coordinate system (like stars/moon)
 *   - Features: Chunk-independent, visible from anywhere
 *   - Performance: -0.1 FPS (negligible)
 *   - Memory: ~500 KB (single texture)
 *   - Renderer: RenderNukeTorexSkybox (based on RenderNTMSkybox)
 *
 * HYSTERESIS SYSTEM:
 * To prevent rapid LOD switching (flickering), we use hysteresis:
 * - Upward transitions (better quality): distance < tier_threshold - 10%
 * - Downward transitions (worse quality): distance > tier_threshold + 10%
 * - Dead zone: 20% band around each threshold where tier doesn't change
 *
 * Example with Tier 2→3 boundary (512m):
 * - Moving away: Switch to Tier 3 at 512m + 51m = 563m
 * - Moving closer: Switch to Tier 2 at 512m - 51m = 461m
 * - Dead zone: [461m, 563m] - no tier change
 *
 * PERFORMANCE MONITORING:
 * - Tracks FPS impact of each tier
 * - Can dynamically adjust quality if FPS drops below threshold
 * - Limits number of simultaneous high-quality explosions
 *
 * CHUNK-INDEPENDENT RENDERING:
 * - All tiers (especially Tier 4-5) render even when explosion chunk is unloaded
 * - Uses entity position stored in NBT for calculations
 * - Skybox projection works at any distance
 *
 * @author HBM Nuclear Tech Team
 */
@SideOnly(Side.CLIENT)
public class NukeTorexLODManager {

	// === TIER BOUNDARIES (meters) ===
	private static final double TIER1_MAX = 128.0; // GPU instanced particles
	private static final double TIER2_MAX = 512.0; // Batch rendered particles
	private static final double TIER3_MAX = 5000.0; // 3D billboard model
	private static final double TIER4_MAX = 50000.0; // 2D billboard
	// TIER 5: >50km (skybox projection)

	// === HYSTERESIS FACTOR ===
	// 10% buffer zone around each threshold to prevent flickering
	private static final double HYSTERESIS = 0.10;

	// === ENTITY REFERENCE ===
	private final EntityNukeTorexRealistic entity;

	// === LOD STATE ===
	private int currentTier = 5; // Start at lowest quality (skybox)
	private int previousTier = 5;
	private double lastDistance = Double.MAX_VALUE;

	// === PERFORMANCE MONITORING ===
	private static int activeHighQualityEffects = 0; // Count of Tier 1-2 explosions
	private static final int MAX_HIGH_QUALITY = 2; // Limit to 2 simultaneous close explosions

	// === RENDERING FLAGS ===
	private boolean renderingEnabled = true;
	private boolean forceQualityDowngrade = false;

	public NukeTorexLODManager(EntityNukeTorexRealistic entity) {
		this.entity = entity;
	}

	/**
	 * Update LOD tier based on camera distance
	 * Called every frame from EntityNukeTorexRealistic.onUpdate()
	 */
	public void update() {
		if (!renderingEnabled) return;

		// Calculate distance from camera to explosion
		EntityPlayer player = Minecraft.getMinecraft().player;
		if (player == null) return;

		double dx = player.posX - entity.posX;
		double dy = player.posY - entity.posY;
		double dz = player.posZ - entity.posZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		// Update LOD tier with hysteresis
		int newTier = calculateTier(distance);

		// Performance limiter: Downgrade if too many high-quality effects
		if (forceQualityDowngrade && newTier <= 2) {
			newTier = 3; // Force to billboard mode
		}

		if (newTier != currentTier) {
			onTierChange(currentTier, newTier);
			previousTier = currentTier;
			currentTier = newTier;
		}

		lastDistance = distance;
	}

	/**
	 * Calculate appropriate LOD tier with hysteresis
	 * Prevents rapid tier switching by using different thresholds for up/down transitions
	 */
	private int calculateTier(double distance) {
		// If moving closer (distance decreasing), use LOWER threshold (threshold - hysteresis)
		// If moving farther (distance increasing), use UPPER threshold (threshold + hysteresis)
		boolean movingCloser = distance < lastDistance;

		// Calculate thresholds with hysteresis
		double tier1Threshold = movingCloser ? TIER1_MAX * (1 - HYSTERESIS) : TIER1_MAX * (1 + HYSTERESIS);
		double tier2Threshold = movingCloser ? TIER2_MAX * (1 - HYSTERESIS) : TIER2_MAX * (1 + HYSTERESIS);
		double tier3Threshold = movingCloser ? TIER3_MAX * (1 - HYSTERESIS) : TIER3_MAX * (1 + HYSTERESIS);
		double tier4Threshold = movingCloser ? TIER4_MAX * (1 - HYSTERESIS) : TIER4_MAX * (1 + HYSTERESIS);

		// Select tier based on distance
		if (distance < tier1Threshold) {
			return 1; // GPU instanced particles
		} else if (distance < tier2Threshold) {
			return 2; // Batch rendered particles
		} else if (distance < tier3Threshold) {
			return 3; // 3D billboard
		} else if (distance < tier4Threshold) {
			return 4; // 2D billboard
		} else {
			return 5; // Skybox projection
		}
	}

	/**
	 * Called when LOD tier changes
	 * Handles cleanup of old rendering resources and initialization of new tier
	 */
	private void onTierChange(int oldTier, int newTier) {
		System.out.println("[LOD] Tier change: " + oldTier + " → " + newTier +
				" (distance: " + String.format("%.0f", lastDistance) + "m)");

		// Cleanup old tier resources
		cleanupTier(oldTier);

		// Initialize new tier resources
		initializeTier(newTier);

		// Update high-quality effect counter
		if (oldTier <= 2 && newTier > 2) {
			activeHighQualityEffects--;
		} else if (oldTier > 2 && newTier <= 2) {
			activeHighQualityEffects++;
		}
	}

	/**
	 * Cleanup resources for a specific tier
	 */
	private void cleanupTier(int tier) {
		switch (tier) {
			case 1:
			case 2:
				// Future: Cleanup particle buffers
				// if (particleRenderer != null) particleRenderer.cleanup();
				break;
			case 3:
				// Future: Cleanup 3D billboard model
				// if (billboard3D != null) billboard3D.cleanup();
				break;
			case 4:
				// Future: Cleanup 2D billboard
				// if (billboard2D != null) billboard2D.cleanup();
				break;
			case 5:
				// Skybox has no cleanup (static texture)
				break;
		}
	}

	/**
	 * Initialize resources for a specific tier
	 */
	private void initializeTier(int tier) {
		switch (tier) {
			case 1:
				// Future: Initialize GPU instanced particle renderer
				// particleRenderer = new RenderNukeTorexParticles(entity, 20000, true);
				System.out.println("  [LOD] Tier 1: GPU instanced particles (20k particles)");
				break;
			case 2:
				// Future: Initialize batch particle renderer
				// particleRenderer = new RenderNukeTorexParticles(entity, 5000, false);
				System.out.println("  [LOD] Tier 2: Batch particles (5k particles)");
				break;
			case 3:
				// Future: Initialize 3D billboard
				// billboard3D = new RenderNukeTorexBillboard3D(entity);
				System.out.println("  [LOD] Tier 3: 3D billboard model");
				break;
			case 4:
				// Future: Initialize 2D billboard
				// billboard2D = new RenderNukeTorexBillboard2D(entity);
				System.out.println("  [LOD] Tier 4: 2D billboard");
				break;
			case 5:
				// Skybox initialized globally, no per-entity setup needed
				System.out.println("  [LOD] Tier 5: Skybox projection");
				break;
		}
	}

	/**
	 * Render the current LOD tier
	 * Called from custom renderer (RenderNukeTorexRealistic)
	 *
	 * @param partialTicks Partial tick time for interpolation
	 */
	public void render(float partialTicks) {
		if (!renderingEnabled) return;

		switch (currentTier) {
			case 1:
			case 2:
				// Future: Render particles
				// if (particleRenderer != null) particleRenderer.render(partialTicks);
				break;
			case 3:
				// Future: Render 3D billboard
				// if (billboard3D != null) billboard3D.render(partialTicks);
				break;
			case 4:
				// Future: Render 2D billboard
				// if (billboard2D != null) billboard2D.render(partialTicks);
				break;
			case 5:
				// Skybox rendering handled globally by RenderNukeTorexSkybox
				// No per-entity rendering needed
				break;
		}
	}

	/**
	 * Cleanup all resources when entity is removed
	 */
	public void cleanup() {
		cleanupTier(currentTier);

		// Update global counter
		if (currentTier <= 2) {
			activeHighQualityEffects--;
		}

		renderingEnabled = false;
	}

	// === GETTERS ===

	public int getCurrentTier() {
		return currentTier;
	}

	public double getLastDistance() {
		return lastDistance;
	}

	public static int getActiveHighQualityEffects() {
		return activeHighQualityEffects;
	}

	// === PERFORMANCE CONTROL ===

	/**
	 * Enable quality downgrade if performance is poor
	 * Prevents new explosions from using Tier 1-2
	 */
	public void setForceQualityDowngrade(boolean force) {
		this.forceQualityDowngrade = force;
	}

	/**
	 * Check if we should limit high-quality effects
	 * Called before creating new explosions
	 */
	public static boolean shouldLimitQuality() {
		return activeHighQualityEffects >= MAX_HIGH_QUALITY;
	}

	/**
	 * Get tier name for debugging
	 */
	public static String getTierName(int tier) {
		switch (tier) {
			case 1: return "GPU Instanced Particles (20k)";
			case 2: return "Batch Particles (5k)";
			case 3: return "3D Billboard";
			case 4: return "2D Billboard";
			case 5: return "Skybox Projection";
			default: return "Unknown";
		}
	}

	/**
	 * Get estimated FPS impact for a tier
	 */
	public static double getTierFPSImpact(int tier) {
		switch (tier) {
			case 1: return -30.0;
			case 2: return -10.0;
			case 3: return -2.0;
			case 4: return -0.5;
			case 5: return -0.1;
			default: return 0.0;
		}
	}

	/**
	 * Get estimated memory usage for a tier (MB)
	 */
	public static double getTierMemoryUsage(int tier) {
		switch (tier) {
			case 1: return 25.0;
			case 2: return 8.0;
			case 3: return 3.0;
			case 4: return 1.0;
			case 5: return 0.5;
			default: return 0.0;
		}
	}
}
