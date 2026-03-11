package com.hbm.explosion;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.World;

/**
 * RAY-BASED CONVENTIONAL BOMB EXPLOSION
 *
 * Adapted from ExplosionNukeRayRealistic for conventional TNT-based bombs
 * Uses Fibonacci sphere sampling for realistic directional destruction
 *
 * Key differences from nuclear explosions:
 * - Uses TNT kg instead of kiloton yields
 * - Simpler pressure model (inverse square law)
 * - No Mach stem effects (not significant at this scale)
 * - Faster radius calculation for conventional explosives
 *
 * Performance: Same as ExplosionNukeRayRealistic
 * - Incremental ray processing (count per tick)
 * - Direct block destruction (minimal overhead)
 */
public class ExplosionBombRay {

    // === CORE PARAMETERS ===
    private final World world;
    private final int posX, posY, posZ;
    private final double tntKg;                  // TNT equivalent in kg
    private final int maxRadius;                 // Maximum destruction radius (blocks)

    // === ARMOR PENETRATION SYSTEM ===
    private final int maxPenetration;            // Maximum penetration level at center
    private final int minPenetration;            // Minimum penetration level at max distance
    private final boolean usePenetrationSystem;  // Enable/disable penetration system

    // === FIBONACCI SPHERE SAMPLING ===
    private final int maxSamples;                // Total ray count
    private final float phi;                     // Golden angle
    private int currentSample = 0;               // Current processing position

    // === PROCESSING STATE ===
    private final List<FloatTriplet> affectedBlocks = new ArrayList<>();
    public boolean isRayTracingComplete = false;
    private int processed = 0;

    // === STATISTICS ===
    private int totalRaysTraced = 0;
    private int totalBlocksQueued = 0;

    /**
     * Constructor without armor penetration system (backwards compatible)
     *
     * @param world World
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param tntKg TNT equivalent in kilograms
     */
    public ExplosionBombRay(World world, int x, int y, int z, double tntKg) {
        this(world, x, y, z, tntKg, 0, 0, false);
    }

    /**
     * Constructor with armor penetration system
     *
     * @param world World
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param tntKg TNT equivalent in kilograms
     * @param maxPen Maximum penetration level at center
     * @param minPen Minimum penetration level at max distance
     * @param usePen Enable penetration system
     */
    public ExplosionBombRay(World world, int x, int y, int z, double tntKg, int maxPen, int minPen, boolean usePen) {
        this.world = world;
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.tntKg = tntKg;
        this.maxPenetration = maxPen;
        this.minPenetration = minPen;
        this.usePenetrationSystem = usePen;

        // Explosion radius calculation for conventional bombs
        // Based on Hopkinson-Cranz scaling: R ≈ k × W^(1/3)
        // where W is TNT weight in kg, k is scaling factor
        // For 5 PSI overpressure: k ≈ 8 for TNT
        this.maxRadius = (int)(8.0 * Math.pow(tntKg, 1.0 / 3.0));

        // Fibonacci sphere sampling (same as nuclear version)
        this.maxSamples = (int)(5.0 * Math.PI * maxRadius * maxRadius);
        this.phi = (float)(Math.PI * (3.0 - Math.sqrt(5.0)));

        System.out.println("=== CONVENTIONAL BOMB RAY TRACER ===");
        System.out.println("TNT: " + String.format("%.1f", tntKg) + " kg");
        System.out.println("Max radius: " + maxRadius + " m");
        System.out.println("Total rays: " + maxSamples);
        System.out.println("====================================");
    }

    /**
     * Ray tracing processing (incremental)
     *
     * @param count Number of rays to process this call
     */
    public void collectRays(int count) {
        MutableBlockPos pos = new MutableBlockPos();
        int raysProcessed = 0;

        FloatTriplet lastPos = new FloatTriplet(posX, posY, posZ);

        for (int s = currentSample; s < this.maxSamples; s++) {
            // Fibonacci sphere vector (same as nuclear version)
            FloatTriplet direction = getNormalFibVec(s);

            // Initial ray energy based on TNT content
            // Conventional bombs: simpler energy model
            double rayEnergy = tntKg * 10.0;

            for (int l = 0; l < this.maxRadius + 1; l++) {
                float x0 = (float)(posX + direction.xCoord * l);
                float y0 = (float)(posY + direction.yCoord * l);
                float z0 = (float)(posZ + direction.zCoord * l);

                // World bounds check
                if (y0 < 1 || y0 > 256) {
                    if (affectedBlocks.size() < Integer.MAX_VALUE - 100) {
                        affectedBlocks.add(new FloatTriplet(lastPos.xCoord, lastPos.yCoord, lastPos.zCoord));
                    }
                    break;
                }

                pos.setPos(x0, y0, z0);

                // Calculate pressure at distance using inverse square law
                // P = P0 / (r² + 1) where P0 is initial pressure
                double distance = l + 1.0;
                double pressureFactor = (tntKg * 100.0) / (distance * distance + 1.0);

                // Block resistance calculation
                double blockResistance = getBlockResistance(pos, distance);

                // Energy decay based on block resistance and distance
                rayEnergy -= blockResistance / (pressureFactor + 0.1);

                // Track last solid block position
                if (rayEnergy > 0 && world.getBlockState(pos).getBlock() != Blocks.AIR) {
                    lastPos = new FloatTriplet(x0, y0, z0);
                }

                // Ray exhausted or max range reached
                if (rayEnergy <= 0 || l == this.maxRadius) {
                    if (affectedBlocks.size() < Integer.MAX_VALUE - 100) {
                        affectedBlocks.add(new FloatTriplet(lastPos.xCoord, lastPos.yCoord, lastPos.zCoord));
                        totalBlocksQueued++;
                    }
                    break;
                }
            }

            raysProcessed++;
            totalRaysTraced++;

            if (raysProcessed >= count) {
                currentSample = s + 1;
                return;
            }
        }

        isRayTracingComplete = true;
        System.out.println("[BOMB RAY TRACER] Complete - Rays: " + totalRaysTraced +
                ", Blocks queued: " + totalBlocksQueued);
    }

