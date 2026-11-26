package me.prot;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.*;
import java.util.*;

/**
 * Protagonist = plot armor (can't drop below half a heart without a totem in offhand)
 * + the signature move STARFALL:
 *   Starlight (0-100) charges as the protagonist fights. Shown as 5 stars on the action bar.
 *   At full: sneak + F (swap hands) → Starfall. Time "slows" for everyone nearby, the protagonist heals and powers up,
 *   and every hit plants a star mark. The 3rd mark on someone collapses into a starburst.
 */
public final class ProtagonistPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private final Properties data = new Properties();
    private File file;

    private final Map<UUID, Double> starlight = new HashMap<>();
    private final Map<UUID, Long> lastCombat = new HashMap<>(), starfallUntil = new HashMap<>(), lastStand = new HashMap<>();
    private final Map<UUID, Integer> marks = new HashMap<>();
    private final Map<UUID, Long> markTime = new HashMap<>();
    private long tick;

    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(255, 205, 90), 1.3f);
    private static final Particle.DustOptions VIOLET = new Particle.DustOptions(Color.fromRGB(150, 90, 255), 1.5f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.fromRGB(235, 240, 255), 1.0f);

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        file = new File(getDataFolder(), "protagonist.properties");
        if (file.exists()) try (Reader r = new FileReader(file)) { data.load(r); } catch (IOException e) { getLogger().warning("Load failed: " + e); }
        PluginCommand c = getCommand("protagonist");
        c.setExecutor(this); c.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
    }

    private void save() {
        try (Writer w = new FileWriter(file)) { data.store(w, "Protagonist"); } catch (IOException e) { getLogger().warning("Save failed: " + e); }
    }

    private boolean opt(String k, boolean def) { String v = data.getProperty("opt." + k); return v == null ? def : v.equals("true"); }
    private boolean isProtagonist(Player p) { return data.containsKey(p.getUniqueId().toString()); }
    private double light(Player p) { return starlight.getOrDefault(p.getUniqueId(), 0.0); }
    private void addLight(Player p, double v) {
        if (!opt("starfall", true)) return;
        double before = light(p), now = Math.max(0, Math.min(100, before + v));
        starlight.put(p.getUniqueId(), now);
        if (before < 100 && now >= 100) {
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.4f);
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.8f);
            bar(p, "§6✦ §e§lSTARFALL READY §6✦ §7(sneak + F)");
        }
    }
    private boolean inStarfall(Player p) { Long u = starfallUntil.get(p.getUniqueId()); return u != null && tick < u; }

    private void bar(Player p, String legacy) { p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(legacy)); }

    // ------------------------------------------------------------------ plot armor (unchanged behaviour) + last stand
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isProtagonist(p)) return;
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return;
        lastCombat.put(p.getUniqueId(), tick);
        if (p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;
        double hp = p.getHealth();
        if (hp - e.getFinalDamage() >= 1.0) { addLight(p, e.getFinalDamage() * 1.5); return; }
        e.setDamage(0);
        if (hp > 1.0) p.setHealth(1.0);
        lastStandMoment(p);
    }

    /** the first time plot armor saves them in a while: a heartbeat moment + a big chunk of starlight */
    private void lastStandMoment(Player p) {
        Long last = lastStand.get(p.getUniqueId());
        if (last != null && tick - last < 600) return;
        lastStand.put(p.getUniqueId(), tick);
        addLight(p, 35);
        p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f);
        Bukkit.getScheduler().runTaskLater(this, () -> p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f), 12L);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0, WHITE);
        bar(p, "§7§o...not yet.");
    }

    // ------------------------------------------------------------------ hits: charge starlight, star marks
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity src = e.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity sh) src = sh;
        if (!(src instanceof Player p) || !isProtagonist(p) || !(e.getEntity() instanceof LivingEntity victim) || victim.equals(p)) return;
        lastCombat.put(p.getUniqueId(), tick);
        if (!inStarfall(p)) { addLight(p, e.getFinalDamage() * (victim instanceof Player ? 2.2 : 0.8)); return; }
        // starfall: plant a mark, 3rd collapses
        UUID v = victim.getUniqueId();
        Long mt = markTime.get(v);
        int m = (mt != null && tick - mt < 200 ? marks.getOrDefault(v, 0) : 0) + 1;
        marks.put(v, m); markTime.put(v, tick);
        Location head = victim.getLocation().add(0, victim.getHeight() + 0.4, 0);
        victim.getWorld().spawnParticle(Particle.DUST, head, 12, 0.15, 0.1, 0.15, 0, GOLD);
        victim.getWorld().playSound(head, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 1f, 1f + m * 0.25f);
        if (m >= 3) {
            marks.remove(v); markTime.remove(v);
            e.setDamage(e.getDamage() + 6);
            collapse(p, victim);
        }
    }

    private void collapse(Player p, LivingEntity victim) {
        Location c = victim.getLocation().add(0, victim.getHeight() / 2, 0);
        World w = c.getWorld();
        w.playSound(c, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.4f, 0.7f);
        w.playSound(c, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1f, 1.6f);
        w.spawnParticle(Particle.END_ROD, c, 60, 0.1, 0.1, 0.1, 0.35);
        w.spawnParticle(Particle.DUST, c, 50, 0.7, 0.7, 0.7, 0, VIOLET);
        w.spawnParticle(Particle.EXPLOSION, c, 1);
        Vector kb = victim.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
        if (kb.lengthSquared() > 0.01) victim.setVelocity(kb.normalize().multiply(0.9).setY(0.55));
    }

    // ------------------------------------------------------------------ the move
    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!isProtagonist(p) || !p.isSneaking() || !opt("starfall", true)) return;
        e.setCancelled(true);
        if (inStarfall(p)) return;
        if (light(p) < 100) { bar(p, "§7Starlight " + stars(light(p)) + " §8" + (int) light(p) + "%"); p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f); return; }
        starfall(p);
    }

    private void starfall(Player p) {
        starlight.put(p.getUniqueId(), 0.0);
        starfallUntil.put(p.getUniqueId(), tick + 160); // 8 seconds
        Location at = p.getLocation(); World w = p.getWorld();
        w.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 2f, 1.5f);
        w.playSound(at, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.5f, 0.6f);
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 2f, 0.5f);
        p.setVelocity(p.getVelocity().setY(0.45));
        p.setHealth(Math.min(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), p.getHealth() + 6));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 160, 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 160, 0, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 160, 0, false, false, true));
        // the world slows down around them
        for (Entity en : p.getNearbyEntities(12, 8, 12)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            if (le instanceof Player o && (isProtagonist(o) || o.getGameMode() == GameMode.SPECTATOR || o.getGameMode() == GameMode.CREATIVE)) continue;
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 3, false, false, true));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 0, false, false, true));
            if (le instanceof Player o) { o.playSound(o.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.5f); o.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 30, 0, false, false, false)); }
            Vector out = le.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (out.lengthSquared() > 0.01) le.setVelocity(out.normalize().multiply(0.6).setY(0.25));
        }
        // expanding star ring + a falling column of light
        for (int i = 0; i < 12; i++) {
            final int k = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                double r = 1 + k * 0.9;
                for (int a = 0; a < 40; a++) {
                    double an = a * Math.PI * 2 / 40;
                    w.spawnParticle(Particle.END_ROD, at.clone().add(Math.cos(an) * r, 0.2, Math.sin(an) * r), 1, 0, 0, 0, 0);
                }
                w.spawnParticle(Particle.DUST, at.clone().add(0, 12 - k, 0), 25, 0.25, 0.4, 0.25, 0, k % 2 == 0 ? GOLD : VIOLET);
            }, i);
        }
        bar(p, "§6✦ §e§lSTARFALL §6✦");
    }

    // ------------------------------------------------------------------ every tick: action bar, aura, marks
    private void tickAll() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isProtagonist(p)) continue;
            UUID u = p.getUniqueId();
            boolean fight = lastCombat.containsKey(u) && tick - lastCombat.get(u) < 200;
            if (inStarfall(p)) {
                // trail of stars while the move lasts
                p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 3, 0.35, 0.5, 0.35, 0, tick % 4 < 2 ? GOLD : VIOLET);
                if (tick % 3 == 0) p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 0.2, 0), 1, 0.3, 0, 0.3, 0.01);
                if (tick % 10 == 0) bar(p, "§6✦ §e§lSTARFALL §6✦ §7" + (starfallUntil.get(u) - tick) / 20 + "s");
            } else if (opt("starfall", true) && fight && tick % 10 == 0) {
                bar(p, light(p) >= 100 ? "§6✦ §e§lSTARFALL READY §6✦ §7(sneak + F)" : "§7Starlight " + stars(light(p)));
            }
            if (opt("aura", true) && fight && p.isSprinting() && tick % 4 == 0)
                p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 0.1, 0), 1, 0.2, 0, 0.2, 0, WHITE);
        }
        // floating marks over marked targets
        if (tick % 5 == 0) for (Iterator<Map.Entry<UUID, Integer>> it = marks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> en = it.next();
            Entity e = Bukkit.getEntity(en.getKey());
            Long mt = markTime.get(en.getKey());
            if (e == null || !e.isValid() || mt == null || tick - mt > 200) { it.remove(); markTime.remove(en.getKey()); continue; }
            for (int i = 0; i < en.getValue(); i++) {
                double an = tick * 0.15 + i * Math.PI * 2 / 3;
                e.getWorld().spawnParticle(Particle.DUST, e.getLocation().add(Math.cos(an) * 0.5, e.getHeight() + 0.35, Math.sin(an) * 0.5), 1, 0, 0, 0, 0, GOLD);
            }
        }
    }

    private static String stars(double v) {
        int n = (int) Math.floor(v / 20);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5; i++) b.append(i < n ? "§6✦" : "§8✦");
        return b.toString();
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { starfallUntil.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ commands
    private Player online(String n) { for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().equalsIgnoreCase(n)) return p; return null; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("protagonist.admin")) { s.sendMessage("§cNo permission."); return true; }
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        if (a.length == 2 && (sub.equals("add") || sub.equals("set"))) {
            Player t = online(a[1]);
            if (t == null) { s.sendMessage("§cPlayer must be online: " + a[1]); return true; }
            if (isProtagonist(t)) { s.sendMessage("§e" + t.getName() + " is already a protagonist."); return true; }
            data.setProperty(t.getUniqueId().toString(), t.getName()); save();
            s.sendMessage("§a" + t.getName() + " §eis now a §6Protagonist§e.");
            t.sendMessage("§6You are the Protagonist. §eNo totem in your offhand = you can't drop below half a heart.");
            t.sendMessage("§6✦ Starfall: §efight to fill your Starlight, then §fsneak + F§e.");
            return true;
        }
        if (a.length == 2 && sub.equals("remove")) {
            String key = null;
            for (String k : data.stringPropertyNames()) if (!k.startsWith("opt.") && data.getProperty(k).equalsIgnoreCase(a[1])) key = k;
            if (key == null) { s.sendMessage("§c" + a[1] + " is not a protagonist."); return true; }
            String name = data.getProperty(key); data.remove(key); save();
            s.sendMessage("§e" + name + " is no longer a protagonist.");
            return true;
        }
        if (a.length == 1 && sub.equals("clear")) {
            data.stringPropertyNames().stream().filter(k -> !k.startsWith("opt.")).forEach(data::remove); save();
            s.sendMessage("§eAll protagonists cleared."); return true;
        }
        if (a.length == 1 && (sub.equals("list") || sub.equals("info"))) {
            List<String> names = new ArrayList<>();
            for (String k : data.stringPropertyNames()) if (!k.startsWith("opt.")) names.add(data.getProperty(k));
            if (names.isEmpty()) { s.sendMessage("§eNo protagonists set."); return true; }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            s.sendMessage("§eProtagonists (" + names.size() + "): §6" + String.join("§e, §6", names));
            s.sendMessage("§7Starfall: " + (opt("starfall", true) ? "§aon" : "§coff") + " §7| Aura: " + (opt("aura", true) ? "§aon" : "§coff"));
            return true;
        }
        if (sub.equals("charge")) { // instant full starlight (for recording)
            Player t = a.length > 1 ? online(a[1]) : (s instanceof Player sp ? sp : null);
            if (t == null || !isProtagonist(t)) { s.sendMessage("§cNot an online protagonist."); return true; }
            starlight.put(t.getUniqueId(), 99.9); addLight(t, 1);
            s.sendMessage("§6" + t.getName() + "§e's Starlight is full."); return true;
        }
        if (a.length == 3 && sub.equals("starlight")) {
            Player t = online(a[1]);
            if (t == null || !isProtagonist(t)) { s.sendMessage("§cNot an online protagonist."); return true; }
            try { starlight.put(t.getUniqueId(), Math.max(0, Math.min(99.9, Double.parseDouble(a[2])))); if (Double.parseDouble(a[2]) >= 100) addLight(t, 1); }
            catch (NumberFormatException ex) { s.sendMessage("§c0-100"); return true; }
            s.sendMessage("§eStarlight of §6" + t.getName() + "§e set to §f" + a[2]); return true;
        }
        if (a.length == 2 && (sub.equals("starfall") || sub.equals("aura"))) {
            boolean on = a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true");
            data.setProperty("opt." + sub, String.valueOf(on)); save();
            s.sendMessage("§e" + sub + ": " + (on ? "§aon" : "§coff")); return true;
        }
        s.sendMessage("§eUsage: §f/protagonist add|remove <player> §7| §flist §7| §fclear §7| §fcharge [player] §7| §fstarlight <player> <0-100> §7| §fstarfall on|off §7| §faura on|off");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("add", "remove", "list", "clear", "charge", "starlight", "starfall", "aura"));
        else if (a.length == 2) {
            String sub = a[0].toLowerCase();
            if (sub.equals("starfall") || sub.equals("aura")) out.addAll(List.of("on", "off"));
            else if (sub.equals("remove")) { for (String k : data.stringPropertyNames()) if (!k.startsWith("opt.")) out.add(data.getProperty(k)); }
            else for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("starlight")) out.addAll(List.of("0", "50", "100"));
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
