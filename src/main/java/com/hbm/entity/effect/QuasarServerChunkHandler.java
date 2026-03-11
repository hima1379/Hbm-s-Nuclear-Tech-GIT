package com.hbm.entity.effect;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
import net.minecraftforge.event.terraingen.OreGenEvent;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * サーバー側のチャンクロードシステムを完全に制御
 */
public class QuasarServerChunkHandler {

    private static QuasarServerChunkHandler INSTANCE = null;
    private static final Map<Integer, Boolean> voidifiedDimensions = new HashMap<>();

    private QuasarServerChunkHandler() {
        // シングルトン
    }

    /**
     * インスタンスを取得して登録
     */
    public static void register() {
        if (INSTANCE == null) {
            INSTANCE = new QuasarServerChunkHandler();
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            System.out.println("[QuasarServerChunkHandler] Registered to event bus");
        }
    }

    /**
     * イベントバスから登録解除
     */
    public static void unregister() {
        if (INSTANCE != null) {
            MinecraftForge.EVENT_BUS.unregister(INSTANCE);
            INSTANCE = null;
            System.out.println("[QuasarServerChunkHandler] Unregistered from event bus");
        }
    }

    /**
     * ディメンションをVoid化リストに追加
     */
    public static void registerVoidDimension(int dimensionId) {
        register(); // イベントハンドラーを登録
        voidifiedDimensions.put(dimensionId, true);
        System.out.println("[QuasarServerChunkHandler] Registered void dimension: " + dimensionId);
    }

    /**
     * ディメンションをVoid化リストから削除
     */
    public static void unregisterVoidDimension(int dimensionId) {
        voidifiedDimensions.remove(dimensionId);
        System.out.println("[QuasarServerChunkHandler] Unregistered void dimension: " + dimensionId);

        // すべてのディメンションが解除されたら、イベントハンドラーも解除
        if (voidifiedDimensions.isEmpty()) {
            unregister();
        }
    }

    /**
     * 指定されたディメンションがVoid化されているか確認
     */
    private static boolean isVoidDimension(World world) {
        if (world == null || world.isRemote) {
            return false;
        }
        int dimensionId = world.provider.getDimension();
        return voidifiedDimensions.getOrDefault(dimensionId, false);
    }

    /**
     * チャンクロードイベント - 最高優先度で処理
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!isVoidDimension(event.getWorld())) {
            return;
        }

        Chunk chunk = event.getChunk();
        ensureChunkIsVoid(chunk, event.getWorld());
        System.out.println("[QuasarServerChunkHandler] ChunkEvent.Load processed for [" +
                chunk.x + ", " + chunk.z + "]");
    }

    /**
     * チャンクデータロードイベント
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChunkDataLoad(ChunkDataEvent.Load event) {
        if (!isVoidDimension(event.getWorld())) {
            return;
        }

        Chunk chunk = event.getChunk();
        ensureChunkIsVoid(chunk, event.getWorld());
        System.out.println("[QuasarServerChunkHandler] ChunkDataEvent.Load processed for [" +
                chunk.x + ", " + chunk.z + "]");
    }

    /**
     * チャンク配置イベントをキャンセル（Pre）
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPopulateChunkPre(PopulateChunkEvent.Pre event) {
        if (!isVoidDimension(event.getWorld())) {
            return;
        }

        event.setResult(Event.Result.DENY);
        System.out.println("[QuasarServerChunkHandler] Cancelled PopulateChunkEvent.Pre at [" +
                event.getChunkX() + ", " + event.getChunkZ() + "]");
    }

    /**
     * チャンク配置イベントをキャンセル（Post）
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPopulateChunkPost(PopulateChunkEvent.Post event) {
        if (!isVoidDimension(event.getWorld())) {
            return;
        }

        World world = event.getWorld();
        Chunk chunk = world.getChunk(event.getChunkX(), event.getChunkZ());
        if (chunk != null) {
            ensureChunkIsVoid(chunk, world);
        }
        System.out.println("[QuasarServerChunkHandler] PopulateChunkEvent.Post processed at [" +
                event.getChunkX() + ", " + event.getChunkZ() + "]");
    }

    /**
     * バイオーム装飾イベントをキャンセル
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDecorateBiome(DecorateBiomeEvent.Pre event) {
        if (!isVoidDimension(event.getWorld())) {
            return;
        }

        event.setResult(Event.Result.DENY);
        System.out.println("[QuasarServerChunkHandler] Cancelled DecorateBiomeEvent.Pre");
    }

    /**
     * 鉱石生成イベントをキャンセル
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onOreGen(OreGenEvent.Pre event) {
        if (!isVoidDimension(event.getWorld())) {
            return;
        }

        event.setResult(Event.Result.DENY);
        System.out.println("[QuasarServerChunkHandler] Cancelled OreGenEvent.Pre");
    }

    /**
     * チャンクが完全に空であることを保証
     */
    private void ensureChunkIsVoid(Chunk chunk, World world) {
        if (chunk == null) {
            return;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int clearedBlocks = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 256; y++) {
                    pos.setPos(chunk.x * 16 + x, y, chunk.z * 16 + z);

                    if (!chunk.getBlockState(pos).getBlock().equals(Blocks.AIR)) {
                        chunk.setBlockState(pos, Blocks.AIR.getDefaultState());
                        clearedBlocks++;
                    }
                }
            }
        }

        if (clearedBlocks > 0) {
            chunk.setTerrainPopulated(true);
            chunk.setLightPopulated(true);
            chunk.markDirty();
            System.out.println("[QuasarServerChunkHandler] Forcibly cleared " + clearedBlocks +
                    " blocks from chunk [" + chunk.x + ", " + chunk.z + "]");
        }
    }
}