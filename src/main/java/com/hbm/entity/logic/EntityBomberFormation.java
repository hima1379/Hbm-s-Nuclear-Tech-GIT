package com.hbm.entity.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a formation of B-29 bombers
 * Formation layout:
 * - Single-line staggered formation with 10km spacing between each bomber
 * - All 30 bombers in row 0, sequentially positioned (column 0-29)
 * - Bombers spawn progressively farther from target to reduce lag
 */
public class EntityBomberFormation {

	public static final int MAX_BOMBERS_PER_FORMATION = 30;
	public static final int MAX_BOMBERS_PER_ROW = 30; // All bombers in single row

	/** Unique formation ID */
	private UUID formationId;

	/** List of bomber entity IDs in this formation */
	private List<UUID> bomberIds;

	/** Target coordinates */
	private int targetX, targetY, targetZ;

	/** Bombing type (0=carpet, 1=napalm, etc.) */
	private int bombingType;

	/** Whether leader has reached drop position */
	private boolean hasReachedDropPosition;

	/** Whether formation is still accepting new members */
	private boolean acceptingMembers;

	/** Current formation leader UUID */
	private UUID currentLeaderUUID;

	/** Row-based bomber tracking for leader succession */
	private List<UUID> row0Bombers = new ArrayList<>(); // Leader's row
	private List<UUID> row1Bombers = new ArrayList<>(); // Back row
	private List<UUID> row2Bombers = new ArrayList<>(); // Front row

	/** Formation approach vector (normalized) - all bombers approach from same direction */
	private double approachVectorX;
	private double approachVectorZ;

	/** Synchronized bombing system - ensures all bombers in same row drop simultaneously */
	private int formationTickCounter = 0;           // Global tick counter for formation
	private boolean row0CanBomb = false;            // Row 0 (leader row) bombing authorization
	private boolean row1CanBomb = false;            // Row 1 (back row) bombing authorization
	private boolean row2CanBomb = false;            // Row 2 (front row) bombing authorization

	public EntityBomberFormation(UUID formationId, int targetX, int targetY, int targetZ, int bombingType) {
		this.formationId = formationId;
		this.bomberIds = new ArrayList<>();
		this.targetX = targetX;
		this.targetY = targetY;
		this.targetZ = targetZ;
		this.bombingType = bombingType;
		this.hasReachedDropPosition = false;
		this.acceptingMembers = true;
		this.currentLeaderUUID = null;

		// Generate random approach direction for entire formation (all bombers approach from same direction)
		java.util.Random rand = new java.util.Random();
		double vecX = rand.nextDouble() - 0.5;
		double vecZ = rand.nextDouble() - 0.5;
		double magnitude = Math.sqrt(vecX * vecX + vecZ * vecZ);
		this.approachVectorX = vecX / magnitude; // Normalize
		this.approachVectorZ = vecZ / magnitude;
	}

	/**
	 * Add a bomber to the formation
	 * @return Formation position [row, column], or null if formation is full
	 */
	public int[] addBomber(UUID bomberId) {
		if (bomberIds.size() >= MAX_BOMBERS_PER_FORMATION) {
			return null;
		}

		bomberIds.add(bomberId);
		int index = bomberIds.size() - 1;

		// NEW: Single-line formation - all bombers in row 0, column = index (0-29)
		// This creates sequential 10km staggered spacing instead of lateral formation
		int row = 0;
		int column = index;

		// Track all bombers in row 0 for leader succession
		row0Bombers.add(bomberId);

		// First bomber is the leader
		if (currentLeaderUUID == null) {
			currentLeaderUUID = bomberId;
		}

		return new int[]{row, column};
	}

	/**
	 * Get bombing delay for a specific timing group (based on column ranges)
	 * NOTE: With individual bombing system, this is deprecated - each bomber bombs when reaching drop distance
	 * Timing groups (for backwards compatibility):
	 * - Group 0 (columns 0-9): Standard timing
	 * - Group 1 (columns 10-19): Delayed timing
	 * - Group 2 (columns 20-29): Early timing
	 */
	public int getBombingDelayForRow(int row) {
		switch(row) {
			case 2: return -100; // Early timing
			case 0: return 0;    // Standard timing
			case 1: return 200;  // Delayed timing
			default: return 0;
		}
	}

