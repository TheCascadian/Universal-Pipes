package com.thecascadian.universalpipes;

import com.mojang.logging.LogUtils;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import com.thecascadian.universalpipes.client.ClientSetup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(UniversalPipes.MODID)
public class UniversalPipes {

    public static final String MODID = "universal_pipes";
    public static final Logger LOGGER = LogUtils.getLogger();

    public UniversalPipes(IEventBus modBus, ModContainer container) {
        RegistryHandler.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, PipesConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, PipesConfig.CLIENT_SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT)
            ClientSetup.registerConfigScreen(container);
    }
}
