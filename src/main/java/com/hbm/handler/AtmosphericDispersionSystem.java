package com.hbm.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.hbm.main.MainRegistry;
import com.hbm.physics.air.AtmosphericStabilityClass;
import com.hbm.physics.air.GaussianPlumeModel;
import com.hbm.physics.air.PlumeSegment;
import com.hbm.physics.air.PlumeSource;
import com.hbm.physics.air.WindField;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

/**
 * Atmospheric dispersion engine for radioactive contamination.
 *
 * Replaces the legacy pocket-to-pocket diffusion in RadiationSystemNT with
 * a physics-accurate Gaussian plume model (SMART code, BNL-116571).
 *
 * -----------------------------------------------------------------------
 * Architecture
 *
 *   Each dimension (World) has its own AtmosphericDispersionSystem stored as
 *   WorldSavedData under the key "hbmAtmoDisp".
 *
 *   The system maintains a list of PlumeSources.  Every server second the
 *   engine:
 *     1. Updates the WindField for the world.
 *     2. For continuous sources, advances the segmented-plume timeline:
 *        every PlumeSource.SEGMENT_INTERVAL_SEC a new PlumeSegment is appended
 *        with the current WindField state (MACCS2 segmented-plume approach,
 *        NUREG/CR-6613 §5).  Old segments are pruned when they expire.
 *     3. For every loaded chunk near a player, evaluates the Gaussian
 *        concentration from all active sources and deposits the result into
 *        RadiationSystemNT via incrementRad().
 *     4. Removes sources that have effectively dissipated.
 *
 * -----------------------------------------------------------------------
 * Offline progression
 *
 *   When a chunk loads (ChunkDataEvent.Load), depositionForChunkLoad() is
 *   called.  It computes the integrated deposition for each source:
 *   - Puff sources: catchUpDt = totalElapsed - src.depositedSeconds
 *   - Continuous sources: sum of seg.catchUpDt(now) over all segments
 *   This covers any period when the chunk was unloaded (including full
 *   server-down time) without requiring the chunk to be loaded while the
 *   plume was active.
 *
 * -----------------------------------------------------------------------
 * Deposition model
 *
 *   Ground-level concentration (SMART 式7):
 *     χ(x,y) = Q/(π·ū·σy·σz) · exp[-(y²/2σy² + H²/2σz²)]
 *
 *   Applied with dry depletion factor f_d (式14-15) and wet scavenging
 *   factor f_w (式17-18) before adding to pocket radiation.
 *
 * -----------------------------------------------------------------------
 * Performance budget
 *
 *   Online deposition scans loaded chunks near players only.
 *   For continuous sources, only the current (active) segment is evaluated
 *   per tick — O(1 segment × chunks) regardless of source age.
 *   The scan is capped at MAX_ONLINE_CHUNKS_PER_SOURCE chunks per source
 *   per tick to avoid lag spikes.
 */
public class AtmosphericDispersionSystem extends WorldSavedData {

    private static final String DATA_NAME = "hbmAtmoDisp";

    // -----------------------------------------------------------------
    // Configuration constants
    // -----------------------------------------------------------------

    /**
     * Minimum deposition threshold [game_rad].
     * Amounts below this are not written to avoid spamming tiny values.
     */
    private static final double MIN_DEPOSITION = 1.0e-6;

    /**
     * Maximum number of loaded chunks to process per source per tick.
     * Scaled to cover the 32-chunk scan radius (65×65 = 4225 chunks per player).
     * Set to 4000 to allow near-full coverage; remaining chunks handled by
     * offline catch-up on chunk load.
     */
    private static final int MAX_ONLINE_CHUNKS_PER_SOURCE = 4000;

    /**
     * Player chunk scan radius [chunks] for online deposition.
     * 32 chunks = 512 m.  The Gaussian plume peak deposition typically occurs
     * at 300–800 m downwind for effective heights of 30–100 m, so a 512 m
     * radius captures the near-field maximum in real time.  Far-field deposition
     * (> 512 m) is handled by offline chunk-load catch-up.
     */
    private static final int PLAYER_SCAN_RADIUS_CHUNKS = 32;

    /**
     * Maximum source emission radius [m].  Sources farther than this from a
     * chunk centre are not evaluated.
     */
    private static final double MAX_SOURCE_RADIUS_M = GaussianPlumeModel.MAX_DOWNWIND_M;

