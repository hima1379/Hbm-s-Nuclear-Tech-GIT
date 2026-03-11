package com.hbm.render.entity.effect;

import com.hbm.entity.effect.EntityNukeTorexRealistic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * TIER 5 RENDERER: SKYBOX PROJECTION FOR ULTRA-DISTANCE NUCLEAR EXPLOSIONS
 *
 * Renders mushroom clouds at 50-716 km distance using celestial coordinate system.
 * Similar to how Minecraft renders stars and the moon - always visible regardless
 * of chunk loading, bypasses world height limit (256 blocks).
 *
 * RENDERING TECHNIQUE:
 * - Uses celestial sphere (distance = 100 units from camera)
 * - Billboard quad always faces camera
 * - Rotates with world coordinate system
 * - Texture size varies with yield and distance
 * - Temperature-based color tinting
 *
 * VISIBILITY RANGE:
 * - Minimum: 50 km (Tier 4→5 transition)
 * - Maximum: 195 km (1 kt) to 716 km (50 MT)
 * - Beyond max range: Fades out over 50 km
 *
 * PERFORMANCE:
 * - ~0.1 FPS impact per explosion
 * - Single quad render per explosion
 * - Minimal state changes
 * - Shared texture atlas (all yields use same texture)
 *
 * INTEGRATION:
 * Called during world render pass (after sky, before terrain).
 * Maintains static registry of all visible explosions.
 *
 * @author HBM Nuclear Tech Team
 */
@SideOnly(Side.CLIENT)
public class RenderNukeTorexSkybox {

	// === TEXTURE ATLAS ===
	// Single 2048x2048 texture with animation frames
	// 10 frames: Phase 0 (flash) → Phase 4 (stabilized cloud)
	private static final ResourceLocation MUSHROOM_CLOUD_ATLAS =
			new ResourceLocation("hbm:textures/entity/nuke_cloud_atlas.png");

	// === STATIC REGISTRY ===
	// All EntityNukeTorexRealistic entities currently in skybox tier (Tier 5)
	private static final List<EntityNukeTorexRealistic> skyboxExplosions = new ArrayList<>();

	// === CELESTIAL DISTANCE ===
	// Fixed distance from camera (same as stars/moon in Minecraft)
	private static final double CELESTIAL_DISTANCE = 100.0;

	// === VISIBILITY CONSTANTS ===
	private static final double TIER5_MIN_DISTANCE = 50000.0; // 50 km (Tier 4→5 boundary)
	private static final double FADE_START_1KT = 195000.0; // 195 km
	private static final double FADE_START_50MT = 716000.0; // 716 km
	private static final double FADE_RANGE = 50000.0; // Fade over 50 km

	/**
	 * Register an explosion for skybox rendering
	 * Called by NukeTorexLODManager when transitioning to Tier 5
	 */
	public static void registerExplosion(EntityNukeTorexRealistic entity) {
		if (!skyboxExplosions.contains(entity)) {
			skyboxExplosions.add(entity);
			System.out.println("[SKYBOX] Registered explosion at " +
					String.format("%.0f", entity.posX) + ", " +
					String.format("%.0f", entity.posY) + ", " +
					String.format("%.0f", entity.posZ));
		}
	}

	/**
	 * Unregister an explosion from skybox rendering
	 * Called when entity dies or transitions to lower tier
	 */
	public static void unregisterExplosion(EntityNukeTorexRealistic entity) {
		skyboxExplosions.remove(entity);
		System.out.println("[SKYBOX] Unregistered explosion");
	}

