package com.hbm.items.armor;

import java.util.List;
import java.util.UUID;

import com.google.common.collect.Multimap;
import com.hbm.capability.HbmCapability;
import com.hbm.capability.HbmCapability.IHBMData;
import com.hbm.handler.ArmorUtil;
import com.hbm.items.ModItems;
import com.hbm.items.gear.ArmorFSB;
import com.hbm.lib.HBMSoundHandler;
import com.hbm.lib.Library;
import com.hbm.packet.AuxParticlePacketNT;
import com.hbm.packet.PacketDispatcher;
import com.hbm.render.model.ModelArmorDNT;
import com.hbm.util.I18nUtil;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * ArmorDNT - 装甲システム統合版
 *
 * レベル100装甲システムを搭載した最強防具
 * - 装甲レベル: 100 (絶対防御)
 * - 最大耐久: 1,000,000 ヒット
 * - 完全ダメージ無効化
 * - ジェットパック機能
 * - 移動速度強化
 */
public class ArmorDNT extends ArmorShieldSystem implements IHeatResistanceArmor {

	private static final UUID speed = UUID.fromString("6ab858ba-d712-485c-bae9-e5e765fc555a");

	public ArmorDNT(ArmorMaterial material, int layer, EntityEquipmentSlot slot, String texture,
					long maxPower, long chargeRate, long consumption, long drain, String s) {
		// ArmorShieldSystemのコンストラクタを呼び出し
		// 装甲レベル100、耐久値1,000,000、再構築時間180秒
		super(material, layer, slot, texture, maxPower, chargeRate, consumption, drain,
				1000000,  // maxHitCount: 100万ヒット
				3600,     // cooldownTime: 180秒 (3600 ticks)
				100,      // armorLevel: レベル100 (絶対防御)
				s);
	}

	// =========================================================================
	// === IHeatResistanceArmor 実装 ============================================
	// =========================================================================

	/**
	 * DNT装甲の耐熱値: 1000万°C
	 *
	 * 1000万°C以下の熱ダメージを完全無効化します。
	 * 1000万°Cを超える熱攻撃(例: 核爆発の火球直撃)のみ貫通可能。
	 */
	@Override
	public int getHeatResistance(ItemStack stack) {
		return 10_000_000; // 1000万°C
	}

	@SideOnly(Side.CLIENT)
	ModelArmorDNT[] models;

	@Override
	@SideOnly(Side.CLIENT)
	public ModelBiped getArmorModel(EntityLivingBase entityLiving, ItemStack itemStack,
									EntityEquipmentSlot armorSlot, ModelBiped _default){
		if(models == null) {
			models = new ModelArmorDNT[4];

			for(int i = 0; i < 4; i++)
				models[i] = new ModelArmorDNT(i);
		}

		return models[armorSlot.getIndex()];
	}