    // -----------------------------------------------------------------
    // Block-source parameters
    // -----------------------------------------------------------------

    /**
     * Effective plume height for ground-level block sources [m].
     * Blocks sit at 0–1 m above ground; 1.5 m represents the centre of the
     * emitting face plus a small thermal plume rise.
     */
    private static final double BLOCK_PLUME_HEIGHT_M = 1.5;

    /**
     * Minimum BlockHazard.radIn value to qualify for atmospheric dispersion.
     * Blocks with radIn below this threshold only contaminate immediate surroundings
     * via RadiationSavedData (unchanged behaviour).
     */
    private static final double MIN_BLOCK_EMISSION = 0.005;

    /**
     * Maximum number of block-sourced plumes that may be active simultaneously.
     * Acts as a CPU / memory safety guard when many radioactive blocks are placed.
     */
    private static final int MAX_BLOCK_SOURCES = 64;

    /**
     * Scale factor converting BlockHazard.radIn [game_rad/update] to an
     * atmospheric emission rate [game_rad·m²/s] for the Gaussian plume model.
     * Tunable to calibrate near-field vs. far-field deposition balance.
     */
    private static final double BLOCK_EMISSION_SCALE = 1.0;

    // -----------------------------------------------------------------
    // Per-world instance cache
    // -----------------------------------------------------------------

    private static final Map<World, AtmosphericDispersionSystem> CACHE = new HashMap<>();

    /** Retrieves (or creates) the dispersion system for the given world. */
    public static AtmosphericDispersionSystem get(World world) {
        AtmosphericDispersionSystem inst = CACHE.get(world);
        if (inst != null) return inst;

        inst = (AtmosphericDispersionSystem) world.getPerWorldStorage()
                .getOrLoadData(AtmosphericDispersionSystem.class, DATA_NAME);
        if (inst == null) {
            inst = new AtmosphericDispersionSystem();
            world.getPerWorldStorage().setData(DATA_NAME, inst);
        }
        inst.world = world;
        CACHE.put(world, inst);
        return inst;
    }

    /** Removes a world from the cache on unload. */
    public static void evict(World world) {
        CACHE.remove(world);
    }

    // -----------------------------------------------------------------
    // State
    // -----------------------------------------------------------------

    private World world;
    private final List<PlumeSource> sources = new ArrayList<>();
    private int nextSourceId = 1;

    /**
     * Maps the BlockPos of each registered BlockHazard to its PlumeSource id.
     * Enables O(1) source removal when the block is broken.
     * Persisted in NBT so placed blocks survive world reloads.
     */
    private final Map<BlockPos, Integer> blockSourceIds = new HashMap<>();

    /**
     * Counter incremented once per {@link #tick()} call.
     * Used to throttle debug logging to once per 60 server seconds.
     */
    private int debugTickCounter = 0;

    // -----------------------------------------------------------------
    // WorldSavedData boilerplate
    // -----------------------------------------------------------------

    public AtmosphericDispersionSystem() {
        super(DATA_NAME);
    }

    public AtmosphericDispersionSystem(String name) {
        super(name);
    }

    // -----------------------------------------------------------------
    // Source registration
    // -----------------------------------------------------------------

    /**
     * Registers a new plume source.  The current WindField state is captured
     * and frozen into the source (for puff sources) or into the first
     * PlumeSegment (for continuous sources).
     *
     * @param posX              World X of release point [m]
     * @param posY              World Y of release point [m]
     * @param posZ              World Z of release point [m]
     * @param effectiveHeightM  Effective release height above ground [m]
     * @param totalGameRad      Total radioactivity to release [game_rad·m²]
     * @param durationSeconds   Release duration [s]; PlumeSource.CONTINUOUS for ongoing
     * @return The registered PlumeSource (for further configuration if needed)
     */
    public PlumeSource registerSource(double posX, double posY, double posZ,
                                       double effectiveHeightM,
                                       double totalGameRad, double durationSeconds) {
        WindField wind = WindField.get(world);
        long now = System.currentTimeMillis();

        PlumeSource src = new PlumeSource(
                nextSourceId++, posX, posY, posZ, effectiveHeightM,
                totalGameRad, durationSeconds,
                wind.windSpeedMs, wind.windDirectionDeg,
                wind.stabilityClass, wind.rainfallMmPerHour,
                now);

        sources.add(src);
        markDirty();

        // [Debug] Log every registered source (rare event — only during meltdowns etc.)
        boolean isContinuous = Double.isInfinite(durationSeconds);
        MainRegistry.logger.info(String.format(
                "[AtmoDisp] Source #%d registered: pos=(%.1f, %.1f, %.1f)  H=%.1f m"
                + "  Q=%.4e game_rad·m²  dur=%s%s",
                src.id, posX, posY, posZ, effectiveHeightM, totalGameRad,
                isContinuous
                        ? "CONTINUOUS"
                        : String.format("%.0f s", durationSeconds),
                isContinuous ? "  [segmented, seg#1]" : ""));
        MainRegistry.logger.info(String.format(
                "[AtmoDisp]   wind: %.2f m/s FROM %.1f°  stability=%s  rainfall=%.1f mm/h"
                + "  totalSources=%d",
                wind.windSpeedMs, wind.windDirectionDeg,
                wind.stabilityClass.name(), wind.rainfallMmPerHour,
                sources.size()));

        return src;
    }