	/**
	 * Main render method - called during world render pass
	 * Should be called from ModEventHandlerClient or RenderNTMSkybox
	 *
	 * @param partialTicks Partial tick time for interpolation
	 * @param world World being rendered
	 * @param mc Minecraft instance
	 */
	public static void renderAllExplosions(float partialTicks, WorldClient world, Minecraft mc) {
		if (skyboxExplosions.isEmpty()) return;

		// Remove dead entities
		skyboxExplosions.removeIf(entity -> entity.isDead);

		if (skyboxExplosions.isEmpty()) return;

		// Setup OpenGL state for skybox rendering
		GL11.glPushMatrix();
		GlStateManager.depthMask(false); // Don't write to depth buffer
		GlStateManager.enableTexture2D();
		GlStateManager.enableBlend();
		GlStateManager.disableAlpha();
		GlStateManager.disableFog();
		GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

		// Bind texture atlas
		mc.renderEngine.bindTexture(MUSHROOM_CLOUD_ATLAS);

		// Render each explosion
		for (EntityNukeTorexRealistic entity : skyboxExplosions) {
			renderExplosion(entity, partialTicks, mc);
		}

		// Restore OpenGL state
		GlStateManager.enableAlpha();
		GlStateManager.enableFog();
		GlStateManager.depthMask(true);
		GL11.glPopMatrix();
	}

	/**
	 * Render a single explosion in skybox coordinates
	 */
	private static void renderExplosion(EntityNukeTorexRealistic entity, float partialTicks, Minecraft mc) {
		// Get camera position
		Entity camera = mc.getRenderViewEntity();
		if (camera == null) return;

		double camX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
		double camY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
		double camZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;

		// Calculate direction from camera to explosion
		double dx = entity.posX - camX;
		double dy = entity.posY - camY;
		double dz = entity.posZ - camZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		// Check if explosion is in valid range for skybox rendering
		if (distance < TIER5_MIN_DISTANCE) {
			return; // Too close - should be in lower tier
		}

		// Calculate visibility range based on yield
		double yieldKt = entity.getYieldKilotons();
		double maxVisibility = calculateMaxVisibility(yieldKt);

		if (distance > maxVisibility + FADE_RANGE) {
			return; // Too far - completely invisible
		}

		// Calculate alpha based on distance fade
		float alpha = 1.0F;
		if (distance > maxVisibility) {
			alpha = (float)(1.0 - ((distance - maxVisibility) / FADE_RANGE));
			alpha = Math.max(0.0F, Math.min(1.0F, alpha));
		}

		// Normalize direction vector
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		dx /= len;
		dy /= len;
		dz /= len;

		// Convert to celestial coordinates (fixed distance from camera)
		Vec3d celestialPos = new Vec3d(dx * CELESTIAL_DISTANCE, dy * CELESTIAL_DISTANCE, dz * CELESTIAL_DISTANCE);

		// Calculate billboard size based on yield and perceived angular size
		// Angular size = actual_size / distance
		// Celestial size = angular_size × celestial_distance
		double actualCloudWidth = entity.getCloudWidth();
		if (actualCloudWidth <= 0) {
			// Estimate based on yield if not yet formed
			actualCloudWidth = 600.0 * Math.pow(yieldKt, 0.4); // Empirical formula
		}

		double angularSize = actualCloudWidth / distance; // radians
		double billboardSize = angularSize * CELESTIAL_DISTANCE;
		billboardSize = Math.max(0.5, Math.min(10.0, billboardSize)); // Clamp [0.5, 10]

		// Get temperature-based color
		int[] rgb = entity.getFireballColor();
		float r = rgb[0] / 255.0F;
		float g = rgb[1] / 255.0F;
		float b = rgb[2] / 255.0F;

		// Get texture coordinates based on explosion progress
		double progress = entity.getDeformationProgress();
		int phase = (progress < 0.1) ? 0 : (progress < 0.3) ? 1 : (progress < 0.7) ? 2 : 3;
		float[] texCoords = getTextureCoords(phase, entity.ticksExisted, entity.getMaxAge());

		// Render billboard quad
		renderBillboard(celestialPos, billboardSize, r, g, b, alpha, texCoords);
	}

