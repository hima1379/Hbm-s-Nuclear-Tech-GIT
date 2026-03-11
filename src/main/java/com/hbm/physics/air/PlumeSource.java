package com.hbm.physics.air;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * Represents a single radioactive atmospheric plume source for the Gaussian
 * dispersion model.
 *
 * A PlumeSource encapsulates all parameters needed by {@link GaussianPlumeModel}
 * to compute the ground deposition pattern at any distance downwind:
 *
 *   - Source position and effective release height
 *   - Total emitted quantity Q [game_rad·m²] for a finite puff, or continuous
 *     emission rate [game_rad·m²/s] for ongoing sources
 *   - Wind conditions and stability class (frozen for puff sources; tracked per
 *     segment for continuous sources — see PlumeSegment)
 *   - Wall-clock timestamps for deposition and duration tracking
 *
 * -----------------------------------------------------------------------
 * Source types
 *
 *   Finite (puff)  – An instantaneous or short-duration burst (e.g. nuclear
 *       explosion fallout).  The entire quantity totalEmissionGameRad is
 *       released over durationSeconds.  After the duration elapses the source
 *       becomes quiescent but is retained until it effectively dissipates.
 *       Wind conditions are frozen at registration time (single snapshot).
 *
 *   Continuous     – An ongoing emission (e.g. damaged reactor, burning fuel
 *       rod).  emissionRatePerSecond [game_rad·m²/s] is constant until
 *       explicitly stopped.  durationSeconds == CONTINUOUS.
 *       Wind conditions evolve over time via PlumeSegment (MACCS2 segmented
 *       plume method): every SEGMENT_INTERVAL_SEC seconds a new segment is
 *       appended with the current WindField state, allowing realistic wind
 *       variation over multi-day events.
 *
 * -----------------------------------------------------------------------
 * Segmented plume (continuous sources only)
 *
 *   The segments list holds one PlumeSegment per SEGMENT_INTERVAL_SEC window.
 *   The most recent segment is always active (endTimeMs == -1).  Old segments
 *   are pruned after PRUNE_AFTER_MS to bound memory usage to ~22 segments
 *   regardless of source duration (see PlumeSegment.PRUNE_AFTER_MS).
 *
 *   Puff sources leave segments empty and use the frozen windSpeedMs /
 *   windDirectionDeg / stabilityClass / rainfallMmPerHour fields directly.
 *
 * -----------------------------------------------------------------------
 * Offline progression
 *
 *   releaseTimeMs is set at the moment the source is registered.  When a
 *   previously unloaded chunk loads, the dispersion system computes the
 *   integrated deposition from each segment's active window and deposits the
 *   accumulated radiation immediately.
 *
 * -----------------------------------------------------------------------
 * Unit conventions
 *
 *   All positions are in Minecraft block coordinates (1 block = 1 metre).
 *   Game radiation units are the same values stored in RadPocket.radiation.
 *   Q [game_rad·m²/s] is chosen so that the Gaussian formula yields
 *   [game_rad/s] at any receptor without additional scaling.
 */
public class PlumeSource {

    // Sentinel value indicating the source runs indefinitely.
    public static final double CONTINUOUS = Double.POSITIVE_INFINITY;

    /**
     * Minimum residual emission below which the source is considered
     * dissipated and eligible for removal [game_rad/s].
     */
    public static final double DISSIPATION_THRESHOLD = 1.0e-4;

    /**
     * Duration of each meteorological segment for continuous sources [s].
     * Every SEGMENT_INTERVAL_SEC seconds the current wind state is frozen into
     * a new PlumeSegment, implementing the MACCS2 segmented-plume approach.
     * Value: 900 s (15 minutes).
     */
    public static final double SEGMENT_INTERVAL_SEC = 900.0;

    /**
     * Maximum number of segments a continuous source may accumulate.
     * 288 = 3 days ÷ 15 min.  Pruning (see PlumeSegment.isPrunable) ensures
     * at most ~22 segments are live at any time; this cap is a safety backstop.
     */
    public static final int MAX_SEGMENTS_PER_SOURCE = 288;

    // -----------------------------------------------------------------
    // Source identification
    // -----------------------------------------------------------------

    /** Unique identifier used for NBT de-duplication (assigned by dispersion system). */
    public int id;

    // -----------------------------------------------------------------
    // Source geometry
    // -----------------------------------------------------------------

    /** World X coordinate of the release point [block / m]. */
    public double posX;

