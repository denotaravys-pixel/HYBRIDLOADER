package com.maliopt.integration;

import com.maliopt.MaliOptMod;
import net.fabricmc.loader.api.FabricLoader;

public final class IrisBridge {

    private static boolean available = false;

    private IrisBridge() {}

    public static void init() {
        available = FabricLoader.getInstance().isModLoaded("iris");
        if (available) {
            MaliOptMod.LOGGER.info("[MaliOpt] Iris detectado — IrisBridge activo");
        }
    }

    public static boolean isAvailable() { return available; }
}
