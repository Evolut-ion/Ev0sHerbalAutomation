package com.Ev0sMods.Ev0sWoodCutter.interactions;

import com.Ev0sMods.Ev0sWoodCutter.Util.FarmingUtilExtended;
import com.hypixel.hytale.builtin.adventure.farming.FarmingUtil;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class HarvesterCropInteraction extends SimpleBlockInteraction {
    public static final BuilderCodec<com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction> CODEC;

    public void interactWithBlock(@Nonnull World world,  @Nonnull ComponentAccessor<EntityStore> store, @Nullable ItemStack itemInHand, @Nonnull Vector3i targetBlock) {

        ChunkStore chunkStore = world.getChunkStore();
        long chunkIndex = ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z);
        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
        if (chunkRef != null && chunkRef.isValid()) {
            BlockChunk blockChunkComponent = (BlockChunk)chunkStore.getStore().getComponent(chunkRef, BlockChunk.getComponentType());

            assert blockChunkComponent != null;

            BlockSection section = blockChunkComponent.getSectionAtBlockY(targetBlock.y);
            if (section != null) {
                WorldChunk worldChunkComponent = (WorldChunk)chunkStore.getStore().getComponent(chunkRef, WorldChunk.getComponentType());

                assert worldChunkComponent != null;

                BlockType blockType = worldChunkComponent.getBlockType(targetBlock);
                if (blockType != null) {
                    int rotationIndex = section.getRotationIndex(targetBlock.x, targetBlock.y, targetBlock.z);
                    FarmingUtilExtended.harvest0((ComponentAccessor<EntityStore>) world, blockType, rotationIndex, targetBlock);
                }
            }
        }
    }

    @Override
    protected void interactWithBlock(@NotNull World world, @NotNull CommandBuffer<EntityStore> commandBuffer, @NotNull InteractionType interactionType, @NotNull InteractionContext interactionContext, @org.jetbrains.annotations.Nullable ItemStack itemStack, @NotNull Vector3i vector3i, @NotNull CooldownHandler cooldownHandler) {

    }

    protected void simulateInteractWithBlock(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nullable ItemStack itemInHand, @Nonnull World world, @Nonnull Vector3i targetBlock) {
    }

    @Nonnull
    public String toString() {
        return "HarvestCropInteraction{} " + super.toString();
    }

    static {
        CODEC = ((BuilderCodec.Builder)BuilderCodec.builder(com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction.class, com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction::new, SimpleBlockInteraction.CODEC).documentation("Harvests the resources from the target farmable block.")).build();
    }
}

