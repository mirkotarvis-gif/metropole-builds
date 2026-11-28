package me.noadv;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Iterator;

/** Blocks every advancement before it's granted, so there's no toast and no chat message. Recipe unlocks are left alone. */
public final class NoAdvancements extends JavaPlugin implements Listener {
    private boolean on;

    @Override public void onEnable() {
        saveDefaultConfig();
        on = getConfig().getBoolean("enabled", true);
        getServer().getPluginManager().registerEvents(this, this);
        applyGamerule();
    }

    private void applyGamerule() {
        for (World w : Bukkit.getWorlds()) w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, !on);
    }

    private static boolean recipe(Advancement a) { return a.getKey().getKey().startsWith("recipes/"); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCriterion(PlayerAdvancementCriterionGrantEvent e) {
        if (on && !recipe(e.getAdvancement())) e.setCancelled(true);
    }

    // safety net: anything that still slips through gets no message and is taken back
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDone(PlayerAdvancementDoneEvent e) {
        if (!on || recipe(e.getAdvancement())) return;
        e.message(null);
        revoke(e.getPlayer(), e.getAdvancement());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (on && getConfig().getBoolean("wipe-on-join", false)) wipe(e.getPlayer());
    }

    private void revoke(Player p, Advancement a) {
        AdvancementProgress pr = p.getAdvancementProgress(a);
        for (String c : pr.getAwardedCriteria()) pr.revokeCriteria(c);
    }

    private int wipe(Player p) {
        int n = 0;
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
            Advancement a = it.next();
            if (recipe(a) || a.getDisplay() == null) continue;
            if (!p.getAdvancementProgress(a).getAwardedCriteria().isEmpty()) { revoke(p, a); n++; }
        }
        return n;
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "on" -> { on = true; getConfig().set("enabled", true); saveConfig(); applyGamerule(); s.sendMessage(ChatColor.GREEN + "Advancements are now blocked."); }
            case "off" -> { on = false; getConfig().set("enabled", false); saveConfig(); applyGamerule(); s.sendMessage(ChatColor.YELLOW + "Advancements are back on."); }
            case "reset" -> {
                int total = 0;
                for (Player p : Bukkit.getOnlinePlayers()) total += wipe(p);
                s.sendMessage(ChatColor.GREEN + "Removed " + total + " advancements from everyone online.");
            }
            default -> s.sendMessage(ChatColor.GRAY + "Advancements are " + (on ? ChatColor.RED + "blocked" : ChatColor.GREEN + "on")
                    + ChatColor.GRAY + ". Use /noadv on|off|reset");
        }
        return true;
    }
}
