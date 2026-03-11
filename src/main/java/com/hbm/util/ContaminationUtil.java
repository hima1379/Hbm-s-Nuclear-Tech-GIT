package com.hbm.util;

import java.util.List;

import com.hbm.capability.HbmLivingCapability.EntityHbmProps;
import com.hbm.capability.HbmLivingCapability;
import com.hbm.capability.HbmLivingProps;
import com.hbm.config.CompatibilityConfig;
import com.hbm.config.GeneralConfig;
import com.hbm.config.RealBombConfig;
import com.hbm.entity.mob.EntityNuclearCreeper;
import com.hbm.entity.mob.EntityQuackos;
import com.hbm.entity.projectile.EntityBulletBase;
import com.hbm.entity.projectile.EntityExplosiveBeam;
import com.hbm.entity.projectile.EntityMiniMIRV;
import com.hbm.entity.projectile.EntityMiniNuke;
import com.hbm.entity.effect.EntityNukeTorex;
import com.hbm.entity.effect.EntityBlackHole;
import com.hbm.entity.logic.EntityNukeExplosionMK5;
import com.hbm.entity.grenade.EntityGrenadeASchrab;
import com.hbm.entity.grenade.EntityGrenadeNuclear;
import com.hbm.entity.missile.EntityMIRV;
import com.hbm.handler.ArmorUtil;
import com.hbm.handler.HazmatRegistry;
import com.hbm.interfaces.IRadiationImmune;
import com.hbm.items.ModItems;
import com.hbm.items.armor.ArmorPenetrationSystem;
import com.hbm.lib.Library;
import com.hbm.lib.ModDamageSource;
import com.hbm.render.amlfrom1710.Vec3;
import com.hbm.util.ArmorRegistry.HazardClass;
import com.hbm.util.BobMathUtil;
import com.hbm.potion.HbmPotion;
import com.hbm.saveddata.RadiationSavedData;
import com.hbm.hazard.HazardSystem;
import com.hbm.hazard.type.HazardTypeRadiation;
import com.hbm.physics.nuke.FireballPhysicsCalculator;
import com.hbm.physics.ThermalDamageSystem;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityMooshroom;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityZombieHorse;
import net.minecraft.entity.passive.EntitySkeletonHorse;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.world.World;

import static com.hbm.entity.logic.EntityNukeExplosionMK5.shockSpeed;

public class ContaminationUtil {

	public static final String NTM_NEUTRON_NBT_KEY = "ntmNeutron";

	/**
	 * Calculates how much radiation can be applied to this entity by calculating resistance
	 * @param entity
	 * @return
	 */
	public static float calculateRadiationMod(EntityLivingBase entity) {

		if(entity.isPotionActive(HbmPotion.mutation))
			return 0;
		float mult = 1;
		if(entity.getEntityData().hasKey("hbmradmultiplier", 99))
			mult = entity.getEntityData().getFloat("hbmradmultiplier");

		float koeff = 10.0F;
		return (float) Math.pow(koeff, -(getConfigEntityRadResistance(entity) + HazmatRegistry.getResistance(entity))) * mult;
	}

	private static void applyRadData(Entity e, float f) {

		if(e instanceof IRadiationImmune)
			return;

		if(!(e instanceof EntityLivingBase entity))
			return;

		if(e instanceof EntityPlayer && (((EntityPlayer) e).capabilities.isCreativeMode || ((EntityPlayer) e).isSpectator()))
			return;

		if(e instanceof EntityPlayer && e.ticksExisted < 200)
			return;

		f *= calculateRadiationMod(entity);

		if(entity.hasCapability(HbmLivingCapability.EntityHbmPropsProvider.ENT_HBM_PROPS_CAP, null)) {
			HbmLivingCapability.IEntityHbmProps ent = entity.getCapability(HbmLivingCapability.EntityHbmPropsProvider.ENT_HBM_PROPS_CAP, null);
			ent.increaseRads(f);
		}
	}

	private static void applyRadDirect(Entity entity, float f) {

		if(entity instanceof IRadiationImmune)
			return;

		if(entity.getEntityData().hasKey("hbmradmultiplier", 99))
			f *= entity.getEntityData().getFloat("hbmradmultiplier");

		if(entity instanceof EntityPlayer && (((EntityPlayer) entity).capabilities.isCreativeMode || ((EntityPlayer) entity).isSpectator()))
			return;

		if(!(entity instanceof EntityLivingBase))
			return;

		if(((EntityLivingBase) entity).isPotionActive(HbmPotion.mutation))
			return;

		if(entity.hasCapability(HbmLivingCapability.EntityHbmPropsProvider.ENT_HBM_PROPS_CAP, null)) {
			HbmLivingCapability.IEntityHbmProps ent = entity.getCapability(HbmLivingCapability.EntityHbmPropsProvider.ENT_HBM_PROPS_CAP, null);
			ent.increaseRads(f);
		}
	}