	@Override
	public void onArmorTick(World world, EntityPlayer player, ItemStack stack) {
		// 親クラス(ArmorShieldSystem)の処理を実行
		super.onArmorTick(world, player, stack);

		if(this != ModItems.dns_plate)
			return;

		IHBMData props = HbmCapability.getData(player);

		/// SPEED ///
		// 装着開始時刻を記録
		if(!stack.hasTagCompound()) {
			stack.setTagCompound(new NBTTagCompound());
		}
		NBTTagCompound nbt = stack.getTagCompound();

		// 着脱検出: 2ティック以上のギャップがあれば再装備とみなして累積tickをリセット
		long currentWorldTime = world.getTotalWorldTime();
		if(nbt.hasKey("lastTickTime")) {
			long lastTick = nbt.getLong("lastTickTime");
			if(currentWorldTime - lastTick > 2) {
				// アーマーを外して再装備した → 加速リセット
				nbt.setLong("accumulatedFloatTicks", 0L);
			}
		}
		nbt.setLong("lastTickTime", currentWorldTime);

		// バグ修正: 浮遊中かつWキー押下中のみ累積tickを増加
		// 以前は equipTime からの絶対経過時間を使用していたため、
		// 地面に立っているだけ・何も操作しなくても常時加速していた。
		// 修正後: !onGround && moveForward > 0 の時のみカウント
		long accumulatedFloatTicks = nbt.getLong("accumulatedFloatTicks");
		if(!player.onGround && !player.isSneaking() && player.moveForward > 0) {
			accumulatedFloatTicks++;
			nbt.setLong("accumulatedFloatTicks", accumulatedFloatTicks);
		} else if(player.moveForward <= 0) {
			// Wキーを離したら加速リセット
			accumulatedFloatTicks = 0;
			nbt.setLong("accumulatedFloatTicks", 0L);
		}

		// 5分 = 6000ティックの実際の浮遊前進操作後にマッハ5（浮遊速度12.25）に到達
		// 初期浮遊速度: 0.25
		// 最終浮遊速度: 12.25（マッハ5相当）
		double baseFloatSpeed = 0.25;
		double maxFloatSpeed = 12.25;
		double targetTicks = 6000.0; // 実際の浮遊前進5分分

		double currentFloatSpeed;
		if(accumulatedFloatTicks >= targetTicks) {
			currentFloatSpeed = maxFloatSpeed;
		} else {
			currentFloatSpeed = baseFloatSpeed + (maxFloatSpeed - baseFloatSpeed) * (accumulatedFloatTicks / targetTicks);
		}

		// 浮遊速度は後で使用するため保存
		nbt.setDouble("currentFloatSpeed", currentFloatSpeed);

		// ダッシュ速度は元の0.25固定
		Multimap<String, AttributeModifier> multimap = super.getAttributeModifiers(EntityEquipmentSlot.CHEST, stack);
		multimap.put(SharedMonsterAttributes.MOVEMENT_SPEED.getName(),
				new AttributeModifier(speed, "DNT SPEED", 0.25, 0));
		player.getAttributeMap().removeAttributeModifiers(multimap);

		if(player.isSprinting()) {
			player.getAttributeMap().applyAttributeModifiers(multimap);
		}

		if(hasFSBArmor(player)) {

			ArmorUtil.resetFlightTime(player);

			if(props.isJetpackActive()) {

				if(player.motionY < 0.6D)
					player.motionY += 0.2D;

				player.fallDistance = 0;

				if(world.getTotalWorldTime() % 4 == 0)
					world.playSound(null, player.posX, player.posY, player.posZ,
							HBMSoundHandler.immolatorShoot, SoundCategory.PLAYERS, 0.125F, 1.5F);

			} else if(!player.isSneaking() && !player.onGround && props.getEnableBackpack()) {
				player.fallDistance = 0;

				if(player.motionY < -1)
					player.motionY += 0.4D;
				else if(player.motionY < -0.1)
					player.motionY += 0.2D;
				else if(player.motionY < 0)
					player.motionY = 0;

				player.motionX *= 1.05D;
				player.motionZ *= 1.05D;

				if(player.moveForward != 0) {
					// 浮遊速度を時間経過で加速（0.25 → 12.25）
					ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
					double floatSpeed = 0.25; // デフォルト値
					if(!chestStack.isEmpty() && chestStack.hasTagCompound() &&
					   chestStack.getTagCompound().hasKey("currentFloatSpeed")) {
						floatSpeed = chestStack.getTagCompound().getDouble("currentFloatSpeed");
					}
					player.motionX += player.getLookVec().x * floatSpeed * player.moveForward;
					player.motionZ += player.getLookVec().z * floatSpeed * player.moveForward;
				}
				if(world.getTotalWorldTime() % 4 == 0)
					world.playSound(null, player.posX, player.posY, player.posZ,
							HBMSoundHandler.immolatorShoot, SoundCategory.PLAYERS, 0.125F, 1.5F);
			}

			if(player.isSneaking() && !player.onGround) {
				player.motionY -= 0.1D;
			}
		}
	}

