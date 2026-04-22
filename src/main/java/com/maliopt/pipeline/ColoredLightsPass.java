package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.config.MaliOptVisualConfig;
import com.maliopt.shader.ShaderExecutionLayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import org.lwjgl.opengl.*;

/**
 * ColoredLightsPass — iluminação colorida dinâmica para Mali-G52.
 *
 * Recolhe até maxDynamicLights fontes de luz à volta do jogador,
 * determina a sua cor e aplica um blend aditivo no screen space.
 *
 * Filosofia:
 *   - Tochas        → laranja (1.0, 0.55, 0.1)
 *   - Soul Fire     → ciano   (0.2, 0.8, 1.0)
 *   - Glowstone     → amarelo (1.0, 0.9, 0.5)
 *   - Sculk Sensor  → violeta (0.5, 0.1, 1.0)
 *   - Outros        → branco  (1.0, 1.0, 0.9)
 *
 * TBDR-safe: single fullscreen pass, zero geometry extra.
 */
public final class ColoredLightsPass {

    private static final int MAX_LIGHTS_HARD_CAP = 8;

    private static int program  = 0;
    private static int quadVao  = 0;
    private static int outFbo   = 0;
    private static int outTex   = 0;
    private static int lastW    = 0;
    private static int lastH    = 0;
    private static boolean ready = false;

    // Uniforms
    private static int uScene      = -1;
    private static int uLightCount = -1;
    // Arrays de uniforms para N luzes
    private static int[] uLightPos   = new int[MAX_LIGHTS_HARD_CAP];
    private static int[] uLightColor = new int[MAX_LIGHTS_HARD_CAP];
    private static int[] uLightRadius= new int[MAX_LIGHTS_HARD_CAP];

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
        "uniform int   uLightCount;\n" +
        "uniform vec2  uLightPos[8];\n" +     // posição normalizada em screen space
        "uniform vec3  uLightColor[8];\n" +
        "uniform float uLightRadius[8];\n" +  // raio em UV (0.0-1.0)
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "\n" +
        "void main() {\n" +
        "    vec3 scene = texture(uScene, vUv).rgb;\n" +
        "    vec3 lightAccum = vec3(0.0);\n" +
        "\n" +
        "    for (int i = 0; i < uLightCount; i++) {\n" +
        "        float dist = distance(vUv, uLightPos[i]);\n" +
        "        float att  = 1.0 - smoothstep(0.0, uLightRadius[i], dist);\n" +
        "        lightAccum += uLightColor[i] * att * 0.12;\n" + // 12% de aditivo
        "    }\n" +
        "\n" +
        "    vec3 finalColor = clamp(scene + lightAccum * scene, 0.0, 1.0);\n" +
        "    fragColor = vec4(finalColor, 1.0);\n" +
        "}\n";

    public static void init() {
        try {
            program = buildProgram(VERT, FRAG, "ColoredLights");
            if (program == 0) {
                MaliOptMod.LOGGER.error("[MaliOpt] ColoredLightsPass: compilação falhou");
                return;
            }
            cacheUniforms();
            quadVao = GL30.glGenVertexArrays();
            ready   = true;
            MaliOptMod.LOGGER.info("[MaliOpt] ✅ ColoredLightsPass iniciado");
        } catch (Exception e) {
            MaliOptMod.LOGGER.error("[MaliOpt] ColoredLightsPass.init() excepção: {}", e.getMessage());
        }
    }

    public static void render(MinecraftClient mc) {
        if (!ready || mc.world == null || mc.player == null) return;

        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
        if (!cfg.coloredLightsEnabled) return;

        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth;
        int h = fb.textureHeight;
        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) rebuildFbo(w, h);
        if (outFbo == 0) return;

