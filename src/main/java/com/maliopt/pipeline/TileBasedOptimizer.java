package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

/**
 * TileBasedOptimizer — Fase 2
 *
 * Explora o modelo TBDR do Mali-G52 MC2:
 * - Tile memory é ultra-rápida, dentro da GPU, sem acesso DRAM
 * - Cada tile (ex: 16x16 pixels) é processado completamente antes do próximo
 * - glInvalidateFramebuffer diz ao driver que não precisa de gravar para DRAM
 * - GL_EXT_shader_pixel_local_storage (Fase 3) usa esta tile memory directamente
 *
 * Fase 2 prepara as flags e estruturas para Fase 3 usar PLS em shaders.
 */
public class TileBasedOptimizer {

    private static boolean initialized    = false;

    // Attachments a invalidar no fim do frame
    // Depth e Stencil nunca precisam de ir para DRAM entre frames
    private static final int[] DEPTH_STENCIL_ATTACHMENTS = {
        GL30.GL_DEPTH_ATTACHMENT,
        GL30.GL_STENCIL_ATTACHMENT
    };

    // Todos os attachments — para invalidar no fim de um render pass completo
    private static final int[] ALL_ATTACHMENTS = {
        GL30.GL_COLOR_ATTACHMENT0,
        GL30.GL_DEPTH_ATTACHMENT,
        GL30.GL_STENCIL_ATTACHMENT
    };

    public static void init() {
        if (initialized || !GPUDetector.isMaliGPU()) return;
        if (!MaliOptConfig.enableTileOptimizer) return;
        initialized = true;

        MaliOptMod.LOGGER.info("[MaliOpt] TileBasedOptimizer iniciado");
        logCapabilities();
    }

    private static void logCapabilities() {
        MaliOptMod.LOGGER.info("[MaliOpt] TBDR capabilities:");

        if (ExtensionActivator.hasDiscardFramebuffer)
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ Depth/Stencil discard — bandwidth salvo por frame");

        if (ExtensionActivator.hasShaderPixelLocalStorage)
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ Pixel Local Storage — pronto para Fase 3");
        else
            MaliOptMod.LOGGER.info("[MaliOpt]   ❌ Pixel Local Storage — driver não expõe (GL4ES)");

        if (ExtensionActivator.hasFramebufferFetch)
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ Framebuffer Fetch — post-process grátis (Fase 4)");

        if (ExtensionActivator.hasFramebufferFetchDepth)
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ Framebuffer Fetch Depth — SSAO possível (Fase 4)");
    }

    /**
     * Chamado no FIM de cada frame.
     * Invalida depth e stencil — o Mali TBDR não precisa de gravar estes
     * para DRAM entre frames. Poupa 2-4 MB/frame de bandwidth.
     */
    public static void onFrameEnd() {
        if (!initialized) return;
        if (!ExtensionActivator.hasDiscardFramebuffer) return;

        try {
            // GL 4.3 = glDiscardFramebufferEXT em GLES — GL4ES traduz
            GL43.glInvalidateFramebuffer(GL30.GL_FRAMEBUFFER, DEPTH_STENCIL_ATTACHMENTS);
        } catch (Exception ignored) {
            // Falha silenciosa — nunca bloquear rendering
        }
    }

    /**
     * Chamado no FIM de um render pass auxiliar completo
     * (ex: shadow map, reflection render).
     * Invalida todos os attachments — o conteúdo foi consumido pelo pass principal.
     */
    public static void onAuxPassEnd() {
        if (!initialized) return;
        if (!ExtensionActivator.hasDiscardFramebuffer) return;

        try {
            GL43.glInvalidateFramebuffer(GL30.GL_FRAMEBUFFER, ALL_ATTACHMENTS);
        } catch (Exception ignored) {}
    }

    /**
     * Verifica se o pipeline PLS (Pixel Local Storage) está disponível.
     * Fase 3 vai usar isto para lighting deferred sem roundtrip DRAM.
     */
    public static boolean isPLSAvailable() {
        return ExtensionActivator.hasShaderPixelLocalStorage;
    }

    /**
     * Verifica se Framebuffer Fetch está disponível.
     * Fase 4 usa para bloom, blur, AO sem blit separado.
     */
    public static boolean isFramebufferFetchAvailable() {
        return ExtensionActivator.hasFramebufferFetch;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
