package com.hbm.entity.missile.guidance;

import com.hbm.physics.Vector3D;

/**
 * Target Tracker for SM-6 Midcourse Guidance Integration.
 *
 * Manages target state estimation using SPY-1 radar measurements combined with
 * onboard active seeker data during terminal phase.
 *
 * Responsibilities:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. SPY-1 Datalink Processing:
 *    - Receive position and velocity measurements from SPY-1 radar
 *    - Filter measurements using α-β-γ filter
 *    - Estimate target acceleration for APN guidance
 *
 * 2. Active Seeker Integration:
 *    - Transition from SPY-1 to onboard seeker during terminal phase
 *    - Maintain track continuity during handover
 *    - Improve accuracy with higher-rate seeker measurements
 *
 * 3. Track Quality Management:
 *    - Monitor measurement residuals
 *    - Detect and handle measurement outliers
 *    - Provide confidence metrics for guidance law adaptation
 *
 * 4. Maneuver Detection:
 *    - Identify sudden target maneuvers from residual spikes
 *    - Adapt filter bandwidth for responsive tracking
 *    - Alert guidance system to increase navigation gains
 *
 * Measurement Sources:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * SPY-1 Radar (Midcourse):
 *   Update rate: 20 Hz (every 50ms)
 *   Accuracy: ~10-50m position, ~5-20 m/s velocity
 *   Range: 0-300 km
 *   Datalink: MIDCOURSE_GUIDANCE_ENHANCED packet
 *
 * Active Seeker (Terminal):
 *   Update rate: 50 Hz (every 20ms)
 *   Accuracy: ~1-5m position, ~2-10 m/s velocity
 *   Range: 0-22.5 km
 *   Datalink: Internal seeker processing
 *
 * Operational Phases:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * INITIALIZATION (Launch to T+3s):
 *   - No target data yet
 *   - Use initial target position from fire control
 *
 * MIDCOURSE (T+3s to Range<5km):
 *   - Primary source: SPY-1 datalink
 *   - Update rate: 20 Hz
 *   - Tracking bandwidth: Moderate (0.5 rad/s)
 *
 * TRANSITION (Range 5-8 km):
 *   - Seeker warming up and acquiring
 *   - Blend SPY-1 and seeker measurements
 *   - Increase tracking bandwidth for responsive seeker
 *
 * TERMINAL (Range<5km, Seeker locked):
 *   - Primary source: Active seeker
 *   - Update rate: 50 Hz
 *   - Tracking bandwidth: High (1.0-2.0 rad/s)
 *
 * @author SM6 Target Tracking & Data Fusion
 */
public class TargetTracker {

    // ========== TRACKING FILTER ==========

    /** Alpha-Beta-Gamma filter for state estimation */
    private final AlphaBetaGammaFilter filter;

    /** Current tracking phase */
    private TrackingPhase phase;

    // ========== MEASUREMENT VALIDATION ==========

    /** Maximum acceptable residual magnitude (m) */
    private double maxResidual;

    /** Consecutive outlier count */
    private int outlierCount;

    /** Maximum consecutive outliers before track loss */
    private static final int MAX_OUTLIERS = 5;

    // ========== MANEUVER DETECTION ==========

    /** Residual history for maneuver detection */
    private double[] residualHistory;

    /** History index */
    private int historyIndex;

    /** History size */
    private static final int HISTORY_SIZE = 10;

    /** Maneuver detection threshold (m) */
    private static final double MANEUVER_THRESHOLD = 50.0;

    /** Is target currently maneuvering? */
    private boolean targetManeuvering;

    // ========== DATA SOURCE TRACKING ==========

    /** Last measurement source */
    private MeasurementSource lastSource;

    /** Last measurement time */
    private double lastMeasurementTime;

    /** Time since last measurement */
    private double timeSinceUpdate;

    /** Maximum time without update before track loss (s) */
    private static final double TRACK_TIMEOUT = 5.0;

    // ========== TRACKING MODES ==========

    public enum TrackingPhase {
        INITIALIZATION,  // No measurements yet
        MIDCOURSE,       // SPY-1 datalink tracking
        TRANSITION,      // Blending SPY-1 and seeker
        TERMINAL,        // Seeker-only tracking
        COAST            // No measurements (coasting on predictions)
    }

    public enum MeasurementSource {
        NONE,
        SPY1_DATALINK,
        ACTIVE_SEEKER,
        BLENDED
    }

    // ========== CONSTRUCTOR ==========

