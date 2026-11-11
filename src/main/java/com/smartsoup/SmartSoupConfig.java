package com.smartsoup;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Configuracoes persistidas em config/smartsoup.cfg. */
public class SmartSoupConfig {
    private static final String CAT = "general";

    public static boolean enabled = true;
    public static float healthThreshold = 13.0F; // HP restante que dispara o consumo
    public static boolean dropBowl = true;       // dropar a tigela vazia apos beber

    private static Configuration cfg;

    public static void load(File file) {
        cfg = new Configuration(file);
        sync();
    }

    public static void sync() {
        enabled = cfg.getBoolean("enabled", CAT, true, "Mod ligado ao iniciar.");
        healthThreshold = (float) cfg.getFloat("healthThreshold", CAT, 13.0F, 1.0F, 20.0F,
                "Consome sopa quando a vida for menor ou igual a este valor (HP; 13 = 3,5 coracoes perdidos).");
        dropBowl = cfg.getBoolean("dropBowl", CAT, true, "Dropa a tigela vazia depois de beber a sopa.");
        if (cfg.hasChanged()) cfg.save();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (cfg != null) {
            cfg.get(CAT, "enabled", true).set(value);
            cfg.save();
        }
    }
}
