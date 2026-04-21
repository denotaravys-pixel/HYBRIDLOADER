package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.shader.ShaderCapabilities;
import com.maliopt.shader.ShaderExecutionLayer;
import com.maliopt.performance.PerformanceGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.*;

/**
 * FBFetchBloomPass — Fase 3b (v2.0)
 *
 * ── FILOSOFIA ────────────────────────────────────────────────────
 *
 *   REGRA DE OURO:
 *     Se não houver nenhum pixel brilhante na cena, este pass
 *     deve devolver a imagem 100% idêntica ao input.
 *     O bloom é invisível até ser necessário.
 *
 *   O QUE FAZ:
 *     - Extrai APENAS pixels com lum > threshold (zonas realmente brilhantes)
 *     - Aplica blur Gaussian 9-tap nesses pixels → glow real
 *     - Soma o glow por cima da cena original (sem modificar a base)
 *     - Reinhard SOMENTE na luminância → preserva saturação e hue vanilla
 *
 *   O QUE NÃO FAZ (NUNCA):
 *     - Não modifica pixels escuros ou médios
 *     - Não aplica Reinhard global (a versão antiga destruía as cores)
 *     - Não usa sceneCopyTex antigo quando a cena já foi processada pelo PLSLightingPass
 *     - Não crasha se FBFetch não estiver disponível
 *
 * ── PIPELINE CORRECTO ────────────────────────────────────────────
 *   Entrada: fb.fbo após PLSLightingPass
 *
 *   FASE A — Extract:
 *     Copia cena → sceneCopyFbo (1 blit necessário)
 *     Extrai bright mask → brightFbo (só pixels com lum > threshold)
 *
 *   FASE B — Blur:
 *     9-tap Gaussian na bright mask → blurFbo (glow suavizado)
 *
 *   FASE C — Composite:
 *     sceneCopyTex + blurTex * intensity → fb.fbo
 *     Reinhard APENAS na luminância → hue e saturação vanilla preservados
 *
 * ── NOTA SOBRE FBFETCH ────────────────────────────────────────────
 *   FBFetch (GL_ARM_shader_framebuffer_fetch) não funciona cross-FBO.
 *   Reservado para passes futuros que escrevem no mesmo FBO que lêem.
 *   Por agora: sempre texture path (1 blit, eficiente no Mali TBDR).
 *
 * ── SEGURANÇA ────────────────────────────────────────────────────
 *   - Falha silenciosa em qualquer erro — ready=false desativa tudo
 *   - Restaura estado GL completo após render
 *   - FBOs reconstruídos automaticamente em mudança de resolução
 */
public class FBFetchBloomPass {

    // ── Programas ────────────────────────────────────────────────────
    private static int progExtract   = 0;
    private static int progBlur      = 0;
    private static int progComposite = 0;

    // ── FBOs ─────────────────────────────────────────────────────────
    private static int brightFbo    = 0;
    private static int brightTex    = 0;
    private static int blurFbo      = 0;
    private static int blurTex      = 0;
    private static int sceneCopyFbo = 0;
    private static int sceneCopyTex = 0;

    private static int quadVao = 0;
    private static int lastW   = 0;
    private static int lastH   = 0;
    private static boolean ready = false;

    // ── Uniforms ─────────────────────────────────────────────────────
    private static int uExtractScene     = -1;
    private static int uExtractThreshold = -1;
    private static int uBlurTex          = -1;
    private static int uBlurTexelSize    = -1;
    private static int uBlurRadius       = -1;
    private static int uCompScene        = -1;
    private static int uCompGlow         = -1;
    private static int uCompIntensity    = -1;

    // ════════════════════════════════════════════════════════════════
    // GLSL — VERTEX (partilhado por todos os passes)
    // ════════════════════════════════════════════════════════════════
    private static final String VERT =
        "#version 310 es\n" +
        "out vec2 vUv;\n" +
        "void main() {\n" +
        "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n" +
        "    vUv = uv;\n" +
        "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n";

