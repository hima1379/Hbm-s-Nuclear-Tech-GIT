package com.hbm.entity.logic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.World;

/**
 * Global manager for B-29 bomber formations
 * Manages all active formations across all worlds
 */
public class EntityBomberFormationManager {

	/** Singleton instance */
	private static EntityBomberFormationManager instance;

	/** Map of world dimension ID to formations map */
	private Map<Integer, Map<UUID, EntityBomberFormation>> worldFormations;

	private EntityBomberFormationManager() {
		this.worldFormations = new HashMap<>();
	}

	public static EntityBomberFormationManager getInstance() {
		if (instance == null) {
			instance = new EntityBomberFormationManager();
		}
		return instance;
	}

	/**
	 * Get formations map for a specific world
	 */
	private Map<UUID, EntityBomberFormation> getFormationsForWorld(World world) {
		int dimension = world.provider.getDimension();
		return worldFormations.computeIfAbsent(dimension, k -> new HashMap<>());
	}

	/**
	 * Create a new formation
	 */
	public EntityBomberFormation createFormation(World world, int targetX, int targetY, int targetZ, int bombingType) {
		UUID formationId = UUID.randomUUID();
		EntityBomberFormation formation = new EntityBomberFormation(formationId, targetX, targetY, targetZ, bombingType);
		getFormationsForWorld(world).put(formationId, formation);
		return formation;
	}

	/**
	 * Get formation by ID
	 */
	public EntityBomberFormation getFormation(World world, UUID formationId) {
		return getFormationsForWorld(world).get(formationId);
	}

	/**
	 * Find an active formation that is still accepting members for the given bombing type
	 * Returns null if no suitable formation exists
	 */
	public EntityBomberFormation findActiveFormation(World world, int bombingType) {
		for (EntityBomberFormation formation : getFormationsForWorld(world).values()) {
			if (formation.isAcceptingMembers()
					&& formation.getBombingType() == bombingType
					&& !formation.isFull()) {
				return formation;
			}
		}
		return null;
	}

	/**
	 * Remove a formation (cleanup when all bombers are destroyed)
	 */
	public void removeFormation(World world, UUID formationId) {
		getFormationsForWorld(world).remove(formationId);
	}

	/**
	 * Clean up formations with no bombers
	 */
	public void cleanupEmptyFormations(World world) {
		Map<UUID, EntityBomberFormation> formations = getFormationsForWorld(world);
		formations.entrySet().removeIf(entry -> entry.getValue().getBomberCount() == 0);
	}
}
