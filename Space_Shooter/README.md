# Space Shooter — menu, combate e HUD

Jogo desktop em Java Swing/AWT, sem dependências externas.

## Compilar e executar

Requer JDK 17 ou superior. Execute a partir da raiz do projeto:

```bash
javac Main.java
java Main
```

Também é possível iniciar o pacote compilado com `java -jar space-shooter-hud.jar`.

## Menu e controles

O jogo abre no menu independente da HUD de combate. Clique em **INICIAR** ou pressione **Enter/Espaço** para começar na fase 1; clique em **SAIR** ou pressione **Esc/Q** para fechar a janela. Os botões mostram realce ao passar o cursor e resposta visual ao serem pressionados. A ativação muda imediatamente o estado do menu, evitando iniciar várias partidas pelo mesmo clique.

Durante a partida, **WASD** ou **setas** movem a nave, **Espaço** dispara e **P** pausa/retoma. Os três botões inferiores alternam pausa, radar e telemetria; os atalhos correspondentes são **P**, **R** e **I**. Depois da vitória, **Enter/R** inicia uma nova partida e **Esc** retorna ao menu. Na tela **GAME OVER**, **Enter/R** reinicia e **Esc** retorna ao menu; ambos também estão disponíveis como botões clicáveis.

Não há simulação de inimigos, projéteis ou fases no menu: os inimigos e a fila da fase 1 só são inicializados ao iniciar uma nova partida. A cena do menu usa apenas o fundo estrelado e seus próprios elementos visuais.

## Sprites personalizados (PNG 64×64)

Os quatro arquivos enviados já estão incluídos nestes caminhos, sem alterar os nomes:

```text
assets/images/player.png
assets/images/enemies/enemy_1.png
assets/images/enemies/enemy_2.png
assets/images/enemies/enemy_3.png
```

O carregador lê cada arquivo uma vez ao iniciar, primeiro pelo caminho do projeto e depois pelo classpath. Os PNGs também são incluídos dentro do JAR, então continuam disponíveis ao executar `java -jar` mesmo fora da pasta do projeto. São aceitas imagens legíveis de exatamente 64×64; se um arquivo estiver ausente ou inválido, seu sprite vetorial correspondente serve como fallback. Reinicie o jogo após adicionar/trocar um PNG.

O asset do jogador é desenhado em 73,6×73,6 pixels (arredondado para 74×74), exatamente 15% maior que o desenho anterior de 64×64; o arquivo original permanece intacto e sua proporção é preservada. O fallback vetorial também recebe escala de 1,15. O desenho é centralizado no centro da nave. A hitbox (70×86), a posição física, os limites de velocidade e o ponto de disparo não são ampliados.

O código atual não contém uma sequência de quadros de animação; os quatro assets recebidos são sprites estáticos. Para dar mais fluidez sem afetar a física, a nave inclina suavemente até 12° conforme a velocidade lateral, retorna suavemente à posição neutra quando para e tem um leve movimento vertical enquanto se desloca. A suavização é independente do movimento real.

## Fases e balanceamento

Cada fase conserva uma fila finita e o intervalo de 1,5 s entre aparições. Só depois de eliminar todos os inimigos ativos e pendentes começa a próxima fase. Inimigos que saem pelo limite inferior voltam ao topo como alvos da mesma onda. As velocidades-base e a distribuição dos três tipos existentes são preservadas.

| Fase | Inimigos | Multiplicador da velocidade-base |
|---:|---:|---:|
| 1 | 3 | 80% |
| 2 | 5 | 90% |
| 3 | 7 | 100% |
| 4 | 9 | 110% |
| 5 | 12 | 120% |

Depois de derrotar a onda da fase 5, aparece a tela de vitória; nenhuma fase 6 é criada. Os parâmetros ficam no início de `Main.java`: `PHASE_ENEMY_COUNTS`, `PHASE_SPEED_MULTIPLIERS`, `PHASE_SPAWN_INTERVAL` e `ENEMY_BASE_MIN_SPEED`/`ENEMY_BASE_MAX_SPEED`.