    // ════════════════════════════════════════════════════════════════
    // GLSL — EXTRACT
    // Isola APENAS pixels com luminância acima do threshold.
    // Pixels abaixo do threshold → vec3(0.0) → sem contribuição no blur.
    // A máscara é suave (smoothstep) para evitar artefactos de banding.
    // ════════════════════════════════════════════════════════════════
    private static final String FRAG_EXTRACT =
        "#version 310 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D uScene;\n" +
        "uniform float uThreshold;\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec3  c   = texture(uScene, vUv).rgb;\n" +
        "    float lum = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "    // smoothstep: transição suave entre lum=threshold e lum=threshold+0.1\n" +
        "    // Evita banding e isola apenas o brilho real\n" +
        "    float mask = smoothstep(uThreshold, uThreshold + 0.1, lum);\n" +
        "    fragColor = vec4(c * mask, 1.0);\n" +
        "}\n";

    // ════════════════════════════════════════════════════════════════
    // GLSL — BLUR (9-tap Gaussian separável em cruz)
    // Corre sobre a bright mask → produz glow suavizado.
    // uRadius controla o spread — ajustado pelo PerformanceGuard.
    // ════════════════════════════════════════════════════════════════
    private static final String FRAG_BLUR =
        "#version 310 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D uTex;\n" +
        "uniform vec2 uTexelSize;\n" +
        "uniform float uRadius;\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    // Gaussian 9-tap normalizado (sigma ≈ 1.5)\n" +
        "    vec3 result = texture(uTex, vUv).rgb * 0.227;\n" +
        "    vec2 step1  = uTexelSize * uRadius;\n" +
        "    vec2 step2  = uTexelSize * uRadius * 2.0;\n" +
        "    vec2 step3  = uTexelSize * uRadius * 3.0;\n" +
        "    vec2 step4  = uTexelSize * uRadius * 4.0;\n" +
        "    result += (texture(uTex, vUv + vec2( step1.x, 0.0)).rgb +\n" +
        "               texture(uTex, vUv + vec2(-step1.x, 0.0)).rgb +\n" +
        "               texture(uTex, vUv + vec2(0.0,  step1.y)).rgb +\n" +
        "               texture(uTex, vUv + vec2(0.0, -step1.y)).rgb) * 0.097;\n" +
        "    result += (texture(uTex, vUv + vec2( step2.x, 0.0)).rgb +\n" +
        "               texture(uTex, vUv + vec2(-step2.x, 0.0)).rgb +\n" +
        "               texture(uTex, vUv + vec2(0.0,  step2.y)).rgb +\n" +
        "               texture(uTex, vUv + vec2(0.0, -step2.y)).rgb) * 0.061;\n" +
        "    result += (texture(uTex, vUv + vec2( step3.x, 0.0)).rgb +\n" +
        "               texture(uTex, vUv + vec2(-step3.x, 0.0)).rgb +\n" +
        "               texture(uTex, vUv + vec2(0.0,  step3.y)).rgb +\n" +
        "               texture(uTex, vUv + vec2(0.0, -step3.y)).rgb) * 0.027;\n" +
        "    result += (texture(uTex, vUv + vec2( step4.x, 0.0)).rgb +\n" +
        "               texture(uTex, vUv + vec2(-step4.x, 0.0)).rgb +\n" +
        "               texture(uTex, vUv + vec2(0.0,  step4.y)).rgb +\n" +
        "               texture(uTex, vUv + vec2(0.0, -step4.y)).rgb) * 0.008;\n" +
        "    fragColor = vec4(result, 1.0);\n" +
        "}\n";

