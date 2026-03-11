package com.hbm.radar;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.hbm.physics.RadarWavePhysics;

/**
 * Physics-based radar system with realistic electromagnetic wave propagation
 * Implements realistic radar specifications based on real-world systems (F-15 APG-70)
 *
 * PHYSICAL PROPERTIES:
 * - Radar waves travel at speed of light (c = 3×10^8 m/s)
 * - At 150km range: round-trip time = 1ms (0.001 seconds)
 * - Minecraft tick = 0.05 seconds = 50ms
 * - Conclusion: Radar detection is INSTANTANEOUS within 1 tick
 *
 * RADAR EQUATION:
 * Pr = Pt × RCS / R²
 * - Pt: Transmitted power (dBm)
 * - RCS: Radar Cross Section (m²) - from RadarCrossSection class
 * - R: Distance to target (blocks)
 * - Pr: Received power (dBm)
 *
 * ATMOSPHERIC ATTENUATION:
 * Power loss = 0.998^(distance_km) for X-band (9-10 GHz)
 */
public class PhysicsBasedRadarSystem {

    // Static registry for all radar systems
    private static final Map<UUID, PhysicsBasedRadarSystem> RADAR_SYSTEMS = new ConcurrentHashMap<>();

    // Radar specifications
    public static class RadarSpec {
        public String radarType;
        public double maxRange; // km
        public double azimuthScan; // degrees (total scan width)
        public double elevationScan; // degrees (total scan height)
        public double beamWidth; // degrees (base beam width at close range)
        public double beamSpreadRate; // degrees per km (beam divergence with distance)
        public double transmitPower; // dBm
        public double frequency; // GHz
        public int scanRate; // degrees/second
        public int scanBars; // number of elevation bars in scan pattern
        /**
         * When false, beam-cone angle gating is disabled.
         * Set to false for AESA radars (e.g. SPY-6) that form simultaneous
         * beams in all directions via digital beamforming (DBF), so no
         * narrow-beam gate should block high-elevation targets.
         */
        public boolean beamAngleGatingEnabled = true;

        // ===== PHYSICS PARAMETERS (used by RadarWavePhysics equations) =====

        /**
         * Antenna gain (linear, not dB). Defaults to SPY-1D 42 dBi.
         * Set from aperture physics (Nickel 2006 G≈N) or confirmed system specs.
         */
        public double antennaGainLinear = RadarWavePhysics.SPY1_ANTENNA_GAIN;

        /**
         * Wavelength (m). Defaults to SPY-1D S-band 3.3 GHz (0.0909 m).
         * Should be consistent with the frequency field (λ = c / f).
         */
        public double wavelengthM = RadarWavePhysics.SPY1_WAVELENGTH;

        /**
         * Minimum detectable signal power (W). Defaults to SPY-1D -130 dBm.
         * Determines the maximum theoretical detection range at threshold SNR.
         */
        public double minDetectablePowerW = RadarWavePhysics.SPY1_MIN_POWER;

        /**
         * Total system losses (linear, ≥ 1). Defaults to SPY-1D 8 dB.
         * Includes feed-line losses, noise figure, processing losses, etc.
         */
        public double systemLossesLinear = RadarWavePhysics.SPY1_LOSSES;

        /**
         * F-15 APG-70 radar specification
         */
        public static RadarSpec F15_APG70() {
            RadarSpec spec = new RadarSpec();
            spec.radarType = "F15Radar";
            spec.maxRange = 150.0; // 150km for fighter-sized target
            spec.azimuthScan = 120.0; // ±60°
            spec.elevationScan = 120.0; // ±60°
            spec.beamWidth = 10.0; // 10° beamwidth (realistic is 3°, widened for gameplay)
            spec.beamSpreadRate = 0.3; // 0.3° per km (X-band, smaller antenna = less spreading)
            spec.transmitPower = 100.0; // High power
            spec.frequency = 9.5; // X-band
            spec.scanRate = 120; // degrees/second
            spec.scanBars = 4; // 4-bar scan pattern (realistic APG-70)
            return spec;
        }