    /** World Y coordinate of the release point [block / m]. */
    public double posY;

    /** World Z coordinate of the release point [block / m]. */
    public double posZ;

    /**
     * Effective plume height H above the ground [m].
     *
     * For nuclear ground bursts: typically 0–50 m.
     * For reactor accidents:     stack height (effective height includes
     *                            plume rise from thermal buoyancy).
     */
    public double effectiveHeightM;

    // -----------------------------------------------------------------
    // Emission parameters
    // -----------------------------------------------------------------

    /**
     * Total quantity emitted by a finite (puff) source [game_rad·m²].
     * For continuous sources this equals the total released so far
     * (updated each server tick).
     */
    public double totalEmissionGameRad;

    /**
     * Instantaneous emission rate [game_rad·m²/s].
     * For finite puffs: totalEmissionGameRad / durationSeconds.
     * For continuous: constant while source is active.
     */
    public double emissionRatePerSecond;

    /**
     * Total emission duration [s].
     * CONTINUOUS (Infinity) for ongoing sources.
     */
    public double durationSeconds;

    // -----------------------------------------------------------------
    // Meteorological conditions frozen at time of release
    // (authoritative for puff sources; kept for backward-compat on continuous)
    // -----------------------------------------------------------------

    /** Wind speed at the time of release [m/s]. */
    public double windSpeedMs;

    /** Wind direction at the time of release [degrees, clockwise from North]. */
    public double windDirectionDeg;

    /** Pasquill-Gifford stability class at the time of release. */
    public AtmosphericStabilityClass stabilityClass;

    /** Rainfall rate at the time of release [mm/h]. */
    public double rainfallMmPerHour;

    // -----------------------------------------------------------------
    // Time tracking
    // -----------------------------------------------------------------

    /** Wall-clock time when this source started releasing [ms since epoch]. */
    public long releaseTimeMs;

    /**
     * Wall-clock time when the last regular (online) deposition update was
     * performed [ms since epoch].  Used by the online tick to compute Δt.
     * 0L = no update has been performed yet.
     */
    public long lastUpdateMs;

    /**
     * Total seconds of online-tick deposition already applied to loaded chunks.
     *
     * Used by puff sources only.  For continuous segmented sources, deposition
     * accounting is delegated to each PlumeSegment's depositedSeconds field.
     *
     * The online tick deposits `rate * dt` to every loaded chunk each second and
     * increments this counter by dt.  When a previously-unloaded chunk finally
     * loads, the catch-up deposition covers:
     *
     *   catch-up Δt = (now - releaseTimeMs)/1000  -  depositedSeconds
     *
     * This avoids double-counting for chunks that were loaded throughout (they
     * already received the online deposits) while ensuring unloaded chunks get
     * the full accumulated dose when they are first visited.
     */
    public double depositedSeconds = 0.0;

    // -----------------------------------------------------------------
    // Segmented plume (continuous sources only)
    // -----------------------------------------------------------------

    /**
     * Ordered list of meteorological segments for this continuous source.
     * Empty for puff sources.  The last element is always the currently active
     * segment (endTimeMs == -1).  Segments are appended every
     * SEGMENT_INTERVAL_SEC and pruned once they exceed PRUNE_AFTER_MS.
     */
    public List<PlumeSegment> segments = new ArrayList<>();

    // -----------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------

    public PlumeSource() {}

