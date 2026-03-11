package com.hbm.main.tileentity.network.data;

import java.util.UUID;

import com.hbm.main.tileentity.TileEntityTickingBase;

import api.hbm.data.DataDeviceType;
import api.hbm.data.DataNet;
import api.hbm.data.DataPacket;
import api.hbm.data.DataPacket.DataPacketType;
import api.hbm.data.IDataConnector;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;

/**
 * SPG-62 Fire Control Radar TileEntity
 *
 * Based on real AN/SPG-62 specifications:
 * - I/J-Band fire control radar (8-20 GHz)
 * - Peak power: 10 kW
 * - Target illumination for semi-active missiles (Standard Missile family)
 * - Mechanical rotation (azimuth: 0-360°, elevation: 0-90°)
 *
 * Operation:
 * - Receives RADAR_SCAN_DATA packets from SPY-1 radar
 * - Tracks first contact from scan data
 * - Mechanically rotates dish toward target (smooth rotation at 2°/tick)
 * - Intermittent illumination pattern (10 ticks on, 10 ticks off)
 * - Sends ILLUMINATION_DATA packets to DataNet for missile guidance
 *
 * Temporary Implementation:
 * - No power system (operates without power)
 * - No GUI controls
 * - Automatic target selection (first contact from SPY-1)
 *
 * Connection:
 * - CableBlue (DataNet) on all faces
 */
public class TileEntitySPG62 extends TileEntityTickingBase implements ITickable, IDataConnector {

	private UUID deviceId;
	private DataNet dataNet;

	// Rotation mechanics (degrees)
	public float azimuthAngle = 0.0F;      // Current horizontal rotation (0-360°) - SPG62_roll part
	public float elevationAngle = 45.0F;   // Current vertical rotation (0-90°) - SPG62_antenna part
	public float targetAzimuth = 0.0F;     // Target horizontal angle
	public float targetElevation = 45.0F;  // Target vertical angle

	// Rotation speed
	private static final float ROTATION_SPEED = 2.0F;  // 2 degrees per tick (40 deg/sec)

	// Tracking state
	private boolean hasTarget = false;
	private double targetX = 0.0;
	private double targetY = 0.0;
	private double targetZ = 0.0;
	private int targetEntityId = -1;  // Entity ID of designated target

	// Intermittent illumination pattern
	public boolean isIlluminating = false;
	private int illuminationCycleTimer = 0;
	private static final int ILLUMINATION_ON_TIME = 10;   // 10 ticks (0.5s)
	private static final int ILLUMINATION_OFF_TIME = 10;  // 10 ticks (0.5s)
	private static final int ILLUMINATION_CYCLE = ILLUMINATION_ON_TIME + ILLUMINATION_OFF_TIME;

	public TileEntitySPG62() {
		this.deviceId = UUID.randomUUID();
	}

	@Override
	public String getInventoryName() {
		return "SPG-62 Fire Control Radar";
	}

	@Override
	public void update() {
		if (!world.isRemote) {
			// 1. Check for DataNet connection (and rescan periodically every 100 ticks)
			if (dataNet == null && world.getTotalWorldTime() % 100 == 0) {
				scanForCables();  // Periodic rescan to handle timing issues
			}

			if (dataNet == null) {
				return;  // No network, do nothing
			}

			// 2. Check for TARGET_DESIGNATION packets from FCS Console
			// Note: Packets are received via receiveData() callback
			// The receiveData() method sets hasTarget and target coordinates

			// 3. Rotate toward target (smooth rotation)
			if (hasTarget) {
				rotateTowardTarget();
			}

			// 4. Update illumination cycle timer
			updateIlluminationCycle();

			// 5. If illuminating, send ILLUMINATION_DATA packet
			if (isIlluminating && hasTarget) {
				sendIlluminationData();
			}

			// Debug logging every 100 ticks
			if (world.getTotalWorldTime() % 100 == 0) {
				System.out.println("[SPG-62 DEBUG] DataNet: #" + dataNet.getNetId() +
					" | Az: " + String.format("%.1f", azimuthAngle) +
					"° -> " + String.format("%.1f", targetAzimuth) + "°, " +
					"El: " + String.format("%.1f", elevationAngle) +
					"° -> " + String.format("%.1f", targetElevation) + "°, " +
					"Illuminating: " + isIlluminating + ", HasTarget: " + hasTarget);
			}
		}
	}

