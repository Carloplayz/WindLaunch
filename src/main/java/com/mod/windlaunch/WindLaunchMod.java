package com.mod.windlaunch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.KeyBinding.Category;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

public class WindLaunchMod implements ClientModInitializer {
    private static KeyBinding launchKey;
    private static KeyBinding switchToMaceKey;
    private static KeyBinding autoMoveKey;
    private boolean switchToMaceEnabled = true;
    private boolean autoMoveEnabled = true;
    private String priorityMessage = null;

    @Override
    public void onInitializeClient() {
        launchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.launch",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                Category.MISC
        ));

        switchToMaceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.switchtomace",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                Category.MISC
        ));

        autoMoveKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.automove",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (launchKey.wasPressed()) {
                launchWindCharge(client);
            }

            while (switchToMaceKey.wasPressed()) {
                toggleSwitchToMace(client);
            }

            while (autoMoveKey.wasPressed()) {
                toggleAutoMove(client);
            }

            if (priorityMessage != null) {
                sendActionBarMessage(client, priorityMessage);
                priorityMessage = null;
            }
        });
    }

    private void launchWindCharge(MinecraftClient client) {
        if (client.player != null) {
            int windChargeSlot = -1;
            int maceSlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.getItem() == Items.WIND_CHARGE) {
                    windChargeSlot = i;
                } else if (stack.getItem() == Items.MACE) { // Replace 'Items.MACE' with the correct mace item
                    maceSlot = i;
                }
            }
            if (windChargeSlot != -1) {
                client.player.getInventory().setSelectedSlot(windChargeSlot);

                float currentPitch = client.player.getPitch();
                client.player.setPitch(90);

                client.player.jump();
                if (client.interactionManager != null) {
                    client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                }

                client.player.setPitch(currentPitch);

                if (switchToMaceEnabled && maceSlot != -1) {
                    client.player.getInventory().setSelectedSlot(maceSlot);
                }

                if (autoMoveEnabled) {
                    moveOneWindCharge(client, windChargeSlot);
                }

                checkWindChargeInventory(client);
            } else {
                setPriorityMessage("No wind charge found in hotbar");
            }
        }
    }

    private void toggleSwitchToMace(MinecraftClient client) {
        switchToMaceEnabled = !switchToMaceEnabled;
        String message = switchToMaceEnabled ? "Mace switching enabled" : "Mace switching disabled";
        sendActionBarMessage(client, message);
    }

    private void toggleAutoMove(MinecraftClient client) {
        autoMoveEnabled = !autoMoveEnabled;
        String message = autoMoveEnabled ? "Auto move enabled" : "Auto move disabled";
        sendActionBarMessage(client, message);
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
            sendActionBarMessage(client, "No wind charge found in inventory");
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
