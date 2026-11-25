package me.cosmic.fake;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** /pvpbot: spawn, remove, removeall, reload, list, inventory, mass-spawn, attack, stop-attack, faction, kit, path, settings */
final class PvpCmd implements CommandExecutor, TabCompleter {
    final CosmicBots pl; final Store st;
    PvpCmd(CosmicBots pl) { this.pl = pl; this.st = pl.store; }
    static final String G = "§a", R = "§c", Y = "§e", W = "§f", D = "§7";

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { help(s); return true; }
        try {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "spawn" -> {
                    String n = a.length > 1 ? a[1] : pl.randomName();
                    Location l = s instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
                    pl.spawn(n, l, GameMode.SURVIVAL, s, b -> { if (b != null) s.sendMessage(G + "Spawned PvP bot " + W + n); });
                }
                case "remove" -> { Bot b = need(s, a, 1); if (b != null) { pl.remove(b, "Removed"); s.sendMessage(G + "Removed " + b.name); } }
                case "removeall" -> { int n = pl.bots.size(); for (Bot b : new ArrayList<>(pl.bots.values())) pl.remove(b, "Removed"); s.sendMessage(G + "Removed " + n + " bots"); }
                case "reload" -> { st.load(); s.sendMessage(G + "Reloaded settings, factions, kits and paths"); }
                case "list" -> s.sendMessage(Y + "Bots (" + pl.bots.size() + "): " + W + (pl.bots.isEmpty() ? "none" : String.join(", ", names())));
                case "inventory" -> { Bot b = need(s, a, 1); if (b != null && s instanceof Player p) p.openInventory(b.p.getInventory()); }
                case "mass-spawn" -> {
                    int n = Integer.parseInt(a[1]); int max = Math.min(50, st.i("max-mass-spawn"));
                    if (n < 1 || n > Math.max(max, 50)) { s.sendMessage(R + "1-" + Math.max(max, 50)); return true; }
                    Location base = s instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
                    for (int i = 0; i < n; i++) {
                        final int k = i;
                        Bukkit.getScheduler().runTaskLater(pl, () -> {
                            double an = k * 2.4, r = 2 + Math.sqrt(k) * 1.6;
                            pl.spawn(pl.randomName(), base.clone().add(Math.cos(an) * r, 0, Math.sin(an) * r), GameMode.SURVIVAL, null, null);
                        }, 3L * i);
                    }
                    s.sendMessage(G + "Spawning " + n + " bots...");
                }
                case "attack" -> { Bot b = need(s, a, 1); Entity t = entity(a[2]); if (b == null) return true; if (t == null) { s.sendMessage(R + "No target " + a[2]); return true; } b.forced = t.getUniqueId(); s.sendMessage(G + b.name + " attacks " + a[2]); }
                case "stop-attack" -> { Bot b = need(s, a, 1); if (b != null) { b.forced = null; b.revenge = null; s.sendMessage(G + b.name + " stopped attacking"); } }
                case "faction" -> faction(s, a);
                case "create-kit", "delete-kit", "kits", "give-kit", "give-kit-near", "give-kit-near-random" -> kit(s, a, 0);
                case "kit" -> kit(s, a, 1);
                case "path" -> path(s, a);
                case "settings" -> settings(s, a);
                default -> help(s);
            }
        } catch (ArrayIndexOutOfBoundsException e) { s.sendMessage(R + "Missing arguments. /pvpbot for help"); }
        catch (NumberFormatException e) { s.sendMessage(R + "That needs a number"); }
        return true;
    }
    List<String> names() { List<String> l = new ArrayList<>(); for (Bot b : pl.bots.values()) l.add(b.name); return l; }
    Bot need(CommandSender s, String[] a, int i) { Bot b = pl.bot(a[i]); if (b == null) s.sendMessage(R + a[i] + " is not a bot"); return b; }
    Entity entity(String n) { Player p = Bukkit.getPlayerExact(n); if (p != null) return p; try { return Bukkit.getEntity(UUID.fromString(n)); } catch (Exception e) { return null; } }
    List<Bot> near(CommandSender s, double r) {
        List<Bot> l = new ArrayList<>(); if (!(s instanceof Player p)) return l;
        for (Bot b : pl.bots.values()) if (b.p.getWorld().equals(p.getWorld()) && b.p.getLocation().distance(p.getLocation()) <= r) l.add(b);
        return l;
    }

    // ================================================================ factions
    void faction(CommandSender s, String[] a) {
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) { s.sendMessage(Y + "Factions: " + W + (st.factions.isEmpty() ? "none" : String.join(", ", st.factions.keySet()))); return; }
        String fn = a[2]; Store.Faction f = st.factions.get(fn.toLowerCase(Locale.ROOT));
        switch (sub) {
            case "create" -> { if (f != null) { s.sendMessage(R + "Exists"); return; } st.factions.put(fn.toLowerCase(Locale.ROOT), new Store.Faction(fn)); st.saveFactions(); s.sendMessage(G + "Created faction " + fn); return; }
            default -> { if (f == null) { s.sendMessage(R + "No faction " + fn); return; } }
        }
        switch (sub) {
            case "delete" -> { st.factions.remove(fn.toLowerCase(Locale.ROOT)); for (Store.Faction o : st.factions.values()) o.hostile.remove(fn.toLowerCase(Locale.ROOT)); s.sendMessage(G + "Deleted " + fn); }
            case "info" -> { s.sendMessage(Y + f.name + D + " (" + f.members.size() + " members)"); s.sendMessage(D + "Members: " + W + String.join(", ", f.members)); s.sendMessage(D + "Hostile to: " + W + (f.hostile.isEmpty() ? "nobody" : String.join(", ", f.hostile))); }
            case "add" -> { for (Store.Faction o : st.factions.values()) o.members.removeIf(m -> m.equalsIgnoreCase(a[3])); f.members.add(a[3]); s.sendMessage(G + "Added " + a[3] + " to " + f.name); }
            case "remove" -> { f.members.removeIf(m -> m.equalsIgnoreCase(a[3])); s.sendMessage(G + "Removed " + a[3]); }
            case "add-near" -> { double r = a.length > 3 ? Double.parseDouble(a[3]) : 20; int n = 0; for (Bot b : near(s, r)) { for (Store.Faction o : st.factions.values()) o.members.remove(b.name); f.members.add(b.name); n++; } s.sendMessage(G + "Added " + n + " nearby bots"); }
            case "add-all" -> { for (Bot b : pl.bots.values()) { for (Store.Faction o : st.factions.values()) o.members.remove(b.name); f.members.add(b.name); } s.sendMessage(G + "Added all " + pl.bots.size() + " bots"); }
            case "hostile" -> {
                Store.Faction o = st.factions.get(a[3].toLowerCase(Locale.ROOT)); if (o == null) { s.sendMessage(R + "No faction " + a[3]); return; }
                boolean on = a.length <= 4 || Boolean.parseBoolean(a[4]);
                if (on) { f.hostile.add(o.name.toLowerCase(Locale.ROOT)); o.hostile.add(f.name.toLowerCase(Locale.ROOT)); } else { f.hostile.remove(o.name.toLowerCase(Locale.ROOT)); o.hostile.remove(f.name.toLowerCase(Locale.ROOT)); }
                s.sendMessage(G + f.name + " and " + o.name + (on ? " are now enemies" : " are at peace"));
            }
            case "attack" -> { Entity t = entity(a[3]); if (t == null) { s.sendMessage(R + "No target"); return; } int n = 0; for (String m : f.members) { Bot b = pl.bot(m); if (b != null) { b.forced = t.getUniqueId(); n++; } } s.sendMessage(G + n + " bots attack " + a[3]); }
            case "give" -> { if (!(s instanceof Player p)) return; ItemStack it = p.getInventory().getItemInMainHand(); if (it.getType().isAir()) { s.sendMessage(R + "Hold the item to give"); return; } int n = 0; for (String m : f.members) { Bot b = pl.bot(m); if (b != null) { b.p.getInventory().addItem(it.clone()); n++; } } s.sendMessage(G + "Gave " + it.getType() + " x" + it.getAmount() + " to " + n + " bots"); }
            case "tp" -> {
                Location to;
                if (a.length >= 6) { Location base = s instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation(); to = new Location(base.getWorld(), CosmicBots.coord(a[3], base.getX()), CosmicBots.coord(a[4], base.getY()), CosmicBots.coord(a[5], base.getZ())); }
                else { Player t = Bukkit.getPlayerExact(a[3]); if (t == null) { s.sendMessage(R + "No player " + a[3]); return; } to = t.getLocation(); }
                int i = 0; for (String m : f.members) { Bot b = pl.bot(m); if (b == null) continue; final Location dest = to.clone().add((i % 5) - 2, 0, (i / 5) - 2); Bukkit.getScheduler().runTaskLater(pl, () -> { b.p.teleport(dest); b.home = dest; }, 4L * i++); }
                s.sendMessage(G + "Teleporting " + i + " bots");
            }
            case "path" -> {
                String act = a[3].toLowerCase(Locale.ROOT);
                if (act.equals("start")) { Store.PathDef pd = st.paths.get(a[4].toLowerCase(Locale.ROOT)); if (pd == null) { s.sendMessage(R + "No path " + a[4]); return; } int n = 0; for (String m : f.members) { Bot b = pl.bot(m); if (b != null) { b.path = pd.name; b.pathIdx = 0; b.pathDir = 1; n++; } } s.sendMessage(G + n + " bots walking " + pd.name); }
                else { for (String m : f.members) { Bot b = pl.bot(m); if (b != null) b.path = null; } s.sendMessage(G + "Stopped " + f.name); }
            }
            case "kit" -> {
                String act = a[3].toLowerCase(Locale.ROOT); List<Bot> bs = new ArrayList<>(); for (String m : f.members) { Bot b = pl.bot(m); if (b != null) bs.add(b); }
                if (act.equals("give-kit")) { ItemStack[] k = st.kits.get(a[4].toLowerCase(Locale.ROOT)); if (k == null) { s.sendMessage(R + "No kit"); return; } for (Bot b : bs) giveKit(b.p, k); s.sendMessage(G + "Gave " + a[4] + " to " + bs.size() + " bots"); }
                else { for (Bot b : bs) giveRandom(b.p, Arrays.copyOfRange(a, 4, a.length)); s.sendMessage(G + "Gave random kits to " + bs.size() + " bots"); }
            }
            default -> s.sendMessage(R + "faction list|create|delete|info|add|remove|add-near|add-all|hostile|attack|give|tp|path|kit");
        }
        st.saveFactions();
    }

    // ================================================================ kits
    void kit(CommandSender s, String[] a, int o) {
        String sub = a[o].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create-kit" -> { if (!(s instanceof Player p)) return; ItemStack[] c = p.getInventory().getContents(); ItemStack[] copy = new ItemStack[c.length]; for (int i = 0; i < c.length; i++) copy[i] = c[i] == null ? null : c[i].clone(); st.kits.put(a[o + 1].toLowerCase(Locale.ROOT), copy); st.saveKits(); s.sendMessage(G + "Saved kit " + a[o + 1] + " from your inventory"); }
            case "delete-kit" -> { st.kits.remove(a[o + 1].toLowerCase(Locale.ROOT)); st.saveKits(); s.sendMessage(G + "Deleted kit " + a[o + 1]); }
            case "kits" -> s.sendMessage(Y + "Kits: " + W + (st.kits.isEmpty() ? "none" : String.join(", ", st.kits.keySet())));
            case "give-kit" -> {
                ItemStack[] k = st.kits.get(a[o + 1].toLowerCase(Locale.ROOT)); if (k == null) { s.sendMessage(R + "No kit " + a[o + 1]); return; }
                Player t = Bukkit.getPlayerExact(a[o + 2]); if (t == null) { s.sendMessage(R + "No player/bot " + a[o + 2]); return; }
                giveKit(t, k); s.sendMessage(G + "Gave " + a[o + 1] + " to " + t.getName());
            }
            case "give-kit-near" -> { ItemStack[] k = st.kits.get(a[o + 1].toLowerCase(Locale.ROOT)); if (k == null) { s.sendMessage(R + "No kit"); return; } double r = a.length > o + 2 ? Double.parseDouble(a[o + 2]) : 20; List<Bot> l = near(s, r); for (Bot b : l) giveKit(b.p, k); s.sendMessage(G + "Gave " + a[o + 1] + " to " + l.size() + " bots"); }
            case "give-kit-near-random" -> { double r = Double.parseDouble(a[o + 1]); List<Bot> l = near(s, r); for (Bot b : l) giveRandom(b.p, Arrays.copyOfRange(a, o + 2, a.length)); s.sendMessage(G + "Gave random kits to " + l.size() + " bots"); }
            default -> s.sendMessage(R + "create-kit|delete-kit|kits|give-kit|give-kit-near|give-kit-near-random");
        }
    }
    void giveKit(Player p, ItemStack[] k) { ItemStack[] c = new ItemStack[k.length]; for (int i = 0; i < k.length; i++) c[i] = k[i] == null ? null : k[i].clone(); p.getInventory().setContents(c); }
    /** args like  pvp:3 archer:1 tank  (name:weight, weight defaults to 1) */
    void giveRandom(Player p, String[] spec) {
        List<String> ks = new ArrayList<>(); List<Double> ws = new ArrayList<>(); double tot = 0;
        for (String x : spec) { String[] q = x.split(":"); if (!st.kits.containsKey(q[0].toLowerCase(Locale.ROOT))) continue; double w = q.length > 1 ? Double.parseDouble(q[1]) : 1; ks.add(q[0].toLowerCase(Locale.ROOT)); ws.add(w); tot += w; }
        if (ks.isEmpty()) return;
        double r = pl.rnd.nextDouble() * tot;
        for (int i = 0; i < ks.size(); i++) { r -= ws.get(i); if (r <= 0) { giveKit(p, st.kits.get(ks.get(i))); return; } }
        giveKit(p, st.kits.get(ks.get(ks.size() - 1)));
    }

    // ================================================================ paths
    void path(CommandSender s, String[] a) {
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) { s.sendMessage(Y + "Paths: " + W + (st.paths.isEmpty() ? "none" : String.join(", ", st.paths.keySet()))); return; }
        if (sub.equals("stop-all")) { for (Bot b : pl.bots.values()) b.path = null; s.sendMessage(G + "All bots stopped walking"); return; }
        if (sub.equals("stop")) { Bot b = need(s, a, 2); if (b != null) { b.path = null; s.sendMessage(G + b.name + " stopped"); } return; }
        String pn = a[2]; Store.PathDef pd = st.paths.get(pn.toLowerCase(Locale.ROOT));
        if (sub.equals("create")) { if (pd != null) { s.sendMessage(R + "Exists"); return; } st.paths.put(pn.toLowerCase(Locale.ROOT), new Store.PathDef(pn)); st.savePaths(); s.sendMessage(G + "Created path " + pn + D + " — stand on spots and use /pvpbot path add-point " + pn); return; }
        if (pd == null) { s.sendMessage(R + "No path " + pn); return; }
        switch (sub) {
            case "delete" -> { st.paths.remove(pn.toLowerCase(Locale.ROOT)); for (Bot b : pl.bots.values()) if (pn.equalsIgnoreCase(b.path)) b.path = null; s.sendMessage(G + "Deleted " + pn); }
            case "add-point" -> { if (s instanceof Player p) { pd.points.add(p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5)); s.sendMessage(G + "Point #" + pd.points.size() + " added"); } }
            case "remove-point" -> { int i = a.length > 3 ? Integer.parseInt(a[3]) - 1 : pd.points.size() - 1; if (i >= 0 && i < pd.points.size()) { pd.points.remove(i); s.sendMessage(G + "Removed point #" + (i + 1)); } }
            case "clear" -> { pd.points.clear(); s.sendMessage(G + "Cleared " + pn); }
            case "info" -> { s.sendMessage(Y + pd.name + D + " points " + W + pd.points.size() + D + ", loop " + W + pd.loop + D + ", walk " + W + pd.walk + D + ", show " + W + pd.show);
                             for (int i = 0; i < pd.points.size(); i++) { Location l = pd.points.get(i); s.sendMessage(D + " #" + (i + 1) + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()); } }
            case "loop" -> { String v = a[3].toLowerCase(Locale.ROOT); if (!List.of("none", "backforth", "circle").contains(v)) { s.sendMessage(R + "none|backforth|circle"); return; } pd.loop = v; s.sendMessage(G + "Loop: " + v); }
            case "walk-type" -> { String v = a[3].toLowerCase(Locale.ROOT); if (!List.of("walk", "sprint", "bhop").contains(v)) { s.sendMessage(R + "walk|sprint|bhop"); return; } pd.walk = v; s.sendMessage(G + "Walk type: " + v); }
            case "show" -> { pd.show = a.length > 3 ? Boolean.parseBoolean(a[3]) : !pd.show; s.sendMessage(G + "Show: " + pd.show); }
            case "start" -> { Bot b = need(s, a, 3); if (b != null) { b.path = pd.name; b.pathIdx = 0; b.pathDir = 1; s.sendMessage(G + b.name + " walks " + pd.name); } }
            case "start-near" -> { double r = a.length > 3 ? Double.parseDouble(a[3]) : 20; int n = 0; for (Bot b : near(s, r)) { b.path = pd.name; b.pathIdx = nearestPoint(pd, b.p.getLocation()); b.pathDir = 1; n++; } s.sendMessage(G + n + " bots walk " + pd.name); }
            case "distribute" -> {
                List<Bot> l = new ArrayList<>(); for (Bot b : pl.bots.values()) if (pd.name.equalsIgnoreCase(b.path)) l.add(b);
                if (l.isEmpty() || pd.points.isEmpty()) { s.sendMessage(R + "No bots on " + pn); return; }
                for (int i = 0; i < l.size(); i++) { int idx = (int) ((long) i * pd.points.size() / l.size()); l.get(i).p.teleport(pd.points.get(idx)); l.get(i).pathIdx = idx; }
                s.sendMessage(G + "Spread " + l.size() + " bots along " + pn);
            }
            default -> s.sendMessage(R + "path create|delete|add-point|remove-point|clear|list|info|loop|walk-type|show|start|stop|distribute|start-near|stop-all");
        }
        st.savePaths();
    }
    static int nearestPoint(Store.PathDef pd, Location l) { int best = 0; double bd = 1e18; for (int i = 0; i < pd.points.size(); i++) { Location p = pd.points.get(i); if (!p.getWorld().equals(l.getWorld())) continue; double d = p.distanceSquared(l); if (d < bd) { bd = d; best = i; } } return best; }

    // ================================================================ settings
    void settings(CommandSender s, String[] a) {
        if (a.length == 1) {
            StringBuilder on = new StringBuilder(), nums = new StringBuilder();
            for (Map.Entry<String, Object> e : st.set.entrySet()) {
                if (e.getValue() instanceof Boolean bo) on.append(bo ? G : R).append(e.getKey()).append(" ");
                else nums.append(Y).append(e.getKey()).append(D).append("=").append(W).append(Store.fmt(((Number) e.getValue()).doubleValue())).append(" ");
            }
            s.sendMessage(Y + "Toggles (" + G + "on" + Y + "/" + R + "off" + Y + "): " + on);
            s.sendMessage(Y + "Values: " + nums);
            s.sendMessage(D + "/pvpbot settings <name> <value>");
            return;
        }
        if (a.length == 2) { Object v = st.set.get(a[1].toLowerCase(Locale.ROOT)); s.sendMessage(v == null ? R + "Unknown setting" : Y + a[1] + " = " + W + v); return; }
        String err = st.setSetting(a[1].toLowerCase(Locale.ROOT), a[2]);
        s.sendMessage(err != null ? R + err : G + a[1] + " = " + a[2]);
    }

    void help(CommandSender s) {
        s.sendMessage(Y + "§lPvP Bots");
        s.sendMessage(D + "/pvpbot spawn [name] | remove <name> | removeall | list | inventory <name> | mass-spawn <n> | reload");
        s.sendMessage(D + "/pvpbot attack <bot> <target> | stop-attack <bot>");
        s.sendMessage(D + "/pvpbot faction list|create|delete|info|add|remove|add-near|add-all|hostile|attack|give|tp|path|kit");
        s.sendMessage(D + "/pvpbot create-kit|delete-kit|kits|give-kit|give-kit-near|give-kit-near-random");
        s.sendMessage(D + "/pvpbot path create|delete|add-point|remove-point|clear|list|info|loop|walk-type|show|start|stop|distribute|start-near|stop-all");
        s.sendMessage(D + "/pvpbot settings [name] [value]");
    }

    // ================================================================ tab completion
    @Override public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        List<String> bots = names(), facs = new ArrayList<>(st.factions.keySet()), kits = new ArrayList<>(st.kits.keySet()), paths = new ArrayList<>(st.paths.keySet());
        List<String> players = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
        String a0 = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 1) out.addAll(List.of("spawn", "remove", "removeall", "reload", "list", "inventory", "mass-spawn", "attack", "stop-attack", "faction", "create-kit", "delete-kit", "kits", "give-kit", "give-kit-near", "give-kit-near-random", "path", "settings"));
        else if (a.length == 2) switch (a0) {
            case "remove", "inventory", "attack", "stop-attack" -> out.addAll(bots);
            case "faction" -> out.addAll(List.of("list", "create", "delete", "info", "add", "remove", "add-near", "add-all", "hostile", "attack", "give", "tp", "path", "kit"));
            case "delete-kit", "give-kit", "give-kit-near" -> out.addAll(kits);
            case "path" -> out.addAll(List.of("create", "delete", "add-point", "remove-point", "clear", "list", "info", "loop", "walk-type", "show", "start", "stop", "distribute", "start-near", "stop-all"));
            case "settings" -> out.addAll(st.set.keySet());
            default -> {}
        }
        else if (a.length == 3) switch (a0) {
            case "attack" -> out.addAll(players);
            case "faction" -> out.addAll(facs);
            case "give-kit" -> out.addAll(players);
            case "path" -> { if (a[1].equalsIgnoreCase("stop")) out.addAll(bots); else out.addAll(paths); }
            case "settings" -> { Object v = st.set.get(a[1].toLowerCase(Locale.ROOT)); if (v instanceof Boolean) out.addAll(List.of("true", "false")); }
            default -> {}
        }
        else if (a.length == 4) switch (a0) {
            case "faction" -> switch (a[1].toLowerCase(Locale.ROOT)) {
                case "add", "remove" -> { out.addAll(bots); out.addAll(players); }
                case "hostile" -> out.addAll(facs);
                case "attack", "tp" -> out.addAll(players);
                case "path" -> out.addAll(List.of("start", "stop"));
                case "kit" -> out.addAll(List.of("give-kit", "give-kit-random"));
                default -> {}
            }
            case "path" -> switch (a[1].toLowerCase(Locale.ROOT)) {
                case "loop" -> out.addAll(List.of("none", "backforth", "circle"));
                case "walk-type" -> out.addAll(List.of("walk", "sprint", "bhop"));
                case "show" -> out.addAll(List.of("true", "false"));
                case "start" -> out.addAll(bots);
                default -> {}
            }
            default -> {}
        }
        else if (a.length >= 5 && a0.equals("faction")) { if (a[1].equalsIgnoreCase("path")) out.addAll(paths); else if (a[1].equalsIgnoreCase("kit")) out.addAll(kits); else if (a[1].equalsIgnoreCase("hostile")) out.addAll(List.of("true", "false")); }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
