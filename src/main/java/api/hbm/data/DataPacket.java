package api.hbm.data;

import net.minecraft.nbt.NBTTagCompound;

import java.util.UUID;

/**
 * Data packet for transmission through DataNet network
 * Used for radar data, missile commands, status updates, etc.
 */
public class DataPacket {

    /** Type of data packet */
    public enum DataPacketType {
        /** Radar scan results (contacts, positions, velocities) */
        RADAR_SCAN_DATA,

        /** Fire control radar illumination data */
        ILLUMINATION_DATA,

        /** Target designation for fire control radar (FCS -> SPG-62) */
        TARGET_DESIGNATION,

        /** Missile launch command */
        MISSILE_COMMAND,

        /** Midcourse guidance updates (SPY-1 -> SM-6 command guidance) */
        MIDCOURSE_GUIDANCE,

        /** Device status update */
        STATUS_UPDATE,

        /** Request for device information */
        DEVICE_QUERY,

        /** Response to device query */
        DEVICE_RESPONSE,

        /** Network synchronization */
        NET_SYNC
    }

    private final DataPacketType type;
    private final UUID sourceDevice;
    private final UUID targetDevice; // null for broadcast
    private final NBTTagCompound data;
    private final long timestamp;

    /**
     * Create a new data packet
     *
     * @param type Packet type
     * @param sourceDevice UUID of sending device
     * @param targetDevice UUID of target device (null for broadcast)
     * @param data Packet data as NBT
     */
    public DataPacket(DataPacketType type, UUID sourceDevice, UUID targetDevice, NBTTagCompound data) {
        this.type = type;
        this.sourceDevice = sourceDevice;
        this.targetDevice = targetDevice;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Broadcast constructor (no specific target)
     */
    public DataPacket(DataPacketType type, UUID sourceDevice, NBTTagCompound data) {
        this(type, sourceDevice, null, data);
    }

    public DataPacketType getType() {
        return type;
    }

    public UUID getSourceDevice() {
        return sourceDevice;
    }

    public UUID getTargetDevice() {
        return targetDevice;
    }

    public NBTTagCompound getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Check if this packet is a broadcast (no specific target)
     */
    public boolean isBroadcast() {
        return targetDevice == null;
    }

    /**
     * Check if this packet is for a specific device
     */
    public boolean isFor(UUID deviceId) {
        return targetDevice != null && targetDevice.equals(deviceId);
    }

    @Override
    public String toString() {
        return String.format("DataPacket[type=%s, source=%s, target=%s, age=%dms]",
                type, sourceDevice, targetDevice != null ? targetDevice : "BROADCAST",
                System.currentTimeMillis() - timestamp);
    }
}
