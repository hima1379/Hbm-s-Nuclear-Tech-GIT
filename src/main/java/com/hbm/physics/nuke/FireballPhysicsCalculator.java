package com.hbm.physics.nuke;

/**
 * FIREBALL PHYSICS CALCULATOR
 *
 * Time-dependent physics for nuclear explosion fireball evolution.
 * Based on "Effects of Nuclear Weapons" (Glasstone & Dolan 1977).
 *
 * This class handles the physics calculations that change over time:
 * - Taylor-Sedov blast wave expansion
 * - Stefan-Boltzmann radiative cooling
 * - Buoyancy and lift forces
 * - Phase transition detection
 *
 * SEPARATION FROM CRATER FORMATION:
 * - CraterFormationProcessor: Static crater (pre-calculated destruction zones)
 * - FireballPhysicsCalculator: Dynamic fireball evolution (time-dependent)
 *
 * @author HBM Nuclear Tech Team
 * @see "Effects of Nuclear Weapons, 1977, 3rd Edition, Pages 29-82"
 */
public class FireballPhysicsCalculator {

	// === PHYSICAL CONSTANTS ===
	private static final double STEFAN_BOLTZMANN = 5.670374419e-8; // W·m⁻²·K⁻⁴
	private static final double GRAVITY = 9.81; // m/s²
	private static final double AIR_DENSITY = 1.225; // kg/m³ at sea level
	private static final double SPECIFIC_HEAT_AIR = 1005.0; // J/(kg·K)
	private static final double GAS_CONSTANT = 287.05; // J/(kg·K) for dry air
	private static final double JOULES_PER_KILOTON = 4.184e12; // 1 kt TNT = 4.184 TJ

	// === FIREBALL SCALING CONSTANTS (from PDF) ===
	// R = C × W^0.4 feet → converted to meters
	private static final double RADIUS_THERMAL_MIN = 90.0 * 0.3048; // 27.4 m/kt^0.4 (thermal minimum)
	private static final double RADIUS_BREAKAWAY = 110.0 * 0.3048; // 33.5 m/kt^0.4 (air burst)
	private static final double RADIUS_MAXIMUM_FACTOR = 2.0; // Maximum ~2× breakaway

	// === TEMPERATURE CONSTANTS (from PDF §2.123) ===
	private static final double TEMP_INITIAL = 1e7; // 10 million K (X-ray phase)
	private static final double TEMP_MINIMUM = 3000.0 + 273.15; // 3000°C (thermal minimum)
	private static final double TEMP_MAXIMUM = 7700.0 + 273.15; // 7700°C (second pulse)
	private static final double TEMP_BREAKAWAY = 300000.0 + 273.15; // 300,000°C (internal, shock formation)

	// === TAYLOR-SEDOV EXPANSION (PDF §2.114) ===
	/**
	 * Calculate fireball radius using Taylor-Sedov blast wave theory.
	 *
	 * PDF: "Isothermal expansion - energy transfer by radiation"
	 * Formula: R(t) = C × (E × t²/ ρ)^(1/5)
	 *
	 * Where:
	 * - E = explosion energy (Joules)
	 * - t = time since detonation (seconds)
	 * - ρ = air density (kg/m³)
	 * - C = dimensionless constant ≈ 1.0
	 *
	 * @param timeSeconds Time since detonation (seconds)
	 * @param yieldKilotons Weapon yield (kilotons TNT)
	 * @param airDensity Air density at detonation altitude (kg/m³)
	 * @return Fireball radius (meters)
	 */
	public static double calculateFireballRadius(double timeSeconds, double yieldKilotons, double airDensity) {
		// 85% of energy goes to kinetic energy (blast wave)
		double energy = yieldKilotons * JOULES_PER_KILOTON * 0.85;

		// Taylor-Sedov formula: R = (E×t²/ρ)^(1/5)
		double radiusCubed = (energy * timeSeconds * timeSeconds) / airDensity;
		double radius = Math.pow(radiusCubed, 0.2); // Fifth root

		// Clamp to physical limits (minimum 1m, maximum from PDF scaling)
		double maxRadius = RADIUS_BREAKAWAY * Math.pow(yieldKilotons, 0.4) * RADIUS_MAXIMUM_FACTOR;
		return Math.max(1.0, Math.min(radius, maxRadius));
	}

	/**
	 * Calculate expansion velocity from Taylor-Sedov theory.
	 *
	 * From Taylor-Sedov formula R(t) = k × t^(2/5):
	 *   dR/dt = (2/5) × k × t^(-3/5) = (2/5) × R / t
	 *
	 * Physical interpretation:
	 * - Expansion velocity decreases with time as blast wave loses energy
	 * - Early time (t → 0): Very high expansion velocity
	 * - Late time (t → ∞): Expansion velocity approaches zero
	 *
	 * Used to determine when buoyancy overcomes expansion inertia:
	 * - Early: v_expansion >> v_rise → Sphere phase (expansion dominates)
	 * - Late: v_expansion ≈ v_rise → Pinching phase (transition)
	 *
	 * Reference: Taylor (1950), Sedov (1959) blast wave theory
	 *
	 * @param fireballRadius Current radius (meters)
	 * @param timeSeconds Time since detonation (seconds)
	 * @return Expansion velocity dR/dt (m/s)
	 */
	public static double calculateExpansionVelocity(double fireballRadius, double timeSeconds) {
		if (timeSeconds < 0.01) {
			// Very early time: Use initial velocity estimate
			// Assume reaches current radius in 0.01s
			return fireballRadius / 0.01;
		}
		// Taylor-Sedov scaling: dR/dt = (2/5) × R / t
		return (2.0 / 5.0) * fireballRadius / timeSeconds;
	}

	/**
	 * Calculate fireball expansion momentum.
	 *
	 * Momentum: p = m × v_expansion
	 *
	 * Expansion momentum represents inertia of the expanding blast wave.
	 * Used to determine sphere → pinching transition:
	 * - When buoyancy force × dt > expansion momentum: Pinching begins
	 * - Pinching parameter: Π = F_buoyancy / (p_expansion / dt)
	 *
	 * Physical basis:
	 * - Fireball mass: m ∝ ρ × R³ ∝ W^1.2
	 * - Expansion velocity: v ∝ R / t ∝ W^0.4 / W^0.5 = W^(-0.1)
	 * - Momentum: p ∝ W^1.2 × W^(-0.1) = W^1.1
	 *
	 * Larger yields have higher expansion momentum, delaying pinching.
	 *
	 * @param fireballMass Mass of hot air (kg) - from calculateFireballMass()
	 * @param expansionVelocity Radial expansion rate (m/s) - from calculateExpansionVelocity()
	 * @return Expansion momentum (kg·m/s)
	 */
	public static double calculateExpansionMomentum(double fireballMass, double expansionVelocity) {
		return fireballMass * expansionVelocity;
	}

	// === STEFAN-BOLTZMANN RADIATIVE COOLING (PDF implicit in §2.122) ===
	/**
	 * Calculate temperature change rate due to blackbody radiation.
	 *
	 * Stefan-Boltzmann Law: P = σ × A × T⁴
	 * Energy loss: dE/dt = σ × A × T⁴
	 * Temperature change: dT/dt = -σ × A × T⁴ / (m × C_p)
	 *
	 * @param currentTemp Current fireball temperature (Kelvin)
	 * @param radius Fireball radius (meters)
	 * @param mass Fireball mass (kg)
	 * @param deltaTime Time step (seconds)
	 * @return New temperature after cooling (Kelvin)
	 */
	public static double calculateRadiativeCooling(double currentTemp, double radius, double mass, double deltaTime) {
		// Surface area
		double area = 4.0 * Math.PI * radius * radius;

		// Temperature derivative: dT/dt = -σ×A×T⁴/(m×C_p)
		double dTdt = -(STEFAN_BOLTZMANN * area * Math.pow(currentTemp, 4)) / (mass * SPECIFIC_HEAT_AIR);

		// Euler integration
		double newTemp = currentTemp + dTdt * deltaTime;

		// Prevent negative temperatures
		return Math.max(newTemp, 273.15); // Minimum: 0°C
	}

	// === BUOYANCY FORCE (PDF §2.129) ===
	/**
	 * Calculate buoyancy force using Archimedes' principle.
	 *
	 * PDF: "Fireball rises like a hot-air balloon"
	 * F_b = (ρ_air - ρ_hot) × g × V
	 *
	 * Where:
	 * - ρ_air = ambient air density
	 * - ρ_hot = hot air density (from ideal gas law)
	 * - g = gravity
	 * - V = fireball volume
	 *
	 * @param radius Fireball radius (meters)
	 * @param temperature Fireball temperature (Kelvin)
	 * @param ambientDensity Ambient air density (kg/m³)
	 * @return Buoyancy force (Newtons), positive = upward
	 */
	public static double calculateBuoyancyForce(double radius, double temperature, double ambientDensity) {
		// Fireball volume
		double volume = (4.0 / 3.0) * Math.PI * Math.pow(radius, 3);

		// Hot air density (ideal gas law: ρ_hot = ρ_ambient × T_ambient / T_hot)
		double ambientTemp = 273.15 + 15.0; // 15°C reference
		double hotDensity = ambientDensity * (ambientTemp / temperature);

		// Buoyancy force (Archimedes)
		return (ambientDensity - hotDensity) * GRAVITY * volume;
	}

	// === SHOCK WAVE VELOCITY (PDF §2.120) ===
	/**
	 * Calculate shock wave radius.
	 *
	 * PDF §2.115: "Shock front moves ahead of isothermal sphere"
	 * Shock velocity initially supersonic, decays to sonic speed.
	 *
	 * @param timeSeconds Time since detonation (seconds)
	 * @param yieldKilotons Weapon yield (kilotons)
	 * @return Shock wave radius (meters)
	 */
	public static double calculateShockWaveRadius(double timeSeconds, double yieldKilotons) {
		// Shock wave travels faster than fireball initially
		// Use modified Taylor-Sedov with higher coefficient
		double energy = yieldKilotons * JOULES_PER_KILOTON;
		double radiusCubed = (energy * timeSeconds * timeSeconds) / AIR_DENSITY;
		double shockRadius = 1.2 * Math.pow(radiusCubed, 0.2); // 20% faster than fireball

		return shockRadius;
	}

	// === PHASE DETECTION (PDF §2.115, §2.120) ===
	/**
	 * Detect current explosion phase based on temperature and velocity.
	 *
	 * PHYSICS-BASED CRITERIA (not time-based!):
	 *
	 * Phase 0: Initial flash (T = 10^7 K)
	 * Phase 1: Isothermal expansion (10^7 K → 3000°C)
	 * Phase 2: Hydrodynamic separation & rise (T < 3000°C, shock transparent)
	 * Phase 3: Mushroom formation (cloud reaches tropopause)
	 * Phase 4: Stabilization (cloud at maximum height)
	 *
	 * @param temperature Current temperature (Kelvin)
	 * @param expansionVelocity Current radius expansion rate (m/s)
	 * @param cloudHeight Current cloud height (meters)
	 * @param targetCloudHeight Stabilization height (meters)
	 * @param currentPhase Current phase (for transition detection)
	 * @return New phase (0-4)
	 */
	public static int detectPhase(double temperature, double expansionVelocity, double cloudHeight,
			double targetCloudHeight, int currentPhase) {

		// Phase 0 → 1: Always at first tick
		if (currentPhase == 0) {
			return 1; // Start isothermal expansion
		}

		// Phase 1 → 2: Hydrodynamic separation (PDF §2.115, §2.120)
		// Criteria: Surface temperature drops below thermal minimum AND expansion slows to sonic speed
		double soundSpeed = 340.0; // m/s at sea level
		if (currentPhase == 1 && temperature < TEMP_MINIMUM && expansionVelocity < soundSpeed) {
			return 2; // Shock front becomes transparent, buoyant rise begins
		}

		// Phase 2 → 3: Mushroom cloud formation (PDF §2.14)
		// Criteria: Cloud reaches 80% of stabilization height
		if (currentPhase == 2 && cloudHeight > targetCloudHeight * 0.8) {
			return 3; // Toroidal convection at atmospheric inversion layer
		}

		// Phase 3 → 4: Cloud stabilization (PDF §2.15)
		// Criteria: Cloud reaches 95% of maximum height
		if (currentPhase == 3 && cloudHeight > targetCloudHeight * 0.95) {
			return 4; // Lateral spreading, slow rise continues
		}

		// No phase change
		return currentPhase;
	}

