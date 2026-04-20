package com.maliopt.config;

import com.maliopt.MaliOptMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class MaliOptConfig {

    private static final Path      CONFIG_PATH = Paths.get("config", "maliopt.properties");
    private static final Properties PROPS       = new Properties();

    public static boolean enableTextureOpt  = true;
    public static boolean enableShaderCache = true;
    public static boolean enableDiscardFBO  = true;
    public static boolean enableDepthOpt    = true;

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                PROPS.load(in);
                enableTextureOpt  = bool("enable_texture_opt",  true);
                enableShaderCache = bool("enable_shader_cache",  true);
                enableDiscardFBO  = bool("enable_discard_fbo",   true);
                enableDepthOpt    = bool("enable_depth_opt",     true);
                MaliOptMod.LOGGER.info("[MaliOpt] Config carregada");
            } catch (IOException e) {
                MaliOptMod.LOGGER.warn("[MaliOpt] Erro ao ler config — usando defaults", e);
            }
        } else {
            save();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            PROPS.setProperty("enable_texture_opt",  String.valueOf(enableTextureOpt));
            PROPS.setProperty("enable_shader_cache",  String.valueOf(enableShaderCache));
            PROPS.setProperty("enable_discard_fbo",   String.valueOf(enableDiscardFBO));
            PROPS.setProperty("enable_depth_opt",     String.valueOf(enableDepthOpt));
            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                PROPS.store(out, "MaliOpt Phase 1 Config");
            }
        } catch (IOException e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] Erro ao salvar config", e);
        }
    }

    private static boolean bool(String key, boolean def) {
        return Boolean.parseBoolean(PROPS.getProperty(key, String.valueOf(def)));
    }
}
