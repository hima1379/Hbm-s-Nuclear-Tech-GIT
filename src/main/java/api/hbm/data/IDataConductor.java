package api.hbm.data;

/**
 * Interface for data conductors (cables)
 * Similar to IEnergyConductor but for data transmission
 *
 * Conductors form DataNet networks that transmit data packets
 */
public interface IDataConductor extends IDataConnector {

    /**
     * Get the DataNet this conductor belongs to
     *
     * @return Current DataNet, or null if not connected
     */
    DataNet getDataNet();

    /**
     * Set the DataNet for this conductor
     * Called when networks merge or split
     *
     * @param net New DataNet
     */
    void setDataNet(DataNet net);

    /**
     * Re-evaluate network connections
     * Called when blocks are placed/removed nearby
     */
    void reEvaluate();

    /**
     * Get unique identity for network tracking
     * Usually position-based hash
     *
     * @return Unique identity long
     */
    long getIdentity();
}
