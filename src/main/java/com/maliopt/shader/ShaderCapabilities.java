package com.maliopt.shader;

import com.maliopt.MaliOptMod;
import com.maliopt.MaliOptNative;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.ExtensionActivator;

public final class ShaderCapabilities {

    // ── Extensões de framebuffer ─────────────────────────────────────
    public static boolean PLS              = false;
    public static boolean FB_FETCH         = false;
    public static boolean FB_FETCH_DEPTH   = false;

    // ── Compressão de texturas ───────────────────────────────────────
    public static boolean ASTC             = false;
    public static boolean ASTC_HDR         = false;

    // ── Render targets ───────────────────────────────────────────────
    public static boolean MULTISAMPLED_RT  = false;

    // ── Arquitectura GPU ─────────────────────────────────────────────
    public static boolean TBDR             = false;
    public static boolean BIFROST          = false;
    public static boolean VALHALL          = false;

    // ── Capacidades de shader ────────────────────────────────────────
    public static boolean MEDIUMP_FAST     = false;
    public static boolean FP16_ARITHMETIC  = false;

    // ── Estado ───────────────────────────────────────────────────────
    private static boolean initialised     = false;
    private static boolean usingNative     = false;

    private ShaderCapabilities() {}

    // ════════════════════════════════════════════════════════════════
    // INIT — compatível com o MaliOptMod existente
    // ════════════════════════════════════════════════════════════════

    /**
     * Mantido por retrocompatibilidade.
     * Equivale a init(false) — usa sempre o caminho OpenGL.
     */
    public static void init() {
        init(false);
    }

    /**
     * Inicializa as capacidades escolhendo o melhor caminho disponível.
     *
     * @param nativeAvailable true se libmaliopt.so foi carregada com sucesso.
     *
     * CAMINHO NATIVO (nativeAvailable=true):
     *   Consulta o driver OpenGL ES directamente via JNI.
     *   Bypassa a camada de tradução MobileGlues/ANGLE/GL4ES.
     *   Resultado: extensões reais do Mali-G52, sem filtragem da camada.
     *
     * CAMINHO OPENGL (nativeAvailable=false):
     *   Lê do ExtensionActivator, que por sua vez lê glGetString().
     *   A camada de tradução pode suprimir algumas extensões Mali.
     *   É o comportamento anterior — seguro e sempre funcional.
     */
    public static void init(boolean nativeAvailable) {
        if (initialised) return;

        if (nativeAvailable) {
            loadFromNativePlugin();
        } else {
            loadFromOpenGL();
        }

        // Arquitectura GPU — igual nos dois caminhos
        // (baseada no nome do renderer, não em extensões)
        String model = GPUDetector.getGPUModel();
        if (model == null) model = "";

        TBDR    = model.contains("Mali-G");
        BIFROST = model.matches(".*Mali-G(31|51|52|71|72|76|77).*");
        VALHALL = model.matches(".*Mali-G(57|68|78|310|510|610|710|615|715).*");

        // Mali sempre tem mediump mais rápido (arquitectura SIMD 16-bit)
        MEDIUMP_FAST = TBDR;

        initialised = true;
        logCapabilities();
    }

    // ════════════════════════════════════════════════════════════════
    // CAMINHO NATIVO — via JNI (libmaliopt.so)
    // ════════════════════════════════════════════════════════════════

