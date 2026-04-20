package com.maliopt.gpu;

import com.maliopt.MaliOptMod;
import org.lwjgl.opengl.GL11;

public class GPUDetector {

    private static String  cachedRenderer   = null;
    private static String  cachedVendor     = null;
    private static String  cachedVersion    = null;
    private static String  cachedExtensions = null;
    private static Boolean isMali           = null;

    // ---------------------------------------------------------------
    // Leitura segura — GL4ES pode retornar null, nunca crashar
    // ---------------------------------------------------------------
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
        if (cachedRenderer == null)
            cachedRenderer = safeGet(GL11.GL_RENDERER);
        return cachedRenderer;
    }

    public static String getVendor() {
        if (cachedVendor == null)
            cachedVendor = safeGet(GL11.GL_VENDOR);
        return cachedVendor;
    }

    public static String getVersion() {
        if (cachedVersion == null)
            cachedVersion = safeGet(GL11.GL_VERSION);
        return cachedVersion;
    }

    public static String getExtensions() {
        if (cachedExtensions == null)
            cachedExtensions = safeGet(GL11.GL_EXTENSIONS);
        return cachedExtensions;
    }

    // ---------------------------------------------------------------
    // Detecção
    // ---------------------------------------------------------------
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

    public static boolean hasExtension(String ext) {
        return getExtensions().contains(ext);
    }

    // Para testes unitários
    public static void resetCache() {
        cachedRenderer   = null;
        cachedVendor     = null;
        cachedVersion    = null;
        cachedExtensions = null;
        isMali           = null;
    }
}
