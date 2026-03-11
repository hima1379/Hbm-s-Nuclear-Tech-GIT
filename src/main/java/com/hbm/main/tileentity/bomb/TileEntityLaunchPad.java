package com.hbm.main.tileentity.bomb;

import com.hbm.lib.Library;
import com.hbm.lib.ForgeDirection;
import com.hbm.items.ModItems;
import com.hbm.interfaces.IBomb;
import com.hbm.main.tileentity.TileEntityLoadedBase;
import com.hbm.packet.AuxGaugePacket;
import com.hbm.packet.AuxElectricityPacket;
import com.hbm.packet.PacketDispatcher;
import com.hbm.packet.TEMissilePacket;
import net.minecraftforge.fml.common.Optional;

import api.hbm.energy.IEnergyUser;
import api.hbm.data.DataDeviceType;
import api.hbm.data.IDataConnector;
import api.hbm.data.DataNet;
import api.hbm.data.DataPacket;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import java.util.UUID;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.SimpleComponent;

@Optional.InterfaceList({@Optional.Interface(iface = "li.cil.oc.api.network.SimpleComponent", modid = "OpenComputers")})
public class TileEntityLaunchPad extends TileEntityLoadedBase implements ITickable, IEnergyUser, IDataConnector, SimpleComponent {

	public ItemStackHandler inventory;

	public long power;
	public final long maxPower = 100000;

	// private static final int[] slots_top = new int[] {0};
	// private static final int[] slots_bottom = new int[] { 0, 1, 2};
	// private static final int[] slots_side = new int[] {0};
	public int state = 0;

	//Time missile needs to clear launchpad in ticks
	public static final int clearingDuraction = 100;
	public int clearingTimer = 0;

	private String customName;

	// DataNet integration
	private UUID deviceId;
	private DataNet dataNet;

	// ★ NEW: Store received target coordinates from FCS via cable blue
	private double receivedTargetX = 0.0;
	private double receivedTargetY = 0.0;
	private double receivedTargetZ = 0.0;
	private double receivedTargetVelX = 0.0; // m/s - used as SPY-1 seed when entity is unloaded
	private double receivedTargetVelY = 0.0;
	private double receivedTargetVelZ = 0.0;
	private int receivedTargetEntityId = -1;
	private boolean receivedSarhMode = false;
	private boolean launchCommandReceived = false;

	public TileEntityLaunchPad() {
		inventory = new ItemStackHandler(3);
		this.deviceId = UUID.randomUUID();
	}

	public String getInventoryName() {
		return this.hasCustomInventoryName() ? this.customName : "container.launchPad";
	}

	public boolean hasCustomInventoryName() {
		return this.customName != null && this.customName.length() > 0;
	}

	public void setCustomName(String name) {
		this.customName = name;
	}