    /**
     * Registers a continuous plume source for a newly placed radioactive block.
     *
     * Called from {@link com.hbm.blocks.generic.BlockHazard#onBlockAdded} when
     * a block with radIn > 0 is placed in the world.  The source runs
     * indefinitely until the block is broken (see {@link #removeBlockSource}).
     *
     * @param pos    Block position of the placed block
     * @param radIn  The block's radIn field value [game_rad/update]
     */
    public void registerBlockSource(BlockPos pos, double radIn) {
        if (radIn < MIN_BLOCK_EMISSION) return;
        if (blockSourceIds.containsKey(pos)) return;          // already tracked
        if (blockSourceIds.size() >= MAX_BLOCK_SOURCES) {
            MainRegistry.logger.warn(String.format(
                    "[AtmoDisp] MAX_BLOCK_SOURCES (%d) reached — block at %s skipped",
                    MAX_BLOCK_SOURCES, pos));
            return;
        }

        double rate = radIn * BLOCK_EMISSION_SCALE;
        PlumeSource src = registerSource(
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                BLOCK_PLUME_HEIGHT_M,
                rate,
                PlumeSource.CONTINUOUS);
        blockSourceIds.put(pos, src.id);
        markDirty();
    }

    /**
     * Stops the plume source associated with a broken radioactive block.
     *
     * The block ceases emitting, but radioactive material already dispersed
     * into the atmosphere continues to travel downwind and deposit on the
     * ground.  The sealed MACCS2 segments remain in the source list and
     * contribute catch-up deposition to any chunk that loads within the next
     * ~5 hours, consistent with NUREG/CR-6613 §5 transport time integration.
     *
     * Called from {@link com.hbm.blocks.generic.BlockHazard#breakBlock}.
     *
     * @param pos Block position of the broken block
     */
    public void removeBlockSource(BlockPos pos) {
        Integer id = blockSourceIds.remove(pos);
        if (id == null) return;
        stopSource(id, System.currentTimeMillis());
        markDirty();
    }

    /**
     * Stops a plume source by its id: seals the active segment and converts
     * continuous → finite.
     *
     * <p>After stopping, the source is NOT removed from the list.  Its sealed
     * segments retain all per-segment meteorological snapshots and continue
     * to provide accurate MACCS2 superposition catch-up deposition for chunks
     * that load later.  {@link PlumeSource#isDissipated} will eventually return
     * true (durationSeconds + 5 h after seal time) and the source will be
     * garbage-collected automatically.
     *
     * <p>This models the real behaviour of airborne radionuclides after a
     * ground-level or elevated release ends: particles/gases already in the
     * atmosphere continue to advect downwind and deposit on receptor surfaces
     * for hours after the source term is zero.
     *
     * @param id  {@link PlumeSource#id} to seal
     */
    public void stopSourceById(int id) {
        stopSource(id, System.currentTimeMillis());
        markDirty();
    }

