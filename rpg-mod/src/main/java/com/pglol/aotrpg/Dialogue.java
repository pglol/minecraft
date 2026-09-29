package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Talking to the townspeople: a proper conversation, not a line over their head. Residents talk
 * about themselves (their work, their temper, their family by name), their town, the time of day,
 * and what they've heard. Strangers passing through say less.
 */
public final class Dialogue {
    private final Random rng = new Random();
    /** Who each player is talking to. */
    private final Map<UUID, Integer> talking = new HashMap<>();

    private static final String HOW = "How are things?", FAMILY = "Tell me about your family.", WORK = "What do you do?",
        NEWS = "Heard anything lately?", TOWN = "What's this place like?", BYE = "Goodbye.";

    public boolean talk(ServerPlayerEntity p, Entity e) {
        if (!(e instanceof VillagerEntity v) || !Townsfolk.folk(e) || !ServerPlayNetworking.canSend(p, Net.Talk.ID)) return false;
        // A real trader keeps their trades.
        var prof = v.getVillagerData().getProfession();
        if (prof != net.minecraft.village.VillagerProfession.NONE && prof != net.minecraft.village.VillagerProfession.NITWIT && !v.getOffers().isEmpty()) return false;
        talking.put(p.getUuid(), e.getId());
        Residents.Member m = AotRpg.RESIDENTS.member(e);
        Residents.Family f = AotRpg.RESIDENTS.familyOf(e);
        if (v.isSleeping()) {
            send(p, v, m, f, pick(ASLEEP), List.of("Let them sleep."));
            return true;
        }
        send(p, v, m, f, greeting(p, v, m), options(v, m));
        return true;
    }

    public void choose(ServerPlayerEntity p, int entityId, String option) {
        Integer id = talking.get(p.getUuid());
        if (id == null || id != entityId) return;
        Entity e = p.getServerWorld().getEntityById(entityId);
        if (!(e instanceof VillagerEntity v) || e.squaredDistanceTo(p) > 8 * 8) {
            end(p);
            return;
        }
        Residents.Member m = AotRpg.RESIDENTS.member(e);
        Residents.Family f = AotRpg.RESIDENTS.familyOf(e);
        String reply = switch (option) {
            case HOW -> how(m, v);
            case FAMILY -> family(m, f, v);
            case WORK -> work(m, v);
            case NEWS -> news(p, m);
            case TOWN -> town(p, f);
            default -> null;
        };
        if (reply == null) {
            send(p, v, m, f, farewell(m, v), List.of());
            talking.remove(p.getUuid());
            return;
        }
        send(p, v, m, f, reply, options(v, m));
    }

    public void end(ServerPlayerEntity p) {
        talking.remove(p.getUuid());
    }

    private List<String> options(VillagerEntity v, Residents.Member m) {
        if (v.isBaby()) return List.of(HOW, FAMILY, NEWS, BYE);
        if (m == null) return List.of(HOW, NEWS, TOWN, BYE);
        return List.of(HOW, FAMILY, WORK, NEWS, TOWN, BYE);
    }

    private void send(ServerPlayerEntity p, VillagerEntity v, Residents.Member m, Residents.Family f, String line, List<String> opts) {
        String name = v.hasCustomName() ? v.getCustomName().getString() : Townsfolk.name(v);
        String sub;
        if (m != null && f != null) {
            String role = m.child() ? (m.female() ? "daughter" : "son") + " of the " + f.surname() + " family"
                : (m.age() == 2 ? "elder" : m.job()) + " · the " + f.surname() + " family";
            name = name + " " + f.surname();
            sub = cap(role);
        } else {
            sub = "Passing through";
        }
        ServerPlayNetworking.send(p, new Net.Talk(v.getId(), name, sub, line, opts));
        if (!v.isSleeping()) {
            Townsfolk.face(v, p.getX(), p.getZ());
            AotRpg.FOLK.hold(v, 200);
        }
    }

    // ------------------------------------------------------------------ what they say

