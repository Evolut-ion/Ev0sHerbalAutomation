package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

public class FertilizerComponentSystem extends EntityTickingSystem<ChunkStore> {
    @Nonnull
    private final Query<ChunkStore> query;
    @Nonnull
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, com.hypixel.hytale.builtin.fluid.FluidSystems.Ticking.class),
            new SystemDependency<>(Order.BEFORE, com.hypixel.hytale.builtin.blocktick.system.ChunkBlockTickSystem.Ticking.class)
    );

    private final ComponentType<ChunkStore, FertilizerState> componentType;

    public FertilizerComponentSystem(ComponentType<ChunkStore, FertilizerState> componentType) {
        this.componentType = componentType;
        this.query = componentType;
    }

    private int debugCounter = 0;

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        if (debugCounter++ % 300 == 0) {
            System.out.println("[FertilizerSystem] tick called, index=" + index + " componentType=" + componentType);
        }
        if (componentType == null) return;
        FertilizerState fs = archetypeChunk.getComponent(index, componentType);
        if (debugCounter % 300 == 1) {
            System.out.println("[FertilizerSystem] component lookup: " + (fs != null ? "FOUND" : "NULL"));
        }
        if (fs != null) {
            fs.tick(dt, index, archetypeChunk, store, commandBuffer);
        }
    }

    @Nonnull @Override public Query<ChunkStore> getQuery() { return query; }
    @Nonnull @Override public Set<Dependency<ChunkStore>> getDependencies() { return DEPENDENCIES; }
}