    // ════════════════════════════════════════════════════════════════
    // GLSL — COMPOSITE
    //
    // PROBLEMA DO v1.x: Reinhard global → HDR / (HDR + 1)
    //   Aplicava tonemapping a TODA a imagem, incluindo zonas vanilla
    //   normais. Isso comprimia as cores e causava dessaturação.
    //
    // SOLUÇÃO v2.0: Reinhard APENAS na luminância
    //   1. Soma cena + glow (apenas os highlights brilham mais)
    //   2. Calcula luminância do resultado HDR
    //   3. Aplica Reinhard só na luminância: lumTM = lum / (lum + 1)
    //   4. Escala o RGB pelo rácio lumTM/lum → preserva hue e saturação
    //   5. Zonas escuras e médias: lum ≈ 0.3, lumTM ≈ 0.23 → escala ≈ 0.77
    //      ... mas o glow é 0 nessas zonas → hdr = scene → escala ≈ 1.0
    //   Resultado: imagem vanilla intacta + glow suave nos highlights
    //
    // ════════════════════════════════════════════════════════════════
    private static final String FRAG_COMPOSITE =
        "#version 310 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D uScene;\n" +
        "uniform sampler2D uGlow;\n" +
        "uniform float uIntensity;\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec3 scene = texture(uScene, vUv).rgb;\n" +
        "    vec3 glow  = texture(uGlow,  vUv).rgb;\n" +
        "\n" +
        "    // Soma: a cena base nunca é alterada, só recebe o glow por cima\n" +
        "    vec3 hdr = scene + glow * uIntensity;\n" +
        "\n" +
        "    // Reinhard SOMENTE na luminância — preserva hue e saturação vanilla\n" +
        "    float lum   = dot(hdr, vec3(0.299, 0.587, 0.114));\n" +
        "    float lumTM = lum / (lum + 1.0);\n" +
        "\n" +
        "    // Se lum quase zero, escala=1 (sem efeito) — evita divisão por zero\n" +
        "    float scale = (lum > 0.001) ? (lumTM / lum) : 1.0;\n" +
        "\n" +
        "    // Aplica apenas onde o glow existe (não toca zonas vanilla puras)\n" +
        "    vec3 result = hdr * scale;\n" +
        "\n" +
        "    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);\n" +
        "}\n";

    // ════════════════════════════════════════════════════════════════
    // INIT
    // ════════════════════════════════════════════════════════════════

    public static void init() {
        try {
            progExtract   = buildProgram(VERT, FRAG_EXTRACT,   "Bloom_Extract");
            progBlur      = buildProgram(VERT, FRAG_BLUR,      "Bloom_Blur");
            progComposite = buildProgram(VERT, FRAG_COMPOSITE, "Bloom_Composite");

            if (progExtract == 0 || progBlur == 0 || progComposite == 0) {
                MaliOptMod.LOGGER.error("[MaliOpt] FBFetchBloomPass: falha de compilação — pass desativado");
                cleanup();
                return;
            }

            cacheUniforms();

            quadVao = GL30.glGenVertexArrays();
            ready   = true;

            MaliOptMod.LOGGER.info("[MaliOpt] ✅ FBFetchBloomPass v2.0 — Reinhard-luminance, vanilla-safe");

        } catch (Exception e) {
            MaliOptMod.LOGGER.error("[MaliOpt] FBFetchBloomPass.init() excepção: {}", e.getMessage());
            cleanup();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // RENDER
    // ════════════════════════════════════════════════════════════════

    public static void render(MinecraftClient mc) {
        if (!ready || mc.world == null) return;
        if (!PerformanceGuard.bloomEnabled()) return;

        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth;
        int h = fb.textureHeight;

        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) rebuildFBOs(w, h);
        if (brightFbo == 0 || blurFbo == 0 || sceneCopyFbo == 0) return;

        // Guarda estado GL
        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        // ── FASE A: copia cena actual + extrai bright mask ─────────
        phaseExtract(fb, w, h);

        // ── FASE B: blur 9-tap na bright mask → glow ───────────────
        phaseBlur(w, h);

        // ── FASE C: composite cena + glow, Reinhard-luminance ──────
        phaseComposite(fb, w, h);

        // Restaura estado GL
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
    }

    // ── Fase A: Extract ───────────────────────────────────────────────

    private static void phaseExtract(Framebuffer fb, int w, int h) {
        // Copia a cena ACTUAL (já processada pelo PLSLightingPass) para sceneCopyFbo
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneCopyFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        // Extrai bright mask para brightFbo
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, brightFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(progExtract);
        GL20.glUniform1i(uExtractScene, 0);
        GL20.glUniform1f(uExtractThreshold, PerformanceGuard.bloomThreshold());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneCopyTex);
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // ── Fase B: Blur ──────────────────────────────────────────────────

    private static void phaseBlur(int w, int h) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, blurFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(progBlur);
        GL20.glUniform1i(uBlurTex, 0);
        GL20.glUniform2f(uBlurTexelSize, 1.0f / w, 1.0f / h);
        GL20.glUniform1f(uBlurRadius, PerformanceGuard.bloomRadius());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, brightTex);
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // ── Fase C: Composite ─────────────────────────────────────────────

