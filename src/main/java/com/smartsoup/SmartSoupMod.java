package com.smartsoup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = SmartSoupMod.MODID, name = "SmartSoup", version = "1.0.0",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class SmartSoupMod {
    public static final String MODID = "smartsoup";

    private final SoupController controller = new SoupController();
    private KeyBinding toggleKey;

    @EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        SmartSoupConfig.load(e.getSuggestedConfigurationFile());
    }

    @EventHandler
    public void init(FMLInitializationEvent e) {
        toggleKey = new KeyBinding("Ligar/Desligar SmartSoup", Keyboard.KEY_P, "SmartSoup");
        ClientRegistry.registerKeyBinding(toggleKey);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        // isPressed() consome o clique, entao so dispara uma vez por toque
        while (toggleKey.isPressed()) {
            boolean now = !SmartSoupConfig.enabled;
            SmartSoupConfig.setEnabled(now);
            if (!now) controller.reset(); // desativado: nao deixa consumo em andamento
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        "[SmartSoup] " + (now ? "ATIVADO" : "DESATIVADO")));
            }
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        // START: roda antes do processamento de teclas/mouse do proprio tick do Minecraft
        if (e.phase == TickEvent.Phase.START) controller.tick();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        controller.reset();
    }
}
