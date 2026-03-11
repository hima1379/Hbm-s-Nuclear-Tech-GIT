package com.hbm.main.tileentity.network.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.hbm.entity.missile.EntityMissileSM6;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.tileentity.TileEntityTickingBase;
import com.hbm.radar.PhysicsBasedRadarSystem;
import com.hbm.radar.PhysicsBasedRadarSystem.RadarContact;
import com.hbm.radar.PhysicsBasedRadarSystem.RadarSpec;
import com.hbm.radar.RadarStealthCapability;

import api.hbm.data.DataDeviceType;
import api.hbm.data.DataNet;
import api.hbm.data.DataPacket;
import api.hbm.data.DataPacket.DataPacketType;
import api.hbm.data.IDataConnector;
import api.hbm.energy.IEnergyUser;
import com.hbm.physics.IRCSProvider;
import com.hbm.physics.RadarWavePhysics;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;

/**
 * SPY-1 Phased Array Radar TileEntity
 *
 * Based on real AN/SPY-1 specifications:
 * - S-band operation (3.1-3.5 GHz, center 3.3 GHz)
 * - Passive Electronically Scanned Array (PESA)
 * - Range: 300km for ballistic missile-sized targets
 * - Tracks 100+ targets simultaneously
 * - 360-degree coverage with 4 arrays (electronically scanned, no mechanical rotation)
 *
 * Detects:
 * - All IRCSProvider entities (missiles, aircraft implementing the physics RCS interface)
 * - All airborne/flying entities (entity.isAirBorne || !entity.onGround)
 * - Players (optional)
 * - Considers stealth characteristics (RCS × stealth factor)
 *
 * Power:
 * - Max: 3000000 HE (buffer for ~50 seconds operation)
 * - Consumption: 2900 HE/tick (58 kW average, realistic SPY-1D consumption)
 *
 * Connection:
 * - CableBlue (DataNet) on BOTTOM face only
 * - Sends RADAR_SCAN_DATA to FCS Console
 */
public class TileEntitySPY1 extends TileEntityTickingBase implements ITickable, IDataConnector, IEnergyUser {

	private UUID deviceId;
	private DataNet dataNet;

	// Power management
	public long power = 0;
	public static final long maxPower = 3000000;  // High capacity for phased array (~50s operation)
	public static final long powerConsumption = 2900;  // 58 kW average (realistic SPY-1D)

	// Radar system - 4 fixed phased array faces for 360° coverage
	private PhysicsBasedRadarSystem[] radarSystems = new PhysicsBasedRadarSystem[4];  // N, E, S, W
	private static final String[] ARRAY_NAMES = {"NORTH", "EAST", "SOUTH", "WEST"};
	private static final double[] ARRAY_AZIMUTHS = {0.0, 90.0, 180.0, 270.0};  // Facing directions
	private int radarUpdateTick = 0;
	private static final int RADAR_UPDATE_INTERVAL = 5;  // Update every 5 ticks (0.25s)

	// Operating state
	private boolean isActive = false;
	private String operatingMode = "SEARCH";  // SEARCH, TRACK, STANDBY

	// Scan configuration
	private boolean scanMissiles = true;
	private boolean scanPlayers = false;
	private boolean scanAirborne = true;  // Scan all flying entities

	// Statistics
	private int totalContactsDetected = 0;
	private int lastScanContactCount = 0;

	// Offline grace period — number of consecutive ticks with power < powerConsumption.
	// STATUS_UPDATE with Online=false is only sent once this exceeds OFFLINE_GRACE_TICKS,
	// preventing a world-join startup transient from falsely declaring the radar offline
	// and causing FCS to purge all radar contacts.
	private int offlineTicks = 0;
	private static final int OFFLINE_GRACE_TICKS = 100; // 5 seconds

	// Midcourse guidance tracking
	// Map: SM-6 missile UUID -> target entity ID
	private Map<UUID, Integer> activeMissiles = new HashMap<>();
	// Last known target state: [posX, posY, posZ, velX(m/s), velY(m/s), velZ(m/s), gameTick]
	private Map<UUID, double[]> lastKnownTargetStates = new HashMap<>();
	private int guidanceUpdateTick = 0;
	private static final int GUIDANCE_UPDATE_INTERVAL = 1;  // Update every tick (50ms)

	public TileEntitySPY1() {
		this.deviceId = UUID.randomUUID();
	}

	@Override
	public void validate() {
		super.validate();
		// Clear radar systems when tile entity is loaded/validated
		// This ensures no stale contacts persist across world loads
		for (int i = 0; i < 4; i++) {
			radarSystems[i] = null;
		}
		// Clear missile tracking
		activeMissiles.clear();
		lastKnownTargetStates.clear();
		System.out.println("[SPY-1] Radar systems cleared on world load/validate at " + pos);
	}

	@Override
	public String getInventoryName() {
		return "SPY-1 Radar";
	}

