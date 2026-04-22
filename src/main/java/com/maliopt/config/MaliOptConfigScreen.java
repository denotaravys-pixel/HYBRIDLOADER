package com.maliopt.config;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * MaliOptConfigScreen — ecrã YACL completo do MaliOpt.
 *
 * Integrado com Mod Menu via MaliOptMod#getModConfigScreenFactory().
 * Cada opção lê e escreve diretamente em MaliOptVisualConfig.
 */
public final class MaliOptConfigScreen {

    private MaliOptConfigScreen() {}

    public static Screen create(Screen parent) {
        MaliOptVisualConfig cfg = MaliOptVisualConfig.get();

        return YetAnotherConfigLib.createBuilder()
            .title(Text.literal("⚡ MaliOpt — Configurações Visuais"))

            // ════════════════════════════════════════════════════════
            // CATEGORIA: SOMBRAS
            // ════════════════════════════════════════════════════════
            .category(ConfigCategory.createBuilder()
                .name(Text.literal("🌑 Sombras"))
                .tooltip(Text.literal("Controla o sistema de sombras em tempo real."))

                .group(OptionGroup.createBuilder()
                    .name(Text.literal("Geral"))
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Ativar Sombras"))
                        .description(OptionDescription.of(
                            Text.literal("Liga/desliga o shadow map. Desativar poupa GPU significativa.")))
                        .binding(false,
                            () -> cfg.shadowsEnabled,
                            v  -> cfg.shadowsEnabled = v)
                        .controller(TickBoxControllerBuilder::create)
                        .build())

                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Resolução"))
                        .description(OptionDescription.of(
                            Text.literal("Resolução do shadow map (512 = rápido, 2048 = bonito).")))
                        .binding(512,
                            () -> cfg.shadowResolution,
                            v  -> cfg.shadowResolution = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                            .range(256, 2048).step(256))
                        .build())

                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Sombras Suaves (PCF)"))
                        .description(OptionDescription.of(
                            Text.literal("Suaviza as bordas das sombras. Leve custo de GPU.")))
                        .binding(true,
                            () -> cfg.shadowSoftEnabled,
                            v  -> cfg.shadowSoftEnabled = v)
                        .controller(TickBoxControllerBuilder::create)
                        .build())
                    .build())

                .group(OptionGroup.createBuilder()
                    .name(Text.literal("Alcance & Cascatas"))
                    .option(Option.<Float>createBuilder()
                        .name(Text.literal("Distância"))
                        .description(OptionDescription.of(
                            Text.literal("Até onde as sombras são projetadas (em blocos).")))
                        .binding(48.0f,
                            () -> cfg.shadowDistance,
                            v  -> cfg.shadowDistance = v)
                        .controller(opt -> FloatSliderControllerBuilder.create(opt)
                            .range(16.0f, 128.0f).step(8.0f))
                        .build())

                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Cascatas"))
                        .description(OptionDescription.of(
                            Text.literal("Mais cascatas = sombras mais nítidas ao longe. Mais custo.")))
                        .binding(1,
                            () -> cfg.shadowCascades,
                            v  -> cfg.shadowCascades = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                            .range(1, 2).step(1))
                        .build())
                    .build())
                .build())

            // ════════════════════════════════════════════════════════
            // CATEGORIA: REFLEXOS
            // ════════════════════════════════════════════════════════
            .category(ConfigCategory.createBuilder()
                .name(Text.literal("🪞 Reflexos (SSR)"))
                .tooltip(Text.literal("Screen-Space Reflections — reflexos na água e superfícies."))

                .group(OptionGroup.createBuilder()
                    .name(Text.literal("Screen-Space Reflections"))
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Ativar SSR"))
                        .description(OptionDescription.of(
                            Text.literal("Liga os reflexos em tempo real. Custo moderado de GPU.")))
                        .binding(false,
                            () -> cfg.ssrEnabled,
                            v  -> cfg.ssrEnabled = v)
                        .controller(TickBoxControllerBuilder::create)
                        .build())

                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Apenas na Água"))
                        .description(OptionDescription.of(
                            Text.literal("Limita SSR à superfície de água. Muito mais eficiente.")))
                        .binding(true,
                            () -> cfg.ssrWaterOnly,
                            v  -> cfg.ssrWaterOnly = v)
                        .controller(TickBoxControllerBuilder::create)
                        .build())

                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Precisão (Passos)"))
                        .description(OptionDescription.of(
                            Text.literal("Mais passos = reflexos mais profundos. Mais lento.")))
                        .binding(16,
                            () -> cfg.ssrMaxSteps,
                            v  -> cfg.ssrMaxSteps = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                            .range(8, 48).step(8))
                        .build())
                    .build())
                .build())

            // ════════════════════════════════════════════════════════
            // CATEGORIA: ILUMINAÇÃO COLORIDA
            // ════════════════════════════════════════════════════════
            .category(ConfigCategory.createBuilder()
                .name(Text.literal("🌈 Iluminação Colorida"))
                .tooltip(Text.literal("Luzes dinâmicas com cor real — tochas laranja, soul fire azul..."))

                .group(OptionGroup.createBuilder()
                    .name(Text.literal("Luzes Dinâmicas"))
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Ativar Luzes Coloridas"))
                        .description(OptionDescription.of(
                            Text.literal("Cada fonte de luz emite a sua cor real no ambiente.")))
                        .binding(false,
                            () -> cfg.coloredLightsEnabled,
                            v  -> cfg.coloredLightsEnabled = v)
                        .controller(TickBoxControllerBuilder::create)
                        .build())

                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Máximo de Luzes Ativas"))
                        .description(OptionDescription.of(
                            Text.literal("Limite de fontes de luz simultâneas. 4 é o ponto ideal para Mali-G52.")))
                        .binding(4,
                            () -> cfg.maxDynamicLights,
                            v  -> cfg.maxDynamicLights = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                            .range(2, 8).step(2))
                        .build())
                    .build())
                .build())

            // ════════════════════════════════════════════════════════
            // CATEGORIA: EFEITOS EXISTENTES
            // ════════════════════════════════════════════════════════
            .category(ConfigCategory.createBuilder()
                .name(Text.literal("✨ Bloom & Lighting"))
                .tooltip(Text.literal("Controlo fino dos efeitos existentes do MaliOpt."))

                .group(OptionGroup.createBuilder()
                    .name(Text.literal("Bloom"))
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Ativar Bloom"))
                        .description(OptionDescription.of(
                            Text.literal("Efeito de brilho em torno de fontes de luz.")))
                        .binding(true,
                            () -> cfg.bloomEnabled,
                            v  -> cfg.bloomEnabled = v)
                        .controller(TickBoxControllerBuilder::create)
                        .build())

                    .option(Option.<Float>createBuilder()
                        .name(Text.literal("Intensidade do Bloom"))
                        .description(OptionDescription.of(
                            Text.literal("Intensidade do efeito de brilho. 0.35 é o valor recomendado.")))
                        .binding(0.35f,
                            () -> cfg.bloomIntensity,
                            v  -> cfg.bloomIntensity = v)
                        .controller(opt -> FloatSliderControllerBuilder.create(opt)
                            .range(0.0f, 1.0f).step(0.05f))
                        .build())
                    .build())

                .group(OptionGroup.createBuilder()
                    .name(Text.literal("Iluminação"))
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Ativar Lighting Pass"))
                        .description(OptionDescription.of(
                            Text.literal("Micro-ajustes de warmth, AO e contraste sobre a imagem base.")))
                        .binding(true,
                            () -> cfg.lightingEnabled,
                            v  -> cfg.lightingEnabled = v)
                        .controller(TickBoxControllerBuilder::create)
                        .build())

                    .option(Option.<Float>createBuilder()
                        .name(Text.literal("Warmth"))
                        .description(OptionDescription.of(
                            Text.literal("Tom quente subtil nas zonas iluminadas. 0.06 por defeito.")))
                        .binding(0.06f,
                            () -> cfg.warmthStrength,
                            v  -> cfg.warmthStrength = v)
                        .controller(opt -> FloatSliderControllerBuilder.create(opt)
                            .range(0.0f, 0.15f).step(0.01f))
                        .build())

                    .option(Option.<Float>createBuilder()
                        .name(Text.literal("Oclusão Ambiente (AO)"))
                        .description(OptionDescription.of(
                            Text.literal("Escurece sombras profundas. 0.10 por defeito.")))
                        .binding(0.10f,
                            () -> cfg.aoStrength,
                            v  -> cfg.aoStrength = v)
                        .controller(opt -> FloatSliderControllerBuilder.create(opt)
                            .range(0.0f, 0.20f).step(0.01f))
                        .build())
                    .build())
                .build())

            // ════════════════════════════════════════════════════════
            // CATEGORIA: PERFORMANCE
            // ════════════════════════════════════════════════════════
            .category(ConfigCategory.createBuilder()
                .name(Text.literal("⚙️ Performance"))
                .tooltip(Text.literal("Gestão automática de qualidade baseada no FPS real."))

                .group(OptionGroup.createBuilder()
                    .name(Text.literal("Gestão Automática"))
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Qualidade Automática"))
                        .description(OptionDescription.of(
                            Text.literal("O MaliOpt ajusta os efeitos automaticamente para manter o FPS alvo.")))
                        .binding(true,
                            () -> cfg.autoQuality,
                            v  -> cfg.autoQuality = v)
                        .controller(TickBoxControllerBuilder::create)
                        .build())

                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("FPS Alvo"))
                        .description(OptionDescription.of(
                            Text.literal("FPS mínimo antes de reduzir qualidade. 45 é ideal para Mali-G52.")))
                        .binding(45,
                            () -> cfg.targetFPS,
                            v  -> cfg.targetFPS = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                            .range(20, 60).step(5))
                        .build())
                    .build())
                .build())

            .save(MaliOptVisualConfig::save)
            .build()
            .generateScreen(parent);
    }
                            }
