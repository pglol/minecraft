# Story design: a parallel protagonist in Attack on Titan's world

> The game stops being "Eren's story with dialogue choices" and becomes "Attack on Titan's world
> with the player in a parallel protagonist role." Eren still exists and the major canon events
> still happen, but the player reaches them from different directions, relationships, factions
> and motivations.

**Principle:** many beginnings → personal journeys → shared historical events → branching
consequences → converging major events → radically different endings.

**No filler.** A mission exists only if it does at least one of:
reveals character · advances the war · exposes lore · changes a relationship · alters a faction ·
affects the future. Every mission in the data carries the tags it satisfies; a mission with none
fails validation and doesn't load.

Each mission also belongs to one of Act II's three threads (used from Act I on):
**Survival** (fighting titans), **Truth** (discovering the world), **People** (deciding who to trust).

---

## 1. Three layers of world state

This is what makes the story personal without breaking the shared world.

| Layer | Who it belongs to | What lives there | Who sees it |
|---|---|---|---|
| **Personal** | one character | act and beat, mission states, choices, relationships, ideology, deeds, flags, phased NPCs/props | that player, plus party members who join their scene |
| **Party scene** | the scene's host | a running mission instance: its actors, props, fights, dialogue | host + party members who opted in |
| **World** | the server | terrain, towns, markets, homes, roaming titans, world events (Call to Arms, hordes, abnormals, wall breaches, boss raids) | everyone |

Rules:

- A single player's story **never changes another player's world.** Story-only NPCs, rubble, fires
  and rescued civilians are **phased**: they exist only for the players in that scene.
- **World events are the only things that touch everyone.** The story reacts to them ("you held
  the gate at the Trost defense") through flags on each participant, but never the other way round.
- The base world stays neutral and persistent: towns look the same to everyone; your story shows
  its own layer on top.

### Phasing (how it works technically)

- **Actors** (story NPCs and scripted titans) are real server entities tagged with a scene id. A
  tracker mixin only sends them to players in that scene, so others never see them. They can't be
  hurt by or collide with outsiders.
- **Props** (collapsed walls, fires, barricades, blood) are sent as per-player block updates to scene
  members only. The real blocks never change, and props are re-sent when chunks reload.
- **Instances** for things the shared map can't host (Shiganshina in 845 before it fell, the
  basement, Paths) use a private copy of the location in the home world, the same trick player
  homes already use.

## 2. Party: everyone's story, together

- Anyone in your party within range sees a prompt when you start a story scene: **Join scene**.
  Joiners see your actors, fight with you and get XP, Marks and pass XP.
- **The host's choices decide the host's story.** Guests can *suggest* a choice (their pick shows
  on the dialogue screen), but guest stories are never written by someone else.
- Guests collect **witness flags** ("Witnessed Eren's transformation with Ada"). Their own story can
  use these later (a line of recognition, a relationship bump), but they still play their own
  beats themselves.
- If a guest is *behind* you in the story, beats that would spoil their own story are marked, and
  they can choose to watch or skip.
- Party role lineups (already built) feed missions: some objectives have Tank/Medic/Recon moments
  (hold a door, keep a civilian alive, spot the nape). A solo player can still complete them with
  Lone Wolf help.

## 3. Who you become: relationships, ideology, deeds

There's no single reputation number. Four records build up over the whole game:

- **Relationships:** an affinity score (−100 to 100) with each canon character (Eren, Mikasa, Armin,
  Levi, Hange, Erwin, Jean, Connie, Sasha, Historia, Ymir, Reiner, Bertholdt, Annie, Zeke, Pieck,
  Porco, Floch, Gabi, Falco, Onyankopon, Yelena…), plus memories (named flags like
  `saved_by_mikasa`, `lied_to_erwin`).
- **Faction standing:** Survey Corps, Garrison, Military Police, Royal Government / Historia,
  Yeagerists, Marley, Warriors, civilians. (The existing SC / Garrison / MP factions and Call to
  Arms become the Act II branch and feed these.)
- **Ideology:** three axes, moved by deeds rather than menus:
  - *Mercy ↔ Ruthlessness* (spare / kill prisoners, protect civilians)
  - *Paradis first ↔ Humanity first* (Marleyans protected or killed, Yeagerist support)
  - *Obedience ↔ Independence* (follow orders, act alone, betray)
- **Deeds log:** every consequential act with its date in the story ("Kept Reiner's secret, 850").
  It shows up in the journal's Chronicle and is what endings look at.

Examples of how deeds drive later content:

