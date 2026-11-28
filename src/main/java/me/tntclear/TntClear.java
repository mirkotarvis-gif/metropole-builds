package me.tntclear;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import java.io.File;
import java.io.RandomAccessFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** /tnt clear: removes lit TNT, TNT minecarts and TNT blocks in every loaded chunk of every world. */
public final class TntClear extends JavaPlugin implements Listener {
    private boolean explosionsOff;
    private boolean busy;

    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    /** with /tnt explosions off, no TNT can blow up at all */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent e) {
        if (explosionsOff && (e.getEntity() instanceof TNTPrimed || e.getEntity() instanceof ExplosiveMinecart)) { e.setCancelled(true); e.getEntity().remove(); }
    }

    // ------------------------------------------------------------ whole-world sweep (also chunks nobody is near)
    private boolean sweeping; private boolean explosionsBefore;
    private int sweepRemoved, sweepLit, sweepDone, sweepTotal;

    /** while sweeping, any TNT entity that loads with a chunk is deleted right away */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!sweeping) return;
        for (Entity en : e.getEntities()) if (en instanceof TNTPrimed || en instanceof ExplosiveMinecart) { en.remove(); sweepLit++; }
    }

    /** every chunk that has ever been generated in this world, read from the region files */
    static List<int[]> savedChunks(World w) {
        File base = w.getWorldFolder();
        File dir = new File(base, "region");
        if (w.getEnvironment() == World.Environment.NETHER && new File(base, "DIM-1/region").isDirectory()) dir = new File(base, "DIM-1/region");
        if (w.getEnvironment() == World.Environment.THE_END && new File(base, "DIM1/region").isDirectory()) dir = new File(base, "DIM1/region");
        List<int[]> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.startsWith("r.") && n.endsWith(".mca"));
        if (files == null) return out;
        for (File f : files) {
            String[] parts = f.getName().split("\\.");
            int rx, rz;
            try { rx = Integer.parseInt(parts[1]); rz = Integer.parseInt(parts[2]); } catch (Exception ex) { continue; }
            if (f.length() < 8192) continue;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                for (int i = 0; i < 1024; i++) { int off = raf.readInt(); if (off != 0) out.add(new int[]{rx * 32 + (i & 31), rz * 32 + (i >> 5)}); }
            } catch (Exception ignored) {}
        }
        return out;
    }

    void sweepWorld(CommandSender s, World w) {
        if (busy || sweeping) { s.sendMessage("§eAlready clearing, wait for it to finish."); return; }
        List<int[]> chunks = savedChunks(w);
        if (chunks.isEmpty()) { s.sendMessage("§cCouldn't read any saved chunks for " + w.getName() + "."); return; }
        sweeping = true; busy = true; explosionsBefore = explosionsOff; explosionsOff = true;
        sweepRemoved = 0; sweepLit = clearLit(); sweepDone = 0; sweepTotal = chunks.size();
        s.sendMessage("§7Sweeping §f" + sweepTotal + "§7 chunks in §f" + w.getName() + "§7. TNT can't explode until it's done.");
        Deque<int[]> todo = new ArrayDeque<>(chunks);
        BlockData tnt = Material.TNT.createBlockData();
        int[] inFlight = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            while (inFlight[0] < 8 && !todo.isEmpty()) {
                int[] c = todo.poll(); inFlight[0]++;
                boolean wasLoaded = w.isChunkLoaded(c[0], c[1]);
                w.getChunkAtAsync(c[0], c[1], false).whenComplete((ch, err) -> Bukkit.getScheduler().runTask(this, () -> {
                    inFlight[0]--; sweepDone++;
                    if (ch == null) return;
                    ChunkSnapshot snap = ch.getChunkSnapshot(false, false, false);
                    if (snap.contains(tnt)) {
                        int minY = w.getMinHeight(), maxY = w.getMaxHeight();
                        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = minY; y < maxY; y++)
                            if (snap.getBlockType(x, y, z) == Material.TNT) { ch.getBlock(x, y, z).setType(Material.AIR, false); sweepRemoved++; }
                    }
                    for (Entity en : ch.getEntities()) if (en instanceof TNTPrimed || en instanceof ExplosiveMinecart) { en.remove(); sweepLit++; }
                    if (!wasLoaded) w.unloadChunkRequest(c[0], c[1]);
                }));
            }
            if (sweepDone > 0 && sweepDone % 2000 == 0) s.sendMessage("§7..." + sweepDone + "/" + sweepTotal + " chunks, " + sweepRemoved + " TNT removed so far");
            if (todo.isEmpty() && inFlight[0] == 0) {
                task.cancel();
                Bukkit.getScheduler().runTaskLater(this, () -> { // let entity data of the last chunks finish loading
                    sweepLit += clearLit();
                    sweeping = false; busy = false; explosionsOff = explosionsBefore;
                    s.sendMessage("§aDone! §fWhole world §acleared: §f" + sweepRemoved + "§a TNT blocks and §f" + sweepLit + "§a lit TNT / TNT minecarts in §f" + sweepTotal + "§a chunks.");
                }, 60L);
            }
        }, 1L, 1L);
    }

    int clearLit() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) for (Entity en : w.getEntities())
            if (en instanceof TNTPrimed || en instanceof ExplosiveMinecart) { en.remove(); n++; }
        return n;
    }

    /** scans loaded chunks a few per tick so the server doesn't freeze */
    void clearBlocks(CommandSender s) {
        if (busy) { s.sendMessage("§eStill clearing TNT blocks, wait a moment."); return; }
        busy = true;
        BlockData tnt = Material.TNT.createBlockData();
        Deque<Chunk> todo = new ArrayDeque<>();
        for (World w : Bukkit.getWorlds()) todo.addAll(Arrays.asList(w.getLoadedChunks()));
        int total = todo.size();
        int[] removed = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            long end = System.nanoTime() + 15_000_000L; // ~15 ms per tick
            while (!todo.isEmpty() && System.nanoTime() < end) {
                Chunk c = todo.poll();
                if (!c.isLoaded()) continue;
                ChunkSnapshot snap = c.getChunkSnapshot(false, false, false);
                if (!snap.contains(tnt)) continue;
                World w = c.getWorld(); int minY = w.getMinHeight(), maxY = w.getMaxHeight();
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = minY; y < maxY; y++)
                    if (snap.getBlockType(x, y, z) == Material.TNT) { c.getBlock(x, y, z).setType(Material.AIR, false); removed[0]++; }
            }
            if (todo.isEmpty()) {
                task.cancel(); busy = false;
                int lit = clearLit(); // anything lit while we scanned
                s.sendMessage("§aRemoved §f" + removed[0] + "§a TNT blocks in §f" + total + "§a loaded chunks" + (lit > 0 ? "§a (+" + lit + " more lit TNT)" : "") + ".");
            }
        }, 1L, 1L);
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        if (sub.equals("clear")) {
            String what = a.length > 1 ? a[1].toLowerCase() : "world";
            if (what.equals("world")) {
                World w = a.length > 2 ? Bukkit.getWorld(a[2]) : (s instanceof org.bukkit.entity.Player p ? p.getWorld() : Bukkit.getWorlds().get(0));
                if (w == null) { s.sendMessage("§cNo world called " + a[2]); return true; }
                sweepWorld(s, w); return true;
            }
            if (what.equals("loaded")) what = "all";
            if (what.equals("lit") || what.equals("all")) s.sendMessage("§aRemoved §f" + clearLit() + "§a lit TNT / TNT minecarts.");
            if (what.equals("blocks") || what.equals("all")) { s.sendMessage("§7Clearing TNT blocks in all loaded chunks..."); clearBlocks(s); }
            return true;
        }
        if (sub.equals("explosions") && a.length > 1) {
            explosionsOff = a[1].equalsIgnoreCase("off");
            if (explosionsOff) clearLit();
            s.sendMessage("§eTNT explosions: " + (explosionsOff ? "§cOFF (TNT is removed the moment it's lit)" : "§aON"));
            return true;
        }
        s.sendMessage("§e/tnt clear §7- remove ALL TNT in your whole world, every chunk ever generated");
        s.sendMessage("§e/tnt clear world <name> §7- same, for another world");
        s.sendMessage("§e/tnt clear loaded §7- quick: only chunks near players");
        s.sendMessage("§e/tnt clear lit §7- only lit TNT and TNT minecarts");
        s.sendMessage("§e/tnt clear blocks §7- only placed TNT blocks");
        s.sendMessage("§e/tnt explosions <on|off> §7- block every TNT explosion");
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> o = new ArrayList<>();
        if (a.length == 1) o.addAll(List.of("clear", "explosions"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("clear")) o.addAll(List.of("world", "loaded", "lit", "blocks"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("clear") && a[1].equalsIgnoreCase("world")) for (World w : Bukkit.getWorlds()) o.add(w.getName());
        else if (a.length == 2 && a[0].equalsIgnoreCase("explosions")) o.addAll(List.of("on", "off"));
        o.removeIf(x -> !x.startsWith(a[a.length - 1].toLowerCase()));
        return o;
    }
}
