package com.hbm.main.tileentity.network.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.hbm.inventory.container.ContainerFCSConsole;
import com.hbm.inventory.gui.GUIFCSConsole;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.tileentity.TileEntityTickingBase;

import api.hbm.data.DataDeviceType;
import api.hbm.data.DataNet;
import api.hbm.data.DataPacket;
import api.hbm.data.IDataConnector;
import api.hbm.energy.IEnergyUser;
import api.hbm.energy.IPowerNet;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;

/**
 * Fire Control System Console - Central command center for radar/missile network
 * Connects via Blue Cable (DataNet) to manage SPY-1 radars and missile launchers
 */
public class TileEntityFCSConsole extends TileEntityTickingBase implements ITickable, IDataConnector, IEnergyUser {

	private UUID deviceId;
	private DataNet dataNet;

	// Power management
	public long power = 0;
	public static final long maxPower = 100000;
	public static final long powerConsumption = 100; // HE per tick

	// Network tracking
	private List<UUID> connectedRadars = new ArrayList<>();
	private List<UUID> connectedLaunchers = new ArrayList<>();
	// Device names for GUI display (synced to client)
	private java.util.Map<UUID, String> deviceNames = new java.util.HashMap<>();
	// Device status data (synced to client for GUI display)
	private java.util.Map<UUID, DeviceStatusData> deviceStatus = new java.util.HashMap<>();
	// Monitor assignments (monitor index 0-4 -> device UUID) - persisted across GUI open/close
	private java.util.Map<Integer, UUID> monitorAssignments = new java.util.HashMap<>();
	private List<RadarTarget> trackedTargets = new ArrayList<>();

	// Radar contact storage (device ID -> list of contacts)
	private java.util.Map<UUID, java.util.List<RadarContactData>> radarContacts = new java.util.HashMap<>();
	// Server-side tracked SM-6 missile contacts - visible on radar regardless of SPY-1 detection range
	private java.util.List<RadarContactData> trackedMissileContacts = new java.util.ArrayList<>();
	// Track number management
	private int nextTrackNumber = 1;
	private java.util.Map<Integer, String> trackNumbers = new java.util.HashMap<>(); // entityId -> "T001"

	// Killed non-missile contacts shown as × "lost" markers on the radar
	private java.util.List<LostContactData> lostContacts = new java.util.ArrayList<>();

	// Operating state
	private boolean isActive = false;
	private String operatingMode = "SURVEILLANCE";

	// === SPG-62 INTEGRATION ===
	// Track which targets are currently being illuminated by SPG-62
	private java.util.Set<Integer> illuminatedEntityIds = new java.util.HashSet<>();
	// Current designated target for SPG-62
	private Integer designatedTargetId = null;

	// === LAUNCH PAD INTEGRATION ===
	// Track connected launch pads and their missile inventory
	private java.util.Map<UUID, LaunchPadInfo> launchPads = new java.util.HashMap<>();

	/**
	 * Stores launch pad information for missile control
	 */
	public static class LaunchPadInfo {
		public UUID padId;
		public String padName;
		public String missileType;  // "SM-6", "Empty", etc.
		public long power;
		public boolean readyToFire;
		public net.minecraft.util.math.BlockPos position;

		public LaunchPadInfo() {
			this.missileType = "Empty";
			this.readyToFire = false;
		}

		public LaunchPadInfo(UUID padId, String padName, String missileType, long power, boolean ready, net.minecraft.util.math.BlockPos pos) {
			this.padId = padId;
			this.padName = padName;
			this.missileType = missileType;
			this.power = power;
			this.readyToFire = ready;
			this.position = pos;
		}
	}

	/**
	 * Stores device status information for client-side GUI display
	 */
	public static class DeviceStatusData {
		public DataDeviceType deviceType;
		public boolean online;
		public String mode;
		public long power;
		public int contactCount;  // For radars
		public String missileType;  // For launchers

		public DeviceStatusData() {
			this.deviceType = DataDeviceType.FCS_CONSOLE;
			this.online = false;
			this.mode = "";
			this.power = 0;
			this.contactCount = 0;
			this.missileType = "NONE";
		}

		public DeviceStatusData(DataDeviceType type, boolean online, String mode, long power, int contactCount, String missileType) {
			this.deviceType = type;
			this.online = online;
			this.mode = mode;
			this.power = power;
			this.contactCount = contactCount;
			this.missileType = missileType;
		}
	}

	/**
	 * Stores radar contact data for display in radar widget
	 */
	public static class RadarContactData {
		public double x, y, z;              // World coordinates
		public double distance;             // Range in blocks
		public double azimuth;              // Horizontal angle (degrees)
		public double elevation;            // Vertical angle (degrees)
		public double signalStrength;       // 0.0 to 1.0
		public double closureRate;          // Relative velocity (blocks/tick)
		public double velocityX, velocityY, velocityZ;  // Velocity vector (blocks/tick)
		public int entityId;                // Entity ID for tracking
		public String trackNumber;          // "T001", "T002", etc.
		public long lastUpdateTime;         // Game tick of last update
		public boolean isMissile;           // True if this contact is a friendly missile

		public RadarContactData() {}

		public RadarContactData(double x, double y, double z, double distance, double azimuth,
		                        double elevation, double signalStrength, double closureRate,
		                        double velocityX, double velocityY, double velocityZ,
		                        int entityId, String trackNum, long updateTime, boolean isMissile) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.distance = distance;
			this.azimuth = azimuth;
			this.elevation = elevation;
			this.signalStrength = signalStrength;
			this.closureRate = closureRate;
			this.velocityX = velocityX;
			this.velocityY = velocityY;
			this.velocityZ = velocityZ;
			this.entityId = entityId;
			this.isMissile = isMissile;
			this.trackNumber = trackNum;
			this.lastUpdateTime = updateTime;
		}

		public NBTTagCompound writeToNBT() {
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setDouble("x", x);
			nbt.setDouble("y", y);
			nbt.setDouble("z", z);
			nbt.setDouble("distance", distance);
			nbt.setDouble("azimuth", azimuth);
			nbt.setDouble("elevation", elevation);
			nbt.setDouble("signalStrength", signalStrength);
			nbt.setDouble("closureRate", closureRate);
			nbt.setDouble("velocityX", velocityX);
			nbt.setDouble("velocityY", velocityY);
			nbt.setDouble("velocityZ", velocityZ);
			nbt.setInteger("entityId", entityId);
			nbt.setString("trackNumber", trackNumber);
			nbt.setLong("lastUpdateTime", lastUpdateTime);
			nbt.setBoolean("isMissile", isMissile);
			return nbt;
		}