| Deed | Consequence |
|---|---|
| Protect civilians | civilian trust: shops, hideouts, informants |
| Kill prisoners | factions fear you: some doors open, others close |
| Protect Marleyans | Marley relationships improve |
| Kill Marleyans | Yeagerist support rises |
| Protect Historia | Royal faction trusts you |
| Support Floch / Armin | Yeagerists gain confidence / an alliance becomes possible |
| Betray the Scouts | Scout missions disappear |
| Cooperate with Warriors | Warrior missions unlock |
| Hide / reveal information | a later betrayal / alliance becomes possible |

Choices never say "(Yeagerist)" on them. At the end the game reads what you did and says
*you became this person*.

## 4. Stepping stones (canon beats)

Canon events are **stepping stones**: fixed points everyone reaches. Each has several
**approaches** (how you arrive, chosen by your origin, branch and relationships) and **roles**
(what you do there). You never replace Eren: Eren transforms, and you're there.

## 5. The five acts, aligned with the game

### Act I: The Walls (845–850)

**Beginnings** map onto the existing character-creation origins:

| Origin (existing) | Opening |
|---|---|
| **Shiganshina** | Childhood near Eren, Mikasa and Armin. Live through the fall of Wall Maria in an instance of 845 Shiganshina: the Colossal Titan, the breach, Carla's death, the evacuation boats, the refugee camps. Starts with personal hatred of titans. |
| **Trost** | Life inside Wall Rose. Shiganshina arrives as news and refugees at the gate. The first real titan is at Trost. |
| **Ragako** (the northern/farming origin) | A farming or military family asking "why do we sacrifice thousands to protect these walls?" Land-reclamation operation fallout, food riots. An early political view. |
| **Stohess / Mitras** (the interior origin) | Access to the interior: corruption, class divides, nobles. The first conflict is the system, not titans. |
| **Underground** (existing bonus origin) | Survival beneath Mitras, a smuggling network, a first taste of ODM gear from thieves. A route toward Levi's world and the interior's underbelly. |

**Every beginning is gentle, wherever it is.** The world's zones have fixed level bands
(Shiganshina 1–5, Trost 10–14, Ragako 14–18, Stohess 28–32, Underground City 30–36, Mitras 34–40,
the sea and Marley 65+). A level 1 character must never be dropped into a zone that outranks them:

- Openings are story scenes, not free roam. Their fights are **scaled to the player** (story
  titans and bandits are fixed at the player's level band, 1–8 in Act I), no matter which zone
  hosts the scene.
- Openings in high-level zones (Stohess, Mitras, the Underground) are **people and truth**
  scenes: politics, crime, class, escape. There's no titan fighting there; your first titans are
  at Trost with everyone else.
- Each opening ends by moving you somewhere that fits your level: the refugee camps and the
  Training Camp (6–10), then Trost (10–14). Your home origin stays open to return to when you've
  grown into it.
- Story missions never send you into a zone more than ~5 levels above you. When the story needs
  you somewhere dangerous (outside the walls, the sea, Marley), it waits until you're ready and
  the journal says so ("Recommended level 60"), instead of throwing you in.
- The outside world (the sea, Marley, Liberio at 65–85) is Act III and later only.

Then **cadet training** (the Training Camp already exists) is where every origin meets. Every
beat of it is a People or Survival mission, and it's where you meet the 104th.

**Stepping stone: Trost.** Everyone's there, in a different role: fight beside Eren, rescue civilians,
guard the supply depot, lose someone (a friend from your origin, or Marco's fate witnessed
differently), witness Eren transform, and start to learn that titans aren't what you thought.

Act I ends on: *"I thought the enemy was titans."*

### Act II: The Truth (850)

- **Branch choice**: Survey Corps / Garrison / Military Police, using the existing factions, now
  with story weight. Each branch has its own mission lines and its own access (the outside, the
  walls and the people, or the interior and the government).
- Converging beats: **Female Titan** (the expedition, Stohess) → **Annie** → **Reiner and
  Bertholdt** → **Historia and Ymir** → **The coup** → **Return to Shiganshina**.
- Personal involvement varies: trust or distrust Erwin, protect Historia, oppose the monarchy,
  believe / hate / understand Reiner, grow close to a Warrior or a Scout.
- Act II ends in **the basement** (an instance): the walls weren't the world. This is one of the
  biggest moments in the game and is built with the cutscene engine.

### Act III: The World

Different routes outside, gated by earlier choices:

- **Paradis**: stay; work with Scouts, the military, the government, the early Yeagerists.
- **Marley**: arrive as a spy, prisoner, disguised Eldian, infiltrator, Warrior candidate, or a
  genuine defector. Marley, Liberio and the port already exist on the map.
- **Warrior program**: a different relationship with Marley entirely: Liberio, candidates,
  internment zones, the military, ordinary Marleyans.

Marleyans are written as people: cruel, racist, indoctrinated, sympathetic, scared, indifferent,
dissenting. The player builds their own worldview.

**Stepping stone: Liberio.** You arrive *with Eren*, *against Eren*, *with Marley*, or as an
*independent operator* with your own objective in the chaos. This is where the game first says,
out loud, "your choices matter."

### Act IV: War

A faction war where everything so far counts: **Yeagerists, Scouts, Marley, Warriors, Paradis
government, Independent**. Your reputation is now ideological (section 3), and your
Yeagerist / non-Yeagerist alignment comes from what you did, not from a button.

**Stepping stone: Zeke + Eren + Paths.** It still happens; your relationship to it changes. Support
Eren, Zeke, Armin or Marley, or reject everyone ("nobody gets to decide the future of humanity").

### Act V: The End

Your whole history decides what's possible, not just which ending you pick:

- **A, Yeagerist:** A1 full Rumbling · A2 controlled Rumbling (military targets, deterrence) ·
  A3 the Rumbling as leverage to force negotiations.
- **B, Anti-Yeagerist (Alliance):** B1 reach Eren · B2 kill Eren · B3 separate Eren from Ymir
  through Paths.
- **C, Marley:** stop Eren, then face "what happens to Paradis now?": protect Paradis, abandon it,
  force Marley to negotiate, or betray Marley.
- **D, Independent:** build your own coalition from anyone you've earned (Armin, Mikasa, Jean,
  Connie, Reiner, Annie, Pieck, Historia, Marleyan or Paradis military, Yeagerist defectors).
  Access depends entirely on earlier relationships. This is *the player's own Attack on Titan*.

