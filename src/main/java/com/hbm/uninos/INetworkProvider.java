package com.hbm.uninos;

/**
 * Each instance of a network provider represents a valid "type" of node in UNINOS.
 * Different providers create different network types (e.g., KlystronNetwork, PlasmaNetwork).
 *
 * @param <T> The type of NodeNet this provider creates
 * @author Adapted for 1.12.2
 */
public interface INetworkProvider<T extends NodeNet> {

	/**
	 * Creates a new network instance of the appropriate type.
	 * Called when a node needs to establish a new network.
	 *
	 * @return A new network instance
	 */
	public T provideNetwork();
}