    /**
     * Constructs target tracker with default parameters.
     */
    public TargetTracker() {
        // Initialize with moderate bandwidth for SPY-1 tracking
        this.filter = new AlphaBetaGammaFilter(0.5, 0.05);

        this.phase = TrackingPhase.INITIALIZATION;
        this.lastSource = MeasurementSource.NONE;

        this.maxResidual = 200.0; // 200m initial gate
        this.outlierCount = 0;

        this.residualHistory = new double[HISTORY_SIZE];
        this.historyIndex = 0;
        this.targetManeuvering = false;

        this.lastMeasurementTime = 0;
        this.timeSinceUpdate = 0;
    }

    // ========== MEASUREMENT PROCESSING ==========

    /**
     * Process SPY-1 datalink measurement.
     *
     * @param position target position from SPY-1 (m)
     * @param velocity target velocity from SPY-1 (m/s) - may be zero if unavailable
     * @param currentTime current time (s)
     * @return true if measurement accepted, false if rejected as outlier
     */
    public boolean processSPY1Measurement(Vector3D position, Vector3D velocity, double currentTime) {
        // Validate measurement
        if (!validateMeasurement(position, currentTime)) {
            return false;
        }

        // Update filter
        filter.update(position, currentTime);

        // Update tracking state
        lastMeasurementTime = currentTime;
        lastSource = MeasurementSource.SPY1_DATALINK;
        timeSinceUpdate = 0;

        // Transition to midcourse if in initialization
        if (phase == TrackingPhase.INITIALIZATION) {
            phase = TrackingPhase.MIDCOURSE;
        }

        // Detect maneuvers
        updateManeuverDetection();

        // Adapt filter bandwidth based on maneuver state
        if (targetManeuvering && filter.getAlpha() < 0.5) {
            filter.setTrackingBandwidth(1.0); // Increase bandwidth during maneuvers
        }

        return true;
    }

    /**
     * Process active seeker measurement.
     *
     * @param position target position from seeker (m)
     * @param currentTime current time (s)
     * @return true if measurement accepted
     */
    public boolean processSeekerMeasurement(Vector3D position, double currentTime) {
        if (!validateMeasurement(position, currentTime)) {
            return false;
        }

        filter.update(position, currentTime);

        lastMeasurementTime = currentTime;
        lastSource = MeasurementSource.ACTIVE_SEEKER;
        timeSinceUpdate = 0;

        // Transition to terminal phase
        if (phase == TrackingPhase.MIDCOURSE || phase == TrackingPhase.TRANSITION) {
            phase = TrackingPhase.TERMINAL;
            // Increase filter bandwidth for responsive terminal tracking
            filter.setTrackingBandwidth(1.5);
        }

        updateManeuverDetection();

        return true;
    }

    /**
     * Validate measurement against current track.
     *
     * Rejects outliers that are too far from predicted position.
     *
     * @param measurement measured position
     * @param currentTime measurement time
     * @return true if valid, false if outlier
     */
    private boolean validateMeasurement(Vector3D measurement, double currentTime) {
        if (!filter.isInitialized()) {
            // First measurement - always accept
            return true;
        }

        // Predict where target should be
        AlphaBetaGammaFilter.FilterState prediction = filter.predict(currentTime);

        // Compute residual
        Vector3D residual = measurement.subtract(prediction.position);
        double residualMag = residual.length();

        // Check against gate
        if (residualMag > maxResidual) {
            outlierCount++;

            if (outlierCount >= MAX_OUTLIERS) {
                // Too many outliers - track lost
                phase = TrackingPhase.COAST;
            }

            return false;
        }

        // Valid measurement - reset outlier count
        outlierCount = 0;
        return true;
    }

    // ========== MANEUVER DETECTION ==========

    /**
     * Update maneuver detection based on recent residuals.
     *
     * Large residuals indicate target is maneuvering beyond filter's predictions.
     */
    private void updateManeuverDetection() {
        // Add current residual to history
        double currentResidual = filter.getLastResidual().length();
        residualHistory[historyIndex] = currentResidual;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;

        // Compute average residual over history
        double avgResidual = 0;
        for (double r : residualHistory) {
            avgResidual += r;
        }
        avgResidual /= HISTORY_SIZE;

        // Detect maneuver if residuals are consistently large
        targetManeuvering = (avgResidual > MANEUVER_THRESHOLD);
    }

    // ========== STATE ACCESS ==========

    /**
     * Get current estimated target position.
     *
     * @param currentTime time for prediction (s)
     * @return estimated position (m)
     */
    public Vector3D getPosition(double currentTime) {
        if (!filter.isInitialized()) {
            return Vector3D.zero();
        }

        AlphaBetaGammaFilter.FilterState state = filter.predict(currentTime);
        return state.position;
    }