	public static void printGeigerData(EntityPlayer player) {

		double eRad = ((long)(HbmLivingProps.getRadiation(player) * 1000)) / 1000D;

		RadiationSavedData data = RadiationSavedData.getData(player.world);
		double rads = ((long)(data.getRadNumFromCoord(player.getPosition()) * 1000D)) / 1000D;
		double env = ((long)(getPlayerRads(player) * 1000D)) / 1000D;

		double res = Library.roundFloat((1D-ContaminationUtil.calculateRadiationMod(player))*100D, 6);
		double resKoeff = ((long)(HazmatRegistry.getResistance(player) * 1000D)) / 1000D;

		double rec = ((long)(env* (100-res)/100D * 1000D))/ 1000D;

		String chunkPrefix = getPreffixFromRad(rads);
		String envPrefix = getPreffixFromRad(env);
		String recPrefix = getPreffixFromRad(rec);
		String radPrefix = "";
		String resPrefix = "" + TextFormatting.WHITE;

		if(eRad < 200)
			radPrefix += TextFormatting.GREEN;
		else if(eRad < 400)
			radPrefix += TextFormatting.YELLOW;
		else if(eRad < 600)
			radPrefix += TextFormatting.GOLD;
		else if(eRad < 800)
			radPrefix += TextFormatting.RED;
		else if(eRad < 1000)
			radPrefix += TextFormatting.DARK_RED;
		else
			radPrefix += TextFormatting.DARK_GRAY;

		if(resKoeff > 0)
			resPrefix += TextFormatting.GREEN;

		player.sendMessage(new TextComponentString("===== ☢ ").appendSibling(new TextComponentTranslation("geiger.title")).appendSibling(new TextComponentString(" ☢ =====")).setStyle(new Style().setColor(TextFormatting.GOLD)));
		player.sendMessage(new TextComponentTranslation("geiger.chunkRad").appendSibling(new TextComponentString(" " + chunkPrefix + rads + " RAD/s")).setStyle(new Style().setColor(TextFormatting.YELLOW)));
		player.sendMessage(new TextComponentTranslation("geiger.envRad").appendSibling(new TextComponentString(" " + envPrefix + env + " RAD/s")).setStyle(new Style().setColor(TextFormatting.YELLOW)));
		player.sendMessage(new TextComponentTranslation("geiger.recievedRad").appendSibling(new TextComponentString(" " + recPrefix + rec + " RAD/s")).setStyle(new Style().setColor(TextFormatting.YELLOW)));
		player.sendMessage(new TextComponentTranslation("geiger.playerRad").appendSibling(new TextComponentString(" " + radPrefix + eRad + " RAD")).setStyle(new Style().setColor(TextFormatting.YELLOW)));
		player.sendMessage(new TextComponentTranslation("geiger.playerRes").appendSibling(new TextComponentString(" " + resPrefix + String.format("%.6f", res) + "% (" + resKoeff + ")")).setStyle(new Style().setColor(TextFormatting.YELLOW)));
	}

	public static void printDosimeterData(EntityPlayer player) {

		double rads = ContaminationUtil.getActualPlayerRads(player);
		boolean limit = false;

		if(rads > 3.6D) {
			rads = 3.6D;
			limit = true;
		}
		rads = ((int)(1000D * rads))/ 1000D;
		String radsPrefix = getPreffixFromRad(rads);

		player.sendMessage(new TextComponentString("===== ☢ ").appendSibling(new TextComponentTranslation("dosimeter.title")).appendSibling(new TextComponentString(" ☢ =====")).setStyle(new Style().setColor(TextFormatting.GOLD)));
		player.sendMessage(new TextComponentTranslation("geiger.recievedRad").appendSibling(new TextComponentString(" " + radsPrefix + (limit ? ">" : "") + rads + " RAD/s")).setStyle(new Style().setColor(TextFormatting.YELLOW)));
	}

	public static String getTextColorFromPercent(double percent){
		if(percent < 0.5)
			return ""+TextFormatting.GREEN;
		else if(percent < 0.6)
			return ""+TextFormatting.YELLOW;
		else if(percent < 0.7)
			return ""+TextFormatting.GOLD;
		else if(percent < 0.8)
			return ""+TextFormatting.RED;
		else if(percent < 0.9)
			return ""+TextFormatting.DARK_RED;
		else
			return ""+TextFormatting.DARK_GRAY;
	}

	public static String getTextColorLung(double percent){
		if(percent > 0.9)
			return ""+TextFormatting.GREEN;
		else if(percent > 0.75)
			return ""+TextFormatting.YELLOW;
		else if(percent > 0.5)
			return ""+TextFormatting.GOLD;
		else if(percent > 0.25)
			return ""+TextFormatting.RED;
		else if(percent > 0.1)
			return ""+TextFormatting.DARK_RED;
		else
			return ""+TextFormatting.DARK_GRAY;
	}

	public static void printDiagnosticData(EntityPlayer player) {

		double digamma = ((int)(HbmLivingProps.getDigamma(player) * 1000)) / 1000D;
		double halflife = ((int)((1D - Math.pow(0.5, digamma)) * 10000)) / 100D;

		player.sendMessage(new TextComponentString("===== Γ ").appendSibling(new TextComponentTranslation("digamma.title")).appendSibling(new TextComponentString(" Γ =====")).setStyle(new Style().setColor(TextFormatting.DARK_PURPLE)));
		player.sendMessage(new TextComponentTranslation("digamma.playerDigamma").appendSibling(new TextComponentString(TextFormatting.RED + " " + digamma + " DRX")).setStyle(new Style().setColor(TextFormatting.LIGHT_PURPLE)));
		player.sendMessage(new TextComponentTranslation("digamma.playerHealth").appendSibling(new TextComponentString(getTextColorFromPercent(halflife/100D) + String.format(" %6.2f", halflife) + "%")).setStyle(new Style().setColor(TextFormatting.LIGHT_PURPLE)));
	}

