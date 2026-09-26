import json, os
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'story')

def A(actor, at, **kw):
    d = {"actor": actor, "at": at}; d.update(kw); return d
def T(n, at, **kw):
    d = {"titans": n, "at": at}; d.update(kw); return d
def S(objective, goal, spawn=(), start=(), done=(), when=None):
    d = {"objective": objective, "goal": goal, "spawn": list(spawn), "start": list(start), "done": list(done)}
    if when: d["when"] = when
    return d
def M(id, title, chapter, thread, purpose, place, level, steps, complete=(), next=None, xp=0, nextIf=None):
    d = {"id": id, "title": title, "chapter": chapter, "thread": thread, "purpose": purpose, "place": place,
         "level": level, "steps": steps, "complete": list(complete), "xp": xp}
    if next: d["next"] = next
    if nextIf: d["nextIf"] = nextIf
    return d
def N(speaker, text, next=None, choices=None, effects=None, end=False):
    d = {"speaker": speaker, "text": text}
    if next: d["next"] = next
    if choices: d["choices"] = choices
    if effects: d["effects"] = effects
    if end: d["end"] = True
    return d
def C(text, next=None, effects=(), requires=None, tag=None):
    d = {"text": text, "effects": list(effects)}
    if next: d["next"] = next
    if requires: d["requires"] = requires
    if tag: d["tag"] = tag
    return d
def say(text, who=None):
    d = {"text": text}
    if who: d["who"] = who
    return {"say": d}
def aff(**kw): return {"affinity": kw}
def ide(**kw): return {"ideology": kw}
def flag(f): return {"flag": f}
def deed(t): return {"deed": t}
def fate(**kw): return {"fate": kw}
def card(t, s=""): return {"card": {"title": t, "sub": s}}
def gate(g, f=0, r=0): return {"gate": g, "rel": [f, r]}
def place(p, f=0, r=0): return {"place": p, "rel": [f, r]}
def E(edge, frac, f=0, r=0, place=None):
    d = {"edge": edge, "frac": frac, "rel": [f, r]}
    if place: d["place"] = place
    return d
def D(start, **nodes): return {"start": start, "nodes": nodes}
def talk(actor, dlg): return {"talk": actor, "dialogue": dlg}
def goto(at, radius=6, **kw):
    d = {"goto": at, "radius": radius}; d.update(kw); return d

cast = {
    "eren_child": {"name": "Eren", "skin": "eren_child"}, "mikasa_child": {"name": "Mikasa", "skin": "mikasa_child"},
    "armin_child": {"name": "Armin", "skin": "armin_child"}, "carla": {"name": "Carla Yeager", "skin": "carla"},
    "hannes": {"name": "Hannes", "skin": "hannes"},
    "eren": {"name": "Eren Yeager", "skin": "eren_cadet"}, "mikasa": {"name": "Mikasa Ackerman", "skin": "mikasa_cadet"},
    "armin": {"name": "Armin Arlert", "skin": "armin_cadet"}, "jean": {"name": "Jean Kirstein", "skin": "jean"},
    "marco": {"name": "Marco Bott", "skin": "marco"}, "connie": {"name": "Connie Springer", "skin": "connie"},
    "sasha": {"name": "Sasha Braus", "skin": "sasha"}, "reiner": {"name": "Reiner Braun", "skin": "reiner"},
    "bertholdt": {"name": "Bertholdt Hoover", "skin": "bertholdt"}, "annie": {"name": "Annie Leonhart", "skin": "annie"},
    "krista": {"name": "Krista Lenz", "skin": "krista"}, "ymir": {"name": "Ymir", "skin": "ymir"},
    "thomas": {"name": "Thomas Wagner", "skin": "thomas"}, "samuel": {"name": "Samuel", "skin": "samuel"},
    "keith": {"name": "Instructor Shadis", "skin": "keith"}, "erwin": {"name": "Commander Erwin Smith", "skin": "erwin"},
    "levi": {"name": "Captain Levi", "skin": "levi"}, "hange": {"name": "Squad Leader Hange", "skin": "hange"},
    "pixis": {"name": "Commander Pixis", "skin": "pixis"},
    "garrison_captain": {"name": "Captain Woermann", "skin": "garrison_captain"},
    "garrison_soldier": {"name": "Garrison Soldier", "skin": "garrison_soldier", "watch": True},
    "lotte": {"name": "Lotte", "skin": "refugee_child"}, "refugee": {"name": "Refugee", "skin": "refugee"},
    "baker": {"name": "Baker Ulm", "skin": "baker", "watch": True},
    "bread_crate": {"name": "Bread crate", "item": "minecraft:barrel"},
    "aldo": {"name": "Aldo", "skin": "farmer"}, "tax_collector": {"name": "MP Tax Collector", "skin": "mp_soldier"},
    "hilde": {"name": "Hilde", "skin": "civilian_f"}, "mp_officer": {"name": "MP Officer Djel", "skin": "mp_officer"},
    "priest": {"name": "Pastor Nick", "skin": "priest"}, "steward": {"name": "Steward Oswin", "skin": "noble"},
    "ferrin": {"name": "Ferrin", "skin": "smuggler"},
    "mp_guard1": {"name": "Military Police", "skin": "mp_soldier", "watch": True},
    "mp_guard2": {"name": "Military Police", "skin": "mp_soldier", "watch": True},
    "gear_pack": {"name": "ODM gear pack", "item": "minecraft:chest"},
    "stair_guard": {"name": "Stairway Guard", "skin": "mp_soldier"},
    "dimo_reeves": {"name": "Dimo Reeves", "skin": "merchant"}, "civilian_f": {"name": "Frightened woman", "skin": "civilian_f"},
    "civilian_m": {"name": "Townsman", "skin": "civilian_m"},
    "boat1": {"name": "Evacuation boat", "item": "boat"}, "boat2": {"name": "Evacuation boat", "item": "boat"},
    "boat3": {"name": "Evacuation boat", "item": "boat"},
    "refugee2": {"name": "Refugee", "skin": "civilian_m"}, "refugee3": {"name": "Refugee", "skin": "civilian_f"},
    "refugee4": {"name": "Refugee", "skin": "refugee_child"}, "refugee5": {"name": "Refugee", "skin": "farmer"},
    "garrison2": {"name": "Garrison Soldier", "skin": "garrison_soldier"},
}

missions, dialogues = [], {}