    private String greeting(ServerPlayerEntity p, VillagerEntity v, Residents.Member m) {
        if (v.isBaby()) return pick(KID_HELLO);
        long t = p.getServerWorld().getTimeOfDay() % 24000;
        String time = t < 5000 ? pick(MORNING) : t < 11500 ? pick(AFTERNOON) : pick(EVENING);
        if (m == null) return time + " " + pick(STRANGER_HELLO);
        String mood = switch (m.trait()) {
            case "cheerful" -> pick("Always nice to see a new face!", "Isn't it a fine day to be alive?", "You look like you could use a smile.");
            case "grumpy" -> pick("What do you want?", "Make it quick.", "I haven't got all day, soldier.");
            case "nervous" -> pick("Oh! You startled me.", "Is something wrong? Is it the Wall?", "Sorry, sorry, I'm a bit jumpy today.");
            case "gossip" -> pick("Ooh, a soldier. Have you heard the latest?", "You'll never guess what I just heard.", "Come closer, I won't bite.");
            case "pious" -> pick("The Walls keep us, friend.", "Blessed be the Walls. What can I do for you?", "Peace be with you.");
            case "bitter" -> pick("Another uniform. Wonderful.", "Come to tell us everything's fine?", "What now?");
            case "dreamer" -> pick("Do you ever wonder what's past the Walls?", "Sorry, I was miles away.", "I was just thinking about the sea. Have you heard of it?");
            case "proud" -> pick("You're speaking to a " + m.job() + " of " + "this town, I'll have you know.", "Good day. You'll find no finer work than mine.", "Ah. Yes?");
            case "kind" -> pick("Hello, dear. Have you eaten?", "You look tired. Sit a moment if you need.", "Can I help you with anything?");
            default -> pick("Long day. Long week, really.", "Mm? Oh, hello.", "Sorry, I'm half asleep on my feet.");
        };
        return time + " " + mood;
    }

    private String how(Residents.Member m, VillagerEntity v) {
        if (v.isBaby()) return pick(KID_HOW);
        if (m == null) return pick(HOW_ANY);
        return switch (m.trait()) {
            case "cheerful" -> pick("Couldn't be better! The bread's warm and the Walls are standing.", "Wonderful. My neighbour's hens finally laid.",
                "Good! Every day inside the Walls is a good day.", "Grand. I've a song stuck in my head and I don't mind one bit.");
            case "grumpy" -> pick("Terrible. Prices up, pay down, and my knee aches.", "How are things? Look around you.",
                "The same as yesterday, and just as bad.", "Someone let their goat in my garden again. That's how things are.");
            case "nervous" -> pick("I keep hearing the bells in my sleep. Are there titans near?", "Fine, I think. Is it fine? You'd tell us, wouldn't you?",
                "I've checked the locks four times today.", "Every time the gate opens my heart stops.");
            case "gossip" -> pick("Well, the " + pick(OTHER_FAMILIES) + " family are fighting again. The whole street heard.",
                "Fine, fine. But the baker's been seen with the tailor's wife, if you catch my meaning.", "Oh, I could tell you things. Ask me what I've heard.");
            case "pious" -> pick("The Walls provide, and we give thanks.", "I pray each morning the Walls stand another day. So far, so good.",
                "Well enough, by the grace of the Walls.");
            case "bitter" -> pick("We pay for the Survey Corps to die outside and nothing changes.", "How do you think? Stuck in a cage, same as always.",
                "Things were better before Maria fell. Everything was.");
            case "dreamer" -> pick("I dreamt of a lake of salt water last night. Isn't that strange?", "Restless. I want to see something new.",
                "Good. I'm saving up. For what, I don't know yet.");
            case "proud" -> pick("Very well. Business has never been better.", "My work speaks for itself.", "Fine. My family's name means something here.");
            case "kind" -> pick("Well enough, thank you for asking. Truly. Not many do.", "All the better for a kind word.",
                "We get by. We help each other, and we get by.");
            default -> pick("Tired. Always tired.", "I'll live. Probably.", "Ask me after I've slept.");
        };
    }

