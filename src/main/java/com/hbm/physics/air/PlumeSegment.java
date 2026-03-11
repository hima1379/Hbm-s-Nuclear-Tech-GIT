package com.hbm.physics.air;

import net.minecraft.nbt.NBTTagCompound;

/**
 * A single meteorological snapshot for a segmented continuous plume source.
 *
 * A PlumeSegment represents a finite time window [startTimeMs, endTimeMs) during
 * which a continuous plume source emitted under specific wind conditions.  This
 * implements the MACCS2 "segmented plume" approach (NUREG/CR-6613, §5) where a
 * long-duration release is divided into short segments, each carrying its own
 * frozen meteorological state.
 *
 * -----------------------------------------------------------------------
 * Lifecycle
 *
 *   1. Created by AtmosphericDispersionSystem when a continuous source is
 *      registered (first segment) or when SEGMENT_INTERVAL_SEC elapses since
 *      the last segment's start (subsequent segments).
 *   2. Active while endTimeMs == -1L.  The current segment receives online-tick
 *      deposition tracked via depositedSeconds / lastUpdateMs.
 *   3. Sealed when a newer segment is created: endTimeMs is set to current time.
 *   4. Prunable when enough time has elapsed after sealing that any previously
 *      unloaded chunk is assumed to have loaded and received its catch-up dose.
 *
 * -----------------------------------------------------------------------
 * Deposition accounting (mirrors PlumeSource.depositedSeconds)
 *
 *   depositedSeconds tracks how many seconds of this segment's active period
 *   have been covered by the online tick for currently-loaded chunks.
 *
 *   On chunk load, the catch-up for this segment is:
 *     catchUpDt = effectiveElapsedSeconds(now) - depositedSeconds  (≥ 0)
 */
public class PlumeSegment {

    // -----------------------------------------------------------------
    // Meteorological snapshot (frozen at segment creation)
    // -----------------------------------------------------------------

    /** Wind speed [m/s]. */
    public double windSpeedMs;

    /** Wind direction [degrees CW from North/+Z], meteorological FROM convention. */
    public double windDirectionDeg;

    /** Pasquill-Gifford stability class. */
    public AtmosphericStabilityClass stabilityClass;

    /** Rainfall rate [mm/h]; 0 = dry. */
    public double rainfallMmPerHour;

    // -----------------------------------------------------------------
    // Timing
    // -----------------------------------------------------------------

    /** Wall-clock time when this segment started [ms since epoch]. */
    public long startTimeMs;

    /**
     * Wall-clock time when this segment was sealed (superseded by a newer
     * segment) [ms since epoch].  -1L = still active (not yet sealed).
     */
    public long endTimeMs = -1L;

    // -----------------------------------------------------------------
    // Emission
    // -----------------------------------------------------------------

    /** Emission rate during this segment [game_rad·m²/s]. */
    public double emissionRatePerSecond;

    // -----------------------------------------------------------------
    // Online-tick tracking
    // -----------------------------------------------------------------

    /**
     * Seconds of this segment's active period covered by online-tick deposition
     * to currently-loaded chunks.  Semantics identical to
     * PlumeSource.depositedSeconds.
     */
    public double depositedSeconds = 0.0;

    /** Wall-clock time of the last online-tick update for this segment [ms]. */
    public long lastUpdateMs;

    // -----------------------------------------------------------------
    // Pruning threshold
    // -----------------------------------------------------------------

    /**
     * How long after sealing a segment is eligible for removal.
     * Matches the 5-hour puff grace period in PlumeSource:
     *   PRUNE_AFTER_MS = (SEGMENT_INTERVAL_SEC + 5 h) × 1000
     *                  = (900 + 18 000) × 1000 = 18 900 000 ms
     *
     * At 900 s per segment, at most ~22 segments are held in memory at once,
     * regardless of how long the continuous source runs.
     */
    public static final long PRUNE_AFTER_MS =
            (long) ((PlumeSource.SEGMENT_INTERVAL_SEC + 5.0 * 3600.0) * 1000.0);