## 6. The quest journal (rebuilt)

The flat quest list becomes:

- **Your Story**: the act, the next stepping stone, and the open threads, each marked
  Survival / Truth / People, with where to go.
- **People**: every character you've met, your affinity, and your last shared memory.
- **Allegiances**: faction standings and the three ideology axes (shown vaguely, never as
  numbers that invite min-maxing).
- **Party**: what each party member is doing right now, with a button to join their scene.
- **World**: live world events and boss raids.
- **Chronicle**: your deeds and choices as a timeline, the record endings are built from.

Existing exploration quests, titan caves and camps stay, but they move to a **Field Work** tab and
feed Survival progress, so they aren't story filler.

## 7. NPCs and dialogue

- **Story actors**: canon and original characters as player-shaped NPCs with their own skins. They
  walk, look, gesture (vanilla poses; a real animation library can be added later) and fight.
- **Dialogue screen**: portrait and name, the line, and choices. Choices can be gated or coloured
  by origin, branch, role (Medic, Tank…), stats (Charisma already exists), relationships and memories.
  A party guest's suggestion shows beside the choice.
- **Voice-ready**: every line has a stable id (`act1.trost.eren.03`). If a sound
  `aot_rpg:voice/<id>` exists it plays, so voice-overs can be added later without code changes.
- **Cutscenes** (later): camera paths, letterbox, fades and scripted actors, using the same actors
  and the same script format.

## 8. Content format

Story content is **data, not code**: JSON files in `data/aot_rpg/story/` describe acts, beats,
missions (with their tags and thread), dialogue trees, conditions (flags, affinity, ideology,
origin, branch, role, level, party) and effects (flags, affinity, ideology, deeds, rewards, phase
props, spawns). Writing a new mission means writing a file; the engine validates it (including the
no-filler rule) at load and reports problems.

## 9. Build order

1. **Engine**: per-character story state, conditions and effects, phasing (actors and props),
   story actor entity, dialogue screen, party scenes, journal v2, content loader and validator,
   migration of old chapters.
2. **Act I vertical slice**: all five openings → cadet training → Trost, fully playable solo
   and in a party.
3. **Act II** (branch lines → Female Titan → Annie → Reiner/Bertholdt → Historia/Ymir → coup →
   Shiganshina → basement), plus the cutscene engine for the basement.
4. **Act III** (Paradis / Marley / Warrior routes → Liberio).
5. **Act IV** (faction war, ideology payoffs → Paths).
6. **Act V** (all ending branches), then voice-over pass.

Levels, gear, roles, homes and everything else already built stay as they are. The story adds
depth on top; it doesn't replace progression.
