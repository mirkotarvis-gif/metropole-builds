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
    final List<org.bukkit.scheduler.BukkitTask> spawning = new ArrayList<>();
    PvpCmd(CosmicBots pl) { this.pl = pl; this.st = pl.store; }
    static final String G = "§a", R = "§c", Y = "§e", W = "§f", D = "§7";

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!pl.allowed(s)) { s.sendMessage(R + "You can't use bot commands."); return true; }
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) pl.loadAllowed();
        if (a.length == 0) { help(s); return true; }
        if (a[0].equalsIgnoreCase("bot-management") && a.length > 1) a = Arrays.copyOfRange(a, 1, a.length);
        try {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "spawn" -> {
                    String n = a.length > 1 ? a[1] : pl.randomName();
                    Location l = s instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
                    pl.spawn(n, l, GameMode.SURVIVAL, s, b -> { if (b != null) s.sendMessage(G + "Spawned PvP bot " + W + n); });
                }
                case "remove" -> { Bot b = need(s, a, 1); if (b != null) { pl.remove(b, "Removed"); s.sendMessage(G + "Removed " + b.name); } }
                case "removeall" -> { for (org.bukkit.scheduler.BukkitTask t : spawning) t.cancel(); spawning.clear(); pl.spawnGen++; int n = pl.removeAll(); s.sendMessage(G + "Removed " + n + " bots"); }
                case "reload" -> { st.load(); s.sendMessage(G + "Reloaded settings, factions, kits and paths"); }
                case "list" -> { s.sendMessage(Y + "Bots (" + pl.bots.size() + "):"); for (Bot b : pl.bots.values()) { Store.Faction f = st.factionOf(b.name); s.sendMessage(W + " " + b.name + D + " " + org.bukkit.ChatColor.stripColor(b.p.getDisplayName()) + " · " + b.style.name().toLowerCase(Locale.ROOT) + " · skill " + (b.skill > 0 ? b.skill : st.i("skill")) + (f != null ? " · " + f.name : "")); } }
                case "inventory" -> { Bot b = need(s, a, 1); if (b != null && s instanceof Player p) p.openInventory(b.p.getInventory()); }
                case "mass-spawn" -> {
                    int n = Integer.parseInt(a[1]); int max = st.i("max-mass-spawn");
                    if (n < 1 || n > max) { s.sendMessage(R + "1-" + max + " (change with /pvpbot settings max-mass-spawn)"); return true; }
                    Location base = s instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
                    int[] ok = {0}, bad = {0};
                    spawning.removeIf(t -> t.isCancelled() || !Bukkit.getScheduler().isQueued(t.getTaskId()));
                    for (int i = 0; i < n; i++) {
                        final int k = i;
                        spawning.add(Bukkit.getScheduler().runTaskLater(pl, () -> {
                            double an = k * 2.4, r = 2 + Math.sqrt(k) * 1.6;
                            pl.spawn(pl.randomName(), base.clone().add(Math.cos(an) * r, 0, Math.sin(an) * r), GameMode.SURVIVAL, null, b -> {
                                if (b != null) ok[0]++; else bad[0]++;
                                if (ok[0] + bad[0] == n) s.sendMessage(G + "Mass spawn done: " + W + ok[0] + G + " online" + (bad[0] > 0 ? R + ", " + bad[0] + " failed (see console)" : "") + D + " | bots online now: " + pl.bots.size());
                            });
                        }, 2L * i));
                    }
                    s.sendMessage(G + "Spawning " + n + " bots... " + D + "(/pvpbot stop-spawn to stop)");
                }
                case "stop-spawn", "stopspawn", "cancel-spawn" -> {
                    int n = 0; for (org.bukkit.scheduler.BukkitTask t : spawning) if (Bukkit.getScheduler().isQueued(t.getTaskId())) { t.cancel(); n++; }
                    spawning.clear(); pl.spawnGen++;
                    s.sendMessage(G + "Stopped spawning" + D + " (" + n + " bots cancelled, " + pl.bots.size() + " online)");
                }
                case "attack" -> { Bot b = need(s, a, 1); Entity t = entity(a[2]); if (b == null) return true; if (t == null) { s.sendMessage(R + "No target " + a[2]); return true; } b.forced = t.getUniqueId(); s.sendMessage(G + b.name + " attacks " + a[2]); }
                case "stop-attack" -> { Bot b = need(s, a, 1); if (b != null) { b.forced = null; b.revenge = null; s.sendMessage(G + b.name + " stopped attacking"); } }
                case "target", "targets" -> target(s, a);
                case "gui", "menu" -> { if (s instanceof Player p) pl.gui.open(p, 0); else s.sendMessage(R + "Players only"); }
                case "style", "styles" -> style(s, a);
                case "war" -> war(s, a);
                case "graveyard" -> {
                    String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
                    if (op.equals("clear")) { int n = pl.graveyard.size(); pl.graveyard.clear(); pl.saveGraveyard(); if (Bukkit.getPluginManager().isPluginEnabled("NickPlugin")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "randomizenick graveyard clear"); s.sendMessage(G + "Graveyard cleared (" + n + " names free)"); }
                    else if (op.equals("remove") && a.length > 2) { pl.graveyard.remove(a[2]); pl.saveGraveyard(); if (Bukkit.getPluginManager().isPluginEnabled("NickPlugin")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "randomizenick graveyard remove " + a[2]); s.sendMessage(G + a[2] + " can be used again"); }
                    else s.sendMessage(D + "☠ Graveyard (" + pl.graveyard.size() + "): " + W + (pl.graveyard.isEmpty() ? "empty" : String.join(", ", new ArrayList<>(pl.graveyard).subList(0, Math.min(80, pl.graveyard.size())))));
                }
                case "difficulty", "skill" -> difficulty(s, a);
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
    /** bot name, "all", or a faction name */
    List<Bot> pick(CommandSender s, String sel) {
        List<Bot> out = new ArrayList<>();
        if (sel.equalsIgnoreCase("all")) out.addAll(pl.bots.values());
        else if (pl.bot(sel) != null) out.add(pl.bot(sel));
        else { Store.Faction f = st.factions.get(sel.toLowerCase(Locale.ROOT)); if (f != null) for (String m : f.members) { Bot b = pl.bot(m); if (b != null) out.add(b); } }
        if (out.isEmpty()) s.sendMessage(R + "No bots match " + sel + " (use a bot name, all, or a faction)");
        return out;
    }
    void target(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage(Y + "/pvpbot target <bot|all|faction> add <player...> | remove <player> | clear | list"); return; }
        List<Bot> bs = pick(s, a[1]); if (bs.isEmpty()) return;
        String op = a[2].toLowerCase(Locale.ROOT);
        switch (op) {
            case "add", "set" -> {
                if (a.length < 4) { s.sendMessage(R + "Name at least one player"); return; }
                for (Bot b : bs) { if (op.equals("set")) b.targets.clear(); for (int i = 3; i < a.length; i++) if (!a[i].equalsIgnoreCase(b.name)) b.targets.add(a[i]); }
                s.sendMessage(G + bs.size() + " bot(s) now hunt: " + W + String.join(", ", Arrays.copyOfRange(a, 3, a.length)));
            }
            case "remove" -> { for (Bot b : bs) b.targets.removeIf(x -> x.equalsIgnoreCase(a[3])); s.sendMessage(G + "Removed " + a[3] + " from " + bs.size() + " bot(s)"); }
            case "clear" -> { for (Bot b : bs) { b.targets.clear(); b.forced = null; b.revenge = null; } s.sendMessage(G + "Cleared targets of " + bs.size() + " bot(s)"); }
            case "list" -> { for (Bot b : bs) s.sendMessage(Y + b.name + ": " + W + (b.targets.isEmpty() ? "none" : String.join(", ", b.targets))); }
            default -> { // shortcut: /pvpbot target <bot> <player...>
                for (Bot b : bs) for (int i = 2; i < a.length; i++) if (!a[i].equalsIgnoreCase(b.name)) b.targets.add(a[i]);
                s.sendMessage(G + bs.size() + " bot(s) now hunt: " + W + String.join(", ", Arrays.copyOfRange(a, 2, a.length)));
            }
        }
        pl.saveBots();
    }
    void style(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage(Y + "/pvpbot style <bot|all|faction> <style|random>");
            for (Style v : Style.values()) s.sendMessage(W + " " + v.name().toLowerCase(Locale.ROOT) + D + " - " + v.desc);
            return;
        }
        List<Bot> bs = pick(s, a[1]); if (bs.isEmpty()) return;
        Style v = Style.of(a[2]);
        if (v == null && !a[2].equalsIgnoreCase("random")) { s.sendMessage(R + "Unknown style " + a[2]); return; }
        for (Bot b : bs) b.style = v != null ? v : Style.random();
        pl.saveBots();
        s.sendMessage(G + bs.size() + " bot(s) now play " + W + (v != null ? v.name().toLowerCase(Locale.ROOT) : "random styles"));
    }
    /** /pvpbot war <A> <B> | stop <A> <B> | spawn <A> <B> <count each> [kitA] [kitB] */
    void war(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage(Y + "/pvpbot war <teamA> <teamB>" + D + " start a war"); s.sendMessage(Y + "/pvpbot war stop <teamA> <teamB>"); s.sendMessage(Y + "/pvpbot war spawn <teamA> <teamB> <bots each> [kitA] [kitB]" + D + " (look at a spot, teams spawn on both sides)"); return; }
        if (a[1].equalsIgnoreCase("stop") && a.length >= 4) {
            Store.Faction x = st.factions.get(a[2].toLowerCase(Locale.ROOT)), y = st.factions.get(a[3].toLowerCase(Locale.ROOT));
            if (x == null || y == null) { s.sendMessage(R + "No such team"); return; }
            x.hostile.remove(y.name.toLowerCase(Locale.ROOT)); y.hostile.remove(x.name.toLowerCase(Locale.ROOT)); st.saveFactions();
            for (Bot b : pl.bots.values()) { b.revenge = null; b.forced = null; }
            Bukkit.broadcastMessage("§7§l☮ §f" + x.name + " §7and §f" + y.name + " §7are at peace.");
            return;
        }
        boolean spawn = a[1].equalsIgnoreCase("spawn");
        int o = spawn ? 2 : 1;
        if (a.length < o + 2) { s.sendMessage(R + "Name two teams"); return; }
        String an = a[o], bn = a[o + 1];
        Store.Faction x = st.factions.computeIfAbsent(an.toLowerCase(Locale.ROOT), k -> new Store.Faction(an));
        Store.Faction y = st.factions.computeIfAbsent(bn.toLowerCase(Locale.ROOT), k -> new Store.Faction(bn));
        x.hostile.add(y.name.toLowerCase(Locale.ROOT)); y.hostile.add(x.name.toLowerCase(Locale.ROOT)); st.saveFactions();
        if (spawn) {
            if (!(s instanceof Player p)) { s.sendMessage(R + "Players only"); return; }
            int n = a.length > o + 2 ? Math.max(1, Math.min((int) st.d("max-mass-spawn"), Integer.parseInt(a[o + 2]))) : 5;
            String ka = a.length > o + 3 ? a[o + 3] : null, kb = a.length > o + 4 ? a[o + 4] : ka;
            org.bukkit.block.Block look = p.getTargetBlockExact(80);
            Location mid = look != null ? look.getLocation().add(0.5, 1, 0.5) : p.getLocation();
            org.bukkit.util.Vector side = p.getLocation().getDirection().setY(0);
            if (side.lengthSquared() < 0.01) side = new org.bukkit.util.Vector(1, 0, 0);
            side.normalize().rotateAroundY(Math.PI / 2).multiply(12);
            spawnTeam(s, x, mid.clone().add(side), n, ka);
            spawnTeam(s, y, mid.clone().subtract(side), n, kb);
        }
        Bukkit.broadcastMessage("§4§l⚔ WAR §c" + x.name + " §7vs §c" + y.name);
        for (Player pp : Bukkit.getOnlinePlayers()) pp.playSound(pp.getLocation(), org.bukkit.Sound.EVENT_RAID_HORN, 1f, 1f);
    }
    void spawnTeam(CommandSender s, Store.Faction f, Location at, int n, String kit) {
        ItemStack[] k = kit == null ? null : st.kits.get(kit.toLowerCase(Locale.ROOT));
        if (kit != null && k == null) s.sendMessage(R + "No kit " + kit + D + " (spawning without it)");
        for (int i = 0; i < n; i++) {
            final int idx = i;
            spawning.add(Bukkit.getScheduler().runTaskLater(pl, () -> {
                double an = idx * 2.4, r = 1.5 + Math.sqrt(idx) * 1.4;
                pl.spawn(pl.randomName(), at.clone().add(Math.cos(an) * r, 0, Math.sin(an) * r), GameMode.SURVIVAL, null, b -> {
                    if (b == null) return;
                    for (Store.Faction o2 : st.factions.values()) o2.members.removeIf(m -> m.equalsIgnoreCase(b.name));
                    f.members.add(b.name); st.saveFactions();
                    if (k != null) giveKit(b.p, k);
                });
            }, 2L * i));
        }
    }
    static int level(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "noob", "easy", "lt5" -> 15; case "lt4" -> 30; case "normal", "medium", "lt3" -> 50; case "ht3" -> 60;
            case "hard", "lt2" -> 70; case "ht2" -> 80; case "lt1" -> 90; case "ht1", "pro", "max" -> 100; case "marlowww", "marlow", "marloww", "grandmaster", "god" -> 101;
            default -> Integer.parseInt(v);
        };
    }
    void difficulty(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage(Y + "/pvpbot difficulty <bot|all|faction|default> <1-100|easy|normal|hard|ht1|lt5..ht1|reset>");
            s.sendMessage(D + "Default for new bots: " + W + st.i("skill"));
            return;
        }
        if (a[1].equalsIgnoreCase("default")) { String err = st.setSetting("skill", String.valueOf(level(a[2]))); s.sendMessage(err != null ? R + err : G + "Default difficulty: " + W + st.i("skill")); return; }
        List<Bot> bs = pick(s, a[1]); if (bs.isEmpty()) return;
        int v = a[2].equalsIgnoreCase("reset") ? -1 : level(a[2]);
        if (v != -1 && (v < 1 || v > 101)) { s.sendMessage(R + "1-100, or marlowww"); return; }
        for (Bot b : bs) b.skill = v;
        pl.saveBots();
        s.sendMessage(G + "Difficulty of " + bs.size() + " bot(s): " + W + (v == -1 ? "default (" + st.i("skill") + ")" : v == 101 ? "§d§lMARLOWWW §7(combat grandmaster)" : v));
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
            case "give" -> {
                ItemStack it = null;
                if (a.length > 3) { org.bukkit.Material m = org.bukkit.Material.matchMaterial(a[3]); if (m == null || !m.isItem()) { s.sendMessage(R + "Unknown item " + a[3]); return; } it = new ItemStack(m, a.length > 4 ? Math.max(1, Integer.parseInt(a[4])) : 1); }
                else if (s instanceof Player p) it = p.getInventory().getItemInMainHand().clone();
                if (it == null || it.getType().isAir()) { s.sendMessage(R + "/pvpbot faction give <faction> <item> [amount] (or hold the item)"); return; } int n = 0; for (String m : f.members) { Bot b = pl.bot(m); if (b != null) { b.p.getInventory().addItem(it.clone()); n++; } } s.sendMessage(G + "Gave " + it.getType() + " x" + it.getAmount() + " to " + n + " bots"); }
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
                String x = a[o + 1], y = a[o + 2];
                String kn = st.kits.containsKey(x.toLowerCase(Locale.ROOT)) ? x : y, pn = kn == x ? y : x;
                ItemStack[] k = st.kits.get(kn.toLowerCase(Locale.ROOT)); if (k == null) { s.sendMessage(R + "No kit " + kn); return; }
                Player t = Bukkit.getPlayerExact(pn); if (t == null) { s.sendMessage(R + "No player/bot " + pn); return; }
                giveKit(t, k); s.sendMessage(G + "Gave " + kn + " to " + t.getName());
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
        for (int i = 0; i < spec.length; i++) {
            String[] q = spec[i].split(":"); String kn = q[0].toLowerCase(Locale.ROOT);
            if (!st.kits.containsKey(kn)) continue;
            double w = 1;
            if (q.length > 1) w = Double.parseDouble(q[1].replace("%", ""));
            else if (i + 1 < spec.length && spec[i + 1].replace("%", "").matches("[0-9.]+")) w = Double.parseDouble(spec[++i].replace("%", ""));
            ks.add(kn); ws.add(w); tot += w;
        }
        if (ks.isEmpty()) return;
        double r = pl.rnd.nextDouble() * tot;
        for (int i = 0; i < ks.size(); i++) { r -= ws.get(i); if (r <= 0) { giveKit(p, st.kits.get(ks.get(i))); return; } }
        giveKit(p, st.kits.get(ks.get(ks.size() - 1)));
    }

    // ================================================================ paths
    void path(CommandSender s, String[] a) {
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) { s.sendMessage(Y + "Paths: " + W + (st.paths.isEmpty() ? "none" : String.join(", ", st.paths.keySet()))); return; }
        if (sub.equals("stop-all")) { int n = 0; for (Bot b : pl.bots.values()) if (b.path != null && (a.length < 3 || a[2].equalsIgnoreCase(b.path))) { b.path = null; n++; } s.sendMessage(G + n + " bots stopped walking"); return; }
        if (sub.equals("stop")) { Bot b = need(s, a, 2); if (b != null) { b.path = null; s.sendMessage(G + b.name + " stopped"); } return; }
        if (sub.equals("start") && a.length > 3 && !st.paths.containsKey(a[2].toLowerCase(Locale.ROOT)) && st.paths.containsKey(a[3].toLowerCase(Locale.ROOT))) { String t = a[2]; a[2] = a[3]; a[3] = t; }
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
            case "loop" -> { String v = a[3].toLowerCase(Locale.ROOT); if (v.equals("true")) v = "circle"; if (v.equals("false")) v = "none";
                if (!List.of("none", "backforth", "circle").contains(v)) { s.sendMessage(R + "true|false|backforth|circle"); return; } pd.loop = v; s.sendMessage(G + "Loop: " + v); }
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
        String k = a[1].toLowerCase(Locale.ROOT);
        if (a.length == 2) { Object v = st.set.get(k);
            if (v instanceof Boolean bo) { st.setSetting(k, String.valueOf(!bo)); s.sendMessage(G + k + " = " + (!bo ? "on" : R + "off")); hint(s, k); return; }
            s.sendMessage(v == null ? R + "Unknown setting " + k + D + " (/pvpbot settings to list them)" : Y + k + " = " + W + Store.fmt(((Number) v).doubleValue()) + D + "  (" + Store.fmt(Store.RANGE.get(k)[0]) + "-" + Store.fmt(Store.RANGE.get(k)[1]) + ", default " + Store.fmt(((Number) Store.DEFAULTS.get(k)).doubleValue()) + ")"); return; }
        String v = a[2].toLowerCase(Locale.ROOT);
        if (v.equals("on") || v.equals("yes") || v.equals("enable")) v = "true";
        if (v.equals("off") || v.equals("no") || v.equals("disable")) v = "false";
        if (v.equals("default") || v.equals("reset")) v = Store.DEFAULTS.containsKey(k) ? (Store.DEFAULTS.get(k) instanceof Boolean bo ? String.valueOf(bo) : Store.fmt(((Number) Store.DEFAULTS.get(k)).doubleValue())) : v;
        String err = st.setSetting(k, v);
        if (err != null) { s.sendMessage(R + err); return; }
        Object now = st.set.get(k);
        s.sendMessage(G + k + " = " + W + (now instanceof Boolean bo ? (bo ? "on" : R + "off") : Store.fmt(((Number) now).doubleValue())));
        hint(s, k);
    }

    void hint(CommandSender s, String k) {
        if (k.startsWith("target-") && !st.b("auto-target"))
            s.sendMessage(D + "Note: target-players/bots/mobs only matter when " + W + "auto-target" + D + " is on (/pvpbot settings auto-target on)");
        if (k.equals("auto-target") && st.b("auto-target")) s.sendMessage(D + "Bots now attack anyone nearby that matches target-players / target-bots / target-mobs");
    }
    void help(CommandSender s) {
        s.sendMessage(Y + "§lPvP Bots");
        s.sendMessage(D + "/pvpbot spawn [name] | remove <name> | removeall | list | inventory <name> | mass-spawn <n> | stop-spawn | reload");
        s.sendMessage(D + "/pvpbot attack <bot> <target> | stop-attack <bot>");
        s.sendMessage(D + "/pvpbot gui" + W + " settings menu");
        s.sendMessage(D + "/pvpbot war <teamA> <teamB> | war stop <A> <B> | war spawn <A> <B> <count> [kitA] [kitB]");
        s.sendMessage(D + "/pvpbot style <bot|all|faction> <style|random>  |  /pvpbot graveyard [clear|remove <name>]");
        s.sendMessage(D + "/pvpbot target <bot|all|faction> add|set|remove|clear|list [players...]");
        s.sendMessage(D + "/pvpbot difficulty <bot|all|faction|default> <1-100|easy|normal|hard|ht1|marlowww|reset>");
        s.sendMessage(D + "/pvpbot faction list|create|delete|info|add|remove|add-near|add-all|hostile|attack|give|tp|path|kit");
        s.sendMessage(D + "/pvpbot create-kit|delete-kit|kits|give-kit|give-kit-near|give-kit-near-random");
        s.sendMessage(D + "/pvpbot path create|delete|add-point|remove-point|clear|list|info|loop|walk-type|show|start|stop|distribute|start-near|stop-all");
        s.sendMessage(D + "/pvpbot settings [name] [value]");
    }

    // ================================================================ tab completion
    @Override public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!pl.allowed(s)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        List<String> bots = names(), facs = new ArrayList<>(st.factions.keySet()), kits = new ArrayList<>(st.kits.keySet()), paths = new ArrayList<>(st.paths.keySet());
        List<String> players = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
        if (a.length > 1 && a[0].equalsIgnoreCase("bot-management")) { if (a.length == 2) { out.addAll(List.of("list", "inventory", "mass-spawn", "stop-spawn", "attack", "stop-attack")); String l = a[1].toLowerCase(Locale.ROOT); out.removeIf(x -> !x.startsWith(l)); return out; } a = Arrays.copyOfRange(a, 1, a.length); }
        if (a.length > 1 && a[0].equalsIgnoreCase("kit")) { if (a.length == 2) { out.addAll(List.of("create-kit", "delete-kit", "kits", "give-kit", "give-kit-near", "give-kit-near-random")); String l = a[1].toLowerCase(Locale.ROOT); out.removeIf(x -> !x.startsWith(l)); return out; } a = Arrays.copyOfRange(a, 1, a.length); }
        String a0 = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 1) out.addAll(List.of("bot-management", "kit", "spawn", "remove", "removeall", "reload", "list", "inventory", "gui", "war", "style", "graveyard", "mass-spawn", "stop-spawn", "attack", "stop-attack", "target", "difficulty", "faction", "create-kit", "delete-kit", "kits", "give-kit", "give-kit-near", "give-kit-near-random", "path", "settings"));
        else if (a.length == 2) switch (a0) {
            case "remove", "inventory", "attack", "stop-attack" -> out.addAll(bots);
            case "faction" -> out.addAll(List.of("list", "create", "delete", "info", "add", "remove", "add-near", "add-all", "hostile", "attack", "give", "tp", "path", "kit"));
            case "delete-kit", "give-kit-near" -> out.addAll(kits);
            case "give-kit" -> { out.addAll(kits); out.addAll(players); }
            case "path" -> out.addAll(List.of("create", "delete", "add-point", "remove-point", "clear", "list", "info", "loop", "walk-type", "show", "start", "stop", "distribute", "start-near", "stop-all"));
            case "settings" -> out.addAll(st.set.keySet());
            default -> {}
        }
        else if (a.length == 3) switch (a0) {
            case "attack" -> out.addAll(players);
            case "faction" -> out.addAll(facs);
            case "give-kit" -> { out.addAll(kits); out.addAll(players); }
            case "path" -> { if (a[1].equalsIgnoreCase("stop")) out.addAll(bots); else out.addAll(paths); }
            case "settings" -> { Object v = st.set.get(a[1].toLowerCase(Locale.ROOT)); if (v instanceof Boolean) out.addAll(List.of("on", "off")); else if (v instanceof Number nn) { out.add(Store.fmt(nn.doubleValue())); out.add("default"); } }
            default -> {}
        }
        else if (a.length == 4) switch (a0) {
            case "faction" -> { switch (a[1].toLowerCase(Locale.ROOT)) {
                case "add", "remove" -> { out.addAll(bots); out.addAll(players); }
                case "hostile" -> out.addAll(facs);
                case "attack", "tp" -> out.addAll(players);
                case "path" -> out.addAll(List.of("start", "stop"));
                case "kit" -> out.addAll(List.of("give-kit", "give-kit-random"));
                default -> {}
            } }
            case "path" -> { switch (a[1].toLowerCase(Locale.ROOT)) {
                case "loop" -> out.addAll(List.of("none", "backforth", "circle"));
                case "walk-type" -> out.addAll(List.of("walk", "sprint", "bhop"));
                case "show" -> out.addAll(List.of("true", "false"));
                case "start" -> out.addAll(bots);
                default -> {}
            } }
            default -> {}
        }
        else if (a.length >= 5 && a0.equals("faction")) { if (a[1].equalsIgnoreCase("path")) out.addAll(paths); else if (a[1].equalsIgnoreCase("kit")) out.addAll(kits); else if (a[1].equalsIgnoreCase("hostile")) out.addAll(List.of("true", "false")); }
        if (a0.equals("style")) { out.clear(); if (a.length == 2) { out.addAll(bots); out.add("all"); out.addAll(facs); } else if (a.length == 3) { for (Style v : Style.values()) out.add(v.name().toLowerCase(Locale.ROOT)); out.add("random"); } }
        if (a0.equals("war")) { out.clear(); if (a.length == 2) { out.add("stop"); out.add("spawn"); out.addAll(facs); } else if (a.length <= 4) out.addAll(facs); else out.addAll(st.kits.keySet()); }
        if (a0.equals("graveyard") && a.length == 2) { out.clear(); out.addAll(List.of("list", "clear", "remove")); }
        if (a0.equals("target") || a0.equals("difficulty")) {
            out.clear();
            if (a.length == 2) { out.addAll(bots); out.add("all"); out.addAll(facs); if (a0.equals("difficulty")) out.add("default"); }
            else if (a.length == 3) out.addAll(a0.equals("target") ? List.of("add", "set", "remove", "clear", "list") : List.of("easy", "normal", "hard", "ht1", "marlowww", "lt5", "lt4", "lt3", "ht3", "lt2", "ht2", "lt1", "reset", "50", "100"));
            else if (a0.equals("target")) out.addAll(players);
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
