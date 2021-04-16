package org.samo_lego.taterzens;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.util.Identifier;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.samo_lego.taterzens.event.EventHandler;
import org.samo_lego.taterzens.npc.TaterzenNPC;

import java.io.File;

import static org.samo_lego.taterzens.Taterzens.*;

@Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
@Mod(MODID)
public class TaterzensForge {

    public TaterzensForge() {
        taterDir = new File(FMLPaths.CONFIGDIR.get() + "/Taterzens/presets");
        Identifier identifier = new Identifier(MODID, "npc");

        //noinspection
        TATERZEN_TYPE = (EntityType<TaterzenNPC>) EntityType.Builder
                .create(TaterzenNPC::new, SpawnGroup.MONSTER)
                .setDimensions(0.6F, 1.8F)
                .build(identifier.toString())
                .setRegistryName(identifier.toString());

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new EventHandler());
        MinecraftForge.EVENT_BUS.addListener(TaterzensForge::entityAttributes);
        Taterzens.onInitialize();
    }

    @SubscribeEvent
    public static void entityAttributes(EntityAttributeCreationEvent event) {
        event.put(TATERZEN_TYPE, TaterzenNPC.createTaterzenAttributes().build());
    }
}