	// === ATMOSPHERIC DENSITY PROFILE ===
	/**
	 * Calculate air density at given altitude.
	 *
	 * Barometric formula: ρ(h) = ρ₀ × exp(-h / H_scale)
	 * Where H_scale = 8,500 meters
	 *
	 * @param altitude Height above sea level (meters)
	 * @return Air density (kg/m³)
	 */
	public static double getAirDensity(double altitude) {
		double SCALE_HEIGHT = 8500.0; // meters
		double SEA_LEVEL_DENSITY = 1.225; // kg/m³

		return SEA_LEVEL_DENSITY * Math.exp(-altitude / SCALE_HEIGHT);
	}

	// ========================================================================
	// ATMOSPHERIC STRATIFICATION (STANDARD ATMOSPHERE MODEL)
	// Reference: U.S. Standard Atmosphere (1976), NOAA/NASA/USAF
	// ========================================================================

	/**
	 * Calculate tropopause height (atmospheric inversion layer).
	 *
	 * The tropopause marks the boundary between troposphere and stratosphere.
	 * Key characteristic: Temperature stops decreasing and starts increasing.
	 * This creates a stable layer (Richardson number >> 1) that halts vertical motion.
	 *
	 * Mushroom clouds rise until reaching the tropopause, then spread horizontally.
	 *
	 * Tropopause height varies with latitude:
	 * - Equator (0°): 16-18 km (warm tropics, deep convection)
	 * - Mid-latitude (30-60°): 10-13 km (temperate zones)
	 * - Poles (60-90°): 8-10 km (cold, shallow troposphere)
	 *
	 * Physical basis:
	 * - Troposphere: Convection-dominated, dT/dz < 0 (unstable)
	 * - Tropopause: Transition layer, dT/dz ≈ 0 (neutral)
	 * - Stratosphere: Radiation-dominated, dT/dz > 0 (stable inversion)
	 *
	 * Reference: Wallace & Hobbs "Atmospheric Science" (2006)
	 *
	 * @param latitude Latitude in degrees (-90 to +90, 0 = Equator)
	 * @return Tropopause height (meters above sea level)
	 */
	public static double calculateTropopauseHeight(double latitude) {
		double absLatitude = Math.abs(latitude);

		if (absLatitude < 30.0) {
			// Tropical: 16-18 km (average 17 km)
			return 17000.0;
		} else if (absLatitude < 60.0) {
			// Mid-latitude: Linear interpolation 17 km → 11 km
			double t = (absLatitude - 30.0) / 30.0; // 0.0-1.0
			return 17000.0 - t * 6000.0; // 17000 → 11000 meters
		} else {
			// Polar: 8-10 km (average 9 km)
			return 9000.0;
		}
	}

	/**
	 * Get atmospheric temperature at given altitude.
	 *
	 * Standard atmosphere model (U.S. Standard Atmosphere 1976):
	 * - Troposphere (0-11 km): T = T₀ - 6.5 K/km (lapse rate)
	 * - Tropopause (11-20 km): T = 216.65 K (isothermal layer)
	 * - Stratosphere (20-47 km): T = 216.65 K + 1.0 K/km (temperature inversion)
	 * - Stratopause (47-51 km): T = 270.65 K (isothermal)
	 * - Mesosphere (51-71 km): T = 270.65 K - 2.8 K/km
	 *
	 * Temperature inversion in stratosphere is caused by:
	 * - Ozone layer absorbing UV radiation
	 * - Heating from above instead of below
	 * - Creates stable stratification (Ri >> 1)
	 *
	 * This inversion is WHY mushroom clouds flatten:
	 * - Buoyancy requires ρ_cloud < ρ_ambient
	 * - In inversion layer: T increases with altitude → ρ decreases slower
	 * - Cloud cannot maintain buoyancy, spreads horizontally
	 *
	 * Reference: NOAA/NASA/USAF Standard Atmosphere tables
	 *
	 * @param altitude Height above sea level (meters)
	 * @return Atmospheric temperature (Kelvin)
	 */
	public static double getAtmosphericTemperature(double altitude) {
		double T_0 = 288.15; // Sea level: 15°C = 288.15 K

		if (altitude < 11000.0) {
			// Troposphere: Linear decrease at 6.5 K/km lapse rate
			return T_0 - 0.0065 * altitude;
		} else if (altitude < 20000.0) {
			// Tropopause: Isothermal layer at -56.5°C
			return 216.65;
		} else if (altitude < 47000.0) {
			// Stratosphere: Linear increase at 1.0 K/km (temperature inversion)
			return 216.65 + 0.001 * (altitude - 20000.0);
		} else if (altitude < 51000.0) {
			// Stratopause: Isothermal at -2.5°C
			return 270.65;
		} else if (altitude < 71000.0) {
			// Mesosphere: Linear decrease at 2.8 K/km
			return 270.65 - 0.0028 * (altitude - 51000.0);
		} else {
			// Mesopause and above: Cold layer
			return 214.65; // -58.5°C
		}
	}

	// === FIREBALL MASS ===
	/**
	 * Calculate fireball mass from radius and temperature.
	 *
	 * Uses ideal gas law to determine hot air density.
	 *
	 * @param radius Fireball radius (meters)
	 * @param temperature Fireball temperature (Kelvin)
	 * @return Fireball mass (kg)
	 */
	public static double calculateFireballMass(double radius, double temperature) {
		double volume = (4.0 / 3.0) * Math.PI * Math.pow(radius, 3);
		double ambientTemp = 273.15 + 15.0; // 15°C
		double hotDensity = AIR_DENSITY * (ambientTemp / temperature);
		return hotDensity * volume;
	}

	// === CLOUD HEIGHT CALCULATION (from PDF Fig 2.16) ===
	/**
	 * Calculate mushroom cloud stabilization height based on yield.
	 *
	 * Data from Glasstone & Dolan (1977) Figure 2.16.
	 *
	 * @param yieldKilotons Weapon yield (kilotons)
	 * @return Stabilization height (meters above ground)
	 */
	public static double calculateCloudHeight(double yieldKilotons) {
		if (yieldKilotons < 100) {
			// Tactical: 3-8 km
			return 3000.0 + (5000.0 * (yieldKilotons / 100.0));
		} else if (yieldKilotons < 1000) {
			// Strategic: 8-13 km
			return 8000.0 + (5000.0 * ((yieldKilotons - 100) / 900.0));
		} else if (yieldKilotons < 10000) {
			// Megaton: 13-20 km
			return 13000.0 + (7000.0 * ((yieldKilotons - 1000) / 9000.0));
		} else {
			// Super-megaton: 20-40 km
			return 20000.0 + (20000.0 * Math.min((yieldKilotons - 10000) / 40000.0, 1.0));
		}
	}

	// === TOROIDAL DIMENSIONS (from PDF §2.07, Fig 2.07a) ===
	/**
	 * Calculate toroidal convection structure dimensions for mushroom cloud.
	 *
	 * PDF §2.07: "The roughly spherical form becomes a toroid (or doughnut)"
	 *
	 * Based on visual analysis of PDF Figure 2.07a and typical vortex ring geometry.
	 *
	 * @param cloudRadius Current mushroom cloud radius (meters)
	 * @return Array [R_major, R_minor] - major radius and minor (tube) radius (meters)
	 */
	public static double[] calculateToroidalDimensions(double cloudRadius) {
		// Major radius: distance from torus axis to center of tube
		// PDF Figure 2.07a shows major radius ≈ half of cloud radius
		double R_major = cloudRadius * 0.5;

		// Minor radius: radius of the torus tube itself
		// PDF Figure 2.07a shows tube radius ≈ 1/3 of cloud radius
		double R_minor = cloudRadius * 0.33;

		return new double[] { R_major, R_minor };
	}

	// === BUOYANT RISE VELOCITY (from PDF Table 2.12) ===
	/**
	 * Calculate buoyant rise velocity based on PDF empirical data.
	 *
	 * PDF Table 2.12 (1-megaton air burst):
	 * - 0-1 minute: Average 440 ft/s (134 m/s)
	 * - 1-2 minutes: Decreasing to 220 ft/s (67 m/s)
	 * - 2-10 minutes: Gradual deceleration to 27 mph (12 m/s)
	 *
	 * Formula: v(t) = v_0 * exp(-t / τ)
	 * Where τ is decay time constant
	 *
	 * @param timeSeconds Time since detonation (seconds)
	 * @param yieldKilotons Weapon yield (kilotons)
	 * @return Rise velocity (meters/second)
	 */
	public static double calculateBuoyantRiseVelocity(double timeSeconds, double yieldKilotons) {
		// Initial rise velocity scales with yield^0.25 (buoyancy ~ volume ~ yield^(1/3), inertia ~ mass ~ yield^(1/3))
		// PDF Table 2.12: 1 MT → 440 ft/s initial
		double v_0 = 134.0 * Math.pow(yieldKilotons / 1000.0, 0.25); // m/s

		// Decay time constant (seconds)
		// PDF: velocity halves in ~60-120 seconds for 1 MT
		double tau = 90.0 * Math.pow(yieldKilotons / 1000.0, 0.2); // Scales slightly with yield

		// Exponential decay
		double velocity = v_0 * Math.exp(-timeSeconds / tau);

		// Minimum velocity (gravitational settling)
		double v_min = 5.0; // m/s

		return Math.max(velocity, v_min);
	}

	// ========================================================================
	// CONTINUOUS DEFORMATION PROGRESS (PHASE-INDEPENDENT)
	// ========================================================================

