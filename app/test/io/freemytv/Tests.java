package io.freemytv;

import java.util.Calendar;

/**
 * The app's logic that runs without a TV: the search by kind (Ask), the IPTV accounts kept out of the log, the same
 * channel joined across lists, today's games (later, live, finished) and title names. tools/test.sh runs it after the
 * build (CI too). No test library: each check prints what failed and the run ends with 1 if anything did.
 */
public final class Tests {
    private static int run, failed;

    public static void main(String[] args) {
        redact();
        ask();
        channels();
        games();
        names();
        speech();
        System.out.println(run + " checks, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        run++;
        if (!ok) { failed++; System.out.println("FAILED: " + what); }
    }

    private static void same(Object got, Object want, String what) {
        check(want == null ? got == null : want.equals(got), what + " → got “" + got + "”, wanted “" + want + "”");
    }

    // The lists' users and passwords never reach the log or an error message.
    private static void redact() {
        String live = AppLog.redact("player: http://server.example:8080/live/joao/s3nh4/1234.ts failed");
        check(!live.contains("joao") && !live.contains("s3nh4") && live.contains("/live/***/***/1234.ts"), "live address hidden: " + live);
        String movie = AppLog.redact("http://x.example/movie/user1/pass1/99.mp4");
        check(!movie.contains("user1") && !movie.contains("pass1"), "movie address hidden: " + movie);
        String series = AppLog.redact("http://x.example/series/u/p/7.mkv");
        check(!series.contains("/u/p/"), "series address hidden: " + series);
        String api = AppLog.redact("GET http://x.example/player_api.php?username=maria&password=abc123&action=get_live_streams");
        check(!api.contains("maria") && !api.contains("abc123") && api.contains("action=get_live_streams"), "api query hidden: " + api);
        String tmdb = AppLog.redact("https://api.themoviedb.org/3/movie/1?api_key=0123456789abcdef&language=pt-BR");
        check(!tmdb.contains("0123456789abcdef"), "TMDB key hidden: " + tmdb);
        same(AppLog.redact("iptv: 3 lists saved"), "iptv: 3 lists saved", "plain text untouched");
        same(AppLog.redact(null), null, "null stays null");
    }

    // "filmes de suspense" is a kind of title; "guerra civil" is a name.
    private static void ask() {
        Ask a = Ask.understand("filmes de suspense");
        check(a != null && a.genres.contains("suspense") && "movie".equals(a.kind), "filmes de suspense");
        a = Ask.understand("Séries de terror");
        check(a != null && a.genres.contains("terror") && "tv".equals(a.kind), "séries de terror");
        a = Ask.understand("suspense");
        check(a != null && a.genres.contains("suspense") && a.kind == null, "suspense alone");
        a = Ask.understand("comédia dos anos 90");
        check(a != null && a.genres.contains("comedia") && a.fromYear == 1990 && a.toYear == 1999, "comédia dos anos 90");
        a = Ask.understand("me sugere uns filmes de ação brasileiros");
        check(a != null && a.genres.contains("acao") && a.national && "movie".equals(a.kind), "ação brasileiros");
        a = Ask.understand("documentários da Netflix");
        check(a != null && a.genres.contains("documentario") && a.services.contains("Netflix"), "documentários da Netflix");
        a = Ask.understand("lançamentos de ficção científica");
        check(a != null && a.genres.contains("ficcao") && a.recent, "lançamentos de ficção científica");
        a = Ask.understand("filmes de guerra");
        check(a != null && a.genres.contains("guerra"), "filmes de guerra");
        a = Ask.understand("desenhos para crianças");
        check(a != null && a.genres.contains("animacao") && a.genres.contains("infantil"), "desenhos para crianças");
        check(Ask.understand("guerra civil") == null, "guerra civil is a title");
        check(Ask.understand("Duna") == null, "Duna is a title");
        check(Ask.understand("Wagner Moura") == null, "a person is not a genre");
        check(Ask.understand("SporTV") == null, "a channel is not a genre");
        check(Ask.understand("filmes") == null, "\"filmes\" alone asks nothing");
        check(Ask.understand("   ") == null, "empty");
        a = Ask.understand("séries brasileiras");
        check(a != null && a.national && "tv".equals(a.kind), "séries brasileiras");
    }

    // The same channel in several lists (and qualities) is one channel.
    private static void channels() {
        String k = Lineup.keyOf("SporTV FHD");
        same(Lineup.keyOf("SPORTV HD"), k, "SporTV qualities");
        same(Lineup.keyOf("BR: SporTV ²"), k, "SporTV with country and backup mark");
        same(Lineup.keyOf("SporTV 1"), k, "SporTV 1 is SporTV");
        check(!Lineup.keyOf("SporTV 2").equals(k), "SporTV 2 is another channel");
        check(!Lineup.keyOf("ESPN 4").equals(Lineup.keyOf("ESPN")), "ESPN 4 is not ESPN");
        same(Lineup.keyOf("Globo Minas 4K"), Lineup.keyOf("GLOBO MINAS FHD"), "Globo Minas 4K and FHD");
        same(Lineup.norm("Ação & Aventura"), "acao e aventura", "norm takes accents out");
        same(group("QUILOS MORTAIS 01", "OUTROS"), Lineup.H24, "a marathon channel is 24 horas");
        same(group("UFCPASS 03 - 24H", "UFC"), Lineup.H24, "24H in the name");
        same(group("Pesadelo na Cozinha", "CANAIS 24 HORAS"), Lineup.H24, "24 horas in the category");
        same(group("Amazon Prime 01", "OUTROS"), Lineup.STREAMS, "a streaming service's live channel");
        same(group("Disney+ 2", ""), Lineup.STREAMS, "Disney+ is a streaming channel");
        same(group("Disney Channel", ""), Lineup.KIDS, "Disney Channel is still Infantil");
        same(group("SporTV 2", "ESPORTES"), Lineup.SPORTS, "SporTV is Esportes");
        same(group("Globo Minas", "ABERTOS"), Lineup.OPEN, "Globo is Abertos");
        same(group("Telecine Action", ""), Lineup.MOVIES, "Telecine is Canais de filmes");
        same(Lineup.groupFor("canais 24 horas"), Lineup.H24, "voice: canais 24 horas");
    }

    private static String group(String raw, String category) { return Lineup.groupOf(Lineup.keyOf(raw), Lineup.norm(category)); }

    // A game is later, live (from 20 min before until 2 h 45 after its start) or finished.
    private static void games() {
        same(Lineup.startOf("BAH x SAO - 21h30 - FHD"), 21 * 60 + 30, "start 21h30");
        same(Lineup.startOf("FLA x COR 16:00"), 16 * 60, "start 16:00");
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long noon = c.getTimeInMillis();
        Lineup.Channel g = new Lineup.Channel();
        g.game = true;
        g.start = 14 * 60;   // 14:00 today
        same(g.state(noon), Lineup.PRE, "14:00 game at noon: later");
        same(g.state(noon + 110 * 60_000L), Lineup.LIVE, "20 min before: can go in");
        same(g.state(noon + 3 * 3600_000L), Lineup.LIVE, "an hour in: live");
        same(g.state(noon + 5 * 3600_000L), Lineup.ENDED, "3 h after: finished");
        Lineup.Channel ch = new Lineup.Channel();
        same(ch.state(noon), Lineup.ALWAYS, "a channel is always on");
    }

    // Brazilian Portuguese said to the TV.
    private static void speech() {
        same(Speech.clean("quero assistir Duna"), "duna", "command off");
        same(Speech.clean("coloca na Globo"), "globo", "coloca na");
        same(Speech.clean("abre o canal esporte vê"), "sportv", "esporte vê is SporTV");
        same(Speech.clean("esporte vê dois"), "sportv 2", "SporTV 2 said");
        same(Speech.clean("agá bê ó"), "hbo", "HBO spelled");
        same(Speech.clean("esse bê tê"), "sbt", "SBT spelled");
        same(Speech.clean("Duna"), "duna", "a name stays");
        same(Speech.clean("O Poderoso Chefão"), "poderoso chefao", "article off, still found by contains");
        check(Speech.variants("Duna parte dois").contains("duna parte 2"), "numbers in words become digits");
        check(Speech.variants("Rocky três").contains("rocky iii"), "and Roman numerals");
        check(Speech.variants("Uma Noite no Museu").contains("uma noite no museu"), "a leading article stays a word");
        same(Speech.sound("Xuxa"), Speech.sound("Chucha"), "x and ch sound alike");
        same(Speech.sound("Brasil"), Speech.sound("Brasiu"), "a closing l sounds like u");
        same(Speech.sound("Cinema"), Speech.sound("Sinema"), "c before i is s");
        same(Speech.sound("Quero"), Speech.sound("Keru"), "qu is k, a final o is u");
        check(Speech.sound("Duna").length() > 0 && !Speech.sound("Duna").equals(Speech.sound("Bruna")), "Duna and Bruna differ");
    }

    // Titles found by name across lists: the decorations come off.
    private static void names() {
        same(Iptv.normalize("Batman: O Cavaleiro das Trevas (2008) [4K]"), "batman o cavaleiro das trevas", "movie name");
        same(Iptv.normalize("Duna - Parte Dois DUBLADO FHD"), "duna parte dois", "tags out");
        same(Iptv.categoryLabel("✨ •NETFLIX"), "NETFLIX", "category label");
        same(Iptv.categoryKey("•Netflix"), Iptv.categoryKey("NETFLIX"), "same category in two lists");
        same(Iptv.distance("bruna", "duna", 2), 2, "Bruna is 2 letters from Duna");
        same(Iptv.distance("dona", "duna", 2), 1, "Dona is 1 letter from Duna");
        check(Iptv.distance("batman", "duna", 2) > 2, "Batman is far from Duna");
    }
}
