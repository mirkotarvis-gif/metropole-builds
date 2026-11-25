package me.cosmic.fake;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

public final class CosmicBots extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    final Map<String, Bot> bots = new LinkedHashMap<>();
    final Set<String> pending = new HashSet<>();
    int spawnGen;
    Store store; Brain brain; PvpCmd pvp;
    long ticks;
    final Random rnd = new Random();

    @Override public void onEnable() {
        store = new Store(getDataFolder()); store.load(); store.saveAll();
        brain = new Brain(this); pvp = new PvpCmd(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("player").setExecutor(this); getCommand("player").setTabCompleter(this);
        getCommand("pvpbot").setExecutor(pvp); getCommand("pvpbot").setTabCompleter(pvp);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskLater(this, this::restore, 60L);
        getLogger().info("CosmicBots 2.3 ready: /player and /pvpbot");
    }
    @Override public void onDisable() { saveBots(); for (Bot b : new ArrayList<>(bots.values())) Nms.remove(b.nms, "Server stopping"); bots.clear(); }

    /** a player by real name, or by the name they show (nicknames) */
    Player findPlayer(String n) {
        Player p = Bukkit.getPlayerExact(n); if (p != null) return p;
        for (Player o : Bukkit.getOnlinePlayers()) {
            String dn = ChatColor.stripColor(o.getDisplayName()), ln = o.getPlayerListName() == null ? null : ChatColor.stripColor(o.getPlayerListName());
            if (n.equalsIgnoreCase(dn) || n.equalsIgnoreCase(ln)) return o;
        }
        return null;
    }
    boolean isBot(Player p) { return bots.containsKey(p.getName().toLowerCase(Locale.ROOT)); }
    Bot bot(String n) { return n == null ? null : bots.get(n.toLowerCase(Locale.ROOT)); }

    // ================================================================= spawn / remove / persistence
    /** spawn a bot; done is called on the main thread with the bot or null */
    void spawn(String name, Location where, GameMode gm, CommandSender tell, Consumer<Bot> done) { spawn(name, where, gm, tell, done, true); }
    void spawn(String name, Location where, GameMode gm, CommandSender tell, Consumer<Bot> done, boolean fresh) {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) { if (tell != null) tell.sendMessage("§cNames are 3-16 letters, numbers or _"); if (done != null) done.accept(null); return; }
        if (bot(name) != null || Bukkit.getPlayerExact(name) != null || pending.contains(name.toLowerCase(Locale.ROOT))) { if (tell != null) tell.sendMessage("§c" + name + " is already online."); if (done != null) done.accept(null); return; }
        if (store.b("safe-spawn")) { where.getChunk().load(); where = safe(where.clone().add(rnd.nextDouble() * 2 - 1, 0, rnd.nextDouble() * 2 - 1)); }
        final Location at = where;
        pending.add(name.toLowerCase(Locale.ROOT));
        final int gen = spawnGen;
        Runnable join = null;
        Consumer<Object> finish = prof -> Bukkit.getScheduler().runTask(this, () -> {
            pending.remove(name.toLowerCase(Locale.ROOT));
            if (gen != spawnGen) { if (done != null) done.accept(null); return; }
            try {
                Object[] r = Nms.join(name, prof, at);
                Bot b = new Bot(name, r[0], (Player) r[1]);
                b.spawnedAt = ticks;
                b.p.setGameMode(gm == null ? GameMode.SURVIVAL : gm);
                bots.put(name.toLowerCase(Locale.ROOT), b);
                saveBots();
                // give fresh bots a random real-looking nickname + skin (NickPlugin)
                if (fresh && store.b("random-nick") && Bukkit.getPluginManager().isPluginEnabled("NickPlugin"))
                    Bukkit.getScheduler().runTaskLater(this, () -> { if (b.p.isOnline()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "randomizenick " + b.name); }, 5L);
                if (done != null) done.accept(b);
            } catch (Throwable t) {
                if (tell != null) tell.sendMessage("§cCouldn't spawn " + name + ": " + t);
                getLogger().severe("spawn " + name + " failed: " + t); t.printStackTrace();
                if (done != null) done.accept(null);
            }
        });
        if (store.b("profile-lagg-fix")) finish.accept(null);
        else Bukkit.getScheduler().runTaskAsynchronously(this, () -> finish.accept(Nms.profile(name)));
    }
    Location safe(Location l) {
        World w = l.getWorld();
        for (int dy = 0; dy <= 12; dy++) for (int sgn : new int[]{-1, 1}) {
            int y = l.getBlockY() + dy * sgn; if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) continue;
            Block b = w.getBlockAt(l.getBlockX(), y, l.getBlockZ());
            if (ok(b)) { Location s = l.clone(); s.setY(y); return s; }
            if (dy == 0) break;
        }
        int y = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) + 1;
        Location s = l.clone(); s.setY(y); return s;
    }
    static boolean ok(Block b) {
        Block below = b.getRelative(0, -1, 0);
        return b.isPassable() && !b.isLiquid() && b.getType() != Material.COBWEB && b.getRelative(0, 1, 0).isPassable() && !b.getRelative(0, 1, 0).isLiquid()
                && below.getType().isSolid() && below.getType() != Material.MAGMA_BLOCK && below.getType() != Material.CACTUS;
    }
    void remove(Bot b, String why) {
        b.removing = true;
        bots.remove(b.name.toLowerCase(Locale.ROOT));
        if (store.b("clear-on-remove")) { b.p.getInventory().clear(); }
        Nms.remove(b.nms, why);
        saveBots();
    }
    /** remove every bot, including stuck/ghost fake players the list lost track of */
    int removeAll() {
        int n = 0;
        for (Bot b : new ArrayList<>(bots.values())) { remove(b, "Removed"); n++; }
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (!Nms.isFake(p)) continue;
            try { Nms.remove(Nms.invoke(p, "getHandle"), "Removed"); n++; } catch (Throwable ignored) {}
        }
        bots.clear(); saveBots();
        return n;
    }
    void saveBots() {
        YamlConfiguration y = new YamlConfiguration();
        for (Bot b : bots.values()) { y.set(b.name + ".loc", Store.str(b.p.getLocation())); y.set(b.name + ".home", Store.str(b.home)); if (b.path != null) y.set(b.name + ".path", b.path); y.set(b.name + ".mode", b.p.getGameMode().name()); if (b.skill > 0) y.set(b.name + ".skill", b.skill); if (!b.targets.isEmpty()) y.set(b.name + ".targets", new ArrayList<>(b.targets)); if (b.forced != null) y.set(b.name + ".forced", b.forced.toString()); }
        try { y.save(new File(getDataFolder(), "bots.yml")); } catch (Exception ignored) {}
    }
    void restore() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "bots.yml"));
        int i = 0;
        for (String n : y.getKeys(false)) {
            Location l = Store.parse(y.getString(n + ".loc", "")); if (l == null) continue;
            Location home = Store.parse(y.getString(n + ".home", "")); String path = y.getString(n + ".path"); GameMode gm = GameMode.valueOf(y.getString(n + ".mode", "SURVIVAL")); int sk = y.getInt(n + ".skill", -1); List<String> tg = y.getStringList(n + ".targets"); String fo = y.getString(n + ".forced");
            Bukkit.getScheduler().runTaskLater(this, () -> spawn(n, l, gm, null, b -> { if (b != null) { if (home != null) b.home = home; b.path = path; b.skill = sk; b.targets.addAll(tg); try { if (fo != null) b.forced = UUID.fromString(fo); } catch (Exception ignored) {} } }, false), 5L * i++);
        }
        if (i > 0) getLogger().info("Restoring " + i + " bots");
    }

    // ================================================================= tick
    private void tick() {
        ticks++;
        double speed = store.d("move-speed");
        for (Bot b : new ArrayList<>(bots.values())) {
            try {
                if (!b.p.isOnline()) { bots.remove(b.name.toLowerCase(Locale.ROOT)); continue; }
                if (ticks % 20 == 0) Nms.keepAlive(b.nms);
                if (b.p.isDead()) continue;
                boolean manual = !b.acts.isEmpty() || b.manualForward != 0 || b.manualStrafe != 0;
                if (manual) { manual(b); b.forward = b.manualForward; b.strafe = b.manualStrafe; b.jump = b.acts.containsKey("jump") && !b.acts.get("jump").once; }
                else brain.tick(b, ticks);
                float sneak = b.p.isSneaking() ? 0.3f : 1f;
                Nms.tick(b.nms, (float) (b.forward * speed * sneak), (float) (b.strafe * speed * sneak), b.jump, ticks);
            } catch (Throwable t) { getLogger().warning("bot " + b.name + ": " + t); }
        }
        if (ticks % 10 == 0) for (Store.PathDef pd : store.paths.values()) if (pd.show) showPath(pd);
    }
    void showPath(Store.PathDef pd) {
        for (int i = 0; i < pd.points.size(); i++) {
            Location a = pd.points.get(i); a.getWorld().spawnParticle(Particle.END_ROD, a.clone().add(0, 0.5, 0), 3, 0.1, 0.3, 0.1, 0);
            if (i + 1 < pd.points.size() || pd.loop.equals("circle")) {
                Location c = pd.points.get((i + 1) % pd.points.size()); if (!c.getWorld().equals(a.getWorld())) continue;
                double d = a.distance(c); for (double s = 0; s < d; s += 0.7) a.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, a.clone().add(c.clone().subtract(a).toVector().normalize().multiply(s)).add(0, 0.3, 0), 1, 0, 0, 0, 0);
            }
        }
    }

    // ================================================================= manual (/player) actions
    void manual(Bot b) {
        for (Map.Entry<String, Bot.Act> e : new ArrayList<>(b.acts.entrySet())) {
            Bot.Act ac = e.getValue();
            if (e.getKey().equals("attack") && b.mining != null) { if (mine(b) && ac.once) b.acts.remove("attack"); continue; }
            if (--ac.left > 0) continue;
            ac.left = ac.interval;
            try {
                switch (e.getKey()) {
                    case "attack" -> { attackOnce(b); if (ac.once && b.mining == null) b.acts.remove("attack"); }
                    case "use" -> { Nms.use(b.nms, b.p); if (ac.once) b.acts.remove("use"); }
                    case "jump" -> { if (ac.once) { if (Nms.onGround(b.nms)) Nms.jumpNow(b.nms); b.acts.remove("jump"); } }
                    case "drop" -> { b.p.dropItem(false); if (ac.once) b.acts.remove("drop"); }
                    case "dropstack" -> { b.p.dropItem(true); if (ac.once) b.acts.remove("dropstack"); }
                    default -> {}
                }
            } catch (Throwable t) { getLogger().warning("action " + e.getKey() + ": " + t); b.acts.remove(e.getKey()); }
        }
    }
    void attackOnce(Bot b) {
        Location eye = b.p.getEyeLocation();
        RayTraceResult r = eye.getWorld().rayTraceEntities(eye, eye.getDirection(), 3.0, e -> !e.equals(b.p));
        if (r != null && r.getHitEntity() != null) { b.p.attack(r.getHitEntity()); b.p.swingMainHand(); return; }
        Block t = b.p.getTargetBlockExact(5);
        if (t != null && !t.isEmpty() && !t.isLiquid()) { b.mining = t; b.progress = 0; return; }
        b.p.swingMainHand();
    }
    boolean mine(Bot b) {
        Block t = b.p.getTargetBlockExact(5);
        if (t == null || b.mining == null || !t.getLocation().equals(b.mining.getLocation()) || t.isEmpty()) { b.mining = null; return false; }
        b.progress += t.getBreakSpeed(b.p);
        if (ticks % 4 == 0) b.p.swingMainHand();
        if (b.progress >= 1f) { b.p.breakBlock(t); b.mining = null; b.progress = 0; return true; }
        return false;
    }

    // ================================================================= /player (Carpet style)
    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("list")) { s.sendMessage("§eBots: §f" + (bots.isEmpty() ? "none" : String.join(", ", bots.keySet()))); return true; }
        String name = a[0];
        if (a.length < 2) { s.sendMessage("§c/player " + name + " <spawn|kill|stop|attack|use|jump|look|turn|move|sneak|unsneak|sprint|unsprint|drop|dropStack|swapHands|hotbar>"); return true; }
        String act = a[1].toLowerCase(Locale.ROOT);
        if (act.equals("spawn")) { spawnCmd(s, name, Arrays.copyOfRange(a, 2, a.length)); return true; }
        Bot b = bot(name);
        if (b == null) { s.sendMessage("§c" + name + " is not a bot."); return true; }
        try {
            switch (act) {
                case "kill" -> { remove(b, "Killed"); s.sendMessage("§aRemoved " + b.name); }
                case "stop" -> { b.acts.clear(); b.manualForward = b.manualStrafe = 0; b.mining = null; b.p.setSneaking(false); b.p.setSprinting(false); s.sendMessage("§a" + b.name + " stopped."); }
                case "attack", "use", "jump", "drop", "dropstack" -> { b.acts.put(act, mode(a, 2)); if (act.equals("attack")) b.mining = null; }
                case "look" -> look(b, a);
                case "turn" -> { String d = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "back"; rot(b, b.p.getLocation().getYaw() + (d.equals("left") ? -90 : d.equals("right") ? 90 : 180), b.p.getLocation().getPitch()); }
                case "move" -> { String d = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "stop"; b.manualForward = d.equals("forward") ? 1 : d.equals("backward") ? -1 : 0; b.manualStrafe = d.equals("left") ? 1 : d.equals("right") ? -1 : 0; }
                case "sneak" -> b.p.setSneaking(true);
                case "unsneak" -> b.p.setSneaking(false);
                case "sprint" -> b.p.setSprinting(true);
                case "unsprint" -> b.p.setSprinting(false);
                case "swaphands" -> { PlayerInventory inv = b.p.getInventory(); ItemStack m = inv.getItemInMainHand(), o = inv.getItemInOffHand(); inv.setItemInMainHand(o); inv.setItemInOffHand(m); }
                case "hotbar" -> b.p.getInventory().setHeldItemSlot(Math.max(1, Math.min(9, Integer.parseInt(a[2]))) - 1);
                default -> s.sendMessage("§cUnknown action " + act);
            }
        } catch (Exception e) { s.sendMessage("§cThat didn't work: " + e.getMessage()); }
        return true;
    }
    Bot.Act mode(String[] a, int i) {
        if (a.length <= i) return new Bot.Act(true, 1);
        String m = a[i].toLowerCase(Locale.ROOT);
        if (m.equals("continuous")) return new Bot.Act(false, 1);
        if (m.equals("interval") && a.length > i + 1) return new Bot.Act(false, Integer.parseInt(a[i + 1]));
        return new Bot.Act(true, 1);
    }
    void look(Bot b, String[] a) {
        if (a.length < 3) return;
        Location l = b.p.getLocation();
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "north" -> rot(b, 180, 0); case "south" -> rot(b, 0, 0);
            case "east" -> rot(b, -90, 0); case "west" -> rot(b, 90, 0);
            case "up" -> rot(b, l.getYaw(), -90); case "down" -> rot(b, l.getYaw(), 90);
            case "at" -> brain.face(b, new Location(l.getWorld(), Double.parseDouble(a[3]) + 0.5, Double.parseDouble(a[4]) + 0.5, Double.parseDouble(a[5]) + 0.5), 360);
            default -> rot(b, Float.parseFloat(a[2]), a.length > 3 ? Float.parseFloat(a[3]) : 0);
        }
    }
    void spawnCmd(CommandSender s, String name, String[] a) {
        Location at = s instanceof Player pl ? pl.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
        World world = at.getWorld(); double x = at.getX(), y = at.getY(), z = at.getZ(); float yaw = at.getYaw(), pitch = at.getPitch(); GameMode gm = GameMode.SURVIVAL;
        try {
            for (int i = 0; i < a.length; i++) switch (a[i].toLowerCase(Locale.ROOT)) {
                case "at" -> { x = coord(a[++i], x); y = coord(a[++i], y); z = coord(a[++i], z); }
                case "facing" -> { yaw = Float.parseFloat(a[++i]); pitch = Float.parseFloat(a[++i]); }
                case "in" -> { String v = a[++i]; try { gm = GameMode.valueOf(v.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { World w = Bukkit.getWorld(v); if (w != null) world = w; } }
                case "survival", "creative", "adventure", "spectator" -> gm = GameMode.valueOf(a[i].toUpperCase(Locale.ROOT));
                default -> {}
            }
        } catch (Exception e) { s.sendMessage("§cUsage: /player " + name + " spawn [at x y z] [facing yaw pitch] [in gamemode]"); return; }
        Location where = new Location(world, x, y, z, yaw, pitch);
        s.sendMessage("§7Spawning " + name + "...");
        spawn(name, where, gm, s, b -> { if (b != null) s.sendMessage("§a" + name + " joined."); });
    }
    static void rot(Bot b, float yaw, float pitch) { Nms.rotate(b.nms, yaw, pitch); }
    static double coord(String v, double base) { return v.startsWith("~") ? base + (v.length() > 1 ? Double.parseDouble(v.substring(1)) : 0) : Double.parseDouble(v); }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { out.addAll(bots.keySet()); out.add("list"); }
        else if (a.length == 2) out.addAll(List.of("spawn", "kill", "stop", "attack", "use", "jump", "look", "turn", "move", "sneak", "unsneak", "sprint", "unsprint", "drop", "dropStack", "swapHands", "hotbar"));
        else if (a.length == 3) switch (a[1].toLowerCase(Locale.ROOT)) {
            case "attack", "use", "jump", "drop", "dropstack" -> out.addAll(List.of("once", "continuous", "interval"));
            case "look" -> out.addAll(List.of("north", "south", "east", "west", "up", "down", "at"));
            case "turn" -> out.addAll(List.of("left", "right", "back"));
            case "move" -> out.addAll(List.of("forward", "backward", "left", "right", "stop"));
            case "spawn" -> out.addAll(List.of("at", "facing", "in"));
            default -> {}
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ================================================================= events
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity src = e.getDamager();
        if (src instanceof Projectile pr) { ProjectileSource ps = pr.getShooter(); if (ps instanceof Entity en) src = en; }
        if (!(e.getEntity() instanceof Player victim)) return;
        if (src instanceof Player att && !store.b("friendly-fire") && store.sameFaction(att.getName(), victim.getName()) && !att.equals(victim)) { e.setCancelled(true); return; }
        Bot b = bot(victim.getName());
        // teamwork: bots on the same team don't hurt each other (sweeps, arrows, carts) unless one was told to attack the other
        if (b != null && src instanceof Player att && !att.equals(victim) && store.b("teamwork") && !store.b("target-bots")) {
            Bot ab = bot(att.getName());
            if (ab != null && brain.team(ab, b) && !victim.getUniqueId().equals(ab.forced) && ab.targets.stream().noneMatch(x -> x.equalsIgnoreCase(victim.getName()))) { e.setCancelled(true); return; }
        }
        if (b != null && src instanceof LivingEntity le && !le.equals(victim) && store.b("revenge")) { b.revenge = le.getUniqueId(); b.revengeAt = ticks; }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Bot b = bot(e.getEntity().getName()); if (b == null) return;
        b.revenge = null; b.blocking = false; b.antiMace = false; b.stunAt = -1; b.tapTicks = 0; b.eatTicks = 0; // targets + /pvpbot attack are kept
        if (ticks - b.spawnedAt < 200) getLogger().warning("Bot " + b.name + " died right after spawning: " + e.getDeathMessage());
        if (store.b("bot-leave-on-death")) { Bukkit.getScheduler().runTaskLater(this, () -> remove(b, "Died"), 2L); return; }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { b.p.spigot().respawn(); } catch (Throwable ignored) {}
            Player np = Bukkit.getPlayer(b.p.getUniqueId());
            if (np != null) { b.p = np; try { b.nms = Nms.invoke(np, "getHandle"); } catch (Throwable ignored) {} }
            b.spawnedAt = ticks;
        }, 20L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Bot b = bot(e.getPlayer().getName());
        if (b != null && !b.removing) getLogger().warning("Bot " + b.name + " left on its own (" + e.getReason() + ")");
        bots.remove(e.getPlayer().getName().toLowerCase(Locale.ROOT));
    }
    /** bots never get kicked (timeouts, spam, flying checks...). Our own /pvpbot remove doesn't go through kicks. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(org.bukkit.event.player.PlayerKickEvent e) {
        Bot b = bot(e.getPlayer().getName());
        if (b == null || b.removing) return;
        e.setCancelled(true);
        if (ticks - b.lastKickLog > 200) { b.lastKickLog = ticks; getLogger().info("Blocked a kick for bot " + b.name + ": " + org.bukkit.ChatColor.stripColor(e.getReason())); }
    }
    /** no cramming deaths when lots of bots stand together, and no suffocation/fall damage right after spawning */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Bot b = bot(p.getName()); if (b == null) return;
        var c = e.getCause();
        if (c == org.bukkit.event.entity.EntityDamageEvent.DamageCause.CRAMMING) { e.setCancelled(true); return; }
        if (ticks - b.spawnedAt < 100 && c != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK && c != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK && c != org.bukkit.event.entity.EntityDamageEvent.DamageCause.PROJECTILE && c != org.bukkit.event.entity.EntityDamageEvent.DamageCause.KILL) e.setCancelled(true);
    }
    /** vanilla undoes melee knockback on players (their client is supposed to apply it). Bots have no client, so we apply it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(EntityDamageByEntityEvent e) {
        if (!store.b("knockback") || !(e.getEntity() instanceof Player v) || !(e.getDamager() instanceof Player a)) return;
        Bot b = bot(v.getName()); if (b == null) return;
        var c = e.getCause();
        if (c != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK && c != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        double ax = a.getLocation().getX(), az = a.getLocation().getZ(); float yaw = a.getLocation().getYaw();
        boolean sprint = a.isSprinting() && c == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK;
        int kb = a.getInventory().getItemInMainHand().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.KNOCKBACK);
        Bukkit.getScheduler().runTask(this, () -> brain.knockback(b, ax, az, yaw, sprint, kb));
    }

    // ================================================================= names
    static final String[] A = {"Dark", "Shadow", "Crimson", "Frost", "Storm", "Iron", "Night", "Blaze", "Void", "Silent", "Swift", "Ghost", "Wolf", "Raven", "Steel", "Ash"};
    static final String[] B = {"Blade", "Hunter", "Knight", "Fang", "Strike", "Reaper", "Slayer", "Archer", "Rider", "Walker", "Edge", "Claw", "Storm", "Heart", "Born", "Bane"};
    String randomName() {
        for (int k = 0; k < 50; k++) {
            String n = store.b("special-names") ? A[rnd.nextInt(A.length)] + B[rnd.nextInt(B.length)] + (rnd.nextInt(3) == 0 ? "" + rnd.nextInt(100) : "") : "Bot" + (1000 + rnd.nextInt(9000));
            if (n.length() <= 16 && bot(n) == null && Bukkit.getPlayerExact(n) == null && !pending.contains(n.toLowerCase(Locale.ROOT))) return n;
        }
        return "Bot" + System.nanoTime() % 100000;
    }
}