    private String family(Residents.Member m, Residents.Family f, VillagerEntity v) {
        if (m == null || f == null) return pick("I'm not from around here. My family's back home.", "Family? Just me these days.");
        List<Residents.Member> others = new ArrayList<>();
        for (Residents.Member o : f.members()) if (o != m) others.add(o);
        if (others.isEmpty()) return pick("Just me in that house. Quiet, but it's mine.", "No one. I lost them when Maria fell.",
            "I live alone. I've a cat, if that counts.");
        StringBuilder b = new StringBuilder();
        if (m.child()) {
            for (Residents.Member o : others) {
                if (o.age() == 1) {
                    b.append(o.female() ? "Mama" : "Papa").append("'s a ").append(o.job()).append(". ");
                } else if (o.age() == 0) {
                    b.append(o.first()).append(" is my ").append(o.female() ? "sister" : "brother").append(pick(". She's annoying.", ". We play soldiers.", ".")
                        .replace("She", o.female() ? "She" : "He")).append(" ");
                } else {
                    b.append(o.female() ? "Oma " : "Opa ").append(o.first()).append(" tells the best stories. ");
                }
            }
            return b.toString().trim();
        }
        for (Residents.Member o : others) {
            if (o.age() == 1 && m.age() == 1) {
                b.append(pick("My ", "That's my ")).append(o.female() ? "wife" : "husband").append(", ").append(o.first()).append(". ")
                    .append(o.female() ? "She's" : "He's").append(" a ").append(o.job()).append(pick(", and good at it.", ", for all it pays.",
                        ". Don't tell " + (o.female() ? "her" : "him") + " I said so, but I'm proud.", ".")).append(" ");
            } else if (o.age() == 0) {
                b.append(o.first()).append(", my ").append(o.female() ? "girl" : "boy").append(pick(", wants to join the Survey Corps. Over my dead body.",
                    ", eats like a titan.", ", never sits still.", ", is the clever one.", ", won't stop asking about the outside.")).append(" ");
            } else if (o.age() == 2) {
                b.append(o.first()).append(pick(" lives with us. ", ", my ")).append(o.female() ? "mother" : "father")
                    .append(pick(", remembers when the Walls were new, or says so.", ", still thinks it's " + (o.female() ? "her" : "his") + " house.", ".")).append(" ");
            } else if (m.age() == 2) {
                b.append(o.first()).append(pick(" keeps this house running.", " is the best thing I ever made. Don't tell the others.", " looks after me, bless them.")).append(" ");
            }
        }
        return ("We're the " + f.surname() + "s. " + b).trim();
    }

    private String work(Residents.Member m, VillagerEntity v) {
        if (m == null) return pick("A bit of this, a bit of that.", "Nothing that'd interest a soldier.");
        if (m.age() == 2) return pick("I did my years. Now I watch the street and complain.", "Retired. My hands don't work like they used to.",
            "I was a " + m.job().replace("retired ", "") + ", once. Best in the district.");
        String job = m.job();
        String[] lines = JOB_LINES.getOrDefault(job, new String[] {"I'm a " + job + ". Honest work.", "I work as a " + job + ". Keeps food on the table."});
        return pick(lines);
    }