	/**
	 * Smooth interpolation function (S-curve)
	 *
	 * Provides smooth acceleration and deceleration for natural-looking transitions.
	 * Formula: t² × (3 - 2t)
	 *
	 * @param edge0 Lower bound
	 * @param edge1 Upper bound
	 * @param x Input value
	 * @return Smoothly interpolated value (0.0-1.0)
	 */
	public static double smoothstep(double edge0, double edge1, double x) {
		double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0 - 2.0 * t); // Smooth S-curve
	}

	/**
	 * PHYSICS-BASED DEFORMATION PROGRESS (Option A: Multi-Parameter Function)
	 *
	 * Calculate continuous deformation progress (sphere → mushroom cloud) using
	 * multiple physical parameters and dimensionless numbers.
	 *
	 * PHYSICAL TRANSITION CRITERIA (from Glasstone & Dolan + fluid dynamics):
	 * - Stage 1 (0.0-0.3): Spherical fireball
	 *   → Fr > 5.0: Expansion inertia dominates buoyancy
	 *   → Temperature > 5000K: Radiating shock front
	 * - Stage 2 (0.3-0.5): Pinching begins
	 *   → 1.0 < Fr < 5.0: Buoyancy competes with expansion
	 *   → Radius approaching maximum (r/r_max > 0.7)
	 * - Stage 3 (0.5-0.7): Stem formation
	 *   → Ro < 1.0: Rotational forces dominate advection
	 *   → Velocity decay: v/v_0 < 0.5
	 * - Stage 4 (0.7-1.0): Mushroom cap
	 *   → Ri > 1.0: Atmospheric stratification halts rise
	 *   → Cloud reaches tropopause (stable layer)
	 *
	 * @param timeSeconds Time since detonation (seconds)
	 * @param yieldKilotons Weapon yield (kilotons TNT equivalent)
	 * @param fireballRadius Current fireball radius (meters)
	 * @param riseVelocity Current vertical rise velocity (m/s)
	 * @param cloudHeight Current cloud top altitude (meters)
	 * @param temperature Current fireball temperature (Kelvin)
	 * @param buoyancyForce Current buoyancy force (Newtons)
	 * @return Deformation progress (0.0-1.0)
	 *
	 * Reference:
	 * - Glasstone & Dolan (1977) §2.04-2.16: Fireball growth and rise
	 * - Taylor-Sedov blast wave theory: Expansion dynamics
	 * - Froude number transition: Buoyancy vs inertia
	 * - Richardson number: Stratification stability
	 * - Rossby number: Rotational flow formation
	 */
	public static double calculateDeformationProgress(
			double timeSeconds,
			double yieldKilotons,
			double fireballRadius,
			double riseVelocity,
			double cloudHeight,
			double temperature,
			double buoyancyForce) {

		// === SAFETY CHECKS ===
		// Ensure all inputs are valid (no NaN, Infinity, or zero/negative values)
		if (Double.isNaN(fireballRadius) || Double.isInfinite(fireballRadius) || fireballRadius <= 0.0) {
			System.err.println("[ERROR] Invalid fireballRadius: " + fireballRadius);
			return 0.0;
		}
		if (Double.isNaN(temperature) || Double.isInfinite(temperature) || temperature <= 0.0) {
			System.err.println("[ERROR] Invalid temperature: " + temperature);
			return 0.0;
		}
		if (yieldKilotons <= 0.0) {
			System.err.println("[ERROR] Invalid yieldKilotons: " + yieldKilotons);
			return 0.0;
		}

		// === PHYSICS PARAMETERS ===
		// Maximum fireball radius (empirical from Glasstone & Dolan)
		double maxRadius = 67.0 * Math.pow(yieldKilotons, 0.4);

		// Initial rise velocity (buoyancy-driven, Archimedes principle)
		double initialVelocity = 134.0 * Math.pow(yieldKilotons / 1000.0, 0.25);

		// Characteristic length (fireball radius)
		double characteristicLength = Math.max(fireballRadius, 10.0);

		// Tropopause height (latitude = 0 for tropical maximum)
		double tropopauseHeight = calculateTropopauseHeight(0.0);

		// === DIMENSIONLESS NUMBERS ===
		// Froude number: Fr = v²/(g×L) - Measures buoyancy vs inertia
		double expansionVelocity = calculateExpansionVelocity(fireballRadius, timeSeconds);
		double totalVelocity = Math.sqrt(expansionVelocity * expansionVelocity +
		                                  riseVelocity * riseVelocity);
		double Fr = calculateFroudeNumber(totalVelocity, characteristicLength);

		// Richardson number: Ri = (g/ρ)×(dρ/dz)/(du/dz)² - Atmospheric stability
		double Ri = calculateRichardsonNumber(cloudHeight);

		// Vorticity and circulation (for Rossby number)
		double vorticity = calculateVorticityMagnitude(riseVelocity, fireballRadius);
		double Ro = calculateRossbyNumber(riseVelocity, vorticity, characteristicLength);

		// === NORMALIZED RATIOS ===
		// Radius ratio: How close to maximum expansion?
		double radiusRatio = fireballRadius / maxRadius;

		// Velocity decay: How much has rise velocity decreased?
		double velocityRatio = (initialVelocity > 0.1) ? (riseVelocity / initialVelocity) : 1.0;

		// Temperature ratio: Cooling progress
		double tempRatio = temperature / 1e7; // Initial temp ~10 million K

		// Height ratio: How close to tropopause?
		double heightRatio = cloudHeight / tropopauseHeight;

		// === STAGE DETERMINATION (PHYSICS-BASED) ===
		double progress = 0.0;

		// STAGE 1: SPHERICAL FIREBALL (0.0-0.3)
		// Criteria: High Fr (>5), low radius ratio (<0.7), high temperature (>5000K)
		if (Fr > 5.0 || radiusRatio < 0.7 || temperature > 5000.0) {
			// Early expansion phase
			// Progress based on radius growth: 0.0 at r=0, 0.3 at r=0.7×r_max
			progress = 0.3 * Math.min(1.0, radiusRatio / 0.7);

			// Smooth start (avoid instant jump to 0.3)
			progress = smoothstep(0.0, 0.3, progress);

		// STAGE 2: PINCHING BEGINS (0.3-0.5)
		// Criteria: Moderate Fr (1-5), approaching max radius, cooling below 5000K
		} else if (Fr > 1.0 && radiusRatio < 0.95 && temperature < 5000.0) {
			// Transition from sphere to pinching
			// Base progress: 0.3
			double stageProgress = 0.0;

			// Factor 1: Froude number decreasing (5.0 → 1.0)
			double frFactor = (5.0 - Fr) / 4.0; // 0.0 at Fr=5, 1.0 at Fr=1
			frFactor = Math.max(0.0, Math.min(1.0, frFactor));

			// Factor 2: Radius approaching maximum (0.7 → 0.95)
			double radFactor = (radiusRatio - 0.7) / 0.25; // 0.0 at 0.7, 1.0 at 0.95
			radFactor = Math.max(0.0, Math.min(1.0, radFactor));

			// Factor 3: Temperature cooling (5000K → 3000K)
			double tempFactor = 1.0 - (temperature - 3000.0) / 2000.0;
			tempFactor = Math.max(0.0, Math.min(1.0, tempFactor));

			// Combine factors (weighted average)
			stageProgress = (frFactor * 0.4 + radFactor * 0.4 + tempFactor * 0.2);

			progress = 0.3 + stageProgress * 0.2; // 0.3-0.5 range
			progress = smoothstep(0.3, 0.5, progress);

		// STAGE 3: STEM FORMATION (0.5-0.7)
		// Criteria: Low Ro (<1.0), velocity decay, toroidal circulation
		} else if (Ro < 1.5 && velocityRatio < 0.8) {
			// Toroidal vortex forming
			// Base progress: 0.5
			double stageProgress = 0.0;

			// Factor 1: Rossby number decreasing (1.5 → 0.5)
			double roFactor = (1.5 - Ro) / 1.0; // 0.0 at Ro=1.5, 1.0 at Ro=0.5
			roFactor = Math.max(0.0, Math.min(1.0, roFactor));

			// Factor 2: Velocity decay (0.8 → 0.4)
			double velFactor = (0.8 - velocityRatio) / 0.4; // 0.0 at 0.8, 1.0 at 0.4
			velFactor = Math.max(0.0, Math.min(1.0, velFactor));

			// Factor 3: Height increasing (approaching tropopause)
			double heightFactor = Math.min(1.0, heightRatio / 0.7);

			// Combine factors
			stageProgress = (roFactor * 0.5 + velFactor * 0.3 + heightFactor * 0.2);

			progress = 0.5 + stageProgress * 0.2; // 0.5-0.7 range
			progress = smoothstep(0.5, 0.7, progress);

		// STAGE 4: MUSHROOM CAP (0.7-1.0)
		// Criteria: High Ri (>1.0), reached tropopause, velocity near zero
		} else {
			// Cloud stabilizing at tropopause
			// Base progress: 0.7
			double stageProgress = 0.0;

			// Factor 1: Richardson number increasing (stability)
			double riFactor = Math.min(1.0, (Ri - 1.0) / 5.0); // 0.0 at Ri=1, 1.0 at Ri=6

			// Factor 2: Height at tropopause (0.7 → 1.0 of tropopause)
			double heightFactor = (heightRatio - 0.7) / 0.3;
			heightFactor = Math.max(0.0, Math.min(1.0, heightFactor));

			// Factor 3: Velocity nearly stopped (<0.2 of initial)
			double velFactor = 1.0 - Math.min(1.0, velocityRatio / 0.2);

			// Factor 4: Time-based (eventually stabilizes regardless)
			double timeFactor = Math.min(1.0, (timeSeconds - 20.0) / 60.0); // 20-80s

			// Combine factors
			stageProgress = (riFactor * 0.3 + heightFactor * 0.3 +
			                 velFactor * 0.2 + timeFactor * 0.2);

			progress = 0.7 + stageProgress * 0.3; // 0.7-1.0 range
			progress = smoothstep(0.7, 1.0, progress);
		}

		// === SANITY CHECKS ===
		// Ensure progress never decreases (monotonic increase)
		// Note: This should be handled by entity state tracking

		// Check for NaN or Infinity
		if (Double.isNaN(progress) || Double.isInfinite(progress)) {
			System.err.println("[ERROR] Invalid progress calculated: " + progress);
			System.err.println("  Fr=" + Fr + " Ri=" + Ri + " Ro=" + Ro);
			System.err.println("  radiusRatio=" + radiusRatio + " velocityRatio=" + velocityRatio);
			System.err.println("  fireballRadius=" + fireballRadius + " cloudHeight=" + cloudHeight);
			return 0.0;
		}

		// Clamp to valid range
		progress = Math.max(0.0, Math.min(1.0, progress));

		return progress;
	}

	/**
	 * PHYSICS-BASED MUSHROOM CLOUD GEOMETRY
	 *
	 * Calculate mushroom cloud geometry using physical equations instead of
	 * fixed fractions. Uses center of mass height, aspect ratio from Froude number,
	 * and toroidal dimensions from Hill's spherical vortex theory.
	 *
	 * Returns multi-layer geometry for rendering:
	 * - Cap (deformed sphere → oblate spheroid)
	 * - Stem (narrow toroidal ring stretched vertically)
	 * - Internal flow (toroidal circulation visualization)
	 *
	 * @param progress Deformation progress (0.0-1.0 from calculateDeformationProgress)
	 * @param fireballRadius Current fireball radius (meters)
	 * @param cloudWidth Current cloud horizontal extent (meters)
	 * @param cloudHeight Current cloud vertical height (meters)
	 * @param timeSeconds Time since detonation (for center of mass calculation)
	 * @param yieldKilotons Weapon yield (for physics scaling)
	 * @param Fr Froude number (for aspect ratio determination)
	 * @return MushroomGeometry object with cap, stem, and flow parameters
	 *
	 * Reference:
	 * - .claude/nuclear-particle-dynamics-knowledge.md: Toroidal dimensions
	 * - Glasstone & Dolan §2.16: Cloud stabilization height
	 * - Hill's spherical vortex: R_major = 0.5×R, R_minor = 0.33×R
	 */
	public static MushroomGeometry calculateMushroomGeometry(
			double progress,
			double fireballRadius,
			double cloudWidth,
			double cloudHeight,
			double timeSeconds,
			double yieldKilotons,
			double Fr) {
		MushroomGeometry geom = new MushroomGeometry();
		geom.progress = progress;

		// === PHYSICS-BASED CALCULATIONS ===
		// Center of mass height (integral of exponential velocity decay)
		double centerOfMassY = calculateCenterOfMassHeight(timeSeconds, yieldKilotons);

		// Aspect ratio from Froude number and progress
		double aspectRatio = calculateAspectRatio(Fr, progress);

		// Toroidal dimensions (Hill's spherical vortex theory)
		// R_major = 0.5 × cloud radius, R_minor = 0.33 × cloud radius
		double cloudRadius = Math.max(fireballRadius, cloudWidth * 0.5);
		double toroidMajorRadius = cloudRadius * 0.5;
		double toroidMinorRadius = cloudRadius * 0.33;

		// === STAGE 1: SPHERICAL FIREBALL (0-30%) ===
		if (progress < 0.3) {
			double stageProgress = progress / 0.3; // 0.0-1.0 within stage

			// Cap: Perfect sphere (aspect ratio ~1.0)
			// Use fireball radius directly (not yet deformed)
			geom.capScaleX = fireballRadius * 2.0;
			geom.capScaleY = fireballRadius * 2.0; // Spherical
			geom.capScaleZ = fireballRadius * 2.0;

			// Cap position: Center of mass (starts half-buried)
			geom.capCenterY = Math.max(fireballRadius * 0.5, centerOfMassY);

			// Stem: Not yet visible (no toroidal circulation)
			geom.stemVisible = false;
			geom.stemScaleX = 0.0;
			geom.stemScaleY = 0.0;
			geom.stemScaleZ = 0.0;
			geom.stemCenterY = 0.0;

			// Internal flow: Not yet visible
			geom.flowVisible = false;
			geom.flowAlpha = 0.0;

		// === STAGE 2: PINCHING BEGINS (30-50%) ===
		} else if (progress < 0.5) {
			double stageProgress = (progress - 0.3) / 0.2; // 0.0-1.0 within stage
			double smoothProgress = smoothstep(0.0, 1.0, stageProgress);

			// Cap: Vertical compression from aspect ratio
			// Aspect ratio transitioning from 1.0 → 1.5 (can elongate first)
			double horizontalScale = fireballRadius * 2.0;
			double verticalScale = horizontalScale * aspectRatio;

			geom.capScaleX = horizontalScale;
			geom.capScaleY = verticalScale;
			geom.capScaleZ = horizontalScale;

			// Cap position: Center of mass (rising as buoyancy increases)
			geom.capCenterY = centerOfMassY;

			// Stem: Just starting to appear (gradual fade-in)
			geom.stemVisible = (stageProgress > 0.5);
			if (geom.stemVisible) {
				// Stem width: Based on toroid minor radius
				double stemWidth = toroidMinorRadius * 0.5 * smoothProgress; // Gradual appearance
				double stemHeight = fireballRadius * 0.5 * smoothProgress; // Short at first

				geom.stemScaleX = stemWidth * 2.0;
				geom.stemScaleY = stemHeight * 2.0;
				geom.stemScaleZ = stemWidth * 2.0;
				geom.stemCenterY = stemHeight * 0.5;
			} else {
				geom.stemScaleX = 0.0;
				geom.stemScaleY = 0.0;
				geom.stemScaleZ = 0.0;
				geom.stemCenterY = 0.0;
			}

			// Internal flow: Beginning to develop
			geom.flowVisible = (stageProgress > 0.5);
			geom.flowAlpha = smoothProgress * 0.3; // Up to 30% opacity

		// === STAGE 3: STEM FORMATION (50-70%) ===
		} else if (progress < 0.7) {
			double stageProgress = (progress - 0.5) / 0.2; // 0.0-1.0 within stage
			double smoothProgress = smoothstep(0.0, 1.0, stageProgress);

			// Cap: Strong flattening + horizontal expansion
			// Aspect ratio approaching minimum (0.35) as Froude number decreases
			double horizontalScale = Math.max(cloudWidth, fireballRadius * (1.0 + smoothProgress));
			double verticalScale = horizontalScale * aspectRatio;

			geom.capScaleX = horizontalScale * 2.0;
			geom.capScaleY = verticalScale * 2.0;
			geom.capScaleZ = horizontalScale * 2.0;

			// Cap position: Center of mass (high altitude now)
			geom.capCenterY = centerOfMassY;

			// Stem: Clearly visible, elongating vertically
			geom.stemVisible = true;

			// Stem width: Based on toroid minor radius (getting narrower)
			double stemWidth = toroidMinorRadius * (1.0 - smoothProgress * 0.4); // Narrowing

			// Stem height: Stretching to connect ground to cap
			// Height = cap center - (stem height / 2) should touch ground
			double stemHeight = Math.max(centerOfMassY * 0.6, fireballRadius * 1.5);

			geom.stemScaleX = stemWidth * 2.0;
			geom.stemScaleY = stemHeight * 2.0;
			geom.stemScaleZ = stemWidth * 2.0;
			geom.stemCenterY = stemHeight * 0.5;

			// Internal flow: Strong toroidal circulation
			geom.flowVisible = true;
			geom.flowAlpha = 0.3 + smoothProgress * 0.2; // 30% → 50% opacity

		// === STAGE 4: MUSHROOM CAP (70-100%) ===
		} else {
			double stageProgress = (progress - 0.7) / 0.3; // 0.0-1.0 within stage
			double smoothProgress = smoothstep(0.0, 1.0, stageProgress);

			// Cap: Maximum flattening (aspect ratio ~0.35, very oblate)
			// Horizontal spreading at tropopause (stratification prevents vertical rise)
			double horizontalScale = Math.max(cloudWidth, fireballRadius * 2.0);
			double verticalScale = horizontalScale * aspectRatio; // aspectRatio ≈ 0.35

			geom.capScaleX = horizontalScale * 2.0;
			geom.capScaleY = verticalScale * 2.0;
			geom.capScaleZ = horizontalScale * 2.0;

			// Cap position: Center of mass (stabilized at tropopause)
			// As cloud stabilizes, center of mass approaches cloudHeight
			double stabilizedHeight = centerOfMassY + (cloudHeight - centerOfMassY) * smoothProgress;
			geom.capCenterY = stabilizedHeight;

			// Stem: Long, narrow column connecting ground to cap
			geom.stemVisible = true;

			// Stem width: Very narrow (toroid minor radius × 0.4)
			double stemWidth = toroidMinorRadius * 0.6;

			// Stem height: Full height from ground to cap base
			// Cap base = capCenterY - capScaleY/2
			double capBase = stabilizedHeight - verticalScale;
			double stemHeight = Math.max(capBase * 0.8, cloudHeight * 0.4);

			geom.stemScaleX = stemWidth * 2.0;
			geom.stemScaleY = stemHeight * 2.0;
			geom.stemScaleZ = stemWidth * 2.0;
			geom.stemCenterY = stemHeight * 0.5;

			// Internal flow: Stabilized circulation (fading as turbulence decreases)
			geom.flowVisible = true;
			geom.flowAlpha = 0.5 - smoothProgress * 0.2; // Fading (50% → 30%)
		}

		return geom;
	}

	/**
	 * Mushroom cloud geometry structure (multi-layer rendering parameters)
	 */
	public static class MushroomGeometry {
		public double progress; // Current deformation progress (0.0-1.0)

		// Cap (mushroom head) - deformed sphere
		public double capScaleX;   // Horizontal scale (diameter)
		public double capScaleY;   // Vertical scale (diameter)
		public double capScaleZ;   // Horizontal scale (diameter)
		public double capCenterY;  // Vertical center position (meters)

		// Stem (mushroom stalk) - stretched torus
		public boolean stemVisible;
		public double stemScaleX;  // Width scale
		public double stemScaleY;  // Height scale
		public double stemScaleZ;  // Depth scale
		public double stemCenterY; // Vertical center position (meters)

		// Internal toroidal flow visualization
		public boolean flowVisible;
		public double flowAlpha;   // Transparency (0.0-1.0)
	}

	// ========================================================================
	// DEFORMATION AND SHAPE PHYSICS
	// ========================================================================

	/**
	 * Calculate aspect ratio from Froude number and deformation progress.
	 *
	 * Aspect Ratio: AR = Height / Width
	 * - AR = 1.0: Perfect sphere
	 * - AR > 1.0: Vertically elongated (pinching phase)
	 * - AR < 1.0: Vertically flattened (mushroom cap)
	 *
	 * Physical basis: Balance between buoyancy and inertia
	 * - High Fr (inertia >> buoyancy): Maintains spherical shape (AR = 1.0)
	 * - Low Fr (buoyancy >> inertia): Flattens into oblate spheroid (AR < 1.0)
	 * - Transition Fr ≈ 1: Can elongate vertically before flattening (AR > 1.0)
	 *
	 * Pinching phenomenon:
	 * - As buoyancy begins competing with expansion, bottom is "pinched" upward
	 * - Creates temporary vertical elongation (AR peaks at ~1.5)
	 * - Then flattens as buoyancy fully dominates (AR drops to 0.3-0.5)
	 *
	 * @param Fr Froude number (dimensionless, from calculateFroudeNumber)
	 * @param progress Deformation progress (0.0-1.0)
	 * @return Aspect ratio H/W (dimensionless)
	 */
	public static double calculateAspectRatio(double Fr, double progress) {
		// Early stage: Sphere (AR = 1.0)
		if (progress < 0.3) {
			return 1.0;
		}

		// Pinching stage (0.3-0.5): Can elongate before flattening
		// Higher Fr → more elongation (expansion still strong)
		if (progress < 0.5) {
			double maxElongation = 1.5; // Maximum AR during pinching
			double t = (progress - 0.3) / 0.2; // 0-1 within stage
			double frFactor = Math.min(1.0, Fr / 5.0); // 0-1 based on Fr (normalized at Fr=5)
			// Elongation proportional to both progress and Froude number
			return 1.0 + t * frFactor * (maxElongation - 1.0);
		}

		// Mushroom stage (0.5-1.0): Progressive flattening
		// Lower Fr → more flattening (buoyancy dominates)
		if (progress < 1.0) {
			double minAR = 0.35; // Final mushroom cap AR (height = 35% of width)
			double t = (progress - 0.5) / 0.5; // 0-1 within stage
			double frFactor = 1.0 / (1.0 + Fr); // Smaller Fr → stronger flattening
			// Smooth transition from elongated (1.5) to very flat (0.35)
			double currentAR = 1.5 - t * frFactor * (1.5 - minAR);
			return Math.max(minAR, currentAR); // Ensure doesn't go below minimum
		}

		// Stabilized mushroom cap
		return 0.35;
	}

	/**
	 * Calculate center of mass height from buoyant rise integration.
	 *
	 * Fireball rises due to buoyancy, following exponential velocity decay:
	 *   v(t) = v_0 × exp(-t / τ)  (from calculateBuoyantRiseVelocity)
	 *
	 * Integrating velocity gives center of mass height:
	 *   y_cm(t) = ∫₀ᵗ v(τ) dτ = v_0 × τ × (1 - exp(-t / τ))
	 *
	 * Physical interpretation:
	 * - Early time (t << τ): Linear rise, y ≈ v_0 × t
	 * - Late time (t >> τ): Asymptotic approach to maximum height, y → v_0 × τ
	 *
	 * Maximum theoretical height:
	 *   H_max = v_0 × τ = [134 × W^0.25] × [90 × W^0.2]
	 *         = 12060 × W^0.45 meters
	 *
	 * For 1 Mt (W = 1000 kt):
	 *   H_max = 12060 × 1000^0.45 ≈ 339 km (unrealistic - limited by tropopause)
	 *
	 * In reality: Cloud stops at tropopause (~10-15 km) due to stratification.
	 *
	 * @param timeSeconds Time since detonation (seconds)
	 * @param yieldKilotons Weapon yield (kilotons)
	 * @return Center of mass height (meters above ground)
	 */
	public static double calculateCenterOfMassHeight(double timeSeconds, double yieldKilotons) {
		// Initial rise velocity (from calculateBuoyantRiseVelocity)
		double v_0 = 134.0 * Math.pow(yieldKilotons / 1000.0, 0.25); // m/s

		// Decay time constant
		double tau = 90.0 * Math.pow(yieldKilotons / 1000.0, 0.2); // seconds

		// Integrate exponential decay: y(t) = v_0 × τ × (1 - exp(-t/τ))
		double height = v_0 * tau * (1.0 - Math.exp(-timeSeconds / tau));

		// Add initial offset: Fireball starts half-buried at ground
		// (Surface burst assumption - center at radius/2 above ground)
		double fireballRadius = calculateFireballRadius(timeSeconds, yieldKilotons, AIR_DENSITY);
		height += fireballRadius * 0.5;

		// Clamp to tropopause height (cloud cannot rise higher due to inversion layer)
		double tropopauseHeight = calculateTropopauseHeight(0.0); // Use equatorial value (max)
		return Math.min(height, tropopauseHeight);
	}

	// === PHASE GEOMETRY PARAMETERS (DEPRECATED - use calculateMushroomGeometry instead) ===
	/**
	 * @deprecated Use {@link #calculateMushroomGeometry(double, double, double, double)} for continuous deformation
	 * Calculate 3D geometry parameters for each explosion phase.
	 *
	 * Returns shape-specific parameters based on PDF descriptions:
	 * - Phase 1: Uniform sphere
	 * - Phase 2: Concentric spheres (core + shell)
	 * - Phase 3: Toroidal structure
	 * - Phase 4: Oblate spheroid (mushroom cap)
	 *
	 * @param phase Current explosion phase (0-4)
	 * @param fireballRadius Current fireball radius (meters)
	 * @param cloudHeight Current cloud height (meters)
	 * @param cloudRadius Current cloud radius (meters)
	 * @return PhaseGeometry object with shape parameters
	 */
	public static PhaseGeometry calculatePhaseGeometry(int phase, double fireballRadius,
			double cloudHeight, double cloudRadius) {
		PhaseGeometry geom = new PhaseGeometry();
		geom.phase = phase;

		switch (phase) {
			case 0: // X-ray heating (point source, not visualized)
			case 1: // Isothermal sphere - UNIFORM VOLUME FILL
				geom.shape = GeometryShape.SPHERE;
				geom.primaryRadius = fireballRadius;
				geom.secondaryRadius = 0.0;
				geom.centerY = 0.0; // Ground level (surface burst)
				break;

			case 2: // Shock-heated shell - TWO CONCENTRIC SPHERES
				geom.shape = GeometryShape.CONCENTRIC_SPHERES;
				geom.primaryRadius = fireballRadius * 0.7; // Inner core (70% of radius)
				geom.secondaryRadius = fireballRadius; // Outer shock shell
				geom.centerY = fireballRadius * 0.3; // Rising off ground
				break;

			case 3: // Toroidal convection - TORUS (DOUGHNUT)
				geom.shape = GeometryShape.TORUS;
				double[] torusDims = calculateToroidalDimensions(cloudRadius);
				geom.primaryRadius = torusDims[0]; // R_major
				geom.secondaryRadius = torusDims[1]; // R_minor
				geom.centerY = cloudHeight; // Torus center height
				break;

			case 4: // Stabilized mushroom - OBLATE SPHEROID
				geom.shape = GeometryShape.OBLATE_SPHEROID;
				geom.primaryRadius = cloudRadius; // Horizontal radius
				geom.secondaryRadius = cloudRadius * 0.3; // Vertical semi-axis (flattened)
				geom.centerY = cloudHeight; // Cap center height
				break;

			default:
				geom.shape = GeometryShape.SPHERE;
				geom.primaryRadius = fireballRadius;
				geom.secondaryRadius = 0.0;
				geom.centerY = 0.0;
		}

		return geom;
	}

	/**
	 * Calculate fireball spread factor (turbulent surface variation)
	 *
	 * Physics basis: Rayleigh-Taylor instabilities at shock front
	 * As the fireball expands, instabilities develop at the density interface,
	 * creating wavelike distortions and turbulent mixing.
	 *
	 * Reference: Glasstone & Dolan 1977 §2.116 - "Shock Front Instabilities"
	 *
	 * CONTINUOUS TIME-BASED FORMULA (phase-independent):
	 * - Early (0-2s): Smooth sphere (X-ray dominated)
	 * - Growth (2-10s): Rayleigh-Taylor instabilities develop
	 * - Mature (10s+): Maximum turbulence
	 *
	 * @param timeSeconds Time since detonation (seconds)
	 * @return Spread factor (0.0-0.8, where 0.8 = maximum turbulence)
	 */
	public static double calculateFireballSpread(double timeSeconds) {
		if (timeSeconds < 0.1) {
			// Initial flash - perfectly smooth (0.0-0.1s)
			return 0.0;
		} else if (timeSeconds < 10.0) {
			// Fireball growth - developing instabilities (0.1-10s)
			// Rayleigh-Taylor growth rate: σ = sqrt(Ag) where A = Atwood number
			// Smooth growth using smoothstep function
			double progress = (timeSeconds - 0.1) / 9.9; // 0.0-1.0
			double smoothProgress = smoothstep(0.0, 1.0, progress);
			return smoothProgress * 0.5; // Up to 0.5 during growth
		} else {
			// Mature fireball - maximum turbulence (10s+)
			// Kelvin-Helmholtz instabilities at shear layer
			return 0.8;
		}
	}

	/**
	 * Calculate temperature ratio between core and shell
	 *
	 * Physics basis: Blackbody radiation & shock compression
	 * - Core: Vaporized weapon debris (300,000°C internal)
	 * - Shell: Shock-heated air (7,700°C visible surface)
	 *
	 * Reference: Glasstone & Dolan 1977 §2.120 - "Fireball Internal Structure"
	 *
	 * The core temperature is determined by weapon energy deposition,
	 * while shell temperature is limited by air ionization and
	 * radiative cooling at the shock front.
	 *
	 * CONTINUOUS TEMPERATURE-BASED FORMULA (phase-independent):
	 * Ratio increases with core temperature (hotter = more differentiation)
	 *
	 * @param coreTemperature Current core temperature (Kelvin)
	 * @param initialTemperature Initial weapon deposition temperature (Kelvin)
	 * @return Temperature ratio (T_core / T_shell), range 1.0-3.0
	 */
	public static double getCoreShellTemperatureRatio(double coreTemperature, double initialTemperature) {
		// Normalize current temperature against initial temperature
		double tempFraction = coreTemperature / initialTemperature;

		// Ratio increases when hotter (more structure), decreases when cooled
		// Clamped between 1.0 (uniform) and 3.0 (maximum differentiation)
		double ratio = 1.0 + tempFraction * 2.0;
		return Math.min(3.0, Math.max(1.0, ratio));
	}

	// === GEOMETRY TYPES ===
	public enum GeometryShape {
		SPHERE,             // Phase 1: Uniform sphere
		CONCENTRIC_SPHERES, // Phase 2: Core + shell
		TORUS,              // Phase 3: Toroidal convection
		OBLATE_SPHEROID     // Phase 4: Mushroom cap
	}

	public static class PhaseGeometry {
		public int phase;
		public GeometryShape shape;
		public double primaryRadius;   // Main dimension (sphere radius, torus major radius, etc.)
		public double secondaryRadius; // Secondary dimension (shell thickness, torus minor radius, etc.)
		public double centerY;         // Vertical center position (meters above ground)
	}

	// ========================================================================
	// HILL'S SPHERICAL VORTEX THEORY (DELFIC CRM)
	// Reference: nuclear-particle-dynamics-knowledge.md Section 1
	// Source: ADA280688-55-129.pdf Chapter III
	// ========================================================================

	/**
	 * Calculate Hill's spherical vortex stream function.
	 *
	 * This describes the flow pattern around and inside a rising spherical vortex.
	 * The vortex represents the mushroom cloud's toroidal circulation.
	 *
	 * Inside sphere (r ≤ a):
	 *   ψ = Ur²(sinθ)² (5/4 - 3r²/4a²)
	 *
	 * Outside sphere (r > a):
	 *   ψ = (1/2)Ua³(sinθ)²/r
	 *
	 * @param r Radial distance from vortex center (meters)
	 * @param theta Angle from axis of motion (radians)
	 * @param a Vortex sphere radius (meters)
	 * @param U Velocity of rising vortex (m/s)
	 * @return Stream function value ψ
	 */
	public static double calculateVortexStreamFunction(double r, double theta, double a, double U) {
		double sinTheta = Math.sin(theta);
		double sinThetaSq = sinTheta * sinTheta;

		if (r <= a) {
			// Inside sphere
			double rSq = r * r;
			double aSq = a * a;
			return U * rSq * sinThetaSq * (1.25 - 0.75 * rSq / aSq);
		} else {
			// Outside sphere
			double aCubed = a * a * a;
			return 0.5 * U * aCubed * sinThetaSq / r;
		}
	}

	/**
	 * Calculate velocity components from Hill's vortex (spherical coordinates).
	 *
	 * Radial velocity: U_r = (1/(r²sinθ)) × (dψ/dθ)
	 * Tangential velocity: U_θ = -(1/(rsinθ)) × (dψ/dr)
	 *
	 * @param r Radial distance from vortex center (meters)
	 * @param theta Angle from axis of motion (radians)
	 * @param a Vortex sphere radius (meters)
	 * @param U Velocity of rising vortex (m/s)
	 * @return Array [U_r, U_θ] - radial and tangential velocities (m/s)
	 */
	public static double[] calculateVortexVelocitySpherical(double r, double theta, double a, double U) {
		double cosTheta = Math.cos(theta);
		double sinTheta = Math.sin(theta);

		double U_r, U_theta;

		if (r <= a) {
			// Inside sphere
			double rSq = r * r;
			double aSq = a * a;
			U_r = -1.5 * U * cosTheta * (1.0 - rSq / aSq);
			U_theta = 1.5 * U * sinTheta * (1.0 - 2.0 * rSq / aSq);
		} else {
			// Outside sphere
			double aCubed = a * a * a;
			double rCubed = r * r * r;
			U_r = U * cosTheta * (1.0 - aCubed / rCubed);
			U_theta = -U * sinTheta * (1.0 + 0.5 * aCubed / rCubed);
		}

		return new double[] { U_r, U_theta };
	}

	/**
	 * Calculate velocity field with image vortex (ground boundary condition).
	 *
	 * This method accounts for the ground boundary by placing an imaginary vortex
	 * below ground (mirror image). This creates realistic flow patterns near the surface.
	 *
	 * Reference: nuclear-particle-dynamics-knowledge.md Section 2
	 *
	 * @param R Radial distance from symmetry axis (meters)
	 * @param Z Height above ground (meters)
	 * @param Z_vortex Height of vortex center above ground (meters)
	 * @param a Vortex radius (meters)
	 * @param U Vortex rise velocity (m/s)
	 * @return Array [U_R, U_Z] - radial and vertical velocities (m/s)
	 */
	public static double[] calculateVortexVelocityWithImageCylindrical(double R, double Z,
			double Z_vortex, double a, double U) {

		// Distance from real vortex midplane
		double Z1 = Z - Z_vortex;

		// Distance from imaginary vortex midplane (mirror below ground)
		double Z2 = Z + Z_vortex;

		// Normalized radial distance
		double RSq = R * R;
		double aSq = a * a;
		double normalizedR = RSq / aSq;

		// Distance from vortex centers
		double r1Sq = RSq + Z1 * Z1;
		double r2Sq = RSq + Z2 * Z2;
		double r1 = Math.sqrt(r1Sq);
		double r2 = Math.sqrt(r2Sq);

		double U_R, U_Z;

		// Case 1: Inside both real and imaginary vortices
		if (r1 <= a && r2 <= a) {
			U_Z = U * (0.5 * (5.0 - 6.0 * normalizedR + 3.0 * Z1 * Z1 / aSq)
					- 0.5 * (5.0 - 6.0 * normalizedR + 3.0 * Z2 * Z2 / aSq));
			U_R = U * ((3.0 * R * Z1) / (2.0 * aSq) - (3.0 * R * Z2) / (2.0 * aSq));
		}
		// Case 2: Inside real vortex, outside imaginary vortex
		else if (r1 <= a && r2 > a) {
			double aCubed = a * a * a;
			double r2_5 = Math.pow(r2Sq, 2.5);
			U_Z = U * (0.5 * (5.0 - 6.0 * normalizedR + 3.0 * Z1 * Z1 / aSq)
					- aCubed * (Z2 * Z2 - 0.5 * RSq) / r2_5);
			U_R = U * ((3.0 * R * Z1) / (2.0 * aSq) - 1.5 * aCubed * R * Z2 / r2_5);
		}
		// Case 3: Outside both vortices
		else {
			double aCubed = a * a * a;
			double r1_5 = Math.pow(r1Sq, 2.5);
			double r2_5 = Math.pow(r2Sq, 2.5);
			U_Z = U * (aCubed * (Z1 * Z1 - 0.5 * RSq) / r1_5
					- aCubed * (Z2 * Z2 - 0.5 * RSq) / r2_5);
			U_R = U * (1.5 * R * Z1 * aCubed / r1_5 - 1.5 * R * Z2 * aCubed / r2_5);
		}

		return new double[] { U_R, U_Z };
	}

	// ========================================================================
	// VORTICITY AND CIRCULATION (HILL'S VORTEX THEORY)
	// Reference: nuclear-particle-dynamics-knowledge.md Section 1
	// ========================================================================

	/**
	 * Calculate vorticity magnitude for Hill's spherical vortex.
	 *
	 * From Hill's vortex theory (nuclear-particle-dynamics-knowledge.md line 679):
	 * Inside vortex sphere (r ≤ a):
	 *   ω = ∇ × v = (3/2) × U / a
	 *
	 * Vorticity represents the local rotation rate of the fluid.
	 * Higher vorticity → stronger toroidal circulation.
	 *
	 * Used to determine when toroidal circulation develops:
	 * - Low vorticity (ω × a / U << 1): Advection dominates, no rotation
	 * - High vorticity (ω × a / U > 1): Rotation dominates, toroidal structure forms
	 *
	 * Physical basis:
	 * - Hill's spherical vortex is an exact solution to Navier-Stokes equations
	 * - Represents a toroidal vortex ring moving through inviscid fluid
	 * - Vorticity concentrated inside sphere of radius 'a'
	 *
	 * @param riseVelocity Vortex rise velocity U (m/s)
	 * @param vortexRadius Vortex sphere radius a (m)
	 * @return Vorticity magnitude ω (1/s)
	 */
	public static double calculateVorticityMagnitude(double riseVelocity, double vortexRadius) {
		if (vortexRadius < 0.001) return 0.0; // Avoid division by zero
		return 1.5 * riseVelocity / vortexRadius;
	}

	/**
	 * Calculate circulation strength for toroidal vortex ring.
	 *
	 * From Hill's vortex theory (nuclear-particle-dynamics-knowledge.md line 679):
	 *   Γ = ∮ v·dl = (4π/3) × a² × U
	 *
	 * Circulation is a measure of the total "strength" of the vortex.
	 * It determines:
	 * - Particle entrainment rate (higher Γ → more debris lifted)
	 * - Mushroom cloud width (larger Γ → wider cap)
	 * - Longevity of toroidal structure (stronger circulation persists longer)
	 *
	 * Scaling with yield:
	 *   Γ ∝ a² × U ∝ W^0.8 × W^0.25 = W^1.05
	 * Larger yields have proportionally stronger circulation.
	 *
	 * Physical interpretation:
	 * - Circulation is conserved in inviscid flow (Kelvin's theorem)
	 * - In reality, viscosity slowly dissipates circulation over time
	 * - For nuclear fireballs: Re >> 10^9, so viscosity negligible during rise phase
	 *
	 * @param riseVelocity Vortex rise velocity U (m/s)
	 * @param vortexRadius Vortex sphere radius a (m)
	 * @return Circulation Γ (m²/s)
	 */
	public static double calculateCirculation(double riseVelocity, double vortexRadius) {
		// Γ = (4π/3) × a² × U
		return (4.0 * Math.PI / 3.0) * vortexRadius * vortexRadius * riseVelocity;
	}

	// ========================================================================
	// PARTICLE DYNAMICS (DELFIC CRM)
	// Reference: nuclear-particle-dynamics-knowledge.md Section 3
	// Source: ADA280688-55-129.pdf Chapter III, Equations 43-46
	// ========================================================================

	/**
	 * Calculate drag coefficient for a particle.
	 *
	 * Drag coefficient varies with Reynolds number:
	 * - Re < 1: Stokes flow (C_D = 24/Re)
	 * - 1 < Re < 1000: Transition regime
	 * - Re > 1000: Turbulent (C_D ≈ 0.44)
	 *
	 * @param diameter Particle diameter (meters)
	 * @param relativeVelocity Velocity relative to air (m/s)
	 * @param airDensity Air density (kg/m³)
	 * @return Drag coefficient (dimensionless)
	 */
	public static double calculateDragCoefficient(double diameter, double relativeVelocity, double airDensity) {
		// Dynamic viscosity of air at 15°C
		double mu = 1.81e-5; // kg/(m·s)

		// Reynolds number: Re = ρ × V × d / μ
		double Re = (airDensity * relativeVelocity * diameter) / mu;

		// Drag coefficient based on Reynolds number
		if (Re < 1.0) {
			// Stokes flow
			return 24.0 / Math.max(Re, 0.01); // Avoid division by zero
		} else if (Re < 1000.0) {
			// Transition regime (empirical fit)
			return 24.0 / Re * (1.0 + 0.15 * Math.pow(Re, 0.687));
		} else {
			// Turbulent flow
			return 0.44;
		}
	}

	/**
	 * Calculate particle acceleration due to gravity and drag.
	 *
	 * Force balance equation (from knowledge base Eq. 43-46):
	 *
	 * Vertical acceleration:
	 *   V̇_Z = -g - (3ρ_a C_D)/(4ρ_m d)(V_Z - U_Z)[(U_Z - V_Z)² + (U_R - V_R)²]^(1/2)
	 *
	 * Radial acceleration:
	 *   V̇_R = -(3ρ_a C_D)/(4ρ_m d)(V_R - U_R)[(U_Z - V_Z)² + (U_R - V_R)²]^(1/2)
	 *
	 * Where:
	 *   ρ_m = particle (mass) density (kg/m³) - typically 2600 kg/m³ (soil)
	 *   ρ_a = air (cloud) density (kg/m³)
	 *   C_D = coefficient of drag
	 *   d = particle diameter (m)
	 *   V_Z, V_R = particle velocities (m/s)
	 *   U_Z, U_R = flow field velocities (m/s)
	 *
	 * @param particleVelZ Particle vertical velocity (m/s, positive = up)
	 * @param particleVelR Particle radial velocity (m/s)
	 * @param flowVelZ Flow field vertical velocity (m/s)
	 * @param flowVelR Flow field radial velocity (m/s)
	 * @param particleDiameter Particle diameter (meters)
	 * @param particleDensity Particle material density (kg/m³)
	 * @param airDensity Air density (kg/m³)
	 * @return Array [accel_Z, accel_R] - vertical and radial accelerations (m/s²)
	 */
	public static double[] calculateParticleAcceleration(
			double particleVelZ, double particleVelR,
			double flowVelZ, double flowVelR,
			double particleDiameter, double particleDensity, double airDensity) {

		// Relative velocity components
		double deltaVZ = particleVelZ - flowVelZ;
		double deltaVR = particleVelR - flowVelR;

		// Relative velocity magnitude
		double relativeSpeed = Math.sqrt(deltaVZ * deltaVZ + deltaVR * deltaVR);

		// Drag coefficient
		double C_D = calculateDragCoefficient(particleDiameter, relativeSpeed, airDensity);

		// Drag factor: (3ρ_a C_D)/(4ρ_m d)
		double dragFactor = (3.0 * airDensity * C_D) / (4.0 * particleDensity * particleDiameter);

		// Vertical acceleration (includes gravity)
		double accelZ = -GRAVITY - dragFactor * deltaVZ * relativeSpeed;

		// Radial acceleration (no gravity component)
		double accelR = -dragFactor * deltaVR * relativeSpeed;

		return new double[] { accelZ, accelR };
	}

	/**
	 * Calculate terminal velocity for a falling particle.
	 *
	 * Terminal velocity is reached when drag force equals gravitational force:
	 *   F_drag = F_gravity
	 *   (1/2) ρ_a C_D A v² = m g
	 *   v_terminal = sqrt(2 m g / (ρ_a C_D A))
	 *
	 * For a sphere: A = π d² / 4, m = ρ_m × (4/3) π (d/2)³
	 *
	 * Simplified: v_t = sqrt((4 g d ρ_m) / (3 C_D ρ_a))
	 *
	 * @param particleDiameter Particle diameter (meters)
	 * @param particleDensity Particle material density (kg/m³)
	 * @param airDensity Air density (kg/m³)
	 * @return Terminal velocity (m/s, positive = downward)
	 */
	public static double calculateTerminalVelocity(double particleDiameter,
			double particleDensity, double airDensity) {

		// Initial guess for C_D (will iterate)
		double C_D = 0.44; // Turbulent regime initial guess
		double v_terminal = 0.0;

		// Iterative calculation (terminal velocity depends on C_D which depends on velocity)
		for (int i = 0; i < 5; i++) {
			// Calculate terminal velocity with current C_D
			v_terminal = Math.sqrt((4.0 * GRAVITY * particleDiameter * particleDensity)
					/ (3.0 * C_D * airDensity));

			// Update C_D based on new velocity
			C_D = calculateDragCoefficient(particleDiameter, v_terminal, airDensity);
		}

		return v_terminal;
	}

	/**
	 * Check if particle should be spawned based on physics phase.
	 *
	 * CRITICAL: Particles should ONLY spawn during Phase 2+ (after fireball formation).
	 *
	 * Phase 0-1: Fireball formation - NO PARTICLES (pure light sphere)
	 * Phase 2+: Buoyant rise and mushroom cloud - PARTICLES ACTIVE
	 *
	 * Reference: nuclear-particle-dynamics-knowledge.md Section 9.1
	 *
	 * @param explosionPhase Current physics phase (0-4)
	 * @param fireballRadius Current fireball radius (meters)
	 * @return True if particles should be spawned/active
	 */
	public static boolean shouldSpawnParticles(int explosionPhase, double fireballRadius) {
		// MODIFIED: Spawn particles from Phase 1 onwards to create volumetric fireball
		// Phase 1+: Fireball composed of multiple particles (like EntityNukeTorex with Cloudlets)
		// Phase 0: Initial flash only (no particles)
		return explosionPhase >= 1 && fireballRadius > 50.0;
	}

	// ========================================================================
	// DIMENSIONLESS NUMBERS (PHYSICS-BASED DEFORMATION CRITERIA)
	// ========================================================================

	/**
	 * Calculate Froude number (inertia vs gravity ratio).
	 *
	 * Fr = v² / (g × L)
	 *
	 * Physical interpretation:
	 * - Fr >> 1: Inertial forces dominate (sphere phase, expansion-driven)
	 * - Fr ≈ 1: Transition regime (pinching begins, buoyancy competing)
	 * - Fr << 1: Gravitational/buoyancy forces dominate (mushroom phase)
	 *
	 * Used to determine when fireball deformation begins:
	 * - Sphere → Pinching transition: Fr drops below 5.0
	 * - Full buoyancy dominance: Fr < 1.0
	 *
	 * Reference: Fluid dynamics textbooks, dimensional analysis
	 *
	 * @param velocity Characteristic velocity (m/s) - typically rise velocity
	 * @param length Characteristic length (m) - typically fireball radius
	 * @return Froude number (dimensionless)
	 */
	public static double calculateFroudeNumber(double velocity, double length) {
		if (length < 0.001) return 0.0; // Avoid division by zero
		return (velocity * velocity) / (GRAVITY * length);
	}

	/**
	 * Calculate Richardson number (buoyancy vs shear ratio).
	 *
	 * Ri = (g/ρ) × (dρ/dz) / (du/dz)²
	 *
	 * Physical interpretation:
	 * - Ri > 1.0: Stable stratification (buoyancy suppresses turbulence)
	 * - Ri < 0.25: Shear-dominated flow (strong turbulent mixing)
	 * - Ri ≈ 1.0: Critical value (transition between regimes)
	 *
	 * Used to determine cloud stabilization:
	 * - At tropopause (10-15 km): Ri >> 1 (temperature inversion, stable layer)
	 * - Mushroom cloud stops rising when encountering Ri > 1 layer
	 *
	 * Reference: Atmospheric dynamics, Turner (1973)
	 *
	 * @param altitude Height above ground (m)
	 * @return Richardson number (dimensionless)
	 */
	public static double calculateRichardsonNumber(double altitude) {
		// Atmospheric density gradient (barometric formula)
		double H_scale = 8500.0; // Scale height (m)
		double rho = getAirDensity(altitude);
		double drho_dz = -rho / H_scale; // Exponential decay: dρ/dz = -ρ/H

		// Temperature gradient (standard atmosphere)
		// Troposphere (0-11 km): dT/dz = -6.5 K/km (lapse rate)
		// Stratosphere (>11 km): dT/dz = +1.0 K/km (inversion)
		double dT_dz;
		if (altitude < 11000.0) {
			dT_dz = -0.0065; // K/m (troposphere)
		} else if (altitude < 20000.0) {
			dT_dz = 0.0; // K/m (tropopause, isothermal)
		} else {
			dT_dz = 0.001; // K/m (stratosphere, inversion)
		}

		// Density gradient from ideal gas law: dρ/dz = (ρ/T) × dT/dz - ρ × g / (R × T)
		// For standard atmosphere, barometric formula dominates

		// Velocity gradient (wind shear) - typical atmospheric values
		// Lower atmosphere: ~1 m/s per 100m = 0.01 s⁻¹
		// Higher atmosphere: ~5 m/s per 100m = 0.05 s⁻¹
		double du_dz = (altitude < 5000.0) ? 0.01 : 0.05; // s⁻¹

		// Richardson number
		// Ri = (g/ρ) × |dρ/dz| / (du/dz)²
		double Ri = (GRAVITY / rho) * Math.abs(drho_dz) / (du_dz * du_dz);

		// Add contribution from temperature stratification
		// In stratosphere (dT/dz > 0), stability is enhanced
		if (dT_dz > 0) {
			Ri *= (1.0 + 10.0 * dT_dz); // Strong stability multiplier
		}

		return Ri;
	}

	/**
	 * Calculate Reynolds number for bulk fireball flow.
	 *
	 * Re = ρ × v × L / μ
	 *
	 * Physical interpretation:
	 * - Re < 2300: Laminar flow (smooth, ordered)
	 * - Re > 4000: Turbulent flow (chaotic, mixing)
	 * - Re = 2300-4000: Transition regime
	 *
	 * For nuclear fireballs:
	 * - 1 kt: Re ≈ 10⁹ (highly turbulent)
	 * - 1 Mt: Re ≈ 10¹⁰ (extreme turbulence)
	 * - All nuclear-scale flows are fully turbulent
	 *
	 * Used to determine turbulence intensity for Rayleigh-Taylor instabilities.
	 *
	 * Reference: Fluid mechanics textbooks, White (2011)
	 *
	 * @param velocity Characteristic velocity (m/s)
	 * @param length Characteristic length (m)
	 * @param airDensity Air density (kg/m³)
	 * @return Reynolds number (dimensionless)
	 */
	public static double calculateReynoldsNumber(double velocity, double length, double airDensity) {
		double mu = 1.81e-5; // Dynamic viscosity of air at 15°C (kg/(m·s))
		return (airDensity * velocity * length) / mu;
	}

	/**
	 * Calculate Rossby number (advection vs rotation ratio).
	 *
	 * Ro = U / (ω × L)
	 *
	 * Physical interpretation:
	 * - Ro >> 1: Advection dominates (straight-line flow, no rotation)
	 * - Ro ≈ 1: Transition (rotation effects become important)
	 * - Ro << 1: Rotation dominates (strong vortex, toroidal circulation)
	 *
	 * Used to determine when toroidal circulation develops:
	 * - Sphere phase: Ro >> 1 (radial expansion only)
	 * - Stem formation: Ro < 1 (Hill's spherical vortex circulation begins)
	 *
	 * Reference: Geophysical fluid dynamics, Vallis (2017)
	 *
	 * @param velocity Flow velocity (m/s) - typically rise velocity
	 * @param vorticity Vorticity magnitude (1/s) - from calculateVorticityMagnitude()
	 * @param length Characteristic length (m) - typically vortex radius
	 * @return Rossby number (dimensionless)
	 */
	public static double calculateRossbyNumber(double velocity, double vorticity, double length) {
		double denominator = vorticity * length;
		if (Math.abs(denominator) < 1e-10) return 1e10; // Avoid division by zero, return large number
		return velocity / denominator;
	}

	// ========================================================================
	// BLAST WAVE PHYSICS (from "Effects of Nuclear Weapons 1977" Ch. 3-4)
	// ========================================================================

	/**
	 * Calculate blast wave overpressure at given distance from ground zero.
	 *
	 * Based on PDF §3.73 scaling laws and exponential decay model:
	 *   p(r) = p_peak × exp(-r / decay_length)
	 *
	 * Physical interpretation:
	 * - p_peak: Peak overpressure at fireball surface (~50-100 psi)
	 * - decay_length: Characteristic decay distance, scales as W^(1/3)
	 * - Overpressure: Pressure increase above ambient (14.7 psi)
	 *
	 * Reference: PDF Table 3.73a, Figure 3.73b (overpressure vs scaled distance)
	 *
	 * @param distanceFromFireball Distance from current fireball edge (meters)
	 * @param yieldKilotons Weapon yield (kilotons)
	 * @param fireballRadius Current fireball radius (meters)
	 * @return Overpressure in psi (pounds per square inch)
	 */
	public static double calculateBlastOverpressure(double distanceFromFireball,
	                                                 double yieldKilotons,
	                                                 double fireballRadius) {
		// Peak overpressure at fireball surface (PDF §3.73)
		// Scales as W^(-1/3) - larger yields have lower surface pressure
		// Typical values: 1kt=100psi, 1MT=50psi at fireball edge
		double p_peak = 100.0 * Math.pow(yieldKilotons / 1000.0, -0.333);

		// Decay length scale (PDF §3.76)
		// Distance over which pressure drops by factor of e (2.718)
		// Scales with cube root of yield (blast wave similarity scaling)
		double decay_length = Math.pow(yieldKilotons, 0.333) * 50.0; // meters

		// Total distance from ground zero
		double totalDistance = fireballRadius + distanceFromFireball;

		// Exponential decay (PDF §3.57 - shock wave attenuation)
		double overpressure = p_peak * Math.exp(-distanceFromFireball / decay_length);

		return Math.max(overpressure, 0.0);
	}

	/**
	 * Calculate dynamic (wind) pressure at given overpressure.
	 *
	 * Dynamic pressure represents the wind force behind the shock front.
	 * Critical for structural damage (drag loading on buildings).
	 *
	 * PDF Table 3.11 provides empirical relationship:
	 *   q ≈ 0.5 × p for moderate overpressures
	 *
	 * Exact Rankine-Hugoniot formula (PDF §3.13):
	 *   q = 2.5 × p² / (7×p + p₀)
	 *
	 * where p₀ = 14.7 psi (ambient pressure at sea level)
	 *
	 * Physical interpretation:
	 * - At p=10psi: q≈5psi (strong winds ~300 mph)
	 * - At p=5psi: q≈2psi (moderate winds ~150 mph)
	 *
	 * @param overpressure Overpressure (psi)
	 * @return Dynamic pressure (psi)
	 */
	public static double calculateDynamicPressure(double overpressure) {
		double p0 = 14.7; // Ambient atmospheric pressure (psi) at sea level

		// Simplified Rankine-Hugoniot relation for normal shock (PDF §3.13)
		// Derived from conservation of mass, momentum, energy across shock
		return 2.5 * overpressure * overpressure / (7.0 * overpressure + p0);
	}

	/**
	 * Calculate distance at which overpressure reaches 5 psi.
	 *
	 * 5 psi is the threshold for severe structural damage:
	 * - Residential buildings: Total destruction (PDF Table 5.31)
	 * - Brick buildings: Severe damage
	 * - Humans: 50% eardrum rupture, severe injuries from debris
	 *
	 * Scaling law from PDF Figure 3.73a (empirical fit to test data):
	 *   R(5psi) = 0.28 × W^0.33 km for optimum airburst
	 *
	 * Examples (from PDF data):
	 * - 1 kt: 0.28 km (280 m)
	 * - 15 kt: 0.69 km (Hiroshima scale)
	 * - 100 kt: 1.24 km
	 * - 1 MT: 2.8 km
	 * - 50 MT: 7.1 km (Tsar Bomba)
	 *
	 * @param yieldKilotons Weapon yield (kilotons)
	 * @return Distance to 5 psi overpressure (meters)
	 */
	public static double calculate5PsiRadius(double yieldKilotons) {
		// Empirical scaling law from PDF Figure 3.73a
		// R ∝ W^(1/3) is fundamental blast wave similarity scaling
		return 280.0 * Math.pow(yieldKilotons, 0.33); // meters
	}

	/**
	 * Calculate blast wave propagation velocity at given overpressure.
	 *
	 * Blast wave initially travels faster than sound (supersonic shock).
	 * As pressure decays, velocity approaches sound speed.
	 *
	 * PDF §3.55 Rankine-Hugoniot shock velocity formula:
	 *   U = c₀ × sqrt(1 + ((γ+1)/(2γ)) × (p/p₀))
	 *
	 * where:
	 *   c₀ = 340 m/s (sound speed in air at 15°C)
	 *   γ = 1.4 (specific heat ratio for air)
	 *   p = overpressure
	 *   p₀ = 14.7 psi (ambient pressure)
	 *
	 * Physical interpretation:
	 * - At p=100psi: U≈600 m/s (Mach 1.8, strong shock)
	 * - At p=10psi: U≈450 m/s (Mach 1.3)
	 * - At p=1psi: U≈360 m/s (Mach 1.06, weak shock)
	 * - At p→0: U→340 m/s (sound wave)
	 *
	 * Reference: PDF §3.55-3.57 (shock wave dynamics)
	 *
	 * @param overpressure Current overpressure (psi)
	 * @return Blast wave velocity (m/s)
	 */
	public static double calculateBlastVelocity(double overpressure) {
		double soundSpeed = 340.0; // m/s at sea level, 15°C
		double p0 = 14.7; // psi (ambient pressure)
		double gamma = 1.4; // Specific heat ratio for diatomic gas (air)

		// Pressure ratio (dimensionless)
		double pressureRatio = overpressure / p0;

		// Mach number enhancement factor from Rankine-Hugoniot relations
		double velocityFactor = 1.0 + ((gamma + 1.0) / (2.0 * gamma)) * pressureRatio;

		// Ensure velocity never drops below sound speed
		return soundSpeed * Math.sqrt(Math.max(velocityFactor, 1.0));
	}

	/**
	 * DYNAMIC STEM RADIUS CALCULATION (PHYSICS-BASED)
	 *
	 * Calculate stem radius ratio at a given height, based on real-time physics parameters.
	 * This replaces static yield-only taper with fully dynamic physics simulation.
	 *
	 * PHYSICAL DEPENDENCIES (from Effects of Nuclear Weapons 1977):
	 *
	 * 1. **Vertical Velocity** (Section 2.129 - Buoyant Rise)
	 *    - High velocity → stem is "pulled thin" by upward flow
	 *    - Low velocity → stem remains thick
	 *    - Formula contribution: radius ∝ 1 / sqrt(velocity)
	 *
	 * 2. **Cloud Height / Fireball Radius** (Section 2.17 - Aspect Ratio)
	 *    - High clouds → long thin stems
	 *    - Low clouds → short thick stems
	 *    - Formula contribution: radius ∝ fireballRadius / cloudHeight
	 *
	 * 3. **Temperature** (Section 2.07 - Toroidal Circulation)
	 *    - High temperature → strong convection → thin stem
	 *    - Low temperature → weak convection → thick stem
	 *    - Formula contribution: radius ∝ 1 / sqrt(temperature)
	 *
	 * 4. **Deformation Progress** (Temporal Evolution)
	 *    - Early stage (0.5-0.7) → thick stem (just forming)
	 *    - Late stage (0.7-1.0) → thin stem (fully developed)
	 *    - Formula contribution: radius ∝ (1 - progress)
	 *
	 * 5. **Yield** (Section 2.17 - Static Scaling)
	 *    - Low yield (<20kt): stem = 0.5 × cloud radius
	 *    - High yield (megaton): stem = 0.1 × cloud radius
	 *    - Formula contribution: baseline scaling factor
	 *
	 * 6. **Height Along Stem** (Section 2.09 - Debris Entrainment)
	 *    - Bottom (0.0): Wide (debris accumulation from afterwinds)
	 *    - Middle (0.5): Narrowest (maximum stretching from toroidal flow)
	 *    - Top (1.0): Slightly wider (connection to mushroom cap)
	 *    - Non-linear profile: sin-based modulation
	 *
	 * RETURN VALUE:
	 * Radius ratio at given height (0.0 to 1.0)
	 * - 1.0 = full base radius
	 * - 0.3 = 30% of base radius (very thin stem)
	 * - Multiply this by stemBaseRadius to get actual radius in meters
	 *
	 * @param heightFraction Normalized height along stem (0.0 = bottom, 1.0 = top)
	 * @param deformationProgress Continuous deformation (0.0 = sphere, 1.0 = full mushroom)
	 * @param verticalVelocity Cloud rise velocity (m/s)
	 * @param buoyancyForce Buoyancy force (N) - currently unused but available for future
	 * @param cloudHeight Current cloud top height (m)
	 * @param fireballRadius Current fireball radius (m)
	 * @param temperature Current temperature (K)
	 * @param yieldKt Weapon yield (kilotons)
	 * @return Radius ratio at this height (0.0 to 1.0, typically 0.3 to 1.0)
	 */
	public static double calculateStemRadiusRatio(
			double heightFraction,
			double deformationProgress,
			double verticalVelocity,
			double buoyancyForce,
			double cloudHeight,
			double fireballRadius,
			double temperature,
			double yieldKt) {

		// === 1. BASELINE TAPER (YIELD-DEPENDENT, from PDF Section 2.17) ===
		// This provides the static scaling relationship
		double baselineTaper;
		if (yieldKt < 20.0) {
			// Low yield: minimal taper (stem is thick relative to cloud)
			baselineTaper = 0.8; // Top is 80% of base width
		} else if (yieldKt < 1000.0) {
			// Medium yield: moderate taper
			double t = (yieldKt - 20.0) / 980.0; // 0.0 to 1.0
			baselineTaper = 0.8 - 0.3 * t; // 0.8 → 0.5
		} else {
			// Megaton range: strong taper (thin stem, wide cap)
			double t = Math.min(1.0, (yieldKt - 1000.0) / 49000.0); // 0.0 to 1.0
			baselineTaper = 0.5 - 0.2 * t; // 0.5 → 0.3
		}

		// === 2. VELOCITY-BASED STRETCHING (PDF Section 2.129) ===
		// Fast rising clouds pull the stem thinner (like taffy being stretched)
		// Typical vertical velocities: 10-100 m/s for nuclear clouds
		double velocityFactor = 1.0;
		if (verticalVelocity > 1.0) { // Avoid division by zero
			// Inverse square root relationship: faster rise → thinner stem
			// Normalized to 50 m/s reference velocity
			double velocityRatio = verticalVelocity / 50.0;
			velocityFactor = 1.0 / Math.sqrt(Math.max(1.0, velocityRatio));
			// Clamp to reasonable range [0.5, 1.0]
			velocityFactor = Math.max(0.5, Math.min(1.0, velocityFactor));
		}

		// === 3. ASPECT RATIO (Cloud Height vs Fireball Radius) ===
		// Tall thin clouds have thin stems, short wide clouds have thick stems
		double aspectRatio = 1.0;
		if (cloudHeight > fireballRadius && fireballRadius > 1.0) {
			// Calculate height-to-width aspect ratio
			double ratio = cloudHeight / fireballRadius;
			// Normalize: ratio = 1 (equal) → factor = 1.0
			//            ratio = 10 (tall) → factor = 0.6 (thinner stem)
			aspectRatio = 1.0 / Math.sqrt(Math.max(1.0, ratio / 2.0));
			aspectRatio = Math.max(0.6, Math.min(1.0, aspectRatio));
		}

		// === 4. TEMPERATURE-BASED CONVECTION (PDF Section 2.07) ===
		// Higher temperature → stronger toroidal circulation → thinner stem
		double tempFactor = 1.0;
		if (temperature > 1000.0) { // Only apply when hot enough for convection
			// Normalize to 3000K reference temperature
			double tempRatio = temperature / 3000.0;
			tempFactor = 1.0 / Math.sqrt(Math.max(1.0, tempRatio));
			tempFactor = Math.max(0.7, Math.min(1.0, tempFactor));
		}

		// === 5. DEFORMATION PROGRESS (Temporal Evolution) ===
		// Early stages: stem just forming, thick and irregular
		// Late stages: stem fully developed, thin and stretched
		double progressFactor = 1.0;
		if (deformationProgress > 0.5) { // Stem only exists after 0.5
			double stemProgress = (deformationProgress - 0.5) / 0.5; // 0.0 to 1.0
			// Early: thick (factor = 1.0), Late: thin (factor = 0.8)
			progressFactor = 1.0 - 0.2 * stemProgress;
		}

		// === 6. HEIGHT-BASED PROFILE (PDF Section 2.09 & Figure 2.07b) ===
		// Non-uniform radius along stem height (realistic irregular shape)
		//
		// Physical reasoning:
		// - Bottom (h=0.0): Wide due to debris entrainment from afterwinds
		// - Middle (h=0.5): Narrowest due to stretching from toroidal circulation
		// - Top (h=1.0): Slightly wider to connect smoothly to mushroom cap
		//
		// Use sin-based modulation for smooth, physically plausible profile
		double heightProfile;
		if (heightFraction < 0.3) {
			// Lower 30%: Debris accumulation zone (wider than baseline)
			// Smooth transition from wide base (1.2×) to baseline (1.0×)
			double t = heightFraction / 0.3; // 0.0 to 1.0
			heightProfile = 1.2 - 0.2 * t; // 1.2 → 1.0
		} else if (heightFraction < 0.7) {
			// Middle 40%: Maximum stretching zone (thinner than baseline)
			// Use sin modulation for smooth variation
			double t = (heightFraction - 0.3) / 0.4; // 0.0 to 1.0
			double sineFactor = Math.sin(t * Math.PI); // 0 → 1 → 0
			heightProfile = 1.0 - 0.3 * sineFactor; // 1.0 → 0.7 → 1.0
		} else {
			// Upper 30%: Cap connection zone (widening toward top)
			// Smooth transition from baseline (1.0×) to cap connection (1.1×)
			double t = (heightFraction - 0.7) / 0.3; // 0.0 to 1.0
			heightProfile = 1.0 + 0.1 * t; // 1.0 → 1.1
		}

		// === 7. COMBINE ALL FACTORS ===
		// Multiplicative combination (all effects compound)
		double finalRatio = baselineTaper * velocityFactor * aspectRatio * tempFactor * progressFactor * heightProfile;

		// Apply overall height-based taper (0.0 = base, 1.0 = top)
		// This ensures top is always thinner than base
		finalRatio *= (1.0 - heightFraction * (1.0 - baselineTaper));

		// === 8. SAFETY CLAMPS ===
		// Ensure radius stays within physically reasonable bounds
		// Min: 0.2 (stem can't be thinner than 20% of base, would break up)
		// Max: 1.2 (debris zone can be up to 120% of base width)
		finalRatio = Math.max(0.2, Math.min(1.2, finalRatio));

		return finalRatio;
	}

	// =========================================================================
	// === FLASH BLINDNESS & SCREEN SHAKE PHYSICS ================================
	// =========================================================================

	/**
	 * Calculates how long the player's vision is whited out (flash blindness).
	 *
	 * Based on thermal fluence exceeding the flash blindness threshold.
	 * Reference: "Effects of Nuclear Weapons" §7.22-7.38, §2.55
	 *
	 * Physical model:
	 *   - 35% of weapon yield is released as thermal radiation
	 *   - Fluence falls off as 1/4πR² from the fireball surface
	 *   - Flash blindness threshold (daytime pupil): ~3 cal/cm² = 125,600 J/m²
	 *   - Recovery duration scales as (H / H_threshold)^0.3 × 5 seconds
	 *
	 * @param yieldKt         Weapon yield in kilotons
	 * @param distanceMeters  Distance from explosion center to player in meters
	 * @return Flash white-out duration in milliseconds (0 if below threshold)
	 */
	public static long calculateFlashDurationMs(double yieldKt, double distanceMeters) {
		if (distanceMeters < 1.0) distanceMeters = 1.0;

		// Total thermal energy: 35% of yield
		// 1 kt TNT = 4.184e12 J
		double E_thermal_J = 0.35 * JOULES_PER_KILOTON * yieldKt;

		// Thermal fluence at player position (J/m²)
		// Simplified: uniform spherical emission, no atmospheric attenuation
		double fluence = E_thermal_J / (4.0 * Math.PI * distanceMeters * distanceMeters);

		// Flash blindness threshold: 3 cal/cm² = 125,600 J/m² (daytime conditions)
		final double H_THRESHOLD = 125600.0;

		if (fluence < H_THRESHOLD) {
			return 0L; // Below threshold — brief glare only, no white-out
		}

		// Recovery duration: empirically 5s at threshold, scales as (H/H_threshold)^0.3
		// Capped at 30 seconds (no permanent blindness per design spec)
		double flashSeconds = 5.0 * Math.pow(fluence / H_THRESHOLD, 0.3);
		flashSeconds = Math.min(flashSeconds, 30.0);

		return (long)(flashSeconds * 1000.0);
	}

	/**
	 * Calculates screen shake duration when the blast shockwave arrives at the player.
	 *
	 * Based on overpressure at the player's distance using Taylor-Sedov scaling.
	 * The shockwave causes physical vibration proportional to the dynamic pressure.
	 *
	 * @param yieldKt         Weapon yield in kilotons
	 * @param distanceMeters  Distance from explosion to player in meters
	 * @return Shake duration in milliseconds (200-8000ms range)
	 */
	public static long calculateShakeDurationMs(double yieldKt, double distanceMeters) {
		if (distanceMeters < 1.0) distanceMeters = 1.0;

		// Scaled distance: distance normalized by yield^(1/3) (Hopkinson-Cranz scaling)
		// At scaled distance 100 m/kt^(1/3): moderate damage zone (~5 psi)
		double scaledDistance = distanceMeters / Math.pow(yieldKt, 1.0 / 3.0);

		// Shake duration inversely proportional to scaled distance
		// 3000ms at scaled distance 100, falls off with distance
		double shakeDurationMs = 3000.0 * (100.0 / Math.max(scaledDistance, 10.0));

		// Clamp to reasonable gameplay range
		return (long)(Math.min(Math.max(shakeDurationMs, 200.0), 8000.0));
	}

	/**
	 * Calculates shake amplitude (0.0-1.0) based on overpressure at player distance.
	 *
	 * Amplitude = 1.0 at scaled distance 50 m/kt^(1/3), falls off as inverse square.
	 *
	 * @param yieldKt         Weapon yield in kilotons
	 * @param distanceMeters  Distance from explosion to player in meters
	 * @return Shake amplitude from 0.0 (imperceptible) to 1.0 (maximum)
	 */
	public static double calculateShakeAmplitude(double yieldKt, double distanceMeters) {
		if (distanceMeters < 1.0) distanceMeters = 1.0;

		double scaledDistance = distanceMeters / Math.pow(yieldKt, 1.0 / 3.0);

		// Inverse-square fall-off: amplitude = 1.0 at scaledDistance=50
		double amplitude = Math.pow(50.0 / Math.max(scaledDistance, 1.0), 2.0);

		return Math.min(1.0, amplitude);
	}

	// =========================================================================
	// === THERMAL TEMPERATURE PHYSICS ==========================================
	// =========================================================================

	/**
	 * Calculates fireball surface temperature at a given time after detonation.
	 * Based on PDF §2.123 two-pulse thermal radiation model.
	 *
	 * Phase 1 (X-ray → thermal minimum):   TEMP_INITIAL → TEMP_MINIMUM (power-law decay)
	 * Phase 2 (second thermal pulse rise):  TEMP_MINIMUM → TEMP_MAXIMUM (linear rise)
	 * Phase 3 (cooling):                    TEMP_MAXIMUM → ambient       (exponential decay)
	 *
	 * Timing scales as W^0.4 per Glasstone §2.123:
	 *   t_min  = 0.011 × (W/20)^0.4 seconds
	 *   t_peak = 1.5   × (W/20)^0.4 seconds
	 *
	 * @param timeSeconds   Time after detonation in seconds
	 * @param yieldKilotons Weapon yield in kilotons
	 * @return Fireball surface temperature in Kelvin
	 */
	public static double calculateFireballSurfaceTemperature(double timeSeconds, double yieldKilotons) {
		if (timeSeconds < 0) return TEMP_INITIAL;

		double timeScale = Math.pow(Math.max(yieldKilotons, 0.001) / 20.0, 0.4);
		double t_min     = 0.011 * timeScale; // thermal-minimum time (seconds)
		double t_peak    = 1.5   * timeScale; // second-pulse peak time (seconds)

		if (timeSeconds <= t_min) {
			// Phase 1: X-ray dissipation → power-law drop to TEMP_MINIMUM
			double t_norm = (t_min > 0) ? timeSeconds / t_min : 1.0;
			return TEMP_INITIAL * Math.pow(TEMP_MINIMUM / TEMP_INITIAL, t_norm);

		} else if (timeSeconds <= t_peak) {
			// Phase 2: linear rise of second thermal pulse
			double t_norm = (timeSeconds - t_min) / (t_peak - t_min);
			return TEMP_MINIMUM + (TEMP_MAXIMUM - TEMP_MINIMUM) * t_norm;

		} else {
			// Phase 3: exponential cooling, half-life ≈ 100 × timeScale seconds
			double t_cool   = timeSeconds - t_peak;
			double halfLife = 100.0 * timeScale;
			return TEMP_MINIMUM + (TEMP_MAXIMUM - TEMP_MINIMUM)
					* Math.exp(-t_cool / halfLife * Math.log(2.0));
		}
	}

	/**
	 * Calculates effective temperature in °C at a given distance from the fireball center.
	 *
	 * Uses inverse-square law: T_eff = T_surface × (R_fireball / distance)²
	 * Inside the fireball: returns full surface temperature (converted to °C).
	 * Result is in Celsius to match ThermalDamageSystem thresholds (100°C / 1500°C).
	 *
	 * @param timeSeconds    Time after detonation in seconds
	 * @param yieldKilotons  Weapon yield in kilotons
	 * @param distanceMeters Distance from explosion center in meters
	 * @return Effective temperature in °C (minimum 0°C)
	 */
	public static double calculateTemperatureAtDistanceCelsius(double timeSeconds, double yieldKilotons, double distanceMeters) {
		double R_fireball = calculateFireballRadius(timeSeconds, yieldKilotons, AIR_DENSITY);
		double T_kelvin   = calculateFireballSurfaceTemperature(timeSeconds, yieldKilotons);
		double T_eff_kelvin;

		if (distanceMeters <= R_fireball) {
			T_eff_kelvin = T_kelvin;
		} else {
			double ratio = R_fireball / distanceMeters;
			T_eff_kelvin = Math.max(0.0, T_kelvin * ratio * ratio);
		}

		return Math.max(0.0, T_eff_kelvin - 273.15); // Kelvin → Celsius
	}
}
