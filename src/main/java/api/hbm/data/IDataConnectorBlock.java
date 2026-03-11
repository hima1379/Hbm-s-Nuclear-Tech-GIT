package api.hbm.data;

import net.minecraft.util.EnumFacing;

/**
 * Interface for blocks that can connect to data cables
 * Checked by Library.canConnectData()
 */
public interface IDataConnectorBlock {

    /**
     * Check if this block can connect to data cables in a specific direction
     *
     * @param dir Direction to check
     * @return true if connection is allowed
     */
    boolean canConnect(EnumFacing dir);
}