# =====================================================================================  SHIGANSHINA
SH = "shiganshina-district"; ch_sh = "Act I · Shiganshina, 845"
dialogues["sh1_armin"] = D("a",
    a=N("armin_child", "Oh. Hi, {first}. You didn't see any of that, right?", "b"),
    b=N("armin_child", "They took my book. Well, they tried. Grandpa says I'm not supposed to show it to anyone anyway.", "b2"),
    b2=N("armin_child", "It's about the outside. There's a lake of salt water out there. So big you can't see the other side.", choices=[
        C("You'll get in real trouble talking like that.", "c1", [aff(armin_child=-3), ide(paradis=5, independence=-3), flag("walls_faithful")]),
        C("Salt water? Can I see the book?", "c2", [aff(armin_child=8), ide(independence=5, paradis=-3), flag("dreams_outside")]),
        C("Which ones took it? Point them out.", "c3", [aff(armin_child=4), ide(mercy=4), flag("armin_defended")])]),
    c1=N("armin_child", "I know. Everyone says that. I just think it's weird that nobody even wants to look.", end=True),
    c2=N("armin_child", "Here, look at this page. We should go and see it one day. You, me, Eren and Mikasa.", effects=[flag("promise_outside")], end=True),
    c3=N("armin_child", "It's fine. Eren went after them. Then Mikasa went after Eren, so. They ran pretty fast.", end=True))
dialogues["sh1_eren"] = D("a",
    a=N("hannes", "Well, if it isn't the little troublemakers. Relax, relax. We're on duty. Technically.", "b"),
    b=N("eren_child", "You're drinking on duty! What happens if they come over the Wall?", "b2"),
    b2=N("hannes", "Then we'll deal with it. It's been a hundred years, kid. Nothing's getting over that thing.", "c"),
    c=N("eren_child", "{first}, you tell him. He doesn't listen to me.", choices=[
        C("Eren's got a point. You don't even look ready.", "d1", [aff(eren_child=6, hannes=-3), ide(independence=5)]),
        C("Leave him alone, Eren. Nothing's going to happen.", "d2", [aff(hannes=6, eren_child=-3), ide(mercy=3, paradis=3)]),
        C("Mr. Hannes, what would you actually do if it broke?", "d3", [aff(hannes=3), flag("asked_hannes")])]),
    d1=N("mikasa_child", "Eren. We need to get the firewood home.", end=True),
    d2=N("hannes", "Ha. Listen to your friend. Now go on, before Carla comes looking for you.", end=True),
    d3=N("hannes", "Honestly? I'd tell you to run for the boats and not stop. Now scram, all of you.", end=True))
missions.append(M("sh1", "The Last Morning", ch_sh, "people", ["character", "relationship", "lore"], SH, [1, 5], [
    S("Armin is sitting by the canal. Go see if he's all right", talk("armin_child", "sh1_armin"),
      spawn=[A("armin_child", [6, 10], face=True)]),
    S("Find Eren and Mikasa by the gate", talk("eren_child", "sh1_eren"),
      spawn=[A("eren_child", [-20, 3], face=True), A("mikasa_child", [-20, 5]), A("hannes", [-24, 0])]),
], complete=[deed("Spent the last quiet morning of 845 with Eren, Mikasa and Armin")], next="sh2", xp=120))

dialogues["sh2_carla"] = D("a",
    a=N("carla", "My legs... I can't feel my legs. Eren, Mikasa, go. Go now.", "b"),
    b=N("eren_child", "We're not leaving you! {first}, help me lift it!", choices=[
        C("(Grab the beam and pull with Eren.)", "c1", [ide(mercy=5), aff(eren_child=8, carla=5), flag("tried_carla")]),
        C("Mikasa, come on. We have to go.", "c2", [ide(independence=-3), aff(mikasa_child=4, eren_child=-4)]),
        C("I'll get help!", "c3", [flag("fetched_hannes")])]),
    c1=N(None, "It doesn't move. Not even a little. The ground shakes again, closer.", "d"),
    c2=N("mikasa_child", "...Eren. Eren, please.", "d"),
    c3=N(None, "You run into the street and nearly knock Hannes over.", "d"),
    d=N("hannes", "Carla! Hold on. Kids, stand back.", "e"),
    e=N("carla", "Hannes, don't. You can't fight it. Take them and get out of here. Please.", "f"),
    f=N("carla", "Eren. Mikasa. Live. You hear me? You have to live.", effects=[fate(carla="killed at Shiganshina")], end=True))
dialogues["sh2_boats"] = D("a",
    a=N("armin_child", "Over here! Grandpa's holding us a place. Eren, where's your mom? Where's...", "b"),
    b=N("eren_child", "I'm going to kill them. All of them. Every single one.", choices=[
        C("I'll be right there with you.", "c", [aff(eren_child=8), ide(paradis=6, mercy=-3), flag("vowed_with_eren")]),
        C("Eren... that's not going to bring her back.", "c", [aff(eren_child=-3, armin_child=4), ide(mercy=5)]),
        C("(Say nothing. Stay next to Mikasa.)", "c", [aff(mikasa_child=8)])]),
    c=N(None, "The crowd carries you through the gate. Nobody talks. Behind you, smoke is rising over Shiganshina.", end=True))
