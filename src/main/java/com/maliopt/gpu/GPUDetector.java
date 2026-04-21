package com.maliopt.gpu;

import com.maliopt.MaliOptMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class GPUDetector {

    private static String      cachedRenderer   = null;
    private static String      cachedVendor     = null;
    private static String      cachedVersion    = null;
    private static Set<String> cachedExtensions = null;
    private static Boolean     isMali           = null;

    private static String safeGet(int param) {
        try {
            String val = GL11.glGetString(param);
            return val != null ? val : "";
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] glGetString({}) falhou: {}", param, e.getMessage());
            return "";
        }
    }

    public static String getRenderer() {
        if (cachedRenderer == null) cachedRenderer = safeGet(GL11.GL_RENDERER);
        return cachedRenderer;
    }

    public static String getVendor() {
        if (cachedVendor == null) cachedVendor = safeGet(GL11.GL_VENDOR);
        return cachedVendor;
    }

    public static String getVersion() {
        if (cachedVersion == null) cachedVersion = safeGet(GL11.GL_VERSION);
        return cachedVersion;
    }

    /**
     * FASE 3 — Detecção de extensões com prioridade MobileGlues.
     *
     * Ordem de prioridade:
     * 1. MobileGlues activo + backend_string_getter_access
     *    → extensões GLES reais do driver Mali (102 extensões)
     * 2. glGetStringi loop (GL4ES / qualquer outro renderer)
     *    → extensões desktop emuladas (87 extensões — problema conhecido)
     * 3. Fallback glGetString
     *    → último recurso
     */
    public static Set<String> getAllExtensions() {
        if (cachedExtensions != null) return cachedExtensions;

        // PRIORIDADE 1 — MobileGlues backend access
        // Retorna as 102 extensões GLES reais do Mali-G52 MC2
        if (MobileGluesDetector.isActive() && MobileGluesDetector.hasBackendAccess()) {
            Set<String> backendExts = MobileGluesDetector.getBackendExtensions();
            if (!backendExts.isEmpty()) {
                MaliOptMod.LOGGER.info("[MaliOpt] Extensões via MobileGlues backend: {}", backendExts.size());
                cachedExtensions = backendExts;
                return cachedExtensions;
            }
        }

        // PRIORIDADE 2 — glGetStringi loop (GL4ES / sem MobileGlues)
        Set<String> exts = new HashSet<>();
        try {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            if (count > 0) {
                for (int i = 0; i < count; i++) {
                    String ext = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                    if (ext != null && !ext.isEmpty()) exts.add(ext);
                }
                MaliOptMod.LOGGER.info("[MaliOpt] glGetStringi: {} extensões encontradas", exts.size());
            }
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] glGetStringi falhou: {}", e.getMessage());
        }

        // PRIORIDADE 3 — Fallback string plana
        if (exts.isEmpty()) {
            String flat = safeGet(GL11.GL_EXTENSIONS);
            if (!flat.isEmpty()) {
                Collections.addAll(exts, flat.split(" "));
                MaliOptMod.LOGGER.info("[MaliOpt] Fallback glGetString: {} extensões", exts.size());
            }
        }

        cachedExtensions = Collections.unmodifiableSet(exts);
        return cachedExtensions;
    }

    public static boolean hasExtension(String ext) {
        return getAllExtensions().contains(ext);
    }

    public static boolean isMaliGPU() {
        if (isMali == null) {
            String r = getRenderer().toLowerCase();
            String v = getVendor().toLowerCase();
            isMali = r.contains("mali") || v.contains("arm");
        }
        return isMali;
    }

    public static boolean isBifrost() {
        String r = getRenderer();
        return r.contains("G52") || r.contains("G57")
            || r.contains("G68") || r.contains("G76")
            || r.contains("G77");
    }

    public static boolean isValhall() {
        String r = getRenderer();
        return r.contains("G310") || r.contains("G510")
            || r.contains("G610") || r.contains("G710")
            || r.contains("G715");
    }

    public static void resetCache() {
        cachedRenderer   = null;
        cachedVendor     = null;
        cachedVersion    = null;
        cachedExtensions = null;
        isMali           = null;
    }
}
