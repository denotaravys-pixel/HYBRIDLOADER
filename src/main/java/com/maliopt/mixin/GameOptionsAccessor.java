package com.maliopt.mixin;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameOptions.class)
public interface GameOptionsAccessor {
    @Accessor("viewDistance")
    SimpleOption<Integer> maliopt_getViewDistance();

    @Accessor("simulationDistance")
    SimpleOption<Integer> maliopt_getSimulationDistance();
}
