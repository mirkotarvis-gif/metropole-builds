package me.keepxp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Players keep their levels and XP bar on death, and no XP orbs drop. */
public final class KeepXP extends JavaPlugin implements Listener {
    private boolean on;

    @Override public void onEnable() {
        saveDefaultConfigIfMissing();
        on = getConfig().getBoolean("enabled", true);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void saveDefaultConfigIfMissing() {
        if (!getConfig().contains("enabled")) { getConfig().set("enabled", true); saveConfig(); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (!on) return;
        e.setKeepLevel(true);
        e.setDroppedExp(0);
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length > 0) { on = a[0].equalsIgnoreCase("on") || a[0].equalsIgnoreCase("true"); getConfig().set("enabled", on); saveConfig(); }
        s.sendMessage("§eKeep XP on death: " + (on ? "§aON" : "§cOFF"));
        return true;
    }
}
