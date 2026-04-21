package com.maliopt.mixin;

import com.maliopt.MaliOptMod;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.pipeline.MaliPipelineOptimizer;
import com.maliopt.pipeline.ShaderCacheManager;
import com.maliopt.pipeline.TileBasedOptimizer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    private static boolean lateInitDone = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void maliopt$onRenderHead(RenderTickCounter tickCounter,
                                      boolean tick,
                                      CallbackInfo ci) {
        if (!GPUDetector.isMaliGPU()) return;

        if (!lateInitDone && !MaliPipelineOptimizer.isInitialized()) {
            MaliOptMod.LOGGER.info("[MaliOpt] Init tardio via render hook");
            MaliPipelineOptimizer.init();
            ShaderCacheManager.init();
            lateInitDone = true;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void maliopt$onRenderTail(RenderTickCounter tickCounter,
                                      boolean tick,
                                      CallbackInfo ci) {
        if (!GPUDetector.isMaliGPU()) return;
        // Fase 2 — delega para TileBasedOptimizer
        MaliPipelineOptimizer.onFrameEnd();
    }
}
