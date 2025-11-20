package awiant.connector;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber
public class StatusReporter {

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter >= 400) { // ~20s
            tickCounter = 0;
            reportStatus();
        }
    }

    private static void reportStatus() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        double meanTickTime = server.getAverageTickTime();
        double tps = Math.min(1000.0 / meanTickTime, 20.0);

        int playerCount = server.getPlayerList().getPlayerCount();
        String statusMessage = String.format("TPS: %.2f %s",
                tps, playerCount > 0 ? (" | Online: " + playerCount) : "");

        if (Connector.bridge != null) {
            Connector.bridge.sendEventString("status", statusMessage);
        }
    }
}