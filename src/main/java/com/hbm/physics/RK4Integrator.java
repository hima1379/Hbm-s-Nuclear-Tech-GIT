package com.hbm.physics;

/**
 * Fourth-Order Runge-Kutta (RK4) Integrator for 6DOF Missile Dynamics.
 *
 * Implements high-accuracy numerical integration for missile state propagation.
 * The RK4 method provides O(h⁴) accuracy, making it suitable for real-time
 * simulation of stiff dynamics equations.
 *
 * State Vector (13 variables):
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Position: [x, y, z]           (m) - inertial frame
 * Velocity: [vx, vy, vz]        (m/s) - inertial frame
 * Attitude: [q0, q1, q2, q3]    (quaternion) - body to inertial rotation
 * Angular Velocity: [ωx, ωy, ωz] (rad/s) - body frame
 *
 * Equations of Motion:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Translation (Newton's 2nd Law):
 *   m × dv/dt = F_total = F_thrust + F_drag + F_lift + F_gravity
 *
 * Rotation (Euler's Equation):
 *   I × dω/dt + ω × (I × ω) = M_total
 *
 * Attitude Kinematics:
 *   dq/dt = 0.5 × q ⊗ ω_quat
 *
 * Where:
 *   m = mass (kg)
 *   I = inertia tensor (kg·m²)
 *   F = forces (N)
 *   M = moments (N·m)
 *   q = attitude quaternion
 *   ω = angular velocity (rad/s)
 *   ⊗ = quaternion multiplication
 *
 * RK4 Algorithm:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * For state x with derivative dx/dt = f(x, t):
 *
 * k1 = f(x_n, t_n)
 * k2 = f(x_n + 0.5×h×k1, t_n + 0.5×h)
 * k3 = f(x_n + 0.5×h×k2, t_n + 0.5×h)
 * k4 = f(x_n + h×k3, t_n + h)
 *
 * x_{n+1} = x_n + (h/6) × (k1 + 2×k2 + 2×k3 + k4)
 *
 * Stability:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * RK4 is stable for timesteps satisfying:
 *   h < 2.78 / λ_max
 *
 * Where λ_max is the largest eigenvalue of the system.
 * For SM-6 missile dynamics at 20 Hz (h=0.05s), this is well satisfied.
 *
 * @author SM6 6DOF Physics System
 */
public class RK4Integrator {

    /**
     * Complete 6DOF state vector.
     */
    public static class State {
        // Translation
        public Vector3D position;        // Position (m, inertial)
        public Vector3D velocity;        // Velocity (m/s, inertial)

        // Rotation
        public Quaternion attitude;      // Attitude quaternion (body to inertial)
        public Vector3D angularVelocity; // Angular velocity (rad/s, body frame)

        public State(Vector3D position, Vector3D velocity,
                    Quaternion attitude, Vector3D angularVelocity) {
            this.position = position;
            this.velocity = velocity;
            this.attitude = attitude.normalize(); // Ensure unit quaternion
            this.angularVelocity = angularVelocity;
        }

        public State copy() {
            return new State(
                position,
                velocity,
                attitude,
                angularVelocity
            );
        }
    }

    /**
     * State derivative (rates of change).
     */
    public static class StateDerivative {
        public Vector3D velocityRate;         // dv/dt (m/s²)
        public Vector3D accelerationRate;     // da/dt (m/s³) - not used in standard formulation
        public Quaternion attitudeRate;       // dq/dt
        public Vector3D angularAcceleration;  // dω/dt (rad/s²)

        public StateDerivative(Vector3D velocityRate, Vector3D accelerationRate,
                              Quaternion attitudeRate, Vector3D angularAcceleration) {
            this.velocityRate = velocityRate;
            this.accelerationRate = accelerationRate;
            this.attitudeRate = attitudeRate;
            this.angularAcceleration = angularAcceleration;
        }
    }

    /**
     * Dynamics function interface.
     * Computes state derivatives given current state and time.
     */
    public interface DynamicsFunction {
        /**
         * Evaluate state derivatives.
         *
         * @param state current state
         * @param time current time (s)
         * @return state derivatives
         */
        StateDerivative evaluate(State state, double time);
    }

    /**
     * Integrate state forward one timestep using RK4.
     *
     * @param state current state
     * @param dynamics dynamics function
     * @param time current time (s)
     * @param dt timestep (s)
     * @return new state at time + dt
     */
    public static State integrate(State state, DynamicsFunction dynamics, double time, double dt) {
        // k1 = f(x_n, t_n)
        StateDerivative k1 = dynamics.evaluate(state, time);

        // k2 = f(x_n + 0.5*h*k1, t_n + 0.5*h)
        State state_k2 = addScaledDerivative(state, k1, 0.5 * dt);
        StateDerivative k2 = dynamics.evaluate(state_k2, time + 0.5 * dt);

        // k3 = f(x_n + 0.5*h*k2, t_n + 0.5*h)
        State state_k3 = addScaledDerivative(state, k2, 0.5 * dt);
        StateDerivative k3 = dynamics.evaluate(state_k3, time + 0.5 * dt);

        // k4 = f(x_n + h*k3, t_n + h)
        State state_k4 = addScaledDerivative(state, k3, dt);
        StateDerivative k4 = dynamics.evaluate(state_k4, time + dt);

        // x_{n+1} = x_n + (h/6) * (k1 + 2*k2 + 2*k3 + k4)
        return addWeightedDerivatives(state, k1, k2, k3, k4, dt);
    }

