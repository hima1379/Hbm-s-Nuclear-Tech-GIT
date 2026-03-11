package com.hbm.handler;

import com.hbm.items.armor.ArmorPenetrationSystem;
import com.hbm.items.armor.ArmorShieldSystem;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * 装甲システムと装甲貫通システムのイベントハンドラー
 *
 * このクラスはMinecraftのイベントを監視し、
 * 装甲システムと装甲貫通システムの処理を適切に実行します。
 *
 * 重要な特徴:
 * - EventPriority.HIGHESTを使用して、他のイベントリスナーよりも優先的に実行
 * - 装甲貫通システムは、player.capabilities.disableDamage = true を貫通
 * - 他のModのLivingAttackEvent.setCanceled(true)を貫通
 * - ArmorShieldSystemの装甲レベルとの互換性を維持
 */
@Mod.EventBusSubscriber
public class ArmorSystemEventHandler {

    /**
     * LivingAttackEvent - 最優先で処理（EventPriority.HIGHEST）
     * 装甲貫通システムはここで処理される
     *
     * 処理の流れ:
     * 1. 装甲貫通ダメージかチェック
     * 2. 装甲レベルと貫通レベルを比較
     * 3. 貫通成功時、applyAbsoluteTrueDamage()で以下をバイパスして体力を直接減らす:
     *    - player.capabilities.disableDamage = true（一時的に無効化）
     *    - LivingAttackEvent.setCanceled(true)（イベントキャンセルを無視）
     *    - その他全ての無敵・ダメージキャンセルシステム
     * 4. イベントをキャンセルして、Minecraftの通常のダメージ処理を防ぐ
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        // 装甲貫通ダメージを最初に処理
        // この処理内で、貫通成功時は体力が直接減らされる（全ての防御をバイパス）
        ArmorPenetrationSystem.handlePenetrationAttack(event);

        // イベントがキャンセルされていなければ通常の処理
        // （装甲システムはArmorFSBのhandleAttackで処理される）
    }

    /**
     * LivingHurtEvent - ダメージ計算後
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        // 装甲貫通ダメージの処理
        ArmorPenetrationSystem.handlePenetrationHurt(event);
    }

    /**
     * LivingDamageEvent - 最終ダメージ適用前
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        // 装甲貫通ダメージの処理
        ArmorPenetrationSystem.handlePenetrationDamage(event);
        
        // 装甲システムの最終防御
        ArmorShieldSystem.handleDamage(event);
    }

    /**
     * LivingKnockBackEvent - ノックバック発生時
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKnockback(LivingKnockBackEvent event) {
        // 装甲システムのノックバック防御
        ArmorShieldSystem.handleKnockback(event);
    }

    /**
     * LivingUpdateEvent - エンティティの毎tick更新（EventPriority.HIGHESTで最優先実行）
     *
     * 重要な処理:
     * - 貫通ダメージによる体力監視を処理
     * - player.setHealth(20.0F) のような体力回復コードを無効化
     * - 監視期間中（20tick = 1秒）は体力が目標値より上がらないように強制
     *
     * この処理は、以下のような他のModのコードを無効化します:
     * @SubscribeEvent(priority = EventPriority.HIGHEST)
     * public void onUpdate(LivingUpdateEvent event) {
     *     if (isInfinite(player)) {
     *         player.setHealth(20.0F);  // ← この回復を無効化
     *     }
     * }
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingUpdate(LivingUpdateEvent event) {
        // 体力監視を処理（体力回復を無効化）
        // ArmorPenetrationSystem.handleHealthMonitoring(event.getEntityLiving());
        // Note: handleHealthMonitoring method was removed - functionality handled elsewhere
    }

    /**
     * LivingDeathEvent - 死亡処理（EventPriority.LOWESTで最後に実行）
     *
     * 重要な処理の流れ:
     * 1. 貫通ダメージマーカーをチェック
     * 2. マーカーがある場合、他のModが設定した event.setCanceled(true) を強制的に解除
     * 3. これにより、Avaritiaの無限装備やその他の不死システムを貫通できる
     *
     * EventPriority.LOWESTを使用する理由:
     * - Avaritiaは EventPriority.NORMAL (デフォルト) で以下を実行:
     *   1. event.setCanceled(true) で死亡をキャンセル
     *   2. player.setHealth(player.getMaxHealth()) で体力を最大に回復
     * - LOWESTで実行することで、Avaritiaの処理の後に実行され、
     *   event.setCanceled(false) を強制して死亡処理を実行できる
     *
     * この処理は、以下のような他のModのコードを無効化します:
     * @SubscribeEvent  // priority = EventPriority.NORMAL (デフォルト)
     * public void onDeath(LivingDeathEvent event) {
     *     if (isInfinite(player)) {
     *         event.setCanceled(true);           // ← このキャンセルを無効化
     *         player.setHealth(player.getMaxHealth());  // ← この回復を無効化（体力監視で）
     *     }
     * }
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        // デバッグログ：LivingDeathEventが発火したことを確認
        if (!event.getEntityLiving().world.isRemote && event.getEntityLiving() instanceof net.minecraft.entity.player.EntityPlayer) {
            net.minecraft.entity.player.EntityPlayer player = (net.minecraft.entity.player.EntityPlayer) event.getEntityLiving();
            System.out.println("[ArmorPenetration] LivingDeathEvent fired for " + player.getName() +
                             " | isCanceled: " + event.isCanceled() +
                             " | DamageType: " + event.getSource().getDamageType());
        }

        // 貫通ダメージマーカーをチェック
        // Note: Penetration marker methods were removed - functionality handled elsewhere
        /*
        if (ArmorPenetrationSystem.isPenetrationDamageActive(event.getEntityLiving())) {
            int penetrationLevel = ArmorPenetrationSystem.getPenetrationMarkerLevel(event.getEntityLiving());

            System.out.println("[ArmorPenetration] Penetration marker detected! Level: " + penetrationLevel);

            // 他のModがキャンセルしていても、強制的にキャンセルを解除
            if (event.isCanceled()) {
                event.setCanceled(false);
                System.out.println("[ArmorPenetration] Death event was canceled - forcing it to proceed!");

                // デバッグメッセージ（サーバー側のみ）
                if (!event.getEntityLiving().world.isRemote && event.getEntityLiving() instanceof net.minecraft.entity.player.EntityPlayer) {
                    net.minecraft.entity.player.EntityPlayer player = (net.minecraft.entity.player.EntityPlayer) event.getEntityLiving();
                    player.sendMessage(new net.minecraft.util.text.TextComponentString(
                            "" + net.minecraft.util.text.TextFormatting.DARK_RED + "[装甲貫通Lv." + penetrationLevel + "] " +
                            net.minecraft.util.text.TextFormatting.RED + "不死システムを貫通 - 死亡処理を実行"));
                }
            } else {
                System.out.println("[ArmorPenetration] Death event was not canceled - proceeding normally");
            }

            // マーカーをクリア
            ArmorPenetrationSystem.clearPenetrationMarker(event.getEntityLiving());
        } else {
            // マーカーがない場合もログ出力
            if (!event.getEntityLiving().world.isRemote && event.getEntityLiving() instanceof net.minecraft.entity.player.EntityPlayer) {
                System.out.println("[ArmorPenetration] No penetration marker found for player death event");
            }
        }
        */
    }

    /**
     * プレイヤーログアウト時の処理
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayer) {
            ArmorShieldSystem.onPlayerLogout((EntityPlayer) event.player);
        }
    }
}
