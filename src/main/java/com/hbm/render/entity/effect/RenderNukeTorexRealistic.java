package com.hbm.render.entity.effect;

import com.hbm.entity.effect.EntityNukeTorexRealistic;
import com.hbm.entity.effect.EntityNukeTorexRealistic.VolumetricParticle;
import com.hbm.lib.RefStrings;
import com.hbm.main.ModEventHandlerClient;
import com.hbm.physics.nuke.FireballPhysicsCalculator;
import com.hbm.physics.nuke.FireballPhysicsCalculator.PhaseGeometry;
import com.hbm.physics.nuke.FireballPhysicsCalculator.MushroomGeometry;
import com.hbm.render.amlfrom1710.AdvancedModelLoader;
import com.hbm.render.amlfrom1710.IModelCustom;
import com.hbm.render.amlfrom1710.Vec3;
import com.hbm.util.SimplexNoise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 5-TIER LOD NUCLEAR EXPLOSION RENDERER
 *
 * Physics-accurate rendering of nuclear explosion visual effects with
 * Level of Detail (LOD) system for visibility from 0m to 716km.
 *
 * Based on "Effects of Nuclear Weapons 1977" (Glasstone & Dolan)
 *
 * LOD TIER SYSTEM:
 * - Tier 1 (0-128m): GPU Instanced Particles (20,000 particles, ~30 FPS)
 * - Tier 2 (128-512m): Batch Rendered Particles (5,000 particles, ~10 FPS)
 * - Tier 3 (512m-5km): 3D Billboard Model (~2 FPS)
 * - Tier 4 (5-50km): 2D Billboard (~0.5 FPS)
 * - Tier 5 (50-716km): Skybox Projection (~0.1 FPS)
 *
 * ARCHITECTURE:
 * - EntityNukeTorexRealistic: Physics simulation (server-side)
 * - RenderNukeTorexRealistic: Visual rendering only (client-side)
 * - No particle spawning in renderer - queries entity.getParticles()
 * - No physics calculations - uses entity getters for all state
 *
 * PHASE 1 IMPLEMENTATION (Current):
 * - Tier 4: 2D billboard (simplest tier for rapid visual confirmation)
 * - Stubs for other tiers (will be implemented in Phase 2-5)
 *
 * @author HBM Nuclear Tech Team
 */
public class RenderNukeTorexRealistic extends Render<EntityNukeTorexRealistic> {

	public static final IRenderFactory<EntityNukeTorexRealistic> FACTORY = RenderNukeTorexRealistic::new;

	// OBJ Models
	private IModelCustom sphereModel;   // Mushroom cap (deformed sphere)
	private IModelCustom cylinderModel; // Mushroom stem (stretched cylinder)
	private IModelCustom ringModel;     // Toroidal flow visualization
	private static final ResourceLocation SPHERE_MODEL =
		new ResourceLocation(RefStrings.MODID, "models/sphere_uv.obj");
	private static final ResourceLocation CYLINDER_MODEL =
		new ResourceLocation(RefStrings.MODID, "models/cylinder.obj");
	private static final ResourceLocation RING_MODEL =
		new ResourceLocation(RefStrings.MODID, "models/ring.obj");

	// Textures - Smoke textures (temperature-based selection)
	// d_smoke1 = hottest (white-hot), d_smoke8 = coldest (dark gray)
	private static final ResourceLocation[] SMOKE_TEXTURES = new ResourceLocation[] {
		new ResourceLocation("hbm:textures/particle/d_smoke1.png"),
		new ResourceLocation("hbm:textures/particle/d_smoke2.png"),
		new ResourceLocation("hbm:textures/particle/d_smoke3.png"),
		new ResourceLocation("hbm:textures/particle/d_smoke4.png"),
		new ResourceLocation("hbm:textures/particle/d_smoke5.png"),
		new ResourceLocation("hbm:textures/particle/d_smoke6.png"),
		new ResourceLocation("hbm:textures/particle/d_smoke7.png"),
		new ResourceLocation("hbm:textures/particle/d_smoke8.png")
	};
	private static final ResourceLocation PARTICLE_TEXTURE = new ResourceLocation("hbm:textures/particle/particle_base.png");
	private static final ResourceLocation BILLBOARD_TEXTURE = new ResourceLocation("hbm:textures/misc/nuke_billboard.png");
	private static final ResourceLocation FIREBALL_TEXTURE = new ResourceLocation("hbm:textures/misc/white.png");

	// LOD tier boundaries (from EntityNukeTorexRealistic)
	private static final double LOD_TIER1_RANGE = 128.0;   // 0-128m: GPU instanced particles
	private static final double LOD_TIER2_RANGE = 512.0;   // 128-512m: Batch rendered particles
	private static final double LOD_TIER3_RANGE = 5000.0;  // 512m-5km: 3D billboard model
	private static final double LOD_TIER4_RANGE = 50000.0; // 5-50km: 2D billboard

	// Procedural temperature gradient texture cache
	// Key: Temperature in 100K increments (e.g., 5000, 5100, 5200, ...)
	// Value: ResourceLocation for the generated gradient texture
	private Map<Integer, ResourceLocation> temperatureTextureCache = new HashMap<>();

	protected RenderNukeTorexRealistic(RenderManager renderManager) {
		super(renderManager);
		// Load OBJ models for mushroom cloud rendering
		sphereModel = AdvancedModelLoader.loadModel(SPHERE_MODEL);     // Cap (deformed sphere)
		cylinderModel = AdvancedModelLoader.loadModel(CYLINDER_MODEL); // Stem (stretched cylinder)
		ringModel = AdvancedModelLoader.loadModel(RING_MODEL);         // Toroidal flow
	}

	@Override
	public void doRender(EntityNukeTorexRealistic entity, double x, double y, double z,
			float entityYaw, float partialTicks) {

		// Get camera distance
		EntityPlayer player = Minecraft.getMinecraft().player;
		if (player == null) return;

		double dx = entity.posX - player.posX;
		double dy = entity.posY - player.posY;
		double dz = entity.posZ - player.posZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		// Trigger flash white-out and camera shake based on yield and player distance
		triggerFlashAndShake(entity, distance);

		// COMPREHENSIVE RENDER DEBUG LOGGING
		if (entity.ticksExisted % 20 == 0 || entity.ticksExisted < 10) {
			int particleCount = entity.volumetricParticles != null ? entity.volumetricParticles.size() : -1;
			System.out.println("#################### RENDER DEBUG ####################");
			System.out.println("[RENDER] Tick=" + entity.ticksExisted);
			System.out.println("[RENDER] Distance=" + String.format("%.1f", distance) + " m");
			System.out.println("[RENDER] Progress=" + String.format("%.3f", entity.getDeformationProgress()));
			System.out.println("[RENDER] Fireball Radius=" + String.format("%.2f", entity.getFireballRadius()) + " m");
			System.out.println("[RENDER] Cloud Height=" + String.format("%.2f", entity.getCloudHeight()) + " m");
			System.out.println("[RENDER] Cloud Width=" + String.format("%.2f", entity.getCloudWidth()) + " m");
			System.out.println("[RENDER] Particles=" + particleCount + " / " + entity.getMaxParticleCount());
			System.out.println("[RENDER] Entity pos=(" + String.format("%.1f", entity.posX) + ", " +
				String.format("%.1f", entity.posY) + ", " + String.format("%.1f", entity.posZ) + ")");
			System.out.println("[RENDER] Player pos=(" + String.format("%.1f", player.posX) + ", " +
				String.format("%.1f", player.posY) + ", " + String.format("%.1f", player.posZ) + ")");
			System.out.println("######################################################");
		}

		// Select and execute LOD tier
		String selectedTier;
		if (distance < LOD_TIER1_RANGE) {
			selectedTier = "Tier 1 (OBJ Sphere)";
			renderTier1_GPUInstanced(entity, x, y, z, partialTicks, distance);
		} else if (distance < LOD_TIER2_RANGE) {
			selectedTier = "Tier 2 (OBJ Sphere)";
			renderTier2_OBJSphere(entity, x, y, z, partialTicks, distance);
		} else if (distance < LOD_TIER3_RANGE) {
			selectedTier = "Tier 3 (3D Billboard)";
			renderTier3_3DBillboard(entity, x, y, z, partialTicks, distance);
		} else if (distance < LOD_TIER4_RANGE) {
			selectedTier = "Tier 4 (2D Billboard)";
			renderTier4_2DBillboard(entity, x, y, z, partialTicks, distance);
		} else {
			selectedTier = "Tier 5 (Skybox)";
			renderTier5_Skybox(entity, x, y, z, partialTicks, distance);
		}

		if (entity.ticksExisted % 20 == 0 || entity.ticksExisted < 10) {
			System.out.println("[RENDER] Selected LOD Tier: " + selectedTier);
		}

		// === BLAST WAVE PARTICLE RENDERING ===
		// Render blast wave particles (independent of LOD tier)
		// These represent the visible shockwave propagating outward
		if (!entity.blastWaveParticles.isEmpty() && distance < LOD_TIER4_RANGE) {
			renderBlastWaveParticles(entity, x, y, z, partialTicks, distance);
		}

		// Debug text (only at close range)
		if (distance < 500) {
			renderDebugInfo(entity, x, y, z, distance);
		}
	}

	/**
	 * TIER 1: OBJ SPHERE (0-128m)
	 *
	 * Renders fireball using OBJ sphere model with physics-based scaling.
	 * Same as Tier 2 for Phase 1 implementation.
	 */
	private void renderTier1_GPUInstanced(EntityNukeTorexRealistic entity, double x, double y, double z,
			float partialTicks, double distance) {
		// Use same OBJ sphere rendering as Tier 2
		renderTier2_OBJSphere(entity, x, y, z, partialTicks, distance);
	}

