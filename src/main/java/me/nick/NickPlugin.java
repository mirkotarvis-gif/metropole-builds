package me.nick;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /nick <player> <name>  |  /nick clear <player|nick>
 * /randomizenick [player] | clear | bypass <player> | bypass list
 *
 * TAB: setPlayerListName. Chat: setDisplayName.
 * Above head: real name tag hidden via a scoreboard team + a floating TextDisplay with the nick.
 * Random nicks: a real, existing Minecraft account's name + that account's skin (from Mojang).
 */
public final class NickPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final String TEAM = "nickplugin_hide";

    private final Properties nicks = new Properties();     // uuid -> nick
    private final Properties skins = new Properties();     // uuid -> value|signature (random skin)
    private final Properties bypass = new Properties();    // uuid -> name
    private final Map<UUID, TextDisplay> tags = new HashMap<>();
    private final Map<UUID, String[]> originalSkin = new HashMap<>(); // uuid -> {value, signature}
    private final Set<String> reserved = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Random rng = new Random();
    private File nickFile, skinFile, bypassFile, poolFile;
    /** ready-to-use real accounts {name, skinValue, skinSignature}, filled in the background so /randomizenick is instant */
    private final java.util.concurrent.ConcurrentLinkedDeque<String[]> pool = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicBoolean filling = new java.util.concurrent.atomic.AtomicBoolean();
    private static final int POOL_SIZE = 150;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        nickFile = new File(getDataFolder(), "nicks.properties");
        skinFile = new File(getDataFolder(), "skins.properties");
        bypassFile = new File(getDataFolder(), "randomnick-bypass.properties");
        load(nicks, nickFile); load(skins, skinFile); load(bypass, bypassFile);

        for (String c : new String[]{"nick", "randomizenick"}) {
            PluginCommand cmd = getCommand(c);
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) { rememberOriginalSkin(p); apply(p); applySavedSkin(p); }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 5L);
        loadDead();
        poolFile = new File(getDataFolder(), "name-pool.txt");
        loadPool();
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refill, 40L, 200L);
    }

    @Override
    public void onDisable() {
        savePool();
        lookups.shutdownNow();
        for (TextDisplay d : tags.values()) if (d != null && d.isValid()) d.remove();
        tags.clear();
    }

    private void load(Properties p, File f) {
        if (f.exists()) try (Reader r = new FileReader(f)) { p.load(r); } catch (IOException e) { getLogger().warning("Load failed " + f + ": " + e); }
    }

    private void store(Properties p, File f) {
        try (Writer w = new FileWriter(f)) { p.store(w, "NickPlugin"); } catch (IOException e) { getLogger().warning("Save failed " + f + ": " + e); }
    }

    private String nickOf(Player p) { return nicks.getProperty(p.getUniqueId().toString()); }

    // ---------------------------------------------------------------- name tag / tab / chat

    private Team team() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(TEAM);
        if (t == null) t = sb.registerNewTeam(TEAM);
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        return t;
    }

    private void apply(Player p) {
        String nick = nickOf(p);
        removeTag(p);
        Team t = team();
        if (nick == null) {
            t.removeEntry(p.getName());
            p.setDisplayName(null);
            p.setPlayerListName(null);
        } else {
            t.addEntry(p.getName());
            p.setDisplayName(nick);
            p.setPlayerListName(nick);
            spawnTag(p, nick);
        }
    }

    private void spawnTag(Player p, String nick) {
        if (p.isDead()) return;
        TextDisplay d = p.getWorld().spawn(p.getLocation().add(0, 2.2, 0), TextDisplay.class);
        d.setPersistent(false);
        d.setText(nick);
        d.setBillboard(Display.Billboard.CENTER);
        Transformation tr = d.getTransformation();
        d.setTransformation(new Transformation(new Vector3f(0f, 0.3f, 0f), tr.getLeftRotation(), tr.getScale(), tr.getRightRotation()));
        p.addPassenger(d);
        p.hideEntity(this, d);
        tags.put(p.getUniqueId(), d);
    }

    private void removeTag(Player p) {
        TextDisplay d = tags.remove(p.getUniqueId());
        if (d != null && d.isValid()) d.remove();
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String nick = nickOf(p);
            if (nick == null) continue;
            TextDisplay d = tags.get(p.getUniqueId());
            if (p.isDead()) { if (d != null) removeTag(p); continue; }
            if (d == null || !d.isValid() || d.getVehicle() != p) { removeTag(p); spawnTag(p, nick); }
        }
    }

    // ---------------------------------------------------------------- skins

    private void rememberOriginalSkin(Player p) {
        for (ProfileProperty pp : p.getPlayerProfile().getProperties())
            if ("textures".equals(pp.getName())) { originalSkin.put(p.getUniqueId(), new String[]{pp.getValue(), pp.getSignature()}); return; }
        originalSkin.put(p.getUniqueId(), null);
    }

    private void setSkin(Player p, String value, String signature) {
        if (value == null) return;
        PlayerProfile prof = p.getPlayerProfile();
        prof.setProperty(new ProfileProperty("textures", value, signature));
        p.setPlayerProfile(prof);
        // make every other client reload this player so they see the new skin
        List<Player> viewers = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p || !o.canSee(p)) continue;
            o.hidePlayer(this, p);
            viewers.add(o);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            for (Player o : viewers) if (o.isOnline()) o.showPlayer(this, p);
        }, 2L);
    }

    private void applySavedSkin(Player p) {
        String s = skins.getProperty(p.getUniqueId().toString());
        if (s == null) return;
        String[] parts = s.split("\\|", 2);
        setSkin(p, parts[0], parts.length > 1 ? parts[1] : null);
    }

    private void restoreSkin(Player p) {
        if (skins.remove(p.getUniqueId().toString()) == null) return;
        store(skins, skinFile);
        String[] orig = originalSkin.get(p.getUniqueId());
        if (orig != null) setSkin(p, orig[0], orig[1]);
    }

    // ---------------------------------------------------------------- Mojang lookups (async only!)

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                .header("User-Agent", "NickPlugin").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200 ? res.body() : null;
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** returns {name, skinValue, skinSignature} of a real account, or null */
    private String[] lookup(String candidate) {
        try {
            String prof = get("https://api.mojang.com/users/profiles/minecraft/" + candidate);
            if (prof == null) prof = get("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + candidate);
            if (prof == null) return null;
            String id = field(prof, "id"), name = field(prof, "name");
            if (id == null || name == null) return null;
            String sess = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
            if (sess == null) return new String[]{name, null, null};
            String value = field(sess, "value"), sig = field(sess, "signature");
            if (value != null) {
                String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                if (!decoded.contains("\"SKIN\"")) value = null; // default Steve/Alex: no custom skin
            }
            return new String[]{name, value, value == null ? null : sig};
        } catch (Exception e) {
            return null;
        }
    }

    /** Longer, normal-looking usernames (8-16 chars), e.g. CosmicWolf, pizza_hunter, ShadowFox27. */
    private String randomCandidate() {
        for (int tries = 0; tries < 50; tries++) {
            String a = WORDS[rng.nextInt(WORDS.length)], b = WORDS[rng.nextInt(WORDS.length)];
            if (a.equals(b)) continue;
            String A = Character.toUpperCase(a.charAt(0)) + a.substring(1), B = Character.toUpperCase(b.charAt(0)) + b.substring(1);
            String n;
            switch (rng.nextInt(6)) {
                case 0: n = A + B; break;
                case 1: n = a + b; break;
                case 2: n = a + "_" + b; break;
                case 3: n = A + B + (rng.nextInt(99) + 1); break;
                case 4: n = "The" + A + B; break;
                default: n = a + b + (rng.nextInt(9000) + 1000); break;
            }
            if (n.length() >= 10 && n.length() <= 16) return n;
            if (n.length() >= 8 && n.length() <= 16 && tries > 35) return n;
        }
        return "cosmic" + (rng.nextInt(9000) + 1000);
    }

    /** names of bots that died: never handed out again */
    private final Set<String> dead = ConcurrentHashMap.newKeySet();
    private File deadFile;
    private void loadDead() {
        deadFile = new File(getDataFolder(), "dead-names.txt");
        if (!deadFile.exists()) return;
        try { for (String l : java.nio.file.Files.readAllLines(deadFile.toPath(), StandardCharsets.UTF_8)) if (!l.isBlank()) dead.add(l.trim().toLowerCase()); } catch (IOException e) { getLogger().warning("dead names: " + e); }
    }
    private void saveDead() {
        try { java.nio.file.Files.write(deadFile.toPath(), new TreeSet<>(dead), StandardCharsets.UTF_8); } catch (IOException e) { getLogger().warning("dead names: " + e); }
    }
    private void bury(String n) {
        if (n == null || n.isBlank()) return;
        if (dead.add(n.toLowerCase())) { pool.removeIf(e -> e[0].equalsIgnoreCase(n)); saveDead(); }
    }

    private boolean nameTaken(String n) {
        if (reserved.contains(n.toLowerCase()) || dead.contains(n.toLowerCase())) return true;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(n)) return true;
            String k = nickOf(p);
            if (k != null && k.equalsIgnoreCase(n)) return true;
        }
        return false;
    }

    private boolean isBypassed(Player p) {
        return p.hasPermission("randomnick.bypass") || bypass.containsKey(p.getUniqueId().toString());
    }

    // ---------------------------------------------------------------- name pool (instant random nicks)

    private void loadPool() {
        if (!poolFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(poolFile, StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) { String[] f = l.split("\t", -1); if (f.length == 3 && VALID.matcher(f[0]).matches()) pool.add(new String[]{f[0], f[1].isEmpty() ? null : f[1], f[2].isEmpty() ? null : f[2]}); }
        } catch (IOException e) { getLogger().warning("pool load: " + e); }
    }

    private synchronized void savePool() {
        if (poolFile == null) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(poolFile), StandardCharsets.UTF_8)) {
            for (String[] e : pool) w.write(e[0] + "\t" + (e[1] == null ? "" : e[1]) + "\t" + (e[2] == null ? "" : e[2]) + "\n");
        } catch (IOException e) { getLogger().warning("pool save: " + e); }
    }

    private boolean inPool(String n) { for (String[] e : pool) if (e[0].equalsIgnoreCase(n)) return true; return false; }

    private String post(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                .header("User-Agent", "NickPlugin").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200 ? res.body() : null;
    }

    /** one request checks 10 names at once; returns {id, name} of the ones that are real accounts */
    private List<String[]> bulk(List<String> names) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) b.append(i > 0 ? "," : "").append('"').append(names.get(i)).append('"');
        String body = b.append(']').toString();
        String res = null;
        try { res = post("https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname", body); } catch (Exception ignored) { }
        if (res == null) try { res = post("https://api.mojang.com/profiles/minecraft", body); } catch (Exception ignored) { }
        if (res == null) return single(names); // bulk endpoint refused: check them one by one (in parallel)
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(res);
        while (m.find()) { String o = m.group(), id = field(o, "id"), n = field(o, "name"); if (id != null && n != null) out.add(new String[]{id, n}); }
        return out;
    }

    private final java.util.concurrent.ExecutorService lookups = java.util.concurrent.Executors.newFixedThreadPool(6, r -> { Thread t = new Thread(r, "NickPlugin-lookup"); t.setDaemon(true); return t; });
    private List<String[]> single(List<String> names) {
        List<java.util.concurrent.Future<String[]>> fs = new ArrayList<>();
        for (String n : names) fs.add(lookups.submit(() -> {
            String prof = get("https://api.mojang.com/users/profiles/minecraft/" + n);
            if (prof == null) prof = get("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + n);
            if (prof == null) return null;
            String id = field(prof, "id"), nm = field(prof, "name");
            return id == null || nm == null ? null : new String[]{id, nm};
        }));
        List<String[]> out = new ArrayList<>(); int fails = 0;
        for (java.util.concurrent.Future<String[]> f : fs) { try { String[] r = f.get(10, java.util.concurrent.TimeUnit.SECONDS); if (r != null) out.add(r); } catch (Exception e) { fails++; } }
        return fails == names.size() ? null : out;
    }

    /** {name, skinValue, skinSignature} for an account id, skin null if it uses a default skin */
    private String[] skinOf(String id, String name) {
        try {
            String sess = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
            if (sess == null) return null;
            String value = field(sess, "value"), sig = field(sess, "signature");
            if (value != null && !new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8).contains("\"SKIN\"")) value = null;
            return new String[]{name, value, value == null ? null : sig};
        } catch (Exception e) { return null; }
    }

    /** keeps POOL_SIZE real long-named accounts with skins ready (runs async) */
    private void refill() {
        if (pool.size() >= POOL_SIZE || !filling.compareAndSet(false, true)) return;
        try {
            int rounds = 0, before = pool.size();
            while (pool.size() < POOL_SIZE && rounds++ < 25) {
                List<String> cands = new ArrayList<>();
                while (cands.size() < 10) { String n = randomCandidate(); if (n.length() >= 8 && n.length() <= 16 && !cands.contains(n)) cands.add(n); }
                List<String[]> found = bulk(cands);
                if (found == null) { getLogger().warning("Mojang name lookups are failing right now (rate limit or blocked network), retrying in 10s"); break; }
                for (String[] f : found) {
                    if (f[1].length() < 9 || inPool(f[1]) || nameTaken(f[1])) continue;
                    String[] r = skinOf(f[0], f[1]);
                    if (r != null && r[1] != null) pool.add(r);
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                }
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            }
            if (pool.size() != before) { savePool(); getLogger().info("Name pool: " + pool.size() + " ready"); }
        } finally { filling.set(false); }
    }

    /** hands out the longest name that's ready */
    private synchronized String[] takeFromPool() {
        String[] best = null;
        for (String[] e : pool) if (!nameTaken(e[0]) && (best == null || e[0].length() > best[0].length())) best = e;
        if (best != null) pool.remove(best);
        return best;
    }

    private void applyPick(Player p, String[] pick, CommandSender reportTo) {
        nicks.setProperty(p.getUniqueId().toString(), pick[0]);
        store(nicks, nickFile);
        apply(p);
        if (pick[1] != null) {
            skins.setProperty(p.getUniqueId().toString(), pick[1] + "|" + (pick[2] == null ? "" : pick[2]));
            store(skins, skinFile);
            setSkin(p, pick[1], pick[2]);
        }
        if (reportTo != null) reportTo.sendMessage("§a" + p.getName() + " §7→ §f" + pick[0] + (pick[1] != null ? " §7(+ skin)" : " §7(no custom skin)"));
    }

    /** Give a real account's name + skin. Instant from the ready pool; slow lookup only if the pool is empty. */
    private void randomize(Player p, CommandSender reportTo) {
        String[] ready = takeFromPool();
        if (ready != null) {
            applyPick(p, ready, reportTo);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> { savePool(); refill(); });
            return;
        }
        // pool empty: give a made-up name right now, then swap to a real account with a skin when one is found
        String temp = null;
        for (int i = 0; i < 20 && temp == null; i++) { String c = randomCandidate(); if (c.length() >= 8 && c.length() <= 16 && !nameTaken(c)) temp = c; }
        if (temp != null) applyPick(p, new String[]{temp, null, null}, null);
        final String tempName = temp;
        if (reportTo != null) reportTo.sendMessage("§a" + p.getName() + " §7→ §f" + temp + " §7(name pool still filling, getting a skin...)");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String[] best = null;
            for (int round = 0; round < 6 && best == null; round++) {
                List<String> cands = new ArrayList<>();
                while (cands.size() < 10) { String n = randomCandidate(); if (n.length() >= 8 && !cands.contains(n)) cands.add(n); }
                List<String[]> found = bulk(cands);
                if (found == null) break;
                for (String[] f : found) {
                    if (f[1].length() < 8 || nameTaken(f[1])) continue;
                    String[] r = skinOf(f[0], f[1]);
                    if (r == null) continue;
                    if (best == null) best = r;
                    if (r[1] != null) { best = r; break; }
                }
            }
            final String[] pick = best;
            if (pick != null) reserved.add(pick[0].toLowerCase());
            Bukkit.getScheduler().runTask(this, () -> {
                if (pick != null) reserved.remove(pick[0].toLowerCase());
                if (!p.isOnline()) return;
                if (pick == null) { if (reportTo != null && tempName == null) reportTo.sendMessage("§cCouldn't reach Mojang for " + p.getName() + " (rate limit?). Try again in a minute."); return; }
                if (tempName != null && !tempName.equals(nickOf(p))) return; // nick was changed meanwhile
                applyPick(p, pick, reportTo);
            });
        });
    }

    // ---------------------------------------------------------------- lookup helpers

    private Player find(String n) {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().equalsIgnoreCase(n)) return p;
        for (Player p : Bukkit.getOnlinePlayers()) { String k = nickOf(p); if (k != null && k.equalsIgnoreCase(n)) return p; }
        return null;
    }

    private void clearPlayer(Player t) {
        nicks.remove(t.getUniqueId().toString());
        store(nicks, nickFile);
        apply(t);
        restoreSkin(t);
    }

    // ---------------------------------------------------------------- events

    /** Replace real names with nicks in a server message (death / join / leave / advancement). */
    private Component swapNames(Component c, Player extra) {
        if (c == null) return null;
        Map<String, String> map = new LinkedHashMap<>();
        if (extra != null) { String n = nickOf(extra); if (n != null) map.put(extra.getName(), n); }
        for (Player p : Bukkit.getOnlinePlayers()) { String n = nickOf(p); if (n != null) map.put(p.getName(), n); }
        map.entrySet().removeIf(en -> en.getKey().equals(en.getValue()));
        if (map.isEmpty()) return c;
        return deep(c, map, 0);
    }

    private static String swapStr(String s, Map<String, String> map) {
        if (s == null) return null;
        for (Map.Entry<String, String> en : map.entrySet())
            s = s.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(en.getKey()) + "(?![A-Za-z0-9_])", Matcher.quoteReplacement(en.getValue()));
        return s;
    }

    /** walks the whole message: plain text, translation arguments (death/advancement names live there), children, hover, click, insertion */
    @SuppressWarnings("deprecation")
    private Component deep(Component c, Map<String, String> map, int depth) {
        if (c == null || depth > 30) return c;
        if (c instanceof net.kyori.adventure.text.TextComponent tc) c = tc.content(swapStr(tc.content(), map));
        if (c instanceof net.kyori.adventure.text.TranslatableComponent tr && !tr.args().isEmpty()) {
            List<Component> args = new ArrayList<>();
            for (Component a : tr.args()) args.add(deep(a, map, depth + 1));
            c = tr.args(args);
        }
        net.kyori.adventure.text.event.HoverEvent<?> hv = c.hoverEvent();
        if (hv != null) {
            if (hv.action() == net.kyori.adventure.text.event.HoverEvent.Action.SHOW_TEXT)
                c = c.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(deep((Component) hv.value(), map, depth + 1)));
            else if (hv.action() == net.kyori.adventure.text.event.HoverEvent.Action.SHOW_ENTITY) {
                net.kyori.adventure.text.event.HoverEvent.ShowEntity se = (net.kyori.adventure.text.event.HoverEvent.ShowEntity) hv.value();
                if (se.name() != null) c = c.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showEntity(se.name(deep(se.name(), map, depth + 1))));
            }
        }
        net.kyori.adventure.text.event.ClickEvent ce = c.clickEvent();
        if (ce != null && ce.action() == net.kyori.adventure.text.event.ClickEvent.Action.SUGGEST_COMMAND) {
            // keep /tell working: it needs the real name, so leave the command but nothing visible shows it
        }
        if (c.insertion() != null) c = c.insertion(null);
        if (!c.children().isEmpty()) {
            List<Component> kids = new ArrayList<>();
            for (Component k : c.children()) kids.add(deep(k, map, depth + 1));
            c = c.children(kids);
        }
        return c;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeathLate(PlayerDeathEvent e) { e.deathMessage(swapNames(e.deathMessage(), e.getPlayer())); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) { e.deathMessage(swapNames(e.deathMessage(), e.getPlayer())); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent e) { e.message(swapNames(e.message(), e.getPlayer())); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoinMsg(PlayerJoinEvent e) { e.joinMessage(swapNames(e.joinMessage(), e.getPlayer())); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitMsg(PlayerQuitEvent e) { e.quitMessage(swapNames(e.quitMessage(), e.getPlayer())); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        rememberOriginalSkin(p);
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) { apply(p); applySavedSkin(p); } });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { removeTag(e.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) { removeTag(e.getPlayer()); }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("nick.use")) { s.sendMessage("§cNo permission."); return true; }
        if (c.getName().equalsIgnoreCase("randomizenick")) return randomCmd(s, a);

        if (a.length != 2) { s.sendMessage("§eUsage: §f/nick <player> <newname> §7| §f/nick clear <player>"); return true; }
        if (a[0].equalsIgnoreCase("clear")) {
            Player t = find(a[1]);
            if (t == null) { s.sendMessage("§cPlayer not found: " + a[1]); return true; }
            if (nickOf(t) == null && !skins.containsKey(t.getUniqueId().toString())) { s.sendMessage("§e" + t.getName() + " has no nick."); return true; }
            clearPlayer(t);
            s.sendMessage("§aCleared. §f" + t.getName() + " §ais back to their real name and skin.");
            return true;
        }
        Player t = find(a[0]);
        if (t == null) { s.sendMessage("§cPlayer not found: " + a[0]); return true; }
        String nick = a[1];
        if (!VALID.matcher(nick).matches()) { s.sendMessage("§cNick must be 1-16 chars: letters, numbers, _"); return true; }
        Player other = find(nick);
        if (other != null && other != t) { s.sendMessage("§cThat name is already used by an online player."); return true; }
        nicks.setProperty(t.getUniqueId().toString(), nick);
        store(nicks, nickFile);
        apply(t);
        s.sendMessage("§aNicked §f" + t.getName() + " §a→ §f" + nick);
        return true;
    }

    private boolean randomCmd(CommandSender s, String[] a) {
        if (a.length >= 1 && (a[0].equalsIgnoreCase("bury") || a[0].equalsIgnoreCase("graveyard") || a[0].equalsIgnoreCase("dead"))) {
            if (a[0].equalsIgnoreCase("bury")) { if (a.length < 2) { s.sendMessage("§e/randomizenick bury <name>"); return true; } bury(a[1]); s.sendMessage("§7" + a[1] + " is now dead, it won't be given out again."); return true; }
            String op = a.length > 1 ? a[1].toLowerCase() : "list";
            switch (op) {
                case "clear" -> { int n = dead.size(); dead.clear(); saveDead(); s.sendMessage("§aGraveyard cleared (" + n + " names free again)."); }
                case "remove" -> { if (a.length < 3) { s.sendMessage("§e/randomizenick graveyard remove <name>"); return true; } boolean r = dead.remove(a[2].toLowerCase()); saveDead(); s.sendMessage(r ? "§a" + a[2] + " can be used again." : "§c" + a[2] + " isn't in the graveyard."); }
                default -> { List<String> l = new ArrayList<>(new TreeSet<>(dead)); s.sendMessage("§8☠ §7Graveyard (" + l.size() + "): §f" + (l.isEmpty() ? "empty" : String.join("§7, §f", l.subList(0, Math.min(80, l.size()))) + (l.size() > 80 ? " §7..." : ""))); }
            }
            return true;
        }
        if (a.length == 0) {
            int n = 0;
            for (Player p : Bukkit.getOnlinePlayers()) if (!isBypassed(p)) { randomize(p, s); n++; }
            s.sendMessage("§eRandomized §f" + n + " §eplayer(s). §7(" + pool.size() + " names left ready)");
            return true;
        }
        String sub = a[0].toLowerCase();
        if (sub.equals("clear") && a.length == 1) {
            int n = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isBypassed(p)) continue;
                if (nickOf(p) != null || skins.containsKey(p.getUniqueId().toString())) { clearPlayer(p); n++; }
            }
            s.sendMessage("§eCleared random nicks/skins from §f" + n + " §eplayer(s).");
            return true;
        }
        if (sub.equals("bypass") && a.length == 2 && a[1].equalsIgnoreCase("list")) {
            if (bypass.isEmpty()) { s.sendMessage("§eNobody is on the bypass list."); return true; }
            s.sendMessage("§eBypass: §f" + String.join(", ", new TreeSet<>(bypass.stringPropertyNames().stream().map(bypass::getProperty).toList())));
            return true;
        }
        if (sub.equals("bypass") && a.length == 2) {
            Player t = null;
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().equalsIgnoreCase(a[1])) t = p;
            String key = null;
            if (t != null) key = t.getUniqueId().toString();
            else for (String k : bypass.stringPropertyNames()) if (bypass.getProperty(k).equalsIgnoreCase(a[1])) key = k;
            if (key == null) { s.sendMessage("§cPlayer must be online to add: " + a[1]); return true; }
            if (bypass.remove(key) != null) s.sendMessage("§e" + a[1] + " §cremoved §efrom bypass. Their nick/skin will be randomized now.");
            else { bypass.setProperty(key, t.getName()); s.sendMessage("§e" + t.getName() + " §aadded §eto bypass. /randomizenick won't touch them."); }
            store(bypass, bypassFile);
            return true;
        }
        if (a.length == 1) {
            Player t = find(a[0]);
            if (t == null) { s.sendMessage("§cPlayer not found: " + a[0]); return true; }
            randomize(t, s);
            return true;
        }
        s.sendMessage("§eUsage: §f/randomizenick §7(everyone) §f| /randomizenick <player> | clear | bypass <player> | bypass list");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String q = a[a.length - 1].toLowerCase();
        List<String> opts = new ArrayList<>();
        if (c.getName().equalsIgnoreCase("randomizenick")) {
            if (a.length == 1) { opts.add("clear"); opts.add("bypass"); opts.add("graveyard"); opts.add("bury"); for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName()); }
            else if (a.length == 2 && a[0].equalsIgnoreCase("bypass")) { opts.add("list"); for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName()); }
        } else {
            if (a.length == 1) { opts.add("clear"); for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName()); }
            else if (a.length == 2 && a[0].equalsIgnoreCase("clear"))
                for (Player p : Bukkit.getOnlinePlayers()) { String k = nickOf(p); if (k != null) { opts.add(p.getName()); opts.add(k); } }
        }
        for (String o : opts) if (o.toLowerCase().startsWith(q) && !out.contains(o)) out.add(o);
        return out;
    }

    // common words: almost all of these are registered Minecraft accounts
    private static final String[] WORDS = (
            "apple banana cherry mango lemon peach melon grape berry coconut tiger lion wolf fox bear eagle hawk falcon raven crow owl " +
            "shark whale dolphin otter panda koala zebra rhino hippo moose bison horse pony donkey goat sheep llama camel frog toad snake " +
            "lizard gecko turtle crab lobster squid octopus jelly bee wasp ant beetle spider moth butterfly dragon phoenix griffin unicorn " +
            "storm thunder lightning rain snow frost ice fire flame ember blaze ash smoke shadow ghost spirit phantom spark comet meteor star " +
            "moon sun planet galaxy nova orbit rocket pilot captain knight king queen prince duke baron wizard mage witch ninja samurai pirate " +
            "viking hunter ranger archer sniper soldier warrior monk rogue thief bandit outlaw sheriff cowboy farmer miner builder crafter " +
            "baker chef cook waffle pancake muffin cookie donut bagel toast cheese butter honey sugar candy cocoa coffee latte mocha tea " +
            "pizza taco burrito nacho burger noodle ramen sushi dumpling pickle potato tomato carrot onion garlic pepper cactus maple oak " +
            "pine cedar willow birch aspen forest river ocean lake canyon valley mountain glacier desert island jungle meadow garden " +
            "pebble stone rock boulder crystal diamond emerald ruby sapphire gold silver copper iron steel cobalt quartz marble granite " +
            "pixel byte vector matrix cipher echo delta alpha omega sigma gamma zeta theta nova blaze turbo nitro rapid swift flash bolt " +
            "zero hero legend myth saga quest arcade joystick console gamer player sprinter runner jumper glider rider drifter wanderer " +
            "nomad pilgrim scout sailor diver surfer skater biker climber hiker camper painter poet singer dancer drummer piano guitar " +
            "violin banjo trumpet flute melody rhythm tempo vinyl radio cosmic lunar solar stellar atomic quantum neon velvet silk satin " +
            "crimson scarlet azure cobalt amber ivory onyx jade violet indigo teal olive mint lime coral salmon peanut walnut almond " +
            "hazel acorn sprout seedling clover tulip rose daisy lily lotus orchid poppy iris ivy fern moss mushroom truffle goblin troll " +
            "ogre giant golem robot android cyborg alien martian zombie skeleton creeper spooky sneaky lucky happy grumpy sleepy dizzy " +
            "fuzzy fluffy bubbly giggle wiggle noodle doodle snicker biscuit pudding gravy nugget crumpet sprinkle marshmallow"
    ).split(" ");
}
