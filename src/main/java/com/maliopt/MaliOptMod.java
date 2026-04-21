package com.maliopt;

import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.MobileGluesDetector;
import com.maliopt.mixin.GameOptionsAccessor;
import com.maliopt.pipeline.MaliPipelineOptimizer;
import com.maliopt.pipeline.ShaderCacheManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MaliOptMod implements ClientModInitializer {

    public static final String MOD_ID = "maliopt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Mínimo MC 1.21.1: simulationDistance >= 5
    private static final int MAX_RENDER_DISTANCE     = 3;
    private static final int MAX_SIMULATION_DISTANCE = 5;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[MaliOpt] Registando eventos...");
        MaliOptConfig.load();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            // ── PASSO 1: Detectar renderer ────────────────────────────
            MobileGluesDetector.detect();

            // ── PASSO 2: Info base da GPU ──────────────────────────────
            LOGGER.info("[MaliOpt] Cliente iniciado — verificando GPU...");
            LOGGER.info("[MaliOpt] Renderer : {}", GPUDetector.getRenderer());
            LOGGER.info("[MaliOpt] Vendor   : {}", GPUDetector.getVendor());
            LOGGER.info("[MaliOpt] Version  : {}", GPUDetector.getVersion());

            if (MobileGluesDetector.isActive()) {
                LOGGER.info("[MaliOpt] GL Layer : MobileGlues v{} ✅",
                    formatMGVersion(MobileGluesDetector.mobileGluesVersion));
            } else {
                LOGGER.info("[MaliOpt] GL Layer : GL4ES (extensões Mali limitadas)");
            }

            // ── PASSO 3: Verificar GPU e activar optimizações ──────────
            if (GPUDetector.isMaliGPU()) {
                LOGGER.info("[MaliOpt] ✅ GPU Mali detectada — activando optimizações");

                ExtensionActivator.activateAll();
                MaliPipelineOptimizer.init();
                ShaderCacheManager.init();

                // ── PASSO 4: Teste de suporte PLS ─────────────────────
                testPLSSupport();

                // ── PASSO 5: Forçar distâncias seguras ────────────────
                forceDistances(client);

            } else {
                LOGGER.info("[MaliOpt] ⚠️  GPU não é Mali — mod inactivo");
            }
        });
    }

    /**
     * Testa se o compilador GLSL do MobileGlues aceita
     * GL_EXT_shader_pixel_local_storage antes de usar em produção.
     * Temporário — remover após confirmação no log.
     */
    private static void testPLSSupport() {
        String fragSrc =
            "#version 310 es\n" +
            "#extension GL_EXT_shader_pixel_local_storage : require\n" +
            "precision mediump float;\n" +
            "__pixel_localEXT FragData {\n" +
            "    layout(rgba8) lowp vec4 albedo;\n" +
            "} pls;\n" +
            "void main() {\n" +
            "    pls.albedo = vec4(1.0, 0.0, 0.0, 1.0);\n" +
            "}\n";

        int shader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(shader, fragSrc);
        GL20.glCompileShader(shader);

        int status = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
        String log  = GL20.glGetShaderInfoLog(shader);
        GL20.glDeleteShader(shader);

        if (status == GL11.GL_TRUE) {
            LOGGER.info("[MaliOpt] ✅ PLS COMPILOU — extensão aceite pelo driver");
        } else {
            LOGGER.warn("[MaliOpt] ❌ PLS FALHOU — log: {}", log);
        }
    }

    /**
     * Força render/simulation distance para os limites seguros do Mali-G52 MC2.
     * Usa @Accessor mixin — funciona em produção (sem remap de strings).
     */
    private static void forceDistances(MinecraftClient client) {
        if (client == null || client.options == null) return;
        try {
            boolean changed = false;
            GameOptionsAccessor acc = (GameOptionsAccessor)(Object) client.options;

            SimpleOption<Integer> viewDist = acc.maliopt_getViewDistance();
            int currentRender = viewDist.getValue();
            if (currentRender > MAX_RENDER_DISTANCE) {
                viewDist.setValue(MAX_RENDER_DISTANCE);
                LOGGER.info("[MaliOpt] Render distance: {} → {} ✅", currentRender, MAX_RENDER_DISTANCE);
                changed = true;
            } else {
                LOGGER.info("[MaliOpt] Render distance: {} (já dentro do limite)", currentRender);
            }

            SimpleOption<Integer> simDist = acc.maliopt_getSimulationDistance();
            int currentSim = simDist.getValue();
            if (currentSim > MAX_SIMULATION_DISTANCE) {
                simDist.setValue(MAX_SIMULATION_DISTANCE);
                LOGGER.info("[MaliOpt] Simulation distance: {} → {} ✅", currentSim, MAX_SIMULATION_DISTANCE);
                changed = true;
            } else {
                LOGGER.info("[MaliOpt] Simulation distance: {} (já dentro do limite)", currentSim);
            }

            if (changed) {
                client.options.write();
                LOGGER.info("[MaliOpt] Distâncias guardadas em options.txt ✅");
            }

        } catch (Exception e) {
            LOGGER.warn("[MaliOpt] forceDistances falhou: {}", e.getMessage());
        }
    }

    /** Converte 1304 → "1.3.4" */
    private static String formatMGVersion(int v) {
        if (v <= 0) return "desconhecida";
        int major = v / 1000;
        int minor = (v % 1000) / 100;
        int patch = v % 100;
        return major + "." + minor + "." + patch;
    }
}
