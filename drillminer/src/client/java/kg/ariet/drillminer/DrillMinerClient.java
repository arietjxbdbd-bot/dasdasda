package kg.ariet.drillminer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class DrillMinerClient implements ClientModInitializer {
    public static final MinerConfig CONFIG = new MinerConfig();
    public static final MinerController CONTROLLER = new MinerController(CONFIG);
    private static KeyBinding openGui;

    @Override
    public void onInitializeClient() {
        CONFIG.load();
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("drillminer", "controls"));
        openGui = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.drillminer.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGui.wasPressed()) {
                client.setScreen(new MinerScreen());
            }
            CONTROLLER.tick(client);
        });
    }

    public static void openScreen() {
        MinecraftClient.getInstance().setScreen(new MinerScreen());
    }
}
