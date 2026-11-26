package me.cosmic.fake;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/** /pvpbot gui: every setting as a clickable item, split into pages. */
final class SettingsGui implements Listener {

    static final class Holder implements InventoryHolder {
        final int page; final Map<Integer, String> slots = new HashMap<>(); Inventory inv;
        Holder(int page) { this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    record Page(String title, Material icon, String[] keys) {}
    static final Page[] PAGES = {
            new Page("PvP Tech", Material.NETHERITE_SWORD, new String[]{"criticals", "crit-spam", "s-tap", "jump-reset", "knockback", "stun", "stun-web", "web-escape",
                    "anti-mace", "pearls", "teamwork", "fullbox", "cart-pvp", "crystal-pvp", "anchor-pvp", "mace", "ranged", "cobwebs", "retreat", "bhop",
                    "shield-break", "shield-mace", "arrow-prediction", "ranged-strafe", "ranged-retreat"}),
            new Page("Gear & Auto", Material.TOTEM_OF_UNDYING, new String[]{"auto-armor", "auto-weapon", "auto-totem", "totem-priority", "auto-shield", "prefer-sword",
                    "auto-eat", "auto-potion", "auto-mend", "drop-armor", "drop-weapon", "clear-on-remove"}),
            new Page("Targeting", Material.TARGET, new String[]{"combat", "revenge", "auto-target", "target-players", "target-bots", "target-mobs", "friendly-fire",
                    "attack-invincible"}),
            new Page("Bots", Material.PLAYER_HEAD, new String[]{"random-nick", "bot-leave-on-death", "safe-spawn", "idle", "special-names", "profile-lagg-fix",
                    "max-mass-spawn", "view-distance", "idle-radius", "drop-distance", "move-speed", "interval"}),
            new Page("Skill & Aim", Material.SPYGLASS, new String[]{"skill", "aim-speed", "miss-chance", "mistake-chance", "melee-range", "attack-cooldown",
                    "crit-fall-ticks", "shield-break-chance", "shield-hold-ticks", "shield-raise-ticks", "bow-draw-ticks", "ranged-min-range",
                    "ranged-optimal-range", "ranged-max-range"}),
    };

    static final Map<String, String> INFO = new HashMap<>();
    static {
        String[][] d = {
                {"criticals", "Jump hits for crits"}, {"crit-spam", "Crit nearly every hit"}, {"s-tap", "Tap S after sprint hits"},
                {"jump-reset", "Jump when hit = less knockback"}, {"knockback", "Bots take melee knockback"}, {"stun", "Axe breaks shield, instant follow-up"},
                {"stun-web", "Web you up close"}, {"web-escape", "Water bucket out of webs"}, {"anti-mace", "Look up + shield vs mace"},
                {"pearls", "Pearl to catch up"}, {"teamwork", "Spread out, help teammates"}, {"fullbox", "Box you in when webbed"},
                {"cart-pvp", "Crossbow cart / insta-cart"}, {"crystal-pvp", "End crystals"}, {"anchor-pvp", "Respawn anchors"},
                {"mace", "Wind charge + mace"}, {"ranged", "Bows and crossbows"}, {"cobwebs", "Webs while retreating"},
                {"retreat", "Run when low"}, {"bhop", "Bunny hop when chasing"}, {"random-nick", "Random nick + skin on spawn"},
                {"bot-leave-on-death", "Bots leave when they die"}, {"auto-target", "Attack anyone nearby"}, {"skill", "Default difficulty (101 = marlowww)"},
                {"max-mass-spawn", "Max bots per mass-spawn"}, {"aim-speed", "Degrees per tick"}, {"interval", "Think every N ticks"},
        };
        for (String[] x : d) INFO.put(x[0], x[1]);
    }

    private final Store st;
    SettingsGui(Store st) { this.st = st; }

    void open(Player p, int page) {
        page = Math.floorMod(page, PAGES.length);
        Holder h = new Holder(page);
        Inventory inv = Bukkit.createInventory(h, 54, "§8Bot Settings §7» §0" + PAGES[page].title());
        h.inv = inv;
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        // tabs on the top row
        for (int i = 0; i < PAGES.length; i++) {
            ItemStack tab = item(PAGES[i].icon(), (i == page ? "§a§l" : "§e") + PAGES[i].title(), List.of(i == page ? "§7You're here" : "§7Click to open"));
            if (i == page) glow(tab);
            inv.setItem(i * 2, tab);
            h.slots.put(i * 2, "#page" + i);
        }
        int slot = 9;
        for (String k : PAGES[page].keys()) {
            if (slot >= 45) break;
            Object v = st.set.get(k);
            if (v == null) continue;
            inv.setItem(slot, v instanceof Boolean b ? toggle(k, b) : number(k, ((Number) v).doubleValue()));
            h.slots.put(slot, k);
            slot++;
        }
        inv.setItem(45, item(Material.ARROW, "§e« Previous page", List.of())); h.slots.put(45, "#prev");
        inv.setItem(49, item(Material.BARRIER, "§cClose", List.of())); h.slots.put(49, "#close");
        inv.setItem(53, item(Material.ARROW, "§eNext page »", List.of())); h.slots.put(53, "#next");
        p.openInventory(inv);
    }

    ItemStack toggle(String k, boolean on) {
        List<String> lore = new ArrayList<>();
        if (INFO.containsKey(k)) lore.add("§7" + INFO.get(k));
        lore.add(""); lore.add(on ? "§a● ON" : "§c○ OFF"); lore.add("§8Click to toggle");
        return item(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "§a" : "§c") + k, lore);
    }

