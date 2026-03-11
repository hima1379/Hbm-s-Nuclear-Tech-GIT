package com.hbm.entity.effect;

import java.util.List;

import com.hbm.interfaces.IConstantRenderer;
import com.hbm.main.MainRegistry;
import com.hbm.saveddata.RadiationSavedData;
import com.hbm.util.ContaminationUtil;
import com.hbm.util.ContaminationUtil.ContaminationType;
import com.hbm.util.ContaminationUtil.HazardType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * EntityBlackRain — Radioactively contaminated rainfall following a nuclear surface burst.
 *
 * Physical basis (Hiroshima/Nagasaki historical records):
 *  - Black, oily, radioactively-contaminated rain fell within ~20 min to 5 hours
 *    of detonation, up to ~10–15 km from the hypocentre for a 15 kt weapon.
 *  - Rain droplets carried soot, dust, and fission-product-bearing particles
 *    entrained from the fireball and mushroom cloud base-surge.
 *  - People caught in the open under the rain received additional dose from
 *    skin contamination and inhalation of radioactive aerosols.
 *
 * In-game behaviour:
 *  1. SERVER — every 20 ticks (1 s):
 *      a. Irradiates exposed EntityLivingBase instances within radius.
 *         "Exposed" = world.canSeeSky() is true at their position
 *         (inside buildings / underground is shielded, per Glasstone §9.120).
 *      b. Contaminates ground at ~50 randomly sampled positions in the radius
 *         via RadiationSavedData.incrementRad().
 *  2. CLIENT — every tick: spawns black smoke particles falling from above
 *     to give a visual impression of dark rain.
 *
 * Irradiation dose model:
 *  - Base rate (Bq/s equivalent) set at construction from weapon yield.
 *  - Intensity = baseRate × (1 − dist/radius)² — heavier rain near epicentre.
 *  - The (1−r/R)² profile mirrors the rainout efficiency curve in
 *    Glasstone & Dolan Table 9.74a: closer = more particles = higher dose.
 *
 * Duration: 6 000 ticks (5 min game time). Removed when timer expires.
 */
public class EntityBlackRain extends Entity implements IConstantRenderer {

    private static final DataParameter<Integer> RAIN_RADIUS =
            EntityDataManager.createKey(EntityBlackRain.class, DataSerializers.VARINT);

    /** Effective radius of the black-rain area in blocks (1 block = 1 m). */
    private int blackRainRadius = 200;

    /** Remaining server ticks until the rain ceases. */
    private int remainingTicks = 6000; // 5 minutes

    /** Base radiation rate per second at ground zero (scaled from yield). */
    private float baseRadiationRate = 0.05F;

    /** Client-side: tick counter for particle spawning rate control. */
    private int clientTick = 0;

    // -----------------------------------------------------------------------

    public EntityBlackRain(World world) {
        super(world);
        this.setSize(1.0F, 1.0F);
        this.ignoreFrustumCheck = true;
        this.isImmuneToFire = true;
        this.noClip = true;
    }

    @Override
    protected void entityInit() {
        this.dataManager.register(RAIN_RADIUS, 200);
    }

    /**
     * Configure the black rain area.
     *
     * @param radius     Radius in blocks. Based on explosion radius × 2 so the rain
     *                   covers the area beyond the immediate fireball crater.
     * @param yieldKt    Total weapon yield in kilotons; scales the contamination rate.
     *                   Historical: 15 kt Hiroshima → ~10 km radius; larger yields scale
     *                   with yield^0.4 (particle transport distance).
     */
    public void setScale(int radius, float yieldKt) {
        this.blackRainRadius = radius;
        this.dataManager.set(RAIN_RADIUS, radius);

        // Base contamination rate: proportional to fission-product activity at H+0.
        // Glasstone §9.14: ~2 900 R/hr per kt fission per mile² at H+1; we use a
        // heavily scaled-down value appropriate for the Minecraft radiation system.
        // fissionFraction ≈ 0.5 assumed; the exponent 0.6 accounts for dilution with
        // the larger soil mass entrained by higher-yield bursts.
        this.baseRadiationRate = (float)(0.04 * Math.pow(Math.max(1.0, yieldKt), 0.6));
    }

    public int getBlackRainRadius() {
        return this.dataManager.get(RAIN_RADIUS);
    }

    // -----------------------------------------------------------------------

    @Override
    public void onUpdate() {
        if (world.isRemote) {
            spawnClientParticles();
            return;
        }

        // Tick down and die when the rain ends.
        remainingTicks--;
        if (remainingTicks <= 0) {
            this.setDead();
            return;
        }

        // Once per second: apply radiation effects.
        if (remainingTicks % 20 == 0) {
            irradiateExposedEntities();
            contaminateGroundSamples();
        }
    }

    // -----------------------------------------------------------------------
    // SERVER — entity irradiation
    // -----------------------------------------------------------------------