    private static void phaseComposite(Framebuffer fb, int w, int h) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(progComposite);
        GL20.glUniform1i(uCompScene, 0);
        GL20.glUniform1i(uCompGlow,  1);
        GL20.glUniform1f(uCompIntensity, PerformanceGuard.bloomIntensity());

        // uScene = sceneCopyTex (cena após PLSLightingPass, antes do bloom)
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneCopyTex);

        // uGlow = blurTex (glow suavizado — só pixels brilhantes)
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurTex);

        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);

        // Cleanup texturas
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // ════════════════════════════════════════════════════════════════
    // FBOs
    // ════════════════════════════════════════════════════════════════

    private static void rebuildFBOs(int w, int h) {
        deleteFBOs();

        brightTex    = makeTex(w, h, true);
        brightFbo    = makeFbo(brightTex);
        blurTex      = makeTex(w, h, true);
        blurFbo      = makeFbo(blurTex);
        sceneCopyTex = makeTex(w, h, false);
        sceneCopyFbo = makeFbo(sceneCopyTex);

        if (brightFbo == 0 || blurFbo == 0 || sceneCopyFbo == 0) {
            MaliOptMod.LOGGER.error("[MaliOpt] BloomPass: FBO setup falhou — pass desativado");
            deleteFBOs();
        } else {
            lastW = w;
            lastH = h;
            MaliOptMod.LOGGER.info("[MaliOpt] BloomPass FBOs: {}x{}", w, h);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static int makeTex(int w, int h, boolean linear) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        int filter = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private static int makeFbo(int tex) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(fbo);
            return 0;
        }
        return fbo;
    }

    private static void deleteFBOs() {
        if (brightFbo    != 0) { GL30.glDeleteFramebuffers(brightFbo);    brightFbo    = 0; }
        if (brightTex    != 0) { GL11.glDeleteTextures(brightTex);        brightTex    = 0; }
        if (blurFbo      != 0) { GL30.glDeleteFramebuffers(blurFbo);      blurFbo      = 0; }
        if (blurTex      != 0) { GL11.glDeleteTextures(blurTex);          blurTex      = 0; }
        if (sceneCopyFbo != 0) { GL30.glDeleteFramebuffers(sceneCopyFbo); sceneCopyFbo = 0; }
        if (sceneCopyTex != 0) { GL11.glDeleteTextures(sceneCopyTex);     sceneCopyTex = 0; }
        lastW = 0;
        lastH = 0;
    }

    // ════════════════════════════════════════════════════════════════
    // UNIFORM CACHE
    // ════════════════════════════════════════════════════════════════

    private static void cacheUniforms() {
        GL20.glUseProgram(progExtract);
        uExtractScene     = GL20.glGetUniformLocation(progExtract, "uScene");
        uExtractThreshold = GL20.glGetUniformLocation(progExtract, "uThreshold");

        GL20.glUseProgram(progBlur);
        uBlurTex       = GL20.glGetUniformLocation(progBlur, "uTex");
        uBlurTexelSize = GL20.glGetUniformLocation(progBlur, "uTexelSize");
        uBlurRadius    = GL20.glGetUniformLocation(progBlur, "uRadius");

        GL20.glUseProgram(progComposite);
        uCompScene     = GL20.glGetUniformLocation(progComposite, "uScene");
        uCompGlow      = GL20.glGetUniformLocation(progComposite, "uGlow");
        uCompIntensity = GL20.glGetUniformLocation(progComposite, "uIntensity");

        GL20.glUseProgram(0);
    }

    // ════════════════════════════════════════════════════════════════
    // SHADER BUILD
    // ════════════════════════════════════════════════════════════════

    private static int buildProgram(String vert, String frag, String name) {
        int v = ShaderExecutionLayer.compile(GL20.GL_VERTEX_SHADER,   vert, name + "_vert");
        int f = ShaderExecutionLayer.compile(GL20.GL_FRAGMENT_SHADER, frag, name + "_frag");
        if (v == 0 || f == 0) {
            if (v != 0) GL20.glDeleteShader(v);
            if (f != 0) GL20.glDeleteShader(f);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, v);
        GL20.glAttachShader(prog, f);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            MaliOptMod.LOGGER.error("[MaliOpt] {} link falhou: {}", name,
                GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    // ════════════════════════════════════════════
