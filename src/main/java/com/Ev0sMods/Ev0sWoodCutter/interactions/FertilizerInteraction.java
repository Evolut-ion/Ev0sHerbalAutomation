package com.Ev0sMods.Ev0sWoodCutter.interactions;

import com.Ev0sMods.Ev0sWoodCutter.blockstates.FertilizerUIPage;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Custom interaction type for the Fertilizer block, registered as "OpenFertilizer".
 *
 * Opens the HyUI status page instead of the vanilla container UI.
 * JSON usage:
 *   "Interactions": { "Use": "OpenFertilizer" }
 */
public class FertilizerInteraction extends SimpleBlockInteraction {

    public static final BuilderCodec<FertilizerInteraction> CODEC =
            BuilderCodec.builder(
                    FertilizerInteraction.class,
                    FertilizerInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Opens the Fertilizer machine status UI.")
            .build();

    @Override
    protected void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull Vector3i blockPos,
            @Nonnull CooldownHandler cooldownHandler) {

        try {
            Ref<EntityStore> playerEnt = interactionContext.getOwningEntity();
            Store<EntityStore> store = playerEnt.getStore();
            PlayerRef playerRef = store.getComponent(playerEnt, PlayerRef.getComponentType());
            if (playerRef == null) return;
            FertilizerUIPage.open(playerRef, playerEnt, store, blockPos);
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log(
                "[FertilizerInteraction] Failed to open UI: " + t.getMessage());
        }
    }

    @Override
    protected void simulateInteractWithBlock(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull World world,
            @Nonnull Vector3i blockPos) {
        // No client-side prediction needed.
    }
}
