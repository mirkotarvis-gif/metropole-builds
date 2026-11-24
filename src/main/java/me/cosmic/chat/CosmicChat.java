package me.cosmic.chat;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class CosmicChat implements ClientModInitializer {
    private static boolean hiding = false;
    private static KeyMapping key;
    /** Chat plugins often send player chat as plain server text: "<Name> hi", "Name: hi", "[Rank] Name » hi". */
    private static final Pattern LOOKS_LIKE_CHAT = Pattern.compile(
            "^(\\[[^\\]]{1,24}\\]\\s*)*<?[A-Za-z0-9_]{3,16}>?\\s*(:|»|>>|->)\\s.*|^<[A-Za-z0-9_]{3,16}>\\s.*");

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("cosmicchat.txt"); }

    @Override public void onInitializeClient() {
        try { hiding = Files.readString(file()).trim().equals("hiding=true"); } catch (Exception ignored) {}

        key = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.cosmicchat.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (key.consumeClick()) {
                hiding = !hiding;
                try { Files.writeString(file(), "hiding=" + hiding); } catch (Exception ignored) {}
                mc.gui.setOverlayMessage(Component.literal(hiding
                        ? "§cPlayer chat HIDDEN §7(deaths, joins/leaves, commands still show)"
                        : "§aPlayer chat SHOWN"), false);
            }
        });

        // Real player chat (signed or not): hide while recording.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) -> !hiding);

        // Server/system messages: keep deaths, joins/leaves, command replies, action bar.
        // Only hide ones that look like a player talking (chat plugins send those as system text).
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!hiding || overlay) return true;
            String s = message.getString().replaceAll("§.", "").trim();
            return !LOOKS_LIKE_CHAT.matcher(s).matches();
        });
    }
}
