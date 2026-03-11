package com.hbm.physics.fusion;

/**
 * Plasma Disruption Physics for Tokamak
 *
 * Handles detection and execution of plasma disruptions - catastrophic events
 * where plasma confinement is lost and the plasma rapidly cools and disperses.
 *
 * Disruption occurs in two phases:
 * 1. Thermal quench: Temperature crashes in milliseconds (50-100 ms)
 * 2. Current quench: Plasma current decays in hundreds of milliseconds
 *
 * Causes of disruption:
 * - Magnetic field loss (power failure)
 * - Beta limit exceeded (pressure too high)
 * - Density limit exceeded (Greenwald limit)
 * - Major instabilities (MHD modes)
 *
 * References:
 * - https://pubs.aip.org/aip/pop/article/19/5/058101/596952
 * - https://www.cambridge.org/core/journals/journal-of-plasma-physics/article/mhd-stability-and-disruptions-in-the-sparc-tokamak/908C6788C0D625C5DDF335DBD9A17476
 */
public class PlasmaDisruption {

	// Timescales (seconds)
	public static final double THERMAL_QUENCH_TIME = 0.050;  // 50 ms for ITER-scale
	public static final double CURRENT_QUENCH_TIME = 0.500;  // 500 ms

	// Trigger thresholds
	public static final double MIN_MAGNETIC_FIELD = 0.5;     // Tesla (below this → disruption)
	public static final double BETA_LIMIT_MARGIN = 1.2;      // 20% margin before disruption
	public static final double GREENWALD_LIMIT_MARGIN = 1.5; // 50% margin before disruption

	// Temperature floor (room temperature)
	public static final double MIN_TEMPERATURE = 300.0;      // K

	/**
	 * Check if disruption should be triggered based on current plasma state.
	 *
	 * @param state Plasma state
	 * @param magneticField Current magnetic field strength (T)
	 * @param plasmaCurrent Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @return true if disruption should be triggered
	 */
	public static boolean shouldDisrupt(PlasmaState state, double magneticField,
	                                     double plasmaCurrent, double minorRadius) {
		// Magnetic field loss
		if (magneticField < MIN_MAGNETIC_FIELD) {
			System.out.println("[Disruption] Triggered by magnetic field loss: B=" +
			                   String.format("%.2f", magneticField) + " T < " + MIN_MAGNETIC_FIELD + " T");
			return true;
		}

		// Beta limit violation
		double betaLimit = BetaLimit.calculateBetaLimit(2.5, plasmaCurrent, minorRadius, magneticField);
		if (state.plasmaBeta > betaLimit * BETA_LIMIT_MARGIN) {
			System.out.println("[Disruption] Triggered by beta limit: β=" +
			                   String.format("%.3f", state.plasmaBeta * 100) + "% > limit=" +
			                   String.format("%.3f", betaLimit * BETA_LIMIT_MARGIN * 100) + "%");
			return true;
		}

		// Greenwald density limit violation
		double greenwaldLimit = DensityLimit.calculateGreenwaldLimit(plasmaCurrent, minorRadius);
		if (state.density > greenwaldLimit * GREENWALD_LIMIT_MARGIN) {
			System.out.println("[Disruption] Triggered by Greenwald limit: n=" +
			                   String.format("%.2e", state.density) + " m^-3 > limit=" +
			                   String.format("%.2e", greenwaldLimit * GREENWALD_LIMIT_MARGIN) + " m^-3");
			return true;
		}

		return false;
	}

	/**
	 * Execute thermal quench - rapid temperature collapse.
	 * Temperature decays exponentially with time constant τ_thermal.
	 *
	 * T(t) = T₀ × exp(-t/τ) + T_min
	 *
	 * @param state Plasma state (modified in place)
	 * @param deltaTime Time step (s)
	 */
	public static void executeThermalQuench(PlasmaState state, double deltaTime) {
		// Exponential decay with tau = THERMAL_QUENCH_TIME
		double decayFactor = Math.exp(-deltaTime / THERMAL_QUENCH_TIME);

		// Apply decay to both ion and electron temperatures
		state.temperatureIon *= decayFactor;
		state.temperatureElectron *= decayFactor;

		// Floor at room temperature
		state.temperatureIon = Math.max(MIN_TEMPERATURE, state.temperatureIon);
		state.temperatureElectron = Math.max(MIN_TEMPERATURE, state.temperatureElectron);

		// Update derived quantities
		state.pressure = state.calculatePressure();
		state.plasmaBeta = state.calculateBeta();
	}

