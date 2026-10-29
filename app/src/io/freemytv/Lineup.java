package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * All your lists' live channels as one lineup: the same channel in several lists (or posted as FHD, HD, SD, 4K, "²"
 * backups) is one channel with its sources, best first; each channel goes into one of our groups (Abertos, Esportes…),
 * the same whatever each provider calls its categories. Built off the main thread (get() can take a moment the first time).
 */
final class Lineup {
    static final String GAMES = "Jogos de hoje", OPEN = "Abertos", SPORTS = "Esportes", MOVIES = "Canais de filmes", SOAPS = "Novelas e realities",
            NEWS = "Notícias", KIDS = "Infantil", DOCS = "Documentários", SHOWS = "Variedades", MUSIC = "Música", FAITH = "Religiosos", OTHER = "Outros",
            H24 = "Canais 24 horas", STREAMS = "Streamings ao vivo", ADULT = "Adultos";
    static final String[] GROUPS = {GAMES, OPEN, SPORTS, STREAMS, MOVIES, H24, SOAPS, NEWS, KIDS, DOCS, SHOWS, MUSIC, FAITH, ADULT, OTHER};

    /** One channel, with its sources (lists and qualities), best first. */
    static final int PRE = 0, LIVE = 1, ENDED = 2, ALWAYS = 3;
    /** A game is on from its start until about 2 h 45 later (extra time, the post-game); before that it hasn't started. */
    static final long GAME_LENGTH = 165 * 60_000L, OPENS_BEFORE = 20 * 60_000L;

    static final class Channel {
        String key, name = "", group = OTHER, icon = "";
        boolean game;
        int start = -1;   // a game's start, in minutes after midnight (from "21h30" in its name); -1: none
        int day = -1, month = -1;   // its date, when the name has one ("28/10 21:30 …")
        final List<Iptv.Version> versions = new ArrayList<>(2);

        /** Today's start of the game (epoch ms), or 0. */
        long startsAt() {
            if (start < 0) return 0;
            java.util.Calendar c = java.util.Calendar.getInstance();
            if (day > 0 && month > 0) {
                int now = c.get(java.util.Calendar.MONTH) + 1;
                if (month == 1 && now == 12) c.add(java.util.Calendar.YEAR, 1);         // a January game seen in December
                else if (month == 12 && now == 1) c.add(java.util.Calendar.YEAR, -1);
                c.set(java.util.Calendar.MONTH, month - 1);
                c.set(java.util.Calendar.DAY_OF_MONTH, day);
            }
            c.set(java.util.Calendar.HOUR_OF_DAY, start / 60);
            c.set(java.util.Calendar.MINUTE, start % 60);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }

        /** PRE (later today), LIVE, ENDED, or ALWAYS for a channel that isn't a game. */
        int state(long now) {
            long at = startsAt();
            if (at == 0) return ALWAYS;
            if (now < at - OPENS_BEFORE) return PRE;
            return now < at + GAME_LENGTH ? LIVE : ENDED;
        }

        /** "21:30" */
        String time() { return start < 0 ? "" : String.format(Locale.US, "%02d:%02d", start / 60, start % 60); }

        /** For a game: "AO VIVO", "Começa às 21:30", "Encerrado"; empty for a channel. */
        String status(long now) {
            long at = startsAt();
            boolean today = at > 0 && android.text.format.DateUtils.isToday(at);
            switch (state(now)) {
                case PRE: return today ? "Começa às " + time() : "Dia " + String.format(Locale.US, "%02d/%02d", day, month) + " às " + time();
                case LIVE: return now < at ? "Começa às " + time() + " · já dá para entrar" : "AO VIVO · começou às " + time();
                case ENDED: return "Encerrado";
                default: return "";
            }
        }

        Iptv.Version best() { return versions.get(0); }

        /** The lists it's in, in order ("Lista 1, Lista 2"). */
        String lists() {
            List<String> names = new ArrayList<>();
            for (Iptv.Version v : versions) if (!names.contains(v.source.name)) names.add(v.source.name);
            return android.text.TextUtils.join(", ", names);
        }

