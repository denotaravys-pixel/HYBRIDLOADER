package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.config.MaliOptVisualConfig;
import com.maliopt.shader.ShaderExecutionLayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.*;

/**
 * SSRPass — Screen-Space Reflections para Mali-G52.
 *
 * Ray-march em screen space a partir de cada fragment.
 * Quando ssrWaterOnly=true, limita o passe a pixels com
 * flag de água (via alpha channel codificado pelo mixin).
 *
 * Custo: O(ssrMaxSteps) por fragment visível.
 * Recomendado: maxSteps=16, ssrWaterOnly=true para Mali-G52.
 */
public final class SSRPass {

    private static int program  = 0;
    private static int quadVao  = 0;
    private static int ssrFbo   = 0;
    private static int ssrTex   = 0;
    private static int lastW    = 0;
    private static int lastH    = 0;
    private static boolean ready = false;

    private static int uScene       = -1;
    private static int uMaxSteps    = -1;
    private static int uStepSize    = -1;
    private static int uWaterOnly   = -1;

    private static final String VERT =
        "#version 310 es\n" +
        "out vec2 vUv;\n" +
        "void main() {\n" +
        "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n" +
        "    vUv = uv;\n" +
        "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n";

    private static final String FRAG =
        "#version 310 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D uScene;\n" +
        "uniform int   uMaxSteps;\n" +
        "uniform float uStepSize;\n" +
        "uniform int   uWaterOnly;\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "\n" +
        "void main() {\n" +
        "    vec4 sceneColor = texture(uScene, vUv);\n" +
        "\n" +
        "    // Quando waterOnly, usa alpha<0.99 como flag de água\n" +
        "    if (uWaterOnly == 1 && sceneColor.a > 0.99) {\n" +
        "        fragColor = sceneColor;\n" +
        "        return;\n" +
        "    }\n" +
        "\n" +
        "    // Direção de reflexo simplificada (screen-space vertical flip)\n" +
        "    vec2 reflectDir = vec2(0.0, -1.0) * uStepSize * 0.01;\n" +
        "    vec2 sampleUv   = vUv;\n" +
        "    vec3 reflectColor = vec3(0.0);\n" +
        "    float found = 0.0;\n" +
        "\n" +
        "    for (int i = 0; i < uMaxSteps; i++) {\n" +
        "        sampleUv += reflectDir;\n" +
        "        if (sampleUv.x < 0.0 || sampleUv.x > 1.0 ||\n" +
        "            sampleUv.y < 0.0 || sampleUv.y > 1.0) break;\n" +
        "        vec3 s = texture(uScene, sampleUv).rgb;\n" +
        "        float lum = dot(s, vec3(0.299, 0.587, 0.114));\n" +
        "        if (lum > 0.3) {\n" +
        "            reflectColor = s;\n" +
        "            found = 1.0;\n" +
        "            break;\n" +
        "        }\n" +
        "    }\n" +
        "\n" +
        "    // Blend suave: 15% de reflexo\n" +
        "    vec3 finalColor = mix(sceneColor.rgb,\n" +
        "                         mix(sceneColor.rgb, reflectColor, 0.15),\n" +
        "                         found);\n" +
        "    fragColor = vec4(finalColor, sceneColor.a);\n" +
        "}\n";

    public static void init() {
        try {
            program = buildProgram(VERT, FRAG, "SSRPass");
            if (program == 0) {
                MaliOptMod.LOGGER.error("[MaliOpt] SSRPass: compilação falhou — desativado");
                return;
            }
            cacheUniforms();
            quadVao = GL30.glGenVertexArrays();
            ready   = true;
            MaliOptMod.LOGGER.info("[MaliOpt] ✅ SSRPass iniciado");
        } catch (Exception e) {
            MaliOptMod.LOGGER.error("[MaliOpt] SSRPass.init() excepção: {}", e.getMessage());
        }
    }

    public static void render(MinecraftClient mc) {
        if (!ready || mc.world == null) return;

        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
        if (!cfg.ssrEnabled) return;

        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth;
        int h = fb.textureHeight;
        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) rebuildFbo(w, h);
        if (ssrFbo == 0) return;

        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        // Render SSR para buffer intermédio
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, ssrFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);

        GL20.glUniform1i(uScene,     0);
        GL20.glUniform1i(uMaxSteps,  cfg.ssrMaxSteps);
        GL20.glUniform1f(uStepSize,  cfg.ssrStepSize);
        GL20.glUniform1i(uWaterOnly, cfg.ssrWaterOnly ? 1 : 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.getColorAttachment());

        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // Blit resultado → framebuffer do Minecraft
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, ssrFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.fbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        // TBDR: invalidar buffer intermédio — não precisa voltar à DRAM
        int[] att = { GL30.GL_COLOR_ATTACHMENT0 };
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, ssrFbo);
        GL30.glInvalidateFramebuffer(GL30.GL_FRAMEBUFFER, att);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
    }

    private static void rebuildFbo(int w, int h) {
        if (ssrFbo != 0) { GL30.glDeleteFramebuffers(ssrFbo); ssrFbo = 0; }
        if (ssrTex != 0) { GL11.glDeleteTextures(ssrTex);     ssrTex = 0; }

        ssrTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, ssrTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        ssrFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, ssrFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, ssrTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MaliOptMod.LOGGER.error("[MaliOpt] SSRPass FBO incompleto — desativado");
            GL30.glDeleteFramebuffers(ssrFbo);
            GL11.glDeleteTextures(ssrTex);
            ssrFbo = 0; ssrTex = 0;
            return;
        }
        lastW = w; lastH = h;
    }

    public static void cleanup() {
        if (program != 0) { GL20.glDeleteProgram(program);       program = 0; }
        if (quadVao != 0) { GL30.glDeleteVertexArrays(quadVao);  quadVao = 0; }
        if (ssrFbo  != 0) { GL30.glDeleteFramebuffers(ssrFbo);   ssrFbo  = 0; }
        if (ssrTex  != 0) { GL11.glDeleteTextures(ssrTex);       ssrTex  = 0; }
        ready = false;
    }

    public static boolean isReady() { return ready; }

    private static void cacheUniforms() {
        GL20.glUseProgram(program);
        uScene     = GL20.glGetUniformLocation(program, "uScene");
        uMaxSteps  = GL20.glGetUniformLocation(program, "uMaxSteps");
        uStepSize  = GL20.glGetUniformLocation(program, "uStepSize");
        uWaterOnly = GL20.glGetUniformLocation(program, "uWaterOnly");
        GL20.glUseProgram(0);
    }

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
            MaliOptMod.LOGGER.error("[MaliOpt] {} link falhou: {}", name, GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }
        }