	@Override
	public void update() {
		if (!world.isRemote) {
			// Update power connections (accept power from all sides)
			this.updateMultiblockConnections();

			// 200-tick diagnostic state dump
			if (world.getTotalWorldTime() % 200 == 0) {
				System.out.println("[SPY1 DIAG] tick=" + world.getTotalWorldTime()
					+ " power=" + power + "/" + maxPower
					+ " isActive=" + isActive()
					+ " dataNet=" + (dataNet != null ? "#" + dataNet.getNetId() + " subs=" + dataNet.getSubscribers().size() : "null")
					+ " offlineTicks=" + offlineTicks);
			}

			// Check if radar can operate
			if (power >= powerConsumption) {
				power -= powerConsumption;
				isActive = true;
				offlineTicks = 0;  // Reset grace-period counter while powered

				// Initialize all 4 radar array faces if needed
				if (radarSystems[0] == null) {
					initializeRadarSystems();
				}

				// Update all 4 radar arrays (simultaneous operation)
				radarUpdateTick++;
				if (radarUpdateTick >= RADAR_UPDATE_INTERVAL) {
					radarUpdateTick = 0;
					performRadarScan();
					sendRadarDataToNetwork();
				}
			} else {
				isActive = false;
				offlineTicks++;  // Count consecutive ticks without power
				// Cleanup radar systems when inactive
				for (int i = 0; i < 4; i++) {
					radarSystems[i] = null;
				}
			}

			// Update midcourse guidance for active missiles
			guidanceUpdateTick++;
			if (guidanceUpdateTick >= GUIDANCE_UPDATE_INTERVAL) {
				guidanceUpdateTick = 0;
				// Always send guidance to in-flight missiles regardless of SPY-1 power state
				if (!activeMissiles.isEmpty()) {
					updateMidcourseGuidance();
				}
			}

			// Send status update periodically
			if (world.getTotalWorldTime() % 40 == 0) {  // Every 2 seconds
				sendStatusUpdate();
			}
		}
	}

	/**
	 * Initialize all 4 AN/SPY-1D PESA array faces with physics-derived parameters.
	 *
	 * =========================================================================
	 * AN/SPY-1D CONFIRMED SPECIFICATIONS (unclassified estimates)
	 * =========================================================================
	 *  Type              : PESA (Passive Electronically Scanned Array)
	 *  Band              : S-band, 3.1–3.5 GHz (center 3.3 GHz, λ=0.0909 m)
	 *  Peak transmit power: ~6 MW total → 1.5 MW per face (4 faces)
	 *  Antenna gain      : ~42 dBi per face (~15,849 linear)
	 *  Receiver sensitivity: ~-130 dBm  (RadarWavePhysics.SPY1_SENSITIVITY_DBM)
	 *  System losses     : ~8 dB         (RadarWavePhysics.SPY1_LOSSES_DB)
	 *  Beam width        : ~1.7° (track) / ~8° (search mode)
	 *  Max detection range: ~370 km for 1 m² RCS target (per Skolnik range equation)
	 *  Scan pattern      : 4-bar elevation × azimuth sweep (PESA raster scan)
	 *  Beam gating       : Enabled — PESA sweeps a SINGLE beam per face (not AESA DBF)
	 * =========================================================================
	 */
	private void initializeRadarSystems() {
		Entity radarEntity = new DummyRadarEntity(world, pos.getX(), pos.getY(), pos.getZ());

		for (int i = 0; i < 4; i++) {
			RadarSpec spec = new RadarSpec();
			spec.radarType = "SPY1D_PESA_" + ARRAY_NAMES[i];

			// ── Detection range cap (gameplay limit; physics equation gives ~370 km for 1 m² RCS)
			spec.maxRange = 370.0; // km  (updated from 300 km — matches unclassified SPY-1D BMD range)

			// ── Azimuth / elevation coverage
			spec.azimuthScan    = 95.0;  // 95° per face (slight overlap at face transitions)
			spec.elevationScan  = 90.0;  // 0° to 90° from horizontal (full upper hemisphere)

			// ── Beam characteristics (VOLUME SEARCH mode)
			// Real SPY-1D narrows to ~1.7° for precision track; search mode ~8-10°.
			// 8° chosen here to represent the effective search beam width.
			spec.beamWidth      = 8.0;   // degrees — SPY-1D volume search beam

			// Beam spreading rate: game mechanic to fill elevation-bar gaps at range.
			// Reduced from 0.5 to 0.3°/km because 4-bar scan already provides better coverage.
			spec.beamSpreadRate = 0.3;   // degrees per km

			// ── Transmit power: 6 MW total / 4 faces = 1.5 MW per face
			spec.transmitPower  = 1500.0; // kW per face  (was 1000 kW — corrected to 1.5 MW)

			// ── Frequency
			spec.frequency      = 3.3;   // GHz (S-band center)

			// ── Scan rate (electronic beam steering, no mechanical movement)
			spec.scanRate       = 360;   // degrees/second equivalent

			// ── Elevation bar scan: 4-bar raster (realistic PESA multi-bar pattern)
			// With elevationScan=90° and scanBars=4:
			//   spacing = 90/(4+1) = 18°  →  bars at –27°, –9°, +9°, +27°
			// Combined with 8° beam + 0.3°/km spreading, scan gaps close by ~40 km.
			spec.scanBars       = 4;

			// ── PESA: beam-cone gating ENABLED (single beam sweeps per face, not simultaneous DBF)
			spec.beamAngleGatingEnabled = true;

			// ── Physics parameters: official SPY-1D unclassified estimates (RadarWavePhysics)
			spec.antennaGainLinear   = RadarWavePhysics.SPY1_ANTENNA_GAIN;   // 42 dBi → ~15,849
			spec.wavelengthM         = RadarWavePhysics.SPY1_WAVELENGTH;      // 0.0909 m
			spec.minDetectablePowerW = RadarWavePhysics.SPY1_MIN_POWER;       // -130 dBm → ~1e-16 W
			spec.systemLossesLinear  = RadarWavePhysics.SPY1_LOSSES;          // 8 dB → ~6.31 linear

			radarEntity.rotationYaw = (float) ARRAY_AZIMUTHS[i];

			final int arrayIndex = i;
			radarSystems[i] = new PhysicsBasedRadarSystem(radarEntity, spec) {
				@Override
				protected boolean isValidRadarTarget(Entity entity) {
					return isEntityValidTarget(entity);
				}
			};
		}

		System.out.println("[SPY-1] All 4 PESA faces initialized (N/E/S/W)"
			+ " | Pt=1.5MW/face | G=42dBi | Pmin=-130dBm | L=8dB | 4-bar scan at " + pos);
	}