        int listCount() {
            Set<Integer> ids = new HashSet<>();
            for (Iptv.Version v : versions) ids.add(v.source.id);
            return ids.size();
        }

        /** "Full HD · Lista 1" or "Full HD · 3 listas". */
        String meta() {
            String st = status(System.currentTimeMillis());
            if (!st.isEmpty()) return st + " · " + (listCount() > 1 ? listCount() + " listas" : best().source.name);
            String q = quality(best());
            int n = listCount();
            String where = n > 1 ? n + " listas" : best().source.name;
            return (q.isEmpty() ? "" : q + " · ") + where;
        }
    }

    private static Lineup cached;
    private static int cachedGen = -1;

    /** The lineup of your lists as they are now (rebuilt after a list changes). */
    /** The lineup (built off the main thread; waits for the lists read at start, so it's built once, not once per list). */
    static Lineup get(Context c) {
        Iptv.get(c).awaitLoaded();
        return build(c);
    }

    private static synchronized Lineup build(Context c) {
        Iptv iptv = Iptv.get(c);
        int gen = iptv.generation();
        if (cached == null || cachedGen != gen) {
            long t0 = System.currentTimeMillis();
            cached = new Lineup(c, iptv.allLive());
            cachedGen = gen;
            AppLog.w("lineup: " + cached.all.size() + " channels in " + (System.currentTimeMillis() - t0) + " ms");
        }
        return cached;
    }

    /** The cached lineup if it's up to date, without building it (null otherwise): for the UI thread. */
    static synchronized Lineup ready(Context c) {
        return cached != null && cachedGen == Iptv.get(c).generation() ? cached : null;
    }

    final List<Channel> all = new ArrayList<>();
    private final Map<String, Channel> byKey = new HashMap<>();
    private final Map<String, List<Channel>> groups = new LinkedHashMap<>();
    private final Map<Iptv.Version, Channel> byVersion = new IdentityHashMap<>();
    private final SharedPreferences prefs;

    private Lineup(Context c, List<Iptv.Version> live) {
        prefs = c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        Map<String, String> cats = new HashMap<>();   // each category's normalized name, once
        for (Iptv.Version v : live) {
            String cat = cats.get(v.category);
            if (cat == null) cats.put(v.category, cat = norm(v.category));
            boolean game = isGame(v, cat);
            String k = keyOf(v.raw);
            if (k.isEmpty()) continue;
            String key = (game ? "game:" : "") + k;
            Channel ch = byKey.get(key);
            if (ch == null) {
                ch = new Channel();
                ch.key = key;
                ch.game = game;
                ch.group = game ? GAMES : groupOf(k, cat);
                byKey.put(key, ch);
                all.add(ch);
            } else if (OTHER.equals(ch.group) && !game) {
                ch.group = groupOf(k, cat);   // another list's category may tell more
            }
            ch.versions.add(v);
            byVersion.put(v, ch);
        }
        for (String g : GROUPS) groups.put(g, new ArrayList<>());
        for (Channel ch : all) {
            sortVersions(ch);
            ch.name = displayName(ch.best().raw, ch.game);
            if (ch.game) {
                java.util.regex.Matcher d = DATE.matcher(ch.best().raw);
                if (d.find()) { ch.day = Integer.parseInt(d.group(1)); ch.month = Integer.parseInt(d.group(2)); }
                ch.start = startOf(DATE.matcher(ch.best().raw).replaceFirst(" "));
                ch.name = DATE.matcher(START.matcher(ch.name).replaceFirst(" ")).replaceFirst(" ").replaceAll("^[\\s\\-|]+|[\\s\\-|]+$", "")
                        .replaceAll("\\s+-\\s+-\\s+", " - ").replaceAll("\\s{2,}", " ").trim();
            }
            for (Iptv.Version v : ch.versions) if (!v.icon.isEmpty()) { ch.icon = v.icon; break; }
            groups.get(ch.group).add(ch);
        }
        migrateFavourites();
    }

