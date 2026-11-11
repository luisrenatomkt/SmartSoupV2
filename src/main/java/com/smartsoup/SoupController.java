package com.smartsoup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;

/**
 * Maquina de estados do consumo.
 *
 * IDLE     -> vida <= limite + sopa na hotbar => inicia.
 * SELECT   -> slot da sopa selecionado; espera 1 tick para o Minecraft sincronizar o slot com o servidor.
 * USING    -> mantem o botao direito pressionado (uso real do item) ate a sopa virar tigela.
 * DROPPING -> solta o botao, dropa a tigela (mesmo caminho da tecla Q) e espera o servidor processar.
 * RETURN   -> volta ao slot registrado antes da acao.
 *
 * Regras de robustez:
 *  - Depois de um consumo concluido o mod ja fica pronto para o proximo (se a vida ainda estiver <= limite),
 *    aguardando a atualizacao de vida do servidor (ou no maximo POST_USE_WAIT_TICKS) para nao beber duas vezes
 *    pelo mesmo dano.
 *  - Interrupcoes (inventario/chat, troca de slot, recraft) restauram o slot e tentam de novo logo depois.
 *  - Se o servidor nunca concluir o consumo (ex.: fome cheia no vanilla), apos MAX_FAILS tentativas o mod espera a
 *    vida passar do limite para nao ficar trocando de slot em loop.
 *  - Um watchdog garante que o mod nunca fica preso fora do estado IDLE.
 */
public class SoupController {
    private enum State { IDLE, SELECT, USING, DROPPING, RETURN }

    private static final int USE_TIMEOUT_TICKS = 100;
    private static final int DROP_WAIT_TICKS = 2;
    private static final int RETRY_COOLDOWN_TICKS = 10;   // apos interrupcao externa
    private static final int FAIL_COOLDOWN_TICKS = 20;    // apos timeout
    private static final int POST_USE_WAIT_TICKS = 10;    // espera pela atualizacao de vida apos beber
    private static final int MAX_FAILS = 2;
    private static final int MAX_BUSY_TICKS = 160;        // watchdog

    private final Minecraft mc = Minecraft.getMinecraft();

    private State state = State.IDLE;
    private boolean armed = true;       // so fica false apos MAX_FAILS timeouts seguidos
    private int previousSlot = -1;
    private int soupSlot = -1;
    private int ticks = 0;
    private int busy = 0;
    private int cooldown = 0;
    private int postWait = 0;
    private float hpBeforeUse = 0.0F;
    private int failures = 0;
    private boolean keyHeld = false;

    public void tick() {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null || p.isDead || p.getHealth() <= 0.0F) {
            if (state != State.IDLE || !armed || keyHeld || cooldown != 0 || postWait != 0) reset();
            return;
        }

        if (cooldown > 0) cooldown--;

        // Vida acima do limite: zera falhas e garante que esta armado
        if (p.getHealth() > SmartSoupConfig.healthThreshold) {
            armed = true;
            failures = 0;
        }

        // Watchdog
        if (state != State.IDLE) {
            if (++busy > MAX_BUSY_TICKS) {
                dbg("watchdog: estado preso, resetando");
                interrupt(p);
                return;
            }
        } else {
            busy = 0;
        }

        switch (state) {
            case IDLE:
                if (!SmartSoupConfig.enabled || !armed || cooldown > 0) return;
                if (p.getHealth() > SmartSoupConfig.healthThreshold) return;
                if (mc.currentScreen != null) return;
                if (postWait > 0) {
                    // espera a vida atualizar depois da sopa anterior (ou o tempo maximo)
                    if (p.getHealth() != hpBeforeUse) postWait = 0;
                    else { postWait--; return; }
                }
                int slot = findSoupInHotbar(p);
                if (slot < 0) return; // sem sopa: nao troca slot, nao mexe em nada
                previousSlot = p.inventory.currentItem;
                soupSlot = slot;
                hpBeforeUse = p.getHealth();
                p.inventory.currentItem = slot; // o Minecraft sincroniza no proprio tick (syncCurrentPlayItem)
                ticks = 0;
                state = State.SELECT;
                dbg("vida " + p.getHealth() + ": selecionando sopa no slot " + (slot + 1));
                break;

            case SELECT:
                if (mc.currentScreen != null
                        || p.inventory.currentItem != soupSlot
                        || !isSoup(p.inventory.getStackInSlot(soupSlot))) {
                    dbg("interrompido na selecao");
                    interrupt(p);
                    return;
                }
                setUseKey(true); // uso real do item: o Minecraft chama PlayerControllerMP.sendUseItem
                ticks = 0;
                state = State.USING;
                break;

            case USING:
                if (mc.currentScreen != null || p.inventory.currentItem != soupSlot) {
                    dbg("interrompido: tela aberta ou slot trocado");
                    interrupt(p);
                    return;
                }
                // Se o botao foi solto por fora (ex.: jogador soltou o botao direito), pressiona de novo
                if (keyHeld && !mc.gameSettings.keyBindUseItem.isKeyDown()) setUseKey(true);

                ItemStack held = p.inventory.getStackInSlot(soupSlot);
                if (isSoup(held)) {
                    if (++ticks > USE_TIMEOUT_TICKS) {
                        dbg("timeout: a sopa nao foi consumida");
                        timeout(p);
                    }
                } else if (held == null || held.getItem() == Items.bowl) {
                    // consumo concluido: virou tigela (ou o servidor removeu o item)
                    setUseKey(false);
                    failures = 0;
                    ticks = 0;
                    state = State.DROPPING;
                    dbg("sopa consumida");
                } else {
                    dbg("interrompido: outro item entrou no slot");
                    interrupt(p); // jogador mexeu no inventario (recraft)
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
                busy = 0;
                postWait = POST_USE_WAIT_TICKS; // pronto para a proxima sopa se a vida continuar baixa
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
        busy = 0;
        cooldown = 0;
        postWait = 0;
        failures = 0;
    }

    /** Consumo interrompido por algo externo: solta o botao, volta ao slot e tenta de novo apos pausa curta. */
    private void interrupt(EntityPlayerSP p) {
        setUseKey(false);
        restoreSlot(p);
        state = State.IDLE;
        ticks = 0;
        busy = 0;
        postWait = 0;
        cooldown = RETRY_COOLDOWN_TICKS;
    }

    /** Consumo nao terminou a tempo. Tenta de novo no maximo MAX_FAILS vezes seguidas. */
    private void timeout(EntityPlayerSP p) {
        setUseKey(false);
        restoreSlot(p);
        state = State.IDLE;
        ticks = 0;
        busy = 0;
        postWait = 0;
        failures++;
        if (failures < MAX_FAILS) {
            cooldown = FAIL_COOLDOWN_TICKS;
        } else {
            armed = false; // espera a vida passar do limite
            dbg("desistindo ate a vida passar do limite");
        }
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

    private void dbg(String msg) {
        if (SmartSoupConfig.debug && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("[SmartSoup][debug] " + msg));
        }
    }
}