	/**
	 * Perform radar scan with all 4 array faces and merge contacts
	 * SPY-1's 4 arrays operate simultaneously for continuous 360° coverage
	 */
	private void performRadarScan() {
		if (radarSystems[0] == null) return;

		// Merge contacts from all 4 array faces
		Map<UUID, RadarContact> allContacts = new java.util.HashMap<>();

		// Update all 4 radar arrays simultaneously
		for (int i = 0; i < 4; i++) {
			if (radarSystems[i] != null) {
				// Update this array face (performs scan)
				radarSystems[i].update();

				// Merge contacts from this array
				Map<UUID, RadarContact> arrayContacts = radarSystems[i].getContacts();
				allContacts.putAll(arrayContacts);
			}
		}

		lastScanContactCount = allContacts.size();
		totalContactsDetected += allContacts.size();

		// Log scan results periodically
		if (world.getTotalWorldTime() % 100 == 0) {
			System.out.println("[SPY-1] 360° scan complete - Contacts: " + allContacts.size() +
					" | Total detected: " + totalContactsDetected +
					" | Power: " + power + "/" + maxPower);
		}
	}

	/**
	 * Check if entity is a valid radar target
	 */
	private boolean isEntityValidTarget(Entity entity) {
		if (entity == null) {
			return false;
		}

		// Reject dead entities immediately (handles EntityGasFlameFX and other dying entities)
		if (entity.isDead) {
			return false;
		}

		// Reject entities not yet fully added to the world chunk
		if (!entity.addedToChunk) {
			return false;
		}

		// Don't detect self (dummy radar entity)
		if (entity instanceof DummyRadarEntity) {
			return false;
		}

		// Filter out shrapnel, debris, and visual effect entities
		// EntityGasFlameFX, EntityExplosionFX, etc. must not pollute radar
		String entityName = entity.getClass().getSimpleName();
		if (entityName.contains("Shrapnel") || entityName.contains("Debris") ||
		    entityName.contains("Fragment") || entityName.contains("Particle") ||
		    entityName.contains("FX") || entityName.contains("Fx") ||
		    entityName.contains("Flame") || entityName.contains("Explosion") ||
		    entityName.contains("Effect") || entityName.contains("Gas") ||
		    entityName.contains("Smoke") || entityName.contains("Fire")) {
			return false;
		}

		// Check IRCSProvider (missiles/aircraft implementing physics RCS interface)
		if (scanMissiles && entity instanceof IRCSProvider) {
			return true;
		}

		// Block dropped items only — they fall fast from high altitude and create false contacts.
		// Particles are already caught by the class name filter above.
		// Everything else (missiles, aircraft, living entities) passes through.
		if (entity instanceof net.minecraft.entity.item.EntityItem) {
			return false;
		}

		// Check airborne entities (flying entities)
		if (scanAirborne) {
			// Require meaningful speed (>= 5 m/s = 0.25 blocks/tick) to avoid detecting
			// static newly-spawned entities whose onGround defaults to false
			double hSpeed = Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ);
			double vSpeed = Math.abs(entity.motionY);
			boolean hasSpeed = hSpeed >= 0.25 || vSpeed >= 0.25;

			if (hasSpeed && (entity.isAirBorne || !entity.onGround)) {
				return true;
			}
		}