	public static void printLungDiagnosticData(EntityPlayer player) {

		float playerAsbestos = 100F-((int)(10000F * HbmLivingProps.getAsbestos(player) / EntityHbmProps.maxAsbestos))/100F;
		float playerBlacklung = 100F-((int)(10000F * HbmLivingProps.getBlackLung(player) / EntityHbmProps.maxBlacklung))/100F;
		float playerTotal = (playerAsbestos * playerBlacklung/100F);
		int contagion = HbmLivingProps.getContagion(player);

		player.sendMessage(new TextComponentString("===== L ").appendSibling(new TextComponentTranslation("lung_scanner.title")).appendSibling(new TextComponentString(" L =====")).setStyle(new Style().setColor(TextFormatting.WHITE)));
		player.sendMessage(new TextComponentTranslation("lung_scanner.player_asbestos_health").setStyle(new Style().setColor(TextFormatting.WHITE)).appendSibling(new TextComponentString(String.format(getTextColorLung(playerAsbestos/100D)+" %6.2f", playerAsbestos)+" %")));
		player.sendMessage(new TextComponentTranslation("lung_scanner.player_coal_health").setStyle(new Style().setColor(TextFormatting.DARK_GRAY)).appendSibling(new TextComponentString(String.format(getTextColorLung(playerBlacklung/100D)+" %6.2f", playerBlacklung)+" %")));
		player.sendMessage(new TextComponentTranslation("lung_scanner.player_total_health").setStyle(new Style().setColor(TextFormatting.GRAY)).appendSibling(new TextComponentString(String.format(getTextColorLung(playerTotal/100D)+" %6.2f", playerTotal)+" %")));
		player.sendMessage(new TextComponentTranslation("lung_scanner.player_mku").setStyle(new Style().setColor(TextFormatting.GRAY)).appendSibling(new TextComponentTranslation(contagion > 0 ? "lung_scanner.pos" : "lung_scanner.neg" )));
		if(contagion > 0){
			player.sendMessage(new TextComponentTranslation("lung_scanner.player_mku_duration").setStyle(new Style().setColor(TextFormatting.GRAY)).appendSibling(new TextComponentString(" §c"+BobMathUtil.ticksToDateString(contagion, 72000))));
		}
	}

	public static double getActualPlayerRads(EntityLivingBase entity) {
		return getPlayerRads(entity) * (double)(ContaminationUtil.calculateRadiationMod(entity));
	}

	public static double getPlayerRads(EntityLivingBase entity) {
		double rads = HbmLivingProps.getRadBuf(entity);
		if(entity instanceof EntityPlayer)
			rads = rads + HbmLivingProps.getNeutron((EntityPlayer)entity)*20;
		return rads;
	}

	public static double getNoNeutronPlayerRads(EntityLivingBase entity) {
		return (double)(HbmLivingProps.getRadBuf(entity)) * (double)(ContaminationUtil.calculateRadiationMod(entity));
	}

	public static float getPlayerNeutronRads(EntityPlayer player){
		float radBuffer = 0F;
		for(ItemStack slotI : player.inventory.mainInventory){
			radBuffer = radBuffer + getNeutronRads(slotI);
		}
		for(ItemStack slotA : player.inventory.armorInventory){
			radBuffer = radBuffer + getNeutronRads(slotA);
		}
		return radBuffer;
	}

	public static boolean isRadItem(ItemStack stack){
		if(stack == null)
			return false;

		if(HazardSystem.getRawRadsFromStack(stack) > 0){
			return true;
		}

		return false;
	}

	public static float getNeutronRads(ItemStack stack){
		if(stack != null && !stack.isEmpty() && !isRadItem(stack)){
			if(stack.hasTagCompound()){
				NBTTagCompound nbt = stack.getTagCompound();
				if(nbt.hasKey(NTM_NEUTRON_NBT_KEY)){
					return nbt.getFloat(NTM_NEUTRON_NBT_KEY) * stack.getCount();
				}
			}
		}
		return 0F;
	}

	public static void addNeutronRadInfo(ItemStack stack, EntityPlayer player, List<String> list, ITooltipFlag flagIn){
		float activationRads = getNeutronRads(stack);
		if(activationRads > 0) {
			list.add("§a[" + I18nUtil.resolveKey("trait.radioactive") + "]");
			float stackRad = activationRads / stack.getCount();
			list.add(" §e" + Library.roundFloat(HazardTypeRadiation.getNewValue(stackRad), 3) + HazardTypeRadiation.getSuffix(stackRad) + " RAD/s");

			if(stack.getCount() > 1) {
				list.add(" §eStack: " + Library.roundFloat(HazardTypeRadiation.getNewValue(activationRads), 3) + HazardTypeRadiation.getSuffix(activationRads) + " RAD/s");
			}
		}
	}

