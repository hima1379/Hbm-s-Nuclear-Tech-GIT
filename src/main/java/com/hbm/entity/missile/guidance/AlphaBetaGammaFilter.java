package com.hbm.entity.missile.guidance;

import com.hbm.physics.Vector3D;

/**
 * Alpha-Beta-Gamma (α-β-γ) Filter for Target State Estimation.
 *
 * A computationally efficient Kalman-like filter that tracks position, velocity,
 * and acceleration of maneuvering targets. Optimal for real-time missile guidance
 * where computational resources are limited.
 *
 * Mathematical Foundation:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * State Vector:
 *   s = [position, velocity, acceleration]ᵀ
 *
 * Prediction Step (prior to measurement):
 *   x̂ₖ₊₁|ₖ = x̂ₖ + v̂ₖ × Δt + 0.5 × âₖ × Δt²
 *   v̂ₖ₊₁|ₖ = v̂ₖ + âₖ × Δt
 *   âₖ₊₁|ₖ = âₖ
 *
 * Update Step (after measurement zₖ₊₁):
 *   residual = zₖ₊₁ - x̂ₖ₊₁|ₖ
 *
 *   x̂ₖ₊₁ = x̂ₖ₊₁|ₖ + α × residual
 *   v̂ₖ₊₁ = v̂ₖ₊₁|ₖ + (β / Δt) × residual
 *   âₖ₊₁ = âₖ₊₁|ₖ + (γ / (0.5 × Δt²)) × residual
 *
 * Filter Gain Selection (Benedict-Bordner):
 * ───────────────────────────────────────────────────────────────────────────
 *
 * For tracking bandwidth λ (rad/s):
 *
 *   α = 1 - e^(-λΔt)³
 *   β = (3/2) × (1 - e^(-λΔt))²
 *   γ = (1/2) × (1 - e^(-λΔt))³
 *
 * Tracking Bandwidth Guidelines:
 *
 *   λ = 0.1 rad/s: Very smooth, high latency (non-maneuvering targets)
 *   λ = 0.5 rad/s: Balanced (typical cruise missiles)
 *   λ = 1.0 rad/s: Responsive (maneuvering aircraft)
 *   λ = 2.0 rad/s: Aggressive (highly maneuvering fighters)
 *
 * Physical Interpretation:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * α (position gain):
 *   - How quickly position estimate adjusts to new measurements
 *   - High α: Trust measurements more (responsive but noisy)
 *   - Low α: Trust predictions more (smooth but laggy)
 *
 * β (velocity gain):
 *   - How quickly velocity estimate updates from position errors
 *   - Relates to first derivative of position residual
 *
 * γ (acceleration gain):
 *   - How quickly acceleration estimate updates
 *   - Relates to second derivative of position residual
 *   - Most sensitive to noise (use lowest values)
 *
 * Steady-State Error Analysis:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * For constant velocity target:
 *   Position error:     0 (perfect tracking)
 *   Velocity error:     0 (perfect tracking)
 *   Acceleration error: N/A
 *
 * For constant acceleration target (a_true):
 *   Position lag:       (1-α)/γ × a_true × Δt²
 *   Velocity lag:       (1-α)/γ × a_true × Δt
 *   Acceleration error: (1-γ)/γ × a_true
 *
 * Noise Amplification:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Measurement noise: σ_z
 *
 * Position noise:     σ_x ≈ α × σ_z
 * Velocity noise:     σ_v ≈ (β / Δt) × σ_z
 * Acceleration noise: σ_a ≈ (γ / Δt²) × σ_z
 *
 * Notice: Higher derivatives amplify noise more severely!
 *
 * Practical Implementation Notes:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * 1. Initialization:
 *    - Use first measurement for position
 *    - Estimate velocity from first two measurements
 *    - Assume zero acceleration initially
 *
 * 2. Maneuver Detection:
 *    - Monitor residual magnitude
 *    - Large residuals indicate target maneuver or measurement outlier
 *    - Temporarily increase gains to track maneuver
 *
 * 3. Data Association:
 *    - Validate measurements using innovation (residual) bounds
 *    - Reject measurements with residual > 3σ (outliers)
 *
 * 4. Filter Divergence Prevention:
 *    - Bound acceleration estimates to physically reasonable values
 *    - Reset filter if residuals become excessive
 *
 * @author SM6 Target Tracking System
 */
public class AlphaBetaGammaFilter {

    // ========== FILTER GAINS ==========

    /** Position gain (α) */
    private double alpha;

    /** Velocity gain (β) */
    private double beta;

    /** Acceleration gain (γ) */
    private double gamma;

    // ========== STATE ESTIMATES ==========

    /** Estimated position (m) */
    private Vector3D position;

