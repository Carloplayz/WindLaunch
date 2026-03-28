package com.mod.windlaunch;

import java.lang.reflect.Method;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import java.nio.file.Path;

public class WindLaunchMod implements ClientModInitializer {
    private static KeyMapping launchKey;
    private static KeyMapping switchToMaceKey;
    private static KeyMapping autoMoveKey;
    private static volatile Method minecraftClientDoItemUse;
    private static final float PITCH_STATIONARY_DEGREES = 90.0f;
    private boolean switchToMaceEnabled = true;
    private boolean autoMoveEnabled = true;
    private Path configPath;
    private String priorityMessage = null;
    private int lastNoInventoryWindChargeMessageTick = Integer.MIN_VALUE;
    private int queuedLaunchPresses = 0;
    private int queuedWindChargeSlot = -1;
    private int queuedMaceSlot = -1;
    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Identifier.parse("windlaunch:windlaunch"));

    @Override
    public void onInitializeClient() {
        launchKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.windlaunch.launch",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KEY_CATEGORY
        ));

        switchToMaceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.windlaunch.switchtomace",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KEY_CATEGORY
        ));

        autoMoveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.windlaunch.automove",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KEY_CATEGORY
        ));

        configPath = FabricLoader.getInstance().getConfigDir().resolve("windlaunch.json");
        WindLaunchConfig config = WindLaunchConfig.load(configPath);
        switchToMaceEnabled = config.switchToMaceEnabled;
        autoMoveEnabled = config.autoMoveEnabled;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (launchKey.consumeClick()) {
                queuedLaunchPresses++;
            }

            while (switchToMaceKey.consumeClick()) {
                toggleSwitchToMace(client);
            }

            while (autoMoveKey.consumeClick()) {
                toggleAutoMove(client);
            }

            processQueuedLaunch(client);

            if (priorityMessage != null) {
                sendActionBarMessage(client, priorityMessage);
                priorityMessage = null;
            }
        });
    }

    private void processQueuedLaunch(Minecraft client) {
        if (client.player == null) {
            queuedLaunchPresses = 0;
            queuedWindChargeSlot = -1;
            queuedMaceSlot = -1;
            return;
        }

        if (queuedLaunchPresses <= 0) return;

        if (client.player.getCooldowns().isOnCooldown(new ItemStack(Items.WIND_CHARGE))) {
            queuedLaunchPresses = 0;
            return;
        }

        int windChargeSlot = findHotbarSlot(client, Items.WIND_CHARGE);
        if (windChargeSlot == -1) {
            queuedLaunchPresses--;
            setPriorityMessage("No wind charge found in hotbar");
            return;
        }

        queuedWindChargeSlot = windChargeSlot;
        queuedMaceSlot = findHotbarSlot(client, Items.MACE);
        queuedLaunchPresses--;

        if (client.player.onGround()) {
            client.player.jumpFromGround();
        }

        executeLaunchUsePhase(client);
        queuedWindChargeSlot = -1;
        queuedMaceSlot = -1;
    }

    private void executeLaunchUsePhase(Minecraft client) {
        if (client.player == null) return;

        int windChargeSlot = queuedWindChargeSlot;
        if (windChargeSlot < 0
                || windChargeSlot > 8
                || client.player.getInventory().getItem(windChargeSlot).getItem() != Items.WIND_CHARGE) {
            windChargeSlot = findHotbarSlot(client, Items.WIND_CHARGE);
        }

        if (windChargeSlot == -1) {
            setPriorityMessage("No wind charge found in hotbar");
            return;
        }

        try {
            java.lang.reflect.Field selectedField = client.player.getInventory().getClass().getDeclaredField("selected");
            selectedField.setAccessible(true);
            int originalSelectedSlot = selectedField.getInt(client.player.getInventory());
            selectedField.setInt(client.player.getInventory(), windChargeSlot);
            
            float currentPitch = client.player.getXRot();
            client.player.setXRot(PITCH_STATIONARY_DEGREES);

            doVanillaItemUseOrFallback(client);

            client.player.setXRot(currentPitch);

            if (switchToMaceEnabled && queuedMaceSlot != -1) {
                selectedField.setInt(client.player.getInventory(), queuedMaceSlot);
            } else {
                selectedField.setInt(client.player.getInventory(), originalSelectedSlot);
            }
        } catch (Exception e) {}

        if (autoMoveEnabled) {
            moveOneWindCharge(client, windChargeSlot);
        }

        checkWindChargeInventory(client);
    }

    private static int findHotbarSlot(Minecraft client, Item item) {
        if (client.player == null) return -1;
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() == item) {
                slot = i;
            }
        }
        return slot;
    }

    private static void doVanillaItemUseOrFallback(Minecraft client) {
        if (!tryDoVanillaItemUse(client) && client.gameMode != null && client.player != null) {
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        }
    }

    private static boolean tryDoVanillaItemUse(Minecraft client) {
        try {
            Method method = minecraftClientDoItemUse;
            if (method == null) {
                method = Minecraft.class.getDeclaredMethod("startUseItem");
                method.setAccessible(true);
                minecraftClientDoItemUse = method;
            }
            method.invoke(client);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void toggleSwitchToMace(Minecraft client) {
        switchToMaceEnabled = !switchToMaceEnabled;
        persistConfig();
        String message = switchToMaceEnabled ? "Mace switching enabled" : "Mace switching disabled";
        sendActionBarMessage(client, message);
    }

    private void toggleAutoMove(Minecraft client) {
        autoMoveEnabled = !autoMoveEnabled;
        persistConfig();
        String message = autoMoveEnabled ? "Auto move enabled" : "Auto move disabled";
        sendActionBarMessage(client, message);
    }

    private void persistConfig() {
        Path path = configPath;
        if (path == null) return;
        WindLaunchConfig config = new WindLaunchConfig();
        config.switchToMaceEnabled = switchToMaceEnabled;
        config.autoMoveEnabled = autoMoveEnabled;
        config.save(path);
    }

    private void moveOneWindCharge(Minecraft client, int targetSlot) {
        if (client.player == null || client.gameMode == null) return;

        int inventoryWindChargeSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getItem(i).getItem() == Items.WIND_CHARGE) {
                inventoryWindChargeSlot = i;
                break;
            }
        }

        if (inventoryWindChargeSlot == -1) {
            if (client.player.tickCount - lastNoInventoryWindChargeMessageTick >= 40) {
                setPriorityMessage("No wind charge found in inventory");
                lastNoInventoryWindChargeMessageTick = client.player.tickCount;
            }
            return;
        }

        ItemStack targetStack = client.player.getInventory().getItem(targetSlot);

        if (targetStack.isEmpty() || (targetStack.getItem() == Items.WIND_CHARGE && targetStack.getCount() < targetStack.getMaxStackSize())) {
            // Use reflection safely for handleInventoryMouseClick
            try {
                for (java.lang.reflect.Method m : client.gameMode.getClass().getMethods()) {
                    if (m.getName().equals("handleContainerInput") && m.getParameterCount() == 5) {
                        Class<?> enumClass = m.getParameterTypes()[3];
                        Object pickupEnum = enumClass.getEnumConstants()[0]; // PICKUP is ordinal 0
                        
                        m.invoke(client.gameMode, client.player.inventoryMenu.containerId, inventoryWindChargeSlot, 0, pickupEnum, client.player);
                        m.invoke(client.gameMode, client.player.inventoryMenu.containerId, targetSlot < 9 ? 36 + targetSlot : targetSlot, 0, pickupEnum, client.player);
                        
                        if (!client.player.containerMenu.getCarried().isEmpty()) {
                            m.invoke(client.gameMode, client.player.inventoryMenu.containerId, inventoryWindChargeSlot, 0, pickupEnum, client.player);
                        }
                        break;
                    }
                }
            } catch (Exception e) {}
        }
    }

    private void checkWindChargeInventory(Minecraft client) {
        if (client.player == null) return;

        int totalWindCharges = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() == Items.WIND_CHARGE) {
                totalWindCharges += stack.getCount();
            }
        }

        if (totalWindCharges <= 32) {
            setPriorityMessage("Warning: Low on wind charges! Only " + totalWindCharges + " left.");
        }
    }

    private void sendActionBarMessage(Minecraft client, String message) {
        try {
            client.player.sendSystemMessage(Component.literal(message));
        } catch (Exception e) {}
    }

    private void setPriorityMessage(String message) {
        if (priorityMessage == null) {
            priorityMessage = message;
        }
    }
}
