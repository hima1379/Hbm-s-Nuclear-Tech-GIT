package api.hbm.data;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data network for transmitting information between connected devices
 * Similar to PowerNet but for data packets instead of energy
 */
public class DataNet {

    private static long nextNetId = 0;
    private final long netId;

    // =====================================================================
    // GLOBAL REGISTRY
    // Tracks every live DataNet so that devices on different cable segments
    // (e.g. LaunchPad on #0 and SPY-1 on #5) can still discover each other.
    // Invariant: a DataNet is in the registry iff it has not been absorbed
    // by merge().  When two cable runs are physically joined, their DataNets
    // merge into one and the absorbed net is removed from the registry.
    // =====================================================================
    private static final Set<DataNet> GLOBAL_REGISTRY = ConcurrentHashMap.newKeySet();

    /**
     * Return an unmodifiable snapshot of every live DataNet.
     * Callers (e.g. LaunchPad looking for SPY-1) iterate this set when the
     * target device lives on a different cable segment.
     */
    public static Set<DataNet> getAllNetworks() {
        return new HashSet<>(GLOBAL_REGISTRY);
    }

    /**
     * Find the first subscriber of the given class across ALL live DataNets.
     * Searches the caller's own DataNet first (preferred, same segment).
     *
     * @param preferredNet  DataNet to check first (may be null)
     * @param type          Subscriber class to look for
     * @return              First matching subscriber, or null if none found
     */
    @SuppressWarnings("unchecked")
    public static <T extends IDataConnector> T findSubscriberOfType(DataNet preferredNet, Class<T> type) {
        // 1. Preferred network first (same cable segment)
        if (preferredNet != null) {
            for (IDataConnector sub : preferredNet.getSubscribers()) {
                if (type.isInstance(sub)) {
                    return (T) sub;
                }
            }
        }
        // 2. All other live networks (different cable segment)
        for (DataNet net : GLOBAL_REGISTRY) {
            if (net == preferredNet) continue;
            for (IDataConnector sub : net.getSubscribers()) {
                if (type.isInstance(sub)) {
                    return (T) sub;
                }
            }
        }
        return null;
    }

    // Network components
    private final Set<IDataConductor> conductors = ConcurrentHashMap.newKeySet();
    private final Set<IDataConnector> subscribers = ConcurrentHashMap.newKeySet();

    // Packet queue for buffered transmission
    private final Set<DataPacket> packetQueue = ConcurrentHashMap.newKeySet();

    /**
     * Create a new DataNet and register it in the global registry.
     */
    public DataNet() {
        this.netId = nextNetId++;
        GLOBAL_REGISTRY.add(this);
        System.out.println("[DataNet] Created new network #" + netId +
                " (global registry size: " + GLOBAL_REGISTRY.size() + ")");
    }

    /**
     * Add a conductor (cable) to this network
     *
     * @param conductor Cable to add
     */
    public void addConductor(IDataConductor conductor) {
        if (conductors.add(conductor)) {
            conductor.setDataNet(this);
            System.out.println("[DataNet #" + netId + "] Added conductor (total: " + conductors.size() + ")");
        }
    }

    /**
     * Remove a conductor from this network
     *
     * @param conductor Cable to remove
     */
    public void removeConductor(IDataConductor conductor) {
        if (conductors.remove(conductor)) {
            conductor.setDataNet(null);
            System.out.println("[DataNet #" + netId + "] Removed conductor (remaining: " + conductors.size() + ")");

            // If network is empty, deregister and allow GC
            if (conductors.isEmpty() && subscribers.isEmpty()) {
                GLOBAL_REGISTRY.remove(this);
                System.out.println("[DataNet #" + netId + "] Network is now empty — removed from global registry");
            }
        }
    }

    /**
     * Add a subscriber (device) to this network
     *
     * @param subscriber Device to add
     */
    public void addSubscriber(IDataConnector subscriber) {
        if (subscribers.add(subscriber)) {
            subscriber.setDataNet(this);
            System.out.println("[DataNet #" + netId + "] Added subscriber: " +
                    subscriber.getDeviceName() + " (total: " + subscribers.size() + ")");
        }
    }

