package me.vaan.movecraftPackets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.events.SignTranslateEvent;
import net.countercraft.movecraft.mapUpdater.update.CraftTranslateCommand;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Edit JVM Bytecode and Inject new Method that send Packets to update
 * the signs instead of forcing the server to update the blocks, this
 * allows some performance gains.
 */
public class SignPatch {

    public static class FunctionInterceptor {

        @RuntimeType
        public static void sendSignEvents(@This CraftTranslateCommand instance) {
            try {
                Field craftField = CraftTranslateCommand.class.getDeclaredField("craft");
                craftField.setAccessible(true);
                final Craft craft = (Craft) craftField.get(instance);

                Field worldField = CraftTranslateCommand.class.getDeclaredField("world");
                worldField.setAccessible(true);
                final World world = (World) worldField.get(instance);

                Object2ObjectMap<String[], List<MovecraftLocation>> signs = new Object2ObjectOpenCustomHashMap<>(new Hash.Strategy<>() {
                    @Override
                    public int hashCode(String[] strings) {
                        return Arrays.hashCode(strings);
                    }

                    @Override
                    public boolean equals(String[] a, String[] b) {
                        return Arrays.equals(a, b);
                    }
                });
                Map<MovecraftLocation, Sign> signStates = new HashMap<>();

                for (MovecraftLocation location : craft.getHitBox()) {
                    Block block = location.toBukkit(craft.getWorld()).getBlock();
                    if (!Tag.SIGNS.isTagged(block.getType())) {
                        continue;
                    }
                    BlockState state = block.getState();
                    if (state instanceof Sign sign) {
                        if (!signs.containsKey(sign.getLines()))
                            signs.put(sign.getLines(), new ArrayList<>());
                        signs.get(sign.getLines()).add(location);
                        signStates.put(location, sign);
                    }
                }

                for (Map.Entry<String[], List<MovecraftLocation>> entry : signs.entrySet()) {
                    SignTranslateEvent event = new SignTranslateEvent(craft, entry.getKey(), entry.getValue());
                    Bukkit.getServer().getPluginManager().callEvent(event);

                    //region Changed code
                    ProtocolManager pm = ProtocolLibrary.getProtocolManager();
                    for (MovecraftLocation location : entry.getValue()) {
                        Block block = location.toBukkit(craft.getWorld()).getBlock();
                        BlockState state = block.getState();
                        if (!(state instanceof Sign)) {
                            continue;
                        }

                        PacketContainer packet = pm.createPacket(PacketType.Play.Server.TILE_ENTITY_DATA);

                        BlockPosition blockPosition = new BlockPosition(block.getX(), block.getY(), block.getZ());
                        packet.getBlockPositionModifier().write(0, blockPosition);
                        packet.getIntegers().write(1, 9);

                        WrappedChatComponent[] chatComponents = new WrappedChatComponent[4];
                        for (int i = 0; i < 4; i++) {
                            chatComponents[i] = WrappedChatComponent.fromText(entry.getKey()[i]);
                        }
                        packet.getChatComponentArrays().write(0, chatComponents);

                        for (var living : location.toBukkit(world).getNearbyLivingEntities(60)) {
                            if (living instanceof Player player)
                                pm.sendServerPacket(player, packet);
                        }
                    }
                    //endregion
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void load() {
        try {
            replaceFunc();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void replaceFunc() {
        new ByteBuddy()
                .redefine(CraftTranslateCommand.class)
                .method(ElementMatchers.named("sendSignEvents").and(ElementMatchers.isPrivate()))
                .intercept(MethodDelegation.to(FunctionInterceptor.class))
                .make()
                .load(CraftTranslateCommand.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST);
    }
}