    List<Channel> group(String name) {
        List<Channel> l = groups.get(name);
        if (l == null) return Collections.emptyList();
        if (!GAMES.equals(name)) return l;
        // Today's games: on now first, then the next ones by time, the finished ones last.
        long now = System.currentTimeMillis();
        List<Channel> out = new ArrayList<>(l);
        Collections.sort(out, (a, b) -> {
            int sa = rank(a.state(now)), sb = rank(b.state(now));
            return sa != sb ? Integer.compare(sa, sb) : Integer.compare(a.start, b.start);
        });
        return out;
    }

    /**
     * Shows why a game can't be watched now (it starts later, or it's over) and returns true; false: go ahead and play.
     */
    static boolean notNow(android.content.Context c, Channel ch) {
        int st = ch.state(System.currentTimeMillis());
        if (st == PRE) {
            android.widget.Toast.makeText(c, "Esse jogo começa às " + ch.time() + ". O canal costuma abrir uns 20 minutos antes.", android.widget.Toast.LENGTH_LONG).show();
            return true;
        }
        if (st == ENDED) {
            android.widget.Toast.makeText(c, "Esse jogo já terminou (começou às " + ch.time() + ").", android.widget.Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    private static int rank(int state) { return state == LIVE ? 0 : state == PRE ? 1 : state == ALWAYS ? 2 : 3; }

    private static final Pattern DATE = Pattern.compile("(?<![0-9])([0-3]?[0-9])/([01]?[0-9])(?![0-9/])");
    private static final Pattern START = Pattern.compile("(?<![0-9])([01]?[0-9]|2[0-3])\\s?[hH:]\\s?([0-5][0-9])?(?![0-9])");

    /** "CAM x CRU - 19h30 - (Globo)" → 19*60+30; -1 when the name has no time. */
    static int startOf(String raw) {
        java.util.regex.Matcher m = START.matcher(raw);
        if (!m.find()) return -1;
        int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return h * 60 + min;
    }

    /** Our groups that have channels, in order. */
    List<String> groupNames() {
        List<String> out = new ArrayList<>();
        for (String g : GROUPS) if (!group(g).isEmpty() && (!ADULT.equals(g) || adult())) out.add(g);
        return out;
    }

    /** Adult channels show only when turned on in Ajustes (off at first: a TV the whole family uses). */
    boolean adult() { return prefs.getBoolean("adult", false); }

    static void setAdult(Context c, boolean on) { c.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().putBoolean("adult", on).apply(); }

    Channel channelOf(Iptv.Version v) { return byVersion.get(v); }

    Channel find(String key) { return byKey.get(key); }

    /** A provider category's channels, as lineup channels (each once). */
    List<Channel> of(List<Iptv.Version> versions) {
        List<Channel> out = new ArrayList<>();
        Set<Channel> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Iptv.Version v : versions) {
            Channel ch = byVersion.get(v);
            if (ch != null && seen.add(ch)) out.add(ch);
        }
        return out;
    }

    /** Channels whose name has the words (names that start with them first). */
    List<Channel> search(String query, int max) {
        String q = norm(query);
        List<Channel> starts = new ArrayList<>(), rest = new ArrayList<>();
        if (q.length() < 2) return starts;
        boolean adult = adult();
        for (Channel ch : all) {
            if (!adult && ADULT.equals(ch.group)) continue;
            String n = norm(ch.name);
            if (n.startsWith(q)) starts.add(ch);
            else if ((" " + n).contains(" " + q)) rest.add(ch);
        }
        Collections.sort(starts, (a, b) -> Integer.compare(a.name.length(), b.name.length()));   // "SporTV" before "SporTV 2"
        starts.addAll(rest);
        return starts.size() > max ? new ArrayList<>(starts.subList(0, max)) : starts;
    }

    // MARK: - What a search means

    /** "jogos de hoje", "esporte", "desenhos", "jornal"… → our group; null when the words aren't a group's name. */
    static String groupFor(String query) {
        String full = norm(query);
        String q = (" " + full + " ").replace(" canais de ", " ").replace(" canal de ", " ").replace(" canais ", " ").replace(" canal ", " ").trim();
        if (full.isEmpty()) return null;
        String[][] map = {
                {GAMES, "jogos de hoje", "jogo de hoje", "jogos do dia", "jogo do dia", "jogos hoje", "jogos", "jogos ao vivo", "futebol hoje", "partidas de hoje"},
                {OPEN, "abertos", "aberto", "abertas", "aberta", "tv aberta", "canais abertos", "regionais"},
                {SPORTS, "esportes", "esporte", "esportivos", "esportivo", "futebol"},
                {MOVIES, "canais de filmes", "canal de filmes", "filmes ao vivo"},
                {SOAPS, "novelas", "novela", "realities", "reality", "reality show"},
                {NEWS, "noticias", "noticia", "jornal", "jornais", "telejornal"},
                {KIDS, "infantil", "infantis", "desenhos", "desenho", "criancas", "crianca", "kids", "desenho animado"},
                {DOCS, "documentarios", "documentario", "documentarios ao vivo"},
                {SHOWS, "variedades"},
                {H24, "24 horas", "24h", "canais 24 horas", "24 horas por dia", "maratona", "maratonas"},
                {STREAMS, "streamings ao vivo", "streaming ao vivo", "streamings", "pay per view", "ppv"},
                {MUSIC, "musica", "musicas", "clipes"},
                {FAITH, "religiosos", "religioso", "gospel", "catolicos", "evangelicos"},
        };
        for (String[] g : map) for (int i = 1; i < g.length; i++) if (full.equals(g[i]) || (g[0] != MOVIES && q.equals(g[i]))) return g[0];
        return null;
    }

    // MARK: - Where to watch a game

    /** A channel that may show a game, and why ("Passa no SporTV", "Jogo do dia", "Sua última escolha"). */
    static final class Option {
        final Channel ch;
        final String why;
        Option(Channel ch, String why) { this.ch = ch; this.why = why; }
    }

    private static final Set<String> COMMON = new HashSet<>(java.util.Arrays.asList("clube", "club", "esporte", "esportes", "futebol",
            "sport", "sports", "regatas", "associacao", "fc", "ec", "sc", "ac", "de", "da", "do", "dos", "das", "e"));

    /**
     * The channels of your lists for a game: the one you chose last time for it, today's game entries naming a team, then
     * the broadcasters' channels ("SporTV" → SporTV; "Globo" → the Globo channels). `memory` names the choice to remember
     * ("soccer", "f1"…).
     */
    List<Option> watchOptions(List<String> broadcasters, List<String> teams, List<String> extraNames, String memory) {
        List<Option> out = new ArrayList<>();
        Set<Channel> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Channel last = find(prefs.getString("watch." + memory, ""));
        if (last != null) { out.add(new Option(last, "Sua última escolha")); seen.add(last); }
        // Today's game entries ("CAM x CRU - 21h30 (Globo)"), naming both teams.
        for (Channel ch : gameEntries(teams)) if (seen.add(ch)) out.add(new Option(ch, "Jogo do dia"));
        // The broadcasters' channels.
        List<String> names = new ArrayList<>(broadcasters);
        names.addAll(extraNames);
        for (String b : names) {
            String kb = keyOf(b);
            if (kb.isEmpty()) continue;
            List<Channel> exact = new ArrayList<>(), numbered = new ArrayList<>();
            for (Channel ch : all) {
                if (ch.game) continue;
                String n = ch.key;
                if (n.equals(kb)) exact.add(ch);
                else if (n.startsWith(kb + " ") && (!kb.equals("globo") || OPEN.equals(ch.group))) numbered.add(ch);
            }
            Collections.sort(numbered, (x, y) -> Integer.compare(y.listCount(), x.listCount()));
            List<Channel> pick = new ArrayList<>(exact);
            for (Channel ch : numbered) if (pick.size() < (kb.equals("globo") ? 6 : 4)) pick.add(ch);
            for (Channel ch : pick) if (seen.add(ch)) out.add(new Option(ch, broadcasters.contains(b) ? "Passa no " + b : "Costuma passar no " + b));
        }
        return out;
    }

    /** The broadcasters today's game entries name for a game (both teams, start within 40 min): "Globo", "Premiere". */
    List<String> broadcastersFor(List<String> teams, long at) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(at);
        int minute = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        List<String> out = new ArrayList<>();
        for (Channel ch : gameEntries(teams)) {
            if (ch.start >= 0 && Math.abs(ch.start - minute) > 40) continue;
            java.util.regex.Matcher m = Pattern.compile("\\(([^)]+)\\)").matcher(ch.best().raw);
            while (m.find()) {
                String b = m.group(1).replaceAll("(?i)\\b(FHD|HD|SD|4K|UHD)\\b", "").replaceAll("\\s+\\d+$", "").trim();
                if (!b.isEmpty() && !out.contains(b)) out.add(b);
            }
        }
        return out;
    }

    /**
     * Today's game entries for a game: `teams` is {home name, away name, home code, away code, extra names…}; an entry
     * matches when it names both teams ("CAM x CRU": codes or the first letters of the names).
     */
    List<Channel> gameEntries(List<String> teams) {
        List<Channel> out = new ArrayList<>();
        if (teams.size() < 2) return out;
        Set<String> home = codes(teams.get(0)), away = codes(teams.get(1));
        if (teams.size() > 3) { home.addAll(codes(teams.get(2))); away.addAll(codes(teams.get(3))); }
        for (Channel ch : groups.get(GAMES)) {
            String n = " " + norm(ch.name) + " ";
            if (any(n, home) && any(n, away)) out.add(ch);
        }
        return out;
    }

    private static boolean any(String n, Set<String> words) {
        for (String w : words) if (n.contains(" " + w + " ")) return true;
        return false;
    }

    /** The ways a team is written in game entries: its code, name words and their first three letters ("cruzeiro", "cru"). */
    static Set<String> codes(String team) {
        Set<String> out = new HashSet<>();
        String n = norm(team);
        if (n.isEmpty()) return out;
        if (n.length() <= 4 && !n.contains(" ")) out.add(n);
        if (n.contains("atletico") && n.contains("mg")) out.add("cam");
        if (n.contains("athletico") || (n.contains("atletico") && n.contains("pr"))) out.add("cap");
        if (n.contains("bragantino")) out.add("rbb");
        if (n.contains("sao paulo")) out.add("sao");
        boolean first = true;
        for (String w : n.split(" ")) {
            if (w.length() < 4 || COMMON.contains(w)) continue;
            out.add(w);
            if (first) { out.add(w.substring(0, 3)); first = false; }
        }
        return out;
    }

    void rememberWatch(String memory, Channel ch) { prefs.edit().putString("watch." + memory, ch.key).apply(); }

    // MARK: - Favourites (by channel, so they hold whichever list it plays from)

    boolean favourite(Channel ch) { return prefs.getStringSet("favch", Collections.emptySet()).contains(ch.key); }

    void toggleFavourite(Channel ch) {
        Set<String> fav = new HashSet<>(prefs.getStringSet("favch", Collections.emptySet()));
        if (!fav.remove(ch.key)) fav.add(ch.key);
        prefs.edit().putStringSet("favch", fav).apply();
    }

    List<Channel> favourites() {
        Set<String> fav = prefs.getStringSet("favch", Collections.emptySet());
        List<Channel> out = new ArrayList<>();
        for (Channel ch : all) if (fav.contains(ch.key)) out.add(ch);
        return out;
    }

    /** Favourites saved before the lineup were "list:stream id": they become channel keys once. */
    private void migrateFavourites() {
        if (prefs.contains("favch")) return;
        Set<String> old = prefs.getStringSet("fav", Collections.emptySet());
        Set<String> keys = new HashSet<>();
        for (Channel ch : all) for (Iptv.Version v : ch.versions) if (old.contains(v.source.id + ":" + v.id)) keys.add(ch.key);
        prefs.edit().putStringSet("favch", keys).apply();
    }

    // MARK: - Sources: the best first, and the one you last chose

    /** Remembers the source that played (or that you chose), so it comes first next time. */
    static void remember(Context c, String key, String pick) {
        if (key == null || key.isEmpty() || pick == null || pick.isEmpty()) return;
        c.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().putString("pick." + key, pick).apply();
    }

    static String pickOf(Iptv.Version v) { return v.source.id + ":" + v.id; }

    private void sortVersions(Channel ch) {
        String pick = prefs.getString("pick." + ch.key, "");
        boolean uhd = prefs.getBoolean("uhd", false);
        Map<Iptv.Version, Integer> score = new IdentityHashMap<>();
        for (Iptv.Version v : ch.versions) {
            String q = quality(v);
            int s = q.equals("4K") ? (uhd ? 40 : 22) : q.equals("Full HD") ? 30 : q.equals("HD") ? 20 : q.equals("SD") ? 8 : 15;
            if (v.raw.contains("²") || v.raw.contains("³")) s -= 3;   // the provider's backups
            if (!v.source.error.isEmpty()) s -= 40;
            if (pick.equals(pickOf(v))) s += 100;
            score.put(v, s);
        }
        Collections.sort(ch.versions, (a, b) -> Integer.compare(score.get(b), score.get(a)));
    }

    /** The channel as the player takes it: title, key, its address, and every source to fall back on or choose. */
    static JSONObject toJson(Channel ch) {
        JSONObject o = new JSONObject();
        try {
            Iptv.Version b = ch.best();
            o.put("title", ch.name).put("key", ch.key).put("icon", ch.icon).put("url", Iptv.liveUrl(b, true)).put("ts", Iptv.liveUrl(b, false));
            JSONArray alts = new JSONArray();
            Map<String, Integer> seen = new HashMap<>();
            for (Iptv.Version v : ch.versions) {
                String q = quality(v);
                String label = v.source.name + (q.isEmpty() ? "" : " · " + q);
                int n = seen.containsKey(label) ? seen.get(label) + 1 : 1;   // the same list and quality twice: number them
                seen.put(label, n);
                alts.put(new JSONObject().put("url", Iptv.liveUrl(v, true)).put("ts", Iptv.liveUrl(v, false))
                        .put("label", label + (n > 1 ? " · opção " + n : "")).put("pick", pickOf(v)));
            }
            o.put("alts", alts);
        } catch (Exception ignored) {}
        return o;
    }

    static JSONArray toJson(List<Channel> list) {
        JSONArray a = new JSONArray();
        for (Channel ch : list) a.put(toJson(ch));
        return a;
    }

    // MARK: - Names and groups

    private static final Pattern UHD = Pattern.compile("2160|\\b4K\\b|UHD"), FHD = Pattern.compile("1080|\\bFHD\\b|FULL ?HD"),
            HD = Pattern.compile("720|\\bHD\\b"), SD = Pattern.compile("480|\\bSD\\b"),
            GAME = Pattern.compile("(?i)(^|\\s)[\\p{L}.]{2,}(\\s+[\\p{L}.]+)?\\s+x\\s+[\\p{L}.]{2,}"),
            TIME = Pattern.compile("\\b\\d{1,2}\\s?[h:]\\s?\\d{0,2}\\b"),
            PREFIX = Pattern.compile("^\\s*\\|?\\s*(BR|PT|US|UK)\\s*[|:\\-]\\s*", Pattern.CASE_INSENSITIVE),
            TAGS = Pattern.compile("(?i)(\\b(4K|UHD|FHD|FULL ?HD|HD|SD|H\\.?26[45]|HEVC|HDR|1080P?|720P?|60 ?FPS|ALT|BACKUP)\\b|[²³¹•|])"),
            MARKS = Pattern.compile("\\p{M}"), NOT_WORD = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> NOISE = new HashSet<>(java.util.Arrays.asList("4k", "uhd", "fhd", "hd", "sd", "fullhd", "h265", "h264",
            "hevc", "hdr", "1080p", "720p", "1080", "720", "60fps", "fps", "alt", "backup", "br"));

    static String quality(Iptv.Version v) {
        String up = v.raw.toUpperCase(Locale.ROOT);
        return UHD.matcher(up).find() ? "4K" : FHD.matcher(up).find() ? "Full HD" : HD.matcher(up).find() ? "HD" : SD.matcher(up).find() ? "SD" : "";
    }

    static String norm(String s) {
        if (s == null) return "";
        String t = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
        return NOT_WORD.matcher(t.replace("&", " e ").replace("+", " plus ")).replaceAll(" ").trim();
    }

    /** "BR: ESPN 1 • FHD ²" → "espn 1": what's left once the quality, the backups and the country are taken out. */
    static String keyOf(String raw) {
        String t = norm(PREFIX.matcher(raw).replaceFirst(""));
        StringBuilder b = new StringBuilder();
        for (String w : t.split(" ")) {
            if (w.isEmpty() || NOISE.contains(w) || (w.equals("full") && t.contains("full hd"))) continue;
            b.append(b.length() == 0 ? "" : " ").append(w);
        }
        String k = b.toString();
        return k.endsWith(" 1") && k.length() > 2 ? k.substring(0, k.length() - 2) : k;   // "SporTV 1" is SporTV
    }

    // Compiled once: this runs for every channel of every list when the lineup is built.
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\(\\s*\\)|\\[\\s*\\]"), DASHES = Pattern.compile("\\s*-\\s*(-\\s*)+"),
            SPACES = Pattern.compile("\\s+"), EDGES = Pattern.compile("^[\\s\\-]+|[\\s\\-]+$");

    static String displayName(String raw, boolean game) {
        String s = TAGS.matcher(PREFIX.matcher(raw).replaceFirst("")).replaceAll(" ");
        s = SPACES.matcher(DASHES.matcher(EMPTY_BRACKETS.matcher(s).replaceAll(" ")).replaceAll(" - ")).replaceAll(" ");
        s = EDGES.matcher(s).replaceAll("");
        return s.isEmpty() ? raw.trim() : s;
    }

    static boolean isGame(Iptv.Version v) { return isGame(v, norm(v.category)); }

    private static boolean isGame(Iptv.Version v, String cat) {
        // Quick no: a game's name has " x " (most channels don't, and the pattern below is slow).
        if (v.raw.indexOf(" x ") < 0 && v.raw.indexOf(" X ") < 0) return false;
        boolean matchup = GAME.matcher(v.raw).find();
        return matchup && (cat.contains("jogo") || cat.contains("futebol") || cat.contains("ppv") || cat.contains("evento") || TIME.matcher(v.raw).find());
    }

    private static boolean has(String hay, String... words) {
        for (String w : words) if (hay.contains(" " + w + " ") || hay.startsWith(w + " ") || hay.equals(w)) return true;
        return false;
    }

    private static boolean starts(String hay, String... words) {
        for (String w : words) if (hay.startsWith(w + " ") || hay.equals(w) || hay.contains(" " + w)) return true;
        return false;
    }

    /** Our group for a channel, from its name first, then the provider's category. */
    // A show on a loop ("Quilos Mortais 01", "UFCPASS 03 - 24H", "Pesadelo na Cozinha 24h"): "24h", "24 horas", "24/7"
    // in the name or the list's category, or a name numbered from 01 (the providers number each marathon's channels).
    private static final Pattern LOOP = Pattern.compile("(^| )(24 ?h|24 ?hs|24 ?hrs|24 horas|24 7|maratona|maratonas)( |$)"),
            NUMBERED = Pattern.compile(" 0[1-9]$");

    static String groupOf(String name, String category) {
        String n = " " + name + " ", c = " " + category + " ";
        if (has(n, "adulto", "adultos", "adult", "xxx", "playboy", "sexy") || has(c, "adulto", "adultos", "adult", "xxx", "18")) return ADULT;
        // The streaming services' own live channels (events, games): "Amazon Prime 01", "Disney+ 2", "HBO Max 3".
        if (starts(n.trim(), "amazon prime", "prime video", "disney plus", "hbo max", "paramount plus", "apple tv", "dazn", "globoplay", "netflix",
                "star plus", "max sports", "nba league pass", "nfl game pass")) return STREAMS;
        if (LOOP.matcher(n.trim()).find() || LOOP.matcher(c.trim()).find() || (NUMBERED.matcher(n.trim()).find() && byWords(n.trim(), true) == null))
            return H24;
        String g = byWords(n.trim(), true);
        if (g != null) return g;
        g = byWords(c.trim(), false);
        return g != null ? g : OTHER;
    }

    private static String byWords(String s, boolean name) {
        if (s.isEmpty()) return null;
        if (starts(s, "religi", "gospel", "evangel", "catolic") || has(s, "cancao nova", "aparecida", "rede vida", "novo tempo", "rit", "rede gospel", "boa vontade", "tv mundial"))
            return FAITH;
        if (has(s, "globonews", "globo news", "cnn", "band news", "bandnews", "record news", "jovem pan", "jp news", "sbt news", "cnbc", "bbc", "euronews",
                "noticias", "news", "bm c", "times brasil", "cnn brasil", "dw", "france 24", "al jazeera")) return NEWS;
        if (has(s, "cartoon", "cartoonito", "discovery kids", "nick", "nick jr", "nickelodeon", "gloob", "gloobinho", "disney", "disney jr", "disney junior",
                "baby", "baby tv", "boomerang", "tooncast", "zoomoo", "infantil", "infantis", "kids", "desenhos", "nat geo kids", "playkids")) return KIDS;
        if (has(s, "discovery", "history", "history 2", "h2", "nat geo", "national geographic", "animal planet", "investigation discovery", "smithsonian",
                "documentarios", "documentario", "curiosity", "discovery science", "discovery turbo", "discovery world", "discovery theater")) return DOCS;
        if (has(s, "sportv", "sportv2", "sportv3", "espn", "espn2", "espn3", "espn4", "premiere", "band sports", "bandsports", "combate", "dazn", "tnt sports",
                "xsports", "canal goat", "goat", "caze", "cazetv", "esporte", "esportes", "sports", "sport", "ufc", "nba", "nfl", "fight", "futebol", "ppv",
                "eleven", "f1", "formula 1", "nsports", "fifa", "conmebol", "paramount plus sports", "max sports", "golf", "tenis", "fox sports")) return SPORTS;
        if (has(s, "telecine", "hbo", "hbo2", "hbo plus", "hbo family", "hbo mundi", "hbo pop", "hbo xtreme", "hbo signature", "max", "megapix", "tnt", "tnt series",
                "tnt novelas", "space", "warner", "universal", "universal tv", "studio universal", "sony", "sony movies", "axn", "fx", "amc", "paramount",
                "paramount network", "star channel", "cinemax", "tcm", "filmes", "filme", "series", "serie", "a e", "canal brasil", "prime box", "film arts",
                "cinecanal", "darkflix", "lifetime", "telecine action", "telecine pipoca", "telecine premium", "telecine fun", "telecine touch", "telecine cult")) return MOVIES;
        if (has(s, "novela", "novelas", "fazenda", "a fazenda", "bbb", "big brother", "reality", "realities", "globoplay", "viva", "power couple", "estrela da casa"))
            return SOAPS;
        if (has(s, "bis", "mtv", "music", "musica", "musicas", "radio", "radios", "vh1", "trace", "music box", "clipes")) return MUSIC;
        if (has(s, "gnt", "multishow", "tlc", "food network", "hgtv", "canal off", "comedy central", "arte 1", "woohoo", "variedades", "e entertainment",
                "travel", "discovery home", "home health", "fashion", "fashion tv", "canal rural", "agro", "terraviva", "adult", "adultos")) return SHOWS;
        if (has(s, "globo", "rede globo", "sbt", "band", "record", "recordtv", "record tv", "redetv", "rede tv", "cultura", "tv cultura", "tv brasil", "gazeta",
                "eptv", "inter tv", "intertv", "rpc", "nsc", "tv tem", "rbs", "tv aberta", "abertos", "abertas", "aberta", "regionais", "regional", "cnt",
                "rede minas", "tv camara", "tv senado", "futura", "tv anhanguera", "tv bahia", "tv globo", "tv integracao", "tv gazeta", "tv tribuna",
                "tv verdes mares", "tv clube", "tv mirante", "tv liberal", "tv rio sul", "tv morena", "tv centro america", "tv vanguarda", "tv fronteira",
                "tv tapajos", "tv paraiba", "tv cabo branco", "tv asa branca", "tv grande rio", "tv sergipe", "tv gazeta alagoas", "tv amazonas", "tv rondonia",
                "tv acre", "tv tocantins", "tv roraima", "tv amapa", "alterosa", "tv itapoan", "record minas", "band minas", "sbt mg")) return OPEN;
        return null;
    }
}
