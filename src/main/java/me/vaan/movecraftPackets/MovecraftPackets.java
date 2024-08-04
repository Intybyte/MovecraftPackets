package me.vaan.movecraftPackets;

import org.bukkit.plugin.java.JavaPlugin;

public final class MovecraftPackets extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        SignPatch.load();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