        /**
         * Default generic radar specification
         */
        public static RadarSpec getDefault() {
            RadarSpec spec = new RadarSpec();
            spec.radarType = "GenericRadar";
            spec.maxRange = 50.0; // 50km
            spec.azimuthScan = 120.0; // ±60°
            spec.elevationScan = 120.0; // ±60°
            spec.beamWidth = 10.0; // 10°
            spec.beamSpreadRate = 0.4; // 0.4° per km (generic radar)
            spec.transmitPower = 80.0; // Medium power
            spec.frequency = 9.5; // X-band
            spec.scanRate = 100; // degrees/second
            spec.scanBars = 4; // 4-bar scan
            return spec;
        }
    }

    // Detected target information
    public static class RadarContact {
        public Entity entity;
        public double distance; // blocks
        public double azimuth; // degrees relative to source
        public double elevation; // degrees
        public double closureRate; // m/s (positive = closing)
        public long lastDetectionTime; // System.currentTimeMillis()
        public double signalStrength; // dBm
        public boolean isLocked;
        public int trackQuality; // 0-100

        public RadarContact(Entity entity, double distance, double azimuth, double elevation) {
            this.entity = entity;
            this.distance = distance;
            this.azimuth = azimuth;
            this.elevation = elevation;
            this.lastDetectionTime = System.currentTimeMillis();
            this.isLocked = false;
            this.trackQuality = 50;
        }
    }

    private final Entity sourceEntity;
    private final World world;
    private RadarSpec spec;

    // Scan parameters (bar scan pattern)
    private double currentAzimuthScan; // Current scan angle
    private double currentElevationScan; // Current elevation bar angle
    private int currentBarIndex; // Current bar number (0 to scanBars-1)
    private boolean scanDirectionPositive; // true = left to right scan
    private boolean barDirectionUp; // true = scanning upward through bars
    private int ticksSinceLastEmit;
    private long ticksExisted = 0;

    // Detected contacts
    private final Map<UUID, RadarContact> contacts;

    public PhysicsBasedRadarSystem(Entity entity, RadarSpec spec) {
        this.sourceEntity = entity;
        this.world = entity.world;
        this.spec = spec;
        this.contacts = new ConcurrentHashMap<>();
        this.currentAzimuthScan = -this.spec.azimuthScan / 2.0; // Start at left edge
        this.currentBarIndex = 0; // Start at top bar
        this.currentElevationScan = calculateBarElevation(0); // Top bar elevation
        this.scanDirectionPositive = true; // Start scanning left to right
        this.barDirectionUp = false; // Start scanning downward through bars
        this.ticksSinceLastEmit = 0;

        // Register in global map
        RADAR_SYSTEMS.put(entity.getUniqueID(), this);
    }

    /**
     * Get or create radar system for entity
     */
    public static PhysicsBasedRadarSystem getOrCreate(Entity entity, RadarSpec spec) {
        UUID id = entity.getUniqueID();
        if (!RADAR_SYSTEMS.containsKey(id)) {
            return new PhysicsBasedRadarSystem(entity, spec);
        }
        return RADAR_SYSTEMS.get(id);
    }

    /**
     * Get existing radar system
     */
    public static PhysicsBasedRadarSystem get(Entity entity) {
        return RADAR_SYSTEMS.get(entity.getUniqueID());
    }

    /**
     * Update radar system (called every tick)
     */
    public void update() {
        ticksExisted++;

        if (sourceEntity.isDead) {
            return;
        }

        // Clean up dead entities and stale contacts (>5 seconds old)
        long currentTime = System.currentTimeMillis();
        this.contacts.entrySet().removeIf(entry ->
            entry.getValue().entity.isDead ||
            currentTime - entry.getValue().lastDetectionTime > 5000
        );

        // Emit radar waves based on scan pattern
        this.ticksSinceLastEmit++;
        if (this.ticksSinceLastEmit >= 1) { // Emit every tick
            this.ticksSinceLastEmit = 0;
            emitRadarWaves();
        }

        // Update scan pattern
        updateScanPattern();
    }

