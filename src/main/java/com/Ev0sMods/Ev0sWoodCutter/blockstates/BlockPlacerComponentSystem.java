package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.Set;

public class BlockPlacerComponentSystem extends EntityTickingSystem<ChunkStore> {
    @Nonnull
    private final Query<ChunkStore> query;
    @Nonnull
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, com.hypixel.hytale.builtin.fluid.FluidSystems.Ticking.class),
            new SystemDependency<>(Order.BEFORE, com.hypixel.hytale.builtin.blocktick.system.ChunkBlockTickSystem.Ticking.class)
    );

    private final ComponentType<ChunkStore, BlockPlacer> componentType;

    public BlockPlacerComponentSystem(ComponentType<ChunkStore, BlockPlacer> componentType) {
        this.componentType = componentType;
        this.query = componentType;
    }

    private int debugCounter = 0;

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                     @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        if (debugCounter++ % 300 == 0) {
            System.out.println("[BlockPlacerSystem] tick called, index=" + index + " componentType=" + componentType);
        }
        if (componentType == null) return;
        BlockPlacer bp = archetypeChunk.getComponent(index, componentType);
        if (debugCounter % 300 == 1) {
            System.out.println("[BlockPlacerSystem] component lookup: " + (bp != null ? "FOUND" : "NULL"));
        }
        if (bp != null) {
            bp.tick(dt, index, archetypeChunk, store, commandBuffer);
        }
    }

    @Nonnull @Override public Query<ChunkStore> getQuery() { return query; }
    @Nonnull @Override public Set<Dependency<ChunkStore>> getDependencies() { return DEPENDENCIES; }
}