    /**
     * Remove a subscriber from this network
     *
     * @param subscriber Device to remove
     */
    public void removeSubscriber(IDataConnector subscriber) {
        if (subscribers.remove(subscriber)) {
            System.out.println("[DataNet #" + netId + "] Removed subscriber: " +
                    subscriber.getDeviceName() + " (remaining: " + subscribers.size() + ")");

            // If both conductors and subscribers are gone, deregister so this
            // network can be GC'd and won't pollute findSubscriberOfType().
            if (conductors.isEmpty() && subscribers.isEmpty()) {
                GLOBAL_REGISTRY.remove(this);
                System.out.println("[DataNet #" + netId + "] Network is now empty — removed from global registry");
            }
        }
    }

    /**
     * Broadcast a data packet to all subscribers on this network
     *
     * @param packet Packet to broadcast
     */
    public void broadcastData(DataPacket packet) {
        if (packet.isBroadcast()) {
            // Broadcast to all active subscribers
            int delivered = 0;
            int skipped = 0;
            for (IDataConnector subscriber : subscribers) {
                if (subscriber.isActive()) {
                    subscriber.receiveData(packet);
                    delivered++;
                } else {
                    skipped++;
                    System.out.println("[DataNet #" + netId + "] SKIP " + packet.getType()
                        + " -> " + subscriber.getDeviceName()
                        + " (isActive=false)");
                }
            }
            System.out.println("[DataNet #" + netId + "] Broadcast " + packet.getType()
                    + " delivered=" + delivered + " skipped=" + skipped);
        } else {
            // Targeted packet - find specific device
            UUID target = packet.getTargetDevice();
            boolean delivered = false;
            for (IDataConnector subscriber : subscribers) {
                if (subscriber.getDeviceId().equals(target) && subscriber.isActive()) {
                    subscriber.receiveData(packet);
                    delivered = true;
                    break;
                }
            }
            if (!delivered) {
                System.out.println("[DataNet #" + netId + "] WARNING: Could not deliver packet to " + target);
            }
        }
    }

    /**
     * Merge another DataNet into this one.
     * All conductors and subscribers are transferred, and the absorbed network
     * is removed from the global registry so it cannot be found by getAllNetworks().
     *
     * @param other Network to merge (will be emptied and deregistered)
     */
    public void merge(DataNet other) {
        if (other == this) return;

        System.out.println("[DataNet] Merging network #" + other.netId + " into #" + netId);

        // Transfer all conductors
        Set<IDataConductor> otherConductors = new HashSet<>(other.conductors);
        for (IDataConductor conductor : otherConductors) {
            other.removeConductor(conductor);
            this.addConductor(conductor);
        }

        // Transfer all subscribers
        Set<IDataConnector> otherSubscribers = new HashSet<>(other.subscribers);
        for (IDataConnector subscriber : otherSubscribers) {
            other.removeSubscriber(subscriber);
            this.addSubscriber(subscriber);
        }

        // The absorbed network is now empty — remove it from the global registry
        GLOBAL_REGISTRY.remove(other);

        System.out.println("[DataNet #" + netId + "] Merge complete. Total conductors: " +
                conductors.size() + ", subscribers: " + subscribers.size() +
                " | Registry size now: " + GLOBAL_REGISTRY.size());
    }

    /**
     * Get all conductors in this network
     */
    public Set<IDataConductor> getConductors() {
        return new HashSet<>(conductors);
    }

    /**
     * Get all subscribers in this network
     */
    public Set<IDataConnector> getSubscribers() {
        return new HashSet<>(subscribers);
    }

    /**
     * Get network ID
     */
    public long getNetId() {
        return netId;
    }

    /**
     * Check if network is empty
     */
    public boolean isEmpty() {
        return conductors.isEmpty() && subscribers.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("DataNet#%d[conductors=%d, subscribers=%d]",
                netId, conductors.size(), subscribers.size());
    }
}
