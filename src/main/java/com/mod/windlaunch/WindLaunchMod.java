package com.mod.windlaunch;

import java.lang.reflect.Method;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.KeyBinding.Category;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

public class WindLaunchMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("WindLaunch");
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

    private enum HandshakeState {
        UNKNOWN,
        ALLOWED,
        DENIED
    }

    private static final byte MSG_HELLO = 1;
    private static final byte MSG_OK = 2;
    private static final byte MSG_DENY = 3;
    private static final Identifier HANDSHAKE_ID_RAW = Identifier.of("windlaunch", "handshake");
    private static final CustomPayload.Id<HandshakePayload> HANDSHAKE_ID = new CustomPayload.Id<>(HANDSHAKE_ID_RAW);
    private HandshakeState handshakeState = HandshakeState.UNKNOWN;
    private int handshakeDelayTicks = -1;
    private int handshakeAttempts = 0;
    private boolean missingPluginNotified = false;

    public record HandshakePayload(byte code) implements CustomPayload {

        public static final PacketCodec<RegistryByteBuf, HandshakePayload> CODEC
                = PacketCodec.of(
                        (HandshakePayload payload, RegistryByteBuf buf) -> buf.writeByte(payload.code()),
                        buf -> new HandshakePayload(buf.readByte())
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return HANDSHAKE_ID;
        }
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Client initializing WindLaunch");
        Category windLaunchCategory = Category.create(Identifier.of("windlaunch", "main"));
        launchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.launch",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                windLaunchCategory
        ));
        switchToMaceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.switchtomace",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                windLaunchCategory
        ));
        autoMoveKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.windlaunch.automove",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                windLaunchCategory
        ));

        configPath = FabricLoader.getInstance().getConfigDir().resolve("windlaunch.json");
        WindLaunchConfig config = WindLaunchConfig.load(configPath);
        switchToMaceEnabled = config.switchToMaceEnabled;
        autoMoveEnabled = config.autoMoveEnabled;

        PayloadTypeRegistry.playC2S().register(HANDSHAKE_ID, HandshakePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HANDSHAKE_ID, HandshakePayload.CODEC);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            boolean isSingleplayer = client.isIntegratedServerRunning();
            if (isSingleplayer) {
                handshakeState = HandshakeState.ALLOWED;
                handshakeDelayTicks = -1;
                handshakeAttempts = 0;
                LOGGER.info("Singleplayer detected: WindLaunch enabled");
                sendActionBarMessage(client, Text.literal("WindLaunch active (singleplayer)").formatted(Formatting.GREEN));
            } else {
                LOGGER.info("Multiplayer detected: scheduling handshake...");
                handshakeState = HandshakeState.UNKNOWN;
                missingPluginNotified = false;
                sendActionBarMessage(client, Text.literal("WindLaunch awaiting server opt-in...").formatted(Formatting.YELLOW));
                handshakeDelayTicks = 40;
                handshakeAttempts = 0;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            handshakeState = HandshakeState.UNKNOWN;
            LOGGER.info("Disconnected: WindLaunch disabled");
            sendActionBarMessage(client, Text.literal("WindLaunch disabled").formatted(Formatting.RED));
        });
        ClientPlayNetworking.registerGlobalReceiver(HANDSHAKE_ID, (payload, context) -> {
            byte code = payload.code();
            context.client().execute(() -> {
                LOGGER.info("Received handshake code {}", code);
                if (code == MSG_OK) {
                    handshakeState = HandshakeState.ALLOWED;
                    LOGGER.info("Handshake OK: WindLaunch enabled");
                    sendLocalChatMessage(
                            context.client(),
                            Text.literal("Yeeey! Seems like the the server owner is your friend. The mod shall work as intended")
                                    .formatted(Formatting.GREEN)
                    );
                } else if (code == MSG_DENY) {
                    handshakeState = HandshakeState.DENIED;
                    LOGGER.info("Handshake DENY: WindLaunch disabled");
                    sendLocalChatMessage(
                            context.client(),
                            Text.literal("Huuuh? The server denied? Seems like you forgot to give yourself use permission. Ooor, it might be your evil server owner's work. Want revenge? Get v1 without server restrictions here: https://github.com/Carloplayz/WindLaunch/releases")
                                    .formatted(Formatting.RED)
                    );
                }
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) return;
            if (handshakeDelayTicks > 0) {
                handshakeDelayTicks--; 
            } else if (handshakeDelayTicks == 0
                    && handshakeAttempts < 5
                    && handshakeState == HandshakeState.UNKNOWN) {
                ClientPlayNetworking.send(new HandshakePayload(MSG_HELLO));
                LOGGER.info("Sent handshake HELLO (attempt {})", handshakeAttempts + 1);
                handshakeAttempts++;
                handshakeDelayTicks = 40;
            } else if (handshakeDelayTicks == 0
                    && handshakeAttempts >= 5
                    && handshakeState == HandshakeState.UNKNOWN
                    && !missingPluginNotified) {
                missingPluginNotified = true;
                handshakeState = HandshakeState.DENIED;
                handshakeDelayTicks = -1;
                sendLocalChatMessage(
                        client,
                        Text.literal("Seems like the server doesn't have opt-in plugin installed. If this a public server you might not wanna use this mod. Though if you really want to I won't mind. Use v1 without server restrictions here: https://github.com/Carloplayz/WindLaunch/releases")
                                .formatted(Formatting.YELLOW)
                );
            }

            while (launchKey.wasPressed()) {
                queuedLaunchPresses++;
            }
            processQueuedLaunch(client);

            while (switchToMaceKey.wasPressed()) {
                toggleSwitchToMace(client);
            }
            while (autoMoveKey.wasPressed()) {
                toggleAutoMove(client);
            }

            if (priorityMessage != null) {
                sendActionBarMessage(client, Text.literal(priorityMessage));
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

        if (!isModAllowed(client)) {
            queuedLaunchPresses = 0;
            setPriorityMessage("WindLaunch blocked (server opt-in required)");
            return;
        }

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
        sendActionBarMessage(client, Text.literal(switchToMaceEnabled ? "Mace switching enabled" : "Mace switching disabled")
                .formatted(switchToMaceEnabled ? Formatting.GREEN : Formatting.RED));
        LOGGER.info("Mace switching {}", switchToMaceEnabled ? "enabled" : "disabled");
    }

    private void toggleAutoMove(MinecraftClient client) {
        autoMoveEnabled = !autoMoveEnabled;
        persistConfig();
        sendActionBarMessage(client, Text.literal(autoMoveEnabled ? "Auto move enabled" : "Auto move disabled")
                .formatted(autoMoveEnabled ? Formatting.GREEN : Formatting.RED));
        LOGGER.info("Auto move {}", autoMoveEnabled ? "enabled" : "disabled");
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
        if (client.player == null || client.interactionManager == null) {
            return;
        }
        int invSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) {
                invSlot = i;
                break;
            }
        }
        if (invSlot == -1) {
            if (client.player.age - lastNoInventoryWindChargeMessageTick >= 40) {
                setPriorityMessage("No wind charge found in inventory");
                lastNoInventoryWindChargeMessageTick = client.player.age;
            }
            return;
        }
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
        if (client.player == null) {
            return;
        }
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() == Items.WIND_CHARGE) {
                total += stack.getCount();
            }
        }
        if (total <= 32) {
            setPriorityMessage("Low on wind charges: " + total + " left");
        }
    }

    private void sendActionBarMessage(MinecraftClient client, Text message) {
        if (client.player != null && client.world != null) {
            client.player.sendMessage(message, true);
        }
    }

    private void sendLocalChatMessage(MinecraftClient client, Text message) {
        if (client.player != null && client.world != null) {
            client.player.sendMessage(message, false);
        }
    }

    private void setPriorityMessage(String message) {
        if (priorityMessage == null) {
            priorityMessage = message;
        }
    }

    private boolean isModAllowed(MinecraftClient client) {
        return client.isIntegratedServerRunning() || handshakeState == HandshakeState.ALLOWED;
    }
}