	/**
	 * Smoothly rotate dish toward target angles
	 * Rotation speed: 2 degrees per tick (40 deg/sec)
	 */
	private void rotateTowardTarget() {
		// Rotate azimuth (handle 0-360 wraparound)
		float azimuthDiff = targetAzimuth - azimuthAngle;

		// Normalize to -180 to +180 range (shortest rotation path)
		while (azimuthDiff > 180.0F) azimuthDiff -= 360.0F;
		while (azimuthDiff < -180.0F) azimuthDiff += 360.0F;

		if (Math.abs(azimuthDiff) > ROTATION_SPEED) {
			// Rotate toward target
			azimuthAngle += Math.signum(azimuthDiff) * ROTATION_SPEED;
		} else {
			// Snap to target (close enough)
			azimuthAngle = targetAzimuth;
		}

		// Normalize azimuth to 0-360 range
		while (azimuthAngle < 0.0F) azimuthAngle += 360.0F;
		while (azimuthAngle >= 360.0F) azimuthAngle -= 360.0F;

		// Rotate elevation (simple linear interpolation, 0-90 range)
		float elevationDiff = targetElevation - elevationAngle;

		if (Math.abs(elevationDiff) > ROTATION_SPEED) {
			// Rotate toward target
			elevationAngle += Math.signum(elevationDiff) * ROTATION_SPEED;
		} else {
			// Snap to target (close enough)
			elevationAngle = targetElevation;
		}

		// Clamp elevation to 0-90 range
		if (elevationAngle < 0.0F) elevationAngle = 0.0F;
		if (elevationAngle > 90.0F) elevationAngle = 90.0F;
	}

	/**
	 * Update illumination cycle
	 * Pattern: illuminate for 10 ticks, pause for 10 ticks, repeat
	 */
	private void updateIlluminationCycle() {
		illuminationCycleTimer++;

		if (illuminationCycleTimer >= ILLUMINATION_CYCLE) {
			illuminationCycleTimer = 0;
		}

		// Illuminate for first 10 ticks of cycle
		isIlluminating = (illuminationCycleTimer < ILLUMINATION_ON_TIME);
	}

	/**
	 * Send illumination data to DataNet
	 * Contains target position, entity ID, and lock status
	 * Only sends when illuminating AND locked on target
	 */
	private void sendIlluminationData() {
		if (dataNet == null) return;

		// Check if we're locked on (aimed correctly + illuminating)
		boolean lockedOn = isLockedOn();

		NBTTagCompound data = new NBTTagCompound();

		// Target position
		data.setDouble("targetX", targetX);
		data.setDouble("targetY", targetY);
		data.setDouble("targetZ", targetZ);

		// Entity ID of target
		data.setInteger("entityId", targetEntityId);

		// Radar angles
		data.setDouble("azimuth", azimuthAngle);
		data.setDouble("elevation", elevationAngle);

		// Illumination and lock status
		data.setBoolean("illuminating", isIlluminating);
		data.setBoolean("lockedOn", lockedOn);

		// Radar position (for range calculations)
		data.setDouble("radarX", pos.getX() + 0.5);
		data.setDouble("radarY", pos.getY() + 0.5);
		data.setDouble("radarZ", pos.getZ() + 0.5);

		// Broadcast illumination data
		DataPacket packet = new DataPacket(DataPacketType.ILLUMINATION_DATA, deviceId, data);
		dataNet.broadcastData(packet);

		// Debug logging (every 20 ticks when illuminating)
		if (world.getTotalWorldTime() % 20 == 0) {
			System.out.println("[SPG-62 ILLUMINATION] Target ID: " + targetEntityId +
				" | Pos: (" + String.format("%.1f", targetX) + ", " +
				String.format("%.1f", targetY) + ", " +
				String.format("%.1f", targetZ) + ") | Az: " +
				String.format("%.1f", azimuthAngle) + "°, El: " +
				String.format("%.1f", elevationAngle) + "° | LockedOn: " + lockedOn);
		}
	}