	public boolean isUseableByPlayer(EntityPlayer player) {
		if (world.getTileEntity(pos) != this) {
			return false;
		} else {
			return player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64;
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound compound) {
		power = compound.getLong("power");
		detectPower = power + 1;
		if (compound.hasKey("inventory"))
			inventory.deserializeNBT(compound.getCompoundTag("inventory"));

		// Load DataNet device ID
		if (compound.hasKey("deviceIdMost") && compound.hasKey("deviceIdLeast")) {
			this.deviceId = new UUID(compound.getLong("deviceIdMost"), compound.getLong("deviceIdLeast"));
		} else {
			this.deviceId = UUID.randomUUID();
		}

		super.readFromNBT(compound);
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound compound) {
		compound.setLong("power", power);

		compound.setTag("inventory", inventory.serializeNBT());

		// Save DataNet device ID
		compound.setLong("deviceIdMost", this.deviceId.getMostSignificantBits());
		compound.setLong("deviceIdLeast", this.deviceId.getLeastSignificantBits());

		return super.writeToNBT(compound);
	}

	public long getPowerScaled(long i) {
		return (power * i) / maxPower;
	}

	@Override
	public void update() {

		if (!world.isRemote) {
			if(clearingTimer > 0) clearingTimer--;

			// Periodically rescan for cables if not connected (every 20 ticks = 1 second)
			if (dataNet == null && world.getTotalWorldTime() % 20 == 0) {
				scanForCables();
			}

			// Broadcast DEVICE_STATUS to DataNet every 20 ticks (1 second)
			if (dataNet != null && world.getTotalWorldTime() % 20 == 0) {
				broadcastDeviceStatus();
			}

			power = Library.chargeTEFromItems(inventory, 2, power, maxPower);
			detectAndSendChanges();
		}
	}

	/**
	 * Broadcast STATUS_UPDATE packet to DataNet
	 * This allows FCS Console to detect and display this launch pad
	 */
	private void broadcastDeviceStatus() {
		if (dataNet == null) return;

		// Check if missile is loaded
		ItemStack missileStack = inventory.getStackInSlot(0);
		boolean hasMissile = !missileStack.isEmpty();
		boolean hasTargetDesignator = !inventory.getStackInSlot(1).isEmpty();
		boolean hasPower = power > 0;

		String mode = (hasMissile && hasPower) ? "READY" : "NOT_READY";

		// Get missile type if loaded
		String missileType = "NONE";
		if (hasMissile) {
			// Get the registry name of the missile item
			missileType = missileStack.getItem().getRegistryName().toString();
		}

		// Create NBT data for the packet
		NBTTagCompound data = new NBTTagCompound();
		data.setString("DeviceType", DataDeviceType.MISSILE_LAUNCHER.name());
		data.setBoolean("Online", true);  // LaunchPad is online
		data.setString("DeviceName", getDeviceName());
		data.setLong("Power", power);
		data.setString("Mode", mode);
		data.setString("MissileType", missileType);  // Add missile type

		// Create and broadcast packet
		DataPacket packet = new DataPacket(DataPacket.DataPacketType.STATUS_UPDATE, deviceId, data);
		dataNet.broadcastData(packet);
	}

	private ItemStack detectStack = ItemStack.EMPTY;
	private long detectPower;
	
	private void detectAndSendChanges() {
		boolean mark = false;
		if(!(detectStack.isEmpty() && inventory.getStackInSlot(0).isEmpty()) && !detectStack.isItemEqualIgnoreDurability(inventory.getStackInSlot(0))){
			mark = true;
			detectStack = inventory.getStackInSlot(0).copy();
		}
		if(detectPower != power){
			mark = true;
			detectPower = power;
		}
		PacketDispatcher.wrapper.sendToAllAround(new AuxGaugePacket(pos, clearingTimer, 0), new TargetPoint(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 20));
		PacketDispatcher.wrapper.sendToAllTracking(new TEMissilePacket(pos.getX(), pos.getY(), pos.getZ(), inventory.getStackInSlot(0)), new TargetPoint(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 1000));
		PacketDispatcher.wrapper.sendToAllAround(new AuxElectricityPacket(pos.getX(), pos.getY(), pos.getZ(), power), new TargetPoint(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 10));
		if(mark)
			markDirty();
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return INFINITE_EXTENT_AABB;
	}

	@Override
	public void setPower(long i) {
		power = i;
	}

	@Override
	public long getPower() {
		return power;
	}

	@Override
	public long getMaxPower() {
		return maxPower;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared() {
		return 65536.0D;
	}
	
	@Override
	public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
		return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
	}
	
	@Override
	public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
		return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY ? CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(inventory) : super.getCapability(capability, facing);
	}

	public boolean setCoords(int x, int z){
		if(!inventory.getStackInSlot(1).isEmpty() && (inventory.getStackInSlot(1).getItem() == ModItems.designator || inventory.getStackInSlot(1).getItem() == ModItems.designator_range || inventory.getStackInSlot(1).getItem() == ModItems.designator_manual)){
			NBTTagCompound nbt;
			if(inventory.getStackInSlot(1).hasTagCompound())
				nbt = inventory.getStackInSlot(1).getTagCompound();
			else
				nbt = new NBTTagCompound();
			nbt.setInteger("xCoord", x);
			nbt.setInteger("zCoord", z);
			inventory.getStackInSlot(1).setTagCompound(nbt);
			return true;
		}
		return false;
	}

	// opencomputers interface

	@Override
	public String getComponentName() {
		return "launchpad";
	}

	@Callback(doc = "setTarget(x:int, z:int); saves coords in target designator item - returns true if it worked")
	public Object[] setTarget(Context context, Arguments args) {
		int x = args.checkInteger(0);
		int z = args.checkInteger(1);
		
		return new Object[] {setCoords(x, z)};
	}