	@Override
	public void handleAttack(LivingAttackEvent event, ArmorFSB chestplate) {
		EntityLivingBase e = event.getEntityLiving();

		// 装甲貫通ダメージかチェック
		if (ArmorPenetrationSystem.isPenetrationDamage(event.getSource())) {
			// 装甲貫通システムで処理される
			return;
		}

		if(ArmorFSB.hasFSBArmor(e)) {
			// 装甲システムが有効な場合はArmorShieldSystemの処理を使用
			if(e instanceof EntityPlayer) {
				EntityPlayer player = (EntityPlayer) e;
				ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

				if(!chestStack.isEmpty() && chestStack.getItem() instanceof ArmorShieldSystem) {
					ArmorShieldSystem armor = (ArmorShieldSystem) chestStack.getItem();

					if(armor.isShieldActive(chestStack)) {
						// 親クラスの装甲システム処理を実行
						super.handleAttack(event, chestplate);
						return;
					}
				}
			}

			// 装甲が無効な場合は元の処理
			if(event.getSource().isExplosion()) {
				return;
			}

			e.world.playSound(null, e.posX, e.posY, e.posZ, SoundEvents.BLOCK_ANVIL_BREAK,
					SoundCategory.PLAYERS, 5F, 1.0F + e.getRNG().nextFloat() * 0.5F);
			event.setCanceled(true);
		}
	}

	@Override
	public void handleHurt(LivingHurtEvent event, ArmorFSB chestplate) {
		EntityLivingBase e = event.getEntityLiving();

		// 装甲貫通ダメージかチェック
		if (ArmorPenetrationSystem.isPenetrationDamage(event.getSource())) {
			return;
		}

		if(ArmorFSB.hasFSBArmor(e)) {
			// 装甲システムが有効な場合はArmorShieldSystemの処理を使用
			if(e instanceof EntityPlayer) {
				EntityPlayer player = (EntityPlayer) e;
				ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

				if(!chestStack.isEmpty() && chestStack.getItem() instanceof ArmorShieldSystem) {
					ArmorShieldSystem armor = (ArmorShieldSystem) chestStack.getItem();

					if(armor.isShieldActive(chestStack)) {
						// 親クラスの装甲システム処理を実行
						super.handleHurt(event, chestplate);
						return;
					}
				}
			}

			// 装甲が無効な場合は元の処理
			if(event.getSource().isExplosion()) {
				event.setAmount(event.getAmount()*0.001F);
				return;
			}

			event.setAmount(0);
		}
	}

	public static String getColor(long a, long b){
		float fraction = 100F * a/b;
		if (fraction > 75)
			return "§a";
		if (fraction > 25)
			return "§e";
		return "§c";
	}

