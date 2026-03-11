package com.hbm.explosion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;

/**
 * CHUNK PRIORITY MANAGER
 *
 * Purpose:
 * - Ensure chunks are loaded BEFORE explosion processing reaches them
 * - Process chunks in order from detonation point outward
 * - Avoid "missing chunk" errors during large explosions
 * - Optimize memory by loading/unloading chunks dynamically
 *
 * Algorithm:
 * 1. Calculate all chunks within explosion radius
 * 2. Sort by distance from ground zero
 * 3. Load chunks ahead of current processing radius
 * 4. Unload chunks behind processing radius (memory optimization)
 *
 * Integration:
 * - Called by FireballVolumeProcessor, BlastPressureFieldProcessor
 * - Uses ForgeChunkManager for reliable chunk loading
 * - Maintains loading buffer zone ahead of destruction
 */
public class ChunkPriorityManager {

	// === CORE PARAMETERS ===
	private final World world;
	private final double centerX, centerZ;
	private final double maxRadius;

	// === CHUNK LOADING ===
	private final PriorityQueue<ChunkDistance> chunkQueue;
	private final Map<ChunkPos, Ticket> loadedChunks;
	private final Ticket masterTicket;

	// === PROCESSING STATE ===
	private double currentLoadRadius = 0.0;
	private int chunksLoaded = 0;
	private int chunksUnloaded = 0;

	// === CONFIGURATION ===
	private static final double LOAD_BUFFER_RADIUS = 32.0; // Load 32m ahead of processing
	private static final double UNLOAD_DISTANCE = 64.0;    // Unload chunks 64m behind processing
	private static final int MAX_CHUNKS_PER_TICK = 16;     // Limit chunk loading rate

	/**
	 * Constructor
	 * @param world World
	 * @param centerX Explosion center X
	 * @param centerZ Explosion center Z
	 * @param maxRadius Maximum explosion radius (meters)
	 * @param modInstance Mod instance for ForgeChunkManager
	 */
	public ChunkPriorityManager(World world, double centerX, double centerZ, double maxRadius, Object modInstance) {
		this.world = world;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.maxRadius = maxRadius;

		// Initialize chunk queue sorted by distance
		this.chunkQueue = new PriorityQueue<>(new ChunkDistanceComparator());
		this.loadedChunks = new HashMap<>();

		// Request chunk loading ticket from Forge
		// Note: This requires the mod to register as a chunk loader in the main mod class
		this.masterTicket = ForgeChunkManager.requestTicket(modInstance, world, Type.NORMAL);

		// Calculate all chunks within explosion radius and add to queue
		calculateChunkQueue();

		printInitializationInfo();
	}

