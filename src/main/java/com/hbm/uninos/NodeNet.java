package com.hbm.uninos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import api.hbm.energy.ILoadedTile;
import net.minecraft.tileentity.TileEntity;

/**
 * Abstract base class for all network types in UNINOS.
 * Manages links (nodes), receivers, and providers in the network.
 *
 * @param <R> Receiver type (e.g., IFusionPowerReceiver)
 * @param <P> Provider type (e.g., TileEntityFusionKlystron)
 * @param <L> Link type (extends GenNode)
 * @author Adapted for 1.12.2
 */
public abstract class NodeNet<R, P, L extends GenNode> {

	/** Global random for distribution calculations */
	public static Random rand = new Random();

	/** Whether this network is still valid */
	public boolean valid = true;

	/** All nodes that are part of this network */
	public Set<L> links = new LinkedHashSet<>();

	/** Receivers subscribed to this network with last update timestamp */
	public HashMap<R, Long> receiverEntries = new HashMap<>();

	/** Providers connected to this network with last update timestamp */
	public HashMap<P, Long> providerEntries = new HashMap<>();

	/**
	 * Creates a new network and registers it as active.
	 */
	public NodeNet() {
		UniNodespace.activeNodeNets.add(this);
	}

	// ===== RECEIVER MANAGEMENT =====

	/**
	 * Checks if a receiver is subscribed to this network.
	 */
	public boolean isSubscribed(R receiver) {
		return this.receiverEntries.containsKey(receiver);
	}

	/**
	 * Adds a receiver to this network.
	 */
	public void addReceiver(R receiver) {
		this.receiverEntries.put(receiver, System.currentTimeMillis());
	}

	/**
	 * Removes a receiver from this network.
	 */
	public void removeReceiver(R receiver) {
		this.receiverEntries.remove(receiver);
	}

	// ===== PROVIDER MANAGEMENT =====

	/**
	 * Checks if a provider is connected to this network.
	 */
	public boolean isProvider(P provider) {
		return this.providerEntries.containsKey(provider);
	}

	/**
	 * Adds a provider to this network.
	 */
	public void addProvider(P provider) {
		this.providerEntries.put(provider, System.currentTimeMillis());
	}

	/**
	 * Removes a provider from this network.
	 */
	public void removeProvider(P provider) {
		this.providerEntries.remove(provider);
	}

	// ===== NETWORK MERGING =====

	/**
	 * Combines two networks into one.
	 * Transfers all links, receivers, and providers from the other network.
	 */
	@SuppressWarnings("unchecked")
	public void joinNetworks(NodeNet network) {
		if (network == this) return;

		// Transfer all links from the other network
		List<L> oldNodes = new ArrayList<>(network.links.size());
		oldNodes.addAll((Set<L>) network.links);

		for (L conductor : oldNodes) {
			forceJoinLink(conductor);
		}
		network.links.clear();

		// Transfer receivers and providers
		for (Object connector : network.receiverEntries.keySet()) {
			this.addReceiver((R) connector);
		}
		for (Object connector : network.providerEntries.keySet()) {
			this.addProvider((P) connector);
		}

		network.destroy();
	}

	// ===== LINK MANAGEMENT =====

	/**
	 * Adds a node as part of this network's links.
	 * Removes the node from its previous network if applicable.
	 */
	public NodeNet joinLink(L node) {
		if (node.net != null) {
			node.net.leaveLink(node);
		}
		return forceJoinLink(node);
	}

	/**
	 * Adds a node as part of this network's links without removing it from existing networks.
	 * Used during network merging.
	 */
	public NodeNet forceJoinLink(L node) {
		this.links.add(node);
		node.setNet(this);
		return this;
	}

	/**
	 * Removes the specified node from this network.
	 */
	public void leaveLink(L node) {
		node.setNet(null);
		this.links.remove(node);
	}

	// ===== NETWORK CONTROL =====

	/**
	 * Marks this network as invalid and removes it from active networks.
	 */
	public void invalidate() {
		this.valid = false;
		UniNodespace.activeNodeNets.remove(this);
	}

	/**
	 * Checks if this network is still valid.
	 */
	public boolean isValid() {
		return this.valid;
	}

	/**
	 * Resets tracking variables before network update.
	 * Override in subclasses to reset energy/fluid trackers.
	 */
	public void resetTrackers() {
	}

	/**
	 * Updates this network's state.
	 * Must be implemented by subclasses to handle energy/fluid transfer.
	 */
	public abstract void update();

	/**
	 * Destroys this network and cleans up all references.
	 */
	@SuppressWarnings("unchecked")
	public void destroy() {
		this.invalidate();
		for (GenNode link : this.links) {
			if (link.net == this) {
				link.setNet(null);
			}
		}
		this.links.clear();
		this.receiverEntries.clear();
		this.providerEntries.clear();
	}

	/**
	 * Checks if a tile entity should be removed from the network.
	 * Returns true if the tile is unloaded or invalid.
	 */
	public static boolean isBadLink(Object o) {
		if (o instanceof ILoadedTile && !((ILoadedTile) o).isLoaded()) {
			return true;
		}
		if (o instanceof TileEntity && ((TileEntity) o).isInvalid()) {
			return true;
		}
		return false;
	}
}