    /**
     * Registers a finite-duration atmospheric plume source from a nuclear ground burst.
     *
     * <p>This must be called AFTER Phase 3 (blast destruction) has completed in
     * {@link com.hbm.entity.logic.EntityNukeExplosionMK5}, ensuring the correct
     * physical sequence: blast wave → terrain lofting → mushroom cloud rise →
     * wind-driven dispersion of fine particles.
     *
     * <p>Physical basis (Glasstone & Dolan Chapter IX):
     * <ul>
     *   <li>§9.50–9.59: Land surface burst entrains soil into the fireball/stem;
     *       60 % of fission-product activity becomes early local fallout.</li>
     *   <li>§9.84/9.97: Effective wind speed determines elongation of the downwind
     *       fallout plume (cigar shape, Table 9.93).</li>
     *   <li>Fig. 9.96: Mushroom cloud stabilisation height H ≈ 900·W^0.3 m.</li>
     * </ul>
     *
     * <p>The registered source uses a finite {@code durationSeconds} so that
     * MACCS2 segments are automatically sealed once the mushroom cloud clears.
     * Sealed segments continue to provide catch-up deposition (NUREG/CR-6613 §5)
     * for up to 5 hours after the cloud disperses.
     *
     * @param x               explosion epicentre X (blocks)
     * @param y               explosion epicentre Y (blocks)
     * @param z               explosion epicentre Z (blocks)
     * @param plumeHeightM    effective release height in metres
     *                        (≈ 900 × W^0.3, scaled from Fig. 9.96)
     * @param sourceRate      emission rate in game-rad units, derived from
     *                        blast-lofted soil volume × specific fission-product activity
     * @param durationSeconds duration of active emission (mushroom cloud rise time)
     */
    public void registerNuclearBlastSource(double x, double y, double z,
            double plumeHeightM, double sourceRate, double durationSeconds) {
        if (sourceRate < MIN_BLOCK_EMISSION) {
            MainRegistry.logger.warn("[AtmoDisp] Nuclear blast source ignored: sourceRate too low ("
                    + sourceRate + ")");
            return;
        }
        // Clamp duration to sensible bounds (5 min – 2 h)
        double clampedDuration = Math.max(300.0, Math.min(7200.0, durationSeconds));
        PlumeSource src = registerSource(x, y + plumeHeightM, z,
                plumeHeightM, sourceRate, clampedDuration);
        MainRegistry.logger.info(String.format(
                "[AtmoDisp] Nuclear blast source #%d: H=%.0f m  Q=%.4e  dur=%.0f s",
                src.id, plumeHeightM, sourceRate, clampedDuration));
        markDirty();
    }

    private void stopSource(int id, long now) {
        for (PlumeSource src : sources) {
            if (src.id != id) continue;

            // Seal the active segment so it stops accumulating new emission time.
            PlumeSegment curSeg = src.currentSegment();
            if (curSeg != null && curSeg.isActive()) {
                curSeg.endTimeMs = now;
            }

            // Convert continuous → finite:
            //   tick()               will no longer create new segments.
            //   isDissipated()       will return true after (elapsed + 5 h).
            //   depositionForChunk() will still use sealed segments for catch-up.
            if (Double.isInfinite(src.durationSeconds)) {
                src.durationSeconds = src.elapsedSeconds(now);
            }

            double dissipatesInS = src.durationSeconds + 5.0 * 3600.0
                                   - src.elapsedSeconds(now);
            MainRegistry.logger.info(String.format(
                    "[AtmoDisp] Source #%d emission sealed:"
                    + "  pos=(%.1f, %.1f, %.1f)  segs=%d"
                    + "  elapsed=%.0f s  disperses for ~%.0f s more",
                    id, src.posX, src.posY, src.posZ,
                    src.segments.size(), src.elapsedSeconds(now),
                    Math.max(dissipatesInS, 0.0)));
            return;
        }
        // Source already dissipated naturally — nothing to do.
    }

    // -----------------------------------------------------------------
    // Per-tick update (called every server second)
    // -----------------------------------------------------------------