	/**
	 * Execute current quench - plasma current decay.
	 * Current decays exponentially with time constant τ_current.
	 *
	 * I(t) = I₀ × exp(-t/τ)
	 *
	 * @param plasmaCurrent Current plasma current (MA)
	 * @param deltaTime Time step (s)
	 * @return New plasma current (MA)
	 */
	public static double executeCurrentQuench(double plasmaCurrent, double deltaTime) {
		// Exponential decay with tau = CURRENT_QUENCH_TIME
		double decayFactor = Math.exp(-deltaTime / CURRENT_QUENCH_TIME);
		return plasmaCurrent * decayFactor;
	}

	/**
	 * Calculate energy deposited on first wall during disruption.
	 * All thermal energy is dumped to the wall in a short time.
	 *
	 * @param state Plasma state before disruption
	 * @param volume Plasma volume (m³)
	 * @param wallArea First wall area (m²)
	 * @return Energy flux on wall (MJ/m²)
	 */
	public static double calculateWallHeatLoad(PlasmaState state, double volume, double wallArea) {
		// Total thermal energy in plasma
		// E = (3/2) × n × V × k_B × (T_i + T_e)
		double E_thermal = (3.0/2.0) * state.density * volume * PlasmaState.BOLTZMANN
		                   * (state.temperatureIon + state.temperatureElectron);

		// Convert to MJ
		double E_MJ = E_thermal / 1.0e6;

		// Energy per unit wall area
		return E_MJ / wallArea;  // MJ/m²
	}

	/**
	 * Calculate disruption severity score (0.0 = minor, 1.0 = catastrophic).
	 * Based on energy dumped to wall and disruption speed.
	 *
	 * @param state Plasma state
	 * @param volume Plasma volume (m³)
	 * @return Severity score (0.0-1.0)
	 */
	public static double calculateDisruptionSeverity(PlasmaState state, double volume) {
		// Calculate thermal energy
		double E_thermal = (3.0/2.0) * state.density * volume * PlasmaState.BOLTZMANN
		                   * (state.temperatureIon + state.temperatureElectron);

		// ITER design thermal energy at full power: ~400 MJ
		// Normalize to this value
		double E_ITER_max = 400.0e6; // J
		double severity = Math.min(1.0, E_thermal / E_ITER_max);

		return severity;
	}

	/**
	 * Calculate time until plasma is completely cold (< 1000 K).
	 *
	 * @param initialTemperature Initial plasma temperature (K)
	 * @return Time until cold (s)
	 */
	public static double getTimeToCold(double initialTemperature) {
		// T(t) = T₀ × exp(-t/τ) + T_min
		// Solve for t when T = 1000 K
		double targetTemp = 1000.0;

		if (initialTemperature <= targetTemp) {
			return 0.0;
		}

		// t = -τ × ln((T - T_min) / (T₀ - T_min))
		double tau = THERMAL_QUENCH_TIME;
		double ratio = (targetTemp - MIN_TEMPERATURE) / (initialTemperature - MIN_TEMPERATURE);

		return -tau * Math.log(ratio);
	}

	/**
	 * Check if disruption is complete (plasma fully cold and current gone).
	 *
	 * @param state Plasma state
	 * @param plasmaCurrent Plasma current (MA)
	 * @return true if disruption is complete
	 */
	public static boolean isDisruptionComplete(PlasmaState state, double plasmaCurrent) {
		double avgTemp = (state.temperatureIon + state.temperatureElectron) / 2.0;

		// Disruption complete when:
		// 1. Temperature < 1000 K (essentially cold)
		// 2. Plasma current < 0.1 MA (essentially zero)
		return (avgTemp < 1000.0) && (plasmaCurrent < 0.1);
	}
}
