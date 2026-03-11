package com.hbm.physics;

/**
 * 3x3 Matrix class for Direction Cosine Matrices (DCM) and inertia tensor operations.
 *
 * Used extensively in 6DOF missile simulation for:
 * - Coordinate frame transformations (body ↔ inertial)
 * - Inertia tensor representation and manipulation
 * - Moment calculations in Euler's rotation equations
 *
 * Matrix layout (row-major):
 * [ m00 m01 m02 ]
 * [ m10 m11 m12 ]
 * [ m20 m21 m22 ]
 *
 * @author SM6 Missile Physics Engine
 */
public class Matrix3x3 {

    private final double[][] elements;

    // ========== CONSTRUCTORS ==========

    /**
     * Constructs matrix from 2D array (row-major).
     */
    public Matrix3x3(double[][] elements) {
        if (elements.length != 3 || elements[0].length != 3) {
            throw new IllegalArgumentException("Matrix must be 3x3");
        }
        // Deep copy to ensure immutability
        this.elements = new double[3][3];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(elements[i], 0, this.elements[i], 0, 3);
        }
    }

    /**
     * Constructs matrix from individual elements (row-major).
     */
    public Matrix3x3(
        double m00, double m01, double m02,
        double m10, double m11, double m12,
        double m20, double m21, double m22
    ) {
        this.elements = new double[][] {
            {m00, m01, m02},
            {m10, m11, m12},
            {m20, m21, m22}
        };
    }

    /**
     * Identity matrix constructor.
     */
    public static Matrix3x3 identity() {
        return new Matrix3x3(
            1, 0, 0,
            0, 1, 0,
            0, 0, 1
        );
    }

    /**
     * Zero matrix constructor.
     */
    public static Matrix3x3 zero() {
        return new Matrix3x3(
            0, 0, 0,
            0, 0, 0,
            0, 0, 0
        );
    }

    /**
     * Diagonal matrix from values.
     */
    public static Matrix3x3 diagonal(double d0, double d1, double d2) {
        return new Matrix3x3(
            d0, 0, 0,
            0, d1, 0,
            0, 0, d2
        );
    }

    /**
     * Diagonal matrix from vector.
     */
    public static Matrix3x3 diagonal(Vector3D diag) {
        return diagonal(diag.x, diag.y, diag.z);
    }

    /**
     * Skew-symmetric matrix from vector (for cross product).
     *
     * Skew(v) such that Skew(v) × w = v × w
     *
     * [  0   -v.z   v.y ]
     * [ v.z   0   -v.x ]
     * [-v.y  v.x    0  ]
     *
     * CRITICAL for: ω × (I × ω) in Euler's rotation equations
     */
    public static Matrix3x3 skewSymmetric(Vector3D v) {
        return new Matrix3x3(
            0, -v.z, v.y,
            v.z, 0, -v.x,
            -v.y, v.x, 0
        );
    }

    // ========== ACCESSORS ==========

    /**
     * Get element at row i, column j.
     */
    public double get(int row, int col) {
        return elements[row][col];
    }

    /**
     * Get row as vector.
     */
    public Vector3D getRow(int row) {
        return new Vector3D(elements[row][0], elements[row][1], elements[row][2]);
    }

    /**
     * Get column as vector.
     */
    public Vector3D getColumn(int col) {
        return new Vector3D(elements[0][col], elements[1][col], elements[2][col]);
    }

    // ========== MATRIX OPERATIONS ==========

    /**
     * Matrix addition.
     */
    public Matrix3x3 add(Matrix3x3 other) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                result[i][j] = this.elements[i][j] + other.elements[i][j];
            }
        }
        return new Matrix3x3(result);
    }

    /**
     * Matrix subtraction.
     */
    public Matrix3x3 subtract(Matrix3x3 other) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                result[i][j] = this.elements[i][j] - other.elements[i][j];
            }
        }
        return new Matrix3x3(result);
    }

    /**
     * Matrix multiplication.
     *
     * C = A × B
     * C[i][j] = Σ A[i][k] × B[k][j]
     *
     * CRITICAL for: Cascading coordinate transformations
     */
    public Matrix3x3 multiply(Matrix3x3 other) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                result[i][j] = 0;
                for (int k = 0; k < 3; k++) {
                    result[i][j] += this.elements[i][k] * other.elements[k][j];
                }
            }
        }
        return new Matrix3x3(result);
    }

    /**
     * Matrix-vector multiplication.
     *
     * result = M × v
     *
     * CRITICAL for: Coordinate frame transformations
     */
    public Vector3D multiply(Vector3D v) {
        return new Vector3D(
            elements[0][0] * v.x + elements[0][1] * v.y + elements[0][2] * v.z,
            elements[1][0] * v.x + elements[1][1] * v.y + elements[1][2] * v.z,
            elements[2][0] * v.x + elements[2][1] * v.y + elements[2][2] * v.z
        );
    }

    /**
     * Scalar multiplication.
     */
    public Matrix3x3 scale(double scalar) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                result[i][j] = this.elements[i][j] * scalar;
            }
        }
        return new Matrix3x3(result);
    }

    /**
     * Matrix transpose.
     *
     * A^T[i][j] = A[j][i]
     *
     * For DCM (orthogonal matrices): A^T = A^(-1)
     */
    public Matrix3x3 transpose() {
        return new Matrix3x3(
            elements[0][0], elements[1][0], elements[2][0],
            elements[0][1], elements[1][1], elements[2][1],
            elements[0][2], elements[1][2], elements[2][2]
        );
    }

    /**
     * Matrix determinant.
     *
     * det(A) = a00(a11*a22 - a12*a21) - a01(a10*a22 - a12*a20) + a02(a10*a21 - a11*a20)
     */
    public double determinant() {
        return elements[0][0] * (elements[1][1] * elements[2][2] - elements[1][2] * elements[2][1])
             - elements[0][1] * (elements[1][0] * elements[2][2] - elements[1][2] * elements[2][0])
             + elements[0][2] * (elements[1][0] * elements[2][1] - elements[1][1] * elements[2][0]);
    }

    /**
     * Matrix trace (sum of diagonal elements).
     *
     * tr(A) = a00 + a11 + a22
     */
    public double trace() {
        return elements[0][0] + elements[1][1] + elements[2][2];
    }

    /**
     * Matrix inverse using adjugate method.
     *
     * A^(-1) = adj(A) / det(A)
     *
     * Returns null if matrix is singular (det ≈ 0).
     */
    public Matrix3x3 inverse() {
        double det = determinant();
        if (Math.abs(det) < 1e-12) {
            return null; // Singular matrix
        }

        // Calculate cofactor matrix
        double c00 = elements[1][1] * elements[2][2] - elements[1][2] * elements[2][1];
        double c01 = -(elements[1][0] * elements[2][2] - elements[1][2] * elements[2][0]);
        double c02 = elements[1][0] * elements[2][1] - elements[1][1] * elements[2][0];

        double c10 = -(elements[0][1] * elements[2][2] - elements[0][2] * elements[2][1]);
        double c11 = elements[0][0] * elements[2][2] - elements[0][2] * elements[2][0];
        double c12 = -(elements[0][0] * elements[2][1] - elements[0][1] * elements[2][0]);

        double c20 = elements[0][1] * elements[1][2] - elements[0][2] * elements[1][1];
        double c21 = -(elements[0][0] * elements[1][2] - elements[0][2] * elements[1][0]);
        double c22 = elements[0][0] * elements[1][1] - elements[0][1] * elements[1][0];

        // Adjugate = transpose of cofactor matrix
        // Inverse = adjugate / determinant
        double invDet = 1.0 / det;
        return new Matrix3x3(
            c00 * invDet, c10 * invDet, c20 * invDet,
            c01 * invDet, c11 * invDet, c21 * invDet,
            c02 * invDet, c12 * invDet, c22 * invDet
        );
    }

    // ========== SPECIALIZED OPERATIONS ==========

    /**
     * Frobenius norm (matrix magnitude).
     *
     * ||A||_F = sqrt(Σ Σ a_ij²)
     */
    public double frobeniusNorm() {
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                sum += elements[i][j] * elements[i][j];
            }
        }
        return Math.sqrt(sum);
    }

    /**
     * Check if matrix is approximately symmetric.
     *
     * CRITICAL for: Validating inertia tensors (must be symmetric)
     */
    public boolean isSymmetric(double epsilon) {
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (Math.abs(elements[i][j] - elements[j][i]) > epsilon) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Check if matrix is approximately orthogonal (rotation matrix).
     *
     * Orthogonal: A × A^T = I
     *
     * CRITICAL for: Validating DCM matrices
     */
    public boolean isOrthogonal(double epsilon) {
        Matrix3x3 aat = this.multiply(this.transpose());
        Matrix3x3 identity = Matrix3x3.identity();

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (Math.abs(aat.elements[i][j] - identity.elements[i][j]) > epsilon) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Orthonormalize matrix using Gram-Schmidt process.
     *
     * CRITICAL for: Correcting DCM drift due to numerical errors
     */
    public Matrix3x3 orthonormalize() {
        // Get column vectors
        Vector3D col0 = getColumn(0);
        Vector3D col1 = getColumn(1);
        Vector3D col2 = getColumn(2);

        // Orthonormalize using Gram-Schmidt
        Vector3D v0 = col0.normalize();
        Vector3D v1 = col1.subtract(v0.scale(col1.dot(v0))).normalize();
        Vector3D v2 = col2.subtract(v0.scale(col2.dot(v0)))
                          .subtract(v1.scale(col2.dot(v1)))
                          .normalize();

        // Reconstruct matrix from orthonormal columns
        return new Matrix3x3(
            v0.x, v1.x, v2.x,
            v0.y, v1.y, v2.y,
            v0.z, v1.z, v2.z
        );
    }

    /**
     * Create inertia tensor for cylinder (missile body approximation).
     *
     * Assumes cylinder aligned with X-axis (body forward).
     *
     * I_xx = (1/2) × m × r²
     * I_yy = I_zz = (1/12) × m × (3r² + L²)
     *
     * @param mass total mass (kg)
     * @param radius cylinder radius (m)
     * @param length cylinder length (m)
     * @return inertia tensor matrix
     */
    public static Matrix3x3 inertiaTensorCylinder(double mass, double radius, double length) {
        double Ixx = 0.5 * mass * radius * radius;
        double Iyy = (1.0 / 12.0) * mass * (3.0 * radius * radius + length * length);
        double Izz = Iyy; // Axisymmetric

        return diagonal(Ixx, Iyy, Izz);
    }

    /**
     * Transform inertia tensor to different reference point.
     *
     * Parallel Axis Theorem:
     * I' = I + m × [d² × E - d ⊗ d]
     *
     * where d is displacement vector from old to new center of mass.
     */
    public Matrix3x3 parallelAxisTransform(double mass, Vector3D displacement) {
        double dSquared = displacement.lengthSquared();

        // d ⊗ d (outer product)
        Matrix3x3 outerProduct = new Matrix3x3(
            displacement.x * displacement.x, displacement.x * displacement.y, displacement.x * displacement.z,
            displacement.y * displacement.x, displacement.y * displacement.y, displacement.y * displacement.z,
            displacement.z * displacement.x, displacement.z * displacement.y, displacement.z * displacement.z
        );

        // d² × E (identity scaled by squared distance)
        Matrix3x3 dSquaredI = Matrix3x3.identity().scale(dSquared);

        // I' = I + m × [d² × E - d ⊗ d]
        return this.add(dSquaredI.subtract(outerProduct).scale(mass));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Matrix3x3[\n");
        for (int i = 0; i < 3; i++) {
            sb.append("  [");
            for (int j = 0; j < 3; j++) {
                sb.append(String.format(" %9.6f", elements[i][j]));
                if (j < 2) sb.append(",");
            }
            sb.append(" ]");
            if (i < 2) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Compact string representation (one line).
     */
    public String toCompactString() {
        return String.format("[[%.3f,%.3f,%.3f],[%.3f,%.3f,%.3f],[%.3f,%.3f,%.3f]]",
            elements[0][0], elements[0][1], elements[0][2],
            elements[1][0], elements[1][1], elements[1][2],
            elements[2][0], elements[2][1], elements[2][2]);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Matrix3x3)) return false;
        Matrix3x3 other = (Matrix3x3) obj;

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (Double.compare(this.elements[i][j], other.elements[i][j]) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        long bits = 1L;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                bits = 31L * bits + Double.doubleToLongBits(elements[i][j]);
            }
        }
        return (int) (bits ^ (bits >>> 32));
    }
}
