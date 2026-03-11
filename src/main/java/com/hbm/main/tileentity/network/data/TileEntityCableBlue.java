package com.hbm.main.tileentity.network.data;

import com.hbm.lib.ForgeDirection;

import api.hbm.data.IDataConductor;
import api.hbm.data.IDataConnector;
import api.hbm.data.DataNet;
import api.hbm.data.DataPacket;
import api.hbm.data.DataDeviceType;
import net.minecraft.util.ITickable;
import net.minecraft.util.EnumFacing;
import net.minecraft.tileentity.TileEntity;

import java.util.UUID;

/**
 * Blue cable TileEntity for data transmission
 * Uses DataNet instead of PowerNet
 */
public class TileEntityCableBlue extends TileEntity implements ITickable, IDataConductor {

    protected DataNet network;
    private UUID deviceId;
    private int updateTimer = 0;
    private static final int UPDATE_INTERVAL = 20; // Update every 20 ticks (1 second)

    public TileEntityCableBlue() {
        this.deviceId = UUID.randomUUID();
    }

    @Override
    public void update() {
        if(!world.isRemote) {
            updateTimer++;

            // Only update network connections periodically, not every tick
            if(updateTimer >= UPDATE_INTERVAL) {
                updateTimer = 0;
                updateNetworkConnections();
            }
        }
    }

    /**
     * Update network connections - called periodically, not every tick
     *
     * Previously this used an if/else that called connect() only when
     * network == null, and only connectDevices() when network != null.
     * That caused a world-reload bug:
     *   - All cables start with network = null and fire updateNetworkConnections()
     *     at tick 20 in a random order.
     *   - Cables processed before any neighbour has a network each create their
     *     own isolated DataNet (#1, #2, …).
     *   - Once network != null the else-branch never ran connect() again, so
     *     those isolated islands never merged.
     *   - SPY-1 ended up on one island, FCS on another → no communication.
     *
     * Fix: always call connect() so that adjacent-cable merging runs every cycle,
     * regardless of whether this cable already has a network.
     */
    private void updateNetworkConnections() {
        // Always scan adjacent cables: join their network if we have none,
        // or merge their network into ours if they differ.
        this.connect();

        // If still no network after the cable scan (completely isolated cable),
        // create a fresh DataNet for this cable.
        if(this.getDataNet() == null) {
            DataNet newNet = new DataNet();
            newNet.addConductor(this);
            System.out.println("[CableBlue] Created new DataNet #" + newNet.getNetId() + " at " + pos);
            // connect() already called connectDevices() above, but this.network was
            // null at that point so connectDevices() returned immediately.
            // Re-call now that the network is set so adjacent devices are connected.
            this.connectDevices();
        }
        // When a network already existed, connect() called connectDevices() at
        // its end with the live network — no extra call needed.
    }

    /**
     * Connect to adjacent cables and devices
     */
    protected void connect() {
        for(ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity te = world.getTileEntity(pos.add(dir.offsetX, dir.offsetY, dir.offsetZ));

            // Connect to other cables (IDataConductor)
            if(te instanceof IDataConductor) {
                IDataConductor conductor = (IDataConductor) te;

                if(!conductor.canConnect(dir.toEnumFacing().getOpposite()))
                    continue;

                // Case 1: Join their network if we don't have one
                if(this.getDataNet() == null && conductor.getDataNet() != null) {
                    conductor.getDataNet().addConductor(this);
                    System.out.println("[CableBlue] Joined DataNet #" + conductor.getDataNet().getNetId() + " from cable at " + te.getPos());
                }

                // Case 3: Propagate our network to neighbor if they have none
                if(this.getDataNet() != null && conductor.getDataNet() == null) {
                    this.getDataNet().addConductor(conductor);
                }

                // Case 2: Merge networks if both exist and are different
                if(this.getDataNet() != null && conductor.getDataNet() != null &&
                   this.getDataNet() != conductor.getDataNet()) {
                    this.getDataNet().merge(conductor.getDataNet());
                    System.out.println("[CableBlue] Merged networks at " + pos);
                }
            }
        }

        // After connecting to cables, connect devices to our network
        this.connectDevices();
    }

    /**
     * Connect adjacent IDataConnector devices to this cable's network
     */
    protected void connectDevices() {
        if(this.network == null) {
            return;
        }

        for(ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            net.minecraft.util.math.BlockPos checkPos = pos.add(dir.offsetX, dir.offsetY, dir.offsetZ);
            TileEntity te = world.getTileEntity(checkPos);

            // Handle TileEntityProxyCombo - get the core block
            if(te instanceof com.hbm.main.tileentity.TileEntityProxyCombo) {
                com.hbm.main.tileentity.TileEntityProxyCombo proxy = (com.hbm.main.tileentity.TileEntityProxyCombo) te;
                te = proxy.getTile();
            }

            // Connect devices (IDataConnector) to our network
            if(te instanceof IDataConnector && !(te instanceof IDataConductor)) {
                IDataConnector device = (IDataConnector) te;

                // Check if device allows connection from this direction
                if(!device.canConnect(dir.toEnumFacing().getOpposite())) {
                    continue;
                }

                // Add device to our network if not already subscribed
                if(!this.network.getSubscribers().contains(device)) {
                    this.network.addSubscriber(device);
                    device.setDataNet(this.network);
                    System.out.println("[CableBlue] Connected device: " + device.getDeviceName() + " to DataNet #" + this.network.getNetId());
                }
            }
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();

        if(!world.isRemote) {
            if(this.network != null) {
                this.network.removeConductor(this);
                this.network = null;
            }
        }
    }

    @Override
    public boolean canConnect(EnumFacing dir) {
        return dir != null;
    }

    @Override
    public DataNet getDataNet() {
        return this.network;
    }

    @Override
    public void setDataNet(DataNet net) {
        this.network = net;
    }

    @Override
    public void reEvaluate() {
        // Force re-evaluation of network connections
        if(this.network != null) {
            this.network.removeConductor(this);
        }
        this.network = null;

        // Immediately try to reconnect
        if(!world.isRemote) {
            this.updateNetworkConnections();
        }
    }

    @Override
    public long getIdentity() {
        // Position-based hash for network tracking
        return pos.toLong();
    }

    @Override
    public void receiveData(DataPacket packet) {
        // Cables just pass data through, don't process it
        // Data is handled by the DataNet
    }

    @Override
    public DataDeviceType getDeviceType() {
        return DataDeviceType.CABLE;
    }

    @Override
    public UUID getDeviceId() {
        return this.deviceId;
    }

    @Override
    public String getDeviceName() {
        return "Blue Cable @ " + pos.toString();
    }

    @Override
    public boolean isActive() {
        return !this.isInvalid() && this.network != null;
    }
}
