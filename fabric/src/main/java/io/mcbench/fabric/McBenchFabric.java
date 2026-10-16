package io.mcbench.fabric;

import io.mcbench.McBench;
import net.fabricmc.api.ClientModInitializer;

public class McBenchFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        McBench.init();
    }
}