    /**
     * Add scaled derivative to state: state + scale * derivative
     */
    private static State addScaledDerivative(State state, StateDerivative derivative, double scale) {
        Vector3D newPosition = state.position.add(state.velocity.scale(scale));
        Vector3D newVelocity = state.velocity.add(derivative.velocityRate.scale(scale));
        Quaternion newAttitude = state.attitude.add(derivative.attitudeRate.scale(scale)).normalize();
        Vector3D newAngularVelocity = state.angularVelocity.add(derivative.angularAcceleration.scale(scale));

        return new State(newPosition, newVelocity, newAttitude, newAngularVelocity);
    }

    /**
     * Weighted sum of derivatives: state + (dt/6) * (k1 + 2*k2 + 2*k3 + k4)
     */
    private static State addWeightedDerivatives(
        State state,
        StateDerivative k1,
        StateDerivative k2,
        StateDerivative k3,
        StateDerivative k4,
        double dt
    ) {
        double h6 = dt / 6.0;

        // Position: integrate velocity
        Vector3D dPosition = state.velocity.scale(dt);
        Vector3D newPosition = state.position.add(dPosition);

        // Velocity: weighted average of acceleration
        Vector3D dVelocity = k1.velocityRate.add(k2.velocityRate.scale(2.0))
                                           .add(k3.velocityRate.scale(2.0))
                                           .add(k4.velocityRate)
                                           .scale(h6);
        Vector3D newVelocity = state.velocity.add(dVelocity);

        // Attitude: weighted average of quaternion rate
        Quaternion dAttitude = k1.attitudeRate.add(k2.attitudeRate.scale(2.0))
                                              .add(k3.attitudeRate.scale(2.0))
                                              .add(k4.attitudeRate)
                                              .scale(h6);
        Quaternion newAttitude = state.attitude.add(dAttitude).normalize();

        // Angular velocity: weighted average of angular acceleration
        Vector3D dAngularVelocity = k1.angularAcceleration.add(k2.angularAcceleration.scale(2.0))
                                                          .add(k3.angularAcceleration.scale(2.0))
                                                          .add(k4.angularAcceleration)
                                                          .scale(h6);
        Vector3D newAngularVelocity = state.angularVelocity.add(dAngularVelocity);

        return new State(newPosition, newVelocity, newAttitude, newAngularVelocity);
    }

    /**
     * Compute inertia tensor for cylindrical missile body.
     *
     * Assumes missile is a cylinder with length L and radius R.
     *
     * Principal moments of inertia:
     *   I_xx = I_yy = (1/12) × m × L² + (1/4) × m × R²  (pitch/yaw)
     *   I_zz = (1/2) × m × R²                            (roll)
     *
     * For SM-6:
     *   Length: 6.55 m
     *   Diameter: 0.34 m (radius 0.17 m)
     *   Mass: 650-1500 kg (varies with fuel)
     *
     * @param mass missile mass (kg)
     * @param length missile length (m)
     * @param radius missile radius (m)
     * @return inertia tensor (diagonal, body frame)
     */
    public static Matrix3x3 computeInertiaTensor(double mass, double length, double radius) {
        double I_longitudinal = (1.0 / 12.0) * mass * length * length +
                               (1.0 / 4.0) * mass * radius * radius;
        double I_roll = 0.5 * mass * radius * radius;

        // Diagonal inertia tensor (principal axes aligned with body frame)
        return Matrix3x3.diagonal(I_longitudinal, I_longitudinal, I_roll);
    }

    /**
     * Compute angular acceleration from Euler's equation.
     *
     * Euler's Equation:
     *   I × dω/dt = M - ω × (I × ω)
     *
     * Solving for dω/dt:
     *   dω/dt = I^(-1) × [M - ω × (I × ω)]
     *
     * @param inertia inertia tensor (kg·m²)
     * @param angularVelocity current angular velocity (rad/s, body frame)
     * @param moment applied moment (N·m, body frame)
     * @return angular acceleration (rad/s², body frame)
     */
    public static Vector3D computeAngularAcceleration(
        Matrix3x3 inertia,
        Vector3D angularVelocity,
        Vector3D moment
    ) {
        // Compute gyroscopic term: ω × (I × ω)
        Vector3D I_omega = inertia.multiply(angularVelocity);
        Vector3D gyroscopic = angularVelocity.cross(I_omega);

        // Net moment: M - gyroscopic
        Vector3D netMoment = moment.subtract(gyroscopic);

        // Angular acceleration: I^(-1) × netMoment
        Matrix3x3 inertiaInv = inertia.inverse();
        return inertiaInv.multiply(netMoment);
    }
}
