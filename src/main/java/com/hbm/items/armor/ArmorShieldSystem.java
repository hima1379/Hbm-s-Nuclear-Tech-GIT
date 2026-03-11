package com.hbm.items.armor;

import java.util.List;
import java.util.HashMap;
import java.util.UUID;

import com.hbm.capability.HbmCapability;
import com.hbm.capability.HbmCapability.IHBMData;
import com.hbm.handler.ArmorUtil;
import com.hbm.items.gear.ArmorFSB;
import com.hbm.lib.HBMSoundHandler;
import com.hbm.lib.Library;
import com.hbm.packet.AuxParticlePacketNT;
import com.hbm.packet.PacketDispatcher;
import com.hbm.render.model.ModelArmorDNT;
import com.hbm.util.I18nUtil;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 装甲システム - Shield Armor System (Enhanced with Armor Levels)
 *
 * この防具は指定回数までのあらゆるダメージを完全に無効化します。
 * - 装甲レベル1以上（Integer.MAX_VALUEまで）をサポート
 * - 装甲貫通システムに対応
 * - 防具貫通攻撃も無効化（貫通レベルが装甲レベル以下の場合）
 * - 通常ダメージ（奈落含む）は装甲レベルに関わらず完全無効化
 * - どんなに大きなダメージも1回としてカウント
 * - ノックバックも完全に防御
 * - ダメージソースそのものを無効化
 * - 体力の減少を根本的に防止
 * - 指定回数のダメージを受けると装甲が破壊される
 */
public class ArmorShieldSystem extends ArmorFSBPowered {

    // 装甲が耐えられるヒット数
    private final int maxHitCount;

    // 装甲破壊時のクールダウン時間（tick）
    private final int cooldownTime;

    // 装甲レベル（1以上、Integer.MAX_VALUEまで）
    private final int armorLevel;

    // プレイヤーの保護された体力を記憶
    private static final HashMap<UUID, Float> protectedHealth = new HashMap<>();

    public ArmorShieldSystem(ArmorMaterial material, int layer, EntityEquipmentSlot slot,
                             String texture, long maxPower, long chargeRate, long consumption,
                             long drain, int maxHitCount, int cooldownTime, int armorLevel, String s) {
        super(material, layer, slot, texture, maxPower, chargeRate, consumption, drain, s);
        this.maxHitCount = maxHitCount;
        this.cooldownTime = cooldownTime;
        this.armorLevel = Math.max(1, armorLevel); // 1以上（Integer.MAX_VALUEまで）
    }

    /**
     * 装甲レベルを取得
     */
    public int getArmorLevel(ItemStack stack) {
        return armorLevel;
    }

    /**
     * 装甲レベルの説明を取得
     */
    public String getArmorLevelDescription() {
        if (armorLevel == Integer.MAX_VALUE) return "神域装甲";
        if (armorLevel >= 10000) return "超越装甲";
        if (armorLevel >= 1000) return "極限装甲";
        if (armorLevel >= 500) return "絶対防御";
        if (armorLevel >= 100) return "超重装甲";
        if (armorLevel >= 50) return "重装甲";
        if (armorLevel >= 10) return "中装甲";
        return "軽装甲";
    }

