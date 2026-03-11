package com.hbm.render.util;

/**
 * Simple 2D vector utility class for radar display coordinate transformations
 */
public class Vec2d {
	public double x, y;

	public Vec2d(double x, double y) {
		this.x = x;
		this.y = y;
	}

	/**
	 * Add another vector to this one
	 */
	public Vec2d add(Vec2d other) {
		return new Vec2d(this.x + other.x, this.y + other.y);
	}

	/**
	 * Subtract another vector from this one
	 */
	public Vec2d subtract(Vec2d other) {
		return new Vec2d(this.x - other.x, this.y - other.y);
	}

	/**
	 * Scale this vector by a factor
	 */
	public Vec2d scale(double factor) {
		return new Vec2d(this.x * factor, this.y * factor);
	}

	/**
	 * Get the length (magnitude) of this vector
	 */
	public double length() {
		return Math.sqrt(x * x + y * y);
	}

	/**
	 * Get normalized version of this vector (length = 1)
	 */
	public Vec2d normalize() {
		double len = length();
		return len > 0 ? new Vec2d(x / len, y / len) : new Vec2d(0, 0);
	}

	/**
	 * Rotate this vector by an angle in degrees
	 */
	public Vec2d rotate(double angleDegrees) {
		double rad = Math.toRadians(angleDegrees);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		return new Vec2d(x * cos - y * sin, x * sin + y * cos);
	}

	/**
	 * Dot product with another vector
	 */
	public double dot(Vec2d other) {
		return this.x * other.x + this.y * other.y;
	}

	/**
	 * Get the distance to another vector
	 */
	public double distanceTo(Vec2d other) {
		double dx = this.x - other.x;
		double dy = this.y - other.y;
		return Math.sqrt(dx * dx + dy * dy);
	}

	@Override
	public String toString() {
		return String.format("Vec2d(%.2f, %.2f)", x, y);
	}
}
