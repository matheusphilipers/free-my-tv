package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your IPTV lists (Xtream Codes servers: a name you choose, the address, user and password, kept only on this TV). Each
 * list's catalog (movies, shows, live channels and its own categories) is downloaded once a day into a small index on
 * disk. Each title of the TMDB screens is looked up in all of them by its TMDB number (when the provider sends it) or by
 * name and year; the same movie posted several times (4K, Full HD, dubbed, subtitled, in two lists) becomes one title with
 * its versions, best first: your audio, then the best quality this TV plays.
 */
final class Iptv {
    interface Listener { void onIptv(); }

    /** One list (provider). */
    static final class Source {
        int id;
        String name = "", server = "", user = "", pass = "", expires = "", error = "";
        int maxConnections, activeConnections;
        long at;
        int movies, shows, channels;
        volatile boolean loading;

        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("server", server).put("user", user).put("pass", pass)
                    .put("at", at).put("movies", movies).put("shows", shows).put("channels", channels).put("expires", expires);
        }

        static Source from(JSONObject o) {
            Source s = new Source();
            s.id = o.optInt("id");
            s.name = o.optString("name");
            s.server = o.optString("server");
            s.user = o.optString("user");
            s.pass = o.optString("pass");
            s.at = o.optLong("at");
            s.movies = o.optInt("movies");
            s.shows = o.optInt("shows");
            s.channels = o.optInt("channels");
            s.expires = o.optString("expires");
            return s;
        }
    }

    /** One item of a list: a movie, a show (whose episodes are fetched when it's opened) or a live channel. */
    static final class Version {
        Source source;
        String kind;        // "movie", "tv" or "live"
        int id;             // stream_id (movie, channel) or series_id (show)
        String raw = "", ext = "mp4", category = "", icon = "", epg = "";
        int tmdb;
        String title = "", year = "";
        String quality = "", codec = "", audio = "";
        int score;          // higher plays first

        /** "4K · H.265 · Dublado" */
        String label() {
            StringBuilder b = new StringBuilder();
            for (String s : new String[] {quality, codec, audio}) if (!s.isEmpty()) b.append(b.length() == 0 ? "" : " · ").append(s);
            return b.length() == 0 ? "Versão padrão" : b.toString();
        }
    }

    static final class Episode {
        Source source;
        int id, season, number;
        String title = "", ext = "mp4", image = "", plot = "";
        int seconds;
    }

    /** What's on a channel: title, start and end (epoch ms). */
    static final class Program { String title = ""; long start, end; }

    private static Iptv instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Source> sources = new ArrayList<>();
    // Per list: the index by TMDB number and by "name year" / "name", and what's in each of its categories.
    // Read from the screens while the catalog thread replaces them: concurrent maps.
    private final Map<Integer, Map<String, List<Version>>> indexes = new java.util.concurrent.ConcurrentHashMap<>(),
            categories = new java.util.concurrent.ConcurrentHashMap<>();
    // Each list's category order ("cats.<id>.<kind>" in the settings), parsed once.
    private final Map<String, List<String>> order = new java.util.concurrent.ConcurrentHashMap<>();
    // When each list was last tried (a failed download waits a while before the next try) and the ones queued now.
    private final Map<Integer, Long> tried = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> queued = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    static final long DAY = 24 * 60 * 60_000L, RETRY = 30 * 60_000L;

    static synchronized Iptv get(Context c) {
        if (instance == null) instance = new Iptv(c.getApplicationContext());
        return instance;
    }

    private Iptv(Context c) {
        context = c;
        prefs = c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        prefer4k = prefs.getBoolean("uhd", false);
        try {
            JSONArray a = new JSONArray(prefs.getString("sources", "[]"));
            for (int i = 0; i < a.length(); i++) sources.add(Source.from(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        if (sources.isEmpty() && !prefs.getString("server", "").isEmpty()) {
            // The first version kept one list in separate keys.
            Source s = new Source();
            s.id = 1;
            s.name = "Minha lista";
            s.server = prefs.getString("server", "");
            s.user = prefs.getString("user", "");
            s.pass = prefs.getString("pass", "");
            sources.add(s);
            prefs.edit().remove("server").remove("user").remove("pass").apply();
            save();
            new File(c.getFilesDir(), "iptv-index.tsv").delete();
        }
        java.io.File[] idx = c.getFilesDir().listFiles((d, name) -> name.startsWith("iptv-"));
        AppLog.w("iptv: " + sources.size() + " lists saved (" + prefs.getString("sources", "").length() + " chars); index files: "
                + (idx == null ? 0 : idx.length) + " · process " + android.app.Application.getProcessName());
        io.execute(() -> { for (Source s : sources()) loadIndex(s); changed(); });
        // Once a day: the first time the TV is on after 24 h (refresh below, from the home screen), and on its own
        // while it stays on (this check, every hour).
        main.postDelayed(daily, 60 * 60_000L);
        main.post(() -> refresh(false));
    }

    private final Runnable daily = new Runnable() {
        @Override public void run() {
            refresh(false);
            main.postDelayed(this, 60 * 60_000L);
        }
    };

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onIptv(); });
    }

    // MARK: - The lists (only on this TV)

    synchronized List<Source> sources() { return new ArrayList<>(sources); }
    boolean connected() { return !sources().isEmpty(); }
    boolean loading() { for (Source s : sources()) if (s.loading) return true; return false; }
    /** Prefer dubbed (true) or subtitled (false). */
    boolean preferDubbed() { return prefs.getBoolean("dub", true); }
    void setPreferDubbed(boolean v) { prefs.edit().putBoolean("dub", v).apply(); rescoreAll(); changed(); }
    /** 4K first (false: Full HD first; 4K is heavy for many boxes and Wi-Fi). */
    boolean prefer4k() { return prefs.getBoolean("uhd", false); }
    void setPrefer4k(boolean v) { prefs.edit().putBoolean("uhd", v).apply(); prefer4k = v; rescoreAll(); changed(); }
    private static volatile boolean prefer4k;

    /** Movies and shows of all lists whose name has the words typed (best version of each), up to `max`. */
    List<Version> search(String query, int max) {
        String q = normalize(query);
        List<Version> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        Map<String, Version> seen = new HashMap<>();
        List<Map<String, List<Version>>> all;
        synchronized (this) { all = new ArrayList<>(categories.values()); }
        for (Map<String, List<Version>> cats : all) {
            for (Map.Entry<String, List<Version>> e : cats.entrySet()) {
                if (e.getKey().startsWith("live|")) continue;
                for (Version v : e.getValue()) {
                    if (!(" " + v.title + " ").contains(" " + q) ) continue;
                    String key = v.kind + (v.tmdb > 0 ? "#" + v.tmdb : v.title + " " + v.year);
                    Version had = seen.get(key);
                    if (had == null) { seen.put(key, v); out.add(v); }
                    else if (v.score > had.score) { out.set(out.indexOf(had), v); seen.put(key, v); }
                }
            }
        }
        // Names that start with the words first, then the shorter ones.
        Collections.sort(out, (a, b) -> {
            boolean as = a.title.startsWith(q), bs = b.title.startsWith(q);
            return as != bs ? (as ? -1 : 1) : Integer.compare(a.title.length(), b.title.length());
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private volatile int generation;

    /** Goes up each time a list's catalog is (re)loaded or a list is removed: the Lineup rebuilds then. */
    int generation() { return generation; }

    /** Every live channel of every list, list by list, in each provider's category order. */
    List<Version> allLive() {
        List<Version> out = new ArrayList<>();
        for (Source s : sources()) for (String c : categories(s, "live")) out.addAll(category(s, "live", c));
        return out;
    }

    /** Live channels of all lists whose name has the words typed. */
    List<Version> searchChannels(String query) {
        String q = normalize(query);
        List<Version> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        List<Map<String, List<Version>>> all;
        synchronized (this) { all = new ArrayList<>(categories.values()); }
        for (Map<String, List<Version>> cats : all)
            for (Map.Entry<String, List<Version>> e : cats.entrySet())
                if (e.getKey().startsWith("live|"))
                    for (Version v : e.getValue()) if (normalize(v.raw).contains(q) && !out.contains(v)) out.add(v);
        return out;
    }

    /** "LFC x CFC - 17h00 - FHD (ESPN)": the provider's game-of-the-day entries are shortcuts that often fail; the
     *  real channel named in the parentheses (ESPN 1 FHD here) shows the same game. Null when there's none. */
    Version stationFor(String title) {
        int open = title.lastIndexOf('('), close = title.lastIndexOf(')');
        if (open < 0 || close < open + 2) return null;
        String name = normalize(title.substring(open + 1, close));
        if (name.isEmpty()) return null;
        Version best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Version v : searchChannels(name)) {
            String raw = normalize(v.raw);
            if (!raw.startsWith(name) || raw.contains(" x ")) continue;   // other game entries
            String rest = raw.substring(name.length()).trim();
            int score = 0;
            // "ESPN" means ESPN 1, not ESPN 2; "Premiere 5" is exact.
            if (!rest.isEmpty() && Character.isDigit(rest.charAt(0)) && !(rest.equals("1") || rest.startsWith("1 "))) score -= 20;
            String up = v.raw.toUpperCase(java.util.Locale.ROOT);
            if (UHD.matcher(up).find()) score += 0;          // 4K is heavy on this box
            else if (FHD.matcher(up).find()) score += 3;
            else if (HD.matcher(up).find()) score += 2;
            else score += 1;
            if (score > bestScore) { bestScore = score; best = v; }
        }
        return best;
    }

    private synchronized void save() {
        JSONArray a = new JSONArray();
        try { for (Source s : sources) a.put(s.json()); } catch (Exception ignored) {}
        prefs.edit().putString("sources", a.toString()).commit();
    }

    /** Checks the account, saves it as a new list and downloads its catalog. done(null) on success, else the error to show. */
    void add(String name, String server, String user, String pass, Movies.Callback<String> done) {
        String srv = server.trim();
        if (!srv.isEmpty() && !srv.startsWith("http")) srv = "http://" + srv;
        while (srv.endsWith("/")) srv = srv.substring(0, srv.length() - 1);
        Source s = new Source();
        s.server = srv;
        s.user = user.trim();
        s.pass = pass;
        s.name = name.trim();
        io.execute(() -> {
            String err = null;
            try {
                if (s.server.isEmpty() || s.user.isEmpty()) throw new IOException("Preencha o servidor e o usuário.");
                login(s);
                synchronized (this) {
                    int id = 1;
                    for (Source x : sources) id = Math.max(id, x.id + 1);
                    s.id = id;
                    if (s.name.isEmpty()) s.name = "Lista " + id;
                    sources.add(s);
                }
                save();
                changed();
                refreshIndex(s);
            } catch (Exception e) {
                err = message(e);
            }
            String result = err;
            main.post(() -> done.done(result));
        });
    }

    /**
     * Takes a list out. It's kept aside (with its catalog) until another one is removed, so "Restaurar" brings it back
     * at once: one OK too many shouldn't cost typing the server, user and password again.
     */
    void remove(Source s) {
        synchronized (this) { sources.remove(s); }
        forgetOrder(s.id);
        indexes.remove(s.id);
        categories.remove(s.id);
        generation++;
        Source before = removed();
        if (before != null && before.id != s.id) new File(context.getFilesDir(), "iptv-" + before.id + ".tsv").delete();
        try { prefs.edit().putString("removed", s.json().toString()).commit(); } catch (Exception ignored) {}
        save();
        AppLog.w("iptv: list " + s.name + " removed (kept aside to restore)");
        changed();
    }

    /** The list removed last, if it can be restored. */
    Source removed() {
        String j = prefs.getString("removed", "");
        if (j.isEmpty()) return null;
        try { return Source.from(new JSONObject(j)); } catch (Exception e) { return null; }
    }

    void restore() {
        Source s = removed();
        if (s == null) return;
        synchronized (this) {
            for (Source x : sources) if (x.id == s.id) { prefs.edit().remove("removed").apply(); return; }
            sources.add(s);
        }
        prefs.edit().remove("removed").commit();
        save();
        AppLog.w("iptv: list " + s.name + " restored");
        io.execute(() -> { loadIndex(s); changed(); });
        changed();
    }

    /**
     * Downloads each list's catalog again when it's more than a day old (or `force`: the Atualizar button). A list
     * whose download failed is tried again after half an hour, not on every screen opened.
     */
    void refresh(boolean force) {
        long now = System.currentTimeMillis();
        for (Source s : sources()) {
            if (s.loading || queued.contains(s.id)) continue;
            if (!force) {
                // The index file, not the one in memory: right after the TV starts it's still being read.
                boolean fresh = now - s.at < DAY && new File(context.getFilesDir(), "iptv-" + s.id + ".tsv").isFile();
                Long last = tried.get(s.id);
                if (fresh || (last != null && now - last < RETRY)) continue;
            }
            queued.add(s.id);
            tried.put(s.id, now);
            io.execute(() -> {
                try {
                    login(s);
                    refreshIndex(s);
                } catch (Exception e) {
                    s.error = message(e);
                    AppLog.w("iptv " + s.name + ": " + e.getMessage());
                    changed();
                } finally {
                    queued.remove(s.id);
                }
            });
        }
    }

    private static String message(Exception e) {
        String m = AppLog.redact(e.getMessage());
        if (e instanceof java.net.UnknownHostException) return "Servidor não encontrado. Confira o endereço.";
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException) return "O servidor não respondeu. Tente de novo.";
        return m == null || m.isEmpty() ? "Não deu para conectar." : m;
    }

    private static void login(Source s) throws Exception {
        JSONObject o = new JSONObject(text(api(s, null)));
        JSONObject u = o.optJSONObject("user_info");
        if (u == null) throw new IOException("Usuário ou senha não aceitos.");
        String status = u.optString("status");
        if (!"active".equalsIgnoreCase(status)) throw new IOException(status.isEmpty() ? "Usuário ou senha não aceitos." : "Conta " + status + ".");
        s.maxConnections = u.optInt("max_connections");
        s.activeConnections = u.optInt("active_cons");
        long exp = u.optLong("exp_date", 0);
        s.expires = exp > 0 ? new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US).format(new java.util.Date(exp * 1000)) : "";
        s.error = "";
    }

    private static String api(Source s, String action) throws IOException {
        return s.server + "/player_api.php?username=" + URLEncoder.encode(s.user, "UTF-8") + "&password=" + URLEncoder.encode(s.pass, "UTF-8")
                + (action == null ? "" : "&action=" + action);
    }

    /** The account right now (how many screens it allows and how many are in use): null if the server doesn't answer. */
    static Source check(Source s) {
        try {
            login(s);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** The list a channel or movie address belongs to. */
    Source sourceOf(String url) {
        for (Source s : sources()) if (url != null && url.startsWith(s.server + "/")) return s;
        return null;
    }

    // MARK: - Stream addresses

    static String movieUrl(Version v) { return v.source.server + "/movie/" + v.source.user + "/" + v.source.pass + "/" + v.id + "." + v.ext; }
    static String episodeUrl(Episode e) { return e.source.server + "/series/" + e.source.user + "/" + e.source.pass + "/" + e.id + "." + e.ext; }
    static String liveUrl(Version v, boolean hls) { return v.source.server + "/live/" + v.source.user + "/" + v.source.pass + "/" + v.id + (hls ? ".m3u8" : ".ts"); }

    // MARK: - The catalog

    private void refreshIndex(Source s) throws Exception {
        s.loading = true;
        s.error = "";
        changed();
        try {
            List<String> vodOrder = new ArrayList<>(), seriesOrder = new ArrayList<>(), liveOrder = new ArrayList<>();
            Map<String, String> vodCats = categories(api(s, "get_vod_categories"), vodOrder);
            Map<String, String> seriesCats = categories(api(s, "get_series_categories"), seriesOrder);
            Map<String, String> liveCats = categories(api(s, "get_live_categories"), liveOrder);
            File tmp = new File(context.getFilesDir(), "iptv-" + s.id + ".tmp");
            int movies, shows, channels = 0;
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
                movies = list(api(s, "get_vod_streams"), "movie", vodCats, out);
                shows = list(api(s, "get_series"), "tv", seriesCats, out);
                try {
                    channels = list(api(s, "get_live_streams"), "live", liveCats, out);
                } catch (Exception e) {
                    AppLog.w("iptv live: " + e.getMessage());
                }
            }
            if (!tmp.renameTo(new File(context.getFilesDir(), "iptv-" + s.id + ".tsv"))) throw new IOException("index");
            prefs.edit().putString("cats." + s.id + ".movie", new JSONArray(vodOrder).toString())
                    .putString("cats." + s.id + ".tv", new JSONArray(seriesOrder).toString())
                    .putString("cats." + s.id + ".live", new JSONArray(liveOrder).toString()).apply();
            forgetOrder(s.id);
            s.movies = movies;
            s.shows = shows;
            s.channels = channels;
            s.at = System.currentTimeMillis();
            save();
            loadIndex(s);
        } finally {
            s.loading = false;
            changed();
        }
    }

    private static Map<String, String> categories(String url, List<String> order) {
        Map<String, String> out = new HashMap<>();
        try {
            JSONArray a = new JSONArray(text(url));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String name = clean(o.optString("category_name"));
                out.put(o.optString("category_id"), name);
                if (!name.isEmpty() && !order.contains(name)) order.add(name);
            }
        } catch (Exception e) {
            AppLog.w("iptv categories: " + e.getMessage());
        }
        return out;
    }

    /** Streams a (big) list to the index file, one line per item: kind, id, ext, tmdb, category, name, image, EPG id. */
    private static int list(String url, String kind, Map<String, String> cats, BufferedWriter out) throws IOException {
        HttpURLConnection c = open(url, 60000);
        int n = 0;
        try (JsonReader r = new JsonReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            if (r.peek() != JsonToken.BEGIN_ARRAY) return 0;
            r.beginArray();
            while (r.hasNext()) {
                String name = "", ext = "mp4", cat = "", tmdb = "", icon = "", epg = "";
                int id = 0;
                r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (r.peek() == JsonToken.NULL) { r.skipValue(); continue; }
                    switch (key) {
                        case "name": name = str(r); break;
                        case "stream_id": case "series_id": id = (int) num(r); break;
                        case "container_extension": ext = str(r); break;
                        case "category_id": cat = str(r); break;
                        case "tmdb": case "tmdb_id": tmdb = str(r); break;
                        case "stream_icon": case "cover": icon = str(r); break;
                        case "epg_channel_id": epg = str(r); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (id == 0 || name.isEmpty()) continue;
                if (kind.equals("live")) ext = "m3u8";
                String category = cats.containsKey(cat) ? cats.get(cat) : "";
                out.write(kind + "\t" + id + "\t" + clean(ext) + "\t" + digits(tmdb) + "\t" + clean(category) + "\t" + clean(name) + "\t" + clean(icon) + "\t" + clean(epg) + "\n");
                n++;
            }
            r.endArray();
        } finally {
            c.disconnect();
        }
        return n;
    }

    private static String str(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.BEGIN_ARRAY || r.peek() == JsonToken.BEGIN_OBJECT) { r.skipValue(); return ""; }
        return r.nextString();
    }

    private static double num(JsonReader r) throws IOException {
        try { return Double.parseDouble(str(r)); } catch (NumberFormatException e) { return 0; }
    }

    private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' ').trim(); }

    private static String digits(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) if (ch >= '0' && ch <= '9') b.append(ch);
        return b.toString();
    }

    private void loadIndex(Source s) {
        File f = new File(context.getFilesDir(), "iptv-" + s.id + ".tsv");
        if (!f.isFile()) return;
        Map<String, List<Version>> idx = new HashMap<>(32768), cats = new HashMap<>(512);
        boolean dub = preferDubbed();
        // Tens of thousands of lines repeat a few kinds, extensions and categories: one copy of each (less memory).
        Map<String, String> pool = new HashMap<>(1024);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 6 || !isNumber(p[1])) continue;
                Version v = new Version();
                v.source = s;
                v.kind = one(pool, p[0]);
                v.id = Integer.parseInt(p[1]);
                v.ext = p[2].isEmpty() ? "mp4" : one(pool, p[2]);
                v.tmdb = isNumber(p[3]) ? Integer.parseInt(p[3]) : 0;
                v.category = one(pool, p[4]);
                v.raw = p[5];
                if (p.length > 6) v.icon = p[6];
                if (p.length > 7) v.epg = p[7];
                add(cats, one(pool, v.kind + "|" + v.category), v);
                if (v.kind.equals("live")) continue;
                parse(v);
                v.quality = one(pool, v.quality);
                v.codec = one(pool, v.codec);
                v.audio = one(pool, v.audio);
                v.year = one(pool, v.year);
                score(v, dub);
                if (v.tmdb > 0) add(idx, v.kind + "#" + v.tmdb, v);
                if (!v.title.isEmpty()) {
                    if (!v.year.isEmpty()) add(idx, v.kind + ":" + v.title + " " + v.year, v);
                    add(idx, v.kind + ":" + v.title, v);
                }
            }
        } catch (Exception e) {
            AppLog.w("iptv index: " + e.getMessage());
        }
        for (List<Version> l : idx.values()) sort(l);
        synchronized (this) {
            indexes.put(s.id, idx);
            categories.put(s.id, cats);
            forgetOrder(s.id);
            generation++;
        }
        changed();
    }

    private static String one(Map<String, String> pool, String s) {
        String had = pool.get(s);
        if (had != null) return had;
        pool.put(s, s);
        return s;
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty() || s.length() > 9) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    private static void add(Map<String, List<Version>> idx, String key, Version v) {
        List<Version> l = idx.get(key);
        if (l == null) idx.put(key, l = new ArrayList<>(2));
        l.add(v);
    }

    private static void sort(List<Version> l) {
        Collections.sort(l, (a, b) -> Integer.compare(b.score, a.score));
    }

    private synchronized void rescoreAll() {
        boolean dub = preferDubbed();
        for (Map<String, List<Version>> idx : indexes.values()) {
            for (Map.Entry<String, List<Version>> e : idx.entrySet()) {
                for (Version v : e.getValue()) score(v, dub);
                // A sorted copy: a screen may be going through the old list right now.
                List<Version> l = new ArrayList<>(e.getValue());
                sort(l);
                e.setValue(l);
            }
        }
    }

    // MARK: - The provider's own organization

    /** A list's categories ("movie", "tv" or "live") that have something, in its order. */
    List<String> categories(Source s, String kind) {
        Map<String, List<Version>> cats = categories.get(s.id);
        if (cats == null) return new ArrayList<>();
        String key = s.id + "." + kind;
        List<String> known = order.get(key);
        if (known == null) {
            known = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(prefs.getString("cats." + key, "[]"));
                for (int i = 0; i < a.length(); i++) if (cats.containsKey(kind + "|" + a.getString(i))) known.add(a.getString(i));
            } catch (Exception ignored) {}
            order.put(key, known);
        }
        return new ArrayList<>(known);
    }

    private void forgetOrder(int id) {
        for (String k : new ArrayList<>(order.keySet())) if (k.startsWith(id + ".")) order.remove(k);
    }

    /** What's in a category, in the provider's order; movies and shows posted several times come once (their best version). */
    List<Version> category(Source s, String kind, String name) {
        Map<String, List<Version>> cats = categories.get(s.id);
        List<Version> all = cats == null ? null : cats.get(kind + "|" + name);
        if (all == null) return Collections.emptyList();
        if (kind.equals("live")) return all;
        List<Version> out = new ArrayList<>();
        Map<String, Version> seen = new HashMap<>();
        for (Version v : all) {
            String key = v.tmdb > 0 ? "#" + v.tmdb : v.title + " " + v.year;
            Version had = seen.get(key);
            if (had == null) { seen.put(key, v); out.add(v); }
            else if (v.score > had.score) { out.set(out.indexOf(had), v); seen.put(key, v); }
        }
        return out;
    }

    /** A category's name without the provider's decorations ("✨ •NETFLIX" → "NETFLIX"), for showing. */
    static String categoryLabel(String name) {
        String t = name.replaceAll("^[^\\p{L}\\p{N}]+", "").replaceAll("[^\\p{L}\\p{N})\\]]+$", "").trim();
        return t.isEmpty() ? name.trim() : t;
    }

    /** What makes two lists' categories the same one ("•NETFLIX" and "Netflix"). */
    static String categoryKey(String name) { return Lineup.norm(name); }

    /** All lists' categories of a kind, merged by name, in the first list's order: {key, label} each. */
    List<String[]> mergedCategories(String kind) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (Source s : sources()) for (String c : categories(s, kind)) {
            String k = categoryKey(c);
            if (!k.isEmpty() && !out.containsKey(k)) out.put(k, categoryLabel(c));
        }
        List<String[]> l = new ArrayList<>();
        for (Map.Entry<String, String> e : out.entrySet()) l.add(new String[] {e.getKey(), e.getValue()});
        return l;
    }

    /** A merged category: what each list has under that name, each title once (its best version). */
    List<Version> mergedCategory(String kind, String key) {
        List<Version> out = new ArrayList<>();
        Map<String, Version> seen = new HashMap<>();
        for (Source s : sources()) for (String c : categories(s, kind)) {
            if (!categoryKey(c).equals(key)) continue;
            for (Version v : category(s, kind, c)) {
                String k = v.tmdb > 0 ? "#" + v.tmdb : v.title + " " + v.year;
                Version had = seen.get(k);
                if (had == null) { seen.put(k, v); out.add(v); }
                else if (v.score > had.score) { out.set(out.indexOf(had), v); seen.put(k, v); }
            }
        }
        return out;
    }

    /** "Batman (2022) [4K]" → "Batman (2022)" for showing. */
    static String display(Version v) {
        String s = PREFIX.matcher(v.raw).replaceFirst("");
        s = s.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("(?i)\\b(4K|UHD|FHD|FULL ?HD|HD|SD|H\\.?26[45]|HEVC|X26[45]|DUBLADO|LEGENDADO|DUAL|AUDIO|NACIONAL)\\b", " ");
        return s.replaceAll("\\s+", " ").replaceAll("[\\s\\-|]+$", "").trim();
    }

    List<Program> guide(Version channel) { return guide(channel, 4); }

    /** The next `limit` programs on a channel (from now). */
    List<Program> guide(Version channel, int limit) {
        List<Program> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(text(api(channel.source, "get_short_epg&stream_id=" + channel.id + "&limit=" + limit)));
            JSONArray a = o.optJSONArray("epg_listings");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                Program p = new Program();
                p.title = b64(e.optString("title"));
                p.start = e.optLong("start_timestamp", 0) * 1000;
                p.end = e.optLong("stop_timestamp", 0) * 1000;
                if (p.end > System.currentTimeMillis()) out.add(p);
            }
        } catch (Exception e) {
            AppLog.w("iptv epg: " + e.getMessage());
        }
        return out;
    }

    private static String b64(String s) {
        try {
            return new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return s;
        }
    }

    // Favourite channels ("list:stream id"), kept on this TV.
    private static String favKey(Version ch) { return ch.source.id + ":" + ch.id; }

    boolean favourite(Version channel) { return prefs.getStringSet("fav", Collections.emptySet()).contains(favKey(channel)); }

    void toggleFavourite(Version channel) {
        java.util.Set<String> fav = new java.util.HashSet<>(prefs.getStringSet("fav", Collections.emptySet()));
        if (!fav.remove(favKey(channel))) fav.add(favKey(channel));
        prefs.edit().putStringSet("fav", fav).apply();
    }

    List<Version> favourites() {
        java.util.Set<String> fav = prefs.getStringSet("fav", Collections.emptySet());
        List<Version> out = new ArrayList<>();
        if (fav.isEmpty()) return out;
        for (Source s : sources()) {
            Map<String, List<Version>> cats = categories.get(s.id);
            if (cats == null) continue;
            for (Map.Entry<String, List<Version>> e : cats.entrySet()) {
                if (!e.getKey().startsWith("live|")) continue;
                for (Version v : e.getValue()) if (fav.contains(favKey(v)) && !out.contains(v)) out.add(v);
            }
        }
        return out;
    }

    /**
     * How fast a channel answers: ms until the first ~20 KB of its stream (or of its HLS playlist) arrive, after the
     * redirects; negative: the HTTP error (or -1 when it doesn't answer). For the channel test in Channels (MENU).
     */
    static long probe(Version ch, boolean hls) {
        long t0 = System.currentTimeMillis();
        String target = liveUrl(ch, hls);
        try {
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", PlayerActivity.AGENT);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    target = new URL(new URL(target), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                if (code != 200) { c.disconnect(); return -code; }
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int total = 0, n;
                    while (total < (hls ? 200 : 20000) && (n = in.read(buf)) > 0) total += n;
                } finally {
                    c.disconnect();
                }
                return System.currentTimeMillis() - t0;
            }
        } catch (Exception e) {
            return -1;
        }
        return -1;
    }

    // MARK: - Looking a title up

    /** The versions of a TMDB title in all your lists, best first (empty if it isn't there). */
    List<Version> versions(Movies.Title t) {
        List<Version> out = new ArrayList<>();
        if (t == null) return out;
        List<Map<String, List<Version>>> all;
        synchronized (this) { all = new ArrayList<>(indexes.values()); }
        for (Map<String, List<Version>> idx : all) {
            List<Version> byId = idx.get(t.kind + "#" + t.id);
            if (byId != null) for (Version v : byId) if (!out.contains(v)) out.add(v);
            // By name: the Portuguese and the original title, with the year (and without, if the item has none).
            for (String name : new String[] {t.name, t.original}) {
                String n = normalize(name);
                if (n.isEmpty()) continue;
                List<Version> l = idx.get(t.kind + ":" + n + " " + t.year);
                if (l != null) for (Version v : l) if (!out.contains(v) && (v.tmdb == 0 || v.tmdb == t.id)) out.add(v);
                l = idx.get(t.kind + ":" + n);
                if (l != null) for (Version v : l) if (!out.contains(v) && v.year.isEmpty() && (v.tmdb == 0 || v.tmdb == t.id)) out.add(v);
            }
        }
        sort(out);
        return out;
    }

    boolean has(Movies.Title t) { return !versions(t).isEmpty(); }

    /** A show's seasons and episodes (from one version of it). */
    List<Episode> episodes(Version show) throws Exception {
        JSONObject o = new JSONObject(text(api(show.source, "get_series_info&series_id=" + show.id)));
        List<Episode> out = new ArrayList<>();
        Object eps = o.opt("episodes");
        if (eps instanceof JSONObject) {
            JSONObject seasons = (JSONObject) eps;
            java.util.Iterator<String> it = seasons.keys();
            while (it.hasNext()) {
                JSONArray a = seasons.optJSONArray(it.next());
                for (int i = 0; a != null && i < a.length(); i++) out.add(episode(a.getJSONObject(i), show.source));
            }
        } else if (eps instanceof JSONArray) {
            JSONArray groups = (JSONArray) eps;
            for (int g = 0; g < groups.length(); g++) {
                JSONArray a = groups.optJSONArray(g);
                for (int i = 0; a != null && i < a.length(); i++) out.add(episode(a.getJSONObject(i), show.source));
            }
        }
        Collections.sort(out, (a, b) -> a.season != b.season ? Integer.compare(a.season, b.season) : Integer.compare(a.number, b.number));
        return out;
    }

    private static Episode episode(JSONObject e, Source s) {
        Episode x = new Episode();
        x.source = s;
        x.id = e.optInt("id");
        x.season = e.optInt("season");
        x.number = e.optInt("episode_num");
        x.title = e.optString("title");
        x.ext = e.optString("container_extension", "mp4");
        JSONObject info = e.optJSONObject("info");
        if (info != null) {
            x.image = info.optString("movie_image");
            x.plot = info.optString("plot");
            x.seconds = info.optInt("duration_secs");
        }
        return x;
    }

    // MARK: - Names and versions

    private static final Pattern YEAR = Pattern.compile("(?<![0-9])(19[3-9][0-9]|20[0-4][0-9])(?![0-9])");
    private static final Pattern PREFIX = Pattern.compile("^\\s*(\\|?[A-Za-z]{2,3}\\|?)\\s*[|:\\-]\\s+");
    private static final Pattern BRACKETS = Pattern.compile("[\\[(][^\\])]*[\\])]");
    private static final String[] NOISE = {"4k", "uhd", "fhd", "hd", "sd", "hdr", "hdr10", "dv", "2160p", "1080p", "720p", "480p", "h264", "h265",
            "x264", "x265", "hevc", "av1", "dublado", "dub", "dual", "audio", "legendado", "leg", "nacional", "web", "webdl", "bluray", "cam",
            "3d", "imax", "remux", "multi", "l"};

    private static void parse(Version v) {
        String raw = v.raw;
        String up = (raw + " " + v.category).toUpperCase(Locale.ROOT);
        Matcher y = YEAR.matcher(raw);
        String year = "";
        while (y.find()) year = y.group(1);   // the last one ("Blade Runner 2049 (2017)" → 2017)
        v.year = year;
        // Precompiled patterns: this runs for every item of a list (tens of thousands) when the TV starts.
        v.quality = UHD.matcher(up).find() ? "4K" : FHD.matcher(up).find() ? "Full HD" : HD.matcher(up).find() ? "HD" : SD.matcher(up).find() ? "SD" : "";
        v.codec = H265.matcher(up).find() ? "H.265" : AV1.matcher(up).find() ? "AV1" : H264.matcher(up).find() ? "H.264" : "";
        String upName = raw.toUpperCase(Locale.ROOT);
        String audio = audioOf(upName);
        if (audio.isEmpty()) audio = audioOf(v.category.toUpperCase(Locale.ROOT));
        v.audio = audio;
        String name = PREFIX.matcher(raw).replaceFirst("");
        if (!year.isEmpty()) {
            int at = name.lastIndexOf(year);
            if (at > 0) name = name.substring(0, at);   // everything after the year is tags
        }
        v.title = normalize(name);
    }

    private static final Pattern UHD = Pattern.compile("2160|4K|UHD"), FHD = Pattern.compile("1080|FHD|FULL ?HD"),
            HD = Pattern.compile("720|\\bHD\\b"), SD = Pattern.compile("480|\\bSD\\b"), H265 = Pattern.compile("H\\.?265|HEVC|X265"),
            AV1 = Pattern.compile("\\bAV1\\b"), H264 = Pattern.compile("H\\.?264|X264|AVC"), DUAL = Pattern.compile("\\bDUAL\\b"),
            LEG = Pattern.compile("\\[L\\]|\\(L\\)|\\bLEG\\b|LEGENDADO|\\bSUB\\b"), DUB = Pattern.compile("\\bDUB\\b|DUBLADO|NACIONAL|\\[D\\]"),
            MARKS = Pattern.compile("\\p{M}"), NOT_WORD = Pattern.compile("[^a-z0-9]+");
    private static final java.util.Set<String> NOISE_SET = new java.util.HashSet<>(java.util.Arrays.asList(NOISE));

    private static String audioOf(String up) {
        if (DUAL.matcher(up).find()) return "Dual áudio";
        if (LEG.matcher(up).find()) return "Legendado";
        if (DUB.matcher(up).find()) return "Dublado";
        return "";
    }

    /** "Batman: O Cavaleiro das Trevas (2008) [4K]" → "batman o cavaleiro das trevas" */
    static String normalize(String s) {
        if (s == null) return "";
        String t = BRACKETS.matcher(s).replaceAll(" ");
        t = MARKS.matcher(Normalizer.normalize(t, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
        t = NOT_WORD.matcher(t.replace("&", " e ")).replaceAll(" ").trim();
        StringBuilder b = new StringBuilder();
        for (String w : t.split(" ")) {
            if (!w.isEmpty() && !NOISE_SET.contains(w)) b.append(b.length() == 0 ? "" : " ").append(w);
        }
        return b.toString();
    }

    private static Boolean hevc, av1;

    /** Which version plays first: your audio, then what this TV decodes, then the best quality. */
    private static void score(Version v, boolean dub) {
        int s = 0;
        if (v.audio.equals(dub ? "Dublado" : "Legendado")) s += 400;
        else if (v.audio.equals("Dual áudio")) s += 350;
        else if (v.audio.isEmpty()) s += 200;
        if (!plays(v.codec)) s -= 1000;
        switch (v.quality) {
            case "4K": s += prefer4k ? 40 : 25; break;   // Full HD first unless you asked for 4K: smoother on Wi-Fi
            case "Full HD": s += 30; break;
            case "HD": s += 20; break;
            case "SD": s += 5; break;
            default: s += 15;
        }
        if (v.ext.equalsIgnoreCase("mp4")) s += 2;
        v.score = s;
    }

    /** False for a video format this TV has no decoder for (an AV1 file on an older box). */
    static boolean plays(String codec) {
        if (codec.equals("H.265")) {
            if (hevc == null) hevc = hasDecoder("video/hevc");
            return hevc;
        }
        if (codec.equals("AV1")) {
            if (av1 == null) av1 = hasDecoder("video/av01");
            return av1;
        }
        return true;
    }

    private static boolean hasDecoder(String mime) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(mime)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // MARK: - Network

    private static HttpURLConnection open(String url, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) FreeMyTV");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException(code == 401 || code == 403 ? "Usuário ou senha não aceitos." : "O servidor respondeu " + code + ".");
        }
        return c;
    }

    private static String text(String url) throws IOException {
        HttpURLConnection c = open(url, 20000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                // Answers read whole are small (an account, categories, a guide, a show's episodes): a server that
                // keeps sending isn't allowed to fill the memory.
                if (out.size() > 24 * 1024 * 1024) throw new IOException("Resposta grande demais do servidor.");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
