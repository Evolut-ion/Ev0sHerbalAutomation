---
name: Ev0s Herbal Automation
description: Automated farming, tree-cutting, and planting machines for Hytale.
author: Ev0sMods
---

![Ev0s Herbal Automation {840x0}](Docs/banner.png)

Ev0s Herbal Automation adds three automation machines designed to streamline farming, forestry, and planting. All machines optionally integrate with **ArcIO** — when ArcIO is installed, machines only operate while receiving an active manathread signal.

---

## Machines

### ![Wood Cutter {64x0}](Icons/ItemsGenerated/WoodCutter.png) Wood Cutter

An automated harvester that ticks every 5 seconds. On each tick it:

1. Collects nearby dropped item entities into its inventory.
2. Scans a **5 × 6 area** in front of it (2 blocks left/right, 5 blocks deep) and harvests any tree logs and ripe crops it finds.
3. Accelerates sapling growth in the same area.

Harvested items are stored directly in the machine's inventory. Right-click the machine to open its container and retrieve items.

---

### ![Block Placer {64x0}](Icons/ItemsGenerated/Placer.png) Block Placer

An automated planter that ticks every 5 seconds. On each tick it:

1. Scans a **5 × 5 area** centered three blocks above itself.
2. Places one item from its inventory into each empty farmland tile in that area.
3. Skips tiles that already contain a sapling, crop, or another machine.
4. Skips tiles directly below an active Fertilizer Block (to avoid planting conflicts).

Load saplings or seeds into the machine's single inventory slot. Right-click to open the container.

---

### ![Fertilizer Block {64x0}](Icons/ItemsGenerated/FertilizerBlock.png) Fertilizer Block

An automated crop accelerator. It reads the fertilizer item in **Slot 0** and the liquid in **Slot 1**, then periodically applies a growth tick to crops and saplings in a **5 × 5 area** in front of it (based on its facing direction).

#### Slot layout

| Slot | Accepted items | Purpose |
|------|---------------|---------|
| 0 | Any fertilizer item (ID contains "fertil") | Determines the fertilizer type and tick speed |
| 1 | Water bucket, Fertilizer Water | Required liquid; Fertilizer Water halves the tick interval |

#### Supported fertilizer types

| Item | Tick interval | Targets |
|------|--------------|---------|
| Standard fertilizer (any "fertil*" item) | 60 s | All crops & saplings |
| NoCube Tree Fertilizer | 30 s | Saplings only |
| NoCube Lime Fertilizer | 30 s | All crops & saplings |
| NoCube Bone Fertilizer | 15 s | All crops & saplings |
| NoCube Seashell Fertilizer | 7.5 s | All crops & saplings |
| NoCube Elite Fertilizer | ~3.8 s | All crops & saplings |

> **Tip:** Place **Fertilizer Water** in Slot 1 to halve the effective tick interval for any fertilizer type.

#### Fertilizer Water

A new craftable item that acts as an enhanced liquid for the Fertilizer Block.

**Recipe** (Farming Bench):
- 1 × Water Bucket
- 5 × NoCube Fertilizer

When placed in Slot 1, it halves the machine's growth interval compared to plain water.

---

## ArcIO Integration

All three machines support **ArcIO** as an optional dependency. When ArcIO is present on the server:

- Each machine registers itself as an ArcIO mechanism.
- The machine will **only tick while receiving a manathread signal greater than 0**.
- With no manathread connected, the machine idles (no signal = no work).
- Connect a manathread to enable/disable the machine remotely via ArcIO networks.

ArcIO is not required — machines operate normally without it.

---

## Tips

- The Fertilizer Block's facing direction determines which direction the 5 × 5 growth area extends. Place it facing your crop field.
- The Block Placer plants 3 blocks above itself — position it beneath your field.
- The Wood Cutter auto-collects dropped items in its area, so it also acts as a hopper for nearby drops.
