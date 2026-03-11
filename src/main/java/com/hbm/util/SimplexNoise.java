package com.hbm.util;

/**
 * SIMPLEX NOISE GENERATOR (3D)
 *
 * Efficient gradient noise implementation for turbulence simulation.
 * Based on Ken Perlin's Simplex Noise algorithm (2001).
 *
 * ADVANTAGES OVER PERLIN NOISE:
 * - Lower computational complexity: O(n²) vs O(2ⁿ)
 * - No directional artifacts
 * - Higher apparent quality
 * - Better defined feature sizes
 *
 * USAGE FOR NUCLEAR EXPLOSION TURBULENCE:
 * - Single octave: Basic chaotic motion (Phase 2)
 * - Multi-octave: Complex turbulent flow (Phase 3-5)
 * - Wavelength: 50m (large eddies), 10m (medium), 2m (small)
 *
 * @author Based on Stefan Gustavson's implementation (2005)
 * @see "Simplex Noise Demystified" (Gustavson, 2005)
 */
public class SimplexNoise {

	// Gradient vectors for 3D (12 edges of cube)
	private static final int[][] GRAD3 = {
		{1,1,0}, {-1,1,0}, {1,-1,0}, {-1,-1,0},
		{1,0,1}, {-1,0,1}, {1,0,-1}, {-1,0,-1},
		{0,1,1}, {0,-1,1}, {0,1,-1}, {0,-1,-1}
	};

	// Permutation table
	private static final int[] P;
	private static final int[] PERM;

	static {
		// Initialize permutation table (Ken Perlin's reference)
		P = new int[256];
		for (int i = 0; i < 256; i++) {
			P[i] = i;
		}

		// Shuffle using simple permutation
		for (int i = 0; i < 256; i++) {
			int j = (int)(Math.random() * 256);
			int temp = P[i];
			P[i] = P[j];
			P[j] = temp;
		}

		// Double the permutation table to avoid overflow
		PERM = new int[512];
		for (int i = 0; i < 512; i++) {
			PERM[i] = P[i & 255];
		}
	}

	/**
	 * Create SimplexNoise with custom seed
	 *
	 * @param seed Random seed for reproducible noise
	 */
	public SimplexNoise(long seed) {
		// Seed-based permutation table (for deterministic turbulence)
		java.util.Random rand = new java.util.Random(seed);
		int[] p = new int[256];
		for (int i = 0; i < 256; i++) {
			p[i] = i;
		}

		// Fisher-Yates shuffle
		for (int i = 255; i > 0; i--) {
			int j = rand.nextInt(i + 1);
			int temp = p[i];
			p[i] = p[j];
			p[j] = temp;
		}

		// Copy to instance permutation table
		System.arraycopy(p, 0, P, 0, 256);
		for (int i = 0; i < 512; i++) {
			PERM[i] = P[i & 255];
		}
	}

	/**
	 * Default constructor (uses static permutation table)
	 */
	public SimplexNoise() {
		// Use default static permutation
	}

	/**
	 * 3D SIMPLEX NOISE
	 *
	 * Returns noise value in range [-1, 1] for given 3D coordinate.
	 *
	 * @param x X coordinate
	 * @param y Y coordinate
	 * @param z Z coordinate
	 * @return Noise value in range [-1, 1]
	 */
	public static double noise(double x, double y, double z) {
		// Skewing and unskewing factors for 3D
		final double F3 = 1.0 / 3.0;
		final double G3 = 1.0 / 6.0;

		double n0, n1, n2, n3; // Noise contributions from four corners

		// Skew the input space to determine which simplex cell we're in
		double s = (x + y + z) * F3;
		int i = fastFloor(x + s);
		int j = fastFloor(y + s);
		int k = fastFloor(z + s);

		double t = (i + j + k) * G3;
		double X0 = i - t; // Unskew the cell origin back to (x,y,z) space
		double Y0 = j - t;
		double Z0 = k - t;
		double x0 = x - X0; // The x,y,z distances from the cell origin
		double y0 = y - Y0;
		double z0 = z - Z0;

		// Determine which simplex we are in
		int i1, j1, k1; // Offsets for second corner
		int i2, j2, k2; // Offsets for third corner

		if (x0 >= y0) {
			if (y0 >= z0) {
				i1=1; j1=0; k1=0; i2=1; j2=1; k2=0; // X Y Z order
			} else if (x0 >= z0) {
				i1=1; j1=0; k1=0; i2=1; j2=0; k2=1; // X Z Y order
			} else {
				i1=0; j1=0; k1=1; i2=1; j2=0; k2=1; // Z X Y order
			}
		} else {
			if (y0 < z0) {
				i1=0; j1=0; k1=1; i2=0; j2=1; k2=1; // Z Y X order
			} else if (x0 < z0) {
				i1=0; j1=1; k1=0; i2=0; j2=1; k2=1; // Y Z X order
			} else {
				i1=0; j1=1; k1=0; i2=1; j2=1; k2=0; // Y X Z order
			}
		}

		// Offsets for corners in (x,y,z) coords
		double x1 = x0 - i1 + G3;
		double y1 = y0 - j1 + G3;
		double z1 = z0 - k1 + G3;
		double x2 = x0 - i2 + 2.0 * G3;
		double y2 = y0 - j2 + 2.0 * G3;
		double z2 = z0 - k2 + 2.0 * G3;
		double x3 = x0 - 1.0 + 3.0 * G3;
		double y3 = y0 - 1.0 + 3.0 * G3;
		double z3 = z0 - 1.0 + 3.0 * G3;

		// Work out the hashed gradient indices
		int ii = i & 255;
		int jj = j & 255;
		int kk = k & 255;
		int gi0 = PERM[ii + PERM[jj + PERM[kk]]] % 12;
		int gi1 = PERM[ii + i1 + PERM[jj + j1 + PERM[kk + k1]]] % 12;
		int gi2 = PERM[ii + i2 + PERM[jj + j2 + PERM[kk + k2]]] % 12;
		int gi3 = PERM[ii + 1 + PERM[jj + 1 + PERM[kk + 1]]] % 12;

		// Calculate contribution from four corners
		double t0 = 0.6 - x0*x0 - y0*y0 - z0*z0;
		if (t0 < 0) {
			n0 = 0.0;
		} else {
			t0 *= t0;
			n0 = t0 * t0 * dot(GRAD3[gi0], x0, y0, z0);
		}

		double t1 = 0.6 - x1*x1 - y1*y1 - z1*z1;
		if (t1 < 0) {
			n1 = 0.0;
		} else {
			t1 *= t1;
			n1 = t1 * t1 * dot(GRAD3[gi1], x1, y1, z1);
		}

		double t2 = 0.6 - x2*x2 - y2*y2 - z2*z2;
		if (t2 < 0) {
			n2 = 0.0;
		} else {
			t2 *= t2;
			n2 = t2 * t2 * dot(GRAD3[gi2], x2, y2, z2);
		}

		double t3 = 0.6 - x3*x3 - y3*y3 - z3*z3;
		if (t3 < 0) {
			n3 = 0.0;
		} else {
			t3 *= t3;
			n3 = t3 * t3 * dot(GRAD3[gi3], x3, y3, z3);
		}

		// Sum contributions and scale to [-1, 1]
		return 32.0 * (n0 + n1 + n2 + n3);
	}

