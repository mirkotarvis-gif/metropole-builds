package me.regen;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * /regen <chunks>: puts the terrain around you back exactly the way the world seed generated it.
 * A hidden copy of the world (same seed, same settings) generates fresh chunks, and they're copied over
 * block by block with their biomes, so it lines up perfectly with the untouched land around it.
 */
public final class TerrainRegen extends JavaPlugin {

    private final Map<UUID, long[]> pendingConfirm = new HashMap<>(); // player -> {radius, expiresAtMillis}
    private Job job;

    final class Job {
        final World dst, src; final CommandSender who; final Deque<int[]> todo = new ArrayDeque<>();
        final Deque<Object[]> ready = new ArrayDeque<>(); int inFlight, done, total; BukkitTask task; long started = System.currentTimeMillis();
        Job(World dst, World src, CommandSender who) { this.dst = dst; this.src = src; this.who = who; }
    }

    @Override public void onDisable() { if (job != null) finish(false); }

    private World tempWorld(World w) {
        String name = "regen_tmp_" + w.getName();
        World t = Bukkit.getWorld(name);
        if (t != null) return t;
        WorldCreator wc = new WorldCreator(name).copy(w);
        wc.seed(w.getSeed());
        wc.generateStructures(true);
        t = wc.createWorld();
        if (t != null) { t.setAutoSave(false); t.setKeepSpawnInMemory(false); }
        return t;
    }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { help(s); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("cancel")) {
            if (job == null) { s.sendMessage("§7Nothing is regenerating."); return true; }
            finish(true); s.sendMessage("§eRegeneration cancelled."); return true;
        }
        if (sub.equals("status")) {
            if (job == null) s.sendMessage("§7Nothing is regenerating.");
            else s.sendMessage("§eRegenerating: §f" + job.done + "/" + job.total + " §echunks.");
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("§cRun it in-game, standing where you want the terrain fixed."); return true; }
        int r;
        try { r = Integer.parseInt(sub); } catch (NumberFormatException e) { help(s); return true; }
        if (r < 0 || r > 20) { s.sendMessage("§cPick 0-20 chunks (0 = just the chunk you're in)."); return true; }
        if (job != null) { s.sendMessage("§cAlready regenerating (" + job.done + "/" + job.total + "). /regen cancel to stop it."); return true; }
        int side = 2 * r + 1, chunks = side * side;
        boolean confirmed = a.length > 1 && a[1].equalsIgnoreCase("confirm");
        long[] pend = pendingConfirm.get(p.getUniqueId());
        if (!confirmed) {
            pendingConfirm.put(p.getUniqueId(), new long[]{r, System.currentTimeMillis() + 30000});
            s.sendMessage("§6This resets §f" + chunks + " chunks §6(" + side * 16 + "×" + side * 16 + " blocks) around you to natural terrain.");
            s.sendMessage("§cEVERYTHING built in that area will be gone. §7Type §f/regen " + r + " confirm §7within 30 seconds.");
            return true;
        }
        if (pend == null || pend[0] != r || pend[1] < System.currentTimeMillis()) { s.sendMessage("§cRun §f/regen " + r + " §cfirst, then confirm."); return true; }
        pendingConfirm.remove(p.getUniqueId());

        World dst = p.getWorld();
        s.sendMessage("§7Preparing a fresh copy of the world (first time can take a few seconds)...");
        World src = tempWorld(dst);
        if (src == null) { s.sendMessage("§cCouldn't create the temporary world."); return true; }
        job = new Job(dst, src, s);
        int cx0 = p.getLocation().getBlockX() >> 4, cz0 = p.getLocation().getBlockZ() >> 4;
        List<int[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) list.add(new int[]{cx0 + dx, cz0 + dz, dx * dx + dz * dz});
        list.sort(Comparator.comparingInt(x -> x[2])); // closest first
        job.todo.addAll(list); job.total = list.size();
        s.sendMessage("§aRegenerating §f" + job.total + "§a chunks... §7(/regen status, /regen cancel)");
        job.task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        return true;
    }

    private void tick() {
        Job j = job; if (j == null) return;
        // keep a few fresh chunks generating in the background
        while (j.inFlight < 6 && !j.todo.isEmpty()) {
            int[] c = j.todo.poll(); j.inFlight++;
            j.src.getChunkAtAsync(c[0], c[1], true).whenComplete((ch, err) -> Bukkit.getScheduler().runTask(this, () -> {
                if (job != j) return;
                j.inFlight--;
                if (ch != null) j.ready.add(new Object[]{c, ch.getChunkSnapshot(true, true, false)});
                else j.done++;
            }));
        }
        // copy as many as fit in ~20 ms
        long end = System.nanoTime() + 20_000_000L;
        while (!j.ready.isEmpty() && (System.nanoTime() < end)) {
            Object[] o = j.ready.poll();
            int[] c = (int[]) o[0];
            apply(j.dst, c[0], c[1], (ChunkSnapshot) o[1]);
            j.done++;
            try { j.src.unloadChunk(c[0], c[1], false); } catch (Throwable ignored) {}
        }
        if (j.done > 0 && j.done % 25 == 0 && j.ready.isEmpty()) j.who.sendMessage("§7..." + j.done + "/" + j.total + " chunks");
        if (j.done >= j.total && j.inFlight == 0 && j.ready.isEmpty()) finish(false);
    }

    private void apply(World w, int cx, int cz, ChunkSnapshot snap) {
        Chunk ch = w.getChunkAt(cx, cz);
        int min = w.getMinHeight(), max = w.getMaxHeight();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = min; y < max; y++) {
            BlockData nd = snap.getBlockData(x, y, z);
            Block b = ch.getBlock(x, y, z);
            if (!b.getBlockData().equals(nd)) b.setBlockData(nd, false);
        }
        // biomes are stored per 4x4x4
        for (int x = 0; x < 16; x += 4) for (int z = 0; z < 16; z += 4) for (int y = min; y < max; y += 4) {
            Biome bi = snap.getBiome(x, y, z);
            w.setBiome((cx << 4) + x, y, (cz << 4) + z, bi);
        }
        for (Entity e : ch.getEntities()) if (e instanceof Item || e instanceof TNTPrimed || e instanceof FallingBlock || e instanceof ExperienceOrb) e.remove();
        try { w.refreshChunk(cx, cz); } catch (Throwable ignored) {}
    }

    private void finish(boolean cancelled) {
        Job j = job; job = null;
        if (j == null) return;
        if (j.task != null) j.task.cancel();
        if (!cancelled) j.who.sendMessage("§aDone! Regenerated §f" + j.done + "§a chunks in " + (System.currentTimeMillis() - j.started) / 1000 + "s.");
        // throw the temporary world away so it doesn't use disk or memory
        World t = j.src; File folder = t.getWorldFolder();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (job != null && job.src == t) return;
            if (Bukkit.unloadWorld(t, false)) deleteFolder(folder.toPath());
        }, 40L);
    }

    private static void deleteFolder(Path p) {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private void help(CommandSender s) {
        s.sendMessage("§e/regen <chunks> §7- reset the terrain around you to how the seed made it");
        s.sendMessage("§7  0 = your chunk, 1 = 3×3 chunks, 2 = 5×5, 5 = 11×11 ... max 20");
        s.sendMessage("§e/regen status §7| §e/regen cancel");
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return new ArrayList<>(List.of("1", "2", "3", "5", "8", "status", "cancel"));
        if (a.length == 2 && a[0].matches("\\d+")) return new ArrayList<>(List.of("confirm"));
        return new ArrayList<>();
    }
}