    private String news(ServerPlayerEntity p, Residents.Member m) {
        List<String> pool = new ArrayList<>(List.of(RUMOURS));
        String hunt = AotRpg.BOUNTIES.rumour(p);
        if (hunt != null) {
            pool.add(hunt);
            pool.add(hunt);
        }
        String job = AotRpg.ESCORTS.rumour(p);
        if (job != null) pool.add(job);
        if (m != null && (m.trait().equals("gossip") || m.trait().equals("bitter")) && rng.nextInt(3) == 0) {
            return pick("Keep this quiet: there's a fellow in the back streets selling gear no honest soldier could afford. Stolen, they say. Wear it where the Military Police can see and you'll regret it.",
                "They say the black market's moved again. Look for a hooded man near the edge of town, after dark.");
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    private String town(ServerPlayerEntity p, Residents.Family f) {
        Net.Area a = AotRpg.PLACES.nearest(p.getX(), p.getZ(), 400, "town", "village", "city", "capital", "safe");
        String name = a == null ? "this place" : a.name();
        // Half the time, point the way to one of this town's traders (the real stall, the real way).
        Net.Area t = AotRpg.VENDORS.townAt(p.getX(), p.getZ());
        if (t != null && rng.nextBoolean()) {
            Vendors.Kind k = Vendors.Kind.values()[rng.nextInt(Vendors.Kind.values().length)];
            String d = Vendors.directions(t, k, p.getX(), p.getZ());
            if (d != null) {
                String[] w = d.split("\\|");
                String where = "stall's " + w[2] + " paces " + w[1] + " of here";
                return switch (k) {
                    case PROVISIONER -> pick("Best bread in " + name + "? " + w[0] + " the Provisioner. Their " + where + ".",
                        "Hungry? " + w[0] + " sells the good stuff. Their " + where + ". Don't let them sell you yesterday's loaf.");
                    case BLADESMITH -> pick("Need blades? " + w[0] + " the Bladesmith. Their " + where + ".",
                        w[0] + " keeps the Garrison's blades sharp and yours too, for a price. Their " + where + ".");
                    case ARMORER -> pick(w[0] + " the Armorer fits half the Scouts in " + name + ". Their " + where + ".",
                        "Coat's seen better days. " + w[0] + " could fix you up. Their " + where + ".");
                    case TOOLMAKER -> pick("Tools? " + w[0] + " the Toolmaker. Their " + where + ".",
                        w[0] + " makes a pickaxe that'll outlive you. Their " + where + ".");
                    case CRAFTSMAN -> pick("Want to make your own? " + w[0] + " the Craftsman sells benches you can carry. Their " + where + ".",
                        w[0] + " has schematics nobody else will part with. Their " + where + ".");
                    case STRANGER -> pick("There's " + w[0] + " in the back streets, " + w[2] + " paces " + w[1] + ". Sells things no honest soldier could afford. I never told you.",
                        "Keep it quiet: " + w[0] + " deals in the back streets, " + w[1] + " of here. Wear what they sell where the Military Police can see and you'll regret it.");
                };
            }
        }
        return pick(name + "? It's home. Crowded, loud, and the Walls are close enough to touch.",
            "You want to know about " + name + "? The market's the heart of it. Everything worth having passes through there.",
            name + " was quieter before the refugees came. I don't blame them. Where else would they go?",
            "Good bread, worse drains. That's " + name + " for you.",
            "The Garrison keeps an eye on " + name + ", mostly from the bottom of a bottle.",
            "Merchants come through " + name + " all the time. They pay well for protection on the roads, I hear.");
    }

    private String farewell(Residents.Member m, VillagerEntity v) {
        if (v.isBaby()) return pick("Bye! Kill a titan for me!", "Bye bye!", "Come back and show me your blades!");
        if (m != null && m.trait().equals("grumpy")) return pick("Finally.", "Mind the door.", "Good.");
        return pick("Take care of yourself.", "May the Walls keep you.", "Come back safe.", "Good day to you.", "Go on, then. Stay alive.");
    }

    private String pick(String... a) {
        return a[rng.nextInt(a.length)];
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ lines

    private static final String[] ASLEEP = {"(They're fast asleep, snoring softly.)", "(Asleep. They mumble something about bread.)",
        "(Sound asleep. Best not to wake them.)", "(They stir, mutter \"five more minutes\", and sleep on.)"};
    private static final String[] MORNING = {"Morning.", "Good morning.", "Early start?", "Morning, soldier."};
    private static final String[] AFTERNOON = {"Afternoon.", "Good day.", "Hello there.", "Ah, hello."};
    private static final String[] EVENING = {"Evening.", "Getting late.", "Good evening.", "You're out late."};
    private static final String[] STRANGER_HELLO = {"I'm only passing through.", "Don't mind me.", "Can I help you?", "Yes?"};
    private static final String[] KID_HELLO = {"Are you a real soldier?", "Wow, are those ODM blades?", "Mama says I'm not allowed to talk to soldiers.",
        "Have you ever seen a titan? Up close?", "I'm going to join the Survey Corps when I'm big!"};
    private static final String[] KID_HOW = {"Good! I found a beetle!", "Bored. There's nothing to do.", "I'm hungry.",
        "My brother pushed me in the canal.", "Great! We're playing titans and I'm the titan!"};
    private static final String[] HOW_ANY = {"Can't complain. Well, I can, but who'd listen?", "Busy. Always busy.", "The roads are worse than ever.",
        "Fine, until the bells ring."};
    private static final String[] OTHER_FAMILIES = {"Braun", "Fischer", "Weber", "Hoffmann", "Koch", "Keller", "Wagner"};
    private static final String[] RUMOURS = {
        "The Survey Corps rode out through the south gate at dawn. Not many horses came back.",
        "A merchant was saying the roads to the next town are crawling with titans. He's looking for guards, and paying well.",
        "My cousin swears she saw a titan walking on all fours. Nobody believes her.",
        "The Military Police have been searching houses. Looking for stolen gear, I heard.",
        "They say the Garrison found a crack in the Wall. They say a lot of things.",
        "Food's getting dear. The farms outside took a beating last month.",
        "There's a smith in the market who'll sell you blades better than the Corps issues. For a price.",
        "Someone's been robbing soldiers on the road at night. Watch yourself.",
        "The priests are holding a vigil at the Wall tonight. Again.",
        "The traders at the market square have fresh stock. Armour, tools, the lot.",
        "A cadet fell off the Wall last week. Gas ran out, they said.",
        "I heard there's a price on someone's head. Big one, too. Soldiers are hunting all over.",
        "The ferry's running again, if you're heading to another district.",
        "Somebody saw a light in the old ruins outside the Wall. Bandits, maybe.",
    };
    private static final Map<String, String[]> JOB_LINES = new HashMap<>();
    static {
        JOB_LINES.put("baker", new String[] {"I'm the baker. Up before the sun, flour in everything.", "Bread, mostly. And the odd cake when there's sugar, which there isn't."});
        JOB_LINES.put("butcher", new String[] {"Butcher. Don't ask where the meat comes from these days.", "I cut meat. Sharp knives, steady hands. Like you, I suppose."});
        JOB_LINES.put("blacksmith", new String[] {"Smith. I mend what you lot break.", "I work the forge. Horseshoes, hinges, the odd blade if the Corps is short."});
        JOB_LINES.put("carpenter", new String[] {"Carpenter. Half this street is my work.", "I build things. Chairs, doors, coffins, more coffins lately."});
        JOB_LINES.put("tailor", new String[] {"Tailor. I've stitched a hundred uniforms and buried half the boys who wore them.", "I make clothes. Want your cloak taken in? It's hanging off you."});
        JOB_LINES.put("farmer", new String[] {"I farm the fields by the Wall. Closer to the titans than I'd like.", "Potatoes, turnips, the odd cabbage. Riveting, I know."});
        JOB_LINES.put("miller", new String[] {"I run the mill. Grind grain, grind my teeth.", "Miller. If there's bread in this town, it went through my stones."});
        JOB_LINES.put("fisherman", new String[] {"I fish the canal. Mostly boots, some days.", "Fisherman. Up at dawn, cold to the bone, and smelling of it."});
        JOB_LINES.put("brewer", new String[] {"Brewer. The Garrison keeps me in business.", "I brew the ale half this district drinks. You're welcome."});
        JOB_LINES.put("washer", new String[] {"I take in washing. Blood comes out with salt and cold water, if you're wondering.", "Laundry. My hands haven't been dry in years."});
        JOB_LINES.put("clerk", new String[] {"Clerk at the district office. I count things. People, mostly.", "I keep the records. Births, deaths. Lately more of one than the other."});
        JOB_LINES.put("merchant", new String[] {"Merchant. I move goods between towns, when the roads are safe. They rarely are.", "I trade. And I'm always looking for sharp blades to guard my wagons, if you're interested."});
        JOB_LINES.put("Garrison soldier", new String[] {"Garrison. Wall duty. Mostly watching nothing happen.", "I man the cannons. Haven't fired one in anger. Pray I never do."});
        JOB_LINES.put("nurse", new String[] {"I nurse at the infirmary. I've patched up soldiers like you. Don't make me do it again.", "Nurse. Keep your blades clean and your gas full and I won't have to see you."});
        JOB_LINES.put("teacher", new String[] {"I teach the children their letters. And about the Walls, as I'm told to.", "Teacher. Every year more of them want to join the Corps."});
        JOB_LINES.put("stablehand", new String[] {"I muck out the stables. The horses are better company than most folk.", "Stablehand. If you've a horse, bring it round and I'll see to it."});
        JOB_LINES.put("innkeeper", new String[] {"I keep the inn. Beds, beer, and no brawling. Mostly.", "Innkeeper. Soldiers drink, merchants talk, I listen."});
        JOB_LINES.put("herbalist", new String[] {"Herbs and remedies. Better than half the doctors, and cheaper.", "I know what grows by the Wall and what it cures."});
        JOB_LINES.put("lamplighter", new String[] {"I light the lamps at dusk and put them out at dawn. Quiet work, I like it.", "Lamplighter. I see the whole town asleep every night."});
    }
}