missions.append(M("sh2", "The Day the Wall Fell", ch_sh, "survival", ["war", "character", "future"], SH, [1, 5], [
    S("Head home along the main street", goto([-5, -8], 7),
      done=[{"shake": 3}, {"flash": 0.6}, {"titan_actor": {"id": "colossal", "shifter": "colossal", "at": gate("outer", -16, 0), "seconds": 30}},
            {"sound": {"id": "minecraft:entity.lightning_bolt.thunder", "volume": 2}}, {"fx": {"type": "steam", "at": gate("outer", -10, 0)}},
            say("Lightning, out of a clear sky. Then a hand, skinless and steaming, grips the top of the Wall.")]),
    S("Look toward the outer gate", {"wait": 4},
      done=[{"fx": {"type": "explosion", "at": gate("outer", 4, 0)}}, {"shake": 2}, card("845", "Shiganshina"),
            say("The gate blows apart. Rocks come down all over the district.")]),
    S("Eren is running for his house. Follow him", goto([-12, 14], 6),
      spawn=[A("carla", [-12, 14], pose="crouch"), A("eren_child", [-11, 12]), A("mikasa_child", [-13, 12])],
      done=[say("The house has come down. Carla is pinned under the main beam.")]),
    S("Help Carla", talk("carla", "sh2_carla")),
    S("Go with Hannes. Don't look back", goto([12, 6], 6),
      start=[{"titan_actor": {"id": "smiling", "shifter": "ordinary", "at": [-40, 14], "seconds": 40}},
             {"walk": {"actor": "smiling", "to": [-14, 14]}},
             say("A titan turns into your street. It's smiling."), {"follow": "eren_child"}, {"follow": "mikasa_child"}, {"follow": "hannes"}],
      done=[{"remove": "carla"}, {"remove": "smiling"}, say("Behind you, Eren is screaming for his mother. Hannes doesn't stop running.")]),
    S("There's a titan on the road to the boats. Bring it down", {"kill": "scene"},
      spawn=[T(1, [34, 0], level=1, strikes=1, spread=2)],
      start=[say("It's between us and the gate. You've got blades? Go for the back of the neck. Only the neck.", "hannes")]),
    S("Everyone is fleeing to the inner gate. Get there", goto(E("inner", 0.72), 12),
      spawn=[A("refugee", E("inner", 0.78, 0, -3)), A("refugee2", E("inner", 0.8, 0, 2)), A("refugee3", E("inner", 0.82, 0, -1)),
             A("refugee4", E("inner", 0.8, 0, 4)), A("refugee5", E("inner", 0.84, 0, 1)), A("garrison2", E("inner", 0.76, 0, 6), face=True),
             A("boat1", E("inner", 0.9, 0, 3)), A("boat2", E("inner", 0.9, 0, -3)), A("boat3", E("inner", 0.95, 0, 0))],
      start=[say("Everyone to the inner gate! The boats won't wait!", "garrison2")],
      done=[say("The crowd is packed so tight you can barely move. Somewhere in it, someone is calling your name.")]),
    S("Find Armin in the crowd", talk("armin_child", "sh2_boats"),
      spawn=[A("armin_child", E("inner", 0.8, 0, -6), face=True)]),
], complete=[card("Wall Maria has fallen", "845"), deed("Escaped Shiganshina as Wall Maria fell"), flag("lost_home")], next="sh3", xp=250))

dialogues["sh3_reclaim"] = D("a",
    a=N("armin_child", "They called Grandpa up this morning. Him and pretty much every adult in the camp.", "b"),
    b=N("armin_child", "They're calling it an operation to take Wall Maria back. But they gave them farming tools, {first}.", choices=[
        C("There isn't enough food. They're getting rid of people.", "c", [ide(independence=6, paradis=-3), aff(armin_child=5), flag("saw_reclamation_truth")]),
        C("Maybe they'll actually win some land back.", "c", [ide(paradis=5, independence=-3)]),
        C("I'm sorry, Armin.", "c", [aff(armin_child=6), ide(mercy=3)])]),
    c=N("eren_child", "We're joining the training corps. Next year, as soon as we're old enough. You're coming, right?", choices=[
        C("Yeah. I'm coming.", "d", [aff(eren_child=3, mikasa_child=3)]),
        C("I'll join. Not for the same reasons as you, though.", "d", [ide(independence=5)])]),
    d=N("mikasa_child", "Then we stay together.", end=True))
missions.append(M("sh3", "Refugees", "Act I · Wall Rose, 846", "people", ["character", "faction", "lore"], "trost-district", [1, 6], [
    S("Find Armin in the refugee camp", talk("armin_child", "sh3_reclaim"),
      spawn=[A("armin_child", E("inner", 0.7, -4, 3), face=True), A("eren_child", E("inner", 0.7, -5, 5)), A("mikasa_child", E("inner", 0.7, -5, 1))],
      start=[{"teleport": E("inner", 0.7)}, card("846", "Refugee camps, Wall Rose")]),
], complete=[deed("Watched the reclamation march out, 846"), {"toast": "Next: the Cadet Training Corps"}], next="tc1", xp=150))

# =====================================================================================  TROST origin
TR = "trost-district"; ch_tr = "Act I · Trost, 845"
dialogues["tr1_captain"] = D("a",
    a=N("garrison_captain", "You, back up. These people just got here from Shiganshina. They've got nothing left.", "b"),
    b=N("garrison_captain", "If you're going to stand there, you might as well help.", choices=[
        C("I'll help carry the injured.", "c1", [ide(mercy=6), {"standing": {"garrison": 3}}, aff(garrison_captain=3)]),
        C("I can keep people back from the dock.", "c2", [ide(independence=-5), {"standing": {"garrison": 5}}]),
        C("What happened down there?", "c3", [flag("asked_how_wall_fell"), aff(garrison_captain=-2)])]),
    c1=N("garrison_captain", "Good. Start with the girl by the crates. She came off alone.", end=True),
    c2=N("garrison_captain", "Fine. And keep an eye on that girl by the crates. She hasn't moved in an hour.", end=True),
    c3=N("garrison_captain", "The outer gate's gone. That's all I know. Now either help or move.", end=True))
dialogues["tr1_lotte"] = D("a",
    a=N("lotte", "Are there titans here too?", choices=[
        C("No. The Wall here is fine. You're safe.", "b", [ide(mercy=3, paradis=3), aff(lotte=6), flag("lotte_comforted")]),
        C("I don't know. But I'll keep an eye out for you.", "b", [aff(lotte=10), ide(mercy=5), flag("lotte_protected")]),
        C("Who did you come with?", "c", [aff(lotte=2)])]),
    b=N("lotte", "I'm Lotte. Okay. I'll remember you.", end=True),
    c=N("lotte", "My mom put me on the boat. She said she'd get on the next one.", "c2"),
    c2=N(None, "The captain said the last boat came in an hour ago.", "b", effects=[flag("lotte_orphan")]))
missions.append(M("tr1", "News from the South", ch_tr, "people", ["character", "relationship", "lore"], TR, [1, 6], [
    S("Refugees from Shiganshina are arriving at the inner gate. Go and see", goto(E("inner", 0.75), 10), done=[card("845", "Trost District")],
      spawn=[A("boat1", E("inner", 0.8, 0, 4)), A("boat2", E("inner", 0.8, 0, -4))]),
    S("Talk to the Garrison captain on the dock", talk("garrison_captain", "tr1_captain"),
      spawn=[A("garrison_captain", E("inner", 0.75, -2, 4), face=True), A("refugee", E("inner", 0.75, -3, -3)), A("lotte", E("inner", 0.75, -1, -6))]),
    S("Check on the girl sitting by the crates", talk("lotte", "tr1_lotte")),
], complete=[deed("Met the refugees from Shiganshina at Trost's gate")], next="tr2", xp=120))

