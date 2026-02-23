package com.mod.windlaunch;

import java.lang.reflect.Method;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.KeyBinding.Category;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import java.nio.file.Path;

public class WindLaunchMod implements ClientModInitializer {
    private static KeyBinding launchKey;
    private static KeyBinding switchToMaceKey;
    private static KeyBinding autoMoveKey;
    private static volatile Method minecraftClientDoItemUse;
    private static final float PITCH_STATIONARY_DEGREES = 90.0f;
    private static final ItemStack WIND_CHARGE_COOLDOWN_STACK = new ItemStack(Items.WIND_CHARGE);
    private boolean switchToMaceEnabled = true;
    private boolean autoMoveEnabled = true;
    private Path configPath;
    private String priorityMessage = null;
    private int lastNoInventoryWindChargeMessageTick = Integer.MIN_VALUE;
    private int queuedLaunchPresses = 0;
    private int queuedWindChargeSlot = -1;
    private int queuedMaceSlot = -1;
    private static final Category KEY_CATEGORY = Category.create(Identifier.of("windlaunch", "windlaunch"));

    @Override
    public void onInitializeClient() {
        launchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.launch",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                KEY_CATEGORY
        ));

        switchToMaceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.switchtomace",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                KEY_CATEGORY
        ));

        autoMoveKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.automove",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                KEY_CATEGORY
        ));

        configPath = FabricLoader.getInstance().getConfigDir().resolve("windlaunch.json");
        WindLaunchConfig config = WindLaunchConfig.load(configPath);
        switchToMaceEnabled = config.switchToMaceEnabled;
        autoMoveEnabled = config.autoMoveEnabled;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (launchKey.wasPressed()) {
                queuedLaunchPresses++;
            }

            while (switchToMaceKey.wasPressed()) {
                toggleSwitchToMace(client);
            }

            while (autoMoveKey.wasPressed()) {
                toggleAutoMove(client);
            }

            processQueuedLaunch(client);

            if (priorityMessage != null) {
                sendActionBarMessage(client, priorityMessage);
                priorityMessage = null;
            }
        });
    }

    private void processQueuedLaunch(MinecraftClient client) {
        if (client.player == null) {
            queuedLaunchPresses = 0;
            queuedWindChargeSlot = -1;
            queuedMaceSlot = -1;
            return;
        }

        if (queuedLaunchPresses <= 0) return;

        if (client.player.getItemCooldownManager().isCoolingDown(WIND_CHARGE_COOLDOWN_STACK)) {
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

        if (client.player.isOnGround()) {
            client.player.jump();
        }

        executeLaunchUsePhase(client);
        queuedWindChargeSlot = -1;
        queuedMaceSlot = -1;
    }

    private void executeLaunchUsePhase(MinecraftClient client) {
        if (client.player == null) return;

        int windChargeSlot = queuedWindChargeSlot;
        if (windChargeSlot < 0
                || windChargeSlot > 8
                || client.player.getInventory().getStack(windChargeSlot).getItem() != Items.WIND_CHARGE) {
            windChargeSlot = findHotbarSlot(client, Items.WIND_CHARGE);
        }

        if (windChargeSlot == -1) {
            setPriorityMessage("No wind charge found in hotbar");
            return;
        }

        int originalSelectedSlot = client.player.getInventory().getSelectedSlot();
        client.player.getInventory().setSelectedSlot(windChargeSlot);

        float currentPitch = client.player.getPitch();
        client.player.setPitch(PITCH_STATIONARY_DEGREES);

        doVanillaItemUseOrFallback(client);

        client.player.setPitch(currentPitch);

        if (switchToMaceEnabled && queuedMaceSlot != -1) {
            client.player.getInventory().setSelectedSlot(queuedMaceSlot);
        } else {
            client.player.getInventory().setSelectedSlot(originalSelectedSlot);
        }

        if (autoMoveEnabled) {
            moveOneWindCharge(client, windChargeSlot);
        }

        checkWindChargeInventory(client);
    }

    private static int findHotbarSlot(MinecraftClient client, net.minecraft.item.Item item) {
        if (client.player == null) return -1;
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() == item) {
                slot = i;
            }
        }
        return slot;
    }

    private static void doVanillaItemUseOrFallback(MinecraftClient client) {
        if (!tryDoVanillaItemUse(client) && client.interactionManager != null && client.player != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        }
    }

    private static boolean tryDoVanillaItemUse(MinecraftClient client) {
        try {
            Method method = minecraftClientDoItemUse;
            if (method == null) {
                method = MinecraftClient.class.getDeclaredMethod("doItemUse");
                method.setAccessible(true);
                minecraftClientDoItemUse = method;
            }
            method.invoke(client);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private void toggleSwitchToMace(MinecraftClient client) {
        switchToMaceEnabled = !switchToMaceEnabled;
        persistConfig();
        String message = switchToMaceEnabled ? "Mace switching enabled" : "Mace switching disabled";
        sendActionBarMessage(client, message);
    }

    private void toggleAutoMove(MinecraftClient client) {
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

    private void moveOneWindCharge(MinecraftClient client, int targetSlot) {
        if (client.player == null || client.interactionManager == null) return;

        int inventoryWindChargeSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) {
                inventoryWindChargeSlot = i;
                break;
            }
        }

        if (inventoryWindChargeSlot == -1) {
            if (client.player.age - lastNoInventoryWindChargeMessageTick >= 40) {
                setPriorityMessage("No wind charge found in inventory");
                lastNoInventoryWindChargeMessageTick = client.player.age;
            }
            return;
        }

        ItemStack targetStack = client.player.getInventory().getStack(targetSlot);

        if (targetStack.isEmpty() || (targetStack.getItem() == Items.WIND_CHARGE && targetStack.getCount() < targetStack.getMaxCount())) {
            client.interactionManager.clickSlot(
                    client.player.playerScreenHandler.syncId,
                    inventoryWindChargeSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );

            client.interactionManager.clickSlot(
                    client.player.playerScreenHandler.syncId,
                    targetSlot < 9 ? 36 + targetSlot : targetSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
            
            if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) {
                client.interactionManager.clickSlot(
                        client.player.playerScreenHandler.syncId,
                        inventoryWindChargeSlot,
                        0,
                        SlotActionType.PICKUP,
                        client.player
                );
            }
        }
    }

    private void checkWindChargeInventory(MinecraftClient client) {
        if (client.player == null) return;

        int totalWindCharges = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() == Items.WIND_CHARGE) {
                totalWindCharges += stack.getCount();
            }
        }

        if (totalWindCharges <= 32) {
            setPriorityMessage("Warning: Low on wind charges! Only " + totalWindCharges + " left.");
        }
    }

    private void sendActionBarMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }

    private void setPriorityMessage(String message) {
        if (priorityMessage == null) {
            priorityMessage = message;
        }
    }
}