    /**
     * Full constructor.  For continuous sources, creates the initial
     * PlumeSegment from the supplied meteorological snapshot.
     *
     * @param id                   Unique identifier
     * @param posX                 Release X [m]
     * @param posY                 Release Y [m]
     * @param posZ                 Release Z [m]
     * @param effectiveHeightM     Effective plume height [m]
     * @param totalEmissionGameRad Total radioactivity for puff [game_rad·m²],
     *                             or rate [game_rad·m²/s] for continuous
     * @param durationSeconds      Duration of the puff [s]; use CONTINUOUS for ongoing
     * @param windSpeedMs          Wind speed at release [m/s]
     * @param windDirectionDeg     Wind direction at release [deg]
     * @param cls                  Stability class at release
     * @param rainfallMmPerHour    Rainfall at release [mm/h]
     * @param releaseTimeMs        Wall-clock release time [ms]
     */
    public PlumeSource(int id, double posX, double posY, double posZ,
                        double effectiveHeightM,
                        double totalEmissionGameRad, double durationSeconds,
                        double windSpeedMs, double windDirectionDeg,
                        AtmosphericStabilityClass cls, double rainfallMmPerHour,
                        long releaseTimeMs) {
        this.id                   = id;
        this.posX                 = posX;
        this.posY                 = posY;
        this.posZ                 = posZ;
        this.effectiveHeightM     = effectiveHeightM;
        this.totalEmissionGameRad = totalEmissionGameRad;
        this.durationSeconds      = durationSeconds;
        this.windSpeedMs          = windSpeedMs;
        this.windDirectionDeg     = windDirectionDeg;
        this.stabilityClass       = cls;
        this.rainfallMmPerHour    = rainfallMmPerHour;
        this.releaseTimeMs        = releaseTimeMs;
        this.lastUpdateMs         = releaseTimeMs;

        if (Double.isInfinite(durationSeconds) || durationSeconds <= 0.0) {
            // Continuous source: rate is specified directly as totalEmission
            this.emissionRatePerSecond = totalEmissionGameRad;
            // Create the initial segment using the registration-time wind snapshot.
            PlumeSegment first = new PlumeSegment();
            first.windSpeedMs           = windSpeedMs;
            first.windDirectionDeg      = windDirectionDeg;
            first.stabilityClass        = cls;
            first.rainfallMmPerHour     = rainfallMmPerHour;
            first.startTimeMs           = releaseTimeMs;
            first.endTimeMs             = -1L;
            first.emissionRatePerSecond = this.emissionRatePerSecond;
            first.lastUpdateMs          = releaseTimeMs;
            this.segments.add(first);
        } else {
            this.emissionRatePerSecond = totalEmissionGameRad / durationSeconds;
        }
    }

    // -----------------------------------------------------------------
    // State queries
    // -----------------------------------------------------------------

    /**
     * Returns the elapsed time since release [seconds] at wall-clock instant
     * nowMs.
     */
    public double elapsedSeconds(long nowMs) {
        return Math.max((nowMs - releaseTimeMs) / 1000.0, 0.0);
    }

    /**
     * Returns the active emission rate at wall-clock instant nowMs.
     *
     * For finite puff sources the rate is emissionRatePerSecond during the
     * release window and 0 thereafter.  For continuous sources it is always
     * emissionRatePerSecond.
     */
    public double activeRateAt(long nowMs) {
        if (Double.isInfinite(durationSeconds)) {
            return emissionRatePerSecond;
        }
        double t = elapsedSeconds(nowMs);
        return t <= durationSeconds ? emissionRatePerSecond : 0.0;
    }

    /**
     * Returns the effective Q [game_rad·m²/s] to pass to the Gaussian model
     * at the given moment.  Accounts for depletion (already-deposited fraction
     * reduces the remaining airborne activity).
     *
     * For simplicity, Q is treated as constant over the emission duration (the
     * exact depletion is handled by GaussianPlumeModel.dryDepletionFactor).
     */
    public double effectiveQ(long nowMs) {
        return activeRateAt(nowMs);
    }

    /**
     * Returns true if this source has effectively dissipated and can be
     * removed from the active source list.
     *
     * A source is considered dissipated when:
     *   (a) It is finite and more than 5× the dispersion time beyond its
     *       release duration has elapsed, OR
     *   (b) Its emission rate has fallen below DISSIPATION_THRESHOLD.
     */
    public boolean isDissipated(long nowMs) {
        if (emissionRatePerSecond < DISSIPATION_THRESHOLD) return true;
        if (Double.isInfinite(durationSeconds)) return false;
        double t = elapsedSeconds(nowMs);
        // After release ends, allow 5 × 3600 s (5 hours) for the puff to
        // travel beyond the maximum meaningful downwind distance.
        return t > (durationSeconds + 5.0 * 3600.0);
    }

