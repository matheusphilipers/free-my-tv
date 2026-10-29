package io.freemytv;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a search means when it isn't a name: "filmes de suspense", "séries de terror", "comédia dos anos 90",
 * "ação brasileiro", "documentários da Netflix", "lançamentos de ficção". Said or typed, in everyday Portuguese.
 * understand() returns null when the words are a title (or anything else): then the search looks for names as before.
 */
final class Ask {
    final Set<String> genres = new LinkedHashSet<>();   // Movies.GENRES keys
    String kind;                                         // "movie", "tv" or null (both)
    int fromYear, toYear;
    boolean national, best, recent;
    final List<String> services = new ArrayList<>();     // Movies.SERVICES names

    // Words (accents and case taken out) and the genre each one means. Two words first, so "ficcao cientifica" wins.
    private static final String[][] GENRE_WORDS = {
        {"ficcao cientifica", "ficcao"}, {"sci fi", "ficcao"}, {"reality show", "reality"}, {"reality shows", "reality"},
        {"segunda guerra", "guerra"}, {"de epoca", "historia"},
        {"suspense", "suspense"}, {"suspenses", "suspense"}, {"thriller", "suspense"}, {"thrillers", "suspense"},
        {"acao", "acao"}, {"action", "acao"}, {"aventura", "aventura"}, {"aventuras", "aventura"},
        {"animacao", "animacao"}, {"animacoes", "animacao"}, {"animado", "animacao"}, {"animados", "animacao"},
        {"desenho", "animacao"}, {"desenhos", "animacao"}, {"anime", "animacao"}, {"animes", "animacao"},
        {"comedia", "comedia"}, {"comedias", "comedia"}, {"engracado", "comedia"}, {"engracados", "comedia"}, {"humor", "comedia"},
        {"crime", "crime"}, {"crimes", "crime"}, {"policial", "crime"}, {"policiais", "crime"}, {"mafia", "crime"}, {"gangster", "crime"},
        {"documentario", "documentario"}, {"documentarios", "documentario"},
        {"drama", "drama"}, {"dramas", "drama"},
        {"familia", "familia"}, {"familiar", "familia"}, {"familiares", "familia"},
        {"fantasia", "fantasia"}, {"magia", "fantasia"},
        {"terror", "terror"}, {"horror", "terror"}, {"medo", "terror"}, {"assustador", "terror"}, {"assustadores", "terror"},
        {"misterio", "misterio"}, {"misterios", "misterio"}, {"investigacao", "misterio"}, {"detetive", "misterio"},
        {"romance", "romance"}, {"romances", "romance"}, {"romantico", "romance"}, {"romanticos", "romance"}, {"romantica", "romance"},
        {"ficcao", "ficcao"}, {"espacial", "ficcao"}, {"futurista", "ficcao"},
        {"guerra", "guerra"}, {"guerras", "guerra"}, {"militar", "guerra"}, {"militares", "guerra"},
        {"historico", "historia"}, {"historicos", "historia"}, {"historica", "historia"},
        {"reality", "reality"}, {"realities", "reality"},
        {"infantil", "infantil"}, {"infantis", "infantil"}, {"crianca", "infantil"}, {"criancas", "infantil"}, {"kids", "infantil"},
    };

    // Words that only connect the others ("me mostra uns filmes de...").
    private static final Set<String> FILLER = new java.util.HashSet<>(java.util.Arrays.asList(
            "de", "do", "da", "dos", "das", "com", "um", "uns", "uma", "umas", "algum", "alguns", "alguma", "algumas", "quero", "queria",
            "ver", "assistir", "me", "mostra", "mostre", "mostrar", "sugira", "sugere", "sugerir", "sugestao", "sugestoes", "indica",
            "indicacao", "indicacoes", "bom", "bons", "boa", "boas", "legal", "legais", "pra", "para", "hoje", "e", "ou", "o", "a",
            "os", "as", "tipo", "genero", "categoria", "que", "tem", "ter", "na", "no", "nas", "nos", "em", "mais", "algo", "coisa",
            "coisas", "procura", "procurar", "busca", "buscar", "achar", "acha", "quais", "qual", "por", "favor", "agora", "anos",
            "decada", "ai", "aqui", "ate"));

    private Ask() {}

