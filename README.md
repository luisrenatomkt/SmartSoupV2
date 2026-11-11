# SmartSoup 1.0.0 (Minecraft 1.8.9 Forge)

Consome Mushroom Stew da **hotbar** quando a vida chega a 13 HP ou menos (3,5 coracoes perdidos),
dropa a tigela vazia e volta ao slot anterior (espada).

## Compilar
Requisitos: JDK 8, Gradle 2.14.1 (ForgeGradle 2.1) e internet.

    gradle wrapper --gradle-version 2.14.1
    ./gradlew setupDecompWorkspace build

O jar fica em `build/libs/SmartSoup-1.0.0.jar`.

## Instalar
1. Instale o Minecraft Forge 1.8.9 (11.15.1.2318).
2. Copie o jar para `.minecraft/mods`.
3. Tecla **P** liga/desliga (mude em Controles > SmartSoup). Config em `config/smartsoup.cfg`
   (`healthThreshold` = 13, `dropBowl` = true, `debug` = false). Com `debug=true` o mod mostra no chat o que esta fazendo.

## Como funciona
Enquanto a vida estiver <= 13 e houver sopa na hotbar, ele continua bebendo (uma por vez, esperando a vida atualizar).
Seleciona o slot da sopa, segura o botao direito (uso real do item, o Minecraft chama
`PlayerControllerMP.sendUseItem`), espera o stack deixar de ser sopa, solta o botao, dropa a tigela
com `EntityPlayerSP.dropOneItem(false)` (mesmo pacote da tecla Q) e volta ao slot registrado.
Nao ha pacotes falsos, nem cura local, nem mexida no inventario.

## Limitacoes
- Em servidores vanilla/singleplayer a sopa so pode ser comida se a fome nao estiver cheia e leva ~1,6 s;
  em servidores de soup (plugin) o efeito e do servidor. Se o consumo nao concluir em 4 s, o mod aborta e volta ao slot.
- Se o jogador trocar de slot durante o consumo, o mod cancela sem mexer no slot.
- Se a hotbar nao tiver sopa, nada e feito.
- A visibilidade da animacao para outros jogadores depende do servidor. Sem garantia de compatibilidade.
- Servidores com anticheat podem tratar troca de slot + uso + drop automatico como macro.

## Compilar pelo GitHub (sem instalar nada)
1. Crie um repositorio no GitHub e envie todo o conteudo desta pasta (incluindo `.github`).
2. Abra a aba **Actions**; o workflow "Build SmartSoup" roda sozinho a cada push (ou clique em "Run workflow").
3. Quando terminar (verde), abra a execucao e baixe **SmartSoup-1.0.0** em *Artifacts*. Dentro do zip esta o `SmartSoup-1.0.0.jar`.
4. Se ficar vermelho, abra o log do passo que falhou e me mande.
