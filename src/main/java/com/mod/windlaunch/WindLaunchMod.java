package com.mod.windlaunch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public class WindLaunchMod implements ClientModInitializer {
    private static KeyBinding launchKey;
    private static KeyBinding switchToMaceKey;
    private static KeyBinding autoMoveKey;

    private boolean switchToMaceEnabled = true;
    private boolean autoMoveEnabled = true;
    private String priorityMessage = null;
    private boolean multiplayerAllowed = false;

    // === Handshake Protocol (byte-based) ===
    private static final byte MSG_HELLO = 1;
    private static final byte MSG_OK = 2;
    private static final byte MSG_DENY = 3;

    private static final Identifier HANDSHAKE_ID_RAW = Identifier.of("windlaunch", "handshake");
    private static final CustomPayload.Id<HandshakePayload> HANDSHAKE_ID = new CustomPayload.Id<>(HANDSHAKE_ID_RAW);

    private int handshakeDelayTicks = -1;
    private int handshakeAttempts = 0;

    // === CustomPayload for handshake ===
    public record HandshakePayload(byte code) implements CustomPayload {
        public static final PacketCodec<RegistryByteBuf, HandshakePayload> CODEC =
            PacketCodec.<RegistryByteBuf, HandshakePayload>of(
                (HandshakePayload payload, RegistryByteBuf buf) -> buf.writeByte(payload.code()),
                buf -> new HandshakePayload(buf.readByte())     // decoder: construct from buf
            );
    
        @Override
        public Id<? extends CustomPayload> getId() {
            return HANDSHAKE_ID;
        }
    }
    

    @Override
    public void onInitializeClient() {
        debugChat("WindLaunch initializing (client)");

        // Register keys
        launchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.launch",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                "category.windlaunch"
        ));
        switchToMaceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.switchtomace",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                "category.windlaunch"
        ));
        autoMoveKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.automove",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                "category.windlaunch"
        ));

        // Register payload codecs
        PayloadTypeRegistry.playC2S().register(HANDSHAKE_ID, HandshakePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HANDSHAKE_ID, HandshakePayload.CODEC);

        // On join, schedule handshake attempts
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            boolean isSingleplayer = client.isIntegratedServerRunning();
            multiplayerAllowed = isSingleplayer;
            if (isSingleplayer) {
                debugChat("Singleplayer detected: WindLaunch enabled");
            } else {
                debugChat("Multiplayer detected: scheduling handshake...");
                handshakeDelayTicks = 40; // wait 2s before first attempt
                handshakeAttempts = 0;
            }
        });

        // On disconnect
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            multiplayerAllowed = false;
            debugChat("Disconnected: WindLaunch disabled");
        });

        // Handle server response
        ClientPlayNetworking.registerGlobalReceiver(HANDSHAKE_ID, (payload, context) -> {
            byte code = payload.code();
            context.client().execute(() -> {
                debugChat("Received handshake code=" + code);
                if (code == MSG_OK) {
                    multiplayerAllowed = true;
                    debugChat("Handshake OK: WindLaunch enabled");
                } else if (code == MSG_DENY) {
                    multiplayerAllowed = false;
                    debugChat("Handshake DENY: WindLaunch disabled");
                }
            });
        });

        // Tick loop
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Retry handshake if needed
            if (handshakeDelayTicks > 0) {
                handshakeDelayTicks--;
            } else if (handshakeDelayTicks == 0 && handshakeAttempts < 5 && !multiplayerAllowed) {
                ClientPlayNetworking.send(new HandshakePayload(MSG_HELLO));
                debugChat("Sent handshake HELLO (attempt " + (handshakeAttempts + 1) + ")");
                handshakeAttempts++;
                handshakeDelayTicks = 40; // wait again before next attempt
            }

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

    // === gameplay logic (unchanged) ===
    private void launchWindCharge(MinecraftClient client) {
        if (client.player != null) {
            if (!isModAllowed(client)) {
                debugChat("Action blocked: WindLaunch disabled by server");
                return;
            }
            int windChargeSlot = -1;
            int maceSlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.getItem() == Items.WIND_CHARGE) windChargeSlot = i;
                else if (stack.getItem() == Items.MACE) maceSlot = i;
            }
            if (windChargeSlot != -1) {
                debugChat("Launching wind charge from slot " + windChargeSlot);
                client.player.getInventory().setSelectedSlot(windChargeSlot);
                float pitch = client.player.getPitch();
                client.player.setPitch(90);
                client.player.jump();
                if (client.interactionManager != null) {
                    client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                }
                client.player.setPitch(pitch);

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
        sendActionBarMessage(client, switchToMaceEnabled ? "Mace switching enabled" : "Mace switching disabled");
    }

    private void toggleAutoMove(MinecraftClient client) {
        autoMoveEnabled = !autoMoveEnabled;
        sendActionBarMessage(client, autoMoveEnabled ? "Auto move enabled" : "Auto move disabled");
    }

    private void moveOneWindCharge(MinecraftClient client, int targetSlot) {
        if (client.player == null || client.interactionManager == null) return;
        int invSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) {
                invSlot = i; break;
            }
        }
        if (invSlot == -1) return;
        ItemStack targetStack = client.player.getInventory().getStack(targetSlot);
        if (targetStack.isEmpty() || (targetStack.getItem() == Items.WIND_CHARGE && targetStack.getCount() < targetStack.getMaxCount())) {
            client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, invSlot, 0, SlotActionType.PICKUP, client.player);
            client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, targetSlot < 9 ? 36 + targetSlot : targetSlot, 0, SlotActionType.PICKUP, client.player);
            if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) {
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, invSlot, 0, SlotActionType.PICKUP, client.player);
            }
        }
    }

    private void checkWindChargeInventory(MinecraftClient client) {
        if (client.player == null) return;
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() == Items.WIND_CHARGE) total += stack.getCount();
        }
        if (total <= 32) setPriorityMessage("Warning: Low on wind charges! Only " + total + " left.");
    }

    private void sendActionBarMessage(MinecraftClient client, String message) {
        if (client.player != null) client.player.sendMessage(Text.literal(message), true);
    }

    private void setPriorityMessage(String message) {
        if (priorityMessage == null) priorityMessage = message;
    }

    private boolean isModAllowed(MinecraftClient client) {
        boolean singleplayer = client.isIntegratedServerRunning();
        return singleplayer || multiplayerAllowed;
    }

    private void debugChat(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[WindLaunch] " + msg), false);
        }
    }
}