dialogues["tr2_baker"] = D("a",
    a=N("baker", "Twenty for a loaf. Don't look at me like that, flour's gone up four times since the boats came in.", "b"),
    b=N("lotte", "It's okay. I'm not that hungry.", choices=[
        C("I'll buy one. [10 Marks, he'll take it]", "c1", [{"pay": 10}, ide(mercy=5), aff(lotte=8), deed("Bought bread for a hungry refugee girl")], requires={"marks": 10}),
        C("(Quietly) Wait here. I'll get us some.", "c2", [flag("will_steal"), ide(independence=5)]),
        C("They're handing out rations at the square later.", "c3", [ide(independence=-5, paradis=2), aff(lotte=-5), flag("refused_lotte")])]),
    c1=N("baker", "Ten, then. Don't tell anyone.", end=True),
    c2=N("lotte", "Don't get caught.", end=True),
    c3=N("lotte", "Okay.", end=True))
missions.append(M("tr2", "Bread and Order", ch_tr, "people", ["character", "future", "relationship"], TR, [1, 6], [
    S("Take Lotte to the market to find her something to eat", talk("baker", "tr2_baker"),
      spawn=[A("baker", [4, 6], face=True), A("bread_crate", [6, 9]), A("lotte", [8, 3], follow=True), A("garrison_soldier", [0, -4], patrol=True)]),
    S("Take a loaf from the crate without being seen (crouch, stay out of their line of sight)", {"take": "bread_crate", "unseen": True},
      done=[flag("stole_bread"), ide(mercy=4, independence=4), aff(lotte=8), deed("Stole bread for a hungry refugee girl"), say("...Thank you.", "lotte")],
      when={"flag": "will_steal"}),
], complete=[{"toast": "Next: the Cadet Training Corps"}], next="tc1", xp=150))

# =====================================================================================  RAGAKO origin
RG = "ragako-village"; ch_rg = "Act I · Ragako, 846"
dialogues["rg1_tax"] = D("a",
    a=N("aldo", "They want a third of the harvest. For the refugees, apparently.", "b"),
    b=N("tax_collector", "Crown's orders. Everyone inside Wall Rose gives. Unless you'd like to explain to someone in Mitras why you didn't.", choices=[
        C("Take it. People are starving.", "c1", [ide(mercy=5, paradis=3), {"standing": {"mp": 2}}]),
        C("A third? We won't make it to spring.", "c2", [ide(independence=6), aff(aldo=6), {"standing": {"mp": -4}}, flag("defied_tax")]),
        C("Do the granaries in Mitras give a third too?", "c3", [flag("shamed_mp"), {"standing": {"mp": -2}}, aff(aldo=8)],
          requires={"charisma": 3}, tag="Charisma")]),
    c1=N("tax_collector", "Good.", end=True),
    c2=N("tax_collector", "Watch your mouth. People get remembered for less.", end=True),
    c3=N("tax_collector", "...Fine. A quarter. And I'll remember you.", effects=[flag("tax_reduced")], end=True))
missions.append(M("rg1", "The Grain Tax", ch_rg, "people", ["faction", "character"], RG, [1, 6], [
    S("Your father is arguing with a Military Police officer", talk("aldo", "rg1_tax"),
      spawn=[A("aldo", [4, 4], face=True), A("tax_collector", [7, 2])], start=[card("846", "Ragako Village")]),
], next="rg2", xp=120))
dialogues["rg2_aldo"] = D("a",
    a=N("aldo", "My name's on the list. They're sending us to take back Wall Maria.", "b"),
    b=N("aldo", "Two hundred and fifty thousand people. Farmers, mostly. They gave us pitchforks.", choices=[
        C("Don't go. I'll hide you in the barn.", "c1", [ide(independence=8), aff(aldo=6), flag("hid_father")]),
        C("Why do they get to decide who dies for the Walls?", "c2", [ide(independence=5, paradis=-5), flag("questions_walls")]),
        C("Just come back. Okay?", "c3", [aff(aldo=8)])]),
    c1=N("aldo", "And then they burn the farm and take you instead. No.", "d"),
    c2=N("aldo", "Somebody always does. Usually it's people like us.", "d"),
    c3=N("aldo", "I'll try.", "d"),
    d=N("aldo", "If you join the army, don't go hide in the interior. Go where the decisions get made. Make better ones than this.",
        effects=[fate(aldo="lost in the Reclamation"), deed("Watched your father leave for the Reclamation, 846")], end=True))
missions.append(M("rg2", "The Reclamation", ch_rg, "truth", ["war", "lore", "character", "future"], RG, [1, 6], [
    S("A Garrison rider is reading names in the square. Find your father", talk("aldo", "rg2_aldo"), spawn=[A("aldo", [2, 6], face=True)],
      start=[say("A Garrison rider comes through the village reading from a list.")]),
    S("Walk with him to the edge of the village", goto([-24, 0], 6), start=[{"follow": "aldo"}],
      done=[{"remove": "aldo"}, say("Around a hundred people came back from the Reclamation. He wasn't one of them.")]),
], complete=[{"toast": "Next: the Cadet Training Corps"}], next="tc1", xp=200))

# =====================================================================================  INTERIOR origin (Stohess / Mitras)
dialogues["in1_extort"] = D("a",
    a=N("hilde", "I paid the permit. I paid it last month, I have the paper here.", "b"),
    b=N("mp_officer", "Price went up. Lot of refugees to feed these days. Lot of people to keep order for.", "c"),
    c=N("mp_officer", "Problem?", choices=[
        C("Yeah. That's just stealing.", "d1", [ide(independence=6, mercy=4), {"standing": {"mp": -6}}, aff(hilde=10, mp_officer=-10), flag("defied_mp")]),
        C("Here. That should cover her. [20 Marks]", "d2", [{"pay": 20}, ide(mercy=5), aff(hilde=6), flag("paid_mp")], requires={"marks": 20}),
        C("No, officer.", "d3", [ide(independence=-4), aff(hilde=-4), flag("ignored_corruption")]),
        C("You might want to ask who my father is first.", "d4", [{"standing": {"mp": 2}}, aff(hilde=3), flag("noble_leverage")], requires={"origin": "MITRAS", "hidden": True}, tag="Noble")]),
    d1=N("mp_officer", "Careful. People who talk like that tend to disappear around here.", end=True),
    d2=N("mp_officer", "Well. That's very generous of you.", end=True),
    d3=N("mp_officer", "Didn't think so.", end=True),
    d4=N("mp_officer", "...No need for that. We're done here.", end=True))
