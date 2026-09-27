# Ground loot: what drops, and who gets it

Numbers here come straight from the code (`Gear.java`, `Loot.java`, `Coins.java`, `AotRpg.java`).

## Who gets it

All titan loot is personal:

- **Who earns it:**
  - the player who landed the kill;
  - anyone who hit the titan in the last **60 seconds** and is still within **96 blocks** of it.
- **Separate drops:** every one of them gets their **own** drops, rolled separately.
- **Hidden from others:** a player's drops are only sent to that player. Nobody else sees them or can pick them up, party members included.
- **Despawn:** personal drops despawn like normal items, after 5 minutes on the ground.

## Marks (every titan)

- **Bounty:** `4 + titan max health / 40` Marks, dropped as coin piles.
  - The killer gets the full bounty.
  - Every other earner gets **75%** of it, minimum 2.
- **Pickup:** coins drift to their owner within 5 blocks and are collected on touch.
- **Despawn:** after 2 minutes.

## Gear: will it drop?

Each earner rolls separately.

| Titan | Drop chance |
|---|---|
| Shifter, or a named boss / 300+ max health | **100%** |
| Ordinary titan | `0.12 + (width × height) / 400`, capped at **60%** |

Ordinary titan examples:

| Titan size (width × height) | Drop chance |
|---|---|
| 3 × 8 | ~18% |
| 5 × 15 | ~31% |
| 8 × 20 | ~52% |
| 10 × 30 or bigger | 60% |

## Gear: what rarity?

Luck is:

- **0** for ordinary titans;
- **1** for bosses;
- **2** for shifters;
- **+1** in Extraction mode.

Luck multiplies the weight of Rare and above by `1 + 2 × luck` and divides Common's weight by the same amount.

| Luck | Common | Uncommon | Rare | Epic | Legendary | Mythic |
|---|---|---|---|---|---|---|
| 0: ordinary titan | 60% | 25% | 10% | 4% | 1% | — |
| 1: boss, or ordinary in Extraction | 22.2% | 27.8% | 33.3% | 13.3% | 3.3% | — |
| 2: shifter, or boss in Extraction | 10.7% | 22.3% | 44.6% | 17.9% | 4.5% | — |
| 3: shifter in Extraction | 5.8% | 18.1% | 50.7% | 20.3% | 5.1% | — |

**Mythic never drops from titans.** It only comes from the rarest crate rolls:

| Crate | Mythic chance per opening |
|---|---|
| Armory | 0.5% (gear) |
| Wardrobe | 0.5% (cosmetic) |
| Honors | 0.5% (title) |
| Officer's | 0.2% (gear) |
| Commander's | 1.5% (gear, title or cosmetic) |

## Gear: what item level?

The item level is based on:

- the area's level, capped at **4 above your level**, or 4 above your strongest party member's level if they're nearby;
- plus a **+1 bonus for bosses** and **+3 for shifters**;
- plus or minus 1 at random.

Gear above your level drops, but it does nothing until you reach its level. Its card on the ground says "Too high for you yet".

## Rewards that skip the ground

These go straight into your inventory or satchel, so they are personal by default:

- **Raid clear:**

  | Difficulty | Gear |
  |---|---|
  | Normal | Rare |
  | Hard | Epic |
  | Nightmare | Legendary |

  Always at raid level +2.
- **Call to Arms MVP:**

  | Kills | Gear |
  |---|---|
  | Under 6 | Rare |
  | 6 or more | Epic |
  | 12 or more | Legendary |

  Plus a 35% chance at a rare cosmetic.
- **Quest rewards:** a rolled rarity at the quest's level.

## On the ground

- **No glow.** Items show a floating card instead:
  - **From afar:** the name, in its rarity colour.
  - **Within 7 blocks:** also rarity and kind, item level, up to 4 stats, and a warning if it's too high for you.
- **Frame:** gear with a rarity gets a glowing frame in that colour, pulsing on Legendary, and faster and redder on Mythic.
