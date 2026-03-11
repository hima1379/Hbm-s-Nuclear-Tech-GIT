package com.hbm.entity.logic;

import com.hbm.explosion.ExplosionBombRay;
import com.hbm.lib.ModDamageSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import java.util.List;

/**
 * Entity to manage incremental ray-based conventional bomb explosions
 *
 * Processes explosion over multiple ticks to avoid lag
 * Uses ExplosionBombRay for realistic directional destruction
 * Uses ArmorPenetrationSystem for entity damage
 */
public class EntityBombExplosion extends Entity {

    // === EXPLOSION PROCESSOR ===
    private ExplosionBombRay explosionRay;
    private double tntKg;

    // === PROCESSING STATE ===
    private boolean initialized = false;
    private int raysPerTick = 500;      // Number of rays to trace per tick
    private int blocksPerTick = 100;    // Number of blocks to destroy per tick

    /**
     * Default constructor for entity registry
     */
    public EntityBombExplosion(World world) {
        super(world);
        this.setSize(1.0F, 1.0F);
        this.ignoreFrustumCheck = true;
        this.isImmuneToFire = true;
    }

    /**
     * Factory method to create bomb explosion
     */
    public static EntityBombExplosion create(World world, double x, double y, double z, double tntKg) {
        EntityBombExplosion entity = new EntityBombExplosion(world);
        entity.setPosition(x, y, z);
        entity.tntKg = tntKg;
        return entity;
    }

    @Override
    protected void entityInit() {
        // No data watchers needed
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) {
            return; // Client-side does nothing
        }

        // Initialize explosion processor on first tick
        if (!initialized) {
            System.out.println("[EntityBombExplosion] Started at (" + (int)posX + "," + (int)posY + "," + (int)posZ + ") with " + tntKg + "kg TNT");

            // ★★★ ARMOR PENETRATION SYSTEM - ENTITY DAMAGE ★★★
            // Apply entity damage immediately using armor penetration system
            applyArmorPenetrationDamage(tntKg);

            // Create explosion processor for block destruction
            // Penetration levels: 70 (center) → 30 (edge)
            explosionRay = new ExplosionBombRay(
                    world,
                    (int)posX,
                    (int)posY,
                    (int)posZ,
                    tntKg,
                    70,    // Max penetration at center
                    30,    // Min penetration at edge
                    true   // Enable penetration system
            );
            initialized = true;
        }