	/**
	 * Handle target designation from FCS Console
	 * FCS Console sends this when operator clicks on a target
	 */
	private void handleTargetDesignation(DataPacket packet) {
		NBTTagCompound data = packet.getData();

		// Extract designated target information
		targetX = data.getDouble("targetX");
		targetY = data.getDouble("targetY");
		targetZ = data.getDouble("targetZ");
		targetEntityId = data.getInteger("entityId");
		String trackNumber = data.getString("trackNumber");

		// Calculate azimuth and elevation to target
		double dx = targetX - (pos.getX() + 0.5);
		double dy = targetY - (pos.getY() + 0.5);
		double dz = targetZ - (pos.getZ() + 0.5);

		// Horizontal distance
		double horizontalDist = Math.sqrt(dx * dx + dz * dz);

		// Azimuth angle (0° = North/+Z, 90° = East/-X, 180° = South/-Z, 270° = West/+X)
		double azimuthRad = Math.atan2(-dx, dz);
		targetAzimuth = (float) Math.toDegrees(azimuthRad);

		// Normalize to 0-360 range
		while (targetAzimuth < 0.0F) targetAzimuth += 360.0F;
		while (targetAzimuth >= 360.0F) targetAzimuth -= 360.0F;

		// Elevation angle (0° = horizontal, 90° = straight up)
		double elevationRad = Math.atan2(dy, horizontalDist);
		targetElevation = (float) Math.toDegrees(elevationRad);

		// Clamp elevation to 0-90 range
		if (targetElevation < 0.0F) targetElevation = 0.0F;
		if (targetElevation > 90.0F) targetElevation = 90.0F;

		hasTarget = true;

		System.out.println("[SPG-62] TARGET DESIGNATED: " + trackNumber +
			" (ID: " + targetEntityId + ") at (" +
			String.format("%.1f", targetX) + ", " +
			String.format("%.1f", targetY) + ", " +
			String.format("%.1f", targetZ) + ") " +
			"Az: " + String.format("%.1f", targetAzimuth) + "°, " +
			"El: " + String.format("%.1f", targetElevation) + "°");
	}

	/**
	 * Check if SPG-62 is locked on target
	 * Lock successful = radar is aimed at target (within tolerance) AND illuminating
	 * @return true if locked on
	 */
	public boolean isLockedOn() {
		if (!hasTarget || !isIlluminating) {
			return false;
		}

		// Check if current angles are close to target angles (within 5 degrees)
		float azimuthDiff = Math.abs(targetAzimuth - azimuthAngle);
		// Handle wraparound
		if (azimuthDiff > 180.0F) azimuthDiff = 360.0F - azimuthDiff;

		float elevationDiff = Math.abs(targetElevation - elevationAngle);

		// Locked on if within 5 degrees on both axes and illuminating
		return azimuthDiff < 5.0F && elevationDiff < 5.0F;
	}

	// ========== IDataConnector Implementation ==========

	@Override
	public void receiveData(DataPacket packet) {
		// Debug logging
		System.out.println("[SPG-62 RECEIVE] Received packet type: " + packet.getType() + " from " + packet.getSourceDevice());

		// Process TARGET_DESIGNATION packets only
		// SPG-62 does NOT auto-track from RADAR_SCAN_DATA
		// It only tracks targets designated by FCS Console
		if (packet.getType() == DataPacketType.TARGET_DESIGNATION) {
			System.out.println("[SPG-62 RECEIVE] Processing TARGET_DESIGNATION packet");
			handleTargetDesignation(packet);
			return;
		}

		// Ignore all other packet types (including RADAR_SCAN_DATA)
		// SPG-62 is a fire control radar - it illuminates designated targets, not general surveillance
		System.out.println("[SPG-62 RECEIVE] Ignoring packet type: " + packet.getType());
	}