	// Getters and setters

	public UUID getFormationId() {
		return formationId;
	}

	public List<UUID> getBomberIds() {
		return bomberIds;
	}

	public int getBomberCount() {
		return bomberIds.size();
	}

	public int getTargetX() {
		return targetX;
	}

	public int getTargetY() {
		return targetY;
	}

	public int getTargetZ() {
		return targetZ;
	}

	public int getBombingType() {
		return bombingType;
	}

	public boolean hasReachedDropPosition() {
		return hasReachedDropPosition;
	}

	public void setReachedDropPosition(boolean reached) {
		this.hasReachedDropPosition = reached;
		if (reached) {
			this.acceptingMembers = false;
		}
	}

	public boolean isAcceptingMembers() {
		return acceptingMembers;
	}

	public boolean isFull() {
		return bomberIds.size() >= MAX_BOMBERS_PER_FORMATION;
	}

	public UUID getCurrentLeaderUUID() {
		return currentLeaderUUID;
	}

	public double getApproachVectorX() {
		return approachVectorX;
	}

	public double getApproachVectorZ() {
		return approachVectorZ;
	}

	/**
	 * Handle bomber death and leader succession
	 * @param deadBomberUUID UUID of the bomber that died
	 * @return true if formation is now empty (all bombers destroyed)
	 */
	public boolean handleBomberDeath(UUID deadBomberUUID) {
		// Remove from main list
		bomberIds.remove(deadBomberUUID);

		// Remove from row lists
		row0Bombers.remove(deadBomberUUID);
		row1Bombers.remove(deadBomberUUID);
		row2Bombers.remove(deadBomberUUID);

		// Check if formation is now empty
		if (bomberIds.isEmpty()) {
			currentLeaderUUID = null;
			System.out.println("[FORMATION] All bombers destroyed! Formation " + formationId + " dissolved.");
			return true; // Formation wiped out
		}

		// Check if leader died
		if (deadBomberUUID.equals(currentLeaderUUID)) {
			// Leader succession priority: row 0 > row 1 > row 2
			if (!row0Bombers.isEmpty()) {
				currentLeaderUUID = row0Bombers.get(0);
				System.out.println("[FORMATION] Leader killed! New leader from Row 0: " + currentLeaderUUID);
			} else if (!row1Bombers.isEmpty()) {
				currentLeaderUUID = row1Bombers.get(0);
				System.out.println("[FORMATION] Row 0 wiped out! New leader from Row 1: " + currentLeaderUUID);
			} else if (!row2Bombers.isEmpty()) {
				currentLeaderUUID = row2Bombers.get(0);
				System.out.println("[FORMATION] Row 0 and 1 wiped out! New leader from Row 2: " + currentLeaderUUID);
			}
		}

		return false; // Formation still has bombers
	}

	/**
	 * Check if this bomber is the current leader
	 */
	public boolean isLeader(UUID bomberUUID) {
		return bomberUUID.equals(currentLeaderUUID);
	}

	/**
	 * Synchronized bombing system methods
	 */
	public void incrementFormationTick() {
		formationTickCounter++;
	}

	public int getFormationTickCounter() {
		return formationTickCounter;
	}

	public void setRow0CanBomb(boolean canBomb) {
		this.row0CanBomb = canBomb;
	}

	public void setRow1CanBomb(boolean canBomb) {
		this.row1CanBomb = canBomb;
	}

	public void setRow2CanBomb(boolean canBomb) {
		this.row2CanBomb = canBomb;
	}

	public boolean canRowBomb(int row) {
		if(row == 0) return row0CanBomb;
		if(row == 1) return row1CanBomb;
		if(row == 2) return row2CanBomb;
		return false;
	}

	/**
	 * Formation-wide chunk pre-loading flag
	 * When true, all bombers in formation have pre-loaded their bombing corridors
	 */
	private boolean formationChunksPreloaded = false;

	public boolean isFormationChunksPreloaded() {
		return formationChunksPreloaded;
	}

	public void setFormationChunksPreloaded(boolean loaded) {
		this.formationChunksPreloaded = loaded;
	}
}