        // Process explosion incrementally
        if (explosionRay != null) {
            // Phase 1: Ray tracing
            if (!explosionRay.isRayTracingComplete) {
                explosionRay.collectRays(raysPerTick);
            }
            // Phase 2: Block destruction
            else {
                explosionRay.processBlocks(blocksPerTick);

                // Check if complete
                if (explosionRay.isComplete()) {
                    System.out.println("[EntityBombExplosion] Complete - " + explosionRay.getProcessed() + " blocks destroyed");
                    this.setDead();
                }
            }
        }
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        this.tntKg = compound.getDouble("tntKg");
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        compound.setDouble("tntKg", this.tntKg);
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        return false; // Invisible entity
    }

    /**
     * Apply Armor Penetration System damage to entities
     * Simulates three damage types:
     * 1. BLAST WAVE - Overpressure and shockwave (300m radius)
     * 2. THERMAL RADIATION - Initial fireball heat (100m radius)
     * 3. FRAGMENTATION - High-velocity fragments (200m radius, line-of-sight)
     *
     * Penetration Level: 70 (center) → 30 (edge)
     * Damage scales with penetration level
     *
     * @param warheadMass TNT equivalent mass in kg
     */
    private void applyArmorPenetrationDamage(double warheadMass) {
        // Damage radii based on TNT mass (scales with cube root)
        double scaleFactor = Math.pow(warheadMass / 64.0, 1.0 / 3.0);  // Hopkinson-Cranz scaling
        final double BLAST_RADIUS = 300.0 * scaleFactor;      // Blast overpressure effective range
        final double THERMAL_RADIUS = 100.0 * scaleFactor;    // Thermal radiation effective range
        final double FRAG_RADIUS = 200.0 * scaleFactor;       // Fragmentation effective range

        // Armor Penetration levels
        final int MAX_PENETRATION = 70;         // Center point
        final int MIN_PENETRATION = 30;         // Maximum range

        // Get all entities within blast radius
        List<Entity> affectedEntities = world.getEntitiesWithinAABBExcludingEntity(
                this,
                this.getEntityBoundingBox().grow(BLAST_RADIUS, BLAST_RADIUS, BLAST_RADIUS)
        );

        System.out.println("[ARMOR PENETRATION] Processing " + affectedEntities.size() + " entities (TNT: " + warheadMass + "kg)");

        for (Entity entity : affectedEntities) {
            double distance = this.getDistance(entity);

            // Initialize damage components
            int totalPenetration = 0;
            float totalDamage = 0.0f;
            StringBuilder damageLog = new StringBuilder();

            // ========== 1. BLAST WAVE DAMAGE ==========
            if (distance < BLAST_RADIUS) {
                // Calculate penetration level (linear interpolation: 70 at center → 30 at edge)
                double blastRatio = 1.0 - (distance / BLAST_RADIUS);
                int blastPenetration = (int)(MIN_PENETRATION + (MAX_PENETRATION - MIN_PENETRATION) * blastRatio);

                // Damage scales with penetration level
                // Formula: Damage = BaseDamage × (Penetration / 70)²
                float blastDamage = (float)(200.0 * Math.pow(blastPenetration / 70.0, 2.0));

                totalPenetration += blastPenetration;
                totalDamage += blastDamage;
                damageLog.append(String.format("Blast[Pen:%d,Dmg:%.0f] ", blastPenetration, blastDamage));
            }

            // ========== 2. THERMAL RADIATION DAMAGE ==========
            if (distance < THERMAL_RADIUS) {
                // Thermal damage: Intense at center, drops off rapidly (inverse square law)
                double thermalRatio = 1.0 - (distance / THERMAL_RADIUS);
                int thermalPenetration = (int)(MIN_PENETRATION + (MAX_PENETRATION - MIN_PENETRATION) * thermalRatio);

                // Thermal damage is more intense but shorter range
                float thermalDamage = (float)(150.0 * Math.pow(thermalPenetration / 70.0, 2.5));

                totalPenetration += thermalPenetration;
                totalDamage += thermalDamage;
                damageLog.append(String.format("Thermal[Pen:%d,Dmg:%.0f] ", thermalPenetration, thermalDamage));
            }

            // ========== 3. FRAGMENTATION DAMAGE ==========
            if (distance < FRAG_RADIUS) {
                // Check line-of-sight (fragments blocked by obstacles)
                boolean lineOfSight = hasLineOfSight(entity);

                if (lineOfSight) {
                    // Fragmentation: Random, but deadly at close range
                    double fragRatio = 1.0 - (distance / FRAG_RADIUS);
                    int fragPenetration = (int)(MIN_PENETRATION + (MAX_PENETRATION - MIN_PENETRATION) * fragRatio * 0.8);

                    // Fragment damage: High velocity, direct hit
                    float fragDamage = (float)(250.0 * Math.pow(fragPenetration / 70.0, 2.0));

                    totalPenetration += fragPenetration;
                    totalDamage += fragDamage;
                    damageLog.append(String.format("Frag[Pen:%d,Dmg:%.0f,LOS:YES] ", fragPenetration, fragDamage));
                } else {
                    damageLog.append("Frag[LOS:NO] ");
                }
            }

            // ========== APPLY TOTAL DAMAGE ==========
            if (totalDamage > 0.1f) {
                System.out.println("[ARMOR PENETRATION] " + entity.getName() +
                        " @ " + String.format("%.1fm", distance) +
                        " | Total Penetration: " + totalPenetration +
                        " | Total Damage: " + String.format("%.0f", totalDamage) +
                        " | " + damageLog.toString());

                // Apply damage using ModDamageSource
                if (entity instanceof EntityLivingBase) {
                    EntityLivingBase living = (EntityLivingBase) entity;

                    // Apply damage through armor penetration
                    entity.attackEntityFrom(ModDamageSource.blast, totalDamage);

                    // Check if entity survived (shouldn't happen with penetration damage, but check anyway)
                    if (living.getHealth() > 0 && totalPenetration >= 50) {
                        System.out.println("[ARMOR PENETRATION] High penetration (" + totalPenetration + ") - Force kill");
                        living.setHealth(0.0f);
                        living.onDeath(ModDamageSource.blast);
                        entity.setDead();
                    }
                } else {
                    // Non-living entities
                    entity.attackEntityFrom(ModDamageSource.blast, totalDamage);
                }
            }
        }
    }

    /**
     * Check if entity has line-of-sight to explosion center (for fragmentation)
     * Fragments are blocked by solid blocks
     *
     * @param entity Target entity
     * @return true if line-of-sight exists
     */
    private boolean hasLineOfSight(Entity entity) {
        // Simple raycast from explosion center to entity
        net.minecraft.util.math.Vec3d explosionPos = new net.minecraft.util.math.Vec3d(this.posX, this.posY, this.posZ);
        net.minecraft.util.math.Vec3d entityPos = new net.minecraft.util.math.Vec3d(entity.posX, entity.posY + entity.height / 2.0, entity.posZ);

        // Check if ray is blocked by solid blocks
        net.minecraft.util.math.RayTraceResult result = world.rayTraceBlocks(
                explosionPos,
                entityPos,
                false,  // stopOnLiquid
                true,   // ignoreBlockWithoutBoundingBox
                false   // returnLastUncollidableBlock
        );

        // If result is null, no block was hit (clear line-of-sight)
        // If result is not null, a block was hit (blocked)
        return result == null;
    }
}
