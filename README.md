# HYBRIDLOADER
# MaliOpt — Mali GPU Optimization Layer

Fabric mod para Minecraft 1.21.1 que optimiza a pipeline de renderização
para GPUs Mali (arquitectura Bifrost/Valhall) no Pojav Launcher (GL4ES).

## Alvo Principal
- **GPU:** Mali-G52 MC2 (Helio G85 — TECNO SPARK 9 Pro)
- **OpenGL ES:** 3.2 via GL4ES (Pojav)
- **Fabric Loader:** 0.15.x
- **Minecraft:** 1.21.1

## Fases de Desenvolvimento

| Fase | Estado | Descrição |
|------|--------|-----------|
| 1 — Base | 🔄 Em progresso | GPUDetector, ExtensionActivator, discard_framebuffer |
| 2 — Pipeline | ⏳ Pendente | TileBasedOptimizer, texturas, depth buffer |
| 3 — Integração | ⏳ Pendente | Sodium 0.6.x, Iris 1.8.x, Config screen |
| 4 — Tuning | ⏳ Pendente | Benchmark FPS, ajuste por versão Mali |

## Build

### Pré-requisitos
- Java 21
- Gradle (via wrapper incluído)

### Compilar
```bash
./gradlew clean build
