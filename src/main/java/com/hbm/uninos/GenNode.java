package com.hbm.uninos;

import net.minecraft.util.math.BlockPos;

/**
 * Generic network node for UNINOS (Universal Node Space).
 * A node represents the "soul" of a tile entity in the network system,
 * capable of existing and connecting even when chunks are unloaded.
 *
 * @param <N> The type of NodeNet this node belongs to
 * @author Adapted for 1.12.2
 */
public class GenNode<N extends NodeNet> {

	/** The positions this node occupies in the world */
	public BlockPos[] positions;

	/** Connection points where this node can link to other nodes */
	public DirPos[] connections;

	/**
	 * The network this node belongs to.
	 * WARNING: Can be null for the first tick between node creation
	 * and network establishment. Always check hasValidNet() first!
	 */
	public N net;

	/** Whether this node has been destroyed and should be removed */
	public boolean expired = false;

	/** Whether the node's network connections have changed recently */
	public boolean recentlyChanged = true;

	/** Used for distinguishing the node type when saving to UNINOS' node map */
	public INetworkProvider networkProvider;

	/**
	 * Creates a new generic node at the specified positions.
	 *
	 * @param provider The network provider for this node type
	 * @param positions One or more positions this node occupies
	 */
	public GenNode(INetworkProvider<N> provider, BlockPos... positions) {
		this.networkProvider = provider;
		this.positions = positions;
	}

	/**
	 * Sets the connection points for this node.
	 *
	 * @param connections Directional positions where connections can be made
	 * @return This node for method chaining
	 */
	public GenNode<N> setConnections(DirPos... connections) {
		this.connections = connections;
		return this;
	}

	/**
	 * Adds a single connection point to this node.
	 *
	 * @param connection The directional position to add
	 * @return This node for method chaining
	 */
	public GenNode<N> addConnection(DirPos connection) {
		DirPos[] newCons = new DirPos[this.connections.length + 1];
		System.arraycopy(this.connections, 0, newCons, 0, this.connections.length);
		newCons[newCons.length - 1] = connection;
		this.connections = newCons;
		return this;
	}

	/**
	 * Checks if this node has a valid network assigned.
	 *
	 * @return True if the network is non-null and valid
	 */
	public boolean hasValidNet() {
		return this.net != null && this.net.isValid();
	}

	/**
	 * Assigns a network to this node.
	 *
	 * @param net The network to assign
	 */
	public void setNet(N net) {
		this.net = net;
		this.recentlyChanged = true;
	}
}
