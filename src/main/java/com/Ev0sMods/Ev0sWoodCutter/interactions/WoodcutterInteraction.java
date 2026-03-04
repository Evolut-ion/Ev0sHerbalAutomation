package com.Ev0sMods.Ev0sWoodCutter.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Custom interaction type for the WoodCutter block, registered as "OpenWoodcutter".
 *
 * Follows the ArcIO pattern: rather than using the generic built-in "Open_Container"
 * interaction, we register our own type so that:
 *   1. The interaction is explicitly bound to the WoodCutter block family.
 *   2. Future state-aware behaviour (interaction hints, sounds per-definition, etc.)
 *      can be added here without touching block-state logic.
 *   3. The JSON State.Definitions ("Off" / "On") drive the animation automatically
 *      via ArcIO's state machine — this interaction just opens the inventory UI.
 *
 * JSON usage:
 *   "Interactions": { "Use": "OpenWoodcutter" }
 *
 * Hytale opens the container automatically for any block whose BlockState implements
 * ItemContainerBlockState when the registered interaction type extends
 * SimpleBlockInteraction without overriding the container behaviour.
 */
public class WoodcutterInteraction extends SimpleBlockInteraction {

    public static final BuilderCodec<WoodcutterInteraction> CODEC =
            BuilderCodec.builder(
                    WoodcutterInteraction.class,
                    WoodcutterInteraction::new,
                    SimpleBlockInteraction.CODEC)
            .documentation("Opens the WoodCutter machine inventory.")
            .build();

    /**
     * Server-side interaction handler.
     * The base class forwards to Hytale's ItemContainerBlockState open logic;
     * override here when you need extra behaviour (e.g. state toggle, sounds).
     */
    @Override
    protected void interactWithBlock(
            @NotNull World world,
            @NotNull CommandBuffer<EntityStore> commandBuffer,
            @NotNull InteractionType interactionType,
            @NotNull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @NotNull Vector3i blockPos,
            @NotNull CooldownHandler cooldownHandler) {
        // Intentionally empty: Hytale automatically opens the ItemContainer UI for
        // any SimpleBlockInteraction targeting a block that implements
        // ItemContainerBlockState.  Add custom behaviour here when needed.
    }

    /**
     * Client-side simulation (prediction) handler.
     * Must be implemented; left empty because container-open is server-authoritative.
     */
    @Override
    protected void simulateInteractWithBlock(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull World world,
            @Nonnull Vector3i blockPos) {
        // No client-side prediction needed for container open.
    }
}