	/**
	 * Dot product of gradient and distance vectors
	 */
	private static double dot(int[] g, double x, double y, double z) {
		return g[0] * x + g[1] * y + g[2] * z;
	}

	/**
	 * Fast floor function (faster than Math.floor for positive values)
	 */
	private static int fastFloor(double x) {
		int xi = (int)x;
		return x < xi ? xi - 1 : xi;
	}

	/**
	 * SINGLE-OCTAVE NOISE (Phase 2 implementation)
	 *
	 * Returns 3D velocity vector based on simplex noise.
	 * Used for basic turbulence simulation.
	 *
	 * @param x X coordinate (world space)
	 * @param y Y coordinate (world space)
	 * @param z Z coordinate (world space)
	 * @param wavelength Wavelength of noise (meters) - determines eddy size
	 * @param amplitude Amplitude multiplier (blocks/tick)
	 * @return [vx, vy, vz] velocity vector
	 */
	public static double[] getTurbulenceVector(double x, double y, double z,
			double wavelength, double amplitude) {
		// Normalize coordinates by wavelength
		double nx = x / wavelength;
		double ny = y / wavelength;
		double nz = z / wavelength;

		// Sample noise at 3 different offsets for X, Y, Z components
		// This ensures each component is independent
		double vx = noise(nx, ny, nz) * amplitude;
		double vy = noise(nx + 100.0, ny + 100.0, nz + 100.0) * amplitude;
		double vz = noise(nx + 200.0, ny + 200.0, nz + 200.0) * amplitude;

		return new double[]{vx, vy, vz};
	}

	/**
	 * MULTI-OCTAVE NOISE (Phase 3+ implementation)
	 *
	 * Returns fractal brownian motion (fBm) by summing multiple octaves.
	 * Each octave has half the amplitude and double the frequency.
	 *
	 * @param x X coordinate
	 * @param y Y coordinate
	 * @param z Z coordinate
	 * @param octaves Number of octaves (typically 3)
	 * @param persistence Amplitude falloff per octave (typically 0.5)
	 * @param lacunarity Frequency increase per octave (typically 2.0)
	 * @param baseWavelength Base wavelength for first octave (meters)
	 * @param baseAmplitude Base amplitude for first octave (blocks/tick)
	 * @return [vx, vy, vz] velocity vector
	 */
	public static double[] getMultiOctaveTurbulence(double x, double y, double z,
			int octaves, double persistence, double lacunarity,
			double baseWavelength, double baseAmplitude) {
		double vx = 0.0;
		double vy = 0.0;
		double vz = 0.0;

		double amplitude = baseAmplitude;
		double wavelength = baseWavelength;

		for (int i = 0; i < octaves; i++) {
			double[] octaveVel = getTurbulenceVector(x, y, z, wavelength, amplitude);
			vx += octaveVel[0];
			vy += octaveVel[1];
			vz += octaveVel[2];

			amplitude *= persistence;
			wavelength /= lacunarity;
		}

		return new double[]{vx, vy, vz};
	}
}
