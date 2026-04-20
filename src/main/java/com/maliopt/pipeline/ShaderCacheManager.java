package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.GPUDetector;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ShaderCacheManager {

    private static final Path CACHE_DIR = Paths.get("maliopt-cache", "shaders");
    private static boolean    supported = false;

    public static void init() {
        if (!GPUDetector.isMaliGPU() || !MaliOptConfig.enableShaderCache) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            GL11.glGetIntegerv(GL41.GL_NUM_PROGRAM_BINARY_FORMATS, count);
            int formats = count.get(0);

            if (formats > 0) {
                Files.createDirectories(CACHE_DIR);
                supported = true;
                MaliOptMod.LOGGER.info(
                    "[MaliOpt] ShaderCache activado ({} formato(s) binário(s)) ✅", formats);
            } else {
                MaliOptMod.LOGGER.warn(
                    "[MaliOpt] ShaderCache: driver não expõe formatos binários — cache desactivado");
            }
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] ShaderCache init falhou: {}", e.getMessage());
        }
    }

    /**
     * Tenta carregar programa compilado do cache.
     * @param programId  ID GL do programa já criado (glCreateProgram)
     * @param cacheKey   Nome único deste shader (ex: "terrain", "entity")
     * @return true se carregou com sucesso — podes saltar glLinkProgram
     */
    public static boolean loadFromCache(int programId, String cacheKey) {
        if (!supported) return false;

        Path path = CACHE_DIR.resolve(sanitize(cacheKey) + ".bin");
        if (!Files.exists(path)) return false;

        try {
            byte[]     bytes  = Files.readAllBytes(path);
            ByteBuffer buf    = ByteBuffer.allocateDirect(bytes.length);
            buf.put(bytes).flip();

            // Primeiros 4 bytes = formato binário do driver
            int format = buf.getInt();
            GL41.glProgramBinary(programId, format, buf);

            // Verifica se o link foi bem sucedido com o binário
            int[] status = new int[1];
            GL20.glGetProgramiv(programId, GL20.GL_LINK_STATUS, status);

            if (status[0] == GL11.GL_TRUE) {
                MaliOptMod.LOGGER.debug("[MaliOpt] Cache HIT: {}", cacheKey);
                return true;
            } else {
                MaliOptMod.LOGGER.warn("[MaliOpt] Cache corrompido — a remover: {}", cacheKey);
                Files.deleteIfExists(path);
                return false;
            }
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] loadFromCache '{}' falhou: {}", cacheKey, e.getMessage());
            return false;
        }
    }

    /**
     * Salva programa compilado no cache.
     * Chamar APÓS glLinkProgram e verificar GL_LINK_STATUS == GL_TRUE.
     */
    public static void saveToCache(int programId, String cacheKey) {
        if (!supported) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer lenBuf = stack.mallocInt(1);
            GL20.glGetProgramiv(programId, GL41.GL_PROGRAM_BINARY_LENGTH, lenBuf);
            int binaryLen = lenBuf.get(0);
            if (binaryLen <= 0) return;

            ByteBuffer binary    = ByteBuffer.allocateDirect(binaryLen);
            IntBuffer  formatBuf = stack.mallocInt(1);
            IntBuffer  actualLen = stack.mallocInt(1);

            GL41.glGetProgramBinary(programId, actualLen, formatBuf, binary);

            int    format  = formatBuf.get(0);
            int    len     = actualLen.get(0);
            byte[] toWrite = new byte[4 + len];

            // Guarda formato nos primeiros 4 bytes (big-endian)
            toWrite[0] = (byte) (format >> 24);
            toWrite[1] = (byte) (format >> 16);
            toWrite[2] = (byte) (format >>  8);
            toWrite[3] = (byte)  format;
            binary.get(toWrite, 4, len);

            Files.write(CACHE_DIR.resolve(sanitize(cacheKey) + ".bin"), toWrite);
            MaliOptMod.LOGGER.debug("[MaliOpt] Cache SAVE: {} ({} bytes)", cacheKey, len);

        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] saveToCache '{}' falhou: {}", cacheKey, e.getMessage());
        }
    }

    public static void clearCache() {
        try {
            if (Files.exists(CACHE_DIR)) {
                Files.walk(CACHE_DIR)
                     .filter(p -> p.toString().endsWith(".bin"))
                     .forEach(p -> {
                         try { Files.delete(p); }
                         catch (IOException ignored) {}
                     });
                MaliOptMod.LOGGER.info("[MaliOpt] Cache de shaders limpo");
            }
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] Erro ao limpar cache: {}", e.getMessage());
        }
    }

    public static boolean isSupported() { return supported; }

    private static String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