	/**
	 * TIER 2: BATCH RENDERED PARTICLES (128-512m)
	 *
	 * PHASE 2 IMPLEMENTATION - PRIMARY RENDERING MODE
	 *
	 * Renders 5,000 particles (every 10th particle) using BufferBuilder batching.
	 * This is the primary rendering mode for most gameplay scenarios.
	 *
	 * ALGORITHM:
	 * 1. Query particles from entity (50,000 volumetric particles)
	 * 2. LOD culling: Select every 10th particle (5,000 total)
	 * 3. Depth sort: Back-to-front for proper alpha blending
	 * 4. Batch render: Camera-facing billboard quads via BufferBuilder
	 * 5. Color based on temperature and phase
	 *
	 * PERFORMANCE: ~10 FPS impact (5,000 quads = 20,000 vertices)
	 */
	private void renderTier2_BatchParticles(EntityNukeTorexRealistic entity, double x, double y, double z,
			float partialTicks, double distance) {
		// PROGRESS-AWARE RENDERING: Early stage should only show fireball (no particles)
		double progress = entity.getDeformationProgress();
		int phase = (progress < 0.1) ? 0 : (progress < 0.3) ? 1 : 2;

		// Query particles from entity
		ArrayList<VolumetricParticle> particles = entity.getParticles();

		// DEBUG: Log particle list state
		if (entity.ticksExisted % 20 == 0 || entity.ticksExisted < 10) {
			System.out.println("========== TIER 2 PARTICLE RENDER ==========");
			System.out.println("[RENDER TIER2] Phase=" + phase);
			System.out.println("[RENDER TIER2] Particles list size: " + particles.size());
			System.out.println("[RENDER TIER2] isEmpty: " + particles.isEmpty());
			System.out.println("[RENDER TIER2] Phase check (>=2): " + (phase >= 2));
			System.out.println("[RENDER TIER2] Tick: " + entity.ticksExisted);
		}

		// MODIFIED: Now render particles from Phase 1 onwards (volumetric fireball)
		// Phase 0: Initial flash only (no particles, use billboard)
		// Phase 1+: Render particles to create volumetric fireball/mushroom cloud
		if (phase < 1 || particles.isEmpty()) {
			// Phase 0 or no particles yet - render pure flash (billboard)
			if (phase < 1) {
				System.out.println("[RENDER TIER2] Phase " + phase + " - Rendering INITIAL FLASH (no particles) - using billboard");
			} else {
				System.out.println("[RENDER TIER2] FALLING BACK TO BILLBOARD - particles list is empty!");
			}
			System.out.println("============================================");
			renderTier4_2DBillboard(entity, x, y, z, partialTicks, distance);
			return;
		}

		System.out.println("[RENDER TIER2] Rendering " + particles.size() + " volumetric particles");
		System.out.println("============================================");

		// Get player for distance calculations
		EntityPlayer player = Minecraft.getMinecraft().player;
		if (player == null) return;

		// LOD CULLING: Select every 10th particle for Tier 2 (50,000 → 5,000)
		ArrayList<VolumetricParticle> culledParticles = new ArrayList<>();
		for (int i = 0; i < particles.size(); i += 10) {
			culledParticles.add(particles.get(i));
		}

		// DEPTH SORTING: Sort back-to-front for alpha blending
		culledParticles.sort((p1, p2) -> {
			double d1 = player.getDistanceSq(p1.x, p1.y, p1.z);
			double d2 = player.getDistanceSq(p2.x, p2.y, p2.z);
			return Double.compare(d2, d1); // Reverse order (farthest first)
		});

		// Get explosion properties for color calculation
		// (phase already defined at line 160)
		double temperature = entity.getTemperature();

		// Setup OpenGL state
		GL11.glPushMatrix();
		GL11.glTranslated(x, y, z);

		GlStateManager.disableLighting();
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
		GlStateManager.enableTexture2D();
		GlStateManager.depthMask(false);
		GlStateManager.disableCull();

		// Get ActiveRenderInfo rotation matrices (like EntityNukeTorex.RenderTorex)
		// This creates proper billboard rotation instead of simple camera-facing
		float f1 = ActiveRenderInfo.getRotationX();
		float f2 = ActiveRenderInfo.getRotationZ();
		float f3 = ActiveRenderInfo.getRotationYZ();
		float f4 = ActiveRenderInfo.getRotationXY();
		float f5 = ActiveRenderInfo.getRotationXZ();

		// GROUP PARTICLES BY TEXTURE (temperature-based)
		// OpenGL requires separate batches for different textures
		ArrayList<ArrayList<VolumetricParticle>> particlesByTexture = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			particlesByTexture.add(new ArrayList<>());
		}

		for (VolumetricParticle p : culledParticles) {
			if (p.alpha < 0.01F) continue; // Skip fully transparent particles

			// Select texture index based on temperature (0-1 range)
			// temperature 1.0 (hot) → smoke1 (index 0)
			// temperature 0.0 (cold) → smoke8 (index 7)
			int textureIndex = (int)((1.0F - p.temperature) * 7.999F);
			textureIndex = Math.max(0, Math.min(7, textureIndex));
			particlesByTexture.get(textureIndex).add(p);
		}

		// BATCH RENDERING: Render each texture group separately
		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();

		for (int texIndex = 0; texIndex < 8; texIndex++) {
			ArrayList<VolumetricParticle> particlesForTexture = particlesByTexture.get(texIndex);
			if (particlesForTexture.isEmpty()) continue;

			// Bind smoke texture for this temperature range
			this.bindTexture(SMOKE_TEXTURES[texIndex]);

			buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

			// Render all particles for this texture
			for (VolumetricParticle p : particlesForTexture) {
				// CRITICAL FIX: Use interpolated position for smooth fluid motion
				Vec3 interpPos = p.getInterpPos(partialTicks);

				// Particle position relative to entity
				double px = interpPos.xCoord - entity.posX;
				double py = interpPos.yCoord - entity.posY;
				double pz = interpPos.zCoord - entity.posZ;

				double scale = p.size;

				// Calculate billboard vertices using ActiveRenderInfo rotation matrices
				// This creates natural varied orientations instead of all particles facing camera
				double x1 = (double)(-f1 * scale - f3 * scale);
				double y1 = (double)(-f5 * scale);
				double z1 = (double)(-f2 * scale - f4 * scale);

				double x2 = (double)(-f1 * scale + f3 * scale);
				double y2 = (double)(f5 * scale);
				double z2 = (double)(-f2 * scale + f4 * scale);

				double x3 = (double)(f1 * scale + f3 * scale);
				double y3 = (double)(f5 * scale);
				double z3 = (double)(f2 * scale + f4 * scale);

				double x4 = (double)(f1 * scale - f3 * scale);
				double y4 = (double)(-f5 * scale);
				double z4 = (double)(f2 * scale - f4 * scale);

				// Update particle color based on current temperature/phase
				float[] color = getTemperatureColor(temperature, phase);
				p.r = color[0];
				p.g = color[1];
				p.b = color[2];

				// Draw quad (4 vertices)
				buf.pos(px + x1, py + y1, pz + z1).tex(0, 0).color(p.r, p.g, p.b, p.alpha).endVertex();
				buf.pos(px + x2, py + y2, pz + z2).tex(0, 1).color(p.r, p.g, p.b, p.alpha).endVertex();
				buf.pos(px + x3, py + y3, pz + z3).tex(1, 1).color(p.r, p.g, p.b, p.alpha).endVertex();
				buf.pos(px + x4, py + y4, pz + z4).tex(1, 0).color(p.r, p.g, p.b, p.alpha).endVertex();
			}

			tess.draw();
		}

		// Restore OpenGL state
		GlStateManager.depthMask(true);
		GlStateManager.disableBlend();
		GlStateManager.enableLighting();
		GlStateManager.enableCull();

