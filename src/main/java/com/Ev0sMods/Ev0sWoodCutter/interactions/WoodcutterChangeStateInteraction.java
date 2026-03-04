package com.Ev0sMods.Ev0sWoodCutter.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChangeStateInteraction;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Interaction type for changing the WoodCutter block's named state definition.
 *
 * Unlike ArcIO's ArcLatch (which changes state on player click), this interaction
 * is never triggered by a player — it is invoked directly from WoodCutter.tick()
 * whenever the ArcIO signal level changes.
 *
 * The two states map to State.Definitions in Woodcutter.json:
 *   "Off" →  empty definition         (no animation — blade at rest)
 *   "On"  →  active.blockyanim loops  (blade spinning)
 *
 * The static {@link #applyStateTo} method is the programmatic entry point.
 * It mirrors {@link ChangeStateInteraction#interactWithBlock} exactly:
 * {@link BlockType#getBlockKeyForState} → asset map index → {@link WorldChunk#setBlock}
 * with flags {@code NO_UPDATE_STATE | NO_SEND_PARTICLES | 256} (= 262),
 * identical to ArcIO's ChangeStateInteraction with {@code UpdateBlockState=false}.
 *
 * Registered in the codec system as "WoodcutterChangeState" so that it can
 * also be referenced from JSON interactions in the future if needed.
 */
public class WoodcutterChangeStateInteraction extends ChangeStateInteraction {

    /** State definition name when the woodcutter has no active signal. */
    public static final String STATE_OFF = "Off";
    /** State definition name when the woodcutter is receiving a signal. */
    public static final String STATE_ON  = "On";

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final BuilderCodec<WoodcutterChangeStateInteraction> CODEC =
            BuilderCodec.builder(
                    WoodcutterChangeStateInteraction.class,
                    WoodcutterChangeStateInteraction::new,
                    (BuilderCodec) ChangeStateInteraction.CODEC)
            .documentation("Changes the WoodCutter named state definition (Off/On), driving client animation.")
            .build();

    // -----------------------------------------------------------------------
    // Player-triggered path (unused — present to satisfy the abstract contract)
    // -----------------------------------------------------------------------

    @Override
    protected void interactWithBlock(
            @NotNull World world,
            @NotNull CommandBuffer<EntityStore> commandBuffer,
            @NotNull InteractionType interactionType,
            @NotNull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @NotNull Vector3i blockPos,
            @NotNull CooldownHandler cooldownHandler) {
        // State is driven programmatically by WoodCutter.tick() via applyStateTo().
        // This path is intentionally left empty.
    }

    @Override
    protected void simulateInteractWithBlock(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull World world,
            @Nonnull Vector3i blockPos) {
        // No client-side prediction needed — state changes are server-authoritative.
    }

    // -----------------------------------------------------------------------
    // Block-driven path — called from WoodCutter.tick(), no player needed
    // -----------------------------------------------------------------------

    /**
     * Switches the WoodCutter's named state definition without involving a player.
     * Mirrors {@link ChangeStateInteraction#interactWithBlock} exactly.
     *
     * @param world     the world containing the block
     * @param x         block X
     * @param y         block Y
     * @param z         block Z
     * @param stateName {@link #STATE_ON} or {@link #STATE_OFF}
     */
    public static void applyStateTo(World world, int x, int y, int z, String stateName) {
        try {
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
            if (chunk == null) {
                HytaleLogger.getLogger().atWarning().log(
                        "[WoodcutterChangeState] chunk null at (%d,%d,%d)", x, y, z);
                return;
            }
            BlockType currentType = chunk.getBlockType(new Vector3i(x, y, z));
            if (currentType == null) {
                HytaleLogger.getLogger().atWarning().log(
                        "[WoodcutterChangeState] currentType null at (%d,%d,%d)", x, y, z);
                return;
            }
            // Mirror ChangeStateInteraction.interactWithBlock exactly:
            // getBlockKeyForState -> asset map index -> setBlock(x,y,z,idx,type,rot,0,flags)
            String stateKey = currentType.getBlockKeyForState(stateName);
            if (stateKey == null) {
                HytaleLogger.getLogger().atWarning().log(
                        "[WoodcutterChangeState] getBlockKeyForState('%s') returned null for type='%s' at (%d,%d,%d)",
                        stateName, currentType.getId(), x, y, z);
                return;
            }
            var assetMap = BlockType.getAssetMap();
            int blockTypeIndex = assetMap.getIndex(stateKey);
            if (blockTypeIndex == Integer.MIN_VALUE) {
                HytaleLogger.getLogger().atWarning().log(
                        "[WoodcutterChangeState] no asset for key='%s' at (%d,%d,%d)",
                        stateKey, x, y, z);
                return;
            }
            BlockType targetType = (BlockType) assetMap.getAsset(blockTypeIndex);
            int rotation = chunk.getRotationIndex(x, y, z);
            // Same flags as ArcIO with UpdateBlockState=false:
            // arg7=0 (NONE), arg8=262 (NO_UPDATE_STATE | NO_SEND_PARTICLES | 256)
            chunk.setBlock(x, y, z, blockTypeIndex, targetType, rotation,
                    SetBlockSettings.NONE,
                    SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES | 256);
            HytaleLogger.getLogger().atInfo().log(
                    "[WoodcutterChangeState] setBlock '%s'->state:'%s' key='%s' idx=%d rot=%d at (%d,%d,%d)",
                    currentType.getId(), stateName, stateKey, blockTypeIndex, rotation, x, y, z);
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                    "[WoodcutterChangeState] exception applying state '%s' at (%d,%d,%d): %s",
                    stateName, x, y, z, e.getMessage());
        }
    }
}
