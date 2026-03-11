package com.hbm.hazard.type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.hbm.config.GeneralConfig;
import com.hbm.handler.AtmosphericDispersionSystem;
import com.hbm.hazard.modifier.HazardModifier;
import com.hbm.items.ModItems;
import com.hbm.lib.Library;
import com.hbm.util.ContaminationUtil;
import com.hbm.util.ContaminationUtil.ContaminationType;
import com.hbm.util.ContaminationUtil.HazardType;
import com.hbm.util.I18nUtil;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class HazardTypeRadiation extends HazardTypeBase {

	// -----------------------------------------------------------------
	// Item-drop atmospheric dispersion
	// -----------------------------------------------------------------

	/**
	 * Effective plume height for items lying on the ground [m].
	 * Very close to ground level — items emit from ~0.3 m above the surface.
	 */
	private static final double ITEM_PLUME_HEIGHT_M = 0.5;

	/**
	 * Scale factor converting hazard level to atmospheric emission rate
	 * [game_rad·m²/s].  Tunable to balance near-field vs. far-field spread.
	 */
	private static final double ITEM_EMISSION_SCALE = 1.0;

	/**
	 * How long between heartbeat sweeps that stop sources for items that are
	 * no longer on the ground [ms].  Items that are picked up, burned, or
	 * despawn stop calling updateEntity (HazardSystem guards on isDead), so
	 * we detect their absence by checking that they haven't been seen within
	 * this interval.
	 */
	private static final long ITEM_CLEANUP_INTERVAL_MS = 15_000L;

	/**
	 * Maps EntityItem.getEntityId() → int[]{ sourceId, dimensionId }.
	 *
	 * Populated when an item first lands on the ground and a continuous
	 * PlumeSource is registered.  Removed by the heartbeat sweep when the
	 * item is no longer seen on the ground, at which point the source is
	 * sealed via {@link AtmosphericDispersionSystem#stopSourceById}.
	 */
	private static final ConcurrentHashMap<Integer, int[]> itemSourceMap =
			new ConcurrentHashMap<>();

	/**
	 * Maps EntityItem.getEntityId() → last-seen wall-clock ms while on ground.
	 * Updated every tick the item is on the ground.  Used by the heartbeat
	 * sweep to detect items that have left the ground.
	 */
	private static final ConcurrentHashMap<Integer, Long> itemLastSeenMs =
			new ConcurrentHashMap<>();

	/** Wall-clock time of the next heartbeat sweep [ms]. */
	private static volatile long nextCleanupMs = 0L;

	@Override
	public void onUpdate(EntityLivingBase target, float level, ItemStack stack) {
		
		boolean reacher = false;
		
		if(target instanceof EntityPlayer && !GeneralConfig.enable528)
			reacher = Library.checkForHeld((EntityPlayer) target, ModItems.reacher);
			
		if(level > 0) {
			float rad = level / 20F;
			
			if(reacher)
				rad = (float) Math.min(Math.sqrt(rad), rad); //to prevent radiation from going up when being <1
			
			ContaminationUtil.contaminate(target, HazardType.RADIATION, ContaminationType.CREATIVE, rad);
		}
	}

	/**
	 * Called every server tick for each live dropped EntityItem.
	 *
	 * <p><b>Lifecycle:</b>
	 * <ol>
	 *   <li>When the item first lands on the ground, a <em>continuous</em>
	 *       PlumeSource is registered so that radionuclides disperse downwind
	 *       via the MACCS2 segmented-plume Gaussian model while the item sits
	 *       on the surface.</li>
	 *   <li>Each tick the item is on the ground, a heartbeat timestamp is
	 *       updated.  If the item is airborne (thrown, bouncing), the source
	 *       is sealed immediately.</li>
	 *   <li>Items that are picked up, burned, or despawn stop calling
	 *       updateEntity (HazardSystem guards on isDead).  A periodic sweep
	 *       every {@value #ITEM_CLEANUP_INTERVAL_MS} ms detects these stale
	 *       entries and seals their sources.</li>
	 *   <li>After sealing, the MACCS2 segments continue to contribute catch-up
	 *       deposition to unloaded chunks for up to 5 hours, modelling the
	 *       real behaviour of airborne particulates after the release ends.</li>
	 * </ol>
	 */
	@Override
	public void updateEntity(EntityItem item, float level) {
		if (item.world.isRemote || level <= 0.0f) return;

		int entityId = item.getEntityId();
		long now = System.currentTimeMillis();

		// --- Heartbeat sweep: stop sources for items no longer on the ground ---
		// Items that have been picked up or despawned stop calling updateEntity,
		// so we detect stale entries by checking the last-seen timestamp.
		if (now > nextCleanupMs) {
			nextCleanupMs = now + ITEM_CLEANUP_INTERVAL_MS;
			List<Integer> stale = new ArrayList<>();
			for (Map.Entry<Integer, Long> e : itemLastSeenMs.entrySet()) {
				if (now - e.getValue() > ITEM_CLEANUP_INTERVAL_MS) {
					stale.add(e.getKey());
				}
			}
			for (int eid : stale) {
				itemLastSeenMs.remove(eid);
				int[] pair = itemSourceMap.remove(eid);
				if (pair != null) {
					net.minecraft.world.WorldServer w =
							net.minecraftforge.common.DimensionManager.getWorld(pair[1]);
					if (w != null) {
						AtmosphericDispersionSystem.get(w).stopSourceById(pair[0]);
					}
				}
			}
		}

		// --- Item left the ground (thrown / bouncing): seal source immediately ---
		if (!item.onGround) {
			itemLastSeenMs.remove(entityId);
			int[] pair = itemSourceMap.remove(entityId);
			if (pair != null) {
				net.minecraft.world.WorldServer w =
						net.minecraftforge.common.DimensionManager.getWorld(pair[1]);
				if (w != null) {
					AtmosphericDispersionSystem.get(w).stopSourceById(pair[0]);
				}
			}
			return;
		}

		// --- Item on ground: update heartbeat ---
		itemLastSeenMs.put(entityId, now);

		// Already registered — nothing more to do this tick.
		if (itemSourceMap.containsKey(entityId)) return;

		// --- First landing: register a continuous plume source ---
		// The source emits while the item rests on the ground.  On departure,
		// the source is sealed and its segments continue to disperse downwind
		// for up to 5 hours per the MACCS2 transport time framework.
		double rate = level * ITEM_EMISSION_SCALE;
		com.hbm.physics.air.PlumeSource src =
				AtmosphericDispersionSystem.get(item.world).registerSource(
						item.posX, item.posY, item.posZ,
						ITEM_PLUME_HEIGHT_M,
						rate,
						com.hbm.physics.air.PlumeSource.CONTINUOUS);
		itemSourceMap.put(entityId,
				new int[]{ src.id, item.world.provider.getDimension() });
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void addHazardInformation(EntityPlayer player, List<String> list, float level, ItemStack stack, List<HazardModifier> modifiers) {
		
		level = HazardModifier.evalAllModifiers(stack, player, level, modifiers);
		if(level == 0) return;
		list.add("§a[" + I18nUtil.resolveKey("trait.radioactive") + "]");
		list.add(" §e" + (Library.roundFloat(getNewValue(level), 3)+ getSuffix(level) + " " + I18nUtil.resolveKey("desc.rads")));
			
		if(stack.getCount() > 1) {
			float stackRad = level * stack.getCount();
			list.add(" §e" + I18nUtil.resolveKey("desc.stack")+" " + Library.roundFloat(getNewValue(stackRad), 3) + getSuffix(stackRad) + " " + I18nUtil.resolveKey("desc.rads"));
		}
	}


	public static float getNewValue(float radiation){
		if(radiation < 1000000){
			return radiation;
		} else if(radiation < 1000000000){
			return radiation * 0.000001F;
		} else{
			return radiation * 0.000000001F;
		}
	}

	public static String getSuffix(float radiation){
		if(radiation < 1000000){
			return "";
		} else if(radiation < 1000000000){
			return I18nUtil.resolveKey("desc.mil");
		} else{
			return I18nUtil.resolveKey("desc.bil");
		}
	}
}