dialogues["in1_nick"] = D("a",
    a=N("priest", "The Walls were given to us by God. Those who doubt them doubt the only thing standing between us and the end.", choices=[
        C("What are they actually made of?", "b1", [flag("asked_nick_walls"), ide(independence=3)]),
        C("Thank you, Pastor.", "b2", [ide(paradis=5, independence=-3), aff(priest=6), flag("church_favor")]),
        C("One of them just fell, though.", "b3", [ide(independence=5), aff(priest=-6)])]),
    b1=N("priest", "...That isn't something you need to know. Some things are better left alone.", effects=[flag("nick_hides_something")], end=True),
    b2=N("priest", "God keep you.", end=True),
    b3=N("priest", "Maria fell because its people lost their faith. Don't lose yours.", end=True))
dialogues["in1_steward"] = D("a",
    a=N("steward", "My employer has an eye for talent. Finish in the top ten at the training corps and there's a Military Police post for you. Here. Inside.", choices=[
        C("I'll think about it. Seriously.", "b", [ide(paradis=3), {"standing": {"mp": 5}}, flag("mp_patron")]),
        C("What if I want to go outside the Walls?", "b", [ide(independence=5), flag("wants_outside")])]),
    b=N("steward", "Most people who go outside don't come back to complain about it. Good day.", end=True))
for pid, org, mid in (("stohess-district", "Stohess", "in1s"), ("mitras", "Mitras", "in1m")):
    missions.append(M(mid, "The Pleasant Side of the Wall", "Act I · " + org + ", 845", "people", ["faction", "character", "lore"], pid, [1, 6], [
        S("Something's going on at a bakery stall in the market", talk("mp_officer", "in1_extort"),
          spawn=[A("hilde", [5, 6]), A("mp_officer", [5, 3], face=True)], start=[card("845", org)]),
        S("A pastor of the Church of the Walls is preaching nearby", talk("priest", "in1_nick"), spawn=[A("priest", [-10, 10], face=True)]),
        S("A well-dressed man is waving you over", talk("steward", "in1_steward"), spawn=[A("steward", [-4, -8], face=True)]),
    ], complete=[deed("Saw how peace is kept in the interior"), {"toast": "Next: the Cadet Training Corps"}], next="tc1", xp=150))

# =====================================================================================  UNDERGROUND origin
UG = "underground-city"; ch_ug = "Act I · The Underground, 845"
dialogues["un1_ferrin"] = D("a",
    a=N("ferrin", "You want out of here, you need gear. Real ODM gear. The MP keep a set at the post by the market.", "b"),
    b=N("ferrin", "Get in, grab it, get out. Two guards. Don't let them see you, and keep your blades put away.", choices=[
        C("What do you get out of it?", "c1", [ide(independence=3), aff(ferrin=3)]),
        C("Robbing the MP? I'm in.", "c2", [ide(independence=6), flag("underground_thief")]),
        C("I'd rather find another way up.", "c3", [ide(mercy=3), aff(ferrin=-3), flag("reluctant_thief")])]),
    c1=N("ferrin", "A favor, someday. Go on.", end=True),
    c2=N("ferrin", "That's what I like to hear.", end=True),
    c3=N("ferrin", "Sure. Pay the stair toll then. It's only about three months of wages.", end=True))
dialogues["un2_toll"] = D("a",
    a=N("stair_guard", "Surface access is three hundred. You got it or not?", choices=[
        C("Here. [300 Marks]", "b1", [{"pay": 300}, ide(independence=-3)], requires={"marks": 300}),
        C("I report to the training corps tomorrow. Want to explain to Shadis why I'm late?", "b2", [flag("talked_past")], requires={"charisma": 3}, tag="Charisma"),
        C("(Fire your anchors and fly straight past him.)", "b3", [ide(independence=5), {"standing": {"mp": -3}}, flag("flew_out")], requires={"flag": "has_old_odm"}),
        C("(Walk off. Come back when he's not looking.)", "b4", [flag("sneak_stair")])]),
    b1=N("stair_guard", "Enjoy the sun.", end=True),
    b2=N("stair_guard", "...Just go.", end=True),
    b3=N("stair_guard", "Hey! Get back here!", end=True),
    b4=N("stair_guard", "Didn't think so.", end=True))
missions.append(M("un1", "Beneath Mitras", ch_ug, "survival", ["character", "relationship", "future"], UG, [1, 6], [
    S("Ferrin the smuggler wants to see you", talk("ferrin", "un1_ferrin"), spawn=[A("ferrin", [3, 3], face=True)], start=[card("845", "The Underground")]),
    S("Take the gear from the MP post without being seen (crouch, stay out of their line of sight)", {"take": "gear_pack", "unseen": True},
      spawn=[A("mp_guard1", [18, 6], patrol=True), A("mp_guard2", [22, -2], patrol=True), A("gear_pack", [20, 2])],
      done=[flag("has_old_odm"), deed("Stole ODM gear from the Military Police"), say("Told you it'd be easy.", "ferrin")]),
    S("Head for the stairway to the surface", talk("stair_guard", "un2_toll"), spawn=[A("stair_guard", [10, 2], face=True)]),
    S("Slip past the guard while he's not looking", goto([-20, 0], 4, unseen=True), when={"flag": "sneak_stair"}),
], complete=[deed("Saw the sky for the first time in years"), {"toast": "Next: the Cadet Training Corps"}], next="tc1", xp=180))

# =====================================================================================  TRAINING (all origins meet)
TC = "cadet-training-camp"; ch_tc = "Act I · The 104th Cadet Corps, 847"
dialogues["tc1_keith"] = D("a",
    a=N("keith", "You! Who are you? Where are you from? What are you doing here?", choices=[
        C("{name}, sir! Shiganshina! I'm here to kill titans!", "b", [ide(paradis=4), flag("motive_revenge")], requires={"origin": "SHIGANSHINA", "hidden": True}),
        C("{name}, sir! Trost! To protect the people inside the Walls!", "b", [ide(mercy=3), flag("motive_protect")], requires={"origin": "TROST", "hidden": True}),
        C("{name}, sir! Ragako! So no one else gets sent to die for nothing!", "b", [ide(independence=6), aff(keith=-2), flag("motive_change")], requires={"origin": "RAGAKO", "hidden": True}),
        C("{name}, sir! {origin}! To join the Military Police!", "b", [{"standing": {"mp": 3}}, flag("motive_mp")], requires={"origin": ["STOHESS", "MITRAS"], "hidden": True}),
        C("{name}, sir! The Underground! So I never have to go back down there!", "b", [ide(independence=4), flag("motive_sky")], requires={"origin": "UNDERGROUND", "hidden": True}),
        C("{name}, sir! I want to know what's out there!", "b", [ide(independence=3, paradis=-3), flag("motive_truth")])]),
    b=N("keith", "Is that so. We'll see if you last the week.", "c"),
    c=N(None, "A few places down the line, a girl is eating a potato. In the middle of the ceremony.", "d"),
    d=N("keith", "...You. What exactly do you think you're doing?", "e"),
    e=N("sasha", "It was hot, sir, and it looked so good, and I... do you want half?", end=True))