    /** Estimated velocity (m/s) */
    private Vector3D velocity;

    /** Estimated acceleration (m/s²) */
    private Vector3D acceleration;

    // ========== FILTER STATUS ==========

    /** Last update time (for Δt calculation) */
    private double lastUpdateTime;

    /** Number of updates received */
    private int updateCount;

    /** Filter is initialized flag */
    private boolean initialized;

    /** Residual from last update (for diagnostics) */
    private Vector3D lastResidual;

    /** Tracking bandwidth (rad/s) */
    private double trackingBandwidth;

    // ========== CONFIGURATION ==========

    /** Maximum believable acceleration (m/s²) */
    private static final double MAX_ACCELERATION = 150.0; // 15G for fighter aircraft

    /** Minimum time step (s) */
    private static final double MIN_TIME_STEP = 0.001;

    /** Maximum time step (s) - for gap detection */
    private static final double MAX_TIME_STEP = 2.0;

    // ========== CONSTRUCTORS ==========

    /**
     * Constructs filter with custom gains.
     *
     * @param alpha position gain
     * @param beta velocity gain
     * @param gamma acceleration gain
     */
    public AlphaBetaGammaFilter(double alpha, double beta, double gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
        this.trackingBandwidth = 0.5; // Default moderate bandwidth

        this.position = Vector3D.zero();
        this.velocity = Vector3D.zero();
        this.acceleration = Vector3D.zero();
        this.lastResidual = Vector3D.zero();

        this.lastUpdateTime = 0;
        this.updateCount = 0;
        this.initialized = false;
    }

    /**
     * Constructs filter with gains computed from tracking bandwidth.
     *
     * Uses Benedict-Bordner equations for optimal gain computation.
     *
     * @param trackingBandwidth desired tracking bandwidth (rad/s)
     * @param timeStep expected measurement interval (s)
     */
    public AlphaBetaGammaFilter(double trackingBandwidth, double timeStep) {
        this.trackingBandwidth = trackingBandwidth;
        computeGainsFromBandwidth(trackingBandwidth, timeStep);

        this.position = Vector3D.zero();
        this.velocity = Vector3D.zero();
        this.acceleration = Vector3D.zero();
        this.lastResidual = Vector3D.zero();

        this.lastUpdateTime = 0;
        this.updateCount = 0;
        this.initialized = false;
    }

    /**
     * Default constructor with moderate tracking bandwidth.
     */
    public AlphaBetaGammaFilter() {
        this(0.5, 0.05); // 0.5 rad/s, 50ms update rate (20 Hz)
    }

    // ========== FILTER UPDATE ==========

    /**
     * Update filter with new position measurement.
     *
     * @param measuredPosition position measurement (m)
     * @param currentTime time of measurement (s)
     */
    public void update(Vector3D measuredPosition, double currentTime) {
        if (!initialized) {
            // First measurement - initialize filter
            position = measuredPosition;
            velocity = Vector3D.zero();
            acceleration = Vector3D.zero();
            lastUpdateTime = currentTime;
            updateCount = 1;
            initialized = true;
            return;
        }

        // Compute time step
        double dt = currentTime - lastUpdateTime;

        // Validate time step
        if (dt < MIN_TIME_STEP) {
            // Measurement too soon - skip or use as refinement
            return;
        }

        if (dt > MAX_TIME_STEP) {
            // Large gap - reinitialize filter
            position = measuredPosition;
            velocity = Vector3D.zero();
            acceleration = Vector3D.zero();
            lastUpdateTime = currentTime;
            updateCount = 1;
            return;
        }

        // ========== PREDICTION STEP ==========

        // Predict position using constant acceleration model
        // x̂ₖ₊₁|ₖ = xₖ + vₖ × dt + 0.5 × aₖ × dt²
        Vector3D predictedPosition = position
            .add(velocity.scale(dt))
            .add(acceleration.scale(0.5 * dt * dt));

        // Predict velocity
        // v̂ₖ₊₁|ₖ = vₖ + aₖ × dt
        Vector3D predictedVelocity = velocity.add(acceleration.scale(dt));

        // Predict acceleration (assume constant)
        // âₖ₊₁|ₖ = aₖ
        Vector3D predictedAcceleration = acceleration;

        // ========== UPDATE STEP ==========

        // Compute residual (innovation)
        Vector3D residual = measuredPosition.subtract(predictedPosition);
        lastResidual = residual;

        // Update position estimate
        // x̂ₖ₊₁ = x̂ₖ₊₁|ₖ + α × residual
        position = predictedPosition.add(residual.scale(alpha));

        // Update velocity estimate
        // v̂ₖ₊₁ = v̂ₖ₊₁|ₖ + (β / dt) × residual
        velocity = predictedVelocity.add(residual.scale(beta / dt));

        // Update acceleration estimate
        // âₖ₊₁ = âₖ₊₁|ₖ + (γ / (0.5 × dt²)) × residual
        acceleration = predictedAcceleration.add(residual.scale(gamma / (0.5 * dt * dt)));

        // ========== BOUNDS CHECKING ==========

        // Limit acceleration to physically reasonable values
        if (acceleration.length() > MAX_ACCELERATION) {
            acceleration = acceleration.clampLength(MAX_ACCELERATION);
        }

        // Update state
        lastUpdateTime = currentTime;
        updateCount++;
    }

