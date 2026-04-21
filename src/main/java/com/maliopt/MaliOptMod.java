package com.maliopt;

import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.MobileGluesDetector;
import com.maliopt.pipeline.MaliPipelineOptimizer;
import com.maliopt.pipeline.ShaderCacheManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MaliOptMod implements ClientModInitializer {

    public static final String MOD_ID = "maliopt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Distância máxima permitida para render e simulação.
    // Acima deste valor o Mali-G52 MC2 fica sobrecarregado.
    private static final int MAX_RENDER_DISTANCE     = 3;
    private static final int MAX_SIMULATION_DISTANCE = 3;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[MaliOpt] Registando eventos...");

        // Config carrega sem GL — seguro aqui
        MaliOptConfig.load();

        // GL context SÓ existe após CLIENT_STARTED
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            // ── PASSO 1: Detectar renderer ────────────────────────────
            // MobileGluesDetector DEVE ser o primeiro a correr.
            // Precisa de contexto GL mas não depende de nenhuma outra classe.
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

                // Com MobileGlues, getAllExtensions() já retorna as 102
                // extensões GLES reais — ExtensionActivator vai ver tudo ✅
                ExtensionActivator.activateAll();
                MaliPipelineOptimizer.init();
                ShaderCacheManager.init();

                // ── PASSO 4: Forçar distâncias seguras ────────────────
                // O Mali-G52 MC2 tem 1916 MB device-local partilhados com
                // o sistema. Render e simulation distance acima de 3 causam
                // "Can't keep up" no server thread integrado por sobrecarga
                // de geração de chunks e lighting calculation.
                forceDistances(client);

            } else {
                LOGGER.info("[MaliOpt] ⚠️  GPU não é Mali — mod inactivo");
            }
        });
    }

    /**
     * Força render distance e simulation distance para MAX_RENDER_DISTANCE
     * e MAX_SIMULATION_DISTANCE se os valores actuais forem superiores.
     *
     * Só reduz — nunca aumenta. Se o utilizador tiver já valores menores,
     * esses são respeitados.
     *
     * Escreve as opções no disco para persistir entre sessões.
     */
    @SuppressWarnings("unchecked")
private static void forceDistances(MinecraftClient client) {
    if (client == null || client.options == null) return;

    try {
        boolean changed = false;

        // viewDistance — campo privado em GameOptions desde MC 1.20+
        java.lang.reflect.Field vdField =
            net.minecraft.client.option.GameOptions.class.getDeclaredField("viewDistance");
        vdField.setAccessible(true);
        net.minecraft.client.option.SimpleOption<Integer> viewDist =
            (net.minecraft.client.option.SimpleOption<Integer>) vdField.get(client.options);

        int currentRender = viewDist.getValue();
        if (currentRender > MAX_RENDER_DISTANCE) {
            viewDist.setValue(MAX_RENDER_DISTANCE);
            LOGGER.info("[MaliOpt] Render distance: {} → {} ✅", currentRender, MAX_RENDER_DISTANCE);
            changed = true;
        } else {
            LOGGER.info("[MaliOpt] Render distance: {} (já dentro do limite)", currentRender);
        }

        // simulationDistance — idem
        java.lang.reflect.Field sdField =
            net.minecraft.client.option.GameOptions.class.getDeclaredField("simulationDistance");
        sdField.setAccessible(true);
        net.minecraft.client.option.SimpleOption<Integer> simDist =
            (net.minecraft.client.option.SimpleOption<Integer>) sdField.get(client.options);

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

    } catch (NoSuchFieldException | IllegalAccessException e) {
        LOGGER.warn("[MaliOpt] forceDistances falhou via reflexão: {}", e.getMessage());
    }
}
    /** Converte 1340 → "1.3.4" */
    private static String formatMGVersion(int v) {
        if (v <= 0) return "desconhecida";
        int major = v / 1000;
        int minor = (v % 1000) / 100;
        int patch = v % 100;
        return major + "." + minor + "." + patch;
    }
}
