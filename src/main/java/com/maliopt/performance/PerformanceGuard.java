package com.maliopt.performance;

import com.maliopt.MaliOptMod;
import com.maliopt.config.MaliOptVisualConfig;
import net.minecraft.client.MinecraftClient;

public final class PerformanceGuard {

    // Limiares internos — usados quando autoQuality=true
    private static final int FPS_DEGRADED = 20;
    private static final int FPS_LOW      = 35;
    private static final int FPS_MEDIUM   = 55;

    private static final int FRAMES_TO_DOWNGRADE = 60;
    private static final int FRAMES_TO_UPGRADE   = 120;

    public enum Quality { DEGRADED, LOW, MEDIUM, HIGH }

    private static Quality current      = Quality.HIGH;
    private static Quality target       = Quality.HIGH;
    private static int     frameCounter = 0;
    private static int     sampleSum    = 0;
    private static int     sampleCount  = 0;
    private static long    lastLogTime  = 0;

    private static final int SAMPLE_WINDOW = 20;

    private PerformanceGuard() {}

    public static void update(MinecraftClient mc) {
        if (mc == null) return;

        // Se autoQuality estiver desligado, mantém HIGH fixo
        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
        if (!cfg.autoQuality) {
            if (current != Quality.HIGH) current = Quality.HIGH;
            return;
        }

        int fps = mc.getCurrentFps();
        sampleSum   += fps;
        sampleCount++;

        if (sampleCount >= SAMPLE_WINDOW) {
            int avgFps = sampleSum / sampleCount;
            sampleSum   = 0;
            sampleCount = 0;
            evaluateFps(avgFps, cfg.targetFPS);
        }
    }

    private static void evaluateFps(int avgFps, int targetFps) {
        // Usa o targetFPS do menu como limiar de MEDIUM
        Quality desired = classify(avgFps, targetFps);

        if (desired == target) {
            frameCounter++;
            int threshold = (desired.ordinal() < current.ordinal())
                ? FRAMES_TO_DOWNGRADE : FRAMES_TO_UPGRADE;

            if (frameCounter >= threshold) {
                applyQuality(desired, avgFps);
                frameCounter = 0;
            }
        } else {
            target       = desired;
            frameCounter = 0;
        }
    }

    private static Quality classify(int fps, int targetFps) {
        if (fps < FPS_DEGRADED) return Quality.DEGRADED;
        if (fps < FPS_LOW)      return Quality.LOW;
        if (fps < targetFps)    return Quality.MEDIUM;
        return Quality.HIGH;
    }

    private static void applyQuality(Quality q, int fps) {
        if (q == current) return;
        Quality prev = current;
        current = q;

        long now = System.currentTimeMillis();
        if (now - lastLogTime > 3000) {
            MaliOptMod.LOGGER.info("[PerfGuard] {} → {} (FPS médio: {})", prev, current, fps);
            lastLogTime = now;
        }
    }

    // ── API consumida pelos passes ────────────────────────────────────

    public static boolean bloomEnabled() {
        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
        return cfg.bloomEnabled && current != Quality.DEGRADED;
    }

    public static boolean lightingPassEnabled() {
        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
        return cfg.lightingEnabled && current != Quality.DEGRADED;
    }

    public static float bloomIntensity() {
        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
        if (current == Quality.DEGRADED) return 0.0f;
        // Respeita slider do menu, mas escala com qualidade
        float base = cfg.bloomIntensity;
        return switch (current) {
            case LOW    -> base * 0.4f;
            case MEDIUM -> base * 0.7f;
            default     -> base;
        };
    }

    public static float bloomRadius() {
        return switch (current) {
            case DEGRADED -> 0.0f;
            case LOW      -> 0.8f;
            case MEDIUM   -> 1.2f;
            default       -> 1.8f;
        };
    }

    public static float bloomThreshold() {
        return switch (current) {
            case DEGRADED -> 1.0f;
            case LOW      -> 0.80f;
            case MEDIUM   -> 0.65f;
            default       -> 0.55f;
        };
    }

    public static float warmth() {
        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
        if (current == Quality.DEGRADED) return 0.0f;
        return switch (current) {
            case LOW    -> cfg.warmthStrength * 0.6f;
            default     -> cfg.warmthStrength;
        };
    }

    public static float ambientOcclusion() {
        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
        if (current == Quality.DEGRADED) return 0.0f;
        return switch (current) {
            case LOW    -> cfg.aoStrength * 0.5f;
            case MEDIUM -> cfg.aoStrength * 0.85f;
            default     -> cfg.aoStrength;
        };
    }

    public static Quality getCurrentQuality() { return current; }
    public static int     targetFpsForHigh()  { return FPS_MEDIUM; }
}