    /** The meaning of the words, or null when they aren't a kind of movie or show (a title, a name, a channel). */
    static Ask understand(String text) {
        String n = " " + Lineup.norm(text) + " ";
        if (n.trim().isEmpty()) return null;
        Ask a = new Ask();
        int year = Calendar.getInstance().get(Calendar.YEAR);
        // Two-word genres first, then one word at a time.
        for (String[] g : GENRE_WORDS) {
            if (!g[0].contains(" ")) continue;
            String w = " " + g[0] + " ";
            if (n.contains(w)) { a.genres.add(g[1]); n = n.replace(w, " "); }
        }
        for (Object[] s : Movies.SERVICES) {
            String name = Lineup.norm((String) s[0]);
            String w = " " + name + " ";
            if (n.contains(w)) { a.services.add((String) s[0]); n = n.replace(w, " "); }
        }
        if (n.contains(" hbo ")) { a.services.add("Max"); n = n.replace(" hbo ", " "); }
        if (n.contains(" prime ")) { a.services.add("Prime Video"); n = n.replace(" prime ", " "); }
        if (n.contains(" disney ")) { a.services.add("Disney+"); n = n.replace(" disney ", " "); }
        boolean anything = false;
        List<String> left = new ArrayList<>();
        for (String w : n.trim().split(" +")) {
            if (w.isEmpty() || FILLER.contains(w)) continue;
            String genre = null;
            for (String[] g : GENRE_WORDS) if (g[0].equals(w)) { genre = g[1]; break; }
            if (genre != null) { a.genres.add(genre); continue; }
            switch (w) {
                case "filme": case "filmes": case "longa": case "longas": a.kind = "movie"; anything = true; continue;
                case "serie": case "series": case "seriado": case "seriados": a.kind = "tv"; anything = true; continue;
                case "brasileiro": case "brasileiros": case "brasileira": case "brasileiras": case "nacional": case "nacionais":
                    a.national = true; continue;
                case "melhores": case "melhor": case "premiados": case "premiado": case "top": case "avaliados": case "avaliado":
                case "aclamados": case "classicos": case "classico": a.best = true; if (w.startsWith("classic")) a.toYear = 1995; continue;
                case "bem": continue;
                case "novo": case "novos": case "nova": case "novas": case "lancamento": case "lancamentos": case "recente":
                case "recentes": case "novidade": case "novidades": case "estreias": case "estreia":
                    a.recent = true; a.fromYear = year - 2; continue;
                case "antigo": case "antigos": case "antiga": case "antigas": case "velhos": a.toYear = 1995; continue;
                default:
            }
            // A decade: "80", "90", "oitenta", "noventa", "2000", "2010".
            int decade = decade(w);
            if (decade > 0) { a.fromYear = decade; a.toYear = decade + 9; continue; }
            left.add(w);
        }
        // Only when every word was understood: "guerra civil" is a title (civil isn't a word above), "guerra" is a genre.
        if (!left.isEmpty()) return null;
        if (a.genres.isEmpty() && !(anything && (a.national || a.best || a.recent || a.fromYear > 0 || !a.services.isEmpty()))) return null;
        return a;
    }

    private static int decade(String w) {
        switch (w) {
            case "50": case "cinquenta": return 1950;
            case "60": case "sessenta": return 1960;
            case "70": case "setenta": return 1970;
            case "80": case "oitenta": return 1980;
            case "90": case "noventa": return 1990;
            case "2000": case "dois mil": return 2000;
            case "2010": return 2010;
            case "2020": return 2020;
            default: return 0;
        }
    }

    /** "Suspense", "Comédia e romance", "Filmes brasileiros": the title of the results. */
    String label() {
        StringBuilder b = new StringBuilder();
        for (String g : genres) b.append(b.length() == 0 ? "" : " e ").append(b.length() == 0 ? Movies.genreName(g) : Movies.genreName(g).toLowerCase(Ui.locale()));
        if (b.length() == 0) b.append("tv".equals(kind) ? "Séries" : "Filmes");
        if (national) b.append(" brasileiros");
        if (fromYear > 0 && toYear == fromYear + 9) b.append(fromYear >= 2000 ? " dos anos " + fromYear : " dos anos " + (fromYear % 100));
        else if (recent) b.append(" recentes");
        else if (toYear > 0) b.append(" clássicos");
        if (!services.isEmpty()) b.append(" · ").append(android.text.TextUtils.join(", ", services));
        return b.toString();
    }

    /** The kinds to look in: the one said, or both (a genre that has no shows on TMDB counts movies only). */
    List<String> kinds() {
        List<String> out = new ArrayList<>();
        for (String k : kind == null ? new String[] {"movie", "tv"} : new String[] {kind}) {
            boolean any = genres.isEmpty();
            for (String g : genres) if (!Movies.genreId(g, k).isEmpty()) any = true;
            if (any) out.add(k);
        }
        return out;
    }

    /** A discover filter for this, with your settings (minimum rating; the genres you avoid are left out by discover). */
    Movies.Filter filter(int minRating) {
        Movies.Filter f = new Movies.Filter();
        f.genres.addAll(genres);
        f.fromYear = fromYear;
        f.toYear = toYear;
        f.national = national;
        f.minRating = minRating;
        f.minVotes = national ? 30 : 150;
        f.services.addAll(services);
        return f;
    }
}
