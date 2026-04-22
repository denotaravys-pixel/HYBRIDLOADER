package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.shader.ShaderExecutionLayer;
import com.maliopt.performance.PerformanceGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.*;

public class FBFetchBloomPass {

    private static int progExtract   = 0;
    private static int progBlur      = 0;
    private static int progComposite = 0;

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

    private static int uExtractScene     = -1;
    private static int uExtractThreshold = -1;
    private static int uBlurTex          = -1;
    private static int uBlurTexelSize    = -1;
    private static int uBlurRadius       = -1;
    private static int uCompScene        = -1;
    private static int uCompGlow         = -1;
    private static int uCompIntensity    = -1;
    private static int uCompSaturation   = -1;

    private static final String VERT =
        "#version 310 es\n" +
        "out vec2 vUv;\n" +
        "void main() {\n" +
        "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n" +
        "    vUv = uv;\n" +
        "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n";

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
        "    float mask = smoothstep(uThreshold, uThreshold + 0.1, lum);\n" +
        "    fragColor = vec4(c * mask, 1.0);\n" +
        "}\n";

    private static final String FRAG_BLUR =
        "#version 310 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D uTex;\n" +
        "uniform vec2 uTexelSize;\n" +
        "uniform float uRadius;\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
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

    private static final String FRAG_COMPOSITE =
        "#version 310 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D uScene;\n" +
        "uniform sampler2D uGlow;\n" +
        "uniform float uIntensity;\n" +
        "uniform float uSaturation;\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "\n" +
        "vec3 aces_tonemap(vec3 x) {\n" +
        "    const float a = 2.51;\n" +
        "    const float b = 0.03;\n" +
        "    const float c = 2.43;\n" +
        "    const float d = 0.59;\n" +
        "    const float e = 0.14;\n" +
        "    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec3 scene = texture(uScene, vUv).rgb;\n" +
        "    vec3 glow  = texture(uGlow,  vUv).rgb;\n" +
        "    highp vec3 hdr = vec3(scene) + vec3(glow) * uIntensity;\n" +
        "    vec3 color = aces_tonemap(hdr);\n" +
        "    float gray = dot(color, vec3(0.299, 0.587, 0.114));\n" +
        "    color = mix(vec3(gray), color, uSaturation);\n" +
        "    color = clamp(color, 0.0, 1.0);\n" +
        "    fragColor = vec4(color, 1.0);\n" +
        "}\n";

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
            MaliOptMod.LOGGER.info("[MaliOpt] ✅ FBFetchBloomPass v3.0 iniciado");

        } catch (Exception e) {
            MaliOptMod.LOGGER.error("[MaliOpt] FBFetchBloomPass.init() excepção: {}", e.getMessage());
            cleanup();
        }
    }

    public static void render(MinecraftClient mc) {
        if (!ready || mc.world == null) return;
        if (!PerformanceGuard.bloomEnabled()) return;

        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth;
        int h = fb.textureHeight;

        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) rebuildFBOs(w, h);
        if (brightFbo == 0 || blurFbo == 0 || sceneCopyFbo == 0) return;

        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        phaseExtract(fb, w, h);
        phaseBlur(w, h);
        phaseComposite(fb, w, h);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
    }

    private static void phaseExtract(Framebuffer fb, int w, int h) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneCopyFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

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

    private static void phaseComposite(Framebuffer fb, int w, int h) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(progComposite);
        GL20.glUniform1i(uCompScene, 0);
        GL20.glUniform1i(uCompGlow, 1);
        GL20.glUniform1f(uCompIntensity, PerformanceGuard.bloomIntensity());
        GL20.glUniform1f(uCompSaturation, 1.2f);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneCopyTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurTex);
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static void rebuildFBOs(int w, int h) {
        deleteFBOs();
        brightTex    = makeTex(w, h, true);
        brightFbo    = makeFbo(brightTex);
        blurTex      = makeTex(w, h, true);
        blurFbo      = makeFbo(blurTex);
        sceneCopyTex = makeTex(w, h, false);
        sceneCopyFbo = makeFbo(sceneCopyTex);
        if (brightFbo == 0 || blurFbo == 0 || sceneCopyFbo == 0) {
            MaliOptMod.LOGGER.error("[MaliOpt] BloomPass: FBO setup falhou");
            deleteFBOs();
        } else {
            lastW = w;
            lastH = h;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static int makeTex(int w, int h, boolean linear) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
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
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
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
        lastW = 0; lastH = 0;
    }

    private static void cacheUniforms() {
        GL20.glUseProgram(progExtract);
        uExtractScene     = GL20.glGetUniformLocation(progExtract, "uScene");
        uExtractThreshold = GL20.glGetUniformLocation(progExtract, "uThreshold");

        GL20.glUseProgram(progBlur);
        uBlurTex       = GL20.glGetUniformLocation(progBlur, "uTex");
        uBlurTexelSize = GL20.glGetUniformLocation(progBlur, "uTexelSize");
        uBlurRadius    = GL20.glGetUniformLocation(progBlur, "uRadius");

        GL20.glUseProgram(progComposite);
        uCompScene      = GL20.glGetUniformLocation(progComposite, "uScene");
        uCompGlow       = GL20.glGetUniformLocation(progComposite, "uGlow");
        uCompIntensity  = GL20.glGetUniformLocation(progComposite, "uIntensity");
        uCompSaturation = GL20.glGetUniformLocation(progComposite, "uSaturation");

        GL20.glUseProgram(0);
    }

    private static int buildProgram(String vert, String frag, String name) {
        int v = ShaderExecutionLayer.compile(GL20.GL_VERTEX_SHADER, vert, name + "_vert");
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
            MaliOptMod.LOGGER.error("[MaliOpt] {} link falhou: {}", name, GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    public static void cleanup() {
        if (progExtract   != 0) { GL20.glDeleteProgram(progExtract);   progExtract   = 0; }
        if (progBlur      != 0) { GL20.glDeleteProgram(progBlur);      progBlur      = 0; }
        if (progComposite != 0) { GL20.glDeleteProgram(progComposite); progComposite = 0; }
        if (quadVao       != 0) { GL30.glDeleteVertexArrays(quadVao);  quadVao       = 0; }
        deleteFBOs();
        ready = false;
    }

    public static boolean isReady() { return ready; }
            }