	public static void neutronActivateInventory(EntityPlayer player, float rad, float decay){
		for(int slotI = 0; slotI < player.inventory.getSizeInventory()-1; slotI++){
			if(slotI != player.inventory.currentItem)
				neutronActivateItem(player.inventory.getStackInSlot(slotI), rad, decay);
		}
		for(ItemStack slotA : player.inventory.armorInventory){
			neutronActivateItem(slotA, rad, decay);
		}
	}

	public static void neutronActivateItem(ItemStack stack, float rad, float decay){
		if(stack != null && !stack.isEmpty() && stack.getCount() == 1 && !isRadItem(stack)){

			NBTTagCompound nbt;
			if(stack.hasTagCompound()){
				nbt = stack.getTagCompound();
			} else{
				nbt = new NBTTagCompound();
			}
			float prevActivation = 0;
			if(nbt.hasKey(NTM_NEUTRON_NBT_KEY)){
				prevActivation = nbt.getFloat(NTM_NEUTRON_NBT_KEY);
			}

			if(prevActivation + rad == 0)
				return;

			float newActivation = prevActivation * decay + (rad / stack.getCount());
			if(prevActivation * decay + rad < 0.0001F || (rad <= 0 && newActivation < 0.001F )){
				nbt.removeTag(NTM_NEUTRON_NBT_KEY);
			} else {
				nbt.setFloat(NTM_NEUTRON_NBT_KEY, newActivation);
			}
			if(nbt.isEmpty()){
				stack.setTagCompound(null);
			} else {
				stack.setTagCompound(nbt);
			}
		}
	}

	public static boolean isContaminated(ItemStack stack){
		if(!stack.hasTagCompound())
			return false;
		if(stack.getTagCompound().hasKey(NTM_NEUTRON_NBT_KEY))
			return true;
		return false;
	}

	public static String getPreffixFromRad(double rads) {

		String chunkPrefix = "";

		if(rads == 0)
			chunkPrefix += TextFormatting.GREEN;
		else if(rads < 1)
			chunkPrefix += TextFormatting.YELLOW;
		else if(rads < 10)
			chunkPrefix += TextFormatting.GOLD;
		else if(rads < 100)
			chunkPrefix += TextFormatting.RED;
		else if(rads < 1000)
			chunkPrefix += TextFormatting.DARK_RED;
		else
			chunkPrefix += TextFormatting.DARK_GRAY;

		return chunkPrefix;
	}

	public static float getRads(Entity e) {
		if(e instanceof IRadiationImmune)
			return 0.0F;
		if(e instanceof EntityLivingBase)
			return HbmLivingProps.getRadiation((EntityLivingBase)e);
		return 0.0F;
	}

	public static float getConfigEntityRadResistance(Entity e){
		float totalResistanceValue = 0.0F;
		if(!(e instanceof EntityPlayer)){
			ResourceLocation entity_path = EntityList.getKey(e);
			Object resistanceMod = CompatibilityConfig.mobModRadresistance.get(entity_path.getNamespace());
			Object resistanceMob = CompatibilityConfig.mobRadresistance.get(entity_path.toString());
			if(resistanceMod != null){
				totalResistanceValue = totalResistanceValue + (float)resistanceMod;
			}
			if(resistanceMob != null){
				totalResistanceValue = totalResistanceValue + (float)resistanceMob;
			}
		}
		return totalResistanceValue;
	}

	public static boolean checkConfigEntityImmunity(Entity e){
		if(!(e instanceof EntityPlayer)){
			ResourceLocation entity_path = EntityList.getKey(e);
			if(entity_path != null){
				if(CompatibilityConfig.mobModRadimmune.contains(entity_path.getNamespace())){
					return true;
				}else{
					return CompatibilityConfig.mobRadimmune.contains(entity_path.toString());
				}
			}
		}
		return false;
	}

	public static boolean isRadImmune(Entity e) {
		if(e instanceof EntityLivingBase && ((EntityLivingBase)e).isPotionActive(HbmPotion.mutation))
			return true;

		return 	e instanceof EntityZombie ||
				e instanceof EntitySkeleton ||
				e instanceof EntityQuackos ||
				e instanceof EntityOcelot ||
				e instanceof EntityMooshroom ||
				e instanceof EntityZombieHorse ||
				e instanceof EntitySkeletonHorse ||
				e instanceof EntityArmorStand ||
				e instanceof EntityItemFrame ||
				e instanceof EntityIronGolem ||
				e instanceof IRadiationImmune || checkConfigEntityImmunity(e);
	}

	/// ASBESTOS ///

	public static void applyAsbestos(Entity e, int i, int dmg) {
		applyAsbestos(e, i, dmg, 1);
	}