dialogues["tc1_sasha"] = D("a",
    a=N("sasha", "Hey, you. You didn't laugh when he made me run. Here, I saved you the last bit. I'm Sasha.", choices=[
        C("You keep it. You ran until dark.", "b", [aff(sasha=8)]),
        C("Thanks.", "b", [aff(sasha=4)])]),
    b=N("sasha", "Okay. We're friends now. That's how it works.", end=True))
missions.append(M("tc1", "Who Are You?", ch_tc, "people", ["character", "relationship"], TC, [2, 8], [
    S("Report to the Cadet Training Camp", goto([0, 0], 14), done=[card("847", "The 104th Training Corps")]),
    S("Fall in for Instructor Shadis's inspection", talk("keith", "tc1_keith"),
      spawn=[A("keith", [4, 0], face=True), A("eren", [8, -4]), A("mikasa", [8, -3]), A("armin", [8, -2]), A("jean", [8, 1]), A("marco", [8, 2]),
             A("sasha", [10, 5]), A("connie", [8, 4]), A("annie", [8, -6]), A("reiner", [9, -8]), A("bertholdt", [9, -9]),
             A("krista", [10, -1]), A("ymir", [10, 0])]),
    S("Find Sasha after dark", talk("sasha", "tc1_sasha")),
], next="tc2", xp=150))

dialogues["tc2_eren"] = D("a",
    a=N("eren", "I can't stay up on the rig. Everyone else can do it. If I fail again tomorrow they'll send me back to the fields.", choices=[
        C("Let me see your belt. Hang on, the buckle's cracked.", "b1", [flag("noticed_belt"), aff(eren=10), deed("Found the broken belt that almost got Eren sent home")]),
        C("Keep your weight forward. Try again.", "b2", [aff(eren=4)]),
        C("Maybe this isn't for you.", "b3", [aff(eren=-10, jean=4), flag("doubted_eren")])]),
    b1=N("eren", "Wait. It's broken? It was broken this whole time?", end=True),
    b2=N("eren", "Yeah. Okay. One more time.", end=True),
    b3=N("eren", "Say that to me again once I'm in the Survey Corps.", end=True))
dialogues["tc2_annie"] = D("a",
    a=N("annie", "They don't grade hand-to-hand, so nobody bothers. What do you want?", choices=[
        C("Show me what you're doing.", "b1", [aff(annie=8), flag("trained_with_annie")]),
        C("So why do you bother?", "b2", [aff(annie=3), flag("asked_annie_why")])]),
    b1=N("annie", "Feet wider. Turn your hip. Your arm's just the end of it. ...Again.", effects=[{"xp": 60}], end=True),
    b2=N("annie", "My dad taught me. He said nobody out there is on your side, so you'd better be able to handle yourself.",
         effects=[flag("annie_whole_world")], end=True))
dialogues["tc2_reiner"] = D("a",
    a=N("reiner", "Hey. You did well on the rig today. Where are you from, anyway?", "b"),
    b=N("bertholdt", "We're from a village up in the mountains. In Wall Maria. It's... not there anymore.", choices=[
        C("Same as a lot of people here. I'm sorry.", "c", [ide(mercy=3), aff(reiner=6, bertholdt=6)]),
        C("Which village? I didn't know there were any up there.", "c2", [flag("asked_reiner_village"), aff(bertholdt=-3)])]),
    c=N("reiner", "We're soldiers now. That's what matters.", end=True),
    c2=N("reiner", "Small place. You wouldn't have heard of it.", effects=[flag("reiner_evasive")], end=True))
missions.append(M("tc2", "Balance", ch_tc, "survival", ["character", "relationship", "war"], TC, [2, 8], [
    S("Put on your ODM harness for the aptitude test (open your inventory with E and wear it)", {"wear": "odm"}),
    S("Eren is having trouble on the balance rig", talk("eren", "tc2_eren"), spawn=[A("eren", [6, 8], face=True), A("mikasa", [7, 10]), A("armin", [5, 10])]),
    S("Annie is training on her own by the fence", talk("annie", "tc2_annie"), spawn=[A("annie", [-6, 8], face=True)]),
    S("Reiner and Bertholdt are by the barracks", talk("reiner", "tc2_reiner"), spawn=[A("reiner", [-2, -10], face=True), A("bertholdt", [-1, -12])]),
], next="tc3", xp=180))

dialogues["tc3_jean"] = D("a",
    a=N("jean", "Not bad. Top ten get the Military Police, you know. Inside Wall Sina. Real beds. No titans.", choices=[
        C("You can have it. I'm going outside.", "b", [ide(independence=4), aff(jean=-2, eren=3)]),
        C("Honestly, that doesn't sound bad.", "b", [ide(paradis=3), aff(jean=6)]),
        C("Isn't it weird that the best soldiers get sent furthest from the titans?", "b", [aff(jean=2), ide(independence=3), flag("backwards_mp")])]),
    b=N("marco", "I want to serve the King. I know how that sounds. I mean it, though.", choices=[
        C("You'd be good at it.", "c", [aff(marco=6)]),
        C("Just make sure they deserve you.", "c", [aff(marco=4), flag("warned_marco")])]),
    c=N("jean", "He's too honest. It's going to get him in trouble.", end=True))
missions.append(M("tc3", "The Training Forest", ch_tc, "survival", ["war", "character", "relationship"], TC, [3, 8], [
    S("Head out to the training forest", goto([-30, 20], 10)),
    S("Cut the napes of the training titans", {"kill": "scene"}, spawn=[T(3, [-44, 26], strikes=1, spread=10)],
      start=[say("Most kills gets the top score. Try to keep up.", "jean")]),
    S("Jean and Marco are catching their breath", talk("jean", "tc3_jean"), spawn=[A("jean", [-32, 20], face=True), A("marco", [-33, 22])]),
], next="tc4", xp=250))

