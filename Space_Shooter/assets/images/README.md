# Assets de imagem do jogo

Os sprites PNG personalizados enviados pelo usuário estão instalados nos caminhos usados pelo jogo:

- `player.png` — nave do jogador, 64×64 px; renderizada a 74×74 px e centralizada na hitbox.
- `enemies/enemy_1.png` — inimigo tipo 1, 64×64 px.
- `enemies/enemy_2.png` — inimigo tipo 2, 64×64 px.
- `enemies/enemy_3.png` — inimigo tipo 3, 64×64 px.

O jogo carrega os arquivos uma vez ao abrir, primeiro a partir do diretório do projeto e depois do classpath. Os PNGs também são empacotados no JAR para funcionarem fora da pasta do projeto. Se um arquivo estiver ausente ou não for 64×64, seu sprite vetorial é usado como fallback. Reinicie o jogo após trocar uma imagem.
