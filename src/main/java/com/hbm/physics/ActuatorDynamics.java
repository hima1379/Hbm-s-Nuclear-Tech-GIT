package com.hbm.physics;

/**
 * Realistic missile control surface actuator dynamics model.
 *
 * Based on:
 * - Cronvich, "Missile Aerodynamics" (04-03-Cronvich.pdf)
 * - Jackson, "Missile Flight Control Systems" (29-01-Jackson.pdf)
 *
 * Models a second-order hydraulic actuator with:
 * - Rate limiting (±60 deg/s typical for tactical missiles)
 * - Position limiting (±20 deg typical)
 * - Bandwidth: 30 Hz (ωn = 100 rad/s, ζ = 0.7)
 * - Transport delay: 10 ms
 *
 * Transfer function: G(s) = ωn² / (s² + 2ζωns + ωn²)
 */
public class ActuatorDynamics {

    // ============ ACTUATOR PARAMETERS (from Cronvich/Jackson PDFs) ============

    /** Rate limit in rad/s (±60 deg/s for tactical missiles) */
    private static final double RATE_LIMIT = Math.toRadians(60.0);  // rad/s

    /** Position limit in rad (±20 deg typical for control surfaces) */
    private static final double POSITION_LIMIT = Math.toRadians(20.0);  // rad

    /** Natural frequency (100 rad/s = 30 Hz bandwidth typical for hydraulic) */
    private static final double OMEGA_N = 100.0;  // rad/s

    /** Damping ratio (0.7 for critically damped hydraulic actuator) */
    private static final double ZETA = 0.7;

    /** Transport delay in seconds (10 ms typical for hydraulic valve) */
    private static final double TRANSPORT_DELAY = 0.010;  // seconds

    // ============ STATE VARIABLES (second-order system) ============

    /** Current actuator position (rad) */
    private double position = 0.0;

    /** Current actuator velocity (rad/s) */
    private double velocity = 0.0;

    /** Command history buffer (for transport delay) */
    private java.util.ArrayDeque<DelayedCommand> commandBuffer = new java.util.ArrayDeque<>();

    /** Helper class for delayed commands */
    private static class DelayedCommand {
        double command;
        double timestamp;

        DelayedCommand(double cmd, double time) {
            this.command = cmd;
            this.timestamp = time;
        }
    }

    // ============ CONSTRUCTOR ============

    public ActuatorDynamics() {
        // Initialize to zero position
        this.position = 0.0;
        this.velocity = 0.0;
    }

    // ============ PUBLIC METHODS ============

    /**
     * Update actuator dynamics for one time step.
     *
     * Implements second-order system:
     *   ẍ + 2ζωnẋ + ωn²x = ωn²u
     *
     * With rate and position limiting.
     *
     * @param commandedPosition Commanded fin deflection (rad)
     * @param dt Time step (seconds, typically 0.05)
     * @param currentTime Current simulation time (seconds)
     * @return Actual fin position after dynamics (rad)
     */
    public double update(double commandedPosition, double dt, double currentTime) {

        // ========== STEP 1: APPLY TRANSPORT DELAY ==========

        // Add current command to buffer with timestamp
        commandBuffer.addLast(new DelayedCommand(commandedPosition, currentTime));

        // Find command from TRANSPORT_DELAY seconds ago
        double delayedCommand = commandedPosition;  // default if buffer empty
        while (!commandBuffer.isEmpty()) {
            DelayedCommand oldest = commandBuffer.peekFirst();
            if (currentTime - oldest.timestamp >= TRANSPORT_DELAY) {
                delayedCommand = oldest.command;
                commandBuffer.removeFirst();
            } else {
                break;
            }
        }

        // ========== STEP 2: APPLY POSITION LIMIT TO COMMAND ==========

        double limitedCommand = Math.max(-POSITION_LIMIT, Math.min(POSITION_LIMIT, delayedCommand));

        // ========== STEP 3: COMPUTE SECOND-ORDER DYNAMICS ==========

        // State-space form of second-order system:
        //   ẋ₁ = x₂
        //   ẋ₂ = -ωn²x₁ - 2ζωnx₂ + ωn²u
        // where x₁ = position, x₂ = velocity, u = command

        double positionError = limitedCommand - position;
        double acceleration = OMEGA_N * OMEGA_N * positionError - 2.0 * ZETA * OMEGA_N * velocity;

        // ========== STEP 4: INTEGRATE (simple Euler for now) ==========

        double newVelocity = velocity + acceleration * dt;

        // ========== STEP 5: APPLY RATE LIMIT ==========

        if (newVelocity > RATE_LIMIT) {
            newVelocity = RATE_LIMIT;
        } else if (newVelocity < -RATE_LIMIT) {
            newVelocity = -RATE_LIMIT;
        }

        // Update position
        double newPosition = position + newVelocity * dt;

        // ========== STEP 6: APPLY POSITION LIMIT TO OUTPUT ==========

        if (newPosition > POSITION_LIMIT) {
            newPosition = POSITION_LIMIT;
            newVelocity = 0.0;  // Stop at limit
        } else if (newPosition < -POSITION_LIMIT) {
            newPosition = -POSITION_LIMIT;
            newVelocity = 0.0;  // Stop at limit
        }

        // ========== STEP 7: UPDATE STATE ==========

        this.position = newPosition;
        this.velocity = newVelocity;

        return newPosition;
    }

    /**
     * Get current actuator position.
     * @return Current fin deflection (rad)
     */
    public double getPosition() {
        return position;
    }

    /**
     * Get current actuator velocity.
     * @return Current rate of fin deflection (rad/s)
     */
    public double getVelocity() {
        return velocity;
    }

    /**
     * Reset actuator to zero position (called at missile initialization).
     */
    public void reset() {
        this.position = 0.0;
        this.velocity = 0.0;
        this.commandBuffer.clear();
    }

    /**
     * Get actuator parameters for debugging.
     * @return String with actuator specs
     */
    public String getParameters() {
        return String.format("Actuator: Rate=%.1f°/s, Pos=%.1f°, BW=%.1fHz, Delay=%.1fms",
            Math.toDegrees(RATE_LIMIT),
            Math.toDegrees(POSITION_LIMIT),
            OMEGA_N / (2.0 * Math.PI),
            TRANSPORT_DELAY * 1000);
    }
}