		// Check players if enabled
		if (scanPlayers && entity instanceof net.minecraft.entity.player.EntityPlayer) {
			// Only detect airborne players
			if (entity.isAirBorne || !entity.onGround) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Send radar scan data to DataNet (to FCS Console)
	 * Merges contacts from all 4 array faces for 360° coverage
	 * Format matches TileEntityFCSConsole.handleRadarScanData() expectations
	 */
	private void sendRadarDataToNetwork() {
		if (dataNet == null || dataNet.isEmpty()) {
			getDataNet();
			if (dataNet == null) return;
		}

		// Merge contacts from all 4 radar array faces
		Map<UUID, RadarContact> contacts = new java.util.HashMap<>();
		for (int i = 0; i < 4; i++) {
			if (radarSystems[i] != null) {
				contacts.putAll(radarSystems[i].getContacts());
			}
		}

		// Create data packet
		NBTTagCompound data = new NBTTagCompound();

		// Add contact data (limit to 100 contacts for SPY-1)
		List<RadarContact> contactList = new ArrayList<>(contacts.values());
		// Remove dead-entity contacts that haven't been evicted from the cache yet
		contactList.removeIf(c -> c.entity == null || c.entity.isDead);
		contactList.sort((a, b) -> Double.compare(a.distance, b.distance));  // Sort by distance (closest first)

		int maxContacts = Math.min(100, contactList.size());
		data.setInteger("ContactCount", maxContacts);

		for (int i = 0; i < maxContacts; i++) {
			RadarContact contact = contactList.get(i);

			// Create contact compound matching FCSConsole expectations
			NBTTagCompound contactNBT = new NBTTagCompound();
			contactNBT.setDouble("x", contact.entity.posX);
			contactNBT.setDouble("y", contact.entity.posY);
			contactNBT.setDouble("z", contact.entity.posZ);
			contactNBT.setDouble("distance", contact.distance);
			contactNBT.setDouble("azimuth", contact.azimuth);
			contactNBT.setDouble("elevation", contact.elevation);
			contactNBT.setDouble("signalStrength", contact.signalStrength);
			contactNBT.setDouble("closureRate", contact.closureRate);
			// Add velocity vector for marker display (convert blocks/tick to m/s)
			// 1 tick = 0.05s, 1 block = 1.0m → multiply by 20
			contactNBT.setDouble("velocityX", contact.entity.motionX * 20.0);
			contactNBT.setDouble("velocityY", contact.entity.motionY * 20.0);
			contactNBT.setDouble("velocityZ", contact.entity.motionZ * 20.0);
			contactNBT.setInteger("entityId", contact.entity.getEntityId());
			// Mark missiles for blue display on FCS
			boolean isMissile = contact.entity instanceof com.hbm.entity.missile.EntityMissileSM6;
			contactNBT.setBoolean("isMissile", isMissile);

			data.setTag("Contact_" + i, contactNBT);
		}

		// Broadcast to all devices on network
		DataPacket packet = new DataPacket(DataPacketType.RADAR_SCAN_DATA, deviceId, data);
		dataNet.broadcastData(packet);
	}

	/**
	 * Send status update to network.
	 *
	 * World-join safety: Only report Online=false once the radar has been
	 * without power for at least OFFLINE_GRACE_TICKS consecutive ticks.
	 * This prevents a brief startup transient (power cables re-establishing
	 * after world load) from falsely declaring the radar offline, which would
	 * cause the FCS Console to purge all tracked radar contacts.
	 *
	 * @param forceOnlineTrue  when true, always reports Online=true regardless
	 *                         of current power state (used on DataNet connect).
	 */
	private void sendStatusUpdate() {
		sendStatusUpdate(false);
	}

	private void sendStatusUpdate(boolean forceOnlineTrue) {
		if (dataNet == null) {
			getDataNet();
			if (dataNet == null) return;
		}

		// Grace period: suppress Online=false during startup transient.
		// Only declare offline after OFFLINE_GRACE_TICKS consecutive powerless ticks.
		boolean reportOnline;
		if (forceOnlineTrue) {
			reportOnline = true;
		} else if (isActive()) {
			reportOnline = true;
		} else {
			// Radar has no power — only report offline after grace period expires
			reportOnline = offlineTicks < OFFLINE_GRACE_TICKS;
			if (!reportOnline) {
				System.out.println("[SPY-1 DEBUG] Grace period expired (" + offlineTicks +
					" ticks offline) — reporting Online=false to FCS");
			}
		}

		NBTTagCompound data = new NBTTagCompound();
		data.setString("DeviceType", DataDeviceType.RADAR_PHASED_ARRAY.name());
		data.setBoolean("Online", reportOnline);
		data.setString("Mode", operatingMode);
		data.setLong("Power", power);
		data.setInteger("ContactCount", lastScanContactCount);

		// Include device name for FCS Console to display
		String deviceName = getDeviceName();
		if (deviceName.contains(" @ ")) {
			deviceName = deviceName.substring(0, deviceName.indexOf(" @ "));
		}
		data.setString("DeviceName", deviceName);

		System.out.println("[SPY1] STATUS_UPDATE -> DataNet#" + dataNet.getNetId()
			+ " subs=" + dataNet.getSubscribers().size()
			+ " Online=" + reportOnline
			+ " (isActive=" + isActive() + " offlineTicks=" + offlineTicks + " power=" + power + ")");

		DataPacket packet = new DataPacket(DataPacketType.STATUS_UPDATE, deviceId, data);
		dataNet.broadcastData(packet);
	}

	// ========== IDataConnector Implementation ==========

	@Override
	public void receiveData(DataPacket packet) {
		// SPY-1 can receive commands from FCS Console in future
		// For now, just log
		System.out.println("[SPY-1] Received packet: " + packet.getType());
	}

	/**
	 * Get or establish DataNet connection
	 * SPY-1 is a multiblock (12×7×12), so we need to scan the entire bottom layer
	 */
	public DataNet getDataNet() {
		// For large multiblocks, we need to actively scan for cables
		// Cables might not be adjacent to core block
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
			System.out.println("[SPY-1] invalidate() — unsubscribed from DataNet");
		}
		super.invalidate();
	}