    /**
     * Main update called once per server second from RadiationSystemNT.
     * Advances the wind field, manages plume segments for continuous sources,
     * processes online deposition for all loaded chunks near players, and
     * prunes dissipated sources.
     */
    public void tick() {
        if (world == null || world.isRemote) return;
        if (sources.isEmpty()) return;

        long now = System.currentTimeMillis();

        // 1. Update wind field (global — always stored on / updated from overworld)
        WindField wind = WindField.get(world);
        // Drive wind evolution using overworld weather.  WindField.get() already
        // returns the overworld's instance; pass the overworld world reference so
        // that isRaining/isThundering/isDaytime reflect the correct dimension.
        net.minecraft.world.World windRefWorld = world;
        if (world.provider.getDimension() != 0) {
            net.minecraft.world.WorldServer overworld =
                    net.minecraftforge.common.DimensionManager.getWorld(0);
            if (overworld != null) windRefWorld = overworld;
        }
        wind.update(windRefWorld);

        // Advance throttle counter (used for once-per-minute debug summaries)
        debugTickCounter++;
        boolean logThisTick = (debugTickCounter % 60 == 0);

        // 2. Collect chunk positions near players (online scan zone)
        List<ChunkPos> scanChunks = getNearPlayerChunks();

        // 3. Deposit radiation for each source into loaded chunks
        Iterator<PlumeSource> itr = sources.iterator();
        while (itr.hasNext()) {
            PlumeSource src = itr.next();

            if (src.isDissipated(now)) {
                // [Debug] Log source dissipation (rare event)
                MainRegistry.logger.info(String.format(
                        "[AtmoDisp] Source #%d dissipated: pos=(%.1f, %.1f, %.1f)"
                        + "  elapsed=%.0f s  segs=%d",
                        src.id, src.posX, src.posY, src.posZ,
                        src.elapsedSeconds(now), src.segments.size()));
                itr.remove();
                markDirty();
                continue;
            }

            // --- Segment management for continuous sources ---
            boolean isContinuous = Double.isInfinite(src.durationSeconds)
                                   && !src.segments.isEmpty();
            if (isContinuous) {
                PlumeSegment curSeg = src.currentSegment();
                if (curSeg != null) {
                    long segAgeMs = now - curSeg.startTimeMs;
                    if (segAgeMs >= (long)(PlumeSource.SEGMENT_INTERVAL_SEC * 1000.0)
                            && src.segments.size() < PlumeSource.MAX_SEGMENTS_PER_SOURCE) {
                        // Seal the current segment
                        curSeg.endTimeMs = now;
                        // Create a new segment with the current WindField state
                        PlumeSegment next = new PlumeSegment();
                        next.windSpeedMs           = wind.windSpeedMs;
                        next.windDirectionDeg      = wind.windDirectionDeg;
                        next.stabilityClass        = wind.stabilityClass;
                        next.rainfallMmPerHour     = wind.rainfallMmPerHour;
                        next.startTimeMs           = now;
                        next.endTimeMs             = -1L;
                        next.emissionRatePerSecond = src.emissionRatePerSecond;
                        next.lastUpdateMs          = now;
                        src.segments.add(next);
                        MainRegistry.logger.info(String.format(
                                "[AtmoDisp] Src#%d new segment #%d:"
                                + "  wind %.2f→%.2f m/s  dir %.1f°→%.1f°  stab %s"
                                + "  totalSegs=%d",
                                src.id, src.segments.size(),
                                curSeg.windSpeedMs, wind.windSpeedMs,
                                curSeg.windDirectionDeg, wind.windDirectionDeg,
                                wind.stabilityClass.name(), src.segments.size()));
                    }
                }
                // Prune old sealed segments
                int prunedCount = 0;
                Iterator<PlumeSegment> segItr = src.segments.iterator();
                while (segItr.hasNext()) {
                    if (segItr.next().isPrunable(now)) {
                        segItr.remove();
                        prunedCount++;
                    }
                }
                if (prunedCount > 0) {
                    MainRegistry.logger.info(String.format(
                            "[AtmoDisp] Src#%d pruned %d segment(s)  remaining=%d",
                            src.id, prunedCount, src.segments.size()));
                }
            }

            // --- Timing guard (based on source-level lastUpdateMs) ---
            double dt = (now - src.lastUpdateMs) / 1000.0;
            if (dt < 0.5) continue;

            // --- Effective Q ---
            double Q = src.effectiveQ(now);
            if (Q <= 0.0) {
                src.lastUpdateMs = now;
                continue;
            }

            // --- Select meteorological conditions for this tick ---
            // For any source that has segments (active-continuous OR stopped-with-segs):
            //   use the current (active) segment's frozen wind snapshot.
            //   Stopped sources have no active segment → currentSegment() = null
            //   → falls into puff path, but Q = 0 → skipped above.
            // For pure puff sources (no segments): use source-level frozen fields.
            boolean hasSegments = !src.segments.isEmpty();
            PlumeSegment curSeg = hasSegments ? src.currentSegment() : null;
            double useWindSpd, useWindDir, useRain, useDt;
            AtmosphericStabilityClass useStab;

            if (curSeg != null) {
                double segDt = (now - curSeg.lastUpdateMs) / 1000.0;
                if (segDt < 0.5) {
                    src.lastUpdateMs = now;
                    continue;
                }
                useWindSpd = curSeg.windSpeedMs;
                useWindDir = curSeg.windDirectionDeg;
                useStab    = curSeg.stabilityClass;
                useRain    = curSeg.rainfallMmPerHour;
                useDt      = segDt;
            } else {
                useWindSpd = src.windSpeedMs;
                useWindDir = src.windDirectionDeg;
                useStab    = src.stabilityClass;
                useRain    = src.rainfallMmPerHour;
                useDt      = dt;
            }

            // Tracking variables for per-source debug summary
            int      chunksProcessed = 0;
            double   tickSumDepo     = 0.0;
            double   tickMaxDepo     = 0.0;
            BlockPos tickMaxPos      = null;

            for (ChunkPos cp : scanChunks) {
                if (chunksProcessed >= MAX_ONLINE_CHUNKS_PER_SOURCE) break;

                // Chunk centre at surface level
                double rx = cp.x * 16 + 8.0;
                double rz = cp.z * 16 + 8.0;

                double amount = GaussianPlumeModel.integratedDeposition(
                        Q, src.posX, src.posZ, src.effectiveHeightM,
                        rx, rz,
                        useWindSpd, useWindDir, useStab,
                        GaussianPlumeModel.DEFAULT_VD,
                        useRain,
                        useDt, useDt);

                if (amount >= MIN_DEPOSITION) {
                    // Deposit at the surface block of the chunk centre
                    int surfaceY = world.getHeight((int) rx, (int) rz);
                    BlockPos pos = new BlockPos((int) rx, surfaceY, (int) rz);
                    RadiationSystemNT.incrementRad(world, pos, (float) amount, Float.MAX_VALUE);
                    chunksProcessed++;
                    tickSumDepo += amount;
                    if (amount > tickMaxDepo) {
                        tickMaxDepo = amount;
                        tickMaxPos  = pos;
                    }
                }
            }

            // --- Update timestamps and depositedSeconds ---
            src.lastUpdateMs = now;
            if (curSeg != null) {
                // Continuous: advance the current segment's tracking.
                // Only increment if the plume footprint actually overlapped the
                // scan zone — same logic and rationale as puff sources.
                curSeg.lastUpdateMs = now;
                if (chunksProcessed > 0) {
                    curSeg.depositedSeconds += useDt;
                }
            } else {
                // Puff: existing behaviour.
                if (chunksProcessed > 0) {
                    src.depositedSeconds += dt;
                }
            }

            // [Debug] Per-source tick summary — throttled to once per 60 server seconds
            if (logThisTick) {
                double elapsed = src.elapsedSeconds(now);
                double depSec  = (curSeg != null) ? curSeg.depositedSeconds : src.depositedSeconds;
                String segInfo = hasSegments
                        ? String.format("  segs=%d%s", src.segments.size(),
                                isContinuous ? "" : "(stopped)")
                        : "";
                MainRegistry.logger.info(String.format(
                        "[AtmoDisp] Tick src#%d: elapsed=%.0f s  Qeff=%.4e game_rad·m²/s"
                        + "  dt=%.1f s%s  chunks=%d/%d  sumDepo=%.6f game_rad  depSec=%.0f s",
                        src.id, elapsed, Q, useDt, segInfo,
                        chunksProcessed, scanChunks.size(),
                        tickSumDepo, depSec));

                if (tickMaxPos != null) {
                    double ddx = tickMaxPos.getX() - src.posX;
                    double ddz = tickMaxPos.getZ() - src.posZ;
                    double dist = Math.sqrt(ddx * ddx + ddz * ddz);
                    String windLabel = (curSeg != null) ? "(seg)" : "(frozen)";
                    MainRegistry.logger.info(String.format(
                            "[AtmoDisp]   peak chunk: %.6f game_rad @ (%d, %d) dist=%.0f m"
                            + "  wind%s: %.2f m/s FROM %.1f°  stab=%s",
                            tickMaxDepo,
                            tickMaxPos.getX(), tickMaxPos.getZ(),
                            dist, windLabel,
                            useWindSpd, useWindDir, useStab.name()));
                } else {
                    MainRegistry.logger.info(String.format(
                            "[AtmoDisp]   no chunks deposited  wind: %.2f m/s FROM %.1f°"
                            + "  stab=%s  scanZone=%d chunks",
                            useWindSpd, useWindDir,
                            useStab.name(), scanChunks.size()));
                }
            }
        }

        if (!sources.isEmpty()) markDirty();
    }

