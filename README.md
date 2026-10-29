<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="">

# Free My TV · edição com Assistir, Ao vivo e Esportes

**Uma tela inicial rápida e sem anúncios para Google TV e Android TV, com um reprodutor próprio para a sua assinatura de TV por IPTV.**

Baseado no [Free My TV](https://github.com/simbasjungles/free-my-tv) (licença MIT). Feito com Claude Code.

</div>

> [!IMPORTANT]
> **Este projeto não traz nenhum canal, filme, série, lista ou servidor.** Ele é só um aplicativo: uma tela inicial
> e um **reprodutor**. Para assistir TV ao vivo, você conecta **a sua própria conta** de um serviço de IPTV que você
> assina e tem o direito de usar (servidor, usuário e senha que o seu provedor te deu). Esses dados ficam **só na sua
> TV**: não vão para este repositório nem para nenhum outro lugar.
>
> Não distribuímos, indicamos nem vendemos conteúdo ou listas. O que você conecta e assiste é responsabilidade sua.
> Use um serviço legalizado.

## O que este fork acrescenta

Tudo do Free My TV original continua aqui: a tela inicial sem anúncios, a prateleira com os seus apps, o clima, a
proteção de tela e o botão que volta tudo ao normal. Além disso:

### Assistir: filmes, séries e TV num lugar só
- Abas **Buscar**, **Para você**, **Ao vivo**, **Filmes**, **Séries**, **Descobrir** e **Ajustes**.
- **Uma busca para tudo:** canais, jogos, filmes, séries e atores, digitando ou **falando**.
- Filmes e séries da sua assinatura aparecem com capa, sinopse e elenco (do [TMDB](https://www.themoviedb.org)), as
  versões (Full HD, 4K, dublado, legendado) juntas num item só.
- **Continuar assistindo** e **Minha lista**, também na tela inicial.
- Nos pôsteres da tela inicial, uma etiqueta diz onde o título está: no seu streaming (Netflix, Prime Video…) ou na
  sua assinatura de IPTV.

### Ao vivo: várias assinaturas, uma grade organizada
- Conecte **mais de uma conta** (cada uma com um nome que você escolhe). O mesmo canal em várias contas, ou em várias
  qualidades, vira **um canal só** com as suas fontes; o app abre pela melhor e, se ela falhar, tenta a próxima sozinho.
- Os canais ficam em grupos que fazem sentido (**Favoritos**, **Jogos de hoje**, **Abertos**, **Esportes**, **Canais de
  filmes**, **Notícias**, **Infantil**…), seja qual for o nome que cada provedor dá às categorias. As categorias
  originais de cada conta continuam lá, recolhidas.
- Cada canal mostra de qual conta ele vem, o que está passando agora e o que vem depois.
- **Segurar OK** num canal: assistir, **escolher a fonte**, favoritar ou pôr no mosaico. A fonte escolhida vira a
  primeira daquele canal.

### Jogos de hoje e "Onde assistir"
- Os jogos do dia separados em **Ao vivo agora**, **Mais tarde** e **Encerrados**, pelo horário de cada um.
- **Onde assistir:** para cada jogo, os canais que transmitem (pela programação do [Sofascore](https://www.sofascore.com))
  encontrados nas suas contas, e os apps oficiais instalados na TV que também passam (Globoplay, Prime Video,
  Disney+, Premiere…).
- Na tela inicial, um cartão de **Esportes** com os próximos jogos do seu time, a tabela, a Fórmula 1 e a NHL
  (opcional, em `config.properties`).

### Mosaico: até 4 canais ao mesmo tempo
- 2, 3 ou 4 quadros, com estilos (lado a lado, imagem na imagem, um grande e os outros menores).
- O som vem do quadro selecionado; um quadro pode ir para o grande ou para a tela cheia.
- **Respeita o limite de telas de cada conta:** o seletor só mostra canais de uma conta que ainda tem tela livre e diz
  qual quadro está usando cada uma. Para trocar, mude o canal desse quadro ou tire ele.

### Busca por voz com o botão do controle
- O botão do microfone abre a busca do próprio app (sem o Gemini) e entende canais, jogos, filmes e séries.

### Reprodutor próprio
- Feito sobre o Media3 (ExoPlayer) do Google: HLS, MPEG-TS, MKV e MP4, áudio e legendas, lembra onde você parou e passa
  para o próximo episódio.

## Controle remoto no reprodutor

| | TV ao vivo | Filmes e séries |
|---|---|---|
| **OK** | lista de canais (segure: fonte, favoritar, mosaico) | pausa |
| **◀ ▶** | canal anterior e próximo (CH+ e CH− também) | volta e avança 10 s (segure para ir mais rápido) |
| **▲** | o que está passando e o que vem depois | mostra o tempo |
| **▼ ou MENU** | fonte, áudio e legendas, favoritar, mosaico, programação | áudio e legendas, recomeçar, próximo episódio |
| **VOLTAR** | fecha o que estiver aberto, depois sai | igual |

## Instalar

O jeito de instalar é o mesmo do projeto original, explicado em detalhes no [README em inglês](README.en.md#install).
Em resumo:

1. **Na TV**, ative o modo desenvolvedor e a **depuração USB** (Configurações → Sistema → Sobre → aperte 7 vezes em
   *Build do Android TV OS*) e anote o IP da TV.
2. **No computador**, com JDK 17 e as ferramentas do Android SDK (build-tools 34, platform android-34) e o `adb`:
   ```bash
   git clone <este repositório> && cd free-my-tv
   ./build.sh          # a primeira vez cria o config.properties e baixa as bibliotecas do reprodutor
   ```
   Preencha a sua cidade em `config.properties` e, se quiser as capas e sinopses, a **sua** chave gratuita do TMDB
   (themoviedb.org → Configurações → API). A chave é sua: não publique esse arquivo.
3. `./build.sh` de novo, depois `adb connect <IP da TV>:5555` e `adb install -r FreeMyTV.apk`.
4. **Na TV:** abra *Free My TV* → *Início* → *Free My TV*.
5. **Sua assinatura de IPTV:** *Assistir* → *Ajustes* → *Adicionar uma lista* (um nome, o servidor, o usuário e a
   senha que o seu provedor te deu, no padrão Xtream Codes) → *Conectar*. Digite na própria TV.

Também dá para pedir a um assistente de IA (como o [Claude Code](https://claude.com/claude-code)) que faça tudo isso:
o arquivo [CLAUDE.md](CLAUDE.md) explica o passo a passo para ele.

### Busca por voz no botão do microfone (opcional)
Para o botão do microfone abrir a busca do app em vez do Gemini, torne o Free My TV o assistente da TV:
```bash
adb shell cmd role add-role-holder android.app.role.ASSISTANT io.freemytv
```
Para voltar ao assistente do Google:
```bash
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss
```

## Privacidade e segurança
- As contas de IPTV (servidor, usuário e senha) ficam só na TV, salvas pelo app. Nada disso está neste código.
- A chave do TMDB fica no seu `config.properties`, que o `.gitignore` deixa fora do Git, assim como a chave de
  assinatura do APK (`*.keystore`).
- Sem root. Nada é desinstalado. O modo *Original* devolve a tela inicial de fábrica.

## Créditos e licença
- Projeto original: [Free My TV](https://github.com/simbasjungles/free-my-tv), de simbasjungles e colaboradores
  (MIT). A tela inicial, o modo *Original*, as versões para LG e Samsung e o site em `docs/` são de lá.
- Este fork: Assistir, Ao vivo com várias contas, Jogos de hoje, Onde assistir, Esportes, mosaico, busca por voz e o
  reprodutor. Feito por Matheus Philipers com o Claude Code.
- Dados de filmes e séries: [TMDB](https://www.themoviedb.org) (este produto usa a API do TMDB, mas não é endossado nem
  certificado por ele). Programação esportiva: [Sofascore](https://www.sofascore.com) e ESPN.
- Licença [MIT](LICENSE).