        // Recolhe fontes de luz próximas
        LightSource[] lights = gatherLights(mc, cfg.maxDynamicLights);
        if (lights.length == 0) return;

        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);

        GL20.glUniform1i(uScene, 0);
        GL20.glUniform1i(uLightCount, lights.length);

        for (int i = 0; i < lights.length; i++) {
            GL20.glUniform2f(uLightPos[i],    lights[i].uvX, lights[i].uvY);
            GL20.glUniform3f(uLightColor[i],  lights[i].r,   lights[i].g,   lights[i].b);
            GL20.glUniform1f(uLightRadius[i], lights[i].radius);
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.getColorAttachment());

        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.fbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        int[] att = { GL30.GL_COLOR_ATTACHMENT0 };
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outFbo);
        GL30.glInvalidateFramebuffer(GL30.GL_FRAMEBUFFER, att);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
    }

    // ── Recolha de luzes ──────────────────────────────────────────────

    private static LightSource[] gatherLights(MinecraftClient mc, int maxLights) {
        ClientWorld world  = mc.world;
        int cap = Math.min(maxLights, MAX_LIGHTS_HARD_CAP);
        LightSource[] result = new LightSource[cap];
        int found = 0;

        BlockPos playerPos = mc.player.getBlockPos();
        int range = 12; // blocos de raio

        for (int dx = -range; dx <= range && found < cap; dx++) {
            for (int dz = -range; dz <= range && found < cap; dz++) {
                for (int dy = -4; dy <= 4 && found < cap; dy++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    int blockLight = world.getLightLevel(LightType.BLOCK, pos);
                    if (blockLight < 10) continue;

                    String blockId = world.getBlockState(pos)
                        .getBlock().toString().toLowerCase();

                    float[] color = colorFromBlock(blockId);

                    // Posição em screen space (approximação simples)
                    float uvX = 0.5f + (dx / (float)(range * 2));
                    float uvY = 0.5f - (dy / (float)(range * 2));
                    float radius = 0.08f + (blockLight / 15.0f) * 0.06f;

                    result[found++] = new LightSource(uvX, uvY, color[0], color[1], color[2], radius);
                }
            }
        }

        // Retorna apenas o subarray preenchido
        LightSource[] trimmed = new LightSource[found];
        System.arraycopy(result, 0, trimmed, 0, found);
        return trimmed;
    }

    private static float[] colorFromBlock(String blockId) {
        if (blockId.contains("torch") || blockId.contains("campfire"))
            return new float[]{ 1.0f, 0.55f, 0.10f };
        if (blockId.contains("soul"))
            return new float[]{ 0.20f, 0.80f, 1.00f };
        if (blockId.contains("glowstone") || blockId.contains("shroomlight"))
            return new float[]{ 1.00f, 0.90f, 0.50f };
        if (blockId.contains("sculk") || blockId.contains("amethyst"))
            return new float[]{ 0.50f, 0.10f, 1.00f };
        if (blockId.contains("lava") || blockId.contains("magma"))
            return new float[]{ 1.00f, 0.30f, 0.05f };
        if (blockId.contains("beacon"))
            return new float[]{ 0.40f, 1.00f, 0.60f };
        return new float[]{ 1.00f, 1.00f, 0.90f }; // branco quente
    }

    // ── FBO ──────────────────────────────────────────────────────────

    private static void rebuildFbo(int w, int h) {
        if (outFbo != 0) { GL30.glDeleteFramebuffers(outFbo); outFbo = 0; }
        if (outTex != 0) { GL11.glDeleteTextures(outTex);     outTex = 0; }

        outTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, outTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        outFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MaliOptMod.LOGGER.error("[MaliOpt] ColoredLightsPass FBO incompleto");
            GL30.glDeleteFramebuffers(outFbo);
            GL11.glDeleteTextures(outTex);
            outFbo = 0; outTex = 0;
            return;
        }
        lastW = w; lastH = h;
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    public static void cleanup() {
        if (program != 0) { GL20.glDeleteProgram(program);      program = 0; }
        if (quadVao != 0) { GL30.glDeleteVertexArrays(quadVao); quadVao = 0; }
        if (outFbo  != 0) { GL30.glDeleteFramebuffers(outFbo);  outFbo  = 0; }
        if (outTex  != 0) { GL11.glDeleteTextures(outTex);      outTex  = 0; }
        ready = false;
    }

    public static boolean isReady() { return ready; }

    // ── Helpers ──────────────────────────────────────────────────────

    private static void cacheUniforms() {
        GL20.glUseProgram(program);
        uScene      = GL20.glGetUniformLocation(program, "uScene");
        uLightCount = GL20.glGetUniformLocation(program, "uLightCount");
        for (int i = 0; i < MAX_LIGHTS_HARD_CAP; i++) {
            uLightPos[i]    = GL20.glGetUniformLocation(program, "uLightPos["    + i + "]");
            uLightColor[i]  = GL20.glGetUniformLocation(program, "uLightColor["  + i + "]");
            uLightRadius[i] = GL20.glGetUniformLocation(program, "uLightRadius[" + i + "]");
        }
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

    // ── Data class ───────────────────────────────────────────────────

    private static final class LightSource {
        final float uvX, uvY, r, g, b, radius;
        LightSource(float uvX, float uvY, float r, float g, float b, float radius) {
            this.uvX = uvX; this.uvY = uvY;
            this.r = r; this.g = g; this.b = b;
            this.radius = radius;
        }
    }
}
