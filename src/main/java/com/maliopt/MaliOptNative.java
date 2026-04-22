package com.maliopt;

/**
 * MaliOptNative — Ponte JNI para o plugin nativo libmaliopt.so
 *
 * Todos os métodos aqui declarados como 'native' têm implementação
 * em C dentro do plugin APK (libmaliopt.so).
 *
 * Convenção de nomes JNI (automática):
 *   Java_com_maliopt_MaliOptNative_detectExtensions → detectExtensions()
 *
 * NÃO chamar nenhum método daqui sem antes verificar
 * MaliOptMod.isNativePluginLoaded() == true.
 * Uma chamada a método nativo sem a biblioteca carregada
 * lança UnsatisfiedLinkError e crasha o jogo.
 */
public final class MaliOptNative {

    // A biblioteca é carregada por MaliOptMod.loadNativePlugin().
    // Este bloco static fica vazio intencionalmente —
    // um segundo System.loadLibrary na mesma JVM é no-op se já carregada,
    // mas lança UnsatisfiedLinkError se o .so não existir.
    // Deixamos o controlo total para MaliOptMod.
    static {}

    private MaliOptNative() {}

    // ── Ciclo de vida ──────────────────────────────────────────────────
    /** Executa glGetString(GL_EXTENSIONS) e eglQueryString no driver real.
     *  Deve ser chamado uma vez, após EGL context estar activo. */
    public static native void detectExtensions();

    /** Relatório completo em formato "KEY=VALUE\n" parseável. */
    public static native String getExtensionReport();

    // ── Extensões de framebuffer ───────────────────────────────────────
    public static native boolean isFramebufferFetchSupported();
    public static native boolean isFramebufferFetchDepthSupported();
    public static native boolean isPLSSupported();

    // ── Texturas e render targets ──────────────────────────────────────
    public static native boolean isASTCLDRSupported();
    public static native boolean isASTCHDRSupported();
    public static native boolean isMultisampledRTSupported();
    public static native boolean isTextureStorageSupported();
    public static native boolean isAnisotropicFilteringSupported();

    // ── Shaders ────────────────────────────────────────────────────────
    public static native boolean isProgramBinarySupported();
    public static native boolean isMaliBinarySupported();
    public static native boolean isTimerQuerySupported();

    // ── Limites de hardware ────────────────────────────────────────────
    public static native int getMaxTextureSize();
    public static native int getMaxSamples();
    public static native int getMaxAnisotropy();
    public static native int getMaxVertexAttribs();

    // ── Identificação da GPU ───────────────────────────────────────────
    public static native String getGPURenderer();
    public static native String getGPUVendor();
    public static native String getGLESVersion();
    public static native String getActiveRenderContext();

    // ── Debug ──────────────────────────────────────────────────────────
    public static native String getRawGLExtensions();
    public static native String getRawEGLExtensions();
}
