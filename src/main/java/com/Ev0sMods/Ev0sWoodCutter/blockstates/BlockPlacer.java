package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.hypixel.hytale.component.spatial.SpatialResource;
import voidbond.arcio.ArcioPlugin;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.components.BlockUUIDComponent;

import javax.annotation.Nonnull;

public class BlockPlacer implements Component<ChunkStore>, TickableBlockState {
    private static final String LOCK_OWNER = "BlockPlacer";
    private static final long PLACE_LOCK_TTL_MS = 2500L;

    /**
     * Single-threaded executor for deferring block modifications that cannot
     * be called from within a system tick.
     */
    private static final ScheduledExecutorService BLOCK_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ev0-placer-blocks");
                t.setDaemon(true);
                return t;
            });

    /** Block operations collected during tick, flushed at the end via BLOCK_SCHEDULER. */
    private final List<Runnable> pendingBlockOps = new ArrayList<>();

    /** Set during plugin registration. */
    public static ComponentType<ChunkStore, BlockPlacer> COMPONENT_TYPE;

    public World w;
    private int square;
    public Store<EntityStore> entities;
    public Ref<EntityStore> ref;

    public static final BuilderCodec<BlockPlacer> CODEC = BuilderCodec.builder(BlockPlacer.class, BlockPlacer::new)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();

    private SimpleItemContainer itemContainer;
    private boolean containerResolved = false;
    private int containerRetryCount = 0;
    public int timer = 0;
    private int bpDebugCounter = 0;

    // --- Component position resolution ---
    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;

    /** Tracks the last known ArcIO signal state so we only log on changes. */
    private boolean lastArcioActive = false;
    /** Whether we have already ensured our ArcIO components exist on this block entity. */
    private boolean arcioInitialized = false;

    /** True when ArcIO is on the server at runtime. */
    private static final boolean ARCIO_PRESENT;
    static {
        boolean found = false;
        try { Class.forName("voidbond.arcio.components.ArcioMechanismComponent"); found = true; }
        catch (ClassNotFoundException ignored) {}
        ARCIO_PRESENT = found;
    }

    public BlockPlacer() {
    }

    public BlockPlacer(BlockPlacer other) {
        this.square = other.square;
    }

    @Override
    public BlockPlacer clone() {
        return new BlockPlacer(this);
    }

    @Override
    public WorldChunk getChunk() {
        return null;
    }

    @Override
    public Vector3i getPosition() {
        return cachedPosition;
    }

    @Override
    public void invalidate() {
        // no-op
    }

    public SimpleItemContainer getItemContainer() {
        return itemContainer;
    }

    /**
     * Try engine-provided position accessors via reflection before falling
     * back to the manual chunk-scan approach.
     */
    private void probeAndGetBlockPosition() {
        try {
            Class<?> sc = this.getClass().getSuperclass();
            if (sc != null) {
                for (String name : new String[]{"getBlockPosition", "getPosition", "getPos", "position"}) {
                    try {
                        java.lang.reflect.Method m = sc.getMethod(name);
                        Object r = m.invoke(this);
                        if (r instanceof Vector3i v3 && !(v3.x == 0 && v3.y == 0 && v3.z == 0)) {
                            cachedPosition = v3;
                            positionResolved = true;
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Cached reflection handle for BlockComponentChunk.getEntityReferences(). */
    private static volatile java.lang.reflect.Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved = false;

    @SuppressWarnings("unchecked")
    private void resolvePosition(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            int myIdx = myRef.getIndex();
            ChunkStore cs = store.getExternalData();
            var chunks = cs.getChunkIndexes();
            if (chunks == null || chunks.isEmpty()) return;

            for (long chunkIdx : chunks) {
                Ref<ChunkStore> colRef = cs.getChunkReference(chunkIdx);
                if (colRef == null) continue;
                BlockComponentChunk bcc = store.getComponent(colRef, BlockComponentChunk.getComponentType());
                if (bcc == null) continue;

                // Use reflection to invoke getEntityReferences() — the compile-time
                // return type (Int2ObjectMap) does not match the runtime descriptor.
                Map<?, ?> entityRefs = getEntityRefsViaReflection(bcc);
                if (entityRefs == null) continue;

                for (Map.Entry<?, ?> entry : entityRefs.entrySet()) {
                    Object key = entry.getKey();
                    Object val = entry.getValue();
                    if (!(key instanceof Integer blockIndex)) continue;
                    if (!(val instanceof Ref<?> blockRef)) continue;
                    if (blockRef.getIndex() == myIdx) {
                        int lx = ChunkUtil.xFromBlockInColumn(blockIndex);
                        int wy = ChunkUtil.yFromBlockInColumn(blockIndex);
                        int lz = ChunkUtil.zFromBlockInColumn(blockIndex);
                        int wx = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.xOfChunkIndex(chunkIdx), lx);
                        int wz = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.zOfChunkIndex(chunkIdx), lz);
                        cachedPosition = new Vector3i(wx, wy, wz);
                        positionResolved = true;
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> getEntityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (BlockPlacer.class) {
                    if (!entityRefsMethodResolved) {
                        for (java.lang.reflect.Method m : bcc.getClass().getMethods()) {
                            if ("getEntityReferences".equals(m.getName()) && m.getParameterCount() == 0) {
                                m.setAccessible(true);
                                entityRefsMethod = m;
                                break;
                            }
                        }
                        entityRefsMethodResolved = true;
                    }
                }
            }
            if (entityRefsMethod == null) return null;
            Object result = entityRefsMethod.invoke(bcc);
            if (result instanceof Map<?, ?> map) return map;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve the ItemContainer from the engine's ItemContainerBlock component
     * (defined in the block JSON) via reflection, so we share the same container
     * that the Open_Container UI uses.
     */
    private void resolveItemContainer(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            java.lang.reflect.Method getComponentMethod = store.getClass().getMethod(
                    "getComponent", Ref.class, Class.forName("com.hypixel.hytale.component.ComponentType"));
            Class<?> icbClass = Class.forName(
                    "com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock");
            java.lang.reflect.Method icbGetComponentType = icbClass.getMethod("getComponentType");
            Object icbComponentType = icbGetComponentType.invoke(null);
            Object icbObj = null;
            try { icbObj = getComponentMethod.invoke(store, myRef, icbComponentType); }
            catch (Throwable ignored) {}
            if (icbObj != null && icbClass.isInstance(icbObj)) {
                java.lang.reflect.Method getIC = icbObj.getClass().getMethod("getItemContainer");
                Object cont = getIC.invoke(icbObj);
                if (cont instanceof SimpleItemContainer sic) {
                    this.itemContainer = sic;
                    containerResolved = true;
                    HytaleLogger.getLogger().atInfo().log(
                            "[BlockPlacer] Resolved ItemContainerBlock container (capacity=%d)",
                            sic.getCapacity());
                }
            }
        } catch (Throwable t) {
            HytaleLogger.getLogger().atInfo().log(
                    "[BlockPlacer] Failed to resolve ItemContainerBlock: %s", t.getMessage());
        }
    }

    @Override
    public void tick(
            float v,
            int index,
            ArchetypeChunk<ChunkStore> archetypeChunk,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        boolean shouldLog = (bpDebugCounter++ % 300 == 0);

        World w = store.getExternalData().getWorld();
        if (w == null) return;

        // Resolve position on first tick
        if (!positionResolved) {
            probeAndGetBlockPosition();
            if (!positionResolved) {
                resolvePosition(store, archetypeChunk.getReferenceTo(index));
            }
            if (!positionResolved) {
                if (shouldLog) HytaleLogger.getLogger().atInfo().log("[BlockPlacer] Position NOT resolved, returning");
                return;
            }
            HytaleLogger.getLogger().atInfo().log(
                "[BlockPlacer] Position resolved: %d,%d,%d",
                cachedPosition.x, cachedPosition.y, cachedPosition.z);
        }

        // Resolve the engine's ItemContainerBlock component container
        if (!containerResolved) {
            resolveItemContainer(store, archetypeChunk.getReferenceTo(index));
            if (!containerResolved) {
                containerRetryCount++;
                if (containerRetryCount % 30 == 1) {
                    HytaleLogger.getLogger().atInfo().log(
                        "[BlockPlacer %d,%d,%d] Container NOT resolved (attempt %d), retrying...",
                        cachedPosition.x, cachedPosition.y, cachedPosition.z, containerRetryCount);
                }
                return;
            }
        }

        // Always ensure ArcIO components are attached on the very first tick after
        // placement/load, so ArcIO can connect before the work timer fires.
        if (ARCIO_PRESENT) {
            ensureArcioComponents(w, commandBuffer);
        }

        if (++timer < 150) return;
        timer = 0;

        // Gate planting on ArcIO signal now that components are guaranteed to exist.
        if (ARCIO_PRESENT) {
            boolean active = isArcioActive(w);
            if (active != lastArcioActive) {
                lastArcioActive = active;
                HytaleLogger.getLogger().atInfo().log(
                    "[BlockPlacer %d,%d,%d] ArcIO signal %s",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z,
                    active ? "ON - placer enabled" : "OFF - placer paused");
            }
            if (!active) return;
        }

        if (this.itemContainer == null) {
            if (shouldLog) HytaleLogger.getLogger().atInfo().log(
                "[BlockPlacer %d,%d,%d] itemContainer is null!",
                cachedPosition.x, cachedPosition.y, cachedPosition.z);
            return;
        }
        var stack = this.itemContainer.getItemStack((short) 0);
        if (stack == null || stack.isEmpty()) {
            if (shouldLog) HytaleLogger.getLogger().atInfo().log(
                "[BlockPlacer %d,%d,%d] No items in slot 0 (stack=%s)",
                cachedPosition.x, cachedPosition.y, cachedPosition.z,
                stack == null ? "null" : "empty");
            return;
        }
        if (shouldLog) HytaleLogger.getLogger().atInfo().log(
            "[BlockPlacer %d,%d,%d] Placing item: %s x%d",
            cachedPosition.x, cachedPosition.y, cachedPosition.z,
            stack.getItemId(), stack.getQuantity());

        int baseX = cachedPosition.x;
        int baseY = cachedPosition.y + 3;
        int baseZ = cachedPosition.z;

        int remaining = stack.getQuantity();

        for (int dx = -2; dx <= 2 && remaining > 0; dx++) {
            for (int dz = -2; dz <= 2 && remaining > 0; dz++) {

                int x = baseX + dx;
                int y = baseY;
                int z = baseZ + dz;

                // Check if there's a fertilizer 3 blocks above this position
                int fertilizerY = y + 3;
                var fertilizerBlock = w.getBlockType(x, fertilizerY, z);
                if (fertilizerBlock != null && fertilizerBlock.getId().contains("Fertilizer")) {
                    HytaleLogger.getLogger().atInfo().log("BlockPlacer: Skipping seed placement at (" + x + ", " + y + ", " + z + ") - fertilizer found at (" + x + ", " + fertilizerY + ", " + z + ")");
                    continue;
                }
                if (fertilizerBlock != null && fertilizerBlock.getId().contains("Test_Fert")) {
                    HytaleLogger.getLogger().atInfo().log("BlockPlacer: Skipping seed placement at (" + x + ", " + y + ", " + z + ") - fertilizer found at (" + x + ", " + fertilizerY + ", " + z + ")");
                    continue;
                }

                // Skip if there's already a sapling here
                var existing = w.getBlockType(x, y, z);
                if (existing != null && existing.getId().contains("Sapling")) {
                    continue;

                } if (existing != null && existing.getId().contains("Test")) {
                    continue;

                }if (existing != null && existing.getId().contains("Hopper")) {
                    continue;

                } if (existing != null && existing.getId().contains("Sucker")) {
                    continue;

                }
                if (existing != null && existing.getId().contains("Plant_Crop")) {
                    continue;

                }

                // If cutter is actively working this position, skip this tick.
                if (MachineActionLock.reservedByOther(LOCK_OWNER, x, y, z)) {
                    continue;
                }
                if (!MachineActionLock.reserve(LOCK_OWNER, x, y, z, PLACE_LOCK_TTL_MS)) {
                    continue;
                }

                // Get this block's entity reference for animation using spatial resource
                Ref<EntityStore> blockEntityRef = null;
                Store<EntityStore> entityStore = w.getEntityStore().getStore();
                SpatialResource<Ref<EntityStore>, EntityStore> spatial = (SpatialResource<Ref<EntityStore>, EntityStore>) entityStore.getResource(EntityModule.get().getEntitySpatialResourceType());
                Vector3d blockPos = new Vector3d(cachedPosition.x, cachedPosition.y, cachedPosition.z);
                ObjectArrayList<Ref<EntityStore>> foundEntities = new ObjectArrayList<>();
                spatial.getSpatialStructure().collectCylinder(blockPos, 0.5, 0.5, foundEntities);
                if (!foundEntities.isEmpty()) {
                    blockEntityRef = foundEntities.get(0);
                }
                this.ref = blockEntityRef;

                if(stack.getItemId().contains("Sapling")){
                    // Ensure there is a solid block to plant on; if not, place Soil_Grass first.
                    var below = w.getBlockType(x, y - 1, z);
                    WorldChunk saplingChunk = w.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
                    final int sx = x, sy = y, sz = z;
                    final World fw = w;
                    final String blockKey = stack.getBlockKey();
                    final WorldChunk fsc = saplingChunk;
                    final boolean needsSoil = (below == null || below.getId().equals("Empty") || below.getId().equals("Air")) && saplingChunk != null;
                    final Ref<EntityStore> fAnimRef = blockEntityRef;
                    final Store<EntityStore> fEntities = entities;
                    pendingBlockOps.add(() -> {
                        if (needsSoil && fsc != null) {
                            fsc.setBlock(sx, sy - 1, sz, "Soil_Grass", 3332);
                        }
                        fw.setBlock(sx, sy, sz, blockKey, 3332);
                        // Play animation when sapling is placed
                        if (fAnimRef != null && fAnimRef.isValid()) {
                            AnimationUtils.playAnimation(fAnimRef, AnimationSlot.Status, "place_anim.blockyanim", false, (ComponentAccessor<EntityStore>)fEntities);
                        }
                    });
                    this.itemContainer.removeItemStackFromSlot((short) 0, 1);
                    remaining--;
                } else if (stack.getItemId().contains("Seeds")) {
                    final int sx = x, sy = y, sz = z;
                    final World fw = w;
                    final String cropBlockId = stack.getItemId().replace("_Seeds", "_Crop") + "_Block";
                    final Ref<EntityStore> fAnimRef = blockEntityRef;
                    final Store<EntityStore> fEntities = entities;
                    pendingBlockOps.add(() -> {
                        fw.setBlock(sx, sy, sz, cropBlockId, 3332);
                        // Play animation when seed is placed
                        if (fAnimRef != null && fAnimRef.isValid()) {
                            AnimationUtils.playAnimation(fAnimRef, AnimationSlot.Status, "place_anim.blockyanim", false, (ComponentAccessor<EntityStore>)fEntities);
                        }
                    });
                    this.itemContainer.removeItemStackFromSlot((short) 0, 1);
                    remaining--;
                }
            }
        }
        this.ref = ref;

        flushPendingBlockOps();
    }

    /** Schedule all queued block operations to run outside the system tick. */
    private void flushPendingBlockOps() {
        if (pendingBlockOps.isEmpty()) return;
        final List<Runnable> ops = new ArrayList<>(pendingBlockOps);
        pendingBlockOps.clear();
        BLOCK_SCHEDULER.schedule(() -> {
            for (Runnable op : ops) {
                try { op.run(); }
                catch (Exception e) {
                    HytaleLogger.getLogger().atWarning().log(
                        "[BlockPlacer] Deferred block op failed: %s", e.getMessage());
                }
            }
        }, 100, TimeUnit.MILLISECONDS);
    }

    private void ensureArcioComponents(World world, CommandBuffer<ChunkStore> commandBuffer) {
        if (arcioInitialized) return;
        try {
            int bx = cachedPosition.x, by = cachedPosition.y, bz = cachedPosition.z;
            Store<ChunkStore> cs = world.getChunkStore().getStore();
            Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(
                    ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunkRef == null) return;
            BlockComponentChunk bcc = (BlockComponentChunk) cs.getComponent(
                    chunkRef, BlockComponentChunk.getComponentType());
            if (bcc == null) return;
            Ref<ChunkStore> blockRef = bcc.getEntityReference(ChunkUtil.indexBlockInColumn(bx, by, bz));
            if (blockRef == null) return;

            BlockUUIDComponent uuid = (BlockUUIDComponent) cs.getComponent(
                    blockRef, BlockUUIDComponent.getComponentType());
            if (uuid == null) {
                uuid = BlockUUIDComponent.randomUUID();
                uuid.setPosition(new Vector3i(bx, by, bz));
                if (commandBuffer != null) {
                    commandBuffer.putComponent(blockRef, BlockUUIDComponent.getComponentType(), uuid);
                } else {
                    cs.putComponent(blockRef, BlockUUIDComponent.getComponentType(), uuid);
                }
                ArcioPlugin.get().putUUID(uuid.getUuid(), blockRef);
                HytaleLogger.getLogger().atInfo().log(
                    "[BlockPlacer %d,%d,%d] Registered ArcIO UUID: %s",
                    bx, by, bz, uuid.getUuid());
            }

            ArcioMechanismComponent mech = (ArcioMechanismComponent) cs.getComponent(
                    blockRef, ArcioMechanismComponent.getComponentType());
            if (mech == null) {
                mech = new ArcioMechanismComponent("BlockPlacer", 0, 1);
                if (commandBuffer != null) {
                    commandBuffer.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                } else {
                    cs.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                }
                HytaleLogger.getLogger().atInfo().log(
                    "[BlockPlacer %d,%d,%d] Added ArcIO mechanism component (type=BlockPlacer)",
                    bx, by, bz);
            }

            arcioInitialized = true;
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[BlockPlacer %d,%d,%d] Failed to ensure ArcIO components: %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
    }

    private boolean isArcioActive(World world) {
        try {
            int bx = cachedPosition.x, by = cachedPosition.y, bz = cachedPosition.z;
            Store<ChunkStore> cs = world.getChunkStore().getStore();
            Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(
                    ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunkRef != null) {
                BlockComponentChunk bcc = (BlockComponentChunk) cs.getComponent(
                        chunkRef, BlockComponentChunk.getComponentType());
                if (bcc != null) {
                    Ref<ChunkStore> blockRef = bcc.getEntityReference(
                            ChunkUtil.indexBlockInColumn(bx, by, bz));
                    if (blockRef != null) {
                        ArcioMechanismComponent mech = (ArcioMechanismComponent) cs.getComponent(
                                blockRef, ArcioMechanismComponent.getComponentType());
                        if (mech != null) {
                            int signal = mech.getStrongestInputSignal(world);
                            int required = mech.getRequiredSignal();
                            if (signal > 0 && signal >= required) return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[BlockPlacer %d,%d,%d] ArcIO own-signal check failed: %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
        return hasAdjacentActiveArcioMechanism(world);
    }

    private boolean hasAdjacentActiveArcioMechanism(World world) {
        try {
            Store<ChunkStore> cs = world.getChunkStore().getStore();
            int bx = cachedPosition.x, by = cachedPosition.y, bz = cachedPosition.z;
            int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            for (int[] off : offsets) {
                int nx = bx + off[0], ny = by + off[1], nz = bz + off[2];
                Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(
                        ChunkUtil.indexChunkFromBlock(nx, nz));
                if (chunkRef == null) continue;
                BlockComponentChunk bcc = (BlockComponentChunk) cs.getComponent(
                        chunkRef, BlockComponentChunk.getComponentType());
                if (bcc == null) continue;
                Ref<ChunkStore> blockRef = bcc.getEntityReference(ChunkUtil.indexBlockInColumn(nx, ny, nz));
                if (blockRef == null) continue;
                ArcioMechanismComponent mc = (ArcioMechanismComponent) cs.getComponent(
                        blockRef, ArcioMechanismComponent.getComponentType());
                if (mc != null) {
                    int signal = mc.getStrongestInputSignal(world);
                    int required = mc.getRequiredSignal();
                    if (signal > 0 && signal >= required) return true;
                }
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[BlockPlacer %d,%d,%d] ArcIO adjacent check failed: %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
        return false;
    }

    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<BlockPlacer.Data> CODEC = BuilderCodec.builder(BlockPlacer.Data.class, BlockPlacer.Data::new)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}