	@Override
	public void onChunkUnload() {
		if (!world.isRemote && dataNet != null) {
			dataNet.removeSubscriber(this);
			dataNet = null;
			System.out.println("[SPY-1] onChunkUnload() — unsubscribed from DataNet");
		}
		super.onChunkUnload();
	}

	/**
	 * Scan for cables in the multiblock footprint and connect
	 * SPY-1 is 12×7×12, so we scan multiple layers for cables
	 */
	private void scanForCables() {
		// SPY-1 multiblock dimensions: [Up:6, Down:0, North:3, South:8, West:5, East:6]
		int coreX = pos.getX();
		int coreY = pos.getY();
		int coreZ = pos.getZ();

		System.out.println("[SPY-1 SCAN] Starting cable scan from core position: " + pos);
		System.out.println("[SPY-1 SCAN] Multiblock scan area: X=[" + (coreX-5) + " to " + (coreX+6) + "], Z=[" + (coreZ-3) + " to " + (coreZ+8) + "]");

		int blocksChecked = 0;
		int conductorsFound = 0;

		// Scan bottom layer (Y-1) AND same level (Y) AND adjacent above (Y+1)
		// This ensures we find cables regardless of placement
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -5; dx <= 6; dx++) {
				for (int dz = -3; dz <= 8; dz++) {
					net.minecraft.util.math.BlockPos checkPos = new net.minecraft.util.math.BlockPos(
						coreX + dx, coreY + dy, coreZ + dz);

					net.minecraft.tileentity.TileEntity te = world.getTileEntity(checkPos);
					blocksChecked++;

					if (te instanceof api.hbm.data.IDataConductor) {
						conductorsFound++;
						api.hbm.data.IDataConductor conductor = (api.hbm.data.IDataConductor) te;

						System.out.println("[SPY-1 SCAN] Found IDataConductor at offset (" + dx + ", " + dy + ", " + dz + ")");

						if (conductor.getDataNet() != null) {
							dataNet = conductor.getDataNet();
							dataNet.addSubscriber(this);
							System.out.println("[SPY-1] ✓ Connected to DataNet #" + dataNet.getNetId() +
								" via cable at " + checkPos);
							// Immediately announce Online status so FCS does not wait
							// up to 40 ticks for the periodic STATUS_UPDATE timer.
							sendStatusUpdate(true);
							return;
						} else {
							// Cable exists but has no DataNet (common after world load due to init ordering).
						// Bootstrap a new DataNet for this cable so SPY-1 can connect immediately.
						System.out.println("[SPY-1 SCAN] Cable has NULL DataNet at " + checkPos
							+ " — bootstrapping new DataNet");
						api.hbm.data.DataNet bootstrapped = new api.hbm.data.DataNet();
						bootstrapped.addConductor(conductor);
						dataNet = bootstrapped;
						dataNet.addSubscriber(this);
						System.out.println("[SPY-1] \u2713 Bootstrapped DataNet #" + dataNet.getNetId()
							+ " and connected via cable at " + checkPos);
						// Immediately announce Online status so FCS does not wait
						// for the next periodic %40 timer after DataNet bootstrap.
						sendStatusUpdate(true);
						return;
						}
					}
				}
			}
		}

		System.out.println("[SPY-1 SCAN] Scan complete. Blocks checked: " + blocksChecked +
			", Conductors found: " + conductorsFound);
		System.out.println("[SPY-1] ✗ No DataNet cables with valid network found in multiblock area");
	}

	@Override
	public boolean canConnect(EnumFacing dir) {
		return dir == EnumFacing.DOWN;  // Only bottom face for cable connection
	}

	@Override
	public DataDeviceType getDeviceType() {
		return DataDeviceType.RADAR_PHASED_ARRAY;
	}

	@Override
	public UUID getDeviceId() {
		return this.deviceId;
	}

	@Override
	public String getDeviceName() {
		return "SPY-1 Radar @ " + pos.toString();
	}

	@Override
	public boolean isActive() {
		// Radar is active if it has sufficient power and is not invalid
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

	/**
	 * Update power connections across entire multiblock structure
	 * SPY-1 is 12×12 at bottom layer, cables connect from below
	 * Core is at (0,0,0) with dimensions [Up:6, Down:0, North:3, South:8, West:5, East:6]
	 */
	private void updateMultiblockConnections() {
		// Subscribe to power networks at all bottom-layer block positions
		// X: from -5 to +6 (12 blocks), Z: from -3 to +8 (12 blocks), Y: 0 (bottom layer)
		for (int dx = -5; dx <= 6; dx++) {
			for (int dz = -3; dz <= 8; dz++) {
				// Try to subscribe to power cable below this block (FROM_DOWN direction)
				this.trySubscribe(world, pos.add(dx, -1, dz), ForgeDirection.DOWN);
			}
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
				System.out.println("[SPY-1] Invalid deviceId in NBT, generated new: " + this.deviceId);
			}
		} else {
			// No deviceId in NBT, generate new one
			this.deviceId = UUID.randomUUID();
			System.out.println("[SPY-1] No deviceId in NBT, generated new: " + this.deviceId);
		}

		this.isActive = nbt.getBoolean("isActive");
		this.operatingMode = nbt.getString("operatingMode");

		// Read scan configuration with proper defaults (true for missiles/airborne, false for players)
		if (nbt.hasKey("scanMissiles")) {
			this.scanMissiles = nbt.getBoolean("scanMissiles");
		} else {
			this.scanMissiles = true;  // Default: detect missiles
		}

		if (nbt.hasKey("scanPlayers")) {
			this.scanPlayers = nbt.getBoolean("scanPlayers");
		} else {
			this.scanPlayers = false;  // Default: don't detect players
		}

		if (nbt.hasKey("scanAirborne")) {
			this.scanAirborne = nbt.getBoolean("scanAirborne");
		} else {
			this.scanAirborne = true;  // Default: detect airborne entities
		}

		this.totalContactsDetected = nbt.getInteger("totalContactsDetected");

		System.out.println("[SPY-1 NBT] Loaded scan config: scanMissiles=" + scanMissiles +
			", scanPlayers=" + scanPlayers + ", scanAirborne=" + scanAirborne);
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		nbt.setLong("power", power);
		nbt.setString("deviceId", deviceId.toString());
		nbt.setBoolean("isActive", isActive);
		nbt.setString("operatingMode", operatingMode);
		nbt.setBoolean("scanMissiles", scanMissiles);
		nbt.setBoolean("scanPlayers", scanPlayers);
		nbt.setBoolean("scanAirborne", scanAirborne);
		nbt.setInteger("totalContactsDetected", totalContactsDetected);
		return nbt;
	}

	// ========== Helper Classes ==========

	/**
	 * Dummy entity to represent the radar position for PhysicsBasedRadarSystem
	 * SPY-1 is ground-based, so this entity doesn't move
	 */
	private static class DummyRadarEntity extends EntityLivingBase {
		public DummyRadarEntity(World world, int x, int y, int z) {
			super(world);
			this.setPosition(x + 0.5, y + 1.5, z + 0.5);  // Center of multiblock, elevated
			this.rotationYaw = 0.0F;
			this.rotationPitch = 0.0F;
		}

		@Override
		public void onUpdate() {
			// Don't update - stationary radar
		}

		@Override
		public boolean isEntityInvulnerable(net.minecraft.util.DamageSource source) {
			return true;  // Invulnerable dummy entity
		}

		@Override
		public net.minecraft.util.EnumHandSide getPrimaryHand() {
			return net.minecraft.util.EnumHandSide.RIGHT;
		}

		@Override
		public Iterable<net.minecraft.item.ItemStack> getArmorInventoryList() {
			return java.util.Collections.emptyList();
		}

		@Override
		public net.minecraft.item.ItemStack getItemStackFromSlot(net.minecraft.inventory.EntityEquipmentSlot slotIn) {
			return net.minecraft.item.ItemStack.EMPTY;
		}

		@Override
		public void setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot slotIn, net.minecraft.item.ItemStack stack) {
			// No-op - dummy entity doesn't hold items
		}

		@Override
		public net.minecraft.util.EnumActionResult applyPlayerInteraction(net.minecraft.entity.player.EntityPlayer player, net.minecraft.util.math.Vec3d vec, net.minecraft.util.EnumHand hand) {
			return net.minecraft.util.EnumActionResult.PASS;
		}
	}

	// ========== Getters for GUI (Phase 2) ==========

	public int getContactCount() {
		return lastScanContactCount;
	}

	public int getTotalContactsDetected() {
		return totalContactsDetected;
	}

	public String getOperatingMode() {
		return operatingMode;
	}

	public double getPowerScaled(int scale) {
		return (power * scale) / (double) maxPower;
	}

	public boolean isScanningMissiles() {
		return scanMissiles;
	}

	public boolean isScanningPlayers() {
		return scanPlayers;
	}

	public boolean isScanningAirborne() {
		return scanAirborne;
	}

	/**
	 * Get radar system for GUI access (returns primary array - North face)
	 */
	public PhysicsBasedRadarSystem getRadarSystem() {
		return radarSystems[0];  // Return North array as representative
	}

	/**
	 * Get all radar array faces
	 */
	public PhysicsBasedRadarSystem[] getRadarSystems() {
		return radarSystems;
	}

	/**
	 * Get detected contacts for GUI display (merged from all 4 arrays)
	 */
	public Map<UUID, RadarContact> getContacts() {
		Map<UUID, RadarContact> allContacts = new java.util.HashMap<>();
		for (int i = 0; i < 4; i++) {
			if (radarSystems[i] != null) {
				allContacts.putAll(radarSystems[i].getContacts());
			}
		}
		return allContacts;
	}

	/**
	 * Get current scan azimuth (averaged across all active arrays)
	 */
	public double getCurrentAzimuth() {
		if (radarSystems[0] == null) return 0.0;
		// Return North array's azimuth as representative
		return radarSystems[0].getCurrentAzimuthScan();
	}

	/**
	 * Get current scan elevation (averaged across all active arrays)
	 */
	public double getCurrentElevation() {
		if (radarSystems[0] == null) return 0.0;
		// Return North array's elevation as representative
		return radarSystems[0].getCurrentElevationScan();
	}

	/**
	 * Get current bar index (for scan pattern display)
	 * Note: SPY-1 doesn't use scan bars, returns 0
	 */
	public int getCurrentBar() {
		return 0;  // SPY-1 uses electronic beam steering, not scan bars
	}

	/**
	 * Get radar specification (returns North array spec)
	 */
	public RadarSpec getRadarSpec() {
		if (radarSystems[0] == null) return RadarSpec.getDefault();
		return radarSystems[0].getSpec();
	}

	/**
	 * Check if radar is currently active
	 */
	public boolean isRadarActive() {
		return isActive && radarSystems[0] != null;
	}

	// ======================== MIDCOURSE GUIDANCE ========================

	/**
	 * Register an SM-6 missile for midcourse guidance
	 * Called by FCS Console when missile is launched
	 * @param missileId Missile device UUID
	 * @param targetEntityId Target entity ID
	 */
	public void registerMissile(UUID missileId, int targetEntityId) {
		registerMissile(missileId, targetEntityId, 0, 0, 0, 0, 0, 0);
	}

	/**
	 * Register an SM-6 missile for midcourse guidance with fallback coordinates.
	 * When the target entity is not currently loaded, the provided initial position
	 * and velocity seed the dead-reckoning cache so guidance is never interrupted.
	 *
	 * @param missileId       Missile device UUID
	 * @param targetEntityId  Target entity ID (-1 for coordinate-only targets)
	 * @param initX/Y/Z       Target world position at launch time (m)
	 * @param initVX/VY/VZ    Target velocity at launch time (m/s)
	 */
	public void registerMissile(UUID missileId, int targetEntityId,
			double initX, double initY, double initZ,
			double initVX, double initVY, double initVZ) {
		activeMissiles.put(missileId, targetEntityId);
		if (world != null) {
			Entity target = (targetEntityId >= 0) ? world.getEntityByID(targetEntityId) : null;
			if (target != null && !target.isDead) {
				// Live entity available — use current state as cache seed
				lastKnownTargetStates.put(missileId, new double[]{
					target.posX, target.posY, target.posZ,
					target.motionX * 20.0, target.motionY * 20.0, target.motionZ * 20.0,
					world.getTotalWorldTime()
				});
				System.out.println("[SPY-1 GUIDANCE] Registered SM-6 | Missile: " + missileId
					+ " | Target ID: " + targetEntityId
					+ " | Initial pos: (" + String.format("%.0f", target.posX)
					+ "," + String.format("%.0f", target.posY)
					+ "," + String.format("%.0f", target.posZ) + ")");
			} else if (initX != 0 || initY != 0 || initZ != 0) {
				// Entity not loaded — seed cache from FCS-provided coordinates so
				// updateMidcourseGuidance() can extrapolate without immediately removing the missile.
				lastKnownTargetStates.put(missileId, new double[]{
					initX, initY, initZ, initVX, initVY, initVZ, world.getTotalWorldTime()
				});
				System.out.println("[SPY-1 GUIDANCE] Registered SM-6 | Missile: " + missileId
					+ " | Target entity not loaded; seeded cache from FCS coords ("
					+ String.format("%.0f", initX) + "," + String.format("%.0f", initY)
					+ "," + String.format("%.0f", initZ) + ")");
			} else {
				System.out.println("[SPY-1 GUIDANCE] Registered SM-6 | Missile: " + missileId
					+ " | WARNING: no entity and no seed coords — guidance will be skipped until target is found");
			}
		}
	}

	/**
	 * Get all active missiles being tracked for midcourse guidance
	 * @return Map of missile UUID to target entity ID
	 */
	public Map<UUID, Integer> getActiveMissiles() {
		return activeMissiles;
	}

	/**
	 * Update midcourse guidance for all active missiles
	 * Sends target position updates via DataNet
	 */
	private void updateMidcourseGuidance() {
		if (activeMissiles.isEmpty()) {
			return;
		}

		// Find all SM-6 missiles in the world
		List<EntityMissileSM6> missiles = world.getEntities(EntityMissileSM6.class, entity -> true);

		// Iterate through active missiles and send guidance updates
		Iterator<Map.Entry<UUID, Integer>> iterator = activeMissiles.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Integer> entry = iterator.next();
			UUID missileId = entry.getKey();
			int targetEntityId = entry.getValue();

			// Find the missile entity
			EntityMissileSM6 missile = null;
			for (EntityMissileSM6 m : missiles) {
				if (m.getDeviceId().equals(missileId)) {
					missile = m;
					break;
				}
			}

			// Check if missile still exists and is not in pitbull mode
			if (missile == null || missile.isDead) {
				// Remove dead/despawned missiles
				iterator.remove();
				lastKnownTargetStates.remove(missileId);
				System.out.println("[SPY-1 GUIDANCE] Removed missile from guidance list (dead/despawned)");
				continue;
			}

			// Find target entity - entity may be in an unloaded chunk at long range
			Entity target = world.getEntityByID(targetEntityId);

			// Resolve target position/velocity: live entity or cached extrapolated state
			double tPosX, tPosY, tPosZ, tVelX, tVelY, tVelZ;
			if (target != null && !target.isDead) {
				// Entity is loaded - use live position/velocity and update cache
				tPosX = target.posX;
				tPosY = target.posY;
				tPosZ = target.posZ;
				tVelX = target.motionX * 20.0; // blocks/tick → m/s
				tVelY = target.motionY * 20.0;
				tVelZ = target.motionZ * 20.0;
				lastKnownTargetStates.put(missileId,
					new double[]{tPosX, tPosY, tPosZ, tVelX, tVelY, tVelZ, world.getTotalWorldTime()});

				if (missile.ticksExisted % 40 == 0) {
					System.out.println("[SPY-1 GUIDANCE] Live target " + target.getName()
						+ " Pos:(" + String.format("%.0f", tPosX) + "," + String.format("%.0f", tPosY) + "," + String.format("%.0f", tPosZ) + ")"
						+ " | Missile:(" + String.format("%.0f", missile.posX) + "," + String.format("%.0f", missile.posY) + "," + String.format("%.0f", missile.posZ) + ")");
				}
			} else {
				// Entity not loaded (out-of-range chunk) or destroyed - use last known track
				double[] lastState = lastKnownTargetStates.get(missileId);
				if (lastState == null) {
					// No cached state yet — skip this tick without removing the missile.
					// The missile stays in guidance list so that if the entity becomes
					// available in a future tick (chunk loads), guidance resumes automatically.
					continue;
				}
				// Extrapolate position from last known state using constant-velocity assumption
				long ticksElapsed = world.getTotalWorldTime() - (long) lastState[6];
				double dt = ticksElapsed * 0.05; // 20 ticks/s → seconds
				tPosX = lastState[0] + lastState[3] * dt;
				tPosY = lastState[1] + lastState[4] * dt;
				tPosZ = lastState[2] + lastState[5] * dt;
				tVelX = lastState[3];
				tVelY = lastState[4];
				tVelZ = lastState[5];

				if (missile.ticksExisted % 40 == 0) {
					System.out.println("[SPY-1 GUIDANCE] Extrapolated target pos (entity unloaded, dt="
						+ String.format("%.1f", dt) + "s)"
						+ " Pos:(" + String.format("%.0f", tPosX) + "," + String.format("%.0f", tPosY) + "," + String.format("%.0f", tPosZ) + ")");
				}
			}

			// Stop guidance when missile is within 5 km of estimated target position
			double dx = tPosX - missile.posX, dy = tPosY - missile.posY, dz = tPosZ - missile.posZ;
			double distanceToTarget = Math.sqrt(dx*dx + dy*dy + dz*dz);
			if (distanceToTarget < 5000.0) {
				iterator.remove();
				lastKnownTargetStates.remove(missileId);
				System.out.println("[SPY-1 GUIDANCE] Missile within " + String.format("%.0f", distanceToTarget)
				                   + "m of target - stopping command guidance (terminal phase)");
				continue;
			}

			// Send midcourse guidance update
			NBTTagCompound data = new NBTTagCompound();
			data.setDouble("targetX",    tPosX);
			data.setDouble("targetY",    tPosY);
			data.setDouble("targetZ",    tPosZ);
			data.setDouble("targetVelX", tVelX);
			data.setDouble("targetVelY", tVelY);
			data.setDouble("targetVelZ", tVelZ);

			DataPacket guidancePacket = new DataPacket(
				DataPacketType.MIDCOURSE_GUIDANCE,
				this.deviceId,
				missileId,  // Send directly to missile
				data
			);

			if (dataNet != null) {
				dataNet.broadcastData(guidancePacket);  // Broadcast to network if available
			}

			// Deliver packet directly to missile (since missiles are entities, not tile entities)
			missile.receiveMidcourseGuidance(guidancePacket);
		}
	}

	/**
	 * Check if player can use this radar
	 */
	public boolean isUsableByPlayer(net.minecraft.entity.player.EntityPlayer player) {
		if (this.world.getTileEntity(this.pos) != this) {
			return false;
		}
		return player.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
	}
}