    /**
     * Returns the currently active (not yet sealed) segment for a continuous
     * source, or null if the segments list is empty.
     *
     * The active segment is always the last element appended to segments; this
     * method performs a backward scan to find it.
     */
    public PlumeSegment currentSegment() {
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).isActive()) return segments.get(i);
        }
        return null;
    }

    // -----------------------------------------------------------------
    // NBT serialisation
    // -----------------------------------------------------------------

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("id",      id);
        nbt.setDouble("posX",     posX);
        nbt.setDouble("posY",     posY);
        nbt.setDouble("posZ",     posZ);
        nbt.setDouble("effH",     effectiveHeightM);
        nbt.setDouble("totalQ",   totalEmissionGameRad);
        nbt.setDouble("rate",     emissionRatePerSecond);
        nbt.setDouble("dur",      Double.isInfinite(durationSeconds) ? -1.0 : durationSeconds);
        nbt.setDouble("wSpd",     windSpeedMs);
        nbt.setDouble("wDir",     windDirectionDeg);
        nbt.setInteger("stab",    stabilityClass.toNBT());
        nbt.setDouble("rain",     rainfallMmPerHour);
        nbt.setLong("releaseMs",  releaseTimeMs);
        nbt.setLong("lastUpMs",   lastUpdateMs);
        nbt.setDouble("depSec",   depositedSeconds);

        // Segments (continuous sources only)
        if (!segments.isEmpty()) {
            NBTTagList segList = new NBTTagList();
            for (PlumeSegment seg : segments) {
                NBTTagCompound segTag = new NBTTagCompound();
                seg.writeToNBT(segTag);
                segList.appendTag(segTag);
            }
            nbt.setTag("segs", segList);
        }
    }

    public static PlumeSource readFromNBT(NBTTagCompound nbt) {
        PlumeSource src = new PlumeSource();
        src.id                   = nbt.getInteger("id");
        src.posX                 = nbt.getDouble("posX");
        src.posY                 = nbt.getDouble("posY");
        src.posZ                 = nbt.getDouble("posZ");
        src.effectiveHeightM     = nbt.getDouble("effH");
        src.totalEmissionGameRad = nbt.getDouble("totalQ");
        src.emissionRatePerSecond = nbt.getDouble("rate");
        double dur               = nbt.getDouble("dur");
        src.durationSeconds      = dur < 0.0 ? CONTINUOUS : dur;
        src.windSpeedMs          = nbt.getDouble("wSpd");
        src.windDirectionDeg     = nbt.getDouble("wDir");
        src.stabilityClass       = AtmosphericStabilityClass.fromNBT(nbt.getInteger("stab"));
        src.rainfallMmPerHour    = nbt.getDouble("rain");
        src.releaseTimeMs        = nbt.getLong("releaseMs");
        src.lastUpdateMs         = nbt.getLong("lastUpMs");
        src.depositedSeconds     = nbt.getDouble("depSec");

        // Segments
        if (nbt.hasKey("segs")) {
            NBTTagList segList = nbt.getTagList("segs", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < segList.tagCount(); i++) {
                src.segments.add(PlumeSegment.readFromNBT(segList.getCompoundTagAt(i)));
            }
        } else if (Double.isInfinite(src.durationSeconds)) {
            // Backward compatibility: continuous source saved before segmentation
            // was implemented.  Reconstruct a synthetic sealed segment covering
            // the already-deposited period, plus an active segment for "now".
            // Use the frozen wind fields as a best estimate for both.
            long now = System.currentTimeMillis();

            // Synthetic historical segment (covers [releaseTimeMs, lastUpdateMs])
            if (src.depositedSeconds > 0.0) {
                PlumeSegment hist = new PlumeSegment();
                hist.windSpeedMs           = src.windSpeedMs;
                hist.windDirectionDeg      = src.windDirectionDeg;
                hist.stabilityClass        = src.stabilityClass;
                hist.rainfallMmPerHour     = src.rainfallMmPerHour;
                hist.startTimeMs           = src.releaseTimeMs;
                hist.endTimeMs             = src.lastUpdateMs;
                hist.emissionRatePerSecond = src.emissionRatePerSecond;
                hist.depositedSeconds      = src.depositedSeconds;
                hist.lastUpdateMs          = src.lastUpdateMs;
                src.segments.add(hist);
            }

            // Active segment starting from lastUpdateMs
            PlumeSegment active = new PlumeSegment();
            active.windSpeedMs           = src.windSpeedMs;
            active.windDirectionDeg      = src.windDirectionDeg;
            active.stabilityClass        = src.stabilityClass;
            active.rainfallMmPerHour     = src.rainfallMmPerHour;
            active.startTimeMs           = src.lastUpdateMs;
            active.endTimeMs             = -1L;
            active.emissionRatePerSecond = src.emissionRatePerSecond;
            active.depositedSeconds      = 0.0;
            active.lastUpdateMs          = src.lastUpdateMs;
            src.segments.add(active);
        }

        return src;
    }
}