	public static void applyAsbestos(Entity e, int i, int dmg, int chance) {

		if(!GeneralConfig.enableAsbestos)
			return;

		if(!(e instanceof EntityLivingBase))
			return;

		if(e instanceof EntityPlayer && ((EntityPlayer)e).capabilities.isCreativeMode)
			return;

		if(e instanceof EntityPlayer && e.ticksExisted < 200)
			return;

		EntityLivingBase entity = (EntityLivingBase)e;

		if(ArmorRegistry.hasProtection(entity, EntityEquipmentSlot.HEAD, HazardClass.PARTICLE_FINE)){
			if(chance > 1){
				if(entity.world.rand.nextInt(chance) == 0){
					ArmorUtil.damageGasMaskFilter(entity, 1);
				}
			}
			else{
				ArmorUtil.damageGasMaskFilter(entity, dmg);
			}
		}
		else{
			HbmLivingProps.incrementAsbestos(entity, i);
		}
	}

	public static void applyCoal(Entity e, int i, int dmg) {
		applyCoal(e, i, dmg, 1);
	}

	/// COAL ///
	public static void applyCoal(Entity e, int i, int dmg, int chance) {

		if(!GeneralConfig.enableCoal)
			return;

		if(!(e instanceof EntityLivingBase))
			return;

		if(e instanceof EntityPlayer && ((EntityPlayer)e).capabilities.isCreativeMode)
			return;

		if(e instanceof EntityPlayer && e.ticksExisted < 200)
			return;

		EntityLivingBase entity = (EntityLivingBase)e;

		if(ArmorRegistry.hasProtection(entity, EntityEquipmentSlot.HEAD, HazardClass.PARTICLE_COARSE)){
			if(chance > 1){
				if(entity.world.rand.nextInt(chance) == 0){
					ArmorUtil.damageGasMaskFilter(entity, 1);
				}
			}
			else{
				ArmorUtil.damageGasMaskFilter(entity, dmg);
			}
		}
		else{
			HbmLivingProps.incrementBlackLung(entity, i);
		}
	}

	/// DIGAMMA ///
	public static void applyDigammaData(Entity e, float f) {

		if(!(e instanceof EntityLivingBase))
			return;

		if(e instanceof EntityQuackos || e instanceof EntityOcelot)
			return;

		if(e instanceof EntityPlayer && ((EntityPlayer)e).capabilities.isCreativeMode)
			return;

		if(e instanceof EntityPlayer && e.ticksExisted < 200)
			return;

		EntityLivingBase entity = (EntityLivingBase)e;

		if(entity.isPotionActive(HbmPotion.stability))
			return;

		if(!(entity instanceof EntityPlayer && ArmorUtil.checkForDigamma((EntityPlayer) entity)))
			HbmLivingProps.incrementDigamma(entity, f);
	}

	public static void applyDigammaDirect(Entity e, float f) {

		if(!(e instanceof EntityLivingBase))
			return;

		if(e instanceof IRadiationImmune)
			return;

		if(e instanceof EntityPlayer && ((EntityPlayer)e).capabilities.isCreativeMode)
			return;

		EntityLivingBase entity = (EntityLivingBase)e;
		HbmLivingProps.incrementDigamma(entity, f);
	}

	public static float getDigamma(Entity e) {

		if(!(e instanceof EntityLivingBase))
			return 0.0F;

		EntityLivingBase entity = (EntityLivingBase)e;
		return HbmLivingProps.getDigamma(entity);
	}

	public static void radiate(World world, double x, double y, double z, double range, float rad3d) {
		radiate(world, x, y, z, range, rad3d, 0, 0, 0, 0);
	}

	public static void radiate(World world, double x, double y, double z, double range, float rad3d, float dig3d, float fire3d) {
		radiate(world, x, y, z, range, rad3d, dig3d, fire3d, 0, 0);
	}

	public static void radiate(World world, double x, double y, double z, double range, float rad3d, float dig3d, float fire3d, float blast3d) {
		radiate(world, x, y, z, range, rad3d, dig3d, fire3d, blast3d, range);
	}

	/**
	 * REALISTIC NUCLEAR WEAPON DAMAGE MODEL
	 *
	 * Implements physics-accurate damage from nuclear weapons:
	 * 1. THERMAL RADIATION - Vaporization and severe burns
	 * 2. BLAST WAVE - Overpressure and internal organ damage
	 * 3. PROMPT RADIATION - Initial gamma and neutron radiation
	 *
	 * References:
	 * - Glasstone & Dolan "The Effects of Nuclear Weapons" (1977)
	 * - NATO Handbook on the Medical Aspects of NBC Operations
	 * - Defense Nuclear Agency reports
	 *
	 * @param world World
	 * @param x Ground zero X
	 * @param y Ground zero Y
	 * @param z Ground zero Z
	 * @param range Maximum damage range
	 * @param rad3d Radiation intensity
	 * @param dig3d Digamma intensity
	 * @param fire3d Thermal energy
	 * @param blast3d Blast energy
	 * @param blastRange Blast wave range
	 */
	public static void radiate(World world, double x, double y, double z, double range, float rad3d, float dig3d, float fire3d, float blast3d, double blastRange) {
		List<Entity> entities = world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(x-range, y-range, z-range, x+range, y+range, z+range));