## HUD e velocidade

A HUD mantém a redução de escala de **10%** (`HUD_SCALE_REDUCTION = 0.90`) e a redução relativa de **10%** na opacidade (`HUD_OPACITY_FACTOR = 0.90`). A nova paleta usa rosa-bebê `#FFB6D9`, rosa-claro de contorno `#FFC8E5`, rosa suave `#FF8FC7` e painéis `#160817` translúcidos; textos e indicadores permanecem contrastantes. A identidade rosa também foi aplicada aos controles do menu, pausa, vitória e GAME OVER. As cores do cenário, player, inimigos e projéteis continuam separadas da paleta da interface.

O indicador circular de velocidade no centro, seu botão, atalho, estado e método de desenho foram removidos. O espaço central volta a ser campo de jogo. O radar tático, que é um mapa de posições distinto do indicador removido, permanece disponível e foi renomeado para evitar confusão.

## Vidas e dano

Cada nova partida inicia com **3 vidas**. O jogador perde uma vida quando colide com o corpo de um inimigo; um contato retira apenas uma vida e ativa **1,35 s de invulnerabilidade**, indicada por um breve piscar do sprite. Se restarem vidas, a nave reaparece no centro inferior sem limpar a fila, reiniciar a fase ou duplicar inimigos. Os três corações na HUD representam diretamente o contador; os corações perdidos ficam apagados. Vidas são tentativas separadas e não uma barra de energia.

Ao chegar a zero, o jogo entra em **GAME OVER**: o loop deixa de atualizar combate, movimento, disparos, inimigos e fases, mas continua desenhando a tela final. Os botões **REINICIAR** e **MENU INICIAL** funcionam por mouse; Enter/R e Esc oferecem as mesmas ações pelo teclado. Uma partida nova restaura as três vidas e reinicializa as entidades e a fase.

O impacto dispara uma tremida de até **3,5 px** por **0,20 s**, com amortecimento quadrático. Ela usa apenas uma transformação temporária da camada visual do mundo; não altera posições físicas, colisões, tiros ou coordenadas do radar. A amplitude volta a zero automaticamente.

A velocidade máxima linear da nave continua **10% menor** que a original: 630 unidades/s, com aceleração multiplicada pelo mesmo fator. O teto diagonal é limitado ao mesmo valor. A velocidade dos projéteis e os parâmetros dos inimigos não foram alterados.

Ao soltar a última tecla direcional, a nave mantém a velocidade existente, incluindo o arrasto já aplicado pelo loop, mas o deslocamento vetorial total fica limitado a **10 pixels**. O orçamento é consumido pela distância realmente percorrida entre as posições antes/depois de cada atualização, após aplicar os limites do mundo; ao esgotá-lo (ou ao ficar bloqueada por um limite), ambas as componentes da velocidade vão a zero. Isso funciona igual em movimento diagonal e não depende de um tempo fixo ou de quantos quadros forem processados. Uma nova tecla direcional cancela o orçamento anterior e retoma a movimentação normal; ao soltar novamente, começa um novo limite de 10 pixels.

## Verificação

O projeto deve compilar com `javac -Xlint:all Main.java`. Os quatro PNGs incluídos foram verificados como imagens RGBA válidas de 64×64. Foram executados testes automatizados para o menu e os controles, cinco fases e velocidades, inércia de até 10 px nas oito direções sob cadências variadas, cores/alpha/escala da HUD, contagem e desenho dos corações, dano por colisão, invulnerabilidade e sua expiração, respawn sem reiniciar a fase, tremida visual sem deslocar entidades, congelamento do GAME OVER, reinício e retorno ao menu. O carregamento dos assets pelo JAR foi testado a partir de outra pasta.