    /**
     * Calculate armor penetration level based on distance from center
     *
     * @param distance Distance from explosion center
     * @return Penetration level (60 at center, 30 at max distance)
     */
    private int calculatePenetrationLevel(double distance) {
        if (!usePenetrationSystem) {
            return 0;
        }

        // Linear interpolation from maxPenetration to minPenetration
        // At distance=0: penetration = maxPenetration (60)
        // At distance=maxRadius: penetration = minPenetration (30)
        double ratio = Math.min(distance / maxRadius, 1.0);
        int penetration = (int)(maxPenetration - (maxPenetration - minPenetration) * ratio);

        return Math.max(penetration, minPenetration);
    }

    /**
     * Block resistance calculation with armor penetration system
     *
     * Uses Forge hardness system for compatibility with all mods
     * Armor penetration reduces effective block resistance
     */
    private double getBlockResistance(MutableBlockPos pos, double distance) {
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        if (block == Blocks.AIR) {
            return 0.1;
        }

        if (block == Blocks.BEDROCK) {
            return Double.MAX_VALUE;
        }

        // Get Forge hardness
        float hardness = block.getBlockHardness(state, world, pos);

        if (hardness < 0) {
            return Double.MAX_VALUE; // Unbreakable
        }

        // Liquids have low resistance
        if (state.getMaterial().isLiquid()) {
            return 0.5;
        }

        // Distance-based resistance scaling
        // Blocks further from center are easier to break (already weakened)
        double distanceFactor = 1.0 + (distance / (maxRadius * 2.0));

        // Hardness-based resistance
        // Use power scaling: harder blocks resist exponentially more
        double baseResistance = Math.pow(hardness + 0.1, 1.3);

        // === ARMOR PENETRATION SYSTEM ===
        if (usePenetrationSystem) {
            int penetrationLevel = calculatePenetrationLevel(distance);
            // Penetration reduces resistance: higher penetration = lower effective resistance
            // Penetration level 60: reduces resistance by 60%
            // Penetration level 30: reduces resistance by 30%
            double penetrationFactor = 1.0 - (penetrationLevel / 100.0);
            baseResistance *= penetrationFactor;
        }

        return baseResistance * distanceFactor;
    }

    /**
     * Fibonacci sphere vector generation (same as nuclear version)
     */
    private FloatTriplet getNormalFibVec(int sample) {
        double fy = (2.0 * sample / (this.maxSamples - 1.0)) - 1.0;  // y goes from 1 to -1
        double fr = Math.sqrt(1.0 - fy * fy);  // radius at y

        double theta = phi * sample;  // golden angle increment
        return new FloatTriplet(
                (float)(Math.cos(theta) * fr),
                (float)fy,
                (float)(Math.sin(theta) * fr)
        );
    }

    /**
     * Block destruction processing (same as nuclear version)
     *
     * Destroys all blocks along ray path from center to tip
     */
    public void processBlocks(int count) {
        MutableBlockPos pos = new MutableBlockPos();
        int processedBlocks = 0;
        int braker = 0;

        for (int l = 0; l < Integer.MAX_VALUE; l++) {
            if (processedBlocks >= count)
                return;

            if (braker >= count * 50)
                return;

            if (l > affectedBlocks.size() - 1)
                break;

            if (affectedBlocks.isEmpty())
                return;

            int in = affectedBlocks.size() - 1;

            float x = affectedBlocks.get(in).xCoord;
            float y = affectedBlocks.get(in).yCoord;
            float z = affectedBlocks.get(in).zCoord;
            pos.setPos(x, y, z);
            world.setBlockToAir(pos);

            // Destroy all blocks along ray path from center to tip
            double dx = x - this.posX;
            double dy = y - this.posY;
            double dz = z - this.posZ;
            double length = Math.sqrt(dx*dx + dy*dy + dz*dz);

            if (length > 0) {
                double pX = dx / length;
                double pY = dy / length;
                double pZ = dz / length;

                for (int i = 0; i < length; i++) {
                    int x0 = (int)(posX + pX * i);
                    int y0 = (int)(posY + pY * i);
                    int z0 = (int)(posZ + pZ * i);
                    pos.setPos(x0, y0, z0);
                    if (!world.isAirBlock(pos)) {
                        world.setBlockToAir(pos);
                        processedBlocks++;
                    }

                    braker++;
                }
            }

            affectedBlocks.remove(in);
        }
        processed += count;
    }

    // === GETTERS ===

    public boolean isComplete() {
        return isRayTracingComplete && affectedBlocks.isEmpty();
    }

    public List<FloatTriplet> getAffectedBlocks() {
        return affectedBlocks;
    }

    public int getProcessed() {
        return processed;
    }

    // === INNER CLASS ===

    public static class FloatTriplet {
        public float xCoord;
        public float yCoord;
        public float zCoord;

        public FloatTriplet(float x, float y, float z) {
            this.xCoord = x;
            this.yCoord = y;
            this.zCoord = z;
        }
    }
}
