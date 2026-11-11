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
 * USING    -> mantem o botao direito pressionado (uso real do item) ate o stack deixar de ser sopa.
 * DROPPING -> solta o botao, dropa a tigela (mesmo caminho da tecla Q) e espera o servidor processar.
 * RETURN   -> volta ao slot registrado antes da acao.
 */
public class SoupController {
    private enum State { IDLE, SELECT, USING, DROPPING, RETURN }

    private static final int USE_TIMEOUT_TICKS = 80;   // sopa vanilla leva 32 ticks
    private static final int DROP_WAIT_TICKS = 2;

    private final Minecraft mc = Minecraft.getMinecraft();

    private State state = State.IDLE;
    private boolean armed = true;
    private int previousSlot = -1;
    private int soupSlot = -1;
    private int ticks = 0;
    private boolean keyHeld = false;

    public void tick() {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null || p.isDead || p.getHealth() <= 0.0F) {
            if (state != State.IDLE || !armed) reset();
            return;
        }

        // Rearma quando a vida passa do limite
        if (p.getHealth() > SmartSoupConfig.healthThreshold) armed = true;

        switch (state) {
            case IDLE:
                if (!SmartSoupConfig.enabled || !armed) return;
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
                if (p.inventory.currentItem != soupSlot || !isSoup(p.inventory.getStackInSlot(soupSlot))) {
                    abort(false);
                    return;
                }
                if (++ticks >= 1) {
                    setUseKey(true); // uso real do item: o Minecraft chama PlayerControllerMP.sendUseItem
                    ticks = 0;
                    state = State.USING;
                }
                break;

            case USING:
                // Jogador mudou de slot manualmente: cancela sem mexer no slot
                if (p.inventory.currentItem != soupSlot) {
                    abort(false);
                    return;
                }
                ItemStack held = p.inventory.getStackInSlot(soupSlot);
                if (!isSoup(held)) { // consumo concluido: virou tigela (ou vazio)
                    setUseKey(false);
                    ticks = 0;
                    state = State.DROPPING;
                    break;
                }
                if (++ticks > USE_TIMEOUT_TICKS) { // falhou (ex.: servidor bloqueou, fome cheia no vanilla)
                    abort(true);
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
    }

    private void abort(boolean restoreSlot) {
        setUseKey(false);
        EntityPlayerSP p = mc.thePlayer;
        if (restoreSlot && p != null) restoreSlot(p);
        state = State.IDLE;
        ticks = 0;
    }

    private void restoreSlot(EntityPlayerSP p) {
        if (previousSlot >= 0 && previousSlot <= 8) p.inventory.currentItem = previousSlot;
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
        if (keyHeld == down) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), down);
        keyHeld = down;
    }
}
