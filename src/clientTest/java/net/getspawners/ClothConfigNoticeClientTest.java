package net.getspawners;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.TitleScreen;

// This entrypoint must load without any Cloth Config classes on the runtime classpath.
public class ClothConfigNoticeClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (GetSpawnersModMenuIntegration.hasCompatibleClothConfig()) {
            throw new AssertionError("This regression requires missing or older Cloth Config");
        }
        var parent = context.computeOnClient(client -> new TitleScreen());
        context.runOnClient(client -> {
            var screen = new GetSpawnersModMenuIntegration().getModConfigScreenFactory().create(parent);
            if (!(screen instanceof AlertScreen)) {
                throw new AssertionError("Missing/older Cloth Config must show an alert instead of opening incompatible widgets");
            }
            client.setScreenAndShow(screen);
        });
        context.waitTicks(3);
        String scenario = FabricLoader.getInstance().isModLoaded("cloth-config") ? "older-cloth" : "missing-cloth";
        context.takeScreenshot("getspawners-" + scenario);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .findFirst().orElseThrow(() -> new AssertionError("Alert must provide a return button"));
            button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
            if (client.gui.screen() != parent) {
                throw new AssertionError("Closing the alert must return to the parent screen");
            }
        });
        context.waitTicks(2);
    }
}
