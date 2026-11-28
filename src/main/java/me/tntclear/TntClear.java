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
            String what = a.length > 1 ? a[1].toLowerCase() : "all";
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
        s.sendMessage("§e/tnt clear §7- remove ALL TNT (lit + blocks)");
        s.sendMessage("§e/tnt clear lit §7- only lit TNT and TNT minecarts");
        s.sendMessage("§e/tnt clear blocks §7- only placed TNT blocks");
        s.sendMessage("§e/tnt explosions <on|off> §7- block every TNT explosion");
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> o = new ArrayList<>();
        if (a.length == 1) o.addAll(List.of("clear", "explosions"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("clear")) o.addAll(List.of("all", "lit", "blocks"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("explosions")) o.addAll(List.of("on", "off"));
        o.removeIf(x -> !x.startsWith(a[a.length - 1].toLowerCase()));
        return o;
    }
}