		public static RadarContactData readFromNBT(NBTTagCompound nbt) {
			RadarContactData data = new RadarContactData();
			data.x = nbt.getDouble("x");
			data.y = nbt.getDouble("y");
			data.z = nbt.getDouble("z");
			data.distance = nbt.getDouble("distance");
			data.azimuth = nbt.getDouble("azimuth");
			data.elevation = nbt.getDouble("elevation");
			data.signalStrength = nbt.getDouble("signalStrength");
			data.closureRate = nbt.getDouble("closureRate");
			data.velocityX = nbt.getDouble("velocityX");
			data.velocityY = nbt.getDouble("velocityY");
			data.velocityZ = nbt.getDouble("velocityZ");
			data.entityId = nbt.getInteger("entityId");
			data.trackNumber = nbt.getString("trackNumber");
			data.lastUpdateTime = nbt.getLong("lastUpdateTime");
			data.isMissile = nbt.getBoolean("isMissile");
			return data;
		}
	}

	/**
	 * Stores information about a contact that was killed (entity died while being tracked).
	 * Displayed on the radar as a × mark + "lost" text for DISPLAY_TICKS game ticks.
	 */
	public static class LostContactData {
		public static final int DISPLAY_TICKS = 200;

		public double x, y, z;         // Last known world coordinates
		public String trackNumber;      // e.g. "T001"
		public int entityId;
		public long lostAtTick;         // Game tick when the kill was detected

		public LostContactData() {}

		public LostContactData(double x, double y, double z, String trackNumber, int entityId, long lostAtTick) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.trackNumber = trackNumber;
			this.entityId = entityId;
			this.lostAtTick = lostAtTick;
		}

		public NBTTagCompound writeToNBT() {
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setDouble("x", x);
			nbt.setDouble("y", y);
			nbt.setDouble("z", z);
			nbt.setString("trackNumber", trackNumber);
			nbt.setInteger("entityId", entityId);
			nbt.setLong("lostAtTick", lostAtTick);
			return nbt;
		}

		public static LostContactData readFromNBT(NBTTagCompound nbt) {
			LostContactData d = new LostContactData();
			d.x = nbt.getDouble("x");
			d.y = nbt.getDouble("y");
			d.z = nbt.getDouble("z");
			d.trackNumber = nbt.getString("trackNumber");
			d.entityId = nbt.getInteger("entityId");
			d.lostAtTick = nbt.getLong("lostAtTick");
			return d;
		}
	}

	public TileEntityFCSConsole() {
		this.deviceId = UUID.randomUUID();
	}

	@Override
	public String getInventoryName() {
		return "FCS Console";
	}

	@Override
	public void update() {
		if (!world.isRemote) {
			// Update power connections
			this.updateStandardConnections(world, pos);

			// 200-tick diagnostic state dump
			if (world.getTotalWorldTime() % 200 == 0) {
				System.out.println("[FCS DIAG] tick=" + world.getTotalWorldTime()
					+ " power=" + power + "/" + maxPower
					+ " isActive=" + isActive()
					+ " dataNet=" + (dataNet != null ? "#" + dataNet.getNetId() + " subs=" + dataNet.getSubscribers().size() : "null")
					+ " radars=" + connectedRadars.size());
			}

			// Try to connect to DataNet if not connected (check every 20 ticks)
			if (dataNet == null && world.getTotalWorldTime() % 20 == 0) {
				getDataNet();
			}

			// Check power and set active state
			if (power >= powerConsumption) {
				power -= powerConsumption;
				isActive = true;

				// Process data from network every tick
				processDataNetUpdates();

				// Update SM-6 missile tracking (every 5 ticks = 4Hz)
				if (world.getTotalWorldTime() % 5 == 0) {
					updateTrackedMissiles();
				}

				// Update Launch Pad information (every 20 ticks)
				if (world.getTotalWorldTime() % 20 == 0) {
					updateLaunchPadInfo();
				}

				// Expire old lost-contact markers (every 40 ticks)
				if (world.getTotalWorldTime() % 40 == 0 && !lostContacts.isEmpty()) {
					final long now = world.getTotalWorldTime();
					lostContacts.removeIf(lc -> (now - lc.lostAtTick) > LostContactData.DISPLAY_TICKS);
				}
			} else {
				isActive = false;
			}
		}
	}

	/**
	 * Process incoming data packets from DataNet
	 */
	private void processDataNetUpdates() {
		if (dataNet == null || dataNet.isEmpty())
			return;

		// DataNet will call receiveData() when packets arrive
		// This method can handle periodic polling if needed
	}

	/**
	 * Server-side scan: find all live SM-6 missiles in the world and store their
	 * positions as tracked contacts. This runs every 5 ticks and uses the server
	 * world entity list which is not limited by client render/chunk load distance.
	 * Results are synced to the client via the normal NBT update packet mechanism.
	 */
	private void updateTrackedMissiles() {
		java.util.List<com.hbm.entity.missile.EntityMissileSM6> missiles =
			world.getEntities(com.hbm.entity.missile.EntityMissileSM6.class, e -> e != null && !e.isDead);

		trackedMissileContacts.clear();
		for (com.hbm.entity.missile.EntityMissileSM6 sm6 : missiles) {
			double dx = sm6.posX - pos.getX();
			double dz = sm6.posZ - pos.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);

			// Assign a stable track number for this missile entity
			int entityId = sm6.getEntityId();
			String trackNum = trackNumbers.get(entityId);
			if (trackNum == null) {
				trackNum = "M" + String.format("%03d", entityId % 1000);
				trackNumbers.put(entityId, trackNum);
			}

			RadarContactData contact = new RadarContactData(
				sm6.posX, sm6.posY, sm6.posZ,
				dist,
				0.0, 0.0, 1.0, 0.0,
				sm6.motionX * 20.0, sm6.motionY * 20.0, sm6.motionZ * 20.0,
				entityId,
				trackNum,
				world.getTotalWorldTime(),
				true  // isMissile = true - renders as blue marker on GUI
			);
			trackedMissileContacts.add(contact);
		}

		// Sync to client
		markDirty();
		net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
		world.notifyBlockUpdate(pos, state, state, 3);
	}

	/**
	 * Update LaunchPad information from connected launchers.
	 *
	 * World-reload safety: After reload, LaunchPad may not yet have rejoined DataNet
	 * (LaunchPad scans every 100 ticks, FCS Console every 40 ticks).  If the pad is
	 * not found among current subscribers we keep the last known entry from either the
	 * previous tick or the deviceStatus map (populated by STATUS_UPDATE broadcasts).
	 * This prevents a window where the GUI shows 0 missiles immediately after reload.
	 */
	private void updateLaunchPadInfo() {
		if (dataNet == null)
			return;

		// Build a fresh map; retain cached entries for pads not yet reconnected.
		java.util.Map<UUID, LaunchPadInfo> updated = new java.util.HashMap<>();

		for (UUID launcherId : connectedLaunchers) {
			boolean foundSubscriber = false;

			// Primary: live TileEntity subscriber (most up-to-date)
			for (IDataConnector subscriber : dataNet.getSubscribers()) {
				if (subscriber.getDeviceId().equals(launcherId) &&
				    subscriber instanceof com.hbm.main.tileentity.bomb.TileEntityLaunchPad) {

					com.hbm.main.tileentity.bomb.TileEntityLaunchPad pad =
						(com.hbm.main.tileentity.bomb.TileEntityLaunchPad) subscriber;

					updated.put(launcherId, new LaunchPadInfo(
						launcherId,
						pad.getDeviceName(),
						pad.getMissileType(),   // registry name e.g. "hbm:missile_sm6"
						pad.getPower(),
						pad.isReadyToFire(),
						pad.getPos()
					));
					foundSubscriber = true;
					break;
				}
			}

			if (!foundSubscriber) {
				// Fallback 1: last STATUS_UPDATE packet (contains registry-name missileType)
				DeviceStatusData status = deviceStatus.get(launcherId);
				if (status != null) {
					String name = deviceNames.getOrDefault(launcherId, "LaunchPad");
					boolean ready = "READY".equals(status.mode);
					updated.put(launcherId, new LaunchPadInfo(
						launcherId, name, status.missileType, status.power, ready, null));
				} else {
					// Fallback 2: keep cached entry from previous tick (e.g. loaded from NBT)
					LaunchPadInfo cached = launchPads.get(launcherId);
					if (cached != null) {
						updated.put(launcherId, cached);
					}
				}
			}
		}

		launchPads = updated;
	}

	// ========== IDataConnector Implementation ==========

	@Override
	public void receiveData(DataPacket packet) {
		if (packet == null)
			return;

		switch (packet.getType()) {
		case RADAR_SCAN_DATA:
			handleRadarScanData(packet);
			break;
		case STATUS_UPDATE:
			handleStatusUpdate(packet);
			break;
		case DEVICE_RESPONSE:
			handleDeviceResponse(packet);
			break;
		case ILLUMINATION_DATA:
			handleIlluminationData(packet);
			break;
		default:
			break;
		}
	}

	private void handleRadarScanData(DataPacket packet) {
		// Parse radar scan data and update tracked targets
		NBTTagCompound data = packet.getData();
		UUID sourceDevice = packet.getSourceDevice();

		// Read contact count
		int contactCount = data.getInteger("ContactCount");

		java.util.List<RadarContactData> contacts = new java.util.ArrayList<>();

		// Read each contact
		for (int i = 0; i < contactCount; i++) {
			NBTTagCompound contactNBT = data.getCompoundTag("Contact_" + i);

			// Get or assign track number
			int entityId = contactNBT.getInteger("entityId");
			String trackNum = trackNumbers.get(entityId);
			if (trackNum == null) {
				trackNum = String.format("T%03d", nextTrackNumber++);
				trackNumbers.put(entityId, trackNum);
				System.out.println("[FCS Console RECEIVE] Assigned track number " + trackNum + " to entity " + entityId);
			}

			// Create contact data with velocity vector and missile flag
			RadarContactData contact = new RadarContactData(
				contactNBT.getDouble("x"),
				contactNBT.getDouble("y"),
				contactNBT.getDouble("z"),
				contactNBT.getDouble("distance"),
				contactNBT.getDouble("azimuth"),
				contactNBT.getDouble("elevation"),
				contactNBT.getDouble("signalStrength"),
				contactNBT.getDouble("closureRate"),
				contactNBT.getDouble("velocityX"),
				contactNBT.getDouble("velocityY"),
				contactNBT.getDouble("velocityZ"),
				entityId,
				trackNum,
				world.getTotalWorldTime(),
				contactNBT.getBoolean("isMissile")  // Read missile flag
			);

			contacts.add(contact);
		}

		// Before replacing old contacts, detect killed non-missile contacts.
		// A contact is "killed" when it disappears from radar AND its entity is dead or gone.
		java.util.List<RadarContactData> oldContacts = radarContacts.get(sourceDevice);
		if (oldContacts != null && !world.isRemote) {
			for (RadarContactData old : oldContacts) {
				if (old.isMissile) continue; // SM-6 missiles use their own tracking
				// Check if this contact is still present in the new scan
				boolean stillTracked = false;
				for (RadarContactData newC : contacts) {
					if (newC.entityId == old.entityId) {
						stillTracked = true;
						break;
					}
				}
				if (!stillTracked) {
					// Contact dropped off radar - check if entity is actually dead
					Entity ent = world.getEntityByID(old.entityId);
					if (ent == null || ent.isDead) {
						// Entity is gone/dead - record as killed
						boolean alreadyLost = false;
						for (LostContactData lc : lostContacts) {
							if (lc.entityId == old.entityId) { alreadyLost = true; break; }
						}
						if (!alreadyLost) {
							lostContacts.add(new LostContactData(
								old.x, old.y, old.z, old.trackNumber, old.entityId,
								world.getTotalWorldTime()));
							System.out.println("[FCS Console KILL] " + old.trackNumber +
								" (ID: " + old.entityId + ") KILLED - lost marker added");
						}
					}
				}
			}
		}

		// Store contacts
		radarContacts.put(sourceDevice, contacts);

		// Update contact count in device status
		DeviceStatusData status = deviceStatus.get(sourceDevice);
		if (status != null) {
			status.contactCount = contacts.size();
		}

		// Sync to client
		if (!world.isRemote) {
			markDirty();
			net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
			world.notifyBlockUpdate(pos, state, state, 3);
		}
	}

	private void handleStatusUpdate(DataPacket packet) {
		// Update device status (radar/launcher online/offline)
		NBTTagCompound data = packet.getData();
		UUID sourceDevice = packet.getSourceDevice();

		System.out.println("[FCS Console DEBUG] Received STATUS_UPDATE from " + sourceDevice);
		System.out.println("[FCS Console DEBUG] DeviceType: " + data.getString("DeviceType") + ", Online: " + data.getBoolean("Online"));

		DataDeviceType deviceType = DataDeviceType.valueOf(data.getString("DeviceType"));
		boolean online = data.getBoolean("Online");

		boolean listChanged = false;

		// Get device name from packet (instead of querying DataNet)
		String deviceName = data.getString("DeviceName");
		if (deviceName == null || deviceName.isEmpty()) {
			deviceName = "Unknown Device";
		}

		// Store device status data for GUI display
		String mode = data.hasKey("Mode") ? data.getString("Mode") : "";
		long power = data.hasKey("Power") ? data.getLong("Power") : 0;
		int contactCount = data.hasKey("ContactCount") ? data.getInteger("ContactCount") : 0;
		String missileType = data.hasKey("MissileType") ? data.getString("MissileType") : "NONE";

		DeviceStatusData status = new DeviceStatusData(deviceType, online, mode, power, contactCount, missileType);
		deviceStatus.put(sourceDevice, status);

		if (deviceType == DataDeviceType.RADAR_PHASED_ARRAY) {
			if (online && !connectedRadars.contains(sourceDevice)) {
				connectedRadars.add(sourceDevice);
				deviceNames.put(sourceDevice, deviceName);
				System.out.println("[FCS Console DEBUG] Added radar: " + sourceDevice + " (total: " + connectedRadars.size() + ")");
				listChanged = true;
			} else if (!online) {
				if (connectedRadars.remove(sourceDevice)) {
					deviceNames.remove(sourceDevice);
					deviceStatus.remove(sourceDevice);
					radarContacts.remove(sourceDevice);  // Clear stale contacts so count resets to 0
					System.out.println("[FCS Console DEBUG] Removed radar: " + sourceDevice);
					listChanged = true;
				}
			}
		} else if (deviceType == DataDeviceType.MISSILE_LAUNCHER) {
			if (online && !connectedLaunchers.contains(sourceDevice)) {
				connectedLaunchers.add(sourceDevice);
				deviceNames.put(sourceDevice, deviceName);
				System.out.println("[FCS Console DEBUG] Added launcher: " + sourceDevice);
				listChanged = true;
			} else if (!online) {
				if (connectedLaunchers.remove(sourceDevice)) {
					deviceNames.remove(sourceDevice);
					deviceStatus.remove(sourceDevice);
					System.out.println("[FCS Console DEBUG] Removed launcher: " + sourceDevice);
					listChanged = true;
				}
			}
		}

		// Notify clients when list changes OR device status updates
		// Always sync to client so GUI can show current device status
		if (!world.isRemote) {
			markDirty();
			net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
			world.notifyBlockUpdate(pos, state, state, 3);
		}
	}

	private void handleIlluminationData(DataPacket packet) {
		// Handle illumination data from SPG-62
		NBTTagCompound data = packet.getData();
		UUID sourceDevice = packet.getSourceDevice();

		// Check if SPG-62 is locked on target
		boolean lockedOn = data.getBoolean("lockedOn");
		boolean illuminating = data.getBoolean("illuminating");

		System.out.println("[FCS Console ILLUMINATION] Received from SPG-62 " + sourceDevice +
			" | Illuminating: " + illuminating + " | LockedOn: " + lockedOn);

		// Get entity ID from packet (more reliable than position matching)
		int entityId = data.getInteger("entityId");

		if (lockedOn) {
			// SPG-62 is locked on target - add to illuminated set
			illuminatedEntityIds.add(entityId);
			System.out.println("[FCS Console ILLUMINATION] Target " + entityId + " is LOCKED ON by SPG-62");
		} else {
			// SPG-62 not locked on (either not illuminating or not aimed correctly)
			// Remove from illuminated set
			illuminatedEntityIds.remove(entityId);
			if (!illuminating) {
				System.out.println("[FCS Console ILLUMINATION] Target " + entityId + " - SPG-62 not illuminating (cycle off)");
			} else {
				System.out.println("[FCS Console ILLUMINATION] Target " + entityId + " - SPG-62 rotating to target");
			}
		}

		// Sync to client for GUI display
		if (!world.isRemote) {
			markDirty();
			net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
			world.notifyBlockUpdate(pos, state, state, 3);
		}
	}

	private void handleDeviceResponse(DataPacket packet) {
		// Handle responses to queries
	}

	public DataNet getDataNet() {
		// For large multiblocks, actively scan for cables if not connected
		// FCS Console is 5×4 multiblock - cables might not be adjacent to core
		if (dataNet == null && !world.isRemote) {
			scanForCables();
		}
		return dataNet;
	}

	public void setDataNet(DataNet net) {
		this.dataNet = net;
	}

	@Override
	public void invalidate() {
		if (!world.isRemote && dataNet != null) {
			dataNet.removeSubscriber(this);
			dataNet = null;
			System.out.println("[FCS Console] invalidate() — unsubscribed from DataNet");
		}
		super.invalidate();
	}

	@Override
	public void onChunkUnload() {
		if (!world.isRemote && dataNet != null) {
			dataNet.removeSubscriber(this);
			dataNet = null;
			System.out.println("[FCS Console] onChunkUnload() — unsubscribed from DataNet");
		}
		super.onChunkUnload();
	}

	/**
	 * Scan for cables in the multiblock footprint and connect
	 * FCS Console is 5 wide × 4 tall
	 */
	private void scanForCables() {
		// FCS Console multiblock: [Up:3, Down:0, North:0, South:0, West:2, East:2]
		// Scan area with margin: X=[-3,+3], Y=[-1,+4], Z=[-2,+2]
		int coreX = pos.getX();
		int coreY = pos.getY();
		int coreZ = pos.getZ();

		System.out.println("[FCS SCAN] Starting cable scan from core position: " + pos);
		System.out.println("[FCS SCAN] Multiblock scan area: X=[" + (coreX-3) + " to " + (coreX+3) + "], Y=[" + (coreY-1) + " to " + (coreY+4) + "], Z=[" + (coreZ-2) + " to " + (coreZ+2) + "]");

		int blocksChecked = 0;
		int conductorsFound = 0;

		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -1; dy <= 4; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) continue; // Skip core

					net.minecraft.util.math.BlockPos checkPos = new net.minecraft.util.math.BlockPos(
						coreX + dx, coreY + dy, coreZ + dz);

					net.minecraft.tileentity.TileEntity te = world.getTileEntity(checkPos);
					blocksChecked++;

					if (te instanceof api.hbm.data.IDataConductor) {
						conductorsFound++;
						api.hbm.data.IDataConductor conductor = (api.hbm.data.IDataConductor) te;

						System.out.println("[FCS SCAN] Found IDataConductor at offset (" + dx + ", " + dy + ", " + dz + ")");

						if (conductor.getDataNet() != null) {
							dataNet = conductor.getDataNet();
							dataNet.addSubscriber(this);
							System.out.println("[FCS Console] ✓ Connected to DataNet #" + dataNet.getNetId() +
								" via cable at " + checkPos);
							return;
						} else {
							System.out.println("[FCS SCAN] Cable has NULL DataNet at " + checkPos);
						}
					}
				}
			}
		}

		System.out.println("[FCS SCAN] Scan complete. Blocks checked: " + blocksChecked +
			", Conductors found: " + conductorsFound);
		System.out.println("[FCS Console] ✗ No DataNet cables with valid network found in multiblock area");
	}

	@Override
	public boolean canConnect(EnumFacing dir) {
		return dir != EnumFacing.DOWN; // Down is for power
	}

	@Override
	public DataDeviceType getDeviceType() {
		return DataDeviceType.FCS_CONSOLE;
	}

	@Override
	public UUID getDeviceId() {
		return this.deviceId;
	}

	@Override
	public String getDeviceName() {
		return "FCS Console @ " + pos.toString();
	}

	@Override
	public boolean isActive() {
		// Console is active if it has sufficient power and is not invalid
		return !this.isInvalid() && isActive && power >= powerConsumption;
	}

	// ========== IEnergyUser Implementation ==========

	@Override
	public long getPower() {
		return power;
	}

	@Override
	public long getMaxPower() {
		return maxPower;
	}

	@Override
	public void setPower(long power) {
		this.power = power;
	}

	// ========== GUI Provider ==========

	public Container provideContainer(int ID, EntityPlayer player, World world, int x, int y, int z) {
		return new ContainerFCSConsole(player.inventory, this);
	}

	public GuiScreen provideGUI(int ID, EntityPlayer player, World world, int x, int y, int z) {
		return new GUIFCSConsole(player.inventory, this);
	}

	public boolean isUsableByPlayer(EntityPlayer player) {
		if (world.getTileEntity(pos) != this) {
			return false;
		} else {
			return player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
		}
	}

	// ========== NBT ==========

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		this.power = nbt.getLong("power");

		// Read deviceId with fallback for empty string
		String deviceIdStr = nbt.getString("deviceId");
		if (deviceIdStr != null && !deviceIdStr.isEmpty()) {
			try {
				this.deviceId = UUID.fromString(deviceIdStr);
			} catch (IllegalArgumentException e) {
				// Invalid UUID, generate new one
				this.deviceId = UUID.randomUUID();
				System.out.println("[FCS Console] Invalid deviceId in NBT, generated new: " + this.deviceId);
			}
		} else {
			// No deviceId in NBT, generate new one
			this.deviceId = UUID.randomUUID();
			System.out.println("[FCS Console] No deviceId in NBT, generated new: " + this.deviceId);
		}

		this.isActive = nbt.getBoolean("isActive");
		this.operatingMode = nbt.getString("operatingMode");

		// Read connected devices lists for client sync
		connectedRadars.clear();
		int radarCount = nbt.getInteger("radarCount");
		for (int i = 0; i < radarCount; i++) {
			String radarIdStr = nbt.getString("radar_" + i);
			if (radarIdStr != null && !radarIdStr.isEmpty()) {
				try {
					connectedRadars.add(UUID.fromString(radarIdStr));
				} catch (IllegalArgumentException e) {
					// Skip invalid UUID
				}
			}
		}

		connectedLaunchers.clear();
		int launcherCount = nbt.getInteger("launcherCount");
		for (int i = 0; i < launcherCount; i++) {
			String launcherIdStr = nbt.getString("launcher_" + i);
			if (launcherIdStr != null && !launcherIdStr.isEmpty()) {
				try {
					connectedLaunchers.add(UUID.fromString(launcherIdStr));
				} catch (IllegalArgumentException e) {
					// Skip invalid UUID
				}
			}
		}

		// Read device names map
		deviceNames.clear();
		int deviceNameCount = nbt.getInteger("deviceNameCount");
		for (int i = 0; i < deviceNameCount; i++) {
			String devIdStr = nbt.getString("deviceNameId_" + i);
			String deviceName = nbt.getString("deviceNameName_" + i);
			if (devIdStr != null && !devIdStr.isEmpty() && deviceName != null) {
				try {
					deviceNames.put(UUID.fromString(devIdStr), deviceName);
				} catch (IllegalArgumentException e) {
					// Skip invalid UUID
				}
			}
		}

		// Read device status map
		deviceStatus.clear();
		int deviceStatusCount = nbt.getInteger("deviceStatusCount");
		for (int i = 0; i < deviceStatusCount; i++) {
			String statusIdStr = nbt.getString("deviceStatusId_" + i);
			if (statusIdStr != null && !statusIdStr.isEmpty()) {
				try {
					UUID deviceId = UUID.fromString(statusIdStr);
					DataDeviceType deviceType = DataDeviceType.valueOf(nbt.getString("deviceStatusType_" + i));
					boolean online = nbt.getBoolean("deviceStatusOnline_" + i);
					String mode = nbt.getString("deviceStatusMode_" + i);
					long power = nbt.getLong("deviceStatusPower_" + i);
					int contactCount = nbt.getInteger("deviceStatusContacts_" + i);
					String missileType = nbt.hasKey("deviceStatusMissileType_" + i) ? nbt.getString("deviceStatusMissileType_" + i) : "NONE";

					DeviceStatusData status = new DeviceStatusData(deviceType, online, mode, power, contactCount, missileType);
					deviceStatus.put(deviceId, status);
				} catch (IllegalArgumentException e) {
					// Skip invalid UUID or enum
				}
			}
		}

		// Read monitor assignments
		monitorAssignments.clear();
		int monitorAssignmentCount = nbt.getInteger("monitorAssignmentCount");
		for (int i = 0; i < monitorAssignmentCount; i++) {
			int monitorIndex = nbt.getInteger("monitorIndex_" + i);
			String monDevIdStr = nbt.getString("monitorDeviceId_" + i);
			if (monDevIdStr != null && !monDevIdStr.isEmpty()) {
				try {
					monitorAssignments.put(monitorIndex, UUID.fromString(monDevIdStr));
				} catch (IllegalArgumentException e) {
					// Skip invalid UUID
				}
			}
		}

		// Read radar contacts
		radarContacts.clear();
		int deviceCount = nbt.getInteger("radarContactDeviceCount");
		for (int d = 0; d < deviceCount; d++) {
			String devIdStr = nbt.getString("radarContactDevice_" + d);
			if (devIdStr != null && !devIdStr.isEmpty()) {
				try {
					UUID deviceId = UUID.fromString(devIdStr);
					int contactCount = nbt.getInteger("radarContactCount_" + d);
					java.util.List<RadarContactData> contacts = new java.util.ArrayList<>();

					for (int i = 0; i < contactCount; i++) {
						NBTTagCompound contactNBT = nbt.getCompoundTag("radarContact_" + d + "_" + i);
						contacts.add(RadarContactData.readFromNBT(contactNBT));
					}
					radarContacts.put(deviceId, contacts);
				} catch (IllegalArgumentException e) {
					// Skip invalid UUID
				}
			}
		}

		// Read track numbers
		trackNumbers.clear();
		int trackCount = nbt.getInteger("trackNumberCount");
		for (int i = 0; i < trackCount; i++) {
			int entityId = nbt.getInteger("trackEntityId_" + i);
			String trackNum = nbt.getString("trackNumber_" + i);
			trackNumbers.put(entityId, trackNum);
		}
		nextTrackNumber = nbt.getInteger("nextTrackNumber");
		if (nextTrackNumber == 0) {
			nextTrackNumber = 1; // Default if not set
		}

		// Read illuminated entity IDs (for SPG-62 integration)
		illuminatedEntityIds.clear();
		int illuminatedCount = nbt.getInteger("illuminatedCount");
		for (int i = 0; i < illuminatedCount; i++) {
			int entityId = nbt.getInteger("illuminated_" + i);
			illuminatedEntityIds.add(entityId);
		}

		// Read launch pad info (for missile control)
		launchPads.clear();
		int launchPadCount = nbt.getInteger("launchPadCount");
		for (int i = 0; i < launchPadCount; i++) {
			try {
				UUID padId = UUID.fromString(nbt.getString("launchPadId_" + i));
				String padName = nbt.getString("launchPadName_" + i);
				String missileType = nbt.getString("launchPadMissile_" + i);
				long power = nbt.getLong("launchPadPower_" + i);
				boolean ready = nbt.getBoolean("launchPadReady_" + i);
				net.minecraft.util.math.BlockPos pos = null;
				if (nbt.hasKey("launchPadX_" + i)) {
					pos = new net.minecraft.util.math.BlockPos(
						nbt.getInteger("launchPadX_" + i),
						nbt.getInteger("launchPadY_" + i),
						nbt.getInteger("launchPadZ_" + i));
				}
				LaunchPadInfo info = new LaunchPadInfo(padId, padName, missileType, power, ready, pos);
				launchPads.put(padId, info);
			} catch (IllegalArgumentException e) {
				// Skip invalid UUID
			}
		}

		// Read tracked SM-6 missile contacts (server-side, synced to client)
		trackedMissileContacts.clear();
		int missileTrackCount = nbt.getInteger("trackedMissileCount");
		for (int i = 0; i < missileTrackCount; i++) {
			NBTTagCompound missileNBT = nbt.getCompoundTag("trackedMissile_" + i);
			trackedMissileContacts.add(RadarContactData.readFromNBT(missileNBT));
		}

		// Read lost contacts (× "killed" markers)
		lostContacts.clear();
		int lostContactCount = nbt.getInteger("lostContactCount");
		for (int i = 0; i < lostContactCount; i++) {
			NBTTagCompound lostNBT = nbt.getCompoundTag("lostContact_" + i);
			lostContacts.add(LostContactData.readFromNBT(lostNBT));
		}

		// ---------------------------------------------------------------
		// Clear runtime-only state so world-join starts clean.
		//
		// connectedRadars / connectedLaunchers / radarContacts / deviceStatus
		// are transient runtime data re-populated from DataNet within ~40
		// ticks via STATUS_UPDATE and RADAR_SCAN_DATA.  Retaining stale NBT
		// values causes two visible bugs:
		//   1. FCS GUI shows frozen positions from the previous session.
		//   2. When SPY-1 briefly sends Online=false at startup (before its
		//      power cables reconnect), FCS purges those stale contacts and
		//      the GUI goes blank.
		// Clearing here ensures a clean slate every time the world is loaded.
		// monitorAssignments and trackNumbers are kept (user preference data).
		//
		// IMPORTANT: Only clear on the SERVER side (disk load).
		// On the CLIENT, readFromNBT() is called by onDataPacket() and
		// handleUpdateTag() which deliver LIVE data from the server.
		// Clearing on the client would erase that live data, leaving the
		// GUI permanently blank.
		// world == null means the TE is being deserialized before being
		// placed in the world (always server-side disk load).
		// ---------------------------------------------------------------
		if (world == null || !world.isRemote) {
			connectedRadars.clear();
			connectedLaunchers.clear();
			radarContacts.clear();
			deviceStatus.clear();
			deviceNames.clear();
			launchPads.clear();
			lostContacts.clear();
			System.out.println("[FCS Console] readFromNBT() — runtime state cleared for clean world-join (server-side)");
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		nbt.setLong("power", power);
		nbt.setString("deviceId", deviceId.toString());
		nbt.setBoolean("isActive", isActive);
		nbt.setString("operatingMode", operatingMode);

		// Write connected devices lists for client sync
		nbt.setInteger("radarCount", connectedRadars.size());
		for (int i = 0; i < connectedRadars.size(); i++) {
			nbt.setString("radar_" + i, connectedRadars.get(i).toString());
		}

		nbt.setInteger("launcherCount", connectedLaunchers.size());
		for (int i = 0; i < connectedLaunchers.size(); i++) {
			nbt.setString("launcher_" + i, connectedLaunchers.get(i).toString());
		}

		// Write device names map
		nbt.setInteger("deviceNameCount", deviceNames.size());
		int index = 0;
		for (java.util.Map.Entry<UUID, String> entry : deviceNames.entrySet()) {
			nbt.setString("deviceNameId_" + index, entry.getKey().toString());
			nbt.setString("deviceNameName_" + index, entry.getValue());
			index++;
		}

		// Write device status map
		nbt.setInteger("deviceStatusCount", deviceStatus.size());
		int statusIndex = 0;
		for (java.util.Map.Entry<UUID, DeviceStatusData> entry : deviceStatus.entrySet()) {
			nbt.setString("deviceStatusId_" + statusIndex, entry.getKey().toString());
			DeviceStatusData status = entry.getValue();
			nbt.setString("deviceStatusType_" + statusIndex, status.deviceType.name());
			nbt.setBoolean("deviceStatusOnline_" + statusIndex, status.online);
			nbt.setString("deviceStatusMode_" + statusIndex, status.mode);
			nbt.setLong("deviceStatusPower_" + statusIndex, status.power);
			nbt.setInteger("deviceStatusContacts_" + statusIndex, status.contactCount);
			nbt.setString("deviceStatusMissileType_" + statusIndex, status.missileType);
			statusIndex++;
		}

		// Write monitor assignments
		nbt.setInteger("monitorAssignmentCount", monitorAssignments.size());
		int assignIndex = 0;
		for (java.util.Map.Entry<Integer, UUID> entry : monitorAssignments.entrySet()) {
			nbt.setInteger("monitorIndex_" + assignIndex, entry.getKey());
			nbt.setString("monitorDeviceId_" + assignIndex, entry.getValue().toString());
			assignIndex++;
		}

		// Write radar contacts
		nbt.setInteger("radarContactDeviceCount", radarContacts.size());
		int deviceIdx = 0;
		for (java.util.Map.Entry<UUID, java.util.List<RadarContactData>> entry : radarContacts.entrySet()) {
			nbt.setString("radarContactDevice_" + deviceIdx, entry.getKey().toString());
			java.util.List<RadarContactData> contacts = entry.getValue();
			nbt.setInteger("radarContactCount_" + deviceIdx, contacts.size());

			for (int i = 0; i < contacts.size(); i++) {
				nbt.setTag("radarContact_" + deviceIdx + "_" + i, contacts.get(i).writeToNBT());
			}
			deviceIdx++;
		}

		// Write track numbers
		nbt.setInteger("trackNumberCount", trackNumbers.size());
		int trackIdx = 0;
		for (java.util.Map.Entry<Integer, String> entry : trackNumbers.entrySet()) {
			nbt.setInteger("trackEntityId_" + trackIdx, entry.getKey());
			nbt.setString("trackNumber_" + trackIdx, entry.getValue());
			trackIdx++;
		}
		nbt.setInteger("nextTrackNumber", nextTrackNumber);

		// Write illuminated entity IDs (for SPG-62 integration)
		nbt.setInteger("illuminatedCount", illuminatedEntityIds.size());
		int illuminatedIdx = 0;
		for (Integer entityId : illuminatedEntityIds) {
			nbt.setInteger("illuminated_" + illuminatedIdx, entityId);
			illuminatedIdx++;
		}

		// Write launch pad info (for missile control)
		nbt.setInteger("launchPadCount", launchPads.size());
		int padIdx = 0;
		for (LaunchPadInfo info : launchPads.values()) {
			nbt.setString("launchPadId_" + padIdx, info.padId.toString());
			nbt.setString("launchPadName_" + padIdx, info.padName);
			nbt.setString("launchPadMissile_" + padIdx, info.missileType);
			nbt.setLong("launchPadPower_" + padIdx, info.power);
			nbt.setBoolean("launchPadReady_" + padIdx, info.readyToFire);
			if (info.position != null) {
				nbt.setInteger("launchPadX_" + padIdx, info.position.getX());
				nbt.setInteger("launchPadY_" + padIdx, info.position.getY());
				nbt.setInteger("launchPadZ_" + padIdx, info.position.getZ());
			}
			padIdx++;
		}

		// Write tracked SM-6 missile contacts (server-side, synced to client)
		nbt.setInteger("trackedMissileCount", trackedMissileContacts.size());
		for (int i = 0; i < trackedMissileContacts.size(); i++) {
			nbt.setTag("trackedMissile_" + i, trackedMissileContacts.get(i).writeToNBT());
		}

		// Write lost contacts (× "killed" markers)
		nbt.setInteger("lostContactCount", lostContacts.size());
		for (int i = 0; i < lostContacts.size(); i++) {
			nbt.setTag("lostContact_" + i, lostContacts.get(i).writeToNBT());
		}

		return nbt;
	}

	// ========== Client Sync ==========

	@Override
	public net.minecraft.network.play.server.SPacketUpdateTileEntity getUpdatePacket() {
		NBTTagCompound nbt = new NBTTagCompound();
		this.writeToNBT(nbt);
		return new net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, nbt);
	}

	@Override
	public void onDataPacket(net.minecraft.network.NetworkManager net, net.minecraft.network.play.server.SPacketUpdateTileEntity pkt) {
		this.readFromNBT(pkt.getNbtCompound());
	}

	@Override
	public NBTTagCompound getUpdateTag() {
		NBTTagCompound nbt = super.getUpdateTag();
		this.writeToNBT(nbt);
		return nbt;
	}

	@Override
	public void handleUpdateTag(NBTTagCompound tag) {
		this.readFromNBT(tag);
	}

	// ========== Helper Classes ==========

	/**
	 * Represents a tracked radar target
	 */
	public static class RadarTarget {
		public int x, y, z;
		public int velocityX, velocityZ;
		public String type;
		public long lastUpdate;

		public RadarTarget(int x, int y, int z, String type) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.type = type;
			this.lastUpdate = System.currentTimeMillis();
		}
	}

	// ========== Getters for GUI ==========

	public int getConnectedRadarCount() {
		return connectedRadars.size();
	}

	public int getConnectedLauncherCount() {
		return connectedLaunchers.size();
	}

	public int getTrackedTargetCount() {
		return trackedTargets.size();
	}

	public String getOperatingMode() {
		return operatingMode;
	}

	public double getPowerScaled(int scale) {
		return (power * scale) / (double) maxPower;
	}

	/**
	 * Get list of connected radar UUIDs (for GUI machine list)
	 */
	public List<UUID> getConnectedRadarIds() {
		return new ArrayList<>(connectedRadars);
	}

	/**
	 * Get list of connected launcher UUIDs (for GUI machine list)
	 */
	public List<UUID> getConnectedLauncherIds() {
		return new ArrayList<>(connectedLaunchers);
	}

	/**
	 * Get device name by UUID (for GUI display)
	 */
	public String getDeviceName(UUID deviceId) {
		return deviceNames.getOrDefault(deviceId, "Unknown Device");
	}

	/**
	 * Get device status by UUID (for GUI display)
	 */
	public DeviceStatusData getDeviceStatus(UUID deviceId) {
		return deviceStatus.get(deviceId);
	}

	/**
	 * Get all connected launch pads (for missile control GUI)
	 */
	public List<LaunchPadInfo> getConnectedLaunchPads() {
		return new ArrayList<>(launchPads.values());
	}

	/**
	 * Get launch pad info by UUID
	 */
	public LaunchPadInfo getLaunchPadInfo(UUID padId) {
		return launchPads.get(padId);
	}

	/**
	 * Get monitor assignment (which device is on which monitor)
	 */
	public UUID getMonitorAssignment(int monitorIndex) {
		return monitorAssignments.get(monitorIndex);
	}

	/**
	 * Set monitor assignment and sync to all clients
	 */
	public void setMonitorAssignment(int monitorIndex, UUID deviceId) {
		if (deviceId == null) {
			monitorAssignments.remove(monitorIndex);
		} else {
			monitorAssignments.put(monitorIndex, deviceId);
		}

		// Sync to clients
		if (!world.isRemote) {
			markDirty();
			net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
			world.notifyBlockUpdate(pos, state, state, 3);
		}
	}

	/**
	 * Get all monitor assignments
	 */
	public java.util.Map<Integer, UUID> getMonitorAssignments() {
		return new java.util.HashMap<>(monitorAssignments);
	}

	/**
	 * Get radar contacts for a specific device (for GUI radar display).
	 * Also merges in server-tracked SM-6 missile contacts which are visible
	 * regardless of SPY-1 detection range or client chunk load distance.
	 */
	public java.util.List<RadarContactData> getRadarContacts(UUID deviceId) {
		java.util.List<RadarContactData> contacts = radarContacts.get(deviceId);
		java.util.List<RadarContactData> result = contacts != null ? new java.util.ArrayList<>(contacts) : new java.util.ArrayList<>();

		// Merge tracked SM-6 missile contacts (server-tracked, always visible)
		for (RadarContactData missile : trackedMissileContacts) {
			boolean alreadyTracked = false;
			for (RadarContactData c : result) {
				if (c.entityId == missile.entityId) {
					c.isMissile = true;  // Ensure blue marker if SPY-1 also detected it
					alreadyTracked = true;
					break;
				}
			}
			if (!alreadyTracked) {
				result.add(missile);
			}
		}

		return result;
	}

	/**
	 * Get killed-contact markers for radar display (× "lost" overlays).
	 * Returns a snapshot; the list is managed server-side and synced via NBT.
	 */
	public java.util.List<LostContactData> getLostContacts() {
		return new java.util.ArrayList<>(lostContacts);
	}

	/**
	 * Get block position (for radar widget coordinate calculations)
	 */
	public net.minecraft.util.math.BlockPos getBlockPos() {
		return pos;
	}

	// ========== SPG-62 INTEGRATION METHODS ==========

	/**
	 * Designate a target for SPG-62 illumination
	 * @param entityId Entity ID of the target to illuminate
	 */
	public void designateTargetToSPG62(int entityId) {
		if (world.isRemote) return; // Server-side only

		designatedTargetId = entityId;

		// Find the target in radar contacts
		RadarContactData targetContact = null;
		for (java.util.List<RadarContactData> contacts : radarContacts.values()) {
			for (RadarContactData contact : contacts) {
				if (contact.entityId == entityId) {
					targetContact = contact;
					break;
				}
			}
			if (targetContact != null) break;
		}

		if (targetContact == null) {
			System.out.println("[FCS Console] Failed to designate target " + entityId + " - not found in radar contacts");
			return;
		}

		// Send TARGET_DESIGNATION packet to SPG-62 via DataNet
		// IMPORTANT: Use getDataNet() to get current network, not cached field
		DataNet network = getDataNet();
		if (network != null) {
			NBTTagCompound data = new NBTTagCompound();
			data.setInteger("entityId", entityId);
			data.setDouble("targetX", targetContact.x);
			data.setDouble("targetY", targetContact.y);
			data.setDouble("targetZ", targetContact.z);
			data.setString("trackNumber", targetContact.trackNumber);

			DataPacket packet = new DataPacket(DataPacket.DataPacketType.TARGET_DESIGNATION, deviceId, data);
			network.broadcastData(packet);

			System.out.println("[FCS Console] Designated target " + targetContact.trackNumber +
				" (ID: " + entityId + ") to SPG-62 on DataNet #" + network.getNetId());
		} else {
			System.out.println("[FCS Console] FAILED to designate target - no DataNet connection!");
		}
	}

	/**
	 * Check if a target is currently being illuminated by SPG-62
	 * @param entityId Entity ID to check
	 * @return true if target is being illuminated
	 */
	public boolean isTargetIlluminated(int entityId) {
		return illuminatedEntityIds.contains(entityId);
	}

	/**
	 * Get all currently illuminated target entity IDs
	 * @return Set of entity IDs being illuminated
	 */
	public java.util.Set<Integer> getIlluminatedTargets() {
		return new java.util.HashSet<>(illuminatedEntityIds);
	}

	/**
	 * Launch missile from specified LaunchPad (called by MissileLaunchPacket)
	 * @param launchPadId UUID of the launch pad
	 * @param targetEntityId Target entity ID for missile guidance
	 * @param sarhMode true = SARH (requires illumination), false = ARH (fire-and-forget)
	 */
	public void launchMissile(UUID launchPadId, int targetEntityId, boolean sarhMode) {
		if (world.isRemote) return; // Server-side only

		// Find the launch pad
		LaunchPadInfo padInfo = launchPads.get(launchPadId);
		if (padInfo == null) {
			System.out.println("[FCS MISSILE] Failed to launch - LaunchPad " + launchPadId + " not found");
			return;
		}

		// Verify target is illuminated (only required for SARH mode)
		if (sarhMode && !illuminatedEntityIds.contains(targetEntityId)) {
			System.out.println("[FCS MISSILE] Failed to launch - SARH mode requires illuminated target, but target " + targetEntityId + " not illuminated");
			return;
		}

		// ★ NEW: Get target entity and extract coordinates
		Entity targetEntity = world.getEntityByID(targetEntityId);
		if (targetEntity == null) {
			System.out.println("[FCS MISSILE] Failed to launch - Target entity " + targetEntityId + " not found");
			return;
		}

		// Extract target coordinates
		double targetX = targetEntity.posX;
		double targetY = targetEntity.posY;
		double targetZ = targetEntity.posZ;

		System.out.println("[FCS MISSILE] ★ Sending MISSILE_COMMAND via cable blue to LaunchPad " + launchPadId);
		System.out.println("  Target: " + targetEntity.getName() + " at (" + targetX + ", " + targetY + ", " + targetZ + ")");

		// ★ NEW: Send MISSILE_COMMAND packet via DataNet (cable blue)
		// This allows the launch pad to receive target coordinates BEFORE launching
		if (dataNet != null) {
			NBTTagCompound commandData = new NBTTagCompound();
			commandData.setDouble("TargetX", targetX);
			commandData.setDouble("TargetY", targetY);
			commandData.setDouble("TargetZ", targetZ);
			// Include target velocity so SPY-1 can extrapolate when entity is out of range
			commandData.setDouble("TargetVelX", targetEntity.motionX * 20.0); // blocks/tick → m/s
			commandData.setDouble("TargetVelY", targetEntity.motionY * 20.0);
			commandData.setDouble("TargetVelZ", targetEntity.motionZ * 20.0);
			commandData.setInteger("TargetEntityId", targetEntityId);
			commandData.setBoolean("SarhMode", sarhMode);

			DataPacket missileCommand = new DataPacket(
				DataPacket.DataPacketType.MISSILE_COMMAND,
				this.deviceId,
				launchPadId,  // Target specific launch pad
				commandData
			);

			dataNet.broadcastData(missileCommand);
			System.out.println("[FCS MISSILE] ✓ MISSILE_COMMAND packet sent via cable blue");
			System.out.println("[FCS MISSILE] LaunchPad will execute launch and register missile with SPY-1 automatically");
		}
	}

	/**
	 * Register missile with SPY-1 radar for midcourse command guidance
	 * This allows SPY-1 to provide target updates until the missile enters pitbull mode
	 */
	private void registerMissileWithSPY1(com.hbm.entity.missile.EntityMissileSM6 missile, int targetEntityId) {
		if (dataNet == null) {
			System.out.println("[FCS CONSOLE WARNING] No DataNet connection - cannot register missile with SPY-1");
			return;
		}

		// Find SPY-1 radar in the network
		for (IDataConnector subscriber : dataNet.getSubscribers()) {
			if (subscriber.getDeviceType() == DataDeviceType.RADAR_PHASED_ARRAY &&
			    subscriber instanceof com.hbm.main.tileentity.network.data.TileEntitySPY1) {

				com.hbm.main.tileentity.network.data.TileEntitySPY1 spy1 =
					(com.hbm.main.tileentity.network.data.TileEntitySPY1) subscriber;

				spy1.registerMissile(missile.getDeviceId(), targetEntityId);

				System.out.println("[FCS CONSOLE] ✓ Registered missile " + missile.getDeviceId() +
					" with SPY-1 radar for midcourse guidance");
				return;
			}
		}

		// No SPY-1 found - missile will operate autonomously
		System.out.println("[FCS CONSOLE WARNING] No SPY-1 radar found in DataNet - " +
			"missile will use autonomous guidance only");
	}
}