		GL11.glPopMatrix();
	}

	/**
	 * TIER 2: OBJ SPHERE RENDERING (128-512m)
	 *
	 * Renders fireball using sphere_uv.obj model with physics-based scaling.
	 * Replaces particle rendering for simpler, faster fireball visualization.
	 *
	 * PHYSICS-BASED SCALING:
	 * - Uses FireballVolumeProcessor calculations from EntityNukeTorexRealistic
	 * - Non-uniform scaling via GL11.glScalef(scaleX, scaleY, scaleZ)
	 * - Phase 1: Spherical fireball (X=Y=Z = fireballRadius * 2)
	 * - Future: Ellipsoidal mushroom cloud (X=cloudWidth, Y=cloudHeight, Z=cloudWidth)
	 *
	 * PERFORMANCE: 20x faster than particle rendering (1,000 vertices vs 20,000)
	 */
	/**
	 * TIER 2: 3D OBJ MODEL RENDERING (128-512m distance)
	 *
	 * Renders mushroom cloud as multi-layer 3D OBJ models with continuous deformation.
	 * Uses physics-accurate geometry from FireballPhysicsCalculator.
	 *
	 * 3-LAYER RENDERING:
	 * - Layer 1: Mushroom cap (sphere_uv.obj with non-uniform scaling)
	 * - Layer 2: Mushroom stem (ring.obj stretched vertically)
	 * - Layer 3: Internal toroidal flow (ring.obj, semi-transparent)
	 *
	 * @param entity Explosion entity
	 * @param x Render X offset
	 * @param y Render Y offset
	 * @param z Render Z offset
	 * @param partialTicks Sub-tick interpolation
	 * @param distance Distance from camera
	 */
	private void renderTier2_OBJSphere(EntityNukeTorexRealistic entity, double x, double y, double z,
			float partialTicks, double distance) {
		// Get explosion properties from entity
		double deformationProgress = entity.getDeformationProgress(); // 0.0-1.0 continuous progress
		double fireballRadius = entity.getFireballRadius();
		double temperature = entity.getTemperature();
		double cloudHeight = entity.getCloudHeight();
		double cloudWidth = entity.getCloudWidth();

		// Render mushroom cloud (continuous sphere→mushroom transition)
		if (fireballRadius > 0) {
			// Get physics-based parameters (time-based, not phase-based)
			double timeSeconds = entity.ticksExisted / 20.0; // Convert ticks to seconds
			double yieldKilotons = entity.getYieldKilotons();

			// Calculate Froude number for aspect ratio determination
			// Fr = v²/(g×L) where v = expansion velocity, L = fireball radius
			double expansionVelocity = FireballPhysicsCalculator.calculateExpansionVelocity(fireballRadius, timeSeconds);
			double riseVelocity = Math.max(0.0, cloudHeight / Math.max(timeSeconds, 1.0)); // Approximate from height/time
			double totalVelocity = Math.sqrt(expansionVelocity * expansionVelocity + riseVelocity * riseVelocity);
			double Fr = FireballPhysicsCalculator.calculateFroudeNumber(totalVelocity, fireballRadius);

			// === PHYSICS-BASED MUSHROOM CLOUD GEOMETRY ===
			// Get continuous deformation geometry from FireballPhysicsCalculator
			MushroomGeometry geom = FireballPhysicsCalculator.calculateMushroomGeometry(
				deformationProgress, fireballRadius, cloudWidth, cloudHeight,
				timeSeconds, yieldKilotons, Fr
			);

			double spreadFactor = FireballPhysicsCalculator.calculateFireballSpread(timeSeconds);
			double initialTemp = 1e7; // Initial temperature for ratio calculation
			double tempRatio = FireballPhysicsCalculator.getCoreShellTemperatureRatio(temperature, initialTemp);

			GL11.glPushMatrix();
			GL11.glTranslated(x, y, z);

			// Disable lighting for self-illuminated fireball/cloud
			GlStateManager.disableLighting();
			GlStateManager.enableBlend();
			GlStateManager.disableCull(); // Render both sides
			GlStateManager.depthMask(false); // Allow layers to blend properly

			// Get camera position for Fresnel effect
			EntityPlayer player = Minecraft.getMinecraft().player;
			double camX = player.posX - entity.posX;
			double camY = player.posY - entity.posY;
			double camZ = player.posZ - entity.posZ;

			// Bind white texture (will be tinted by glColor4f)
			bindTexture(FIREBALL_TEXTURE);

			// Calculate base alpha (fade out near end of life)
			double progress = entity.getDeformationProgress();
		int phase = (progress < 0.1) ? 0 : (progress < 0.3) ? 1 : (progress < 0.7) ? 2 : 3;
		float baseAlpha = calculateFireballAlpha(entity, phase);

			// === PHASE 5: FULL 3-LAYER OBJ RENDERING ===
			// Layer 1: Mushroom cap (always visible, deformed sphere)
			renderMushroomCap(geom, entity, temperature, tempRatio, spreadFactor, camX, camY, camZ, baseAlpha);

			// Layer 2: Mushroom stem (visible after progress > 0.5)
			renderMushroomStem(geom, entity, temperature, spreadFactor, baseAlpha);

			// Layer 3: Toroidal flow visualization (visible after progress > 0.7)
			renderToroidalFlow(geom, entity, temperature, baseAlpha);

			// Restore OpenGL state
			GlStateManager.depthMask(true);
			GlStateManager.enableCull();
			GlStateManager.disableBlend();
			GlStateManager.enableLighting();

			GL11.glPopMatrix();
		} else {
			// Fireball not yet formed or dissipated - no rendering
		}
	}

	/**
	 * Calculate fireball alpha based on progress and age
	 * PHYSICS-BASED: Uses continuous progress (0.0-1.0) instead of discrete phases
	 *
	 * @param entity The explosion entity
	 * @param phase Current explosion phase (used for compatibility, but progress is preferred)
	 * @return Alpha value (0.0-1.0)
	 */
	private float calculateFireballAlpha(EntityNukeTorexRealistic entity, int phase) {
		// PHYSICS-BASED APPROACH: Use continuous progress, not discrete phases
		double progress = entity.getDeformationProgress();
		float lifeFraction = (float)entity.ticksExisted / (float)entity.getMaxAge();

		// Constant visibility throughout deformation (0.0-1.0 progress)
		// Only fade out near the end of entity lifetime
		if (lifeFraction > 0.9F) {
			// Final 10% of lifetime: gradual fade out
			float fadeProgress = (lifeFraction - 0.9F) / 0.1F; // 0.0-1.0
			return 1.0F - fadeProgress;
		}

		// Full visibility during all deformation stages
		return 1.0F;
	}

	/**
	 * Calculate temperature for specific layer (temperature gradient)
	 *
	 * Implements center-to-edge temperature gradient for realistic appearance:
	 * - Layer 0 (inner core): Full temperature (hottest)
	 * - Layer 1 (outer shell): 50% temperature (cooler)
	 *
	 * @param baseTemperature Base temperature from physics calculation
	 * @param layer Layer index (0=core, 1=shell)
	 * @return Adjusted temperature for the layer
	 */
	private double getLayerTemperature(double baseTemperature, int layer) {
		switch (layer) {
			case 0: // Inner core - hottest
				return baseTemperature; // Full temperature
			case 1: // Outer shell - cooler
				return baseTemperature * 0.5; // Half temperature
			default:
				return baseTemperature;
		}
	}

	/**
	 * Calculate Fresnel factor for edge glow effect
	 *
	 * Fresnel effect makes edges of sphere brighter when viewed at grazing angles.
	 * This simulates atmospheric scattering and internal glow.
	 *
	 * Formula: fresnelFactor = (1 - dot(viewDir, normal))^power
	 * - At sphere center (facing camera): dot = 1, fresnel = 0 (no enhancement)
	 * - At sphere edge (grazing angle): dot = 0, fresnel = 1 (maximum enhancement)
	 *
	 * @param camX Camera X offset from sphere center
	 * @param camY Camera Y offset from sphere center
	 * @param camZ Camera Z offset from sphere center
	 * @param power Fresnel power (higher = sharper edge glow)
	 * @return Fresnel factor (0.0-1.0)
	 */
	private float calculateFresnelFactor(double camX, double camY, double camZ, float power) {
		// Normalize camera direction vector
		double camDist = Math.sqrt(camX * camX + camY * camY + camZ * camZ);
		if (camDist < 0.001) return 0.0F; // Avoid division by zero

		double camDirX = camX / camDist;
		double camDirY = camY / camDist;
		double camDirZ = camZ / camDist;

		// For a sphere, the normal at any point is the direction from center to that point
		// We approximate by using camera direction as if viewing sphere center
		// dot(viewDir, normal) ≈ 1.0 at center, ≈ 0.0 at edges
		double dotProduct = Math.abs(camDirX * 0 + camDirY * 0 + camDirZ * 1.0); // Simplified

		// Fresnel term: (1 - dot)^power
		double fresnel = Math.pow(1.0 - dotProduct, power);

		return (float)Math.min(1.0, Math.max(0.0, fresnel));
	}

	/**
	 * Apply turbulence effect to color for realistic surface variation
	 *
	 * Uses simplified pseudo-random noise based on time and layer to create
	 * non-uniform color distribution across sphere surface.
	 *
	 * Effect:
	 * - Creates bright/dark patches on fireball surface
	 * - Animates over time (turbulent motion)
	 * - Variation intensity: ±10% for core, ±15% for shell (scaled by physics-based spreadFactor)
	 *
	 * @param baseColor Base color array [r, g, b]
	 * @param ticksExisted Current entity age (for animation)
	 * @param layer Layer index (affects variation intensity)
	 * @param spreadFactor Physics-based turbulence intensity (0.0-1.0 from FireballPhysicsCalculator)
	 * @return Modified color with turbulence applied
	 */
	private float[] applyTurbulence(float[] baseColor, int ticksExisted, int layer, double spreadFactor) {
		// Time-based animation
		float time = ticksExisted * 0.1F;

		// Pseudo-random noise using sine waves
		// Multiple frequencies create complex patterns
		float noise1 = (float)Math.sin(time * 0.5 + layer * 3.14);
		float noise2 = (float)Math.cos(time * 0.7 + layer * 2.71);
		float noise3 = (float)Math.sin(time * 1.3 + layer * 1.41);

		// Combine noise components
		float turbulence = (noise1 + noise2 * 0.5F + noise3 * 0.3F) / 1.8F; // Range: [-1, 1]

		// Scale by layer (outer layer has more variation) and physics-based spread factor
		// spreadFactor: 0.0 (smooth) → 0.3 (developing) → 0.8 (turbulent)
		float variationAmount = ((layer == 0) ? 0.10F : 0.15F) * (float)spreadFactor;
		float variation = turbulence * variationAmount;

		// Apply variation to color (multiplicative)
		float multiplier = 1.0F + variation;

		float[] result = new float[3];
		result[0] = Math.min(1.0F, Math.max(0.0F, baseColor[0] * multiplier));
		result[1] = Math.min(1.0F, Math.max(0.0F, baseColor[1] * multiplier));
		result[2] = Math.min(1.0F, Math.max(0.0F, baseColor[2] * multiplier));

		return result;
	}

	/**
	 * TIER 3: 3D BILLBOARD MODEL (512m-5km)
	 *
	 * Renders a low-poly 3D model with billboard texture.
	 * Future implementation - currently stubbed.
	 */
	private void renderTier3_3DBillboard(EntityNukeTorexRealistic entity, double x, double y, double z,
			float partialTicks, double distance) {
		// TODO: Phase 4 - Implement 3D billboard model
		// For now, fall back to Tier 4 rendering
		renderTier4_2DBillboard(entity, x, y, z, partialTicks, distance);
	}

	/**
	 * TIER 4: 2D BILLBOARD (5-50km)
	 *
	 * PHASE 1 IMPLEMENTATION - SIMPLEST TIER FOR RAPID VISUAL CONFIRMATION
	 *
	 * Renders a simple camera-facing quad with explosion texture.
	 * Size scales with fireball/cloud dimensions.
	 * Color based on temperature and phase.
	 *
	 * PDF §2.16: "Mushroom cloud visible from hundreds of kilometers"
	 */
	private void renderTier4_2DBillboard(EntityNukeTorexRealistic entity, double x, double y, double z,
			float partialTicks, double distance) {

		// Get explosion properties from entity
		double progress = entity.getDeformationProgress();
		int phase = (progress < 0.1) ? 0 : (progress < 0.3) ? 1 : (progress < 0.7) ? 2 : 3;
		double fireballRadius = entity.getFireballRadius();
		double cloudHeight = entity.getCloudHeight();
		double cloudWidth = entity.getCloudWidth();
		double temperature = entity.getTemperature();

		// Calculate billboard size based on phase
		// USE PHYSICAL SCALE: 1 meter = 1 block (Minecraft standard)
		// Fireball dimensions from PDF: 67m (1kt) to 1002m (50MT)
		double billboardSize;
		double billboardY;

		if (phase <= 1) {
			// Phase 0-1: Spherical fireball
			// Full physical scale diameter
			billboardSize = fireballRadius * 2.0;
			billboardY = y + fireballRadius * 0.5; // Half-buried (surface burst)
		} else if (phase == 2) {
			// Phase 2: Rising ellipsoid
			// Use height or diameter, whichever is larger
			billboardSize = Math.max(fireballRadius * 2.0, cloudHeight * 0.4);
			billboardY = y + cloudHeight * 0.5;
		} else {
			// Phase 3-4: Mushroom cloud
			// Full cloud dimensions
			billboardSize = Math.max(cloudWidth * 2.0, cloudHeight);
			billboardY = y + cloudHeight * 0.6;
		}

		// Get color based on temperature
		float[] color = getTemperatureColor(temperature, phase);
		float alpha = entity.getAlpha() * 0.9F; // Slightly transparent

		// DEBUG LOG BILLBOARD SIZE
		if (entity.ticksExisted % 20 == 0 || entity.ticksExisted < 10) {
			System.out.println("[RENDER BILLBOARD] Phase=" + phase);
			System.out.println("[RENDER BILLBOARD] Billboard Size=" + String.format("%.2f", billboardSize) + " blocks");
			System.out.println("[RENDER BILLBOARD] Billboard Y=" + String.format("%.2f", billboardY) + " blocks");
			System.out.println("[RENDER BILLBOARD] Color=(" + String.format("%.2f", color[0]) + ", " +
				String.format("%.2f", color[1]) + ", " + String.format("%.2f", color[2]) + ")");
			System.out.println("[RENDER BILLBOARD] Alpha=" + String.format("%.2f", alpha));
		}

		// Setup OpenGL state
		GL11.glPushMatrix();
		GL11.glTranslated(x, billboardY, z);

		// Billboard rotation (face camera)
		GL11.glRotatef(-this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
		GL11.glRotatef(this.renderManager.playerViewX, 1.0F, 0.0F, 0.0F);

		GlStateManager.disableLighting();
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
		GlStateManager.enableTexture2D();
		GlStateManager.depthMask(false);

		// Bind texture
		this.bindTexture(PARTICLE_TEXTURE);

		// Render quad
		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();
		buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

		double half = billboardSize / 2.0;

		// Bottom-left
		buf.pos(-half, -half, 0).tex(0, 0).color(color[0], color[1], color[2], alpha).endVertex();
		// Top-left
		buf.pos(-half, half, 0).tex(0, 1).color(color[0], color[1], color[2], alpha).endVertex();
		// Top-right
		buf.pos(half, half, 0).tex(1, 1).color(color[0], color[1], color[2], alpha).endVertex();
		// Bottom-right
		buf.pos(half, -half, 0).tex(1, 0).color(color[0], color[1], color[2], alpha).endVertex();

		tess.draw();

		// Restore OpenGL state
		GlStateManager.depthMask(true);
		GlStateManager.disableBlend();
		GlStateManager.enableLighting();

		GL11.glPopMatrix();
	}

	/**
	 * TIER 5: SKYBOX PROJECTION (50-716km)
	 *
	 * Renders explosion as skybox element for extreme distances.
	 * Future implementation - currently stubbed.
	 */
	private void renderTier5_Skybox(EntityNukeTorexRealistic entity, double x, double y, double z,
			float partialTicks, double distance) {
		// TODO: Phase 5 - Implement skybox projection
		// For now, still render as billboard (will be very small but visible)
		renderTier4_2DBillboard(entity, x, y, z, partialTicks, distance);
	}

	/**
	 * Calculate RGB color based on temperature and phase
	 *
	 * PDF §2.123-2.126: Temperature evolution determines color
	 * - 7000+ K: White-hot
	 * - 5000-7000 K: Yellow-white
	 * - 3500-5000 K: Yellow-orange
	 * - 2000-3500 K: Orange-red
	 * - <2000 K: Dull red → brown
	 *
	 * @param temperature Current temperature (Kelvin)
	 * @param phase Current phase (0-4)
	 * @return RGB array [r, g, b] (0.0-1.0)
	 */
	private float[] getTemperatureColor(double temperature, int phase) {
		if (phase == 0 || temperature >= 7000.0) {
			// White-hot (Phase 0 or very high temp)
			return new float[]{1.0F, 1.0F, 1.0F};
		} else if (temperature >= 5000.0) {
			// Yellow-white
			float t = (float)((temperature - 5000.0) / 2000.0);
			return new float[]{1.0F, 1.0F, t};
		} else if (temperature >= 3500.0) {
			// Yellow-orange
			float t = (float)((temperature - 3500.0) / 1500.0);
			return new float[]{1.0F, 0.6F + 0.4F * t, 0.0F};
		} else if (temperature >= 2000.0) {
			// Orange-red
			float t = (float)((temperature - 2000.0) / 1500.0);
			return new float[]{1.0F, 0.3F + 0.3F * t, 0.0F};
		} else if (temperature >= 1000.0) {
			// Dull red
			float t = (float)((temperature - 1000.0) / 1000.0);
			return new float[]{0.6F + 0.4F * t, 0.2F * t, 0.0F};
		} else {
			// Cooled: Brown/gray (dust color)
			if (phase >= 3) {
				// Mushroom cloud: Gray-brown
				return new float[]{0.55F, 0.50F, 0.45F};
			} else {
				// Still cooling: Dark brown
				return new float[]{0.4F, 0.3F, 0.25F};
			}
		}
	}

	/**
	 * Render debug information text
	 */
	private void renderDebugInfo(EntityNukeTorexRealistic entity, double x, double y, double z, double distance) {
		double yieldKt = entity.getYieldKilotons();
		double progress = entity.getDeformationProgress();
		int phase = (progress < 0.1) ? 0 : (progress < 0.3) ? 1 : (progress < 0.7) ? 2 : 3;
		double temperature = entity.getTemperature();
		double fireballRadius = entity.getFireballRadius();
		double cloudHeight = entity.getCloudHeight();
		int particleCount = entity.getParticles().size();

		String[] phaseNames = {"FLASH", "GROWTH", "RISE", "MUSHROOM", "STABLE"};
		String phaseName = phase < phaseNames.length ? phaseNames[phase] : "UNKNOWN";

		// Determine LOD tier
		String tier;
		if (distance < LOD_TIER1_RANGE) tier = "T1 (GPU)";
		else if (distance < LOD_TIER2_RANGE) tier = "T2 (BATCH)";
		else if (distance < LOD_TIER3_RANGE) tier = "T3 (3D)";
		else if (distance < LOD_TIER4_RANGE) tier = "T4 (2D)";
		else tier = "T5 (SKY)";

		String[] lines = {
			"LOD NUCLEAR EXPLOSION",
			String.format("Tier: %s (%.0fm)", tier, distance),
			String.format("Yield: %.1f kt", yieldKt),
			String.format("Phase: %d (%s)", phase, phaseName),
			String.format("Temp: %.0f K", temperature),
			String.format("Fireball: %.0f m", fireballRadius),
			String.format("Cloud: %.0f m", cloudHeight),
			String.format("Particles: %d", particleCount)
		};

		double textY = Math.max(cloudHeight, fireballRadius) + 20;

		GL11.glPushMatrix();
		GL11.glTranslated(x, y + textY, z);
		GL11.glNormal3f(0.0F, 1.0F, 0.0F);
		GL11.glRotatef(-this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
		GL11.glRotatef(this.renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
		GL11.glScalef(-0.02F, -0.02F, 0.02F);

		GlStateManager.disableLighting();
		GlStateManager.depthMask(false);
		GlStateManager.disableDepth();
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

		int lineHeight = 10;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int strWidth = this.getFontRendererFromRenderManager().getStringWidth(line);

			// Background
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder buf = tessellator.getBuffer();
			GlStateManager.disableTexture2D();
			buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
			buf.pos(-strWidth / 2 - 1, -1 + i * lineHeight, 0.0D).color(0.0F, 0.0F, 0.0F, 0.7F).endVertex();
			buf.pos(-strWidth / 2 - 1, 8 + i * lineHeight, 0.0D).color(0.0F, 0.0F, 0.0F, 0.7F).endVertex();
			buf.pos(strWidth / 2 + 1, 8 + i * lineHeight, 0.0D).color(0.0F, 0.0F, 0.0F, 0.7F).endVertex();
			buf.pos(strWidth / 2 + 1, -1 + i * lineHeight, 0.0D).color(0.0F, 0.0F, 0.0F, 0.7F).endVertex();
			tessellator.draw();
			GlStateManager.enableTexture2D();

			// Text
			this.getFontRendererFromRenderManager().drawString(line, -strWidth / 2, i * lineHeight, 0xFFFFFF);
		}

		GlStateManager.enableDepth();
		GlStateManager.depthMask(true);
		GlStateManager.enableLighting();
		GlStateManager.disableBlend();

		GL11.glPopMatrix();
	}

	/**
	 * Override shouldRender to extend Y-axis visibility range to realistic distances
	 *
	 * PHYSICS-BASED VISIBILITY:
	 * Real-world nuclear explosions are visible from hundreds of kilometers due to:
	 * - Massive vertical extent (mushroom clouds reach 5-20km height)
	 * - Earth's curvature limits horizon to ~500km for 20km tall objects
	 * - Atmospheric scattering allows visibility even beyond geometric horizon
	 *
	 * IMPLEMENTATION:
	 * - Use cloud height to calculate extended bounding box
	 * - Allow rendering up to 716km horizontal distance (Tier 5 skybox range)
	 * - No Y-axis culling (mushroom cloud extends thousands of meters vertically)
	 *
	 * This prevents mushroom clouds from disappearing when player looks up/down.
	 *
	 * @param entity The explosion entity
	 * @param camera Frustum camera for culling
	 * @param camX Camera X position
	 * @param camY Camera Y position
	 * @param camZ Camera Z position
	 * @return true if entity should render, false otherwise
	 */
	@Override
	public boolean shouldRender(EntityNukeTorexRealistic entity, net.minecraft.client.renderer.culling.ICamera camera,
			double camX, double camY, double camZ) {
		// Get explosion dimensions
		double cloudHeight = entity.getCloudHeight();
		double cloudWidth = entity.getCloudWidth();
		double fireballRadius = entity.getFireballRadius();

		// Calculate maximum vertical extent (from ground to cloud top)
		// Cloud center can be at entity.posY + cloudHeight/2, top at entity.posY + cloudHeight
		double maxVerticalExtent = Math.max(cloudHeight, fireballRadius * 2.0);

		// Calculate maximum horizontal extent
		double maxHorizontalExtent = Math.max(cloudWidth, fireballRadius) * 2.0;

		// Calculate distance from camera to entity center
		double dx = entity.posX - camX;
		double dy = entity.posY - camY;
		double dz = entity.posZ - camZ;
		double horizontalDist = Math.sqrt(dx * dx + dz * dz);

		// TIER 5 MAXIMUM RENDER DISTANCE (50-716km skybox projection)
		// Beyond this, explosion is too far even for skybox rendering
		final double MAX_RENDER_DISTANCE = 716000.0; // 716 km in blocks

		// Check horizontal distance (spherical culling)
		if (horizontalDist > MAX_RENDER_DISTANCE) {
			return false; // Too far even for skybox tier
		}

		// EXTENDED Y-AXIS VISIBILITY
		// No Y-axis culling within max render distance - mushroom clouds extend
		// thousands of meters vertically and should be visible when looking up/down
		// This matches real-world visibility where a 20km high mushroom cloud
		// is visible from horizon to horizon (limited only by Earth's curvature)

		// Check if camera is within extended bounding box
		// This creates a cylinder of visibility around the explosion
		double entityBottom = entity.posY - maxVerticalExtent * 0.5; // Allow some margin below
		double entityTop = entity.posY + maxVerticalExtent * 1.5; // Cloud extends upward

		// CRITICAL: Use very generous Y-bounds to prevent culling when looking up at tall clouds
		// A 10000m tall cloud should be visible even if camera is 5000m below the entity origin
		double verticalMargin = 10000.0; // 10km margin for extreme cases
		if (camY < entityBottom - verticalMargin || camY > entityTop + verticalMargin) {
			// Camera is extremely far above or below the explosion
			// Still render if within horizontal distance
			if (horizontalDist < MAX_RENDER_DISTANCE * 0.5) {
				return true; // Close enough horizontally to see even if offset vertically
			}
		}

		// Within range - allow rendering
		return true;
	}

	@Override
	protected ResourceLocation getEntityTexture(EntityNukeTorexRealistic entity) {
		return PARTICLE_TEXTURE;
	}

	// ========== PHASE 5 STUBS ==========
	// TODO: Implement these methods in Phase 5

	/**
	 * Render mushroom cap layer (deformed sphere_uv.obj)
	 * Uses physics-based deformation and temperature-dependent smoke textures
	 */
	private void renderMushroomCap(FireballPhysicsCalculator.MushroomGeometry geom,
			EntityNukeTorexRealistic entity, double temperature, double tempRatio,
			double spreadFactor, double camX, double camY, double camZ, float baseAlpha) {
		// DEBUG: Log every render call
		if (entity.ticksExisted % 20 == 0) {
			System.out.println("========== RENDER MUSHROOM CAP ==========");
			System.out.println("[CAP] sphereModel null? " + (sphereModel == null));
			System.out.println("[CAP] capScaleX=" + geom.capScaleX + " capScaleY=" + geom.capScaleY + " capScaleZ=" + geom.capScaleZ);
			System.out.println("[CAP] capCenterY=" + geom.capCenterY);
			System.out.println("[CAP] temperature=" + temperature + " tempRatio=" + tempRatio);
			System.out.println("[CAP] baseAlpha=" + baseAlpha);
		}

		if (sphereModel == null) {
			System.err.println("[ERROR] Sphere model not loaded for mushroom cap!");
			return;
		}

		// Safety check for invalid geometry
		if (Double.isNaN(geom.capScaleX) || Double.isNaN(geom.capScaleY) || Double.isNaN(geom.capScaleZ) ||
			geom.capScaleX <= 0 || geom.capScaleY <= 0 || geom.capScaleZ <= 0) {
			System.err.println("[ERROR] Invalid cap scales: X=" + geom.capScaleX + " Y=" + geom.capScaleY + " Z=" + geom.capScaleZ);
			return;
		}

		// === PROCEDURAL GRADIENT TEXTURE ===
		// Use physics-based radial temperature gradient instead of static smoke texture
		ResourceLocation gradientTex = getTemperatureGradientTexture(temperature);
		this.bindTexture(gradientTex);

		// Apply temperature-based alpha modulation
		float alpha = baseAlpha * (0.7f + 0.3f * (float)tempRatio);

		if (entity.ticksExisted % 20 == 0) {
			System.out.println("[CAP] Using procedural gradient texture for temperature=" + temperature + "K");
			System.out.println("[CAP] alpha=" + alpha);
			System.out.println("[CAP] RENDERING NOW...");
		}

		GL11.glPushMatrix();

		// Position at cap center (physics-based center of mass)
		GL11.glTranslated(0, geom.capCenterY, 0);

		// Apply physics-based anisotropic deformation
		// Early stage: spherical (capScaleX ≈ capScaleY ≈ capScaleZ)
		// Late stage: flattened disk (capScaleX > capScaleY, spreading horizontally)
		// NOTE: capScale values are DIAMETERS, but OBJ sphere has radius 1 (diameter 2)
		// So we scale by (diameter / 2) to get correct size
		GL11.glScaled(geom.capScaleX / 2.0, geom.capScaleY / 2.0, geom.capScaleZ / 2.0);

		// Set WHITE color to let gradient texture RGB show through, with alpha for transparency
		GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);

		// Enable blending for semi-transparency
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.disableCull(); // Render both sides

		// Render sphere model
		sphereModel.renderAll();

		if (entity.ticksExisted % 20 == 0) {
			System.out.println("[CAP] RENDER COMPLETE");
			System.out.println("=========================================");
		}

		GlStateManager.enableCull();
		GL11.glPopMatrix();
	}

	/**
	 * Render mushroom stem layer with REALISTIC TURBULENT GEOMETRY
	 *
	 * PHYSICS-BASED IMPLEMENTATION (Effects of Nuclear Weapons 1977):
	 * - Section 2.07: Toroidal circulation creates irregular upward flow
	 * - Section 2.09: Afterwinds suck debris upward, creating turbulent stem
	 * - Section 2.17: Stem radius varies with yield (1/2 to 1/10 of cap width)
	 * - Figure 2.07b: Shows highly irregular, twisted stem structure
	 *
	 * RENDERING APPROACH (SINGLE CYLINDER WITH PROCEDURAL VERTEX DEFORMATION):
	 * 1. Generate cylinder mesh procedurally using Tessellator/BufferBuilder
	 * 2. Apply per-vertex taper: radius varies from base (1.0) to top (0.3-0.8)
	 * 3. Apply per-vertex turbulence: Perlin noise XZ displacement
	 * 4. Single draw call for optimal performance
	 *
	 * This generates ONE CYLINDER with variable XZ ratios at different heights.
	 */
	private void renderMushroomStem(FireballPhysicsCalculator.MushroomGeometry geom,
			EntityNukeTorexRealistic entity, double temperature, double spreadFactor,
			float baseAlpha) {
		// DEBUG: Log every call
		if (entity.ticksExisted % 20 == 0) {
			System.out.println("========== RENDER SINGLE TURBULENT CYLINDER ==========");
			System.out.println("[STEM] progress=" + entity.getDeformationProgress());
		}

		// Stem only appears in later stages (deformation progress > 0.5)
		double progress = entity.getDeformationProgress();
		if (progress < 0.5) {
			if (entity.ticksExisted % 20 == 0) {
				System.out.println("[STEM] Skipped - progress < 0.5");
				System.out.println("======================================================");
			}
			return;
		}

		// Gradual appearance: 0.5 → 0.0 alpha, 0.7 → 1.0 alpha
		double stemAlpha = (progress - 0.5) / 0.2; // 0.0 to 1.0
		stemAlpha = Math.min(1.0, stemAlpha);

		if (stemAlpha < 0.01) return; // Skip if nearly invisible

		// === PROCEDURAL GRADIENT TEXTURE ===
		// Use gradient texture at 90% of cap temperature for cooler appearance
		ResourceLocation gradientTex = getTemperatureGradientTexture(temperature * 0.9);
		this.bindTexture(gradientTex);

		// Combined alpha (base × stem appearance)
		float alpha = (float)(baseAlpha * stemAlpha * 0.6);

		// Enable blending for semi-transparency
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.disableCull();

		// === CALCULATE STEM DIMENSIONS ===
		double stemHeight = geom.stemScaleY; // Total stem height (diameter in Y)
		double stemBaseRadiusX = geom.stemScaleX / 2.0; // Base radius X
		double stemBaseRadiusZ = geom.stemScaleZ / 2.0; // Base radius Z
		double stemBottom = geom.stemCenterY - stemHeight / 2.0; // Bottom Y position

		// Turbulence parameters (Perlin noise wavelength)
		double turbulenceWavelength = stemHeight * 0.3; // Eddies at 30% of stem height
		double turbulenceAmplitude = stemBaseRadiusX * 0.2; // Max 20% radius displacement

		// Time-based animation for rising/wobbling effect
		double timeSeconds = entity.ticksExisted / 20.0;
		double wobbleFrequency = 0.5; // 0.5 Hz wobble
		double wobblePhase = timeSeconds * wobbleFrequency * 2.0 * Math.PI;

		// === GATHER PHYSICS PARAMETERS FOR DYNAMIC RADIUS CALCULATION ===
		// These parameters change every tick based on real-time physics simulation
		double yieldKt = entity.getYieldKilotons();
		double deformationProgress = entity.getDeformationProgress();
		double verticalVelocity = entity.getVerticalVelocity(); // Real physics value
		double buoyancyForce = entity.getBuoyancyForce(); // Real physics value
		double cloudHeight = entity.getCloudHeight();
		double fireballRadius = entity.getFireballRadius();
		double currentTemperature = entity.getTemperature();

		if (entity.ticksExisted % 20 == 0) {
			System.out.println("[STEM] === DYNAMIC PHYSICS PARAMETERS ===");
			System.out.println("[STEM] Yield=" + String.format("%.1f", yieldKt) + "kt");
			System.out.println("[STEM] Deformation Progress=" + String.format("%.3f", deformationProgress));
			System.out.println("[STEM] Vertical Velocity=" + String.format("%.1f", verticalVelocity) + " m/s (real-time physics)");
			System.out.println("[STEM] Buoyancy Force=" + String.format("%.2e", buoyancyForce) + " N");
			System.out.println("[STEM] Cloud Height=" + String.format("%.1f", cloudHeight) + "m");
			System.out.println("[STEM] Fireball Radius=" + String.format("%.1f", fireballRadius) + "m");
			System.out.println("[STEM] Temperature=" + String.format("%.0f", currentTemperature) + "K");
			System.out.println("[STEM] Generating single cylinder with DYNAMIC per-vertex radius calculation");
			System.out.println("[STEM] Stem height=" + String.format("%.1f", stemHeight) + "m, Base radius=" + String.format("%.1f", stemBaseRadiusX) + "m");
		}

		// === GENERATE SINGLE CYLINDER WITH PROCEDURAL VERTEX DEFORMATION ===
		// Mesh resolution
		final int RADIAL_SEGMENTS = 32; // Segments around circumference (smoother = more segments)
		final int HEIGHT_SEGMENTS = 16; // Vertical subdivisions (more = smoother taper/turbulence)

		GL11.glPushMatrix();

		// Set color (white to let texture show through)
		GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);

		// Start building mesh
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer = tessellator.getBuffer();
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);

		// Generate cylinder mesh with per-vertex taper and turbulence
		for (int h = 0; h < HEIGHT_SEGMENTS; h++) {
			// Y coordinates for this height slice (normalized -1 to +1, like cylinder.obj)
			double y0_norm = -1.0 + (h / (double)HEIGHT_SEGMENTS) * 2.0;
			double y1_norm = -1.0 + ((h + 1) / (double)HEIGHT_SEGMENTS) * 2.0;

			// World Y coordinates
			double y0 = stemBottom + (y0_norm + 1.0) / 2.0 * stemHeight;
			double y1 = stemBottom + (y1_norm + 1.0) / 2.0 * stemHeight;

			// Height fraction (0.0 at base, 1.0 at top) for physics calculation
			double heightFraction0 = (y0_norm + 1.0) / 2.0; // 0.0 to 1.0
			double heightFraction1 = (y1_norm + 1.0) / 2.0;

			// === DYNAMIC RADIUS CALCULATION (PHYSICS-BASED) ===
			// Call FireballPhysicsCalculator to get radius ratio at this height
			// This ratio changes based on:
			// - Vertical velocity (fast rise → thinner stem)
			// - Cloud height / fireball radius (tall cloud → thin stem)
			// - Temperature (hot → strong convection → thin stem)
			// - Deformation progress (early → thick, late → thin)
			// - Yield (high yield → thin stem from PDF Section 2.17)
			// - Height profile (bottom wide, middle narrow, top slightly wide)
			double radiusRatio0 = FireballPhysicsCalculator.calculateStemRadiusRatio(
				heightFraction0,
				deformationProgress,
				verticalVelocity,
				buoyancyForce,
				cloudHeight,
				fireballRadius,
				currentTemperature,
				yieldKt
			);
			double radiusRatio1 = FireballPhysicsCalculator.calculateStemRadiusRatio(
				heightFraction1,
				deformationProgress,
				verticalVelocity,
				buoyancyForce,
				cloudHeight,
				fireballRadius,
				currentTemperature,
				yieldKt
			);

			// Apply radius ratio to base radius
			double radiusX0 = stemBaseRadiusX * radiusRatio0;
			double radiusZ0 = stemBaseRadiusZ * radiusRatio0;
			double radiusX1 = stemBaseRadiusX * radiusRatio1;
			double radiusZ1 = stemBaseRadiusZ * radiusRatio1;

			// === TURBULENCE OFFSET (XZ displacement) ===
			// Apply Perlin noise based on height
			double noiseX0 = SimplexNoise.noise(
				entity.posX / turbulenceWavelength,
				y0 / turbulenceWavelength,
				entity.posZ / turbulenceWavelength + 1000.0
			);
			double noiseZ0 = SimplexNoise.noise(
				entity.posX / turbulenceWavelength + 2000.0,
				y0 / turbulenceWavelength,
				entity.posZ / turbulenceWavelength
			);
			double noiseX1 = SimplexNoise.noise(
				entity.posX / turbulenceWavelength,
				y1 / turbulenceWavelength,
				entity.posZ / turbulenceWavelength + 1000.0
			);
			double noiseZ1 = SimplexNoise.noise(
				entity.posX / turbulenceWavelength + 2000.0,
				y1 / turbulenceWavelength,
				entity.posZ / turbulenceWavelength
			);

			// Turbulence strength varies by height (stronger at middle)
			double turbStrength0 = Math.sin(heightFraction0 * Math.PI);
			double turbStrength1 = Math.sin(heightFraction1 * Math.PI);

			// Turbulence displacement
			double offsetX0 = noiseX0 * turbulenceAmplitude * turbStrength0;
			double offsetZ0 = noiseZ0 * turbulenceAmplitude * turbStrength0;
			double offsetX1 = noiseX1 * turbulenceAmplitude * turbStrength1;
			double offsetZ1 = noiseZ1 * turbulenceAmplitude * turbStrength1;

			// === WOBBLE ANIMATION ===
			double wobblePhaseOffset0 = heightFraction0 * 2.0 * Math.PI;
			double wobblePhaseOffset1 = heightFraction1 * 2.0 * Math.PI;
			offsetX0 += Math.cos(wobblePhase + wobblePhaseOffset0) * turbulenceAmplitude * 0.1;
			offsetZ0 += Math.sin(wobblePhase + wobblePhaseOffset0) * turbulenceAmplitude * 0.1;
			offsetX1 += Math.cos(wobblePhase + wobblePhaseOffset1) * turbulenceAmplitude * 0.1;
			offsetZ1 += Math.sin(wobblePhase + wobblePhaseOffset1) * turbulenceAmplitude * 0.1;

			// Generate quads around circumference
			for (int i = 0; i < RADIAL_SEGMENTS; i++) {
				// Angles for this radial segment
				double angle0 = (i / (double)RADIAL_SEGMENTS) * Math.PI * 2.0;
				double angle1 = ((i + 1) / (double)RADIAL_SEGMENTS) * Math.PI * 2.0;

				// Calculate vertex positions (cylinder coords + taper + turbulence offset)
				// Bottom ring
				double x00 = Math.cos(angle0) * radiusX0 + offsetX0;
				double z00 = Math.sin(angle0) * radiusZ0 + offsetZ0;
				double x10 = Math.cos(angle1) * radiusX0 + offsetX0;
				double z10 = Math.sin(angle1) * radiusZ0 + offsetZ0;

				// Top ring
				double x01 = Math.cos(angle0) * radiusX1 + offsetX1;
				double z01 = Math.sin(angle0) * radiusZ1 + offsetZ1;
				double x11 = Math.cos(angle1) * radiusX1 + offsetX1;
				double z11 = Math.sin(angle1) * radiusZ1 + offsetZ1;

				// Texture coordinates (wrap around cylinder)
				double u0 = i / (double)RADIAL_SEGMENTS;
				double u1 = (i + 1) / (double)RADIAL_SEGMENTS;
				double v0 = h / (double)HEIGHT_SEGMENTS;
				double v1 = (h + 1) / (double)HEIGHT_SEGMENTS;

				// Add quad vertices (counter-clockwise winding for outward-facing normal)
				buffer.pos(x00, y0_norm * stemHeight / 2.0, z00).tex(u0, v0).endVertex();
				buffer.pos(x01, y1_norm * stemHeight / 2.0, z01).tex(u0, v1).endVertex();
				buffer.pos(x11, y1_norm * stemHeight / 2.0, z11).tex(u1, v1).endVertex();
				buffer.pos(x10, y0_norm * stemHeight / 2.0, z10).tex(u1, v0).endVertex();
			}
		}

		// Draw the single cylinder mesh (ONE DRAW CALL)
		tessellator.draw();

		GL11.glPopMatrix();
		GlStateManager.enableCull();

		if (entity.ticksExisted % 20 == 0) {
			// Calculate radius at base and top for debug display
			double baseRadiusRatio = FireballPhysicsCalculator.calculateStemRadiusRatio(
				0.0, deformationProgress, verticalVelocity, buoyancyForce,
				cloudHeight, fireballRadius, currentTemperature, yieldKt
			);
			double topRadiusRatio = FireballPhysicsCalculator.calculateStemRadiusRatio(
				1.0, deformationProgress, verticalVelocity, buoyancyForce,
				cloudHeight, fireballRadius, currentTemperature, yieldKt
			);

			System.out.println("[STEM] === RENDERING COMPLETE ===");
			System.out.println("[STEM] Single cylinder rendered with " + (RADIAL_SEGMENTS * HEIGHT_SEGMENTS) + " quads");
			System.out.println("[STEM] DYNAMIC RADIUS RANGE:");
			System.out.println("[STEM]   Base (h=0.0): " + String.format("%.1f", stemBaseRadiusX * baseRadiusRatio) + "m (ratio=" + String.format("%.3f", baseRadiusRatio) + ")");
			System.out.println("[STEM]   Top  (h=1.0): " + String.format("%.1f", stemBaseRadiusX * topRadiusRatio) + "m (ratio=" + String.format("%.3f", topRadiusRatio) + ")");
			System.out.println("[STEM] Physics-based: velocity, height, temp, progress, yield ALL contribute");
			System.out.println("======================================================");
		}
	}

	/**
	 * Render internal toroidal flow layer (ring.obj, semi-transparent)
	 * Visualizes Hill's vortex internal circulation pattern
	 */
	private void renderToroidalFlow(FireballPhysicsCalculator.MushroomGeometry geom,
			EntityNukeTorexRealistic entity, double temperature, float baseAlpha) {
		// DEBUG: Log every call
		if (entity.ticksExisted % 20 == 0) {
			System.out.println("========== RENDER TOROIDAL FLOW ==========");
			System.out.println("[FLOW] ringModel null? " + (ringModel == null));
			System.out.println("[FLOW] progress=" + entity.getDeformationProgress());
		}

		if (ringModel == null) {
			System.err.println("[ERROR] Ring model not loaded for toroidal flow!");
			return;
		}

		// Toroidal flow only visible in mushroom stage (deformation progress > 0.7)
		double progress = entity.getDeformationProgress();
		if (progress < 0.7) {
			if (entity.ticksExisted % 20 == 0) {
				System.out.println("[FLOW] Skipped - progress < 0.7");
				System.out.println("=========================================");
			}
			return;
		}

		// Gradual appearance: 0.7 → 0.0 alpha, 1.0 → 1.0 alpha
		double flowAlpha = (progress - 0.7) / 0.3;
		flowAlpha = Math.min(1.0, flowAlpha);

		if (flowAlpha < 0.01) return; // Skip if nearly invisible

		// No texture needed - use flat color for flow visualization
		GlStateManager.disableTexture2D();

		// Flow color: temperature-based but more transparent and slightly blue-shifted
		float[] rgb = getTemperatureColor(temperature * 0.7);
		float r = rgb[0] / 255.0f * 0.8f;
		float g = rgb[1] / 255.0f * 0.8f;
		float b = Math.min(1.0f, rgb[2] / 255.0f * 1.2f); // Slight blue shift

		// Very transparent to show internal structure
		float alpha = (float)(baseAlpha * flowAlpha * 0.25);

		// Calculate toroidal dimensions (Hill's spherical vortex theory)
		// R_major = 0.5 × cloud radius, R_minor = 0.33 × cloud radius
		double cloudRadius = Math.max(geom.capScaleX, geom.capScaleZ) * 0.5; // Half of cap diameter
		double toroidMajorRadius = cloudRadius * 0.5;
		double toroidMinorRadius = cloudRadius * 0.33;

		// Position at toroid center (between cap and stem, or at cap center if no stem)
		double toroidCenterY = geom.stemVisible ?
			(geom.capCenterY + geom.stemCenterY) * 0.5 :
			geom.capCenterY;

		GL11.glPushMatrix();

		// Position at toroid center (between cap and stem)
		GL11.glTranslated(0, toroidCenterY, 0);

		// Rotation animation to show vortex flow
		// Rotate around Y axis based on entity age
		float rotationAngle = (entity.ticksExisted * 2.0f) % 360.0f;
		GL11.glRotatef(rotationAngle, 0.0f, 1.0f, 0.0f);

		// Scale to toroidal dimensions
		// Major radius (horizontal extent), minor radius (tube thickness)
		double scaleXZ = toroidMajorRadius;
		double scaleY = toroidMinorRadius;
		GL11.glScaled(scaleXZ, scaleY, scaleXZ);

		// Set color and alpha
		GL11.glColor4f(r, g, b, alpha);

		// Enable additive blending for glow effect
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
		GlStateManager.disableCull();

		// Render ring model
		ringModel.renderAll();

		GlStateManager.enableCull();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); // Reset blend
		GlStateManager.enableTexture2D();
		GL11.glPopMatrix();
	}

	/**
	 * TEMPORARY: Simple sphere rendering
	 * Renders a basic sphere using the OBJ model until full multi-layer rendering is implemented
	 */
	private void renderSimpleSphere(FireballPhysicsCalculator.MushroomGeometry geom,
			EntityNukeTorexRealistic entity, double temperature, float baseAlpha) {
		if (sphereModel == null) {
			System.err.println("[ERROR] Sphere model not loaded!");
			return;
		}

		// Temperature-based color (same as EntityNukeTorex)
		float[] rgb = getTemperatureColor(temperature);
		float r = rgb[0] / 255.0f;
		float g = rgb[1] / 255.0f;
		float b = rgb[2] / 255.0f;

		GL11.glPushMatrix();

		// Position at cap center
		GL11.glTranslated(0, geom.capCenterY, 0);

		// Scale to fireball size
		float scale = (float)(geom.capScaleX / 2.0); // Radius
		GL11.glScalef(scale, scale, scale);

		// Set color and alpha
		GL11.glColor4f(r, g, b, baseAlpha * 0.8f);

		// Enable blending for transparency
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Render sphere model
		sphereModel.renderAll();

		GL11.glPopMatrix();

		// DEBUG: Log first render
		if (entity.ticksExisted == 1) {
			System.out.println("[RENDER SPHERE] First render: scale=" + scale + " centerY=" + geom.capCenterY);
			System.out.println("  Color: R=" + r + " G=" + g + " B=" + b + " A=" + baseAlpha);
		}
	}

	/**
	 * Get smoke texture index from temperature (Kelvin)
	 * Maps temperature range to texture index 0-7
	 * 10000K → index 0 (d_smoke1, hottest/white)
	 * 1000K  → index 7 (d_smoke8, coldest/dark gray)
	 */
	private int getTextureIndexFromTemperature(double temperature) {
		// Clamp temperature to reasonable range
		double clampedTemp = Math.max(1000.0, Math.min(10000.0, temperature));

		// Inverse mapping: high temp → low index (hotter textures)
		// (10000 - temp) / (10000 - 1000) * 7 = normalized to 0-7
		double normalized = (10000.0 - clampedTemp) / 9000.0;
		int index = (int)(normalized * 7.999);
		return Math.max(0, Math.min(7, index));
	}

	/**
	 * Get temperature-based color (same as EntityNukeTorex)
	 */
	private float[] getTemperatureColor(double temperature) {
		if (temperature >= 7000.0) {
			// Brilliant white (>7000 K)
			return new float[]{255, 255, 255};
		} else if (temperature >= 5000.0) {
			// White-blue (5000-7000 K)
			int blue = (int)(255.0 * ((temperature - 5000.0) / 2000.0));
			return new float[]{255, 255, blue};
		} else if (temperature >= 3500.0) {
			// Yellow-orange (3500-5000 K)
			int green = (int)(255.0 * ((temperature - 3500.0) / 1500.0));
			return new float[]{255, green, 0};
		} else if (temperature >= 2000.0) {
			// Orange-red (2000-3500 K)
			int green = (int)(165.0 * ((temperature - 2000.0) / 1500.0));
			return new float[]{255, green, 0};
		} else {
			// Dull red (<2000 K)
			int red = (int)(255.0 * (temperature / 2000.0));
			return new float[]{red, 0, 0};
		}
	}

	// ========== PROCEDURAL TEMPERATURE GRADIENT TEXTURE GENERATION ==========

	/**
	 * Convert temperature (Kelvin) to RGB color using blackbody radiation curve
	 *
	 * PHYSICS-BASED COLOR MAPPING:
	 * - 10000K+: White (255, 255, 255)
	 * - 7000-10000K: Blue-white (255, 255, 200-255)
	 * - 5000-7000K: Yellow (255, 220-255, 100-200)
	 * - 3000-5000K: Orange (255, 150-220, 0-100)
	 * - 1500-3000K: Red (255, 50-150, 0)
	 * - <1500K: Dark red (100-255, 0, 0)
	 *
	 * @param kelvin Temperature in Kelvin
	 * @return RGB array [r, g, b] (0-255 range)
	 */
	private int[] temperatureToColor(double kelvin) {
		if (kelvin >= 10000) {
			// Brilliant white (>10000 K)
			return new int[]{255, 255, 255};
		} else if (kelvin >= 7000) {
			// Blue-white (7000-10000 K)
			double t = (kelvin - 7000) / 3000.0;
			return new int[]{255, 255, (int)(200 + 55 * t)};
		} else if (kelvin >= 5000) {
			// Yellow (5000-7000 K)
			double t = (kelvin - 5000) / 2000.0;
			return new int[]{255, (int)(220 + 35 * t), (int)(100 + 100 * t)};
		} else if (kelvin >= 3000) {
			// Orange (3000-5000 K)
			double t = (kelvin - 3000) / 2000.0;
			return new int[]{255, (int)(150 + 70 * t), (int)(0 + 100 * t)};
		} else if (kelvin >= 1500) {
			// Red (1500-3000 K)
			double t = (kelvin - 1500) / 1500.0;
			return new int[]{255, (int)(50 + 100 * t), 0};
		} else {
			// Dark red (<1500 K)
			double t = Math.max(0.0, kelvin / 1500.0);
			return new int[]{(int)(100 + 155 * t), 0, 0};
		}
	}

	/**
	 * Generate radial temperature gradient texture (512x512 BufferedImage)
	 *
	 * GAUSSIAN TEMPERATURE DISTRIBUTION:
	 * T(r) = T_center × exp(-r²/(2σ²))
	 * where σ = radius × 0.3 (Gaussian spread parameter)
	 *
	 * Creates realistic center-to-edge temperature gradient:
	 * - Center (r=0): Full temperature (white-hot for 10000K)
	 * - Mid-radius: Yellow/orange (transitional)
	 * - Edge (r=256): Cool/dark red (approaching ambient)
	 *
	 * @param centerTemperature Temperature at center in Kelvin
	 * @return 512x512 ARGB BufferedImage with radial gradient
	 */
	private BufferedImage generateRadialTemperatureTexture(double centerTemperature) {
		int size = 512;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

		int centerX = size / 2;
		int centerY = size / 2;
		double sigma = (size / 2.0) * 0.3; // Gaussian spread: 30% of radius

		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				// Calculate distance from center
				double dx = x - centerX;
				double dy = y - centerY;
				double r = Math.sqrt(dx * dx + dy * dy);

				// Gaussian temperature falloff: T(r) = T_center × exp(-r²/(2σ²))
				double tempRatio = Math.exp(-(r * r) / (2.0 * sigma * sigma));
				double pixelTemp = centerTemperature * tempRatio;

				// Convert temperature to RGB using blackbody radiation
				int[] rgb = temperatureToColor(pixelTemp);

				// Create ARGB pixel (full alpha)
				int argb = (255 << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
				img.setRGB(x, y, argb);
			}
		}

		return img;
	}

	/**
	 * Get temperature gradient texture with caching
	 *
	 * TEXTURE CACHING STRATEGY:
	 * - Quantize temperature to 100K increments (5000K, 5100K, 5200K, ...)
	 * - Cache generated textures by quantized key
	 * - Reuse textures for similar temperatures (reduces GPU uploads)
	 *
	 * PERFORMANCE:
	 * - First call: Generates 512x512 texture, uploads to GPU (~2ms)
	 * - Cached calls: Instant lookup (0ms)
	 * - Memory: ~1MB per texture × ~100 cache entries = ~100MB max
	 *
	 * @param temperature Current temperature in Kelvin
	 * @return ResourceLocation for the cached or newly generated gradient texture
	 */
	private ResourceLocation getTemperatureGradientTexture(double temperature) {
		// Quantize temperature to 100K increments for caching
		int tempKey = ((int)(temperature / 100.0)) * 100;

		// Check cache
		if (!temperatureTextureCache.containsKey(tempKey)) {
			// Generate new texture
			BufferedImage img = generateRadialTemperatureTexture(tempKey);
			DynamicTexture dynTex = new DynamicTexture(img);

			// Upload to Minecraft texture manager
			ResourceLocation loc = Minecraft.getMinecraft().getTextureManager()
				.getDynamicTextureLocation("nuke_temp_gradient_" + tempKey, dynTex);

			// Cache for future use
			temperatureTextureCache.put(tempKey, loc);

			System.out.println("[TEMP GRADIENT] Generated new texture for " + tempKey + "K");
		}

		return temperatureTextureCache.get(tempKey);
	}

	/**
	 * RENDER BLAST WAVE PARTICLES
	 *
	 * Renders visible blast wave (dust/smoke lifted by shockwave).
	 * Uses billboard quad rendering with color based on overpressure.
	 *
	 * RENDERING TECHNIQUE:
	 * - Billboard quads (camera-facing)
	 * - Alpha blending for dust cloud transparency
	 * - Depth sorting (back-to-front) for proper transparency
	 * - Batch rendering via BufferBuilder for performance
	 *
	 * PERFORMANCE:
	 * - Typical: 500-2000 particles
	 * - LOD: Disabled beyond 50km (Tier 4 range)
	 * - Single draw call for all particles
	 *
	 * @param entity Nuclear explosion entity
	 * @param x Render offset X
	 * @param y Render offset Y
	 * @param z Render offset Z
	 * @param partialTicks Frame interpolation
	 * @param distance Camera distance
	 */
	private void renderBlastWaveParticles(EntityNukeTorexRealistic entity, double x, double y, double z,
	                                       float partialTicks, double distance) {
		// Get player for camera position
		EntityPlayer player = Minecraft.getMinecraft().player;
		if (player == null) return;

		ArrayList<EntityNukeTorexRealistic.BlastWaveParticle> particles = entity.blastWaveParticles;
		if (particles.isEmpty()) return;

		// Debug logging
		if (entity.ticksExisted % 20 == 0) {
			System.out.println("========== BLAST WAVE RENDER ==========");
			System.out.println("[BLAST RENDER] Particles: " + particles.size());
			System.out.println("[BLAST RENDER] Distance: " + String.format("%.1f", distance) + "m");
		}

		// Depth sorting (back-to-front for proper alpha blending)
		particles.sort((p1, p2) -> {
			double d1 = player.getDistanceSq(p1.x, p1.y, p1.z);
			double d2 = player.getDistanceSq(p2.x, p2.y, p2.z);
			return Double.compare(d2, d1); // Reverse order (farthest first)
		});

		// Setup OpenGL state
		GL11.glPushMatrix();
		GL11.glTranslated(x, y, z);

		// Bind smoke/cloud texture
		this.bindTexture(PARTICLE_TEXTURE);

		// Enable blending
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.depthMask(false); // Disable depth writes for transparency
		GlStateManager.disableCull();

		// Start batch rendering
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer = tessellator.getBuffer();
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

		// Camera-facing basis vectors (for billboard)
		float rotationX = ActiveRenderInfo.getRotationX();
		float rotationZ = ActiveRenderInfo.getRotationZ();
		float rotationYZ = ActiveRenderInfo.getRotationYZ();
		float rotationXY = ActiveRenderInfo.getRotationXY();
		float rotationXZ = ActiveRenderInfo.getRotationXZ();

		// Render each particle as billboard quad
		for (EntityNukeTorexRealistic.BlastWaveParticle p : particles) {
			// Interpolated position
			Vec3 pos = p.getInterpPos(partialTicks);
			double px = pos.xCoord - entity.posX;
			double py = pos.yCoord - entity.posY;
			double pz = pos.zCoord - entity.posZ;

			// Particle size
			float size = p.size;

			// Calculate quad corners (billboard facing camera)
			// Using ActiveRenderInfo rotation values
			float minU = 0.0F;
			float maxU = 1.0F;
			float minV = 0.0F;
			float maxV = 1.0F;

			// Billboard vertices (camera-facing square)
			double[] vx = new double[4];
			double[] vy = new double[4];
			double[] vz = new double[4];

			vx[0] = px + (-rotationX - rotationXY) * size;
			vy[0] = py + (-rotationXZ) * size;
			vz[0] = pz + (-rotationZ - rotationYZ) * size;

			vx[1] = px + (-rotationX + rotationXY) * size;
			vy[1] = py + (rotationXZ) * size;
			vz[1] = pz + (-rotationZ + rotationYZ) * size;

			vx[2] = px + (rotationX + rotationXY) * size;
			vy[2] = py + (rotationXZ) * size;
			vz[2] = pz + (rotationZ + rotationYZ) * size;

			vx[3] = px + (rotationX - rotationXY) * size;
			vy[3] = py + (-rotationXZ) * size;
			vz[3] = pz + (rotationZ - rotationYZ) * size;

			// Color from particle (pressure-based)
			int r = (int)(p.r * 255);
			int g = (int)(p.g * 255);
			int b = (int)(p.b * 255);
			int a = (int)(p.alpha * 255);

			// Add quad vertices
			buffer.pos(vx[0], vy[0], vz[0]).tex(minU, maxV).color(r, g, b, a).endVertex();
			buffer.pos(vx[1], vy[1], vz[1]).tex(maxU, maxV).color(r, g, b, a).endVertex();
			buffer.pos(vx[2], vy[2], vz[2]).tex(maxU, minV).color(r, g, b, a).endVertex();
			buffer.pos(vx[3], vy[3], vz[3]).tex(minU, minV).color(r, g, b, a).endVertex();
		}

		// Draw batch
		tessellator.draw();

		// Restore OpenGL state
		GlStateManager.enableCull();
		GlStateManager.depthMask(true);
		GlStateManager.disableBlend();

		GL11.glPopMatrix();

		if (entity.ticksExisted % 20 == 0) {
			System.out.println("[BLAST RENDER] Rendered " + particles.size() + " particles");
			System.out.println("=======================================");
		}
	}

	// =========================================================================
	// === FLASH & SHAKE TRIGGER SYSTEM =========================================
	// =========================================================================

	/**
	 * Triggers physics-based screen flash and camera shake for this explosion.
	 *
	 * FLASH: Light travels instantaneously — triggered on first render frame (ticksExisted <= 3).
	 *        Duration = f(yield, distance) via thermal fluence calculation.
	 *        Threshold: 3 cal/cm² = 125,600 J/m² (daytime pupil).
	 *
	 * SHAKE: Triggered when the blast shockwave front passes the player.
	 *        Shockwave speed: ~343 m/s = ~17.15 blocks/tick.
	 *        Duration and amplitude = f(yield, distance) via overpressure scaling.
	 *        Also sets player.hurtTime for vanilla hurt-camera tilt.
	 *
	 * Both effects are one-shot per entity (tracked via nukeFlash/ShakeTriggeredEntities sets).
	 * Sets are cleaned up when the entity is dead.
	 *
	 * @param entity   The nuclear explosion entity
	 * @param distance Player distance from explosion in blocks (≈ meters in this mod)
	 */
	private void triggerFlashAndShake(EntityNukeTorexRealistic entity, double distance) {
		int entityId = entity.getEntityId();
		double yieldKt = entity.getYieldKilotons();
		double distanceMeters = Math.max(1.0, distance);

		// === FLASH TRIGGER ===
		// Light reaches player instantly — trigger on first render frames only
		if (!ModEventHandlerClient.nukeFlashTriggeredEntities.contains(entityId)
				&& entity.ticksExisted <= 3) {

			long flashMs = FireballPhysicsCalculator.calculateFlashDurationMs(yieldKt, distanceMeters);
			if (flashMs > 0) {
				ModEventHandlerClient.nukeFlashTimestamp = System.currentTimeMillis();
				ModEventHandlerClient.nukeFlashDurationMs = flashMs;
			}
			// Mark as triggered regardless of whether threshold was met
			ModEventHandlerClient.nukeFlashTriggeredEntities.add(entityId);
		}

		// === SHAKE TRIGGER ===
		// Shockwave speed: ~343 m/s ≈ 17.15 blocks/tick
		// (1 tick = 0.05s; 343 m/s × 0.05 s/tick = 17.15 m/tick)
		final double SHOCKWAVE_SPEED = 17.15;
		double shockwaveFrontDistance = entity.ticksExisted * SHOCKWAVE_SPEED;

		// Trigger within a 3-tick window as the front sweeps past the player
		boolean shockwavePassingPlayer =
				shockwaveFrontDistance >= distanceMeters
				&& shockwaveFrontDistance < distanceMeters + SHOCKWAVE_SPEED * 3;

		if (!ModEventHandlerClient.nukeShakeTriggeredEntities.contains(entityId)
				&& shockwavePassingPlayer) {

			long shakeMs   = FireballPhysicsCalculator.calculateShakeDurationMs(yieldKt, distanceMeters);
			double amplitude = FireballPhysicsCalculator.calculateShakeAmplitude(yieldKt, distanceMeters);

			if (shakeMs >= 200 && amplitude > 0.02) {
				ModEventHandlerClient.nukeShakeTimestamp  = System.currentTimeMillis();
				ModEventHandlerClient.nukeShakeDurationMs = shakeMs;
				ModEventHandlerClient.nukeShakeAmplitude  = amplitude;

				// Trigger vanilla hurt-camera tilt (identical to RenderTorex.doScreenShake)
				EntityPlayer player = Minecraft.getMinecraft().player;
				if (player != null) {
					int hurtTicks = Math.max(5, (int)(20.0 * amplitude));
					player.hurtTime    = hurtTicks;
					player.maxHurtTime = hurtTicks;
					player.attackedAtYaw = 0F;
				}
			}
			ModEventHandlerClient.nukeShakeTriggeredEntities.add(entityId);
		}

		// === CLEANUP ===
		// Remove dead entities from the tracking sets to avoid memory leak
		if (entity.isDead) {
			ModEventHandlerClient.nukeFlashTriggeredEntities.remove(entityId);
			ModEventHandlerClient.nukeShakeTriggeredEntities.remove(entityId);
		}
	}
}