    // -----------------------------------------------------------------
    // Offline progression (chunk load event)
    // -----------------------------------------------------------------

    /**
     * Called by RadiationSystemNT when a chunk loads.
     *
     * Computes the integrated deposition that all active plume sources should
     * have contributed to this chunk between each source's online-tick coverage
     * and the current wall-clock time, then deposits the result immediately.
     *
     * For puff sources, catch-up = totalElapsed - src.depositedSeconds.
     * For continuous (segmented) sources, catch-up is summed over all segments:
     *   each segment contributes seg.catchUpDt(now) of integrated deposition.
     *
     * This ensures seamless progression during server downtime or while the
     * chunk was outside the active scan zone.
     *
     * @param chunkPos Position of the newly loaded chunk.
     */
    public void depositionForChunkLoad(ChunkPos chunkPos) {
        if (world == null || world.isRemote || sources.isEmpty()) return;

        long now = System.currentTimeMillis();
        double rx = chunkPos.x * 16 + 8.0;
        double rz = chunkPos.z * 16 + 8.0;

        for (PlumeSource src : sources) {
            if (src.isDissipated(now)) continue;

            // Use segment-based catch-up for any source with sealed segments,
            // including sources stopped mid-emission (block broken, item picked up).
            // Their sealed segments retain per-segment wind data for accurate
            // MACCS2 superposition (NUREG/CR-6613 §5).  Radioactive material
            // that was airborne when the source stopped continues to deposit on
            // chunks as they load, modelling real atmospheric transport.
            boolean hasSegments = !src.segments.isEmpty();

            if (hasSegments) {
                // --- Segmented source (active or stopped) ---
                // Sum catch-up contributions from every segment, each using its
                // own frozen meteorological snapshot.  This is the MACCS2
                // superposition of independent segment plumes.
                double totalAmount = 0.0;
                for (PlumeSegment seg : src.segments) {
                    double catchUpDt = seg.catchUpDt(now);
                    if (catchUpDt < 1.0) continue;

                    double segAmount = GaussianPlumeModel.integratedDeposition(
                            seg.emissionRatePerSecond,
                            src.posX, src.posZ, src.effectiveHeightM,
                            rx, rz,
                            seg.windSpeedMs, seg.windDirectionDeg,
                            seg.stabilityClass,
                            GaussianPlumeModel.DEFAULT_VD,
                            seg.rainfallMmPerHour,
                            catchUpDt, catchUpDt);
                    totalAmount += segAmount;
                }

                if (totalAmount >= MIN_DEPOSITION) {
                    int surfaceY = world.getHeight((int) rx, (int) rz);
                    BlockPos pos = new BlockPos((int) rx, surfaceY, (int) rz);
                    RadiationSystemNT.incrementRad(world, pos, (float) totalAmount, Float.MAX_VALUE);

                    if (totalAmount >= 0.01) {
                        double dist = Math.sqrt(
                                (rx - src.posX) * (rx - src.posX)
                                + (rz - src.posZ) * (rz - src.posZ));
                        double totalElapsed = src.elapsedSeconds(now);
                        MainRegistry.logger.info(String.format(
                                "[AtmoDisp] Chunk-load catch-up: chunk(%d,%d) src#%d"
                                + "  amount=%.4f game_rad  dist=%.0f m"
                                + "  segs=%d  elapsed=%.0f s",
                                chunkPos.x, chunkPos.z, src.id,
                                totalAmount, dist,
                                src.segments.size(), totalElapsed));
                    }
                }

            } else {
                // --- Puff source (original single-snapshot logic) ---
                // catch-up Δt = total elapsed since release MINUS what was already
                // deposited by online ticks to loaded chunks.
                double totalElapsedSeconds = (now - src.releaseTimeMs) / 1000.0;
                double catchUpDt = totalElapsedSeconds - src.depositedSeconds;
                if (catchUpDt < 1.0) continue;

                double Q = src.effectiveQ(now);
                if (Q <= 0.0) continue;

                double amount = GaussianPlumeModel.integratedDeposition(
                        Q, src.posX, src.posZ, src.effectiveHeightM,
                        rx, rz,
                        src.windSpeedMs, src.windDirectionDeg,
                        src.stabilityClass,
                        GaussianPlumeModel.DEFAULT_VD,
                        src.rainfallMmPerHour,
                        catchUpDt, catchUpDt);

                if (amount >= MIN_DEPOSITION) {
                    int surfaceY = world.getHeight((int) rx, (int) rz);
                    BlockPos pos = new BlockPos((int) rx, surfaceY, (int) rz);
                    RadiationSystemNT.incrementRad(world, pos, (float) amount, Float.MAX_VALUE);

                    if (amount >= 0.01) {
                        double dist = Math.sqrt(
                                (rx - src.posX) * (rx - src.posX)
                                + (rz - src.posZ) * (rz - src.posZ));
                        MainRegistry.logger.info(String.format(
                                "[AtmoDisp] Chunk-load catch-up: chunk(%d,%d) src#%d"
                                + "  amount=%.4f game_rad  dist=%.0f m"
                                + "  catchUpDt=%.1f s  elapsed=%.0f s",
                                chunkPos.x, chunkPos.z, src.id,
                                amount, dist, catchUpDt, totalElapsedSeconds));
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Source queries
    // -----------------------------------------------------------------

    /** Returns the current active plume source list (read-only view). */
    public List<PlumeSource> getSources() {
        return sources;
    }

    /** Returns the number of active plume sources. */
    public int getSourceCount() {
        return sources.size();
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Builds the set of chunk positions to process during the online tick by
     * scanning a PLAYER_SCAN_RADIUS_CHUNKS radius around each player.
     */
    private List<ChunkPos> getNearPlayerChunks() {
        List<ChunkPos> result = new ArrayList<>();
        if (world.playerEntities == null) return result;

        java.util.Set<ChunkPos> seen = new java.util.HashSet<>();
        for (net.minecraft.entity.player.EntityPlayer player : world.playerEntities) {
            int pcx = (int) Math.floor(player.posX / 16.0);
            int pcz = (int) Math.floor(player.posZ / 16.0);
            for (int dx = -PLAYER_SCAN_RADIUS_CHUNKS; dx <= PLAYER_SCAN_RADIUS_CHUNKS; dx++) {
                for (int dz = -PLAYER_SCAN_RADIUS_CHUNKS; dz <= PLAYER_SCAN_RADIUS_CHUNKS; dz++) {
                    ChunkPos cp = new ChunkPos(pcx + dx, pcz + dz);
                    if (seen.add(cp)) {
                        result.add(cp);
                    }
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------
    // NBT serialisation
    // -----------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        sources.clear();
        nextSourceId = nbt.getInteger("nextId");

        NBTTagList list = nbt.getTagList("sources", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            sources.add(PlumeSource.readFromNBT(list.getCompoundTagAt(i)));
        }

        // Restore placed-block → source-id mapping
        blockSourceIds.clear();
        if (nbt.hasKey("bsIds")) {
            NBTTagList bsList = nbt.getTagList("bsIds", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < bsList.tagCount(); i++) {
                NBTTagCompound tag = bsList.getCompoundTagAt(i);
                BlockPos p = new BlockPos(
                        tag.getInteger("bsX"),
                        tag.getInteger("bsY"),
                        tag.getInteger("bsZ"));
                blockSourceIds.put(p, tag.getInteger("bsId"));
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("nextId", nextSourceId);

        NBTTagList list = new NBTTagList();
        for (PlumeSource src : sources) {
            NBTTagCompound tag = new NBTTagCompound();
            src.writeToNBT(tag);
            list.appendTag(tag);
        }
        nbt.setTag("sources", list);

        // Persist placed-block → source-id mapping so block sources survive reloads
        if (!blockSourceIds.isEmpty()) {
            NBTTagList bsList = new NBTTagList();
            for (Map.Entry<BlockPos, Integer> e : blockSourceIds.entrySet()) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setInteger("bsX", e.getKey().getX());
                tag.setInteger("bsY", e.getKey().getY());
                tag.setInteger("bsZ", e.getKey().getZ());
                tag.setInteger("bsId", e.getValue());
                bsList.appendTag(tag);
            }
            nbt.setTag("bsIds", bsList);
        }

        return nbt;
    }
}