    /**
     * Predict future state without measurement.
     *
     * Useful for extrapolation between measurements or during measurement dropouts.
     *
     * @param predictionTime time to predict to (s)
     * @return predicted state
     */
    public FilterState predict(double predictionTime) {
        if (!initialized) {
            return new FilterState(Vector3D.zero(), Vector3D.zero(), Vector3D.zero(), false);
        }

        double dt = predictionTime - lastUpdateTime;

        Vector3D predictedPosition = position
            .add(velocity.scale(dt))
            .add(acceleration.scale(0.5 * dt * dt));

        Vector3D predictedVelocity = velocity.add(acceleration.scale(dt));

        Vector3D predictedAcceleration = acceleration;

        return new FilterState(
            predictedPosition,
            predictedVelocity,
            predictedAcceleration,
            true
        );
    }

    // ========== GAIN COMPUTATION ==========

    /**
     * Compute optimal gains from tracking bandwidth (Benedict-Bordner).
     *
     * @param lambda tracking bandwidth (rad/s)
     * @param dt time step (s)
     */
    private void computeGainsFromBandwidth(double lambda, double dt) {
        double exp_term = Math.exp(-lambda * dt);

        this.alpha = 1.0 - exp_term * exp_term * exp_term;
        this.beta = 1.5 * (1.0 - exp_term) * (1.0 - exp_term);
        this.gamma = 0.5 * (1.0 - exp_term) * (1.0 - exp_term) * (1.0 - exp_term);
    }

    /**
     * Adjust tracking bandwidth (recomputes gains).
     *
     * @param newBandwidth new tracking bandwidth (rad/s)
     */
    public void setTrackingBandwidth(double newBandwidth) {
        this.trackingBandwidth = newBandwidth;
        double dt = (updateCount > 1) ? 0.05 : 0.05; // Assume 20 Hz if unknown
        computeGainsFromBandwidth(newBandwidth, dt);
    }

    // ========== ACCESSORS ==========

    public Vector3D getPosition() { return position; }
    public Vector3D getVelocity() { return velocity; }
    public Vector3D getAcceleration() { return acceleration; }
    public Vector3D getLastResidual() { return lastResidual; }
    public boolean isInitialized() { return initialized; }
    public int getUpdateCount() { return updateCount; }

    public double getAlpha() { return alpha; }
    public double getBeta() { return beta; }
    public double getGamma() { return gamma; }

    /**
     * Get estimate quality metric [0, 1].
     *
     * Based on update count and residual magnitude.
     */
    public double getConfidence() {
        if (!initialized || updateCount < 3) {
            return 0.0; // Need at least 3 updates for meaningful estimate
        }

        // Confidence decreases with large residuals (indicates poor tracking)
        double residualMag = lastResidual.length();
        double residualFactor = Math.exp(-residualMag / 100.0); // Decay over 100m

        // Confidence increases with update count (up to saturation)
        double countFactor = Math.min(1.0, updateCount / 10.0);

        return residualFactor * countFactor;
    }

    /**
     * Reset filter to uninitialized state.
     */
    public void reset() {
        position = Vector3D.zero();
        velocity = Vector3D.zero();
        acceleration = Vector3D.zero();
        lastResidual = Vector3D.zero();
        lastUpdateTime = 0;
        updateCount = 0;
        initialized = false;
    }

    @Override
    public String toString() {
        return String.format(
            "α-β-γ Filter[pos=%.1fm, vel=%.1fm/s, accel=%.2fm/s², updates=%d, conf=%.2f]",
            position.length(),
            velocity.length(),
            acceleration.length(),
            updateCount,
            getConfidence()
        );
    }

    /**
     * Filter state snapshot.
     */
    public static class FilterState {
        public final Vector3D position;
        public final Vector3D velocity;
        public final Vector3D acceleration;
        public final boolean valid;

        public FilterState(Vector3D position, Vector3D velocity, Vector3D acceleration, boolean valid) {
            this.position = position;
            this.velocity = velocity;
            this.acceleration = acceleration;
            this.valid = valid;
        }
    }
}