    /**
     * Program-based radar detection (no entity spawning)
     * Simulates realistic electromagnetic wave propagation at light speed
     *
     * PHYSICAL MODEL:
     * - Radar waves travel at speed of light (c = 3×10^8 m/s)
     * - Detection is instantaneous within game tick
     * - Radar equation: Pr = Pt × RCS / R²
     * - Atmospheric attenuation: 0.998^(distance_km)
     */
    private void emitRadarWaves() {
        String side = this.world.isRemote ? "CLIENT" : "SERVER";

        double yaw = this.sourceEntity.rotationYaw;
        double pitch = this.sourceEntity.rotationPitch;

        // Calculate beam direction
        double totalAzimuth = yaw + this.currentAzimuthScan;
        double totalElevation = pitch + this.currentElevationScan;

        // Convert to radians
        double azRad = Math.toRadians(totalAzimuth);
        double elRad = Math.toRadians(totalElevation);

        // Calculate direction vector
        double cosEl = Math.cos(elRad);
        Vec3d beamDirection = new Vec3d(
            -Math.sin(azRad) * cosEl,
            -Math.sin(elRad),
            Math.cos(azRad) * cosEl
        ).normalize();

        // Scan for targets
        detectTargetsInBeam(beamDirection);
    }

    // ===== REALISM: PHYSICS DELEGATED TO RadarWavePhysics =====

    /**
     * Calculate radar horizon distance using the 4/3 Earth radius model (IEEE Std 686).
     * Delegates to RadarWavePhysics.computeHorizonRange() for correctness and consistency.
     * The 4/3 model accounts for atmospheric refraction of radar waves, extending the
     * effective horizon by ~15% vs the geometric horizon.
     *
     * Formula: R = √(2 × k_e × R_E) × (√h₁ + √h₂),  k_e = 4/3
     *
     * @param radarAltitude  radar altitude above ground (m)
     * @param targetAltitude target altitude above ground (m)
     * @return maximum line-of-sight distance in meters
     */
    private double calculateHorizonDistance(double radarAltitude, double targetAltitude) {
        return RadarWavePhysics.computeHorizonRange(radarAltitude, targetAltitude);
    }

    /**
     * Check if target is within horizon (not hidden by Earth's curvature)
     *
     * @param radarPos Radar position
     * @param targetPos Target position
     * @return true if target is visible (not beyond horizon)
     */
    private boolean isWithinHorizon(Vec3d radarPos, Vec3d targetPos) {
        // Calculate altitudes (Y coordinate in Minecraft)
        double radarAlt = radarPos.y;
        double targetAlt = targetPos.y;

        // Calculate horizon distance for these altitudes
        double horizonDistance = calculateHorizonDistance(radarAlt, targetAlt);

        // Calculate horizontal distance (X-Z plane only, ignore height)
        double dx = targetPos.x - radarPos.x;
        double dz = targetPos.z - radarPos.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        // Target is visible if within horizon distance
        return horizontalDistance <= horizonDistance;
    }

    /**
     * Calculate received power using the full radar range equation via RadarWavePhysics.
     * Formula: Pr = (Pt × G² × λ² × σ) / ((4π)³ × R⁴ × L_sys)
     *
     * Parameters are taken from the radar spec (antennaGainLinear, wavelengthM,
     * systemLossesLinear, transmitPower) set per-sensor in the TileEntity, so
     * SPY-1D, SPY-6, and future radars each use their own correct values.
     *
     * Atmospheric attenuation is applied using RadarWavePhysics.computeAtmosphericAttenuation()
     * which models frequency-dependent (ITU-R P.676) and altitude-dependent (ISA density)
     * two-way signal loss.
     *
     * @param distance   slant range to target (m)
     * @param rcs        target radar cross section (m²)
     * @param meanAltM   mean altitude along the signal path (m) for attenuation scaling
     * @return received power (W)
     */
    private double calculateReceivedPower(double distance, double rcs, double meanAltM) {
        double Pt = this.spec.transmitPower * 1000.0; // spec is in kW → convert to W

        // Full radar equation (Skolnik 3rd ed., eq. 2.1)
        double Pr = RadarWavePhysics.computeReceivedPower(
                Pt,
                this.spec.antennaGainLinear,
                this.spec.wavelengthM,
                rcs,
                distance,
                this.spec.systemLossesLinear);

        // Frequency-aware two-way atmospheric attenuation (ITU-R P.676, ISA density scaling)
        double freqHz = this.spec.frequency * 1.0e9; // spec.frequency is in GHz → Hz
        Pr *= RadarWavePhysics.computeAtmosphericAttenuation(freqHz, distance, meanAltM);

        return Pr;
    }