    /**
     * 現在のヒットカウントを取得
     */
    public int getHitCount(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }
        return stack.getTagCompound().getInteger("hitCount");
    }

    /**
     * ヒットカウントを設定
     */
    public void setHitCount(ItemStack stack, int count) {
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }
        stack.getTagCompound().setInteger("hitCount", count);
    }

    /**
     * ヒットカウントを増加
     */
    public void incrementHitCount(ItemStack stack) {
        setHitCount(stack, getHitCount(stack) + 1);
    }

    /**
     * 装甲が破壊されているかチェック
     */
    public boolean isShieldBroken(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            return false;
        }
        return stack.getTagCompound().getBoolean("shieldBroken");
    }

    /**
     * 装甲破壊状態を設定
     */
    public void setShieldBroken(ItemStack stack, boolean broken) {
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }
        stack.getTagCompound().setBoolean("shieldBroken", broken);
        if (broken) {
            stack.getTagCompound().setLong("brokenTime", System.currentTimeMillis());
        }
    }

    /**
     * クールダウンタイマーを取得
     */
    public long getCooldownTimer(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            return 0;
        }
        return stack.getTagCompound().getLong("cooldownTimer");
    }

    /**
     * クールダウンタイマーを設定
     */
    public void setCooldownTimer(ItemStack stack, long timer) {
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }
        stack.getTagCompound().setLong("cooldownTimer", timer);
    }

    /**
     * 装甲が有効かチェック（エネルギーと破壊状態）
     */
    public boolean isShieldActive(ItemStack stack) {
        return getCharge(stack) > 0 && !isShieldBroken(stack);
    }

    /**
     * プレイヤーの体力を保護
     */
    private void protectPlayerHealth(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        float currentHealth = player.getHealth();

        // 現在の体力を記憶
        if (!protectedHealth.containsKey(playerId)) {
            protectedHealth.put(playerId, currentHealth);
        }

        // 体力が減少していた場合、元に戻す
        Float savedHealth = protectedHealth.get(playerId);
        if (savedHealth != null && currentHealth < savedHealth) {
            player.setHealth(savedHealth);
        }
    }

    /**
     * 保護された体力を更新
     */
    private void updateProtectedHealth(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        float currentHealth = player.getHealth();

        // 体力が増加した場合のみ更新（回復時）
        Float savedHealth = protectedHealth.get(playerId);
        if (savedHealth == null || currentHealth > savedHealth) {
            protectedHealth.put(playerId, currentHealth);
        }
    }

    @Override
    public void onArmorTick(World world, EntityPlayer player, ItemStack stack) {
        super.onArmorTick(world, player, stack);

        // 装甲が有効な場合、体力を保護（通常ダメージと失敗した貫通ダメージを復元）
        // 成功した貫通ダメージはmarkPenetrationSuccessで保護基準が更新されるため復元されない
        if (isShieldActive(stack)) {
            protectPlayerHealth(player);
            updateProtectedHealth(player);
            // hurtResistantTimeの操作は行わない（イベントハンドラーで防御するため）
        }

        // 装甲が破壊されている場合、クールダウンタイマーを処理
        if (isShieldBroken(stack)) {
            // 保護された体力をクリア
            protectedHealth.remove(player.getUniqueID());

            long currentTimer = getCooldownTimer(stack);
            if (currentTimer > 0) {
                setCooldownTimer(stack, currentTimer - 1);

                // クールダウン中の視覚エフェクト（5秒ごと）
                if (currentTimer % 100 == 0 && !world.isRemote) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.YELLOW + "[装甲Lv." + armorLevel + "] 再構築まで: " +
                                    (currentTimer / 20) + "秒"));
                }
            } else {
                // クールダウン完了 - 装甲を再構築
                setShieldBroken(stack, false);
                setHitCount(stack, 0);

                // 現在の体力を新しい保護基準として設定
                protectedHealth.put(player.getUniqueID(), player.getHealth());

                if (!world.isRemote) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.GREEN + "╔═══════════════════════════╗"));
                    player.sendMessage(new TextComponentString(
                            TextFormatting.GREEN + "║ " + TextFormatting.BOLD +
                                    "装甲レベル" + armorLevel + " 再構築完了!" +
                                    TextFormatting.RESET + TextFormatting.GREEN + " ║"));
                    player.sendMessage(new TextComponentString(
                            TextFormatting.GREEN + "╚═══════════════════════════╝"));



                    // 再構築時のエフェクト
                    NBTTagCompound data = new NBTTagCompound();
                    data.setString("type", "vanillaExt");
                    data.setString("mode", "explode");
                    data.setInteger("count", 20);

                    PacketDispatcher.wrapper.sendToAllAround(
                            new AuxParticlePacketNT(data, player.posX, player.posY + 1, player.posZ),
                            new TargetPoint(world.provider.getDimension(),
                                    player.posX, player.posY + 1, player.posZ, 50)
                    );
                }
            }
        }

        // 装甲システムの視覚エフェクト
        if (isShieldActive(stack) && world.getTotalWorldTime() % 20 == 0) {
            if (!world.isRemote) {
                NBTTagCompound data = new NBTTagCompound();
                data.setString("type", "vanillaExt");
                data.setString("mode", "happyVillager");
                data.setInteger("count", 3);

                PacketDispatcher.wrapper.sendToAllAround(
                        new AuxParticlePacketNT(data, player.posX, player.posY + 1, player.posZ),
                        new TargetPoint(world.provider.getDimension(),
                                player.posX, player.posY + 1, player.posZ, 50)
                );
            }
        }
    }

    @Override
    public void handleAttack(LivingAttackEvent event, ArmorFSB chestplate) {
        EntityLivingBase entity = event.getEntityLiving();

        if (!(entity instanceof EntityPlayer)) {
            return;
        }

        EntityPlayer player = (EntityPlayer) entity;
        ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

        if (chestStack.isEmpty() || !(chestStack.getItem() instanceof ArmorShieldSystem)) {
            return;
        }

        ArmorShieldSystem armor = (ArmorShieldSystem) chestStack.getItem();

        // 装甲貫通ダメージかチェック
        if (ArmorPenetrationSystem.isPenetrationDamage(event.getSource())) {
            // 装甲貫通システムで処理される
            return;
        }

        // 装甲システムが有効な場合 - 通常ダメージは完全無効化（ヒットカウント増加・アーマー破壊なし）
        // 構図: 通常ダメージ(奈落含む) < 越えられない壁 < アーマーレベル
        if (armor.isShieldActive(chestStack)) {
            // 体力を強制的に保護
            armor.protectPlayerHealth(player);

            // エフェクトと音を再生（ヒットカウント増加なし）
            if (!entity.world.isRemote) {
                entity.world.playSound(null, entity.posX, entity.posY, entity.posZ,
                        SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.5F, 2.0F);

                player.sendMessage(new TextComponentString(
                        TextFormatting.AQUA + "[装甲Lv." + armor.armorLevel + "] " +
                                TextFormatting.GREEN + "通常ダメージ完全無効化"));

                // ダメージ吸収エフェクト
                NBTTagCompound data = new NBTTagCompound();
                data.setString("type", "vanillaExt");
                data.setString("mode", "crit");
                data.setInteger("count", 10);

                PacketDispatcher.wrapper.sendToAllAround(
                        new AuxParticlePacketNT(data, player.posX, player.posY + 1, player.posZ),
                        new TargetPoint(entity.world.provider.getDimension(),
                                player.posX, player.posY + 1, player.posZ, 50)
                );
            }

            // ダメージソースそのものを完全にキャンセル
            event.setCanceled(true);
        }
    }

    @Override
    public void handleHurt(LivingHurtEvent event, ArmorFSB chestplate) {
        EntityLivingBase entity = event.getEntityLiving();

        if (!(entity instanceof EntityPlayer)) {
            return;
        }

        EntityPlayer player = (EntityPlayer) entity;
        ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

        if (chestStack.isEmpty() || !(chestStack.getItem() instanceof ArmorShieldSystem)) {
            return;
        }

        ArmorShieldSystem armor = (ArmorShieldSystem) chestStack.getItem();

        // 装甲貫通ダメージはスキップ
        if (ArmorPenetrationSystem.isPenetrationDamage(event.getSource())) {
            return;
        }

        // 装甲システムが有効な場合、ダメージを完全に0に設定（安全弁）
        if (armor.isShieldActive(chestStack)) {
            event.setAmount(0.0F);

            // 体力を保護
            armor.protectPlayerHealth(player);
        }
    }

    /**
     * ダメージイベントの最終段階でも防御
     * 注意: このメソッドはイベントハンドラーから呼ばれる必要があります
     */
    public static void handleDamage(LivingDamageEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) {
            return;
        }

        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

        if (!chestStack.isEmpty() && chestStack.getItem() instanceof ArmorShieldSystem) {
            ArmorShieldSystem armor = (ArmorShieldSystem) chestStack.getItem();

            // 装甲貫通ダメージはスキップ
            if (ArmorPenetrationSystem.isPenetrationDamage(event.getSource())) {
                return;
            }

            // 装甲システムが有効なら最終ダメージも0に
            if (armor.isShieldActive(chestStack)) {
                event.setAmount(0.0F);

                // 体力を強制的に保護
                armor.protectPlayerHealth(player);

                // 完全にダメージ処理をキャンセル
                event.setCanceled(true);
            }
        }
    }

    /**
     * ノックバックを防ぐ
     * 注意: このメソッドはイベントハンドラーから呼ばれる必要があります
     */
    public static void handleKnockback(LivingKnockBackEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) {
            return;
        }

        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

        if (!chestStack.isEmpty() && chestStack.getItem() instanceof ArmorShieldSystem) {
            ArmorShieldSystem armor = (ArmorShieldSystem) chestStack.getItem();

            // 装甲システムが有効ならノックバックをキャンセル
            if (armor.isShieldActive(chestStack)) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * プレイヤーがログアウトした時の処理
     */
    public static void onPlayerLogout(EntityPlayer player) {
        protectedHealth.remove(player.getUniqueID());
    }

    /**
     * 貫通成功時にprotectedHealthを新しいHP値に更新する
     * ArmorPenetrationSystemのapplyAbsoluteTrueDamageから呼び出される。
     * これにより、protectPlayerHealthが翌tickに貫通ダメージを復元しなくなる。
     *
     * @param playerId  対象プレイヤーのUUID
     * @param newHealth 貫通ダメージ適用後の新しいHP値
     */
    public static void markPenetrationSuccess(UUID playerId, float newHealth) {
        protectedHealth.put(playerId, newHealth);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, World worldIn, List<String> list, ITooltipFlag flagIn) {
        long power = getCharge(stack);
        list.add("Charge: " + getColor(power, getMaxCharge(stack)) +
                Library.getShortNumber(power) + " §2/ " + Library.getShortNumber(getMaxCharge(stack)));

        list.add("");
        list.add(TextFormatting.GOLD + "╔═══════════════════════════╗");
        list.add(TextFormatting.GOLD + "║  " + TextFormatting.BOLD +
                "装甲システム Lv." + armorLevel + TextFormatting.RESET +
                TextFormatting.GOLD + "      ║");
        list.add(TextFormatting.GOLD + "╚═══════════════════════════╝");

        // 装甲レベル情報
        list.add(ArmorPenetrationSystem.getLevelColor(armorLevel) + "● 装甲レベル: " +
                TextFormatting.BOLD + armorLevel + TextFormatting.RESET +
                ArmorPenetrationSystem.getLevelColor(armorLevel) + " [" + getArmorLevelDescription() + "]");

        if (isShieldBroken(stack)) {
            long cooldown = getCooldownTimer(stack);
            list.add(TextFormatting.RED + "● 状態: " + TextFormatting.BOLD + "シールド破壊");
            list.add(TextFormatting.YELLOW + "● 再構築: " + (cooldown / 20) + " 秒後");
        } else {
            int currentHits = getHitCount(stack);
            int remaining = maxHitCount - currentHits;
            list.add(TextFormatting.GREEN + "● 状態: " + TextFormatting.BOLD + "シールド展開中");
            list.add(TextFormatting.YELLOW + "● 残り耐久: " + TextFormatting.AQUA + remaining +
                    TextFormatting.WHITE + " / " + TextFormatting.GREEN + maxHitCount +
                    TextFormatting.YELLOW + " ヒット");

            // プログレスバー
            StringBuilder bar = new StringBuilder(TextFormatting.YELLOW + "● [");
            int barLength = 10;
            int filled = (int)((double)remaining / maxHitCount * barLength);
            for (int i = 0; i < barLength; i++) {
                if (i < filled) {
                    bar.append(TextFormatting.GREEN + "■");
                } else {
                    bar.append(TextFormatting.GRAY + "□");
                }
            }
            bar.append(TextFormatting.YELLOW + "]");
            list.add(bar.toString());
        }

        list.add("");
        list.add("" + TextFormatting.AQUA + "▶ 防御機能:");
        list.add("" + TextFormatting.WHITE + "  ✓ 完全ダメージ無効化");
        list.add("" + TextFormatting.WHITE + "  ✓ ダメージソース無効化");
        list.add("" + TextFormatting.WHITE + "  ✓ 体力減少完全防止");
        list.add("" + TextFormatting.WHITE + "  ✓ 装甲貫通対応 (Lv." + armorLevel + "まで防御)");
        list.add("" + TextFormatting.WHITE + "  ✓ ノックバック無効");
        list.add("" + TextFormatting.WHITE + "  ✓ 無敵時間強制設定");

        list.add("");
        list.add(TextFormatting.YELLOW + "▶ 仕様:");
        list.add(TextFormatting.WHITE + "  最大吸収: " + TextFormatting.GREEN + maxHitCount + TextFormatting.WHITE + " ヒット");
        list.add(TextFormatting.WHITE + "  再構築: " + TextFormatting.AQUA + (cooldownTime / 20) + TextFormatting.WHITE + " 秒");
        list.add(TextFormatting.WHITE + "  装甲Lv: " + ArmorPenetrationSystem.getLevelColor(armorLevel) +
                TextFormatting.BOLD + armorLevel);

        list.add("");
        list.add(TextFormatting.DARK_GRAY + "※ 貫通レベル" + (armorLevel + 1) + "以上で貫通される");

        if (isShieldActive(stack)) {
            list.add("");
            list.add(TextFormatting.GREEN + ">>> 完全防護モード起動中 <<<");
        } else if (isShieldBroken(stack)) {
            list.add("");
            list.add(TextFormatting.RED + ">>> 修復プロトコル実行中 <<<");
        }
    }

    public static String getColor(long a, long b) {
        float fraction = 100F * a / b;
        if (fraction > 75)
            return "§a";
        if (fraction > 25)
            return "§e";
        return "§c";
    }
}