	@Override
	public void addInformation(ItemStack stack, World worldIn, List<String> list, ITooltipFlag flagIn){
		// エネルギー情報
		long power = getCharge(stack);
		list.add("Charge: " + getColor(power, getMaxCharge(stack)) + Library.getShortNumber(power) +
				" §2/ " + Library.getShortNumber(getMaxCharge(stack)));

		// 装甲システム情報
		list.add("");
		list.add("" + TextFormatting.GOLD + "╔═══════════════════════════════╗");
		list.add("" + TextFormatting.GOLD + "║  " + TextFormatting.BOLD +
				"DNT装甲システム Lv.100" + TextFormatting.RESET +
				"" + TextFormatting.GOLD + "  ║");
		list.add("" + TextFormatting.GOLD + "╚═══════════════════════════════╝");

		// 装甲ステータス
		if (isShieldBroken(stack)) {
			long cooldown = getCooldownTimer(stack);
			list.add("" + TextFormatting.RED + "● 状態: シールド破壊中");
			list.add("" + TextFormatting.YELLOW + "● 再構築: " + (cooldown / 20) + " 秒後");
		} else {
			int currentHits = getHitCount(stack);
			int remaining = 1000000 - currentHits;
			list.add("" + TextFormatting.GREEN + "● 状態: シールド展開中");
			list.add("" + TextFormatting.AQUA + "● 装甲レベル: " + TextFormatting.DARK_RED +
					"100 [絶対防御]");
			list.add("" + TextFormatting.YELLOW + "● 残り耐久: " + TextFormatting.GREEN +
					Library.getShortNumber(remaining) + "" + TextFormatting.WHITE + " / " +
					TextFormatting.AQUA + "1,000,000" + TextFormatting.YELLOW + " ヒット");

			// 耐久パーセント表示
			float percentage = (float)remaining / 1000000f * 100f;
			String percentColor = percentage > 75 ? "" + TextFormatting.GREEN :
					percentage > 25 ? "" + TextFormatting.YELLOW :
							"" + TextFormatting.RED;
			list.add(percentColor + "● 耐久率: " + String.format("%.2f", percentage) + "%");
		}

		list.add("");
		list.add("" + TextFormatting.GOLD + I18nUtil.resolveKey("armor.fullSetBonus"));

		if(!effects.isEmpty()) {
			for(PotionEffect effect : effects) {
				list.add("" + TextFormatting.AQUA + "  " + I18n.format(effect.getEffectName()));
			}
		}

		// 装甲システム機能
		list.add("");
		list.add("" + TextFormatting.AQUA + "▶ 装甲システム:");
		list.add("" + TextFormatting.WHITE + "  ✓ レベル100絶対防御");
		list.add("" + TextFormatting.WHITE + "  ✓ 100万ヒット耐久");
		list.add("" + TextFormatting.WHITE + "  ✓ 完全ダメージ無効化");
		list.add("" + TextFormatting.WHITE + "  ✓ 体力減少防止");
		list.add("" + TextFormatting.WHITE + "  ✓ ノックバック無効");
		list.add("" + TextFormatting.DARK_GRAY + "  ※ レベル101以上の貫通で突破可能");

		// 浮遊速度加速システム情報
		list.add("");
		list.add("" + TextFormatting.LIGHT_PURPLE + "▶ 浮遊速度加速システム:");
		if(stack.hasTagCompound() && stack.getTagCompound().hasKey("accumulatedFloatTicks")) {
			NBTTagCompound nbt = stack.getTagCompound();
			long accumulatedFloatTicks = nbt.getLong("accumulatedFloatTicks");

			double baseSpeed = 0.25;
			double maxSpeed = 12.25;
			double targetTicks = 6000.0;

			double currentSpeed;
			if(accumulatedFloatTicks >= targetTicks) {
				currentSpeed = maxSpeed;
			} else {
				currentSpeed = baseSpeed + (maxSpeed - baseSpeed) * (accumulatedFloatTicks / targetTicks);
			}

			// 実際の浮遊前進tick数を秒に換算
			int accumulatedSeconds = (int)(accumulatedFloatTicks / 20);
			int remainingSeconds = Math.max(0, 300 - accumulatedSeconds);

			list.add("" + TextFormatting.WHITE + "  現在浮遊速度: " +
					TextFormatting.GOLD + String.format("%.2f", currentSpeed) + "x");
			list.add("" + TextFormatting.WHITE + "  浮遊前進時間: " +
					TextFormatting.YELLOW + accumulatedSeconds + "秒 / 300秒");

			if(accumulatedFloatTicks < targetTicks) {
				list.add("" + TextFormatting.WHITE + "  マッハ5まで: " +
						TextFormatting.GREEN + remainingSeconds + "秒 (浮遊前進時)");
			} else {
				list.add("" + TextFormatting.GREEN + "  ★ マッハ5到達！");
			}
		} else {
			list.add("" + TextFormatting.GRAY + "  浮遊しながらWキーで加速開始");
		}

		// 元の機能
		list.add("");
		list.add("" + TextFormatting.AQUA + "▶ DNT機能:");
		list.add("" + TextFormatting.YELLOW + "  " + I18nUtil.resolveKey("armor.explosionImmune"));
		list.add("" + TextFormatting.YELLOW + "  " + I18nUtil.resolveKey("armor.cap", 5));
		list.add("" + TextFormatting.YELLOW + "  " + I18nUtil.resolveKey("armor.modifier", 0.001F));
		list.add("" + TextFormatting.AQUA + "  " + I18nUtil.resolveKey("armor.rocketBoots"));
		list.add("" + TextFormatting.AQUA + "  " + I18nUtil.resolveKey("armor.fastFall"));
		list.add("" + TextFormatting.AQUA + "  " + I18nUtil.resolveKey("armor.sprintBoost"));

		list.add("");
		list.add("" + TextFormatting.RED + "▶ 制限:");
		list.add("" + TextFormatting.RED + "  " + I18nUtil.resolveKey("armor.vats"));
		list.add("" + TextFormatting.RED + "  " + I18nUtil.resolveKey("armor.thermal"));
		list.add("" + TextFormatting.RED + "  " + I18nUtil.resolveKey("armor.hardLanding"));
		list.add("" + TextFormatting.DARK_RED + "  " + I18nUtil.resolveKey("armor.ignoreLimit"));
	}
}