dialogues["tc4_grad"] = D("a",
    a=N("keith", "Top ten. Mikasa Ackerman. Reiner Braun. Bertholdt Hoover. Annie Leonhart. Eren Yeager. Jean Kirstein. Marco Bott. Connie Springer. Sasha Braus. Krista Lenz.", "b"),
    b=N("keith", "{name}. You missed the ten. Your file says...", "c"),
    c=N("keith", "'Doesn't quit.' Out there that counts for more than a ranking. You'll choose your branch after your first posting. Dismissed.", choices=[
        C("(Salute.)", None, [ide(independence=-2), aff(keith=4)]),
        C("Sir, why did you leave the Survey Corps?", "d", [flag("asked_shadis"), ide(independence=2)])]),
    d=N("keith", "Because I wasn't special, cadet. Most of us aren't. Now get out of my sight.", end=True))
missions.append(M("tc4", "Graduation", "Act I · Graduation, 850", "people", ["character", "future"], TC, [3, 10], [
    S("It's graduation day. Fall in", talk("keith", "tc4_grad"),
      spawn=[A("keith", [4, 0], face=True), A("eren", [8, -4]), A("mikasa", [8, -3]), A("armin", [8, -2]), A("jean", [8, 1]), A("marco", [8, 2]),
             A("sasha", [9, 4]), A("connie", [8, 4]), A("annie", [8, -6]), A("reiner", [9, -8]), A("bertholdt", [9, -9]), A("krista", [10, -1])],
      start=[card("850", "Graduation")]),
], complete=[deed("Graduated from the 104th Cadet Corps"), {"toast": "Your first posting: Trost District"}], next="tb1", xp=300))

# =====================================================================================  TROST (stepping stone)
ch_tb = "Act I · The Battle of Trost, 850"
dialogues["tb1_eren"] = D("a",
    a=N("eren", "Last day as cadets. Tomorrow I sign up for the Survey Corps. I've been waiting five years for this.", "b"),
    b=N("sasha", "I took some meat from the officers' storeroom. Real meat. We can split it tonight.", choices=[
        C("Sasha, you're going to get us all thrown in a cell.", "c", [aff(sasha=3)]),
        C("I want a big piece.", "c", [aff(sasha=6)])]),
    c=N(None, "Then the light changes. Lightning, out of a clear sky.", end=True,
        effects=[{"shake": 3}, {"flash": 1}, {"titan_actor": {"id": "colossal", "shifter": "colossal", "at": gate("outer", -14, 0), "seconds": 30}},
                 {"sound": {"id": "minecraft:entity.lightning_bolt.thunder", "volume": 2}}, card("The Battle of Trost", "850")]))
missions.append(M("tb1", "The Colossal Returns", ch_tb, "survival", ["war", "character"], TR, [5, 12], [
    S("Report to your squad near Trost's outer gate", goto(E("outer", 0.55), 12),
      spawn=[A("eren", E("outer", 0.55, 0, 2), face=True), A("sasha", E("outer", 0.55, 0, -2)), A("connie", E("outer", 0.55, -1, -3)),
             A("samuel", E("outer", 0.55, -1, 3)), A("thomas", E("outer", 0.55, -2, 1))]),
    S("Talk to Eren", talk("eren", "tb1_eren")),
    S("Brace yourself", {"wait": 5}, start=[say("That's him. That's the one from five years ago.", "eren"), {"fx": {"type": "steam", "at": gate("outer", -8, 0)}}],
      done=[{"fx": {"type": "explosion", "at": gate("outer", 2, 0)}}, {"shake": 2}, say("The outer gate caves in. Rocks come down across the whole district.")]),
    S("Titans are coming through the gate. Stop them", {"kill": "scene"}, spawn=[T(3, E("outer", 0.85), spread=10)],
      start=[{"remove": "colossal"}]),
    S("A titan has Thomas! Kill it before it's too late", {"kill": "scene", "timeout": 30,
        "success": [fate(thomas="saved at Trost"), aff(thomas=15), deed("Saved Thomas Wagner at Trost"), say("You came back for me...", "thomas")],
        "fail": [fate(thomas="eaten at Trost"), say("THOMAS!", "eren"), flag("failed_thomas")]},
      spawn=[T(1, E("outer", 0.7, 0, 12), spread=2)], start=[say("It's got Thomas!", "connie")]),
], next="tb2", xp=250))

dialogues["tb2_dimo"] = D("a",
    a=N("dimo_reeves", "Keep pushing! Do you know what's in this cart? It's worth more than this whole street!", "b"),
    b=N("civilian_f", "Please, we can't get past! They're coming!", choices=[
        C("Move the cart. Now.", "c1", [ide(independence=5), {"standing": {"garrison": 2}}, aff(dimo_reeves=-10), flag("threatened_reeves")]),
        C("None of that's worth anything if you get eaten, Mr. Reeves.", "c2", [aff(dimo_reeves=5), flag("reeves_persuaded")],
          requires={"charisma": 3}, tag="Charisma"),
        C("Everyone, push with me!", "c3", [ide(mercy=5, independence=-2), flag("pushed_cart")])]),
    c1=N("dimo_reeves", "Fine! Fine. Pull it back!", end=True),
    c2=N("dimo_reeves", "...Pull it back. Pull it back!", end=True),
    c3=N(None, "Twenty people throw their weight against it, and the cart finally scrapes through the gate.", end=True))
missions.append(M("tb2", "Evacuation", ch_tb, "people", ["war", "character", "faction"], TR, [5, 12], [
    S("People are trapped at the inner gate", talk("dimo_reeves", "tb2_dimo"),
      spawn=[A("dimo_reeves", E("inner", 0.75), face=True), A("civilian_f", E("inner", 0.73, 0, 2)), A("civilian_m", E("inner", 0.73, 0, -2))]),
    S("Hold them off while everyone gets through", {"kill": "scene"}, spawn=[T(2, E("inner", 0.35), spread=8)],
      start=[{"walk": {"actor": "civilian_f", "to": E("inner", 0.95)}}, {"walk": {"actor": "civilian_m", "to": E("inner", 0.95, 0, 2)}}]),
], complete=[ide(mercy=3), {"standing": {"civilians": 5}}, deed("Held the inner gate while Trost was evacuated")], next="tb3", xp=250))

