package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.Ev0sMods.Ev0sWoodCutter.interactions.WoodcutterChangeStateInteraction;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.Map;
import voidbond.arcio.ArcioPlugin;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.components.BlockUUIDComponent;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WoodCutter implements Component<ChunkStore>, TickableBlockState {
    private static final String LOCK_OWNER = "WoodCutter";
    private static final long CUT_LOCK_TTL_MS = 2500L;

    /**
     * Single-threaded executor for deferring block modifications (breakBlock, setBlock,
     * removeEntity) that cannot be called from within a system tick.
     */
    private static final ScheduledExecutorService BLOCK_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ev0-woodcutter-blocks");
                t.setDaemon(true);
                return t;
            });

    /** Block operations collected during tick, flushed at the end via BLOCK_SCHEDULER. */
    private final List<Runnable> pendingBlockOps = new ArrayList<>();

    /** Set during plugin registration. */
    public static ComponentType<ChunkStore, WoodCutter> COMPONENT_TYPE;

    public World w;
    private int square;
    public Store<EntityStore> entities;

    public static final BuilderCodec<WoodCutter> CODEC = BuilderCodec.builder(WoodCutter.class, WoodCutter::new)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();

    public int timer = 0;

    // --- Component position resolution ---
    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;
    private int cachedRotation = 0;
    private SimpleItemContainer itemContainer;
    private boolean containerResolved = false;
    private int containerRetryCount = 0;

    /** Whether we have already ensured our ArcIO components exist on this block entity. */
    private boolean arcioInitialized = false;
    /** Whether the looping animation is currently playing. */
    private boolean isAnimating = false;

    /** True when ArcIO is on the server at runtime. */
    private static final boolean ARCIO_PRESENT;
    static {
        boolean found = false;
        try { Class.forName("voidbond.arcio.components.ArcioMechanismComponent"); found = true; }
        catch (ClassNotFoundException ignored) {}
        ARCIO_PRESENT = found;
    }

    public WoodCutter() {
    }

    public WoodCutter(WoodCutter other) {
        this.square = other.square;
    }

    @Override
    public WoodCutter clone() {
        return new WoodCutter(this);
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
    private boolean probeOnce = false;
    private void probeAndGetBlockPosition() {
        try {
            Class<?> sc = this.getClass().getSuperclass();
            if (!probeOnce) {
                probeOnce = true;
                System.out.println("[WC-probe] this.class=" + this.getClass().getName()
                    + " superclass=" + (sc != null ? sc.getName() : "null"));
                // Also list ALL methods on this runtime class hierarchy
                StringBuilder sb = new StringBuilder("[WC-probe] All methods: ");
                for (java.lang.reflect.Method m : this.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && Vector3i.class.isAssignableFrom(m.getReturnType())) {
                        sb.append(m.getDeclaringClass().getSimpleName()).append(".").append(m.getName()).append("() ");
                    }
                }
                System.out.println(sb.toString());
            }
            if (sc != null) {
                for (String name : new String[]{"getBlockPosition", "getPosition", "getPos", "position"}) {
                    try {
                        java.lang.reflect.Method m = sc.getMethod(name);
                        Object r = m.invoke(this);
                        if (r instanceof Vector3i v3 && !(v3.x == 0 && v3.y == 0 && v3.z == 0)) {
                            cachedPosition = v3;
                            positionResolved = true;
                            System.out.println("[WC-probe] Resolved via " + name + ": " + cachedPosition);
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean resolveLogOnce = false;
    /** Cached reflection handle for BlockComponentChunk.getEntityReferences(). */
    private static volatile java.lang.reflect.Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved = false;

    @SuppressWarnings("unchecked")
    private void resolvePosition(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            int myIdx = myRef.getIndex();
            ChunkStore cs = store.getExternalData();
            var chunks = cs.getChunkIndexes();
            if (!resolveLogOnce) {
                resolveLogOnce = true;
                System.out.println("[WC-resolve] myIdx=" + myIdx
                    + " chunkCount=" + (chunks != null ? chunks.size() : "null"));
            }
            if (chunks == null || chunks.isEmpty()) return;

            for (long chunkIdx : chunks) {
                Ref<ChunkStore> colRef = cs.getChunkReference(chunkIdx);
                if (colRef == null) continue;
                BlockComponentChunk bcc = store.getComponent(colRef, BlockComponentChunk.getComponentType());
                if (bcc == null) continue;

                // Use reflection to invoke getEntityReferences() — the compile-time
                // return type (Int2ObjectMap) does not match the runtime descriptor,
                // causing NoSuchMethodError when called directly.
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
                        System.out.println("[WC-resolve] FOUND at " + cachedPosition);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            if (!resolveLogOnce) {
                resolveLogOnce = true;
                System.out.println("[WC-resolve] EXCEPTION: " + t.getClass().getName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Invoke getEntityReferences() via reflection so the JVM does not try to
     * link against a specific return-type descriptor at load time.
     */
    @SuppressWarnings("unchecked")
    private static Map<?, ?> getEntityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (WoodCutter.class) {
                    if (!entityRefsMethodResolved) {
                        // Search all declared + inherited methods named getEntityReferences
                        for (java.lang.reflect.Method m : bcc.getClass().getMethods()) {
                            if ("getEntityReferences".equals(m.getName()) && m.getParameterCount() == 0) {
                                m.setAccessible(true);
                                entityRefsMethod = m;
                                System.out.println("[WC-resolve] Found getEntityReferences() -> " + m.getReturnType().getName());
                                break;
                            }
                        }
                        if (entityRefsMethod == null) {
                            System.out.println("[WC-resolve] getEntityReferences() NOT found on " + bcc.getClass().getName());
                            // List available methods for debugging
                            StringBuilder sb = new StringBuilder("[WC-resolve] Available methods: ");
                            for (java.lang.reflect.Method m : bcc.getClass().getMethods()) {
                                if (m.getName().toLowerCase().contains("entity") || m.getName().toLowerCase().contains("ref")) {
                                    sb.append(m.getName()).append("(").append(m.getParameterCount()).append(") ");
                                }
                            }
                            System.out.println(sb);
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
                            "[WoodCutter] Resolved ItemContainerBlock container (capacity=%d)",
                            sic.getCapacity());
                }
            }
        } catch (Throwable t) {
            HytaleLogger.getLogger().atInfo().log(
                    "[WoodCutter] Failed to resolve ItemContainerBlock: %s", t.getMessage());
        }
    }

    private int wcDebugCounter = 0;

    @Override
    public void tick(
            float v,
            int index,
            ArchetypeChunk<ChunkStore> archetypeChunk,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        boolean shouldLog = (wcDebugCounter++ % 300 == 0);

        World w = store.getExternalData().getWorld();
        if (w == null) {
            if (shouldLog) System.out.println("[WC-tick] World is NULL, returning");
            return;
        }

        // Resolve position on first tick
        if (!positionResolved) {
            probeAndGetBlockPosition();
            if (!positionResolved) {
                resolvePosition(store, archetypeChunk.getReferenceTo(index));
            }
            if (!positionResolved) {
                if (shouldLog) System.out.println("[WC-tick] Position NOT resolved, returning");
                return;
            }
            if (shouldLog) System.out.println("[WC-tick] Position resolved: " + cachedPosition);
        }

        // Resolve the engine's ItemContainerBlock component container
        if (!containerResolved) {
            resolveItemContainer(store, archetypeChunk.getReferenceTo(index));
            if (!containerResolved) {
                containerRetryCount++;
                if (containerRetryCount % 30 == 1) {
                    System.out.println("[WC-tick] Container NOT resolved (attempt " + containerRetryCount + "), retrying...");
                }
                return;
            }
        }

        // Always ensure ArcIO components are attached early so ArcIO can
        // connect before the work timer fires.
        if (ARCIO_PRESENT) {
            ensureArcioComponents(w, commandBuffer);
        }

        if (++timer < 150) {
            if (shouldLog) System.out.println("[WC-tick] Timer=" + timer + " < 150, waiting...");
            return;
        }
        timer = 0;
        if (shouldLog) System.out.println("[WC-tick] Timer fired! Position=" + cachedPosition + " posResolved=" + positionResolved);

            // Resolve rotation from chunk
            WorldChunk posChunk = w.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
            if (posChunk != null) {
                cachedRotation = posChunk.getRotationIndex(cachedPosition.x, cachedPosition.y, cachedPosition.z);
            }

            // EntityStore needed for item collection.
            Store<EntityStore> entityStore = w.getEntityStore().getStore();
            this.entities = entityStore;

            // If ArcIO is installed, gate on signal.
            if (ARCIO_PRESENT) {
                boolean active = isArcioActive(w);
                HytaleLogger.getLogger().atInfo().log(
                    "[WoodCutter %d,%d,%d] ArcIO active=%b",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z, active);
                if (!active) {
                    // Signal off — ensure we are in Off state and stop.
                    if (isAnimating) {
                        final World fw = w;
                        pendingBlockOps.add(() -> applyBlockState(fw, WoodcutterChangeStateInteraction.STATE_OFF));
                        flushPendingBlockOps();
                        isAnimating = false;
                    }
                    return;
                }
            }

            HytaleLogger.getLogger().atInfo().log(
                "[WoodCutter %d,%d,%d] Tick - scanning area (rot=%d)",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, cachedRotation);

            // Tracks whether any harvesting occurs this tick — drives On/Off state for non-ArcIO.
            boolean didWork = false;

    /* ---------------------------------
       PHASE 0: Collect nearby dropped items
       --------------------------------- */
            for (Ref<EntityStore> itemRef : getAllItemsInBox(
                    this,
                    this.cachedPosition,
                    entityStore,
                    true, true, true
            )) {
                ItemComponent ic =
                        itemRef.getStore().getComponent(itemRef, ItemComponent.getComponentType());
                if (ic == null) continue;

                ItemStack stack = ic.getItemStack();
                if (stack == null) continue;

                if (this.itemContainer.canAddItemStack(stack)) {
                    this.itemContainer.addItemStack(stack);
                    // removeEntity on the EntityStore is safe during tick (only ChunkStore is locked).
                    // Must be done immediately — the spatial ref becomes stale after the tick.
                    try {
                        entities.removeEntity(itemRef, RemoveReason.REMOVE);
                    } catch (Exception e) {
                        HytaleLogger.getLogger().atInfo().log("[WoodCutter] removeEntity failed: %s", e.getMessage());
                    }
                }
            }

            int baseX = cachedPosition.x;
            int baseY = cachedPosition.y;
            int baseZ = cachedPosition.z;

            // List to track all sapling positions for growth
            List<Vector3i> saplingsToGrow = new ArrayList<>();

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = 1; dz <= 5; dz++) {

                    int x = baseX + dx;
                    int y = baseY;
                    int z = baseZ + dz;
                    if(cachedRotation == 0){
                        z = baseZ + dz;
                    }
                    if(cachedRotation == 1){
                        z = baseZ - dz;
                    }
                    if(cachedRotation == 2){
                         x = baseX + dz;
                         z = baseZ + dx;
                    }
                    if(cachedRotation == 3){
                        x = baseX - dz;
                        z = baseZ + dx;
                    }

                    BlockType block = w.getBlockType(x, y, z);
                    if (block == null) continue;

                    // Failsafe: skip cells currently reserved by the placer.
                    if (MachineActionLock.reservedByOther(LOCK_OWNER, x, y, z)) {
                        continue;
                    }

                    // If already a sapling, just queue it for growth
                    if (block.getId().startsWith("Plant_Sapling_")) {
                        saplingsToGrow.add(new Vector3i(x, y, z));
                        continue;
                    }
                    if (block.getId().contains("Seed")) {
                        continue;
                    }

                    // --- Crop handling: only harvest fully grown, skip immature ---
                    if (block.getId().contains("Plant_Crop_")) {
                        boolean isFullyGrown = false;
                        FarmingData farmingConfig = block.getFarming();

                        if (farmingConfig != null && farmingConfig.getStages() != null) {
                            Store<ChunkStore> chunkStore = w.getChunkStore().getStore();
                            Ref<ChunkStore> chunkRef = w.getChunkStore().getChunkReference(
                                    ChunkUtil.indexChunkFromBlock(x, z));
                            if (chunkRef != null) {
                                BlockComponentChunk blockComponentChunk =
                                        (BlockComponentChunk) chunkStore.getComponent(
                                                chunkRef, BlockComponentChunk.getComponentType());
                                if (blockComponentChunk != null) {
                                    int blockIndexColumn = ChunkUtil.indexBlockInColumn(x, y, z);
                                    Ref<ChunkStore> blockRef =
                                            blockComponentChunk.getEntityReference(blockIndexColumn);

                                    if (blockRef != null) {
                                        FarmingBlock farmingBlock =
                                                (FarmingBlock) chunkStore.getComponent(
                                                        blockRef, FarmingBlock.getComponentType());
                                        if (farmingBlock != null) {
                                            // Plant still has a FarmingBlock → check its stage
                                            float currentProgress = farmingBlock.getGrowthProgress();
                                            int currentStage = (int) currentProgress;

                                            String currentStageSet = farmingBlock.getCurrentStageSet();
                                            if (currentStageSet == null) {
                                                currentStageSet = farmingConfig.getStartingStageSet();
                                            }

                                            FarmingStageData[] stages =
                                                    (FarmingStageData[]) farmingConfig.getStages()
                                                            .get(currentStageSet);
                                            if (stages != null && stages.length > 0) {
                                                if (currentStage >= stages.length - 1) {
                                                    isFullyGrown = true;
                                                } else {
                                                    // Not mature – leave it alone
                                                    HytaleLogger.getLogger().atFine().log(
                                                            "[WoodCutter %d,%d,%d] Crop %s at (%d,%d,%d) still growing: stage %d/%d",
                                                            cachedPosition.x, cachedPosition.y, cachedPosition.z,
                                                            block.getId(), x, y, z,
                                                            currentStage, stages.length - 1);
                                                }
                                            }
                                        } else {
                                            // FarmingBlock component was removed → growth completed
                                            isFullyGrown = true;
                                        }
                                    } else {
                                        // No block entity ref → FarmingBlock removed after growth finished
                                        isFullyGrown = true;
                                    }
                                }
                            }
                        } else {
                            // No farming stages defined → treat as harvestable
                            isFullyGrown = true;
                        }

                        if (isFullyGrown) {
                            if (!MachineActionLock.reserve(LOCK_OWNER, x, y, z, CUT_LOCK_TTL_MS)) {
                                continue;
                            }
                            WorldChunk cropChunk = w.getChunkIfInMemory(
                                    ChunkUtil.indexChunkFromBlock(x, z));
                            if (cropChunk != null) {
                                HytaleLogger.getLogger().atInfo().log(
                                        "[WoodCutter %d,%d,%d] Harvesting fully grown crop %s at (%d,%d,%d)",
                                        cachedPosition.x, cachedPosition.y, cachedPosition.z,
                                        block.getId(), x, y, z);
                                final WorldChunk fCropChunk = cropChunk;
                                final int cx = x, cy = y, cz = z;
                                pendingBlockOps.add(() -> fCropChunk.setBlock(cx, cy, cz, "Empty", 3332));
                                didWork = true;
                                if (block.getGathering() != null
                                        && block.getGathering().getHarvest() != null) {
                                    this.itemContainer.addItemStacks(
                                            BlockHarvestUtils.getDrops(block, 3, null,
                                                    block.getGathering().getHarvest().getDropListId()));
                                }
                            }
                        }
                        continue; // Crop handled – skip hardwood checks, move to next block
                    }

                    // Branches are harvested identically to trunks.
                    if (block.getId().contains("Branch")) {
                        if (!MachineActionLock.reserve(LOCK_OWNER, x, y, z, CUT_LOCK_TTL_MS)) {
                            continue;
                        }
                        WorldChunk branchChunk = w.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
                        if (branchChunk != null) {
                            HytaleLogger.getLogger().atInfo().log(
                                "[WoodCutter %d,%d,%d] Cutting branch %s at (%d,%d,%d)",
                                cachedPosition.x, cachedPosition.y, cachedPosition.z, block.getId(), x, y, z);
                            final WorldChunk fbc = branchChunk;
                            final int bx = x, by = y, bz = z;
                            pendingBlockOps.add(() -> {
                                fbc.breakBlock(bx, by, bz, 3332);
                                fbc.breakBlock(bx, by + 1, bz, 3332);
                                fbc.breakBlock(bx, by - 1, bz, 3332);
                                fbc.setBlock(bx, by - 1, bz, "Soil_Grass");
                                fbc.setBlock(bx, by - 2, bz, "Soil_Grass");
                            });
                             pendingBlockOps.add(() -> {
                                fbc.breakBlock(bx, by + 1, bz, 3332);


                            });

                            didWork = true;
                        }
                        continue;
                    }

                    Item item = block.getItem();

                    // Primary check: resource types on the item
                    boolean isWood = false;
                    if (item != null && item.getResourceTypes() != null) {
                        for (var rt : item.getResourceTypes()) {
                            if (rt.id != null && rt.id.startsWith("Wood_")) {
                                isWood = true;
                                break;
                            }
                        }
                    }
                    // Fallback: check block ID directly (covers missing item/resource-type data)
                    if (!isWood) {
                        String blockId = block.getId();
                        if (blockId.contains("Wood_") || blockId.contains("_Trunk") || blockId.contains("Branch")) {
                            isWood = true;
                        }
                    }
                    if (!isWood) continue;

                    if (!MachineActionLock.reserve(LOCK_OWNER, x, y, z, CUT_LOCK_TTL_MS)) {
                        continue;
                    }

                    WorldChunk chunk = w.getChunkIfInMemory(
                            ChunkUtil.indexChunkFromBlock(x, z)
                    );
                    if (chunk == null) continue;

                    HytaleLogger.getLogger().atInfo().log(
                        "[WoodCutter %d,%d,%d] Cutting tree %s at (%d,%d,%d)",
                        cachedPosition.x, cachedPosition.y, cachedPosition.z,
                        block.getId(), x, y, z);

                    // Normalize the species name — prefer item.getBlockId(), fall back to block ID
                    String sourceId = (item != null && item.getBlockId() != null) ? item.getBlockId() : block.getId();
                    String sapling = sourceId
                            .replaceFirst("^Wood_", "")
                            .replaceAll("_(Trunk|Full|Large|Mature|Stump)$", "");
                    sapling = sapling.replace("_Trunk", "");

                    // Clear tree (deferred to avoid store modification during tick)
                    final WorldChunk fc = chunk;
                    final int tx = x, ty = y, tz = z;
                    pendingBlockOps.add(() -> {
                        fc.breakBlock(tx, ty, tz, 3332);
                        fc.breakBlock(tx, ty + 1, tz, 3332);
                        fc.breakBlock(tx, ty - 1, tz, 3332);
                        fc.breakBlock(tx, ty - 2, tz, 3332);
                        // Restore ground blocks to Soil_Grass (trees can grow roots down)
                        fc.setBlock(tx, ty - 1, tz, "Soil_Grass", 3332);
                        fc.setBlock(tx, ty - 2, tz, "Soil_Grass", 3332);
                    });
                    didWork = true;

                    saplingsToGrow.add(new Vector3i(x, y, z));
                }
            }

    /* ---------------------------------
       PHASE 2: Cleanup - scan 7-wide × 8-deep area at all heights
       for stray wood / branch blocks left by overgrown trees.
       --------------------------------- */
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = 1; dz <= 6; dz++) {
                    int cx = baseX + dx;
                    int cz = baseZ + dz;
                    if (cachedRotation == 1) { cz = baseZ - dz; }
                    if (cachedRotation == 2) { cx = baseX + dz; cz = baseZ + dx; }
                    if (cachedRotation == 3) { cx = baseX - dz; cz = baseZ + dx; }

                    WorldChunk cleanChunk = w.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(cx, cz));
                    if (cleanChunk == null) continue;

                    for (int cy = baseY - 2; cy <= baseY + 25; cy++) {
                        BlockType cleanBlock = w.getBlockType(cx, cy, cz);
                        if (cleanBlock == null) continue;

                        String cleanId = cleanBlock.getId();
                        // Skip saplings: do not remove blocks that are saplings
                        if (cleanId.startsWith("Plant_Sapling_")) continue;

                        boolean isStrayWood = cleanId.contains("Branch")
                                || cleanId.contains("_Trunk")
                                || cleanId.contains("Wood_")
                                || cleanId.contains("_Log");
                        if (!isStrayWood) {
                            Item cleanItem = cleanBlock.getItem();
                            if (cleanItem != null && cleanItem.getResourceTypes() != null) {
                                for (var rt : cleanItem.getResourceTypes()) {
                                    if (rt.id != null && rt.id.startsWith("Wood_")) {
                                        isStrayWood = true;
                                        break;
                                    }
                                }
                            }
                        }

                        if (isStrayWood) {
                            if (MachineActionLock.reservedByOther(LOCK_OWNER, cx, cy, cz)) {
                                continue;
                            }
                            if (!MachineActionLock.reserve(LOCK_OWNER, cx, cy, cz, CUT_LOCK_TTL_MS)) {
                                continue;
                            }
                            final WorldChunk fcc = cleanChunk;
                            final int fcx = cx, fcy = cy, fcz = cz;
                            final int fBaseY = baseY;
                            pendingBlockOps.add(() -> {
                                fcc.breakBlock(fcx, fcy, fcz, 3332);
                                // Only restore soil when clearing at or below machine level
                                if (fcy <= fBaseY) {
                                    fcc.setBlock(fcx, fcy - 1, fcz, "Soil_Grass", 3332);
                                    fcc.setBlock(fcx, fcy - 2, fcz, "Soil_Grass", 3332);
                                }
                            });
                            didWork = true;
                        }
                    }
                }
            }

            // Always drive On/Off based on whether we actually harvested something.
            if (didWork != isAnimating) {
                final String targetState = didWork
                        ? WoodcutterChangeStateInteraction.STATE_ON
                        : WoodcutterChangeStateInteraction.STATE_OFF;
                final World fw = w;
                pendingBlockOps.add(() -> applyBlockState(fw, targetState));
                isAnimating = didWork;
            }

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
                        "[WoodCutter] Deferred block op failed: %s", e.getMessage());
                }
            }
        }, 100, TimeUnit.MILLISECONDS);
    }

    private void applyBlockState(World world, String stateName) {
        try {
            int bx = cachedPosition.x, by = cachedPosition.y, bz = cachedPosition.z;
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) {
                HytaleLogger.getLogger().atWarning().log(
                    "[WoodCutter %d,%d,%d] applyBlockState: chunk null", bx, by, bz);
                return;
            }
            BlockType current = chunk.getBlockType(new Vector3i(bx, by, bz));
            if (current == null) {
                HytaleLogger.getLogger().atWarning().log(
                    "[WoodCutter %d,%d,%d] applyBlockState: BlockType null", bx, by, bz);
                return;
            }
            // Mirror ChangeStateInteraction: getBlockKeyForState -> asset map index -> setBlock
            String stateKey = current.getBlockKeyForState(stateName);
            if (stateKey == null) {
                HytaleLogger.getLogger().atWarning().log(
                    "[WoodCutter %d,%d,%d] applyBlockState: no key for state '%s' (current=%s)",
                    bx, by, bz, stateName, current.getId());
                return;
            }
            var assetMap = BlockType.getAssetMap();
            int blockTypeIndex = assetMap.getIndex(stateKey);
            if (blockTypeIndex == Integer.MIN_VALUE) {
                HytaleLogger.getLogger().atWarning().log(
                    "[WoodCutter %d,%d,%d] applyBlockState: asset index not found for key '%s'",
                    bx, by, bz, stateKey);
                return;
            }
            BlockType target = (BlockType) assetMap.getAsset(blockTypeIndex);
            int rot = chunk.getRotationIndex(bx, by, bz);
            HytaleLogger.getLogger().atInfo().log(
                "[WoodCutter %d,%d,%d] setBlock to state '%s' key='%s' idx=%d rot=%d",
                bx, by, bz, stateName, stateKey, blockTypeIndex, rot);
            chunk.setBlock(bx, by, bz, blockTypeIndex, target, rot,
                SetBlockSettings.NONE,
                SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES | 256);
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[WoodCutter %d,%d,%d] applyBlockState failed: %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
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

            // Add BlockUUIDComponent if missing
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
                    "[WoodCutter %d,%d,%d] Registered ArcIO UUID: %s",
                    bx, by, bz, uuid.getUuid());
            }

            // Add ArcioMechanismComponent if missing
            ArcioMechanismComponent mech = (ArcioMechanismComponent) cs.getComponent(
                    blockRef, ArcioMechanismComponent.getComponentType());
            if (mech == null) {
                mech = new ArcioMechanismComponent("Woodcutter", 0, 1);
                if (commandBuffer != null) {
                    commandBuffer.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                } else {
                    cs.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                }
                HytaleLogger.getLogger().atInfo().log(
                    "[WoodCutter %d,%d,%d] Added ArcIO mechanism component (type=Woodcutter)",
                    bx, by, bz);
            }

            arcioInitialized = true;
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[WoodCutter %d,%d,%d] Failed to ensure ArcIO components: %s",
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
                "[WoodCutter %d,%d,%d] ArcIO own-signal check failed: %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
        // Fallback: check adjacent ArcIO mechanisms
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
                "[WoodCutter %d,%d,%d] ArcIO adjacent check failed: %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
        return false;
    }

    public List<Ref<EntityStore>> getAllItemsInBox(WoodCutter hp, Vector3i pos, @Nonnull ComponentAccessor<EntityStore> components, boolean players, boolean entities, boolean items) {
        final java.util.List<Ref<EntityStore>> results = SpatialResource.getThreadLocalReferenceList();
        if (items) {
            components.getResource(EntityModule.get().getItemSpatialResourceType()).getSpatialStructure().collectCylinder(new Vector3d(pos.x,pos.y,pos.z), 20,20,results );
        }
        this.entities = (Store<EntityStore>) components;
        return results;
    }

    private Ref<ChunkStore> getBlockEntityReference(World world) {
        Store<ChunkStore> cs = world.getChunkStore().getStore();
        Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(
                ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
        if (chunkRef == null) return null;
        BlockComponentChunk bcc = (BlockComponentChunk) cs.getComponent(
                chunkRef, BlockComponentChunk.getComponentType());
        if (bcc == null) return null;
        return bcc.getEntityReference(ChunkUtil.indexBlockInColumn(cachedPosition.x, cachedPosition.y, cachedPosition.z));
    }

    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}
