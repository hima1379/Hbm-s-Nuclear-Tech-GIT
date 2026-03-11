package api.hbm.energy;

import java.math.BigDecimal;
import java.math.BigInteger;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Hybrid energy value class that uses long for performance when possible,
 * and automatically upgrades to BigInteger when values exceed long range.
 *
 * This class is immutable - all operations return new instances.
 *
 * @author hbm
 */
public final class EnergyValue implements Comparable<EnergyValue> {

	// Internal representation
	private final long longValue;
	private final BigInteger bigValue;
	private final boolean isLong;

	// Constants
	public static final EnergyValue ZERO = new EnergyValue(0L);
	public static final EnergyValue ONE = new EnergyValue(1L);
	private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
	private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

	// Private constructors
	private EnergyValue(long value) {
		this.longValue = value;
		this.bigValue = null;
		this.isLong = true;
	}

	private EnergyValue(BigInteger value) {
		this.longValue = 0;
		this.bigValue = value;
		this.isLong = false;
	}

	// Factory methods

	/**
	 * Create an EnergyValue from a long
	 */
	public static EnergyValue of(long value) {
		if(value == 0) return ZERO;
		if(value == 1) return ONE;
		return new EnergyValue(value);
	}

	/**
	 * Create an EnergyValue from a BigInteger
	 */
	public static EnergyValue of(BigInteger value) {
		if(value == null) return ZERO;
		if(value.equals(BigInteger.ZERO)) return ZERO;
		if(value.equals(BigInteger.ONE)) return ONE;

		// Check if it fits in long range
		if(value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0) {
			return new EnergyValue(value.longValue());
		}

		return new EnergyValue(value);
	}

	/**
	 * Create a BigInteger-mode EnergyValue with value 0
	 * Unlike of(BigInteger.ZERO), this FORCES BigInteger mode even for zero.
	 * This is used by FENSU to ensure it always operates in BigInteger mode
	 * and can accumulate energy beyond Long.MAX_VALUE.
	 */
	public static EnergyValue zeroBigInteger() {
		return new EnergyValue(BigInteger.ZERO);
	}

	/**
	 * Create a BigInteger-mode EnergyValue, forcing BigInteger storage
	 * even if the value fits in long range.
	 * This is used when you need to ensure BigInteger mode for subsequent operations.
	 */
	public static EnergyValue ofBigIntegerForced(BigInteger value) {
		if(value == null) return zeroBigInteger();
		return new EnergyValue(value);
	}

	// Arithmetic operations

	/**
	 * Add two EnergyValues
	 */
	public EnergyValue add(EnergyValue other) {
		if(this.isLong && other.isLong) {
			// Try long addition with overflow detection
			long result = this.longValue + other.longValue;

			// Check for overflow (same sign inputs, different sign output)
			if(((this.longValue ^ result) & (other.longValue ^ result)) < 0) {
				// Overflow detected, upgrade to BigInteger
				System.out.println("[EnergyValue] Overflow detected: " + this.longValue + " + " + other.longValue + " -> BigInteger");
				return new EnergyValue(
					BigInteger.valueOf(this.longValue).add(BigInteger.valueOf(other.longValue))
				);
			}

			return new EnergyValue(result);
		}

		// At least one is BigInteger
		BigInteger result = this.toBigInteger().add(other.toBigInteger());
		return EnergyValue.of(result);
	}

	/**
	 * Add a long value
	 */
	public EnergyValue add(long value) {
		return this.add(EnergyValue.of(value));
	}

	/**
	 * Subtract two EnergyValues
	 */
	public EnergyValue subtract(EnergyValue other) {
		if(this.isLong && other.isLong) {
			// Try long subtraction with overflow detection
			long result = this.longValue - other.longValue;

			// Check for overflow
			if(((this.longValue ^ other.longValue) & (this.longValue ^ result)) < 0) {
				// Overflow detected, upgrade to BigInteger
				return new EnergyValue(
					BigInteger.valueOf(this.longValue).subtract(BigInteger.valueOf(other.longValue))
				);
			}

			return new EnergyValue(result);
		}

		// At least one is BigInteger
		BigInteger result = this.toBigInteger().subtract(other.toBigInteger());
		return EnergyValue.of(result);
	}

	/**
	 * Subtract a long value
	 */
	public EnergyValue subtract(long value) {
		return this.subtract(EnergyValue.of(value));
	}

	/**
	 * Multiply two EnergyValues
	 */
	public EnergyValue multiply(EnergyValue other) {
		if(this.isLong && other.isLong) {
			// Check if multiplication would overflow
			if(this.longValue == 0 || other.longValue == 0) {
				return ZERO;
			}

			// Check for overflow
			long result = this.longValue * other.longValue;
			if(result / this.longValue != other.longValue) {
				// Overflow detected, upgrade to BigInteger
				return new EnergyValue(
					BigInteger.valueOf(this.longValue).multiply(BigInteger.valueOf(other.longValue))
				);
			}

			return new EnergyValue(result);
		}

		// At least one is BigInteger
		BigInteger result = this.toBigInteger().multiply(other.toBigInteger());
		return EnergyValue.of(result);
	}

