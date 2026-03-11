package com.hbm.uninos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import com.hbm.util.Tuple.Pair;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * Unified Nodespace - A universal networking system for all multiblock structures.
 *
 * "Nodespace" is an invisible "dimension" where nodes exist. A node is the "soul" of
 * a tile entity with networking capabilities. Instead of tile entities finding each other
 * (costly and assumes loaded chunks), tiles create nodes at their positions in nodespace.
 * The nodespace handles connections which can happen even in unloaded chunks.
 *
 * A node is the "soul" of a tile entity which can act independent of its "body".
 *
 * @author Adapted for 1.12.2
 */
public class UniNodespace {

	/** Map of all node worlds per dimension */
	public static Map<World, UniNodeWorld> worlds = new HashMap<>();

	/** Set of all active networks across all dimensions */
	public static Set<NodeNet> activeNodeNets = new HashSet<>();

	/**
	 * Gets a node at the specified position and type.
	 *
	 * @param world The world
	 * @param x X coordinate
	 * @param y Y coordinate
	 * @param z Z coordinate
	 * @param type The network provider type
	 * @return The node at that position, or null if none exists
	 */
	public static GenNode getNode(World world, int x, int y, int z, INetworkProvider type) {
		UniNodeWorld nodeWorld = worlds.get(world);
		if (nodeWorld != null) {
			return nodeWorld.nodes.get(new Pair<>(new BlockPos(x, y, z), type));
		}
		return null;
	}

	/**
	 * Creates a new node in the nodespace.
	 *
	 * @param world The world to create the node in
	 * @param node The node to create
	 */
	public static void createNode(World world, GenNode node) {
		UniNodeWorld nodeWorld = worlds.get(world);
		if (nodeWorld == null) {
			nodeWorld = new UniNodeWorld();
			worlds.put(world, nodeWorld);
		}
		nodeWorld.pushNode(node);
	}

	/**
	 * Destroys a node at the specified position.
	 *
	 * @param world The world
	 * @param x X coordinate
	 * @param y Y coordinate
	 * @param z Z coordinate
	 * @param type The network provider type
	 */
	public static void destroyNode(World world, int x, int y, int z, INetworkProvider type) {
		GenNode node = getNode(world, x, y, z, type);
		if (node != null && worlds.get(world) != null) {
			worlds.get(world).popNode(node);
		}
	}

	/**
	 * Destroys the specified node.
	 *
	 * @param world The world
	 * @param node The node to destroy
	 */
	public static void destroyNode(World world, GenNode node) {
		if (node != null && worlds.get(world) != null) {
			worlds.get(world).popNode(node);
		}
	}

	/** Timer for periodic network cleanup */
	private static int reapTimer = 0;

	/**
	 * Main update loop for the entire nodespace.
	 * Called once per server tick.
	 * - Checks node connections
	 * - Updates all networks
	 * - Performs periodic cleanup
	 */
	public static void updateNodespace() {
		MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
		if (server == null) return;

		// Update all nodes in all worlds
		for (World world : server.worlds) {
			UniNodeWorld nodeWorld = worlds.get(world);
			if (nodeWorld == null) continue;

			// Check connections for each node
			for (Entry<Pair<BlockPos, INetworkProvider>, GenNode> entry : nodeWorld.nodes.entrySet()) {
				GenNode node = entry.getValue();
				INetworkProvider provider = entry.getKey().getValue();

				// If node has no valid network or recently changed, check connections
				if (!node.hasValidNet() || node.recentlyChanged) {
					checkNodeConnection(world, node, provider);
					node.recentlyChanged = false;
				}
			}
		}

		// Update all active networks
		updateNetworks();

		// Update cleanup timer
		updateReapTimer();
	}

	/**
	 * Updates all active networks.
	 */
	private static void updateNetworks() {
		// Reset trackers before update
		for (NodeNet net : activeNodeNets) {
			net.resetTrackers();
		}

		// Update all networks
		for (NodeNet net : activeNodeNets) {
			net.update();
		}

		// Periodic cleanup of expired nodes and empty networks
		if (reapTimer <= 0) {
			activeNodeNets.forEach((net) -> {
				net.links.removeIf((link) -> ((GenNode) link).expired);
			});
			activeNodeNets.removeIf((net) -> net.links.size() <= 0);
		}
	}

