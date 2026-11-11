package com.smartsoup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

/**
 * Maquina de estados do consumo.
 *
 * IDLE     -> espera vida <= limite, armado, com sopa na hotbar.
 * SELECT   -> slot da sopa selecionado; espera 1 tick para o Minecraft sincronizar o slot com o servidor.
 * USING    -> mantem o botao direito pressionado (uso real do item) ate a sopa virar tigela.
 * DROPPING -> solta o botao, dropa a tigela (mesmo caminho da tecla Q) e espera o servidor processar.
 * RETURN   -> volta ao slot registrado antes da acao.
 *
 * Interrupcoes (abrir inventario/chat, trocar de slot, soltar o botao, mover a sopa durante o recraft)
 * NAO deixam o mod travado: o slot e restaurado e o mod rearma para tentar de novo depois de um
 * pequeno intervalo.
 */
public class SoupController {
    private enum State { IDLE, SELECT, USING, DROPPING, RETURN }

    private static final int USE_TIMEOUT_TICKS = 80;     // sopa vanilla leva 32 ticks
    private static final int DROP_WAIT_TICKS = 2;
    private static final int RETRY_COOLDOWN_TICKS = 10;  // pausa apos interrupcao (nao e falha do servidor)
    private static final int FAIL_COOLDOWN_TICKS = 20;   // pausa apos timeout
    private static final int MAX_FAILS = 2;              // timeouts seguidos antes de esperar a vida subir

    private final Minecraft mc = Minecraft.getMinecraft();

    private State state = State.IDLE;
    private boolean armed = true;
    private int previousSlot = -1;
    private int soupSlot = -1;
    private int ticks = 0;
    private int cooldown = 0;
    private int failures = 0;
    private boolean keyHeld = false;

    public void tick() {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null || p.isDead || p.getHealth() <= 0.0F) {
            if (state != State.IDLE || !armed || keyHeld || cooldown != 0) reset();
            return;
        }

        if (cooldown > 0) cooldown--;

        // Rearma quando a vida passa do limite
        if (p.getHealth() > SmartSoupConfig.healthThreshold) {
            armed = true;
            failures = 0;
        }

        switch (state) {
            case IDLE:
                if (!SmartSoupConfig.enabled || !armed || cooldown > 0) return;
                if (p.getHealth() > SmartSoupConfig.healthThreshold) return;
                if (mc.currentScreen != null || p.isUsingItem()) return;
                int slot = findSoupInHotbar(p);
                if (slot < 0) return; // sem sopa: nao troca slot, nao mexe em nada
                previousSlot = p.inventory.currentItem;
                soupSlot = slot;
                p.inventory.currentItem = slot; // o Minecraft sincroniza no proprio tick (syncCurrentPlayItem)
                armed = false;                  // evita varias tentativas enquanto a vida segue baixa
                ticks = 0;
                state = State.SELECT;
                break;

            case SELECT:
                if (mc.currentScreen != null
                        || p.inventory.currentItem != soupSlot
                        || !isSoup(p.inventory.getStackInSlot(soupSlot))) {
                    interrupt(p);
                    return;
                }
                setUseKey(true); // uso real do item: o Minecraft chama PlayerControllerMP.sendUseItem
                ticks = 0;
                state = State.USING;
                break;

            case USING:
                // Inventario/chat aberto, slot trocado, ou botao solto por fora (o Minecraft solta
                // todas as teclas ao abrir uma tela): interrompe com seguranca e tenta de novo depois.
                if (mc.currentScreen != null
                        || p.inventory.currentItem != soupSlot
                        || (keyHeld && !mc.gameSettings.keyBindUseItem.isKeyDown())) {
                    interrupt(p);
                    return;
                }
                ItemStack held = p.inventory.getStackInSlot(soupSlot);
                if (isSoup(held)) {
                    if (++ticks > USE_TIMEOUT_TICKS) timeout(p); // servidor bloqueou / fome cheia no vanilla
                } else if (held == null || held.getItem() == Items.bowl) {
                    // consumo concluido: virou tigela (ou o servidor removeu o item)
                    setUseKey(false);
                    failures = 0;
                    ticks = 0;
                    state = State.DROPPING;
                } else {
                    interrupt(p); // outro item entrou no slot (recraft/movimentacao do jogador)
                }
                break;

            case DROPPING:
                if (ticks == 0) {
                    ItemStack s = p.inventory.getStackInSlot(soupSlot);
                    if (SmartSoupConfig.dropBowl && s != null && s.getItem() == Items.bowl
                            && p.inventory.currentItem == soupSlot) {
                        p.dropOneItem(false); // igual a tecla Q: envia C07PacketPlayerDigging DROP_ITEM
                    }
                }
                if (++ticks >= DROP_WAIT_TICKS) state = State.RETURN;
                break;

            case RETURN:
                restoreSlot(p);
                state = State.IDLE;
                break;
        }
    }

    /** Limpa tudo: usado ao morrer, sair do mundo ou desativar o mod. */
    public void reset() {
        setUseKey(false);
        state = State.IDLE;
        armed = true;
        previousSlot = -1;
        soupSlot = -1;
        ticks = 0;
        cooldown = 0;
        failures = 0;
    }

    /** Consumo interrompido por algo externo: solta o botao, volta ao slot e rearma com pausa curta. */
    private void interrupt(EntityPlayerSP p) {
        setUseKey(false);
        restoreSlot(p);
        state = State.IDLE;
        ticks = 0;
        armed = true;
        cooldown = RETRY_COOLDOWN_TICKS;
    }

    /** Consumo nao terminou a tempo. Tenta de novo no maximo MAX_FAILS vezes seguidas. */
    private void timeout(EntityPlayerSP p) {
        setUseKey(false);
        restoreSlot(p);
        state = State.IDLE;
        ticks = 0;
        failures++;
        if (failures < MAX_FAILS) {
            armed = true;
            cooldown = FAIL_COOLDOWN_TICKS;
        } // senao fica desarmado ate a vida passar do limite
    }

    /** So volta ao slot anterior se o jogador ainda estiver no slot da sopa (nao sobrescreve troca manual). */
    private void restoreSlot(EntityPlayerSP p) {
        if (previousSlot >= 0 && previousSlot <= 8 && p.inventory.currentItem == soupSlot) {
            p.inventory.currentItem = previousSlot;
        }
        previousSlot = -1;
        soupSlot = -1;
    }

    /** Procura apenas nos slots 0..8 (hotbar). */
    private int findSoupInHotbar(EntityPlayerSP p) {
        for (int i = 0; i < 9; i++) {
            if (isSoup(p.inventory.mainInventory[i])) return i;
        }
        return -1;
    }

    private boolean isSoup(ItemStack s) {
        return s != null && s.getItem() == Items.mushroom_stew;
    }

    private void setUseKey(boolean down) {
        // sem "early return": o Minecraft pode ter soltado a tecla por conta propria (ex.: ao abrir o inventario)
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), down);
        keyHeld = down;
    }
}