	/**
	 * Multiply by a long value
	 */
	public EnergyValue multiply(long value) {
		return this.multiply(EnergyValue.of(value));
	}

	/**
	 * Divide two EnergyValues
	 */
	public EnergyValue divide(EnergyValue other) {
		if(other.isZero()) {
			throw new ArithmeticException("Division by zero");
		}

		if(this.isLong && other.isLong) {
			return new EnergyValue(this.longValue / other.longValue);
		}

		// At least one is BigInteger
		BigInteger result = this.toBigInteger().divide(other.toBigInteger());
		return EnergyValue.of(result);
	}

	/**
	 * Divide by a long value
	 */
	public EnergyValue divide(long value) {
		return this.divide(EnergyValue.of(value));
	}

	/**
	 * Modulo operation
	 */
	public EnergyValue mod(EnergyValue other) {
		if(other.isZero()) {
			throw new ArithmeticException("Division by zero");
		}

		if(this.isLong && other.isLong) {
			return new EnergyValue(this.longValue % other.longValue);
		}

		BigInteger result = this.toBigInteger().mod(other.toBigInteger().abs());
		return EnergyValue.of(result);
	}

	/**
	 * Get the minimum of two EnergyValues
	 */
	public EnergyValue min(EnergyValue other) {
		return this.compareTo(other) <= 0 ? this : other;
	}

	/**
	 * Get the maximum of two EnergyValues
	 */
	public EnergyValue max(EnergyValue other) {
		return this.compareTo(other) >= 0 ? this : other;
	}

	/**
	 * Clamp this value between min and max
	 */
	public EnergyValue clamp(EnergyValue min, EnergyValue max) {
		if(this.compareTo(min) < 0) return min;
		if(this.compareTo(max) > 0) return max;
		return this;
	}

	/**
	 * Get absolute value
	 */
	public EnergyValue abs() {
		if(this.isLong) {
			if(this.longValue == Long.MIN_VALUE) {
				// Special case: abs(Long.MIN_VALUE) overflows
				return new EnergyValue(LONG_MIN.negate());
			}
			return new EnergyValue(Math.abs(this.longValue));
		}
		return new EnergyValue(this.bigValue.abs());
	}

	/**
	 * Negate this value
	 */
	public EnergyValue negate() {
		if(this.isLong) {
			if(this.longValue == Long.MIN_VALUE) {
				// Special case: negate(Long.MIN_VALUE) overflows
				return new EnergyValue(LONG_MIN.negate());
			}
			return new EnergyValue(-this.longValue);
		}
		return new EnergyValue(this.bigValue.negate());
	}

	// Comparison operations

	@Override
	public int compareTo(EnergyValue other) {
		if(this.isLong && other.isLong) {
			return Long.compare(this.longValue, other.longValue);
		}
		return this.toBigInteger().compareTo(other.toBigInteger());
	}

	/**
	 * Check if this value is greater than another
	 */
	public boolean isGreaterThan(EnergyValue other) {
		return this.compareTo(other) > 0;
	}

	/**
	 * Check if this value is greater than or equal to another
	 */
	public boolean isGreaterThanOrEqual(EnergyValue other) {
		return this.compareTo(other) >= 0;
	}

	/**
	 * Check if this value is less than another
	 */
	public boolean isLessThan(EnergyValue other) {
		return this.compareTo(other) < 0;
	}

	/**
	 * Check if this value is less than or equal to another
	 */
	public boolean isLessThanOrEqual(EnergyValue other) {
		return this.compareTo(other) <= 0;
	}

	/**
	 * Check if this value equals another
	 */
	public boolean isEqual(EnergyValue other) {
		return this.compareTo(other) == 0;
	}

	/**
	 * Check if this value is zero
	 */
	public boolean isZero() {
		if(this.isLong) {
			return this.longValue == 0;
		}
		return this.bigValue.equals(BigInteger.ZERO);
	}

	/**
	 * Check if this value is positive
	 */
	public boolean isPositive() {
		if(this.isLong) {
			return this.longValue > 0;
		}
		return this.bigValue.compareTo(BigInteger.ZERO) > 0;
	}

	/**
	 * Check if this value is negative
	 */
	public boolean isNegative() {
		if(this.isLong) {
			return this.longValue < 0;
		}
		return this.bigValue.compareTo(BigInteger.ZERO) < 0;
	}

	// Conversion methods

	/**
	 * Convert to long (may overflow)
	 */
	public long toLong() {
		if(this.isLong) {
			return this.longValue;
		}
		return this.bigValue.longValue();
	}

	/**
	 * Convert to long, clamping to Long.MAX_VALUE or Long.MIN_VALUE if out of range
	 */
	public long toLongClamped() {
		if(this.isLong) {
			return this.longValue;
		}

		if(this.bigValue.compareTo(LONG_MAX) > 0) {
			return Long.MAX_VALUE;
		}
		if(this.bigValue.compareTo(LONG_MIN) < 0) {
			return Long.MIN_VALUE;
		}
		return this.bigValue.longValue();
	}