	@Callback(doc = "launch(); tries to launch the rocket")
	public Object[] launch(Context context, Arguments args) {
		Block b = world.getBlockState(pos).getBlock();
		if(b instanceof IBomb){
			((IBomb)b).explode(world, pos);
		}
		return new Object[] {null};
	}

	// ======================== DATANET INTERFACE ========================

	public DataNet getDataNet() {
		if (dataNet == null && !world.isRemote) {
			scanForCables();
		}
		return dataNet;
	}

	/**
	 * Scan adjacent blocks for DataNet cables (IDataConductor)
	 * LaunchPad is a single-block device, so we only check 6 adjacent positions
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
					System.out.println("[LaunchPad] ✓ Found and connected to DataNet #" +
						dataNet.getNetId() + " via cable at " + checkPos);
					return;
				} else {
					// Cable exists but has no network yet - ask it to re-evaluate
					System.out.println("[LaunchPad] Found cable with NULL DataNet at " + checkPos + " - triggering re-evaluate");
					conductor.reEvaluate();
					// Try again on next tick
				}
			}
		}

		// No cable found - log it
		if (world.getTotalWorldTime() % 100 == 0) {  // Log every 5 seconds to avoid spam
			System.out.println("[LaunchPad] ⚠ No DataNet cables found in adjacent blocks at " + pos);
		}
	}

	public void setDataNet(DataNet net) {
		this.dataNet = net;
	}

	@Override
	public void invalidate() {
		if (!world.isRemote && dataNet != null) {
			dataNet.removeSubscriber(this);
			dataNet = null;
			System.out.println("[LaunchPad] invalidate() — unsubscribed from DataNet");
		}
		super.invalidate();
	}

	@Override
	public void onChunkUnload() {
		if (!world.isRemote && dataNet != null) {
			dataNet.removeSubscriber(this);
			dataNet = null;
			System.out.println("[LaunchPad] onChunkUnload() — unsubscribed from DataNet");
		}
		super.onChunkUnload();
	}

	@Override
	public void receiveData(DataPacket packet) {
		// ★ NEW: Receive MISSILE_COMMAND packets from FCS via cable blue
		if (packet.getType() == DataPacket.DataPacketType.MISSILE_COMMAND) {
			// Check if packet is for this launcher
			if (!packet.isFor(this.deviceId)) {
				return; // Not for us
			}

			NBTTagCompound data = packet.getData();

			// Extract target coordinates and launch parameters
			if (data.hasKey("TargetX") && data.hasKey("TargetY") && data.hasKey("TargetZ")) {
				receivedTargetX = data.getDouble("TargetX");
				receivedTargetY = data.getDouble("TargetY");
				receivedTargetZ = data.getDouble("TargetZ");
				receivedTargetVelX = data.getDouble("TargetVelX");
				receivedTargetVelY = data.getDouble("TargetVelY");
				receivedTargetVelZ = data.getDouble("TargetVelZ");
				receivedTargetEntityId = data.getInteger("TargetEntityId");
				receivedSarhMode = data.getBoolean("SarhMode");
				launchCommandReceived = true;

				System.out.println("[LaunchPad] ✓ Received MISSILE_COMMAND via cable blue:");
				System.out.println("  Target coordinates: (" + receivedTargetX + ", " + receivedTargetY + ", " + receivedTargetZ + ")");
				System.out.println("  Target entity ID: " + receivedTargetEntityId);
				System.out.println("  SARH mode: " + receivedSarhMode);

				// Execute launch immediately upon receiving command
				executeLaunchCommand();
			}
		}
	}

	@Override
	public boolean canConnect(EnumFacing dir) {
		return true;  // Accept connections from all directions
	}

	@Override
	public DataDeviceType getDeviceType() {
		return DataDeviceType.MISSILE_LAUNCHER;
	}

	@Override
	public UUID getDeviceId() {
		return this.deviceId;
	}

	@Override
	public String getDeviceName() {
		if (this.customName != null && !this.customName.isEmpty()) {
			return this.customName;
		}
		return "LaunchPad @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	@Override
	public boolean isActive() {
		return !this.isInvalid() && power > 0;
	}

	// ======================== REMOTE LAUNCH INTERFACE ========================

	/**
	 * ★ NEW: Execute launch command using received target coordinates
	 * Called automatically when MISSILE_COMMAND packet is received via cable blue
	 */
	private void executeLaunchCommand() {
		if (!launchCommandReceived) {
			System.out.println("[LaunchPad executeLaunchCommand] FAILED: No launch command received");
			return;
		}

		// Check if missile is loaded
		if (inventory.getStackInSlot(0).isEmpty()) {
			System.out.println("[LaunchPad executeLaunchCommand] FAILED: No missile loaded");
			launchCommandReceived = false;
			return;
		}

		// Check if clearing timer allows launch
		if (clearingTimer > 0) {
			System.out.println("[LaunchPad executeLaunchCommand] FAILED: Clearing timer = " + clearingTimer);
			launchCommandReceived = false;
			return;
		}

		// Check power requirement (75000 HE minimum)
		if (power < 75000) {
			System.out.println("[LaunchPad executeLaunchCommand] FAILED: Insufficient power = " + power);
			launchCommandReceived = false;
			return;
		}

		System.out.println("[LaunchPad executeLaunchCommand] Launching SM-6 with pre-configured target coordinates...");

		// Consume power
		power -= 75000;

		// Start clearing timer
		clearingTimer = clearingDuraction;

		// Spawn SM-6 missile entity above the launch pad
		com.hbm.entity.missile.EntityMissileSM6 missile = new com.hbm.entity.missile.EntityMissileSM6(world);
		missile.setPosition(pos.getX() + 0.5, pos.getY() + 3.0, pos.getZ() + 0.5);

		// Disable Minecraft's entity gravity
		missile.setNoGravity(true);

		// ★★★ CRITICAL FIX: Set initial yaw toward target BEFORE initializing ★★★
		// Calculate initial yaw from launch pad to target
		double dx = receivedTargetX - (pos.getX() + 0.5);
		double dz = receivedTargetZ - (pos.getZ() + 0.5);
		float initialYaw = (float) Math.toDegrees(Math.atan2(dx, dz));
		missile.rotationYaw = initialYaw;
		missile.prevRotationYaw = initialYaw;
		System.out.println("[LaunchPad executeLaunchCommand] ✓ Set initial yaw toward target: " +
		                   String.format("%.1f", initialYaw) + "°");

		// Initialize vertical launch orientation (this will preserve the yaw we just set)
		missile.initializeVerticalLaunch();

		// ★★★ CRITICAL: Set target coordinates BEFORE spawning ★★★
		// This allows missile to know target direction from the start
		missile.setTargetCoordinates(receivedTargetX, receivedTargetY, receivedTargetZ);
		System.out.println("[LaunchPad executeLaunchCommand] ✓ Set initial target coordinates: (" +
		                   receivedTargetX + ", " + receivedTargetY + ", " + receivedTargetZ + ")");

		// Set target entity if available
		if (receivedTargetEntityId >= 0) {
			Entity targetEntity = world.getEntityByID(receivedTargetEntityId);
			if (targetEntity != null) {
				missile.setTargetEntity(targetEntity);
				System.out.println("[LaunchPad executeLaunchCommand] ✓ Set target entity: " + targetEntity.getName());
			} else {
				// Entity in unloaded chunk — seed dead-reckoning velocity from FCS packet
				// so the SM-6 doesn't assume a stationary target (vel=0) during midcourse.
				missile.setTargetVelocity(receivedTargetVelX, receivedTargetVelY, receivedTargetVelZ);
				System.out.println("[LaunchPad executeLaunchCommand] ⚠ Target entity not loaded; seeded velocity ("
					+ String.format("%.1f", receivedTargetVelX) + ","
					+ String.format("%.1f", receivedTargetVelY) + ","
					+ String.format("%.1f", receivedTargetVelZ) + ") m/s for dead-reckoning");
			}
		} else if (receivedTargetVelX != 0.0 || receivedTargetVelY != 0.0 || receivedTargetVelZ != 0.0) {
			// Coordinate-only target with velocity from FCS packet — seed dead-reckoning
			missile.setTargetVelocity(receivedTargetVelX, receivedTargetVelY, receivedTargetVelZ);
		}

		// Set guidance mode
		missile.setGuidanceMode(receivedSarhMode);

		// Spawn missile in world
		System.out.println("[LaunchPad executeLaunchCommand] ★★★ Spawning missile in world...");
		System.out.println("[LaunchPad executeLaunchCommand] Missile class: " + missile.getClass().getName());
		System.out.println("[LaunchPad executeLaunchCommand] World: " + (world.isRemote ? "CLIENT" : "SERVER"));
		boolean spawnResult = world.spawnEntity(missile);
		System.out.println("[LaunchPad executeLaunchCommand] ★★★ Spawn result: " + spawnResult);
		System.out.println("[LaunchPad executeLaunchCommand] ★★★ Missile isDead: " + missile.isDead);
		System.out.println("[LaunchPad executeLaunchCommand] ★★★ Missile EntityID: " + missile.getEntityId());
		System.out.println("[LaunchPad executeLaunchCommand] ✓ Missile spawned at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());

		// Register missile with SPY-1 or SPY-6 radar for midcourse guidance (ARH mode only).
		// Try SPY-1 first; fall back to SPY-6 so either radar type works.
		if (!receivedSarhMode) {
			com.hbm.main.tileentity.network.data.TileEntitySPY1 spy1 =
				DataNet.findSubscriberOfType(dataNet,
					com.hbm.main.tileentity.network.data.TileEntitySPY1.class);

			if (spy1 != null) {
				// Pass seed coordinates so SPY-1 can extrapolate even when the target
				// entity is in an unloaded chunk — prevents immediate removal from guidance list.
				spy1.registerMissile(missile.getDeviceId(), receivedTargetEntityId,
					receivedTargetX, receivedTargetY, receivedTargetZ,
					receivedTargetVelX, receivedTargetVelY, receivedTargetVelZ);
				System.out.println("[LaunchPad executeLaunchCommand] ✓ Registered missile " + missile.getDeviceId() +
					" with SPY-1 radar for midcourse guidance");
			} else {
				// SPY-1 not in network — try SPY-6
				com.hbm.main.tileentity.network.data.TileEntitySPY6 spy6 =
					DataNet.findSubscriberOfType(dataNet,
						com.hbm.main.tileentity.network.data.TileEntitySPY6.class);
				if (spy6 != null) {
					spy6.registerMissile(missile.getDeviceId(), receivedTargetEntityId,
						receivedTargetX, receivedTargetY, receivedTargetZ,
						receivedTargetVelX, receivedTargetVelY, receivedTargetVelZ);
					System.out.println("[LaunchPad executeLaunchCommand] ✓ Registered missile " + missile.getDeviceId() +
						" with SPY-6 radar for midcourse guidance");
				} else {
					System.out.println("[LaunchPad executeLaunchCommand] ⚠ No SPY-1 or SPY-6 radar found in DataNet");
				}
			}
		}

		// Remove missile from inventory
		inventory.setStackInSlot(0, net.minecraft.item.ItemStack.EMPTY);

		// Mark block for update
		markDirty();

		// Reset launch command flag
		launchCommandReceived = false;

		System.out.println("[LaunchPad executeLaunchCommand] ✓✓✓ SUCCESS: SM-6 launched with initial target coordinates!");
	}

	/**
	 * Remote launch method for FCS Console
	 * @param targetEntityId Target entity ID for missile guidance
	 * @param sarhMode true = SARH, false = ARH
	 * @return EntityMissileSM6 if launch successful, null otherwise
	 */
	public com.hbm.entity.missile.EntityMissileSM6 remoteLaunch(int targetEntityId, boolean sarhMode) {
		System.out.println("[LaunchPad remoteLaunch] Called with targetEntityId=" + targetEntityId + ", sarhMode=" + sarhMode);

		// Check if missile is loaded
		if (inventory.getStackInSlot(0).isEmpty()) {
			System.out.println("[LaunchPad remoteLaunch] FAILED: No missile loaded");
			return null;
		}

		// Check if clearing timer allows launch
		if (clearingTimer > 0) {
			System.out.println("[LaunchPad remoteLaunch] FAILED: Clearing timer = " + clearingTimer);
			return null;
		}

		// Check power requirement (75000 HE minimum)
		if (power < 75000) {
			System.out.println("[LaunchPad remoteLaunch] FAILED: Insufficient power = " + power);
			return null;
		}

		// Find target entity
		Entity targetEntity = world.getEntityByID(targetEntityId);
		if (targetEntity == null) {
			System.out.println("[LaunchPad remoteLaunch] WARNING: Target entity " + targetEntityId + " not found, launching anyway");
		} else {
			System.out.println("[LaunchPad remoteLaunch] Target found: " + targetEntity.getName() + " at " +
			                   targetEntity.posX + ", " + targetEntity.posY + ", " + targetEntity.posZ);
		}

		// Consume power
		power -= 75000;

		// Start clearing timer
		clearingTimer = clearingDuraction;

		// Spawn SM-6 missile entity above the launch pad
		// Launch pad is 2 blocks tall, spawn missile at Y+3 to ensure it's in open air
		com.hbm.entity.missile.EntityMissileSM6 missile = new com.hbm.entity.missile.EntityMissileSM6(world);
		missile.setPosition(pos.getX() + 0.5, pos.getY() + 3.0, pos.getZ() + 0.5);

		// Disable Minecraft's entity gravity - SM-6 has its own realistic physics simulation
		missile.setNoGravity(true);

		// ★★★ CRITICAL FIX: Set initial yaw toward target BEFORE initializing ★★★
		if (targetEntity != null) {
			// Calculate initial yaw from launch pad to target
			double dx = targetEntity.posX - (pos.getX() + 0.5);
			double dz = targetEntity.posZ - (pos.getZ() + 0.5);
			float initialYaw = (float) Math.toDegrees(Math.atan2(dx, dz));
			missile.rotationYaw = initialYaw;
			missile.prevRotationYaw = initialYaw;
			System.out.println("[LaunchPad remoteLaunch] ✓ Set initial yaw toward target: " +
			                   String.format("%.1f", initialYaw) + "°");
		}

		// Initialize vertical launch orientation (80 degrees pitch) - preserves yaw
		missile.initializeVerticalLaunch();

		// ★★★ CRITICAL FIX: Set target coordinates BEFORE spawning ★★★
		if (targetEntity != null) {
			missile.setTargetEntity(targetEntity);
			// Also set coordinates so missile knows direction even if entity reference is lost
			missile.setTargetCoordinates(targetEntity.posX, targetEntity.posY, targetEntity.posZ);
			System.out.println("[LaunchPad remoteLaunch] ✓ Target entity set: " + targetEntity.getName());
			System.out.println("[LaunchPad remoteLaunch] ✓ Initial target coordinates: (" +
			                   targetEntity.posX + ", " + targetEntity.posY + ", " + targetEntity.posZ + ")");
		} else {
			System.out.println("[LaunchPad remoteLaunch] ✗ WARNING: No target entity found!");
		}

		// Spawn missile in world
		System.out.println("[LaunchPad remoteLaunch] ★★★ Spawning missile in world...");
		System.out.println("[LaunchPad remoteLaunch] Missile class: " + missile.getClass().getName());
		System.out.println("[LaunchPad remoteLaunch] World: " + (world.isRemote ? "CLIENT" : "SERVER"));
		boolean spawnResult = world.spawnEntity(missile);
		System.out.println("[LaunchPad remoteLaunch] ★★★ Spawn result: " + spawnResult);
		System.out.println("[LaunchPad remoteLaunch] ★★★ Missile isDead: " + missile.isDead);
		System.out.println("[LaunchPad remoteLaunch] ★★★ Missile EntityID: " + missile.getEntityId());
		System.out.println("[LaunchPad remoteLaunch] Missile spawned at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());

		// Remove missile from inventory
		inventory.setStackInSlot(0, net.minecraft.item.ItemStack.EMPTY);
		System.out.println("[LaunchPad remoteLaunch] Missile removed from inventory");

		// Mark block for update
		markDirty();

		System.out.println("[LaunchPad remoteLaunch] SUCCESS: Missile launched!");
		return missile;  // Return the missile entity for FCS to register with SPY-1
	}

	/**
	 * Get missile type currently loaded
	 * @return Missile type name or "Empty"
	 */
	public String getMissileType() {
		ItemStack missile = inventory.getStackInSlot(0);
		if (!missile.isEmpty() && missile.getItem().getRegistryName() != null) {
			// Return registry name (e.g. "hbm:missile_sm6") for consistent matching in FCS GUI.
			// Display names are localized and may contain hyphens ("SM-6 ERAM") that break
			// simple substring checks like contains("sm6").
			return missile.getItem().getRegistryName().toString();
		}
		return "Empty";
	}

	/**
	 * Check if launch pad is ready to fire
	 * @return true if ready (missile loaded, not clearing, enough power)
	 */
	public boolean isReadyToFire() {
		return !inventory.getStackInSlot(0).isEmpty() &&
		       clearingTimer == 0 &&
		       power >= 75000;
	}
}
