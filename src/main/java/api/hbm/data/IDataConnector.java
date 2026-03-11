package api.hbm.data;

import net.minecraft.util.EnumFacing;

import java.util.UUID;

/**
 * Interface for devices that can connect to the DataNet network
 * Implemented by TileEntities that send/receive data
 */
public interface IDataConnector {

    /**
     * Receive a data packet from the network
     *
     * @param packet The data packet to receive
     */
    void receiveData(DataPacket packet);

    /**
     * Check if this device can connect in a specific direction
     *
     * @param dir Direction to check
     * @return true if connection is allowed
     */
    boolean canConnect(EnumFacing dir);

    /**
     * Get the device type
     *
     * @return Device type enum
     */
    DataDeviceType getDeviceType();

    /**
     * Get unique device identifier
     *
     * @return UUID of this device
     */
    UUID getDeviceId();

    /**
     * Get device display name
     *
     * @return Human-readable device name
     */
    String getDeviceName();

    /**
     * Check if device is currently active/operational
     *
     * @return true if device is active
     */
    boolean isActive();

    /**
     * Get the DataNet this device is connected to
     *
     * @return Current DataNet or null if not connected
     */
    DataNet getDataNet();

    /**
     * Set the DataNet this device is connected to
     * Called by cables when connecting the device to a network
     *
     * @param net DataNet to connect to
     */
    void setDataNet(DataNet net);
}