    /**
     * Maximum detection range for a target with given RCS, using spec parameters.
     * Formula: R_max = ⁴√((Pt × G² × λ² × σ) / ((4π)³ × P_min × L_sys))
     *
     * Does not include atmospheric attenuation (free-space upper bound).
     * Scales as the 4th root of RCS: doubling RCS adds only ~19% range.
     *
     * @param rcs  target radar cross section (m²)
     * @return maximum free-space detection range (m)
     */
    private double calculateMaxRangeForRCS(double rcs) {
        double Pt = this.spec.transmitPower * 1000.0; // kW → W
        return RadarWavePhysics.computeMaxRange(
                Pt,
                this.spec.antennaGainLinear,
                this.spec.wavelengthM,
                rcs,
                this.spec.minDetectablePowerW,
                this.spec.systemLossesLinear);
    }

    /**
     * Check if there is clear line-of-sight from radar to target (ASM-inspired)
     * Uses ray-tracing to check for opaque blocks along the path
     *
     * Optimizations:
     * - Skip check for high-altitude targets (>500m) - aircraft above terrain
     * - Use larger step size (5 blocks) for distant targets (>10km)
     * - Use smaller step size (1 block) for nearby targets (<10km)
     *
     * @param from Radar position
     * @param to Target position
     * @return true if clear line of sight, false if blocked by terrain/buildings
     */
    private boolean hasLineOfSight(Vec3d from, Vec3d to) {
        // Optimization: Skip expensive ray-tracing for high-altitude targets
        // Aircraft at >500m altitude are well above terrain
        if (to.y > 500.0) {
            return true;
        }

        Vec3d direction = to.subtract(from).normalize();
        double distance = from.distanceTo(to);

        // Optimization: Adaptive step size based on distance
        // Nearby targets (<10km): 1 block steps for accuracy
        // Distant targets (>10km): 5 block steps for performance
        double stepSize = (distance > 10000.0) ? 5.0 : 1.0;

        int steps = (int)(distance / stepSize);

        // Ray-trace from radar to target
        for (int i = 1; i < steps; i++) {
            Vec3d checkPos = from.add(direction.scale(i * stepSize));
            BlockPos blockPos = new BlockPos(checkPos);

            // Check if this block is solid/opaque
            IBlockState state = world.getBlockState(blockPos);
            if (state.isOpaqueCube()) {
                // Blocked by terrain or building
                return false;
            }
        }

        // Clear line of sight
        return true;
    }