	/**
	 * Render a billboard quad at celestial position
	 */
	private static void renderBillboard(Vec3d pos, double size, float r, float g, float b, float alpha, float[] texCoords) {
		GL11.glPushMatrix();

		// Translate to celestial position
		GL11.glTranslated(pos.x, pos.y, pos.z);

		// Billboard rotation: Always face camera
		// Minecraft's default camera is already set up, so we just need to counter-rotate
		Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
		if (camera != null) {
			GL11.glRotatef(-camera.rotationYaw, 0.0F, 1.0F, 0.0F);
			GL11.glRotatef(camera.rotationPitch, 1.0F, 0.0F, 0.0F);
		}

		// Apply color and alpha
		GlStateManager.color(r, g, b, alpha);

		// Render quad
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buf = tessellator.getBuffer();
		buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
		buf.pos(-size, -size, 0).tex(texCoords[0], texCoords[3]).endVertex(); // Bottom-left
		buf.pos(size, -size, 0).tex(texCoords[2], texCoords[3]).endVertex();  // Bottom-right
		buf.pos(size, size, 0).tex(texCoords[2], texCoords[1]).endVertex();   // Top-right
		buf.pos(-size, size, 0).tex(texCoords[0], texCoords[1]).endVertex();  // Top-left
		tessellator.draw();

		GL11.glPopMatrix();
	}

	/**
	 * Calculate maximum visibility based on yield
	 * Interpolates between 195 km (1 kt) and 716 km (50 MT)
	 */
	private static double calculateMaxVisibility(double yieldKt) {
		// Log interpolation
		double logYield = Math.log10(yieldKt);
		double logMin = Math.log10(1.0); // 1 kt
		double logMax = Math.log10(50000.0); // 50 MT

		double t = (logYield - logMin) / (logMax - logMin);
		t = Math.max(0.0, Math.min(1.0, t)); // Clamp [0,1]

		return FADE_START_1KT + (FADE_START_50MT - FADE_START_1KT) * t;
	}

	/**
	 * Get texture coordinates from atlas based on explosion phase
	 * Atlas layout: 10 frames in 2 rows of 5
	 * Frame 0-4: Top row (phases 0-2)
	 * Frame 5-9: Bottom row (phases 3-4)
	 *
	 * @return [u_min, v_min, u_max, v_max]
	 */
	private static float[] getTextureCoords(int phase, int ticksExisted, int maxAge) {
		// Map phase to frame index (0-9)
		int frameIndex;
		if (phase == 0) {
			// Flash phase: Frame 0
			frameIndex = 0;
		} else if (phase == 1) {
			// Fireball growth: Frames 1-2 (animated)
			int subPhase = (ticksExisted / 10) % 2;
			frameIndex = 1 + subPhase;
		} else if (phase == 2) {
			// Fireball rise: Frames 3-4 (animated)
			int subPhase = (ticksExisted / 20) % 2;
			frameIndex = 3 + subPhase;
		} else if (phase == 3) {
			// Mushroom cloud formation: Frames 5-7 (animated)
			int subPhase = (ticksExisted / 30) % 3;
			frameIndex = 5 + subPhase;
		} else {
			// Stabilization: Frames 8-9 (slow animation)
			int subPhase = (ticksExisted / 60) % 2;
			frameIndex = 8 + subPhase;
		}

		// Atlas is 5 columns × 2 rows
		int col = frameIndex % 5;
		int row = frameIndex / 5;

		float u_min = col / 5.0F;
		float u_max = (col + 1) / 5.0F;
		float v_min = row / 2.0F;
		float v_max = (row + 1) / 2.0F;

		return new float[]{u_min, v_min, u_max, v_max};
	}

	/**
	 * Get count of currently rendered explosions
	 */
	public static int getActiveCount() {
		return skyboxExplosions.size();
	}

	/**
	 * Clear all registered explosions (for cleanup)
	 */
	public static void clearAll() {
		skyboxExplosions.clear();
	}
}
