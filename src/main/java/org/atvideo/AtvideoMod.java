package org.atvideo;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.atvideo.network.AtvideoNetwork;
import org.slf4j.Logger;

@Mod(AtvideoMod.MODID)
public final class AtvideoMod {
    public static final String MODID = "atvideo";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AtvideoMod(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(AtvideoNetwork::register);
    }
}