	/**
	 * Convert to int, clamping to Integer.MAX_VALUE or Integer.MIN_VALUE if out of range
	 */
	public int toIntClamped() {
		long value = this.toLongClamped();
		if(value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if(value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) value;
	}

	/**
	 * Convert to BigInteger
	 */
	public BigInteger toBigInteger() {
		if(this.isLong) {
			return BigInteger.valueOf(this.longValue);
		}
		return this.bigValue;
	}

	/**
	 * Convert to BigDecimal for display purposes
	 */
	public BigDecimal toBigDecimal() {
		if(this.isLong) {
			return BigDecimal.valueOf(this.longValue);
		}
		return new BigDecimal(this.bigValue);
	}

	/**
	 * Check if this value fits in long range
	 */
	public boolean fitsInLong() {
		return this.isLong;
	}

	/**
	 * Check if this value would overflow when converted to int
	 */
	public boolean fitsInInt() {
		if(this.isLong) {
			return this.longValue >= Integer.MIN_VALUE && this.longValue <= Integer.MAX_VALUE;
		}
		return this.bigValue.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0 &&
		       this.bigValue.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0;
	}

	// Object methods

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(!(obj instanceof EnergyValue)) return false;

		EnergyValue other = (EnergyValue) obj;
		return this.compareTo(other) == 0;
	}

	@Override
	public int hashCode() {
		if(this.isLong) {
			return Long.hashCode(this.longValue);
		}
		return this.bigValue.hashCode();
	}

	@Override
	public String toString() {
		if(this.isLong) {
			return String.valueOf(this.longValue);
		}
		return this.bigValue.toString();
	}

	/**
	 * Get a debug string showing internal representation
	 */
	public String toDebugString() {
		if(this.isLong) {
			return "EnergyValue[long=" + this.longValue + "]";
		}
		return "EnergyValue[BigInteger=" + this.bigValue + "]";
	}

	// NBT Serialization

	/**
	 * Write this EnergyValue to NBT.
	 * Automatically chooses the most efficient format:
	 * - long values are stored as NBTTagLong (8 bytes)
	 * - BigInteger values are stored as NBTTagByteArray (variable length)
	 *
	 * @param compound The NBT compound to write to
	 * @param key The key to use for storage
	 */
	public void writeToNBT(NBTTagCompound compound, String key) {
		if(this.isLong) {
			// Store as long (efficient, 8 bytes)
			compound.setLong(key, this.longValue);
		} else {
			// Store as BigInteger byte array
			byte[] bytes = this.bigValue.toByteArray();
			compound.setByteArray(key + "_big", bytes);
			// Remove old long value if it exists (migration case)
			compound.removeTag(key);
		}
	}

	/**
	 * Read an EnergyValue from NBT.
	 * Automatically detects the format:
	 * - Checks for BigInteger format (key + "_big")
	 * - Falls back to long format for backward compatibility
	 *
	 * @param compound The NBT compound to read from
	 * @param key The key to read
	 * @return The EnergyValue, or ZERO if not found
	 */
	public static EnergyValue readFromNBT(NBTTagCompound compound, String key) {
		// Check for BigInteger format first
		if(compound.hasKey(key + "_big", 7)) { // 7 = TAG_BYTE_ARRAY
			byte[] bytes = compound.getByteArray(key + "_big");
			if(bytes.length == 0) return ZERO;
			return EnergyValue.of(new BigInteger(bytes));
		}

		// Fall back to long format (backward compatibility)
		if(compound.hasKey(key, 4)) { // 4 = TAG_LONG
			return EnergyValue.of(compound.getLong(key));
		}

		// Not found, return zero
		return ZERO;
	}

	// Packet Serialization

	/**
	 * Write this EnergyValue to a ByteBuf for packet transmission.
	 * Format:
	 * - 1 byte: type flag (0 = long, 1 = BigInteger)
	 * - long format: 8 bytes (long value)
	 * - BigInteger format: 4 bytes (length) + N bytes (data)
	 *
	 * @param buf The ByteBuf to write to
	 */
	public void writeToByteBuf(ByteBuf buf) {
		if(this.isLong) {
			buf.writeByte(0); // Type flag: long
			buf.writeLong(this.longValue);
		} else {
			buf.writeByte(1); // Type flag: BigInteger
			byte[] bytes = this.bigValue.toByteArray();
			buf.writeInt(bytes.length);
			buf.writeBytes(bytes);
		}
	}

	/**
	 * Read an EnergyValue from a ByteBuf.
	 *
	 * @param buf The ByteBuf to read from
	 * @return The EnergyValue read from the buffer
	 */
	public static EnergyValue readFromByteBuf(ByteBuf buf) {
		byte typeFlag = buf.readByte();

		if(typeFlag == 0) {
			// Long format
			return EnergyValue.of(buf.readLong());
		} else {
			// BigInteger format
			int length = buf.readInt();
			byte[] bytes = new byte[length];
			buf.readBytes(bytes);
			return EnergyValue.of(new BigInteger(bytes));
		}
	}
}