    ItemStack number(String k, double v) {
        double[] r = Store.RANGE.get(k);
        List<String> lore = new ArrayList<>();
        if (INFO.containsKey(k)) lore.add("§7" + INFO.get(k));
        lore.add("");
        lore.add("§fValue: §e" + Store.fmt(v) + (k.equals("skill") && v >= 101 ? " §d(marlowww)" : ""));
        lore.add("§8Range " + Store.fmt(r[0]) + " - " + Store.fmt(r[1]) + " · default " + Store.fmt(((Number) Store.DEFAULTS.get(k)).doubleValue()));
        lore.add(""); lore.add("§7Left +" + Store.fmt(step(k)) + " · Right -" + Store.fmt(step(k)));
        lore.add("§7Shift = x10 · Middle/Drop = default");
        ItemStack it = item(Material.CLOCK, "§b" + k, lore);
        it.setAmount((int) Math.max(1, Math.min(64, Math.round(v))));
        return it;
    }

    static double step(String k) {
        double[] r = Store.RANGE.get(k);
        double span = r[1] - r[0];
        if (span <= 1.01) return 0.05;
        if (span <= 10) return 0.1;
        return 1;
    }

    static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(name); im.setLore(lore);
        im.addItemFlags(ItemFlag.values());
        it.setItemMeta(im);
        return it;
    }
    static void glow(ItemStack it) { ItemMeta im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        String k = h.slots.get(e.getRawSlot());
        if (k == null) return;
        if (!((CosmicBots) org.bukkit.Bukkit.getPluginManager().getPlugin("CosmicBots")).allowed(p)) { p.closeInventory(); return; }
        switch (k) {
            case "#prev" -> { open(p, h.page - 1); click(p); return; }
            case "#next" -> { open(p, h.page + 1); click(p); return; }
            case "#close" -> { p.closeInventory(); return; }
        }
        if (k.startsWith("#page")) { open(p, Integer.parseInt(k.substring(5))); click(p); return; }
        Object v = st.set.get(k);
        if (v instanceof Boolean b) st.setSetting(k, String.valueOf(!b));
        else if (v instanceof Number n) {
            double[] r = Store.RANGE.get(k);
            double nv;
            if (e.getClick() == ClickType.MIDDLE || e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP) nv = ((Number) Store.DEFAULTS.get(k)).doubleValue();
            else {
                double s = step(k) * (e.isShiftClick() ? 10 : 1);
                nv = n.doubleValue() + (e.isRightClick() ? -s : s);
            }
            nv = Math.max(r[0], Math.min(r[1], Math.round(nv * 100) / 100.0));
            st.setSetting(k, Store.fmt(nv));
        }
        Object now = st.set.get(k);
        e.getInventory().setItem(e.getRawSlot(), now instanceof Boolean b ? toggle(k, b) : number(k, ((Number) now).doubleValue()));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, now instanceof Boolean b && !b ? 0.7f : 1.3f);
    }
    private static void click(Player p) { p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1f); }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true); }
}