    // -----------------------------------------------------------------
    // State queries
    // -----------------------------------------------------------------

    /** Returns true if this segment is still actively emitting (not yet sealed). */
    public boolean isActive() {
        return endTimeMs < 0L;
    }

    /**
     * Returns the portion of this segment's duration that has elapsed as of
     * nowMs [s].  For an active segment this grows over time; for a sealed
     * segment it is fixed at (endTimeMs - startTimeMs) / 1000.
     */
    public double effectiveElapsedSeconds(long nowMs) {
        if (endTimeMs < 0L) {
            return Math.max((nowMs - startTimeMs) / 1000.0, 0.0);
        }
        return (endTimeMs - startTimeMs) / 1000.0;
    }

    /**
     * Returns the catch-up integration time [s] for a chunk loading at nowMs.
     * This is the portion of the segment not yet covered by online ticks:
     *   catchUpDt = effectiveElapsedSeconds(nowMs) - depositedSeconds  (clamped ≥ 0)
     */
    public double catchUpDt(long nowMs) {
        return Math.max(effectiveElapsedSeconds(nowMs) - depositedSeconds, 0.0);
    }

    /**
     * Returns true when this segment has been sealed long enough that any
     * future chunk-load catch-up would yield zero dose.  The grace period
     * mirrors the 5-hour rule used for finite puff sources.
     */
    public boolean isPrunable(long nowMs) {
        if (endTimeMs < 0L) return false;  // still active
        long sealDuration = endTimeMs - startTimeMs;
        return nowMs - startTimeMs > sealDuration + PRUNE_AFTER_MS;
    }

    // -----------------------------------------------------------------
    // NBT serialisation
    // -----------------------------------------------------------------

    private static final String KEY_W_SPD   = "wSpd";
    private static final String KEY_W_DIR   = "wDir";
    private static final String KEY_STAB    = "stab";
    private static final String KEY_RAIN    = "rain";
    private static final String KEY_START   = "startMs";
    private static final String KEY_END     = "endMs";
    private static final String KEY_RATE    = "rate";
    private static final String KEY_DEP_SEC = "depSec";
    private static final String KEY_LAST_UP = "lastUpMs";

    public void writeToNBT(NBTTagCompound tag) {
        tag.setDouble( KEY_W_SPD,   windSpeedMs);
        tag.setDouble( KEY_W_DIR,   windDirectionDeg);
        tag.setInteger(KEY_STAB,    stabilityClass.toNBT());
        tag.setDouble( KEY_RAIN,    rainfallMmPerHour);
        tag.setLong(   KEY_START,   startTimeMs);
        tag.setLong(   KEY_END,     endTimeMs);
        tag.setDouble( KEY_RATE,    emissionRatePerSecond);
        tag.setDouble( KEY_DEP_SEC, depositedSeconds);
        tag.setLong(   KEY_LAST_UP, lastUpdateMs);
    }

    public static PlumeSegment readFromNBT(NBTTagCompound tag) {
        PlumeSegment seg          = new PlumeSegment();
        seg.windSpeedMs           = tag.getDouble( KEY_W_SPD);
        seg.windDirectionDeg      = tag.getDouble( KEY_W_DIR);
        seg.stabilityClass        = AtmosphericStabilityClass.fromNBT(tag.getInteger(KEY_STAB));
        seg.rainfallMmPerHour     = tag.getDouble( KEY_RAIN);
        seg.startTimeMs           = tag.getLong(   KEY_START);
        seg.endTimeMs             = tag.getLong(   KEY_END);
        seg.emissionRatePerSecond = tag.getDouble( KEY_RATE);
        seg.depositedSeconds      = tag.getDouble( KEY_DEP_SEC);
        seg.lastUpdateMs          = tag.getLong(   KEY_LAST_UP);
        return seg;
    }
}