dialogues["tb3_armin"] = D("a",
    a=N("armin", "Our whole squad's gone. Eren... Eren got eaten. He pushed me out of its mouth.", "b"),
    b=N("armin", "And there's a titan out there killing other titans. It's not attacking us at all.", choices=[
        C("Then we use it to get to the supplies.", "c", [ide(paradis=5, independence=3), flag("use_rogue")]),
        C("It's a titan, Armin. It's not on our side.", "c", [ide(paradis=2, mercy=-3)]),
        C("Titans don't do that. Something's off.", "c", [flag("doubt_titans"), ide(independence=3)])]),
    c=N("armin", "Mikasa's already out there with it. We have to move.", end=True))
missions.append(M("tb3", "The Supply Depot", ch_tb, "survival", ["war", "relationship"], TR, [5, 12], [
    S("You're almost out of gas. Get to the supply building", goto([8, -18], 8), start=[say("They're all over the supply building. We're out of gas and they're just sitting in there!", "jean")]),
    S("Clear the titans out of the supply building", {"kill": "scene"}, spawn=[T(3, [8, -30], spread=6)]),
    S("Find Armin", talk("armin", "tb3_armin"), spawn=[A("armin", [8, -14], face=True), A("jean", [10, -14])]),
], next="tb4", xp=300))

dialogues["tb4_mikasa"] = D("a",
    a=N("mikasa", "He came out of its neck. Eren. He's alive. They're going to call him a monster.", choices=[
        C("Then they'll have to get through me first.", "b", [aff(mikasa=10, eren=8), ide(paradis=4), flag("defended_eren")]),
        C("Mikasa... can you blame them?", "b", [aff(mikasa=-10), ide(independence=-3), flag("feared_eren")]),
        C("If a person can turn into one... what are they?", "b", [flag("titans_are_human_q"), ide(independence=4, paradis=-2)])]),
    b=N("mikasa", "Stay close to me.", effects=[deed("Saw Eren Yeager come out of a titan at Trost")], end=True))
missions.append(M("tb4", "The Rogue Titan", ch_tb, "truth", ["lore", "war", "character", "future"], TR, [5, 12], [
    S("Find the titan that's fighting the others", goto([-10, 10], 14),
      done=[{"titan_actor": {"id": "rogue", "shifter": "attack", "at": [-18, 12], "seconds": 25}}, {"shake": 2},
            {"sound": {"id": "minecraft:entity.ravager.roar", "volume": 2, "pitch": 0.6}}, say("It roars and tears into another titan with its bare hands.")]),
    S("Watch", {"wait": 6}, start=[{"flash": 1.5}], done=[say("Steam pours off its neck. There's someone inside it.")]),
    S("Mikasa is up on the roof", talk("mikasa", "tb4_mikasa"), spawn=[A("mikasa", [-12, 6], face=True)]),
    S("Marco hasn't come back. Check the rooftops near the supply building, quickly", goto([18, -25], 6, timeout=60,
        success=[fate(marco="alive"), aff(marco=15), {"spawn": {"actor": "marco", "at": [18, -24], "face": True}},
                 say("I'm okay. Reiner, Annie and Bertholdt were just here. They were acting really strange.", "marco"),
                 flag("marco_saw_warriors"), deed("Found Marco alive, and three friends acting strangely")],
        fail=[fate(marco="died at Trost"), say("Marco is lying in the street. Half of him. Nobody saw what happened."), flag("marco_dead"),
              deed("Found Marco's body. Nobody saw what happened")])),
], next="tb5", xp=350))

dialogues["tb5_erwin"] = D("a",
    a=N("erwin", "Trost has been sealed. A titan carried the boulder into the gate, and that titan was one of our own cadets.", "b"),
    b=N("erwin", "I won't pretend the Survey Corps is safe. Most of you would die within the year. But what we find out there might be the truth about this world.", "c"),
    c=N("levi", "Make your choice. Don't whine about it later.", choices=[
        C("The Survey Corps.", "d1", [{"faction": "SURVEY_CORPS"}, {"standing": {"scouts": 10}}, ide(independence=3), flag("branch_survey")]),
        C("The Garrison. Somebody has to keep the Walls standing.", "d2", [{"faction": "GARRISON"}, {"standing": {"garrison": 10}}, flag("branch_garrison")]),
        C("The Military Police.", "d3", [{"faction": "MILITARY_POLICE"}, {"standing": {"mp": 10}}, flag("branch_mp")])]),
    d1=N("erwin", "Then give us your heart.", end=True),
    d2=N("erwin", "The Walls will be glad of you.", end=True),
    d3=N("erwin", "The interior could use more honest soldiers. More than you'd think.", end=True))
missions.append(M("tb5", "I Thought the Enemy Was Titans", "Act I · Aftermath, 850", "truth", ["future", "faction", "lore"], TR, [5, 14], [
    S("The Survey Corps is recruiting in the square", talk("erwin", "tb5_erwin"),
      spawn=[A("erwin", [-6, 0], face=True), A("levi", [-6, 2]), A("hange", [-6, -2])], start=[card("Aftermath", "Trost, 850")]),
], complete=[card("Act I: The Walls", "I thought the enemy was titans."), deed("Chose a branch after Trost"), {"chapter": 10}], xp=500))

def dump(name, obj):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)

dump("cast.json", {"actors": cast})
dump("act1.json", {"missions": missions, "dialogues": dialogues})
for fn in ("act1.json", "cast.json"):
    txt = open(os.path.join(OUT, fn), encoding="utf-8").read()
    assert "\u2014" not in txt and "—" not in txt and "–" not in txt, fn + " has a dash"
dump("index.json", {"files": ["cast.json", "act1.json"],
                    "starts": {"SHIGANSHINA": "sh1", "TROST": "tr1", "RAGAKO": "rg1", "STOHESS": "in1s", "MITRAS": "in1m", "UNDERGROUND": "un1", "default": "tr1"}})

# Validation: dialogues referenced exist, nodes referenced exist, actors exist.
ids = {m["id"] for m in missions}
for m in missions:
    for s in m["steps"]:
        g = s["goal"]
        if "dialogue" in g: assert g["dialogue"] in dialogues, g
        for a in s["spawn"]:
            if "actor" in a: assert a["actor"] in cast, a
        for k in ("talk", "take"):
            if k in g: assert g[k] in cast, g
    if m.get("next"): assert m["next"] in ids, m["next"]
for did, d in dialogues.items():
    for nid, n in d["nodes"].items():
        if n.get("speaker"): assert n["speaker"] in cast, (did, nid)
        refs = [n.get("next")] + [c.get("next") for c in n.get("choices", [])]
        for r in refs:
            if r: assert r in d["nodes"], (did, nid, r)
print(len(missions), "missions,", len(dialogues), "dialogues,", len(cast), "actors")