    /**
     * Preenche os campos via plugin nativo.
     * O plugin consulta glGetString(GL_EXTENSIONS) directamente no driver,
     * sem camada de tradução — extensões Mali reais garantidas.
     */
    private static void loadFromNativePlugin() {
        try {
            // Dispara a detecção no lado nativo (C)
            MaliOptNative.detectExtensions();

            // Lê cada extensão crítica individualmente
            PLS            = MaliOptNative.isPLSSupported();
            FB_FETCH       = MaliOptNative.isFramebufferFetchSupported();
            FB_FETCH_DEPTH = MaliOptNative.isFramebufferFetchDepthSupported();

            ASTC           = MaliOptNative.isASTCLDRSupported();
            ASTC_HDR       = MaliOptNative.isASTCHDRSupported();
            MULTISAMPLED_RT= MaliOptNative.isMultisampledRTSupported();

            FP16_ARITHMETIC = GPUDetector.hasExtension(
                "GL_EXT_shader_explicit_arithmetic_types_float16");

            usingNative = true;
            MaliOptMod.LOGGER.info("[MaliOpt] ShaderCapabilities: fonte = plugin nativo ✅");

        } catch (UnsatisfiedLinkError | Exception e) {
            // Se o JNI falhar por qualquer razão, cai no fallback OpenGL.
            // Nunca crasha o jogo por causa disso.
            MaliOptMod.LOGGER.warn(
                "[MaliOpt] Plugin nativo falhou durante detecção ({}). " +
                "Usando fallback OpenGL.", e.getMessage());
            loadFromOpenGL();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CAMINHO OPENGL — via ExtensionActivator (comportamento anterior)
    // ════════════════════════════════════════════════════════════════

    /**
     * Preenche os campos via ExtensionActivator.
     * Comportamento idêntico ao ShaderCapabilities original.
     * Pode ser filtrado pela camada MobileGlues/GL4ES.
     */
    private static void loadFromOpenGL() {
        PLS            = ExtensionActivator.hasShaderPixelLocalStorage;
        FB_FETCH       = ExtensionActivator.hasFramebufferFetch;
        FB_FETCH_DEPTH = ExtensionActivator.hasFramebufferFetchDepth;

        ASTC           = ExtensionActivator.hasAstcLdr;
        ASTC_HDR       = ExtensionActivator.hasAstcHdr;
        MULTISAMPLED_RT= ExtensionActivator.hasMultisampledRenderToTexture;

        FP16_ARITHMETIC = GPUDetector.hasExtension(
            "GL_EXT_shader_explicit_arithmetic_types_float16");

        usingNative = false;
        MaliOptMod.LOGGER.info("[MaliOpt] ShaderCapabilities: fonte = OpenGL (fallback)");
    }

    // ════════════════════════════════════════════════════════════════
    // API PÚBLICA
    // ════════════════════════════════════════════════════════════════

    /** Pode usar o caminho TBDR ultra-eficiente (PLS + FBFetch) */
    public static boolean canUseTBDRPath() {
        return TBDR && (PLS || FB_FETCH);
    }

    /** Pode usar bloom zero-DRAM */
    public static boolean canUseZeroCopyBloom() {
        return FB_FETCH;
    }

    /** Qualidade recomendada para este hardware */
    public static ShaderQuality recommendedQuality() {
        if (VALHALL) return ShaderQuality.HIGH;
        if (BIFROST) return ShaderQuality.MEDIUM;
        return ShaderQuality.LOW;
    }

    /** Se as capacidades vieram do plugin nativo ou do fallback OpenGL */
    public static boolean isUsingNativePlugin() { return usingNative; }

    public static boolean isInitialised() { return initialised; }

    public enum ShaderQuality { LOW, MEDIUM, HIGH }

    // ════════════════════════════════════════════════════════════════
    // LOG
    // ════════════════════════════════════════════════════════════════

    private static void logCapabilities() {
        MaliOptMod.LOGGER.info("[MaliOpt] ═══ ShaderCapabilities ═══════════════");
        MaliOptMod.LOGGER.info("[MaliOpt]  Fonte:            {}",
            usingNative ? "Plugin Nativo (real)" : "OpenGL fallback");
        MaliOptMod.LOGGER.info("[MaliOpt]  PLS:              {}", PLS);
        MaliOptMod.LOGGER.info("[MaliOpt]  FB_FETCH:         {}", FB_FETCH);
        MaliOptMod.LOGGER.info("[MaliOpt]  FB_FETCH_DEPTH:   {}", FB_FETCH_DEPTH);
        MaliOptMod.LOGGER.info("[MaliOpt]  ASTC:             {}", ASTC);
        MaliOptMod.LOGGER.info("[MaliOpt]  ASTC_HDR:         {}", ASTC_HDR);
        MaliOptMod.LOGGER.info("[MaliOpt]  MULTISAMPLED_RT:  {}", MULTISAMPLED_RT);
        MaliOptMod.LOGGER.info("[MaliOpt]  TBDR:             {}", TBDR);
        MaliOptMod.LOGGER.info("[MaliOpt]  BIFROST:          {}", BIFROST);
        MaliOptMod.LOGGER.info("[MaliOpt]  VALHALL:          {}", VALHALL);
        MaliOptMod.LOGGER.info("[MaliOpt]  MEDIUMP_FAST:     {}", MEDIUMP_FAST);
        MaliOptMod.LOGGER.info("[MaliOpt]  FP16_ARITHMETIC:  {}", FP16_ARITHMETIC);
        MaliOptMod.LOGGER.info("[MaliOpt]  Quality:          {}", recommendedQuality());
        MaliOptMod.LOGGER.info("[MaliOpt] ══════════════════════════════════════");
    }
}