	/**
	 * Calculate all chunks within explosion radius and sort by distance
	 */
	private void calculateChunkQueue() {
		int centerChunkX = (int) Math.floor(centerX) >> 4;
		int centerChunkZ = (int) Math.floor(centerZ) >> 4;

		int chunkRadius = (int) Math.ceil(maxRadius / 16.0) + 2; // +2 for safety margin

		for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
			for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
				// Calculate chunk center position
				double chunkCenterX = (cx * 16) + 8.0;
				double chunkCenterZ = (cz * 16) + 8.0;

				// Calculate distance from explosion center to chunk center
				double dx = chunkCenterX - centerX;
				double dz = chunkCenterZ - centerZ;
				double distance = Math.sqrt(dx * dx + dz * dz);

				// Only include chunks within explosion radius
				if (distance <= maxRadius + 32.0) { // +32m margin
					ChunkPos pos = new ChunkPos(cx, cz);
					chunkQueue.add(new ChunkDistance(pos, distance));
				}
			}
		}

		System.out.println("[ChunkPriorityManager] Queued " + chunkQueue.size() + " chunks for loading");
	}

	/**
	 * Update chunk loading based on current processing radius
	 * Call this each tick during explosion processing
	 *
	 * @param currentProcessingRadius Current distance being processed (meters)
	 */
	public void updateChunkLoading(double currentProcessingRadius) {
		if (masterTicket == null) {
			System.err.println("[ChunkPriorityManager] WARNING: No chunk loading ticket available!");
			return;
		}

		// Calculate target load radius (processing radius + buffer)
		double targetLoadRadius = currentProcessingRadius + LOAD_BUFFER_RADIUS;

		// Load chunks ahead of processing
		int chunksLoadedThisTick = 0;
		while (!chunkQueue.isEmpty() && chunksLoadedThisTick < MAX_CHUNKS_PER_TICK) {
			ChunkDistance nextChunk = chunkQueue.peek();

			// Stop if chunk is beyond target load radius
			if (nextChunk.distance > targetLoadRadius) {
				break;
			}

			// Remove from queue and load
			chunkQueue.poll();
			loadChunk(nextChunk.pos);
			chunksLoadedThisTick++;
		}

		// Unload chunks far behind processing (memory optimization)
		List<ChunkPos> toUnload = new ArrayList<>();
		for (Map.Entry<ChunkPos, Ticket> entry : loadedChunks.entrySet()) {
			ChunkPos pos = entry.getKey();
			double chunkCenterX = (pos.x * 16) + 8.0;
			double chunkCenterZ = (pos.z * 16) + 8.0;
			double dx = chunkCenterX - centerX;
			double dz = chunkCenterZ - centerZ;
			double distance = Math.sqrt(dx * dx + dz * dz);

			// Unload if chunk is far behind current processing
			if (distance < currentProcessingRadius - UNLOAD_DISTANCE) {
				toUnload.add(pos);
			}
		}

		for (ChunkPos pos : toUnload) {
			unloadChunk(pos);
		}

		currentLoadRadius = targetLoadRadius;
	}

	/**
	 * Load a chunk and force it to stay loaded
	 */
	private void loadChunk(ChunkPos pos) {
		// Check if already loaded
		if (loadedChunks.containsKey(pos)) {
			return;
		}

		// Force chunk loading
		ForgeChunkManager.forceChunk(masterTicket, pos);

		// Also ensure world has the chunk loaded
		if (!world.isRemote) {
			Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.x, pos.z);
			if (chunk == null) {
				chunk = world.getChunkProvider().provideChunk(pos.x, pos.z);
			}
		}

		loadedChunks.put(pos, masterTicket);
		chunksLoaded++;
	}

	/**
	 * Unload a chunk to free memory
	 */
	private void unloadChunk(ChunkPos pos) {
		Ticket ticket = loadedChunks.remove(pos);
		if (ticket != null) {
			ForgeChunkManager.unforceChunk(ticket, pos);
			chunksUnloaded++;
		}
	}

	/**
	 * Check if a specific chunk is loaded
	 * @param blockX Block X coordinate
	 * @param blockZ Block Z coordinate
	 * @return true if chunk is loaded
	 */
	public boolean isChunkLoaded(int blockX, int blockZ) {
		int chunkX = blockX >> 4;
		int chunkZ = blockZ >> 4;
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		return loadedChunks.containsKey(pos) || world.isBlockLoaded(new net.minecraft.util.math.BlockPos(blockX, 64, blockZ));
	}

	/**
	 * Force load a specific chunk immediately
	 * Use this for critical operations that can't wait
	 */
	public void forceLoadChunk(int blockX, int blockZ) {
		int chunkX = blockX >> 4;
		int chunkZ = blockZ >> 4;
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		loadChunk(pos);
	}

	/**
	 * Cleanup - release all chunk loading tickets
	 * MUST be called when explosion completes or entity is removed
	 */
	public void cleanup() {
		System.out.println("[ChunkPriorityManager] Cleanup - releasing all chunks");

		// Release all forced chunks
		for (Map.Entry<ChunkPos, Ticket> entry : loadedChunks.entrySet()) {
			ForgeChunkManager.unforceChunk(entry.getValue(), entry.getKey());
		}

		loadedChunks.clear();
		chunkQueue.clear();

		// Release master ticket
		if (masterTicket != null) {
			ForgeChunkManager.releaseTicket(masterTicket);
		}

		System.out.println(String.format("[ChunkPriorityManager] Stats - Loaded: %d, Unloaded: %d",
			chunksLoaded, chunksUnloaded));
	}

	private void printInitializationInfo() {
		System.out.println("=== CHUNK PRIORITY MANAGER ===");
		System.out.println("Ground zero: " + (int) centerX + ", " + (int) centerZ);
		System.out.println("Maximum radius: " + (int) maxRadius + " m");
		System.out.println("Chunks in queue: " + chunkQueue.size());
		System.out.println("Load buffer: " + (int) LOAD_BUFFER_RADIUS + " m ahead");
		System.out.println("Unload distance: " + (int) UNLOAD_DISTANCE + " m behind");
		System.out.println("Strategy: Distance-based priority loading");
		System.out.println("==============================");
	}

	// === HELPER CLASSES ===

	/**
	 * Chunk position with distance from explosion center
	 */
	private static class ChunkDistance {
		final ChunkPos pos;
		final double distance;

		ChunkDistance(ChunkPos pos, double distance) {
			this.pos = pos;
			this.distance = distance;
		}
	}

	/**
	 * Comparator for sorting chunks by distance (nearest first)
	 */
	private static class ChunkDistanceComparator implements Comparator<ChunkDistance> {
		@Override
		public int compare(ChunkDistance a, ChunkDistance b) {
			return Double.compare(a.distance, b.distance);
		}
	}

	// === PUBLIC INTERFACE ===

	public int getChunksLoaded() {
		return chunksLoaded;
	}

	public int getChunksUnloaded() {
		return chunksUnloaded;
	}

	public int getRemainingChunks() {
		return chunkQueue.size();
	}

	public double getCurrentLoadRadius() {
		return currentLoadRadius;
	}
}