	/**
	 * Get the DataNet this radar is connected to
	 * SPG-62 is a single-block device, so we check 6 adjacent positions for cables
	 */
	public DataNet getDataNet() {
		if (dataNet == null && !world.isRemote) {
			scanForCables();
		}
		return dataNet;
	}

	/**
	 * Scan adjacent blocks for DataNet cables (IDataConductor)
	 * SPG-62 is a single-block device, so we only check 6 adjacent positions
	 */
	private void scanForCables() {
		// Check all 6 adjacent blocks
		EnumFacing[] directions = EnumFacing.values();

		for (EnumFacing dir : directions) {
			BlockPos checkPos = pos.offset(dir);
			TileEntity te = world.getTileEntity(checkPos);

			if (te instanceof api.hbm.data.IDataConductor) {
				api.hbm.data.IDataConductor conductor = (api.hbm.data.IDataConductor) te;

				// If cable has a network, join it
				if (conductor.getDataNet() != null) {
					dataNet = conductor.getDataNet();
					dataNet.addSubscriber(this);
					System.out.println("[SPG-62] ✓ Found and connected to DataNet #" +
						dataNet.getNetId() + " via cable at " + checkPos);
					return;
				} else {
					// Cable exists but has no network yet - ask it to re-evaluate
					System.out.println("[SPG-62] Found cable with NULL DataNet at " + checkPos + " - triggering re-evaluate");
					conductor.reEvaluate();
					// Try again on next tick
				}
			}
		}

		// No cable found - log it periodically
		if (world.getTotalWorldTime() % 100 == 0) {  // Log every 5 seconds to avoid spam
			System.out.println("[SPG-62] ⚠ No DataNet cables found in adjacent blocks at " + pos);
		}
	}

	public void setDataNet(DataNet net) {
		this.dataNet = net;
	}

	@Override
	public boolean canConnect(EnumFacing dir) {
		return true;  // Allow connections from all directions
	}

	@Override
	public DataDeviceType getDeviceType() {
		return DataDeviceType.RADAR_FIRE_CONTROL;
	}

	@Override
	public UUID getDeviceId() {
		return this.deviceId;
	}

	@Override
	public String getDeviceName() {
		return "SPG-62 Fire Control Radar @ " + pos.toString();
	}

	@Override
	public boolean isActive() {
		// Radar is always active (temporary - no power system)
		return !this.isInvalid();
	}

	// ========== NBT ==========

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		// Read deviceId with fallback
		String deviceIdStr = nbt.getString("deviceId");
		if (deviceIdStr != null && !deviceIdStr.isEmpty()) {
			try {
				this.deviceId = UUID.fromString(deviceIdStr);
			} catch (IllegalArgumentException e) {
				this.deviceId = UUID.randomUUID();
				System.out.println("[SPG-62] Invalid deviceId in NBT, generated new: " + this.deviceId);
			}
		} else {
			this.deviceId = UUID.randomUUID();
			System.out.println("[SPG-62] No deviceId in NBT, generated new: " + this.deviceId);
		}

		// Read rotation state
		this.azimuthAngle = nbt.getFloat("azimuthAngle");
		this.elevationAngle = nbt.getFloat("elevationAngle");

		// Read illumination state
		this.isIlluminating = nbt.getBoolean("isIlluminating");
		this.illuminationCycleTimer = nbt.getInteger("illuminationCycleTimer");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		nbt.setString("deviceId", deviceId.toString());

		// Save rotation state
		nbt.setFloat("azimuthAngle", azimuthAngle);
		nbt.setFloat("elevationAngle", elevationAngle);

		// Save illumination state
		nbt.setBoolean("isIlluminating", isIlluminating);
		nbt.setInteger("illuminationCycleTimer", illuminationCycleTimer);

		return nbt;
	}
}
