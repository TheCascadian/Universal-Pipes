package com.thecascadian.universalpipes;

import com.mojang.logging.LogUtils;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(UniversalPipes.MODID)
public class UniversalPipes {

    public static final String MODID = "universal_pipes";
    public static final Logger LOGGER = LogUtils.getLogger();

    public UniversalPipes(IEventBus modBus) {
        RegistryHandler.register(modBus);
    }
}