	/**
	 * Updates the cleanup timer.
	 * Cleanup runs every 5 minutes to remove expired nodes and empty networks.
	 */
	private static void updateReapTimer() {
		if (reapTimer <= 0) {
			reapTimer = 5 * 60 * 20; // 5 minutes in ticks
		} else {
			reapTimer--;
		}
	}

	/**
	 * Goes over each connection point of the given node and tries to find neighbor nodes.
	 * Joins networks with discovered neighbors.
	 *
	 * @param world The world
	 * @param node The node to check
	 * @param provider The network provider
	 */
	@SuppressWarnings("unchecked")
	private static void checkNodeConnection(World world, GenNode node, INetworkProvider provider) {
		// Check each connection point
		for (DirPos con : node.connections) {
			// Get neighbor node at this connection
			GenNode conNode = getNode(world, con.getX(), con.getY(), con.getZ(), provider);

			if (conNode != null) {
				// If both nodes already share the same valid network, skip
				if (conNode.hasValidNet() && conNode.net == node.net) {
					continue;
				}

				// Check if connection is valid (opposing directions)
				if (checkConnection(conNode, con, false)) {
					connectToNode(node, conNode);
				}
			}
		}

		// If node still has no valid network, create one
		if (node.net == null || !node.net.isValid()) {
			provider.provideNetwork().joinLink(node);
		}
	}

	/**
	 * Checks if a node can be connected given a DirPos connection.
	 *
	 * @param connectsTo The node to connect to
	 * @param connectFrom The connection point trying to connect
	 * @param skipSideCheck Whether to ignore direction matching
	 * @return True if connection is valid
	 */
	public static boolean checkConnection(GenNode connectsTo, DirPos connectFrom, boolean skipSideCheck) {
		// Check if the target node has a matching connection point
		for (DirPos revCon : connectsTo.connections) {
			BlockPos revPos = revCon.getPos();
			EnumFacing revDir = revCon.getDir();
			BlockPos fromPos = connectFrom.getPos();
			EnumFacing fromDir = connectFrom.getDir();

			// Calculate the position this connection comes from (subtract direction offset, matching 1.7.10 logic)
			BlockPos revSource = revPos.offset(revDir.getOpposite());

			// Check if positions match and directions are opposite
			if (revSource.equals(fromPos) &&
			    (revDir == fromDir.getOpposite() || skipSideCheck)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Links two nodes with different or potentially no networks.
	 *
	 * @param origin The originating node
	 * @param connection The node to connect to
	 */
	private static void connectToNode(GenNode origin, GenNode connection) {
		if (origin.hasValidNet() && connection.hasValidNet()) {
			// Both nodes have different networks - merge them
			if (origin.net.links.size() > connection.net.links.size()) {
				origin.net.joinNetworks(connection.net);
			} else {
				connection.net.joinNetworks(origin.net);
			}
		} else if (!origin.hasValidNet() && connection.hasValidNet()) {
			// Origin has no net, connection does - join connection's net
			connection.net.joinLink(origin);
		} else if (origin.hasValidNet() && !connection.hasValidNet()) {
			// Connection has no net, origin does - join origin's net
			origin.net.joinLink(connection);
		}
	}

	/**
	 * Per-world node storage.
	 */
	public static class UniNodeWorld {

		/** Map of nodes by position and provider type */
		public HashMap<Pair<BlockPos, INetworkProvider>, GenNode> nodes = new LinkedHashMap<>();

		/**
		 * Adds a node at all its positions to the nodespace.
		 *
		 * @param node The node to add
		 */
		public void pushNode(GenNode node) {
			for (BlockPos pos : node.positions) {
				nodes.put(new Pair<>(pos, node.networkProvider), node);
			}
		}

		/**
		 * Removes the specified node from all positions in nodespace.
		 *
		 * @param node The node to remove
		 */
		public void popNode(GenNode node) {
			// Destroy the network if this node was part of one
			if (node.net != null) {
				node.net.destroy();
			}

			// Remove from all positions
			for (BlockPos pos : node.positions) {
				nodes.remove(new Pair<>(pos, node.networkProvider));
			}

			// Mark as expired for cleanup
			node.expired = true;
		}
	}
}