    /**
     * Detect targets within radar beam using physical radar equation
     */
    private void detectTargetsInBeam(Vec3d beamDirection) {
        Vec3d radarPos = new Vec3d(sourceEntity.posX, sourceEntity.posY, sourceEntity.posZ);
        double maxRange = this.spec.maxRange * 1000.0; // km to blocks

        int detectedCount = 0;
        int totalEntities = 0;

        // CRITICAL: Use world.loadedEntityList
        // This includes:
        // 1. Entities in player-loaded chunks
        // 2. Entities in chunks force-loaded by ForgeChunkManager (missiles with IChunkLoader!)
        //
        // Missiles use EntityMissileBaseAdvanced.loadMainChunk() to force-load their current chunk
        // Therefore, missiles flying to x=10000 will STILL appear in loadedEntityList
        List<Entity> allEntities = new ArrayList<>();

        for (Object obj : this.world.loadedEntityList) {
            if (obj instanceof Entity) {
                allEntities.add((Entity) obj);
            }
        }

        totalEntities = allEntities.size();

        // Debug: Count entity types
        int missileCount = 0;
        int rcsProviderCount = 0;
        for (Entity e : allEntities) {
            if (e.getClass().getSimpleName().contains("Missile")) {
                missileCount++;
            }
            if (e instanceof com.hbm.physics.IRCSProvider) {
                rcsProviderCount++;
            }
        }

        if (ticksExisted % 100 == 0) {
            System.out.println("[RADAR SCAN] Total entities in loadedEntityList: " + totalEntities +
                " | Missiles: " + missileCount +
                " | IRCSProvider: " + rcsProviderCount);
        }

        // Search all entities
        for (Entity entity : allEntities) {
            boolean isMissile = entity.getClass().getSimpleName().contains("Missile");

            // Override isValidRadarTarget() to filter specific entity types
            if (!isValidRadarTarget(entity)) {
                continue;
            }

            // Don't detect self
            if (entity.getUniqueID().equals(this.sourceEntity.getUniqueID())) {
                continue;
            }

            Entity target = entity;
            Vec3d targetPos = new Vec3d(target.posX, target.posY, target.posZ);
            Vec3d toTarget = targetPos.subtract(radarPos);
            double distance = toTarget.length();

            // Skip entities with NaN or infinite positions (can happen when entities
            // drift to extreme altitudes and the physics engine produces degenerate values).
            // These would propagate NaN through the radar equation and create spurious contacts.
            if (Double.isNaN(distance) || Double.isInfinite(distance)) {
                continue;
            }

            if (isMissile) {
                System.out.println("[RADAR DEBUG] Missile passed filter | Distance: " + String.format("%.1f", distance) +
                    " | Pos: (" + String.format("%.1f", target.posX) + ", " +
                    String.format("%.1f", target.posY) + ", " +
                    String.format("%.1f", target.posZ) + ")");
            }

            // Check if within max range
            if (distance > maxRange || distance < 10.0) {
                if (isMissile) {
                    System.out.println("[RADAR DEBUG] ✗ Missile REJECTED by distance check | Distance: " +
                        String.format("%.1f", distance) + " | Range: " + String.format("%.1f", maxRange));
                }
                continue;
            }

            // ===== HORIZON CHECK (ASM-INSPIRED REALISM) =====
            // Check if target is beyond horizon due to Earth's curvature
            if (!isWithinHorizon(radarPos, targetPos)) {
                // Target is below horizon - cannot be detected
                if (isMissile) {
                    System.out.println("[RADAR DEBUG] ✗ Missile REJECTED by horizon check");
                }
                continue;
            }

            // ===== TERRAIN OCCLUSION CHECK (ASM-INSPIRED REALISM) =====
            // Check if line-of-sight is blocked by terrain or buildings
            if (!hasLineOfSight(radarPos, targetPos)) {
                // Blocked by terrain - cannot be detected
                if (isMissile) {
                    System.out.println("[RADAR DEBUG] ✗ Missile REJECTED by line-of-sight check");
                }
                continue;
            }

            // Check if target is within beam cone
            Vec3d toTargetNorm = toTarget.normalize();
            double dotProduct = beamDirection.dotProduct(toTargetNorm);
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dotProduct))));

            // ===== DISTANCE-BASED BEAM SPREADING (ASM-INSPIRED) =====
            // Real radar beams diverge with distance due to diffraction
            // Formula: effectiveBeamWidth = baseBeamWidth + (distance_km) × spreadRate
            //
            // Physical basis:
            // - Near field: narrow beam (precision tracking)
            // - Far field: beam spreads (wide area search)
            //
            // This solves the "gap problem" where targets fall between scan positions
            // at long range while maintaining realistic narrow beam at close range
            double distanceKm = distance / 1000.0; // Convert meters to km
            double effectiveBeamWidth = this.spec.beamWidth + (distanceKm * this.spec.beamSpreadRate);
            double effectiveBeamHalfAngle = effectiveBeamWidth / 2.0;

            if (this.spec.beamAngleGatingEnabled && angle > effectiveBeamHalfAngle) {
                if (isMissile) {
                    System.out.println("[RADAR DEBUG] ✗ Missile REJECTED by beam angle | Angle: " +
                        String.format("%.1f", angle) + "° | Max: " + String.format("%.1f", effectiveBeamHalfAngle) +
                        "° (base: " + String.format("%.1f", this.spec.beamWidth) + "° + spread: " +
                        String.format("%.1f", distanceKm * this.spec.beamSpreadRate) + "°)");
                }
                continue; // Outside beam
            }

            // Calculate Radar Cross Section (RCS)
            double rcs = RadarCrossSection.calculateRCS(target);

            // Mean altitude along signal path — used for ISA atmospheric attenuation scaling.
            // Higher altitude = thinner air = less attenuation per km.
            double meanAltM = (radarPos.y + targetPos.y) / 2.0;

            // ===== FULL RADAR EQUATION (via RadarWavePhysics) =====
            // P_r = (P_t × G² × λ² × σ) / ((4π)³ × R⁴ × L_sys)  [Skolnik eq. 2.1]
            // Followed by frequency-aware ISA atmospheric two-way attenuation.
            // Parameters taken from spec (set per-sensor in TileEntity):
            //   P_t = spec.transmitPower × 1000 W,  G = spec.antennaGainLinear
            //   λ   = spec.wavelengthM,              L = spec.systemLossesLinear
            double receivedPower = calculateReceivedPower(distance, rcs, meanAltM);

            // Detection threshold from spec — each radar type has its own sensitivity.
            // SPY-1D: -130 dBm  |  SPY-6 AMDR: -145 dBm (+15 dB AESA improvement)
            double minDetectablePower = this.spec.minDetectablePowerW;
            if (receivedPower < minDetectablePower) {
                if (isMissile) {
                    System.out.println("[RADAR DEBUG] ✗ Missile REJECTED by power threshold | RCS: " +
                        String.format("%.4f", rcs) + " | Power: " + String.format("%.2e", receivedPower) +
                        " | Min: " + String.format("%.2e", minDetectablePower));
                }
                continue; // Signal too weak
            }

            if (isMissile) {
                System.out.println("[RADAR DEBUG] ✓✓✓ Missile DETECTED! All checks passed!");
            }

            // TARGET DETECTED!
            // Calculate relative angles
            double entityYaw = this.sourceEntity.rotationYaw;
            double entityPitch = this.sourceEntity.rotationPitch;

            // Calculate relative azimuth
            double targetYaw = Math.toDegrees(Math.atan2(toTarget.z, toTarget.x)) - 90.0;
            double relativeAzimuth = normalizeAngle(targetYaw - entityYaw);

            // Calculate relative elevation
            double horizontalDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
            double targetPitch = Math.toDegrees(Math.atan2(-toTarget.y, horizontalDist));
            double relativeElevation = targetPitch - entityPitch;

            System.out.println("[RADAR PHYSICS] Target detected: " + target.getName() +
                               " | Distance: " + String.format("%.1f", distance) + "m" +
                               " | Azimuth: " + String.format("%.1f", relativeAzimuth) + "°" +
                               " | Power: " + String.format("%.2e", receivedPower) + " dBm" +
                               " | RCS: " + String.format("%.4f", rcs) + " m²");
            detectedCount++;

            // Create or update contact
            updateOrCreateContact(target, distance, relativeAzimuth, relativeElevation, receivedPower);
        }

        // Log summary periodically
        if (ticksExisted % 40 == 0) {
            System.out.println("[RADAR PHYSICS] Scan summary - Entities scanned: " + totalEntities +
                               " | Detected: " + detectedCount +
                               " | Scan angle: " + String.format("%.1f", currentAzimuthScan) + "°" +
                               " | Bar: " + (currentBarIndex + 1) + "/" + spec.scanBars);
        }
    }

    /**
     * Override this method to filter specific entity types
     * Default: all entities are valid targets
     */
    protected boolean isValidRadarTarget(Entity entity) {
        // Override in subclass to filter specific entity types
        // Example: return entity instanceof EntityMissile || entity instanceof EntityAircraft;
        return entity != null && !entity.equals(sourceEntity);
    }

    /**
     * Update existing contact or create new one
     */
    private void updateOrCreateContact(Entity target, double distance, double azimuth, double elevation, double signalStrength) {
        UUID targetUUID = target.getUniqueID();
        RadarContact contact = this.contacts.get(targetUUID);

        // Calculate closure rate (radial velocity)
        // Closure rate = rate of distance decrease (positive = approaching)
        double dx = target.posX - sourceEntity.posX;
        double dy = target.posY - sourceEntity.posY;
        double dz = target.posZ - sourceEntity.posZ;

        // Direction unit vector from radar to target
        double dirX = dx / distance;
        double dirY = dy / distance;
        double dirZ = dz / distance;

        // Target velocity (blocks/tick -> m/s: multiply by 20)
        double velX = target.motionX * 20.0;
        double velY = target.motionY * 20.0;
        double velZ = target.motionZ * 20.0;

        // Radial velocity = dot product of velocity and direction
        // Negative because approaching target has negative rate of distance change
        double closureRate = -(velX * dirX + velY * dirY + velZ * dirZ);

        if (contact == null) {
            contact = new RadarContact(target, distance, azimuth, elevation);
            contact.signalStrength = signalStrength;
            contact.closureRate = closureRate;
            this.contacts.put(targetUUID, contact);
            System.out.println("[RADAR PHYSICS] New contact: " + target.getName() +
                               " @ " + String.format("%.1f", distance) + "m");
        } else {
            // Update existing contact
            contact.distance = distance;
            contact.azimuth = azimuth;
            contact.elevation = elevation;
            contact.signalStrength = signalStrength;
            contact.closureRate = closureRate;
            contact.lastDetectionTime = System.currentTimeMillis();
            contact.trackQuality = Math.min(100, contact.trackQuality + 10);
        }
    }

    /**
     * Calculate elevation angle for a specific bar in the scan pattern
     * Bars are distributed across the elevation scan range
     */
    private double calculateBarElevation(int barIndex) {
        if (this.spec.scanBars <= 1) {
            return 0.0; // Single bar at center
        }

        // Distribute bars across elevation range
        // Example: 4 bars across ±60° = -30°, -10°, +10°, +30°
        double elevationRange = this.spec.elevationScan / 2.0;
        double barSpacing = (2.0 * elevationRange) / (this.spec.scanBars + 1);

        return -elevationRange + barSpacing * (barIndex + 1);
    }

    /**
     * Update scan pattern with bi-directional scanning
     * - Horizontal: alternates left-to-right and right-to-left per bar
     * - Vertical: scans through bars downward, then upward, then repeats
     */
    private void updateScanPattern() {
        double scanStep = this.spec.scanRate / 20.0; // degrees per tick (20 ticks/second)

        // Horizontal sweep (alternating direction per bar)
        if (this.scanDirectionPositive) {
            // Sweeping left to right
            this.currentAzimuthScan += scanStep;
            if (this.currentAzimuthScan >= this.spec.azimuthScan / 2.0) {
                // Reached right edge, move to next bar
                moveToNextBar();
                this.scanDirectionPositive = false;
                this.currentAzimuthScan = this.spec.azimuthScan / 2.0;
            }
        } else {
            // Sweeping right to left
            this.currentAzimuthScan -= scanStep;
            if (this.currentAzimuthScan <= -this.spec.azimuthScan / 2.0) {
                // Reached left edge, move to next bar
                moveToNextBar();
                this.scanDirectionPositive = true;
                this.currentAzimuthScan = -this.spec.azimuthScan / 2.0;
            }
        }
    }

    /**
     * Move to next bar with vertical bi-directional scanning
     */
    private void moveToNextBar() {
        if (this.barDirectionUp) {
            this.currentBarIndex--;
            if (this.currentBarIndex < 0) {
                this.barDirectionUp = false;
                this.currentBarIndex = 1;
            }
        } else {
            this.currentBarIndex++;
            if (this.currentBarIndex >= this.spec.scanBars) {
                this.barDirectionUp = true;
                this.currentBarIndex = this.spec.scanBars - 2;
            }
        }

        this.currentElevationScan = calculateBarElevation(this.currentBarIndex);
    }

    /**
     * Normalize angle to -180 to 180
     */
    private double normalizeAngle(double angle) {
        while (angle > 180.0) angle -= 360.0;
        while (angle < -180.0) angle += 360.0;
        return angle;
    }

    // Getters
    public Map<UUID, RadarContact> getContacts() {
        return this.contacts;
    }

    public RadarSpec getSpec() {
        return this.spec;
    }

    public double getCurrentAzimuthScan() {
        return this.currentAzimuthScan;
    }

    public double getCurrentElevationScan() {
        return this.currentElevationScan;
    }

    public int getCurrentBarIndex() {
        return this.currentBarIndex;
    }

    /**
     * Cleanup when entity is destroyed
     */
    public static void remove(Entity entity) {
        RADAR_SYSTEMS.remove(entity.getUniqueID());
    }
}
