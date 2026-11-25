package me.cosmic.fake;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/** Settings, factions, kits and paths, all saved to plugins/CosmicBots/*.yml */
final class Store {
    final File dir;
    Store(File dir) { this.dir = dir; dir.mkdirs(); }

    // ================================================================ settings
    static final LinkedHashMap<String, Object> DEFAULTS = new LinkedHashMap<>();
    static final Map<String, double[]> RANGE = new HashMap<>();
    static {
        for (String k : new String[]{"auto-armor", "auto-weapon", "combat", "revenge", "target-players", "criticals", "ranged", "mace", "crystal-pvp", "anchor-pvp",
                "retreat", "auto-totem", "totem-priority", "auto-shield", "shield-break", "shield-mace", "prefer-sword", "auto-eat", "auto-potion", "auto-mend", "bhop",
                "safe-spawn", "clear-on-remove", "arrow-prediction", "ranged-strafe", "ranged-retreat", "cobwebs",
                "knockback", "s-tap", "jump-reset", "crit-spam", "stun", "stun-web", "web-escape", "anti-mace", "pearls", "teamwork", "fullbox", "cart-pvp"}) DEFAULTS.put(k, true);
        for (String k : new String[]{"target-bots", "drop-armor", "drop-weapon", "auto-target", "target-mobs", "idle", "friendly-fire", "special-names", "bot-leave-on-death",
                "attack-invincible", "profile-lagg-fix"}) DEFAULTS.put(k, false);
        num("interval", 1, 1, 100); num("attack-cooldown", 10, 1, 40); num("crit-fall-ticks", 3, 1, 10); num("shield-hold-ticks", 40, 10, 200);
        num("shield-raise-ticks", 5, 2, 40); num("bow-draw-ticks", 22, 5, 100); num("melee-range", 3.0, 2, 6); num("move-speed", 1.0, 0.1, 2.0);
        num("view-distance", 32, 5, 128); num("aim-speed", 30, 3, 90); num("drop-distance", 3, 1, 10); num("idle-radius", 10, 3, 50);
        num("shield-break-chance", 80, 0, 100); num("miss-chance", 8, 0, 100); num("mistake-chance", 5, 0, 100);
        num("ranged-min-range", 6, 3, 20); num("ranged-optimal-range", 18, 10, 50); num("ranged-max-range", 40, 15, 100); num("max-mass-spawn", 50, 1, 10000); num("skill", 70, 1, 101);
    }
    static void num(String k, double v, double lo, double hi) { DEFAULTS.put(k, v); RANGE.put(k, new double[]{lo, hi}); }
    final Map<String, Object> set = new LinkedHashMap<>(DEFAULTS);
    boolean b(String k) { return Boolean.TRUE.equals(set.get(k)); }
    double d(String k) { Object o = set.get(k); return o instanceof Number n ? n.doubleValue() : 0; }
    int i(String k) { return (int) Math.round(d(k)); }
    /** returns an error message or null */
    String setSetting(String k, String v) {
        if (!DEFAULTS.containsKey(k)) return "Unknown setting " + k;
        if (DEFAULTS.get(k) instanceof Boolean) {
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) return k + " is true/false";
            set.put(k, Boolean.parseBoolean(v));
        } else {
            double x; try { x = Double.parseDouble(v); } catch (NumberFormatException e) { return k + " needs a number"; }
            double[] r = RANGE.get(k); if (x < r[0] || x > r[1]) return k + " must be " + fmt(r[0]) + "-" + fmt(r[1]);
            set.put(k, x);
        }
        saveSettings(); return null;
    }
    static String fmt(double x) { return x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x); }

    // ================================================================ factions
    static final class Faction { final String name; final LinkedHashSet<String> members = new LinkedHashSet<>(); final Set<String> hostile = new HashSet<>(); Faction(String n) { name = n; } }
    final Map<String, Faction> factions = new LinkedHashMap<>();
    Faction factionOf(String member) { for (Faction f : factions.values()) for (String m : f.members) if (m.equalsIgnoreCase(member)) return f; return null; }
    boolean sameFaction(String a, String b) { Faction f = factionOf(a); return f != null && f == factionOf(b); }
    boolean hostile(String a, String b) {
        Faction fa = factionOf(a), fb = factionOf(b);
        return fa != null && fb != null && fa != fb && (fa.hostile.contains(fb.name.toLowerCase()) || fb.hostile.contains(fa.name.toLowerCase()));
    }

    // ================================================================ kits
    final Map<String, ItemStack[]> kits = new LinkedHashMap<>();

    // ================================================================ paths
    static final class PathDef { final String name; final List<Location> points = new ArrayList<>(); String loop = "none"; String walk = "walk"; boolean show; PathDef(String n) { name = n; } }
    final Map<String, PathDef> paths = new LinkedHashMap<>();

    // ================================================================ load / save
    void load() {
        YamlConfiguration s = YamlConfiguration.loadConfiguration(new File(dir, "settings.yml"));
        for (String k : DEFAULTS.keySet()) if (s.contains(k)) {
            if (DEFAULTS.get(k) instanceof Boolean) { String v = String.valueOf(s.get(k)).toLowerCase(); set.put(k, v.equals("true") || v.equals("on") || v.equals("yes")); }
            else { double x = s.getDouble(k, ((Number) DEFAULTS.get(k)).doubleValue()); double[] r = RANGE.get(k); set.put(k, Math.max(r[0], Math.min(r[1], x))); }
        }
        // v2.2: bots from a mass-spawn were killing each other right away; bots no longer target other bots unless you turn it on
        if (s.getInt("config-version", 1) < 2) { set.put("target-bots", false); saveSettings(); }
        YamlConfiguration f = YamlConfiguration.loadConfiguration(new File(dir, "factions.yml"));
        factions.clear();
        for (String k : f.getKeys(false)) { Faction fa = new Faction(k); fa.members.addAll(f.getStringList(k + ".members")); for (String h : f.getStringList(k + ".hostile")) fa.hostile.add(h.toLowerCase()); factions.put(k.toLowerCase(), fa); }
        YamlConfiguration kc = YamlConfiguration.loadConfiguration(new File(dir, "kits.yml"));
        kits.clear();
        for (String k : kc.getKeys(false)) { List<?> l = kc.getList(k); if (l == null) continue; ItemStack[] arr = new ItemStack[l.size()]; for (int i = 0; i < arr.length; i++) arr[i] = l.get(i) instanceof ItemStack is ? is : null; kits.put(k.toLowerCase(), arr); }
        YamlConfiguration pc = YamlConfiguration.loadConfiguration(new File(dir, "paths.yml"));
        paths.clear();
        for (String k : pc.getKeys(false)) {
            ConfigurationSection c = pc.getConfigurationSection(k); if (c == null) continue;
            PathDef p = new PathDef(k); p.loop = c.getString("loop", "none"); p.walk = c.getString("walk", "walk"); p.show = c.getBoolean("show");
            for (String pt : c.getStringList("points")) { Location l = parse(pt); if (l != null) p.points.add(l); }
            paths.put(k.toLowerCase(), p);
        }
    }
    void saveSettings() { YamlConfiguration s = new YamlConfiguration(); s.set("config-version", 2); set.forEach(s::set); try { s.save(new File(dir, "settings.yml")); } catch (Exception ignored) {} }
    void saveFactions() {
        YamlConfiguration f = new YamlConfiguration();
        for (Faction fa : factions.values()) { f.set(fa.name + ".members", new ArrayList<>(fa.members)); f.set(fa.name + ".hostile", new ArrayList<>(fa.hostile)); }
        try { f.save(new File(dir, "factions.yml")); } catch (Exception ignored) {}
    }
    void saveKits() { YamlConfiguration k = new YamlConfiguration(); kits.forEach((n, a) -> k.set(n, Arrays.asList(a))); try { k.save(new File(dir, "kits.yml")); } catch (Exception ignored) {} }
    void savePaths() {
        YamlConfiguration pc = new YamlConfiguration();
        for (PathDef p : paths.values()) { pc.set(p.name + ".loop", p.loop); pc.set(p.name + ".walk", p.walk); pc.set(p.name + ".show", p.show);
            List<String> l = new ArrayList<>(); for (Location x : p.points) l.add(str(x)); pc.set(p.name + ".points", l); }
        try { pc.save(new File(dir, "paths.yml")); } catch (Exception ignored) {}
    }
    void saveAll() { saveSettings(); saveFactions(); saveKits(); savePaths(); }

    static String str(Location l) { return l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch(); }
    static Location parse(String s) {
        try { String[] p = s.split(","); World w = Bukkit.getWorld(p[0]); if (w == null) return null;
              return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), p.length > 4 ? Float.parseFloat(p[4]) : 0, p.length > 5 ? Float.parseFloat(p[5]) : 0);
        } catch (Exception e) { return null; }
    }
}