		for(Entity e : entities) {
			if(isExplosionExempt(e)) continue;

			Vec3 vec = Vec3.createVectorHelper(e.posX - x, (e.posY + e.getEyeHeight()) - y, e.posZ - z);
			double len = vec.length();

			if(len > range) continue;
			vec = vec.normalize();
			double dmgLen = Math.max(len, range * 0.05D);

			// Calculate block resistance along ray path
			float res = 0;
			for(int i = 1; i < len; i++) {
				int ix = (int)Math.floor(x + vec.xCoord * i);
				int iy = (int)Math.floor(y + vec.yCoord * i);
				int iz = (int)Math.floor(z + vec.zCoord * i);
				res += world.getBlockState(new BlockPos(ix, iy, iz)).getBlock().getExplosionResistance(null);
			}

			boolean isLiving = e instanceof EntityLivingBase;

			if(res < 1) res = 1;

			// === BLAST WAVE DAMAGE ===
			if(isLiving && blast3d > 0 && len < blastRange) {
				applyBlastDamage((EntityLivingBase)e, len, dmgLen, blastRange, blast3d, res, rad3d > 0, vec);
			}

			// === PROMPT RADIATION ===
			if(isLiving && rad3d > 0){
				float eRads = rad3d;
				eRads /= (float)(dmgLen * dmgLen * Math.sqrt(res));
				contaminate((EntityLivingBase)e, HazardType.RADIATION, ContaminationType.CREATIVE, eRads);
			}

			// === DIGAMMA RADIATION ===
			if(isLiving && dig3d > 0){
				float eDig = dig3d;
				eDig /= (float)(dmgLen * dmgLen * dmgLen);
				contaminate((EntityLivingBase)e, HazardType.DIGAMMA, ContaminationType.DIGAMMA, eDig);
			}
		}
	}

	/**
	 * PHYSICS-BASED NUCLEAR THERMAL DAMAGE
	 *
	 * Uses FireballPhysicsCalculator (two-pulse model, PDF §2.123) to compute the
	 * temperature at the entity's distance, then routes damage through ThermalDamageSystem.
	 *
	 * Designed for EntityNukeExplosionMK5 with a real weapon yield and a freeze-corrected
	 * thermal clock that pauses during chunk-based destruction and resumes afterward.
	 *
	 * @param yieldKilotons       Weapon yield in kilotons
	 * @param effectiveThermalTick Physics time tick (freeze-corrected during chunk destruction)
	 * @param blast3d             Blast energy
	 * @param blastRange          Active blast wave range (ticksExisted × shockSpeed)
	 */
	public static void radiate(World world, double x, double y, double z, double range,
			float rad3d, float dig3d,
			double yieldKilotons, long effectiveThermalTick,
			float blast3d, double blastRange) {

		// Convert Minecraft ticks to real seconds (20 ticks/s)
		double timeSeconds = effectiveThermalTick / 20.0;

		List<Entity> entities = world.getEntitiesWithinAABB(Entity.class,
				new AxisAlignedBB(x - range, y - range, z - range, x + range, y + range, z + range));

		for (Entity e : entities) {
			if (isExplosionExempt(e)) continue;

			Vec3 vec = Vec3.createVectorHelper(e.posX - x, (e.posY + e.getEyeHeight()) - y, e.posZ - z);
			double len = vec.length();

			if (len > range) continue;
			vec = vec.normalize();
			double dmgLen = Math.max(len, range * 0.05D);

			// Calculate block resistance along ray path
			float res = 0;
			for (int i = 1; i < (int)len; i++) {
				int ix = (int)Math.floor(x + vec.xCoord * i);
				int iy = (int)Math.floor(y + vec.yCoord * i);
				int iz = (int)Math.floor(z + vec.zCoord * i);
				res += world.getBlockState(new BlockPos(ix, iy, iz)).getBlock().getExplosionResistance(null);
			}
			if (res < 1) res = 1;

			boolean isLiving = e instanceof EntityLivingBase;

			// === PHYSICS-BASED THERMAL RADIATION DAMAGE ===
			if (isLiving && yieldKilotons > 0) {
				applyThermalDamagePhysics((EntityLivingBase) e, timeSeconds, yieldKilotons, len);
			}

			// === BLAST WAVE DAMAGE ===
			if (isLiving && blast3d > 0 && len < blastRange) {
				applyBlastDamage((EntityLivingBase) e, len, dmgLen, blastRange, blast3d, res, rad3d > 0, vec);
			}

			// === PROMPT RADIATION ===
			if (isLiving && rad3d > 0) {
				float eRads = rad3d;
				eRads /= (float)(dmgLen * dmgLen * Math.sqrt(res));
				contaminate((EntityLivingBase) e, HazardType.RADIATION, ContaminationType.CREATIVE, eRads);
			}

			// === DIGAMMA RADIATION ===
			if (isLiving && dig3d > 0) {
				float eDig = dig3d;
				eDig /= (float)(dmgLen * dmgLen * dmgLen);
				contaminate((EntityLivingBase) e, HazardType.DIGAMMA, ContaminationType.DIGAMMA, eDig);
			}
		}
	}

	/**
	 * Apply physics-based thermal radiation damage.
	 *
	 * Temperature at the entity's position is computed via
	 * FireballPhysicsCalculator.calculateTemperatureAtDistanceCelsius() (inverse-square
	 * law on the two-pulse fireball surface temperature).  The result is handed to
	 * ThermalDamageSystem which handles:
	 *   - 100°C–1499°C  → linear burn damage (1×–15× scale)
	 *   - ≥ 1500°C       → ArmorPenetrationSystem super-penetration
	 *   - IHeatResistanceArmor → cancels penetration if resistance ≥ temperature
	 *
	 * Also includes the marshmallow roasting easter egg for marginal heating (100–200°C).
	 */
	private static void applyThermalDamagePhysics(EntityLivingBase entity,
			double timeSeconds, double yieldKilotons, double distanceMeters) {

		double tempCelsius = FireballPhysicsCalculator.calculateTemperatureAtDistanceCelsius(
				timeSeconds, yieldKilotons, distanceMeters);

		if (tempCelsius < ThermalDamageSystem.MIN_THERMAL_TEMP) return;

		ThermalDamageSystem.applyThermalDamage(entity, tempCelsius, ThermalDamageSystem.BASE_FIRE_DAMAGE);

		// Marshmallow roasting easter egg (marginal heating zone: 100–200°C)
		if (entity instanceof EntityPlayer && tempCelsius < 200.0) {
			EntityPlayer p = (EntityPlayer) entity;
			int rngBound = Math.max(1, (int) distanceMeters);
			if (p.getHeldItemMainhand().getItem() == ModItems.marshmallow
					&& p.getRNG().nextInt(rngBound) == 0) {
				p.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(ModItems.marshmallow_roasted));
			}
			if (p.getHeldItemOffhand().getItem() == ModItems.marshmallow
					&& p.getRNG().nextInt(rngBound) == 0) {
				p.setHeldItem(EnumHand.OFF_HAND, new ItemStack(ModItems.marshmallow_roasted));
			}
		}
	}

	/**
	 * APPLY REALISTIC BLAST WAVE DAMAGE
	 *
	 * Blast wave consists of two components:
	 * 1. OVERPRESSURE - Instantaneous pressure spike
	 * 2. DYNAMIC PRESSURE - High-velocity winds
	 *
	 * Effects by overpressure:
	 *
	 * 200+ PSI: Complete destruction, body torn apart
	 * 50-200 PSI: Severe internal injuries, body parts severed
	 * 20-50 PSI: Lung collapse, internal bleeding, broken bones
	 * 10-20 PSI: Severe internal injuries, ruptured organs
	 * 5-10 PSI: Eardrum rupture, lung damage, moderate injuries
	 * 2-5 PSI: Minor injuries, temporary hearing loss
	 * <2 PSI: Minimal direct injuries
	 *
	 * CRITICAL: Armor cannot protect against overpressure.
	 * Blast waves cause internal organ damage through compression.
	 * Even full armor cannot prevent lung collapse or internal bleeding.
	 *
	 * Reference: Glasstone & Dolan, Chapter VI
	 */
	private static void applyBlastDamage(EntityLivingBase entity, double distance, double effectiveDistance,
										 double blastRange, float blastEnergy, float obstacleResistance,
										 boolean isNuclear, Vec3 direction) {

		// Calculate base blast damage
		float baseBlastDamage = blastEnergy / (float)(effectiveDistance * effectiveDistance * obstacleResistance);

		// Only apply blast damage in active shockwave region
		if(blastRange - shockSpeed * 2 < distance) {

			// Calculate distance-based damage multiplier
			double distanceRatio = distance / blastRange;
			float damageMultiplier = (float)(1.0 - distanceRatio);

			if(baseBlastDamage > 0.025f) {
				// OVERPRESSURE DAMAGE - Armor-bypassing internal injuries
				float overpressureDamage = baseBlastDamage * damageMultiplier;

				// Categorize damage by intensity
				if(overpressureDamage > 50.0f) {
					// EXTREME: Body torn apart
					ArmorPenetrationSystem.dealAbsoluteDamageBypassAll(entity, overpressureDamage, Integer.MAX_VALUE);

					if(entity instanceof EntityPlayer) {
						((EntityPlayer)entity).sendMessage(new TextComponentString(
								"§4§l[EXTREME OVERPRESSURE] Catastrophic internal trauma!"));
					}

				} else if(overpressureDamage > 20.0f) {
					// SEVERE: Organ rupture, severe internal bleeding
					ArmorPenetrationSystem.dealAbsoluteDamageBypassAll(entity, overpressureDamage, Integer.MAX_VALUE);
					// (combined into dealAbsoluteDamageBypassAll above)

					if(entity instanceof EntityPlayer) {
						((EntityPlayer)entity).sendMessage(new TextComponentString(
								"§c§l[SEVERE OVERPRESSURE] Major internal injuries!"));
					}

				} else if(overpressureDamage > 5.0f) {
					// MODERATE: Lung damage, internal injuries
					ArmorPenetrationSystem.dealAbsoluteDamageBypassAll(entity, overpressureDamage, Integer.MAX_VALUE);
					// (combined into dealAbsoluteDamageBypassAll above)

					if(entity instanceof EntityPlayer && entity.getRNG().nextFloat() < 0.7f) {
						((EntityPlayer)entity).sendMessage(new TextComponentString(
								"§6[OVERPRESSURE] Internal injuries from blast wave!"));
					}

				} else {
					// MINOR: Eardrum damage, minor injuries
					ArmorPenetrationSystem.dealAbsoluteDamageBypassAll(entity, overpressureDamage, Integer.MAX_VALUE);
				}

				// DYNAMIC PRESSURE - Knockback effect (wind blast)
				double knockbackMultiplier = Math.min(overpressureDamage / 10.0, 5.0);
				entity.motionX += direction.xCoord * 0.015D * knockbackMultiplier;
				entity.motionY += direction.yCoord * 0.015D * knockbackMultiplier;
				entity.motionZ += direction.zCoord * 0.015D * knockbackMultiplier;
			}
		}
	}

	/**
	 * Create armor-bypassing damage source
	 * Simulates damage that cannot be blocked by armor (thermal radiation, overpressure)
	 */
	private static DamageSource createArmorBypassDamage(String type, EntityLivingBase entity) {
		DamageSource source = new DamageSource(type);
		source.setDamageBypassesArmor();
		source.setDamageIsAbsolute(); // Cannot be reduced
		return source;
	}

	/**
	 * Create partial armor-bypass damage
	 * Some portion bypasses armor, rest is normal
	 */
	private static DamageSource createPartialArmorBypassDamage(String type, EntityLivingBase entity, float bypassRatio) {
		// Apply bypassing portion
		if(bypassRatio > 0) {
			DamageSource bypass = new DamageSource(type + "_bypass");
			bypass.setDamageBypassesArmor();
			entity.attackEntityFrom(bypass, entity.getMaxHealth() * bypassRatio * 0.1f);
		}

		// Return normal damage source for remaining portion
		return new DamageSource(type);
	}

	private static boolean isExplosionExempt(Entity e) {

		if (e instanceof EntityOcelot ||
				e instanceof EntityNukeTorex ||
				e instanceof EntityNukeExplosionMK5 ||
				e instanceof EntityMIRV ||
				e instanceof EntityMiniNuke ||
				e instanceof EntityMiniMIRV ||
				e instanceof EntityGrenadeASchrab ||
				e instanceof EntityGrenadeNuclear ||
				e instanceof EntityExplosiveBeam ||
				e instanceof EntityBulletBase ||
				(e instanceof EntityPlayer &&
						ArmorUtil.checkArmor((EntityPlayer) e, ModItems.euphemium_helmet, ModItems.euphemium_plate, ModItems.euphemium_legs, ModItems.euphemium_boots))) {
			return true;
		}

		if(e instanceof EntityPlayer && (((EntityPlayer)e).isCreative() || ((EntityPlayer)e).isSpectator())) {
			return true;
		}

		return false;
	}

	public static enum HazardType {
		MONOXIDE,
		RADIATION,
		NEUTRON,
		DIGAMMA
	}

	public static enum ContaminationType {
		GAS,
		GAS_NON_REACTIVE,
		GOGGLES,
		FARADAY,
		HAZMAT,
		HAZMAT2,
		DIGAMMA,
		DIGAMMA2,
		CREATIVE,
		RAD_BYPASS,
		NONE
	}

	@SuppressWarnings("incomplete-switch")
	public static boolean contaminate(EntityLivingBase entity, HazardType hazard, ContaminationType cont, float amount) {

		if(hazard == HazardType.RADIATION) {
			float radEnv = HbmLivingProps.getRadEnv(entity);
			HbmLivingProps.setRadEnv(entity, radEnv + amount);
		}

		if(entity instanceof EntityPlayer player) {

			switch(cont) {
				case GOGGLES:			if(ArmorUtil.checkForGoggles(player))	return false; break;
				case FARADAY:			if(ArmorUtil.checkForFaraday(player))	return false; break;
				case HAZMAT:			if(ArmorUtil.checkForHazmat(player))	return false; break;
				case HAZMAT2:			if(ArmorUtil.checkForHaz2(player))		return false; break;
				case DIGAMMA:			if(ArmorUtil.checkForDigamma(player))	return false; break;
				case DIGAMMA2: break;
			}

			if(player.capabilities.isCreativeMode && cont != ContaminationType.NONE){
				if(hazard == HazardType.NEUTRON)
					HbmLivingProps.setNeutron(entity, amount);
				return false;
			}

			if(player.ticksExisted < 200)
				return false;
		}

		if((hazard == HazardType.RADIATION || hazard == HazardType.NEUTRON) && isRadImmune(entity)){
			return false;
		}

		switch(hazard) {
			case MONOXIDE: entity.attackEntityFrom(ModDamageSource.monoxide, amount); break;
			case RADIATION: HbmLivingProps.incrementRadiation(entity, amount * (cont == ContaminationType.RAD_BYPASS ? 1 : calculateRadiationMod(entity))); break;
			case NEUTRON: HbmLivingProps.incrementRadiation(entity, amount * (cont == ContaminationType.RAD_BYPASS ? 1 : calculateRadiationMod(entity))); HbmLivingProps.setNeutron(entity, amount); break;
			case DIGAMMA: applyDigammaData(entity, amount); break;
		}

		return true;
	}
}