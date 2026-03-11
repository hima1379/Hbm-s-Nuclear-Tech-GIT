package com.hbm.explosion;

import com.hbm.entity.logic.EntityBombExplosion;
import net.minecraft.world.World;

/**
 * Realistic bomb explosion system based on actual WW2 bomb specifications
 * Uses ray-based destruction for realistic directional damage
 *
 * NOW USES RAY TRACING:
 * - ExplosionBombRay for directional destruction (like ExplosionNukeRayRealistic)
 * - Fibonacci sphere sampling for uniform coverage
 * - Incremental processing to prevent lag
 * - No longer uses Minecraft's sphere-based explosion
 *
 * References:
 * - WW2 General Purpose Bombs (Wikipedia)
 * - AN-M64 500lb bomb specifications
 * - TNT equivalent conversion formulas
 *
 * Bomb specifications (TNT content):
 * - 500lb (227kg) bomb: ~267 lbs (121kg) TNT
 * - 1000lb (454kg) bomb: ~500 lbs (227kg) TNT
 * - 2000lb (908kg) bomb: ~1000 lbs (454kg) TNT
 */
public class ExplosionRealisticBomb {

	/**
	 * WW2 bomb types with realistic TNT content and actual weights
	 */
	public enum BombType {
		BOMB_500LB(121.0,  227.0, "500lb General Purpose Bomb"),
		BOMB_1000LB(227.0, 454.0, "1000lb General Purpose Bomb"),
		BOMB_2000LB(454.0, 908.0, "2000lb General Purpose Bomb");

		public final double tntKg;       // TNT explosive content
		public final double totalWeightKg; // Total bomb weight (casing + explosive)
		public final String displayName;

		BombType(double tntKg, double totalWeightKg, String displayName) {
			this.tntKg = tntKg;
			this.totalWeightKg = totalWeightKg;
			this.displayName = displayName;
		}

		/**
		 * Calculate Minecraft explosion power based on TNT content
		 * (Kept for backward compatibility, but no longer used)
		 */
		public float getExplosionPower() {
			double minecraftTNTBlocks = this.tntKg / 4.0;
			float explosionPower = (float)(minecraftTNTBlocks * 0.5);
			return explosionPower;
		}
	}

	/**
	 * Create realistic bomb explosion using RAY-BASED DESTRUCTION
	 *
	 * @param world The world
	 * @param x X coordinate
	 * @param y Y coordinate
	 * @param z Z coordinate
	 * @param bombType Type of bomb
	 * @param cloud Spawn smoke cloud (currently unused - can be added later)
	 * @param rubble Spawn rubble (currently unused - can be added later)
	 * @param shrapnel Spawn shrapnel (currently unused - can be added later)
	 */
	public static void explode(World world, double x, double y, double z, BombType bombType,
			boolean cloud, boolean rubble, boolean shrapnel) {

		// Spawn ray-based explosion entity
		EntityBombExplosion explosionEntity = EntityBombExplosion.create(world, x, y, z, bombType.tntKg);
		world.spawnEntity(explosionEntity);
	}

	/**
	 * Create realistic bomb explosion with fire
	 * (Currently same as normal explosion - fire effects can be added later)
	 */
	public static void explodeFire(World world, double x, double y, double z, BombType bombType,
			boolean cloud, boolean rubble, boolean shrapnel) {

		// For now, use same explosion system
		explode(world, x, y, z, bombType, cloud, rubble, shrapnel);
	}

	/**
	 * Debug: Log explosion parameters
	 */
	public static void logExplosion(BombType bombType, double x, double y, double z) {
		com.hbm.main.MainRegistry.logger.info("=== REALISTIC BOMB EXPLOSION (RAY-BASED) ===");
		com.hbm.main.MainRegistry.logger.info("Bomb: " + bombType.displayName);
		com.hbm.main.MainRegistry.logger.info("TNT Content: " + bombType.tntKg + " kg");
		com.hbm.main.MainRegistry.logger.info("Explosion Method: Ray-traced (Fibonacci sphere)");
		com.hbm.main.MainRegistry.logger.info("Location: X=" + (int)x + " Y=" + (int)y + " Z=" + (int)z);
		com.hbm.main.MainRegistry.logger.info("===========================================");
	}
}