    /**
     * Get current estimated target velocity.
     *
     * @param currentTime time for prediction (s)
     * @return estimated velocity (m/s)
     */
    public Vector3D getVelocity(double currentTime) {
        if (!filter.isInitialized()) {
            return Vector3D.zero();
        }

        AlphaBetaGammaFilter.FilterState state = filter.predict(currentTime);
        return state.velocity;
    }

    /**
     * Get current estimated target acceleration.
     *
     * @param currentTime time for prediction (s)
     * @return estimated acceleration (m/s²)
     */
    public Vector3D getAcceleration(double currentTime) {
        if (!filter.isInitialized()) {
            return Vector3D.zero();
        }

        AlphaBetaGammaFilter.FilterState state = filter.predict(currentTime);
        return state.acceleration;
    }

    /**
     * Get complete target state.
     *
     * @param currentTime time for prediction (s)
     * @return target state
     */
    public TargetState getState(double currentTime) {
        if (!filter.isInitialized()) {
            return new TargetState(
                Vector3D.zero(),
                Vector3D.zero(),
                Vector3D.zero(),
                0.0,
                false,
                phase,
                lastSource
            );
        }

        AlphaBetaGammaFilter.FilterState state = filter.predict(currentTime);

        return new TargetState(
            state.position,
            state.velocity,
            state.acceleration,
            filter.getConfidence(),
            targetManeuvering,
            phase,
            lastSource
        );
    }

    // ========== TRACK MANAGEMENT ==========

    /**
     * Update time since last measurement.
     *
     * Call this every tick to monitor track health.
     *
     * @param currentTime current time (s)
     */
    public void updateTimeSinceLastMeasurement(double currentTime) {
        timeSinceUpdate = currentTime - lastMeasurementTime;

        // Check for track timeout
        if (timeSinceUpdate > TRACK_TIMEOUT && phase != TrackingPhase.INITIALIZATION) {
            phase = TrackingPhase.COAST;
        }
    }

    /**
     * Check if track is valid.
     *
     * @return true if track is active and receiving measurements
     */
    public boolean isTrackValid() {
        return filter.isInitialized() &&
               phase != TrackingPhase.COAST &&
               timeSinceUpdate < TRACK_TIMEOUT;
    }

    /**
     * Get tracking quality metric [0, 1].
     *
     * Based on filter confidence, update rate, and phase.
     */
    public double getTrackQuality() {
        if (!filter.isInitialized()) {
            return 0.0;
        }

        double baseConfidence = filter.getConfidence();

        // Reduce confidence if too much time since update
        double timeDecay = Math.exp(-timeSinceUpdate / 2.0);

        // Reduce confidence during coast phase
        double phaseMultiplier = (phase == TrackingPhase.COAST) ? 0.5 : 1.0;

        return baseConfidence * timeDecay * phaseMultiplier;
    }

    /**
     * Reset tracker to uninitialized state.
     */
    public void reset() {
        filter.reset();
        phase = TrackingPhase.INITIALIZATION;
        lastSource = MeasurementSource.NONE;
        outlierCount = 0;
        targetManeuvering = false;
        timeSinceUpdate = 0;
    }

    // ========== ACCESSORS ==========

    public TrackingPhase getPhase() { return phase; }
    public MeasurementSource getLastSource() { return lastSource; }
    public boolean isTargetManeuvering() { return targetManeuvering; }
    public double getTimeSinceUpdate() { return timeSinceUpdate; }

    @Override
    public String toString() {
        return String.format(
            "TargetTracker[phase=%s, quality=%.2f, maneuver=%s, lastUpdate=%.1fs ago]",
            phase,
            getTrackQuality(),
            targetManeuvering ? "YES" : "NO",
            timeSinceUpdate
        );
    }

    /**
     * Complete target state snapshot.
     */
    public static class TargetState {
        public final Vector3D position;
        public final Vector3D velocity;
        public final Vector3D acceleration;
        public final double confidence;
        public final boolean maneuvering;
        public final TrackingPhase phase;
        public final MeasurementSource source;

        public TargetState(
            Vector3D position,
            Vector3D velocity,
            Vector3D acceleration,
            double confidence,
            boolean maneuvering,
            TrackingPhase phase,
            MeasurementSource source
        ) {
            this.position = position;
            this.velocity = velocity;
            this.acceleration = acceleration;
            this.confidence = confidence;
            this.maneuvering = maneuvering;
            this.phase = phase;
            this.source = source;
        }

        @Override
        public String toString() {
            return String.format(
                "TargetState[pos=(%.1f,%.1f,%.1f), vel=%.1fm/s, accel=%.1fm/s², conf=%.2f]",
                position.x, position.y, position.z,
                velocity.length(),
                acceleration.length(),
                confidence
            );
        }
    }
}