    private void irradiateExposedEntities() {
        int r = blackRainRadius;
        List<EntityLivingBase> nearby = world.getEntitiesWithinAABB(
                EntityLivingBase.class,
                new AxisAlignedBB(posX - r, 0, posZ - r,
                                  posX + r, 256, posZ + r));

        for (EntityLivingBase entity : nearby) {
            double dx = entity.posX - posX;
            double dz = entity.posZ - posZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > r) continue;

            // Shielded if inside a building / underground
            // (Glasstone §9.120: frame house gives factor 0.3–0.6; underground = ~0.0002)
            if (!world.canSeeSky(new BlockPos(entity))) continue;

            // Intensity falls off as (1 − r/R)² — highest under the plume core.
            double relDist = dist / r;
            float intensity = (float)(baseRadiationRate * (1.0 - relDist) * (1.0 - relDist));
            if (intensity < 0.001F) continue;

            ContaminationUtil.contaminate(entity, HazardType.RADIATION,
                    ContaminationType.CREATIVE, intensity);
        }
    }

    // -----------------------------------------------------------------------
    // SERVER — ground contamination
    // -----------------------------------------------------------------------

    private void contaminateGroundSamples() {
        int r = blackRainRadius;
        // ~50 random sample points per second — statistically samples the whole
        // area over the 5-minute rain duration without being computationally heavy.
        for (int i = 0; i < 50; i++) {
            double angle = world.rand.nextDouble() * Math.PI * 2.0;
            double dist  = world.rand.nextDouble() * r;
            int sx = (int)(posX + Math.cos(angle) * dist);
            int sz = (int)(posZ + Math.sin(angle) * dist);

            double relDist = dist / r;
            float rad = (float)(baseRadiationRate * 0.01
                    * (1.0 - relDist) * (1.0 - relDist));
            if (rad < 1e-5F) continue;

            RadiationSavedData.incrementRad(world,
                    new BlockPos(sx, 64, sz),   // Y is irrelevant for chunk-based storage
                    rad, rad * 20F);
        }
    }

    // -----------------------------------------------------------------------
    // CLIENT — visual particles
    // -----------------------------------------------------------------------

    private void spawnClientParticles() {
        // -----------------------------------------------------------------------
        // Spawn particles centered on the LOCAL PLAYER, not on the entity.
        // The entity sits at the explosion epicentre; spawning there would be
        // invisible if the player has moved away. Instead sample a disc around
        // the player and verify each point is inside the rain radius.
        // -----------------------------------------------------------------------
        net.minecraft.client.entity.EntityPlayerSP player =
                net.minecraft.client.Minecraft.getMinecraft().player;
        if (player == null) return;

        int R = getBlackRainRadius();
        // Skip entirely if player is outside the rain circle.
        double edx = player.posX - posX;
        double edz = player.posZ - posZ;
        if (edx * edx + edz * edz > (double) R * R) return;

        // --- FALLING BLACK RAIN STREAKS (SMOKE_LARGE, high → ground) -----------
        // 60 particles/tick with fast downward velocity creates a dense curtain
        // of dark streaks unmistakably visible as heavy black rain.
        for (int i = 0; i < 60; i++) {
            double dist  = Math.sqrt(world.rand.nextDouble()) * 24.0; // uniform disc
            double angle = world.rand.nextDouble() * Math.PI * 2.0;
            double px = player.posX + Math.cos(angle) * dist;
            double pz = player.posZ + Math.sin(angle) * dist;
            double py = player.posY + 14.0 + world.rand.nextDouble() * 28.0; // high above
            world.spawnParticle(EnumParticleTypes.SMOKE_LARGE,
                    px, py, pz,
                    (world.rand.nextDouble() - 0.5) * 0.03,   // vx: near-vertical
                    -0.85 - world.rand.nextDouble() * 0.55,   // vy: fast downward
                    (world.rand.nextDouble() - 0.5) * 0.03);  // vz: near-vertical
        }

        // --- GROUND SOOT / SPLASH (SMOKE_NORMAL rising after impact) -----------
        // Smaller particles at ground level simulate the black oily residue
        // characteristic of the Hiroshima / Nagasaki black rain phenomenon.
        for (int i = 0; i < 20; i++) {
            double dist  = Math.sqrt(world.rand.nextDouble()) * 16.0;
            double angle = world.rand.nextDouble() * Math.PI * 2.0;
            double px = player.posX + Math.cos(angle) * dist;
            double pz = player.posZ + Math.sin(angle) * dist;
            world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
                    px, player.posY + world.rand.nextDouble() * 0.5, pz,
                    (world.rand.nextDouble() - 0.5) * 0.12,
                     0.04 + world.rand.nextDouble() * 0.08,
                    (world.rand.nextDouble() - 0.5) * 0.12);
        }
    }

    // -----------------------------------------------------------------------
    // IConstantRenderer — always render regardless of camera distance
    // -----------------------------------------------------------------------

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(posX, posY, posZ, posX, posY, posZ);
    }

    @Override
    public boolean isInRangeToRender3d(double x, double y, double z) {
        return true;
    }

    @Override
    public boolean isInRangeToRenderDist(double distance) {
        return true;
    }

    // -----------------------------------------------------------------------
    // NBT
    // -----------------------------------------------------------------------

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        blackRainRadius    = nbt.getInteger("radius");
        remainingTicks     = nbt.getInteger("ticks");
        baseRadiationRate  = nbt.getFloat("baseRad");
        this.dataManager.set(RAIN_RADIUS, blackRainRadius);
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setInteger("radius",  blackRainRadius);
        nbt.setInteger("ticks",   remainingTicks);
        nbt.setFloat("baseRad",   baseRadiationRate);
    }
}
