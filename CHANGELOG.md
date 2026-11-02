# Changelog

Tudo o que mudou no Free My TV, da versão mais nova para a mais antiga. O que está na tela da TV é
`<versão> (<código do commit>)`, por exemplo `1.2 (ab12cd3)`; os pacotes da LG e da Samsung têm a versão própria
(`1.2.0` desde o Esportes na web), que só muda quando o `web/` muda.

## 1.2 (2026-11-02)

### Adicionado

- **Esportes nas TVs LG e Samsung.** O atalho **Esportes** da gaveta (↓ na prateleira) abre a lista dos jogos de hoje dos
  mesmos campeonatos do Android (ao vivo em vermelho, os próximos por horário, os encerrados) e, com um time escolhido, os
  3 próximos jogos dele no topo. O time se escolhe em *Ajustes do Início → Time de futebol* (ou na primeira linha da
  própria lista), com as Séries A e B; *Jogos de hoje* liga e desliga. Só lê o ESPN com a lista aberta e guarda a última
  resposta. Os pacotes da LG e da Samsung passam a `1.2.0`. Buscar, a pílula e o protetor do Google não existem na versão
  web (ela não tem IPTV nem protetor próprio).
- **Time de futebol escolhido na TV.** *Ajustes do Início → Time de futebol* abre a lista das Séries A e B do
  Brasileirão, lida do ESPN na hora (acompanha as subidas e descidas de cada ano), com *Nenhum* na primeira linha. O
  que se escolhe na TV vale mais que o `sports_soccer` do `config.properties`. O time da Série B tem jogos, tabela e
  card como o da Série A. O canal da TV de cada jogo só aparece para o time do `config.properties` (o id do Sofascore é
  dele).
- **Jogos de hoje.** Os jogos do dia (no fuso da TV) dos principais campeonatos, ligado por padrão:
  - Brasil: Brasileirão A e B, Copa do Brasil, Libertadores, Sul-Americana, Paulistão, Carioca, Mineiro, Gaúcho e Copa
    do Nordeste. Europa: Champions League, Europa League, Premier League, La Liga, Serie A, Bundesliga, Ligue 1 e Liga
    Portugal. Mais Copa do Mundo e as Eliminatórias Sul-Americanas.
  - Uma aba **Hoje**, a primeira de *Esportes*, com a lista por campeonato, o horário, o placar ao vivo e *Final* (↑ ↓
    rolam).
  - Uma fileira **Jogos de hoje** na página de esportes da home, abaixo dos seus times: até 12 cards, ao vivo primeiro,
    depois os próximos por horário, depois os encerrados. O OK abre o **Onde assistir** com os canais das suas listas.
  - *Ajustes do Início → Jogos de hoje* liga e desliga. Desligado e sem time, o card de Esportes some.
  - Gentil com a rede e com a TV: lê todos os campeonatos a cada 10 minutos e, no meio tempo, só os que têm jogo hoje.
- **Buscar também no modo leve.** A aba *Buscar* procura canais, filmes e séries das suas listas IPTV com os dados que o
  modo leve já tem em memória (o índice de títulos continua desligado, então o custo é pequeno). Sem a chave do TMDB, ela
  pula o catálogo de fora e diz "Nada encontrado" quando não acha. O botão do microfone do controle passou a abrir a busca
  no modo leve (antes abria a lista de filmes e não ouvia nada).
- **Pílula com Buscar.** A pílula de abas, na home e no Assistir, agora é *Início · Buscar · Ao vivo · Filmes · Séries ·
  Ajustes*.
- **Protetor de tela do Google.** `screensaver=google` no `config.properties` mantém o protetor da própria TV (o modo
  Ambiente do Google, com os álbuns do seu Google Fotos escolhidos no app Google Home). O Free My TV não recoloca o dele
  a cada boot, não desliga o app do protetor (`dreamx`) e o liga de novo se estiver desligado. O padrão continua
  `screensaver=freemytv`. O Google fechou a API do Google Fotos para outros apps, então não há como o Free My TV mostrar
  as suas fotos sozinho.
- **Versão na TV com o commit.** O menu principal mostra `Free My TV 1.2 (ab12cd3)`: o código do commit de onde o app foi
  compilado, com um `+` quando há mudanças ainda não commitadas.
- **Chromecast com Google TV (testado no HD, Android 14).** O `CLAUDE.md` explica o caminho pela *Depuração por Wi-Fi*
  (`adb pair`, porta de conexão que muda, e `adb tcpip 5555` para a porta que o app usa por dentro).

### Alterado

- *Ajustes do Início* ficou com 13 linhas (com o dólar do BRL): as linhas ficam mais baixas para a lista e a dica
  caberem nos 1080 px.
- O aviso depois de um boot ou uma troca não diz mais "Desliguei o que faltava" quando o app acabou de ligar um app.
- `CLAUDE.md` e os README (pt e en) refletem tudo acima, inclusive a chave `screensaver` e a pílula com Buscar.

### Notas para quem instala

- Para o botão do microfone do controle abrir a busca do Free My TV, o app precisa ser o assistente da TV
  (`adb shell cmd role add-role-holder android.app.role.ASSISTANT io.freemytv`). Isso tira o Google Assistente do botão e
  o "Ok Google"; para voltar, `... com.google.android.katniss`.
- A busca completa (catálogo do TMDB, *Para você*, *Descobrir*) continua exigindo `lite=off` e a chave do TMDB.

## Antes (já na `main`)

### 2026-11-01

- **Versões Full e Light** (`lite=auto|on|off` no `config.properties`, ou *Assistir → Ajustes → Modo leve* na TV): a
  Light, para boxes de 1 GB, usa só as listas IPTV, sem TMDB, sem índice de títulos e sem mosaico.
- **Pílula de abas no topo** da home (Início, Ao vivo, Filmes, Séries, Ajustes), nas duas versões, com troca entre Início
  e as abas sem recriar a tela.
- **Esportes mais leve** em TVs Android 8 a 11 (cada card e título em repouso guardado como imagem; de ~120 ms para
  ~19 ms por quadro numa UniTV V10).

### 2026-10-30

- **Eventos do dia** (jogos, corridas, PPV das suas listas IPTV), com o fim pela grade ou pela duração do esporte e sem
  bloqueio para entrar antes; grupo **Fórmula 1** com as câmeras dos pilotos.

### 2026-10-29

- **Assistir** (filmes, séries e TV num lugar só), **Ao vivo** com várias contas e grade organizada, **mosaico** de até 4
  canais, **Onde assistir**, **Esportes**, **busca por voz** com o botão do controle (em português do Brasil falado, por
  gênero e com "você quis dizer") e o **reprodutor próprio** (Media3).
- Auditoria geral, voz em português, busca por gênero, player, listas e categorias.
- Traduções das telas novas nos 24 idiomas.

### Do Free My TV original

- A tela inicial sem anúncios, a prateleira com os seus apps, o clima, o protetor de tela com as fotos das sugestões, a
  troca entre *Free My TV* e *Original* e o vigia que volta à tela original depois de 3 travamentos em 5 minutos. Detalhes
  no [README](README.md).
