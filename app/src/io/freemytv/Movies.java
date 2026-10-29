package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Movies and shows from TMDB, for the "Movies & Shows" screens and the home screen's rows: what's hot on Brazil's
 * streaming services, discovery with filters, rankings, a title's details (cast, Portuguese user reviews, where to
 * watch, similar titles) and your preferences (favourite genres, genres to avoid, minimum rating, the services you
 * pay for). The home rows are rebuilt once a week (or when the preferences change) and kept on disk.
 */
final class Movies {
    static final String API = "https://api.themoviedb.org/3";

    // MARK: - Model

    static final class Title {
        /** For the home screen's posters (filled in the background): the first streaming service it's on, and your IPTV lists. */
        volatile String service = "";
        volatile boolean onIptv, tagged;
        int id;
        String kind = "movie";   // "movie" or "tv"
        String name = "", overview = "", poster = "", backdrop = "", year = "", original = "";
        double rating;
        int votes;
        final List<Integer> genres = new ArrayList<>();
        int rank;   // in a ranking (1..), 0 otherwise
        /** Continue watching on the home screen: where it stopped (PlayerActivity.Resume), and its picture's address. */
        org.json.JSONObject resume;
        String imageUrl = "";

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject().put("id", id).put("k", kind).put("n", name).put("o", overview).put("p", poster)
                    .put("b", backdrop).put("y", year).put("r", rating).put("v", votes).put("rk", rank).put("or", original);
            JSONArray g = new JSONArray();
            for (int x : genres) g.put(x);
            return o.put("g", g);
        }

        static Title from(JSONObject o) {
            Title t = new Title();
            t.id = o.optInt("id");
            t.kind = o.optString("k", "movie");
            t.name = o.optString("n");
            t.overview = o.optString("o");
            t.poster = o.optString("p");
            t.backdrop = o.optString("b");
            t.year = o.optString("y");
            t.rating = o.optDouble("r", 0);
            t.votes = o.optInt("v");
            t.rank = o.optInt("rk");
            t.original = o.optString("or");
            JSONArray g = o.optJSONArray("g");
            for (int i = 0; g != null && i < g.length(); i++) t.genres.add(g.optInt(i));
            return t;
        }

        String kindLabel() { return kind.equals("tv") ? "Série" : "Filme"; }
    }

    static final class Person { int id; String name = "", role = "", photo = ""; }

    /** An actor or director: photo, biography and what they've done (most popular first). */
    static final class PersonDetail {
        String name = "", photo = "", bio = "", born = "", place = "", known = "";
        boolean bioEnglish;
        final List<Title> titles = new ArrayList<>();
    }

    PersonDetail person(int id) throws Exception {
        JSONObject o = get("/person/" + id + "?language=pt-BR&append_to_response=combined_credits");
        PersonDetail p = new PersonDetail();
        p.name = o.optString("name");
        p.photo = o.isNull("profile_path") ? "" : o.optString("profile_path");
        p.bio = o.optString("biography");
        if (p.bio.isEmpty()) {
            p.bio = get("/person/" + id + "?language=en-US").optString("biography");
            p.bioEnglish = !p.bio.isEmpty();
        }
        String b = o.optString("birthday");
        if (b.length() >= 10) p.born = b.substring(8, 10) + "/" + b.substring(5, 7) + "/" + b.substring(0, 4);
        p.place = o.optString("place_of_birth");
        p.known = o.optString("known_for_department");
        JSONObject credits = o.optJSONObject("combined_credits");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String part : new String[] {"cast", "crew"}) {
            JSONArray a = credits == null ? null : credits.optJSONArray(part);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject c = a.getJSONObject(i);
                String kind = c.optString("media_type");
                if (!(kind.equals("movie") || kind.equals("tv")) || c.isNull("poster_path")) continue;
                if (kind.equals("tv") && c.optJSONArray("genre_ids") != null && c.optJSONArray("genre_ids").toString().matches(".*\\b(10767|10763)\\b.*")) continue;   // talk shows, news
                if (!seen.add(kind + c.optInt("id"))) continue;
                Title t = title(c, kind);
                t.rank = (int) Math.round(c.optDouble("popularity", 0) * 100);
                p.titles.add(t);
            }
        }
        java.util.Collections.sort(p.titles, (x, y) -> Integer.compare(y.rank, x.rank));
        for (Title t : p.titles) t.rank = 0;
        return p;
    }

    // MARK: - My list (favourite movies and shows, on this TV)

    List<Title> myList() {
        List<Title> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("mine", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Title.from(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    boolean inMyList(Title t) {
        for (Title x : myList()) if (x.id == t.id && x.kind.equals(t.kind)) return true;
        return false;
    }

    /** Adds (at the front) or removes; true if it's in the list now. */
    boolean toggleMyList(Title t) {
        List<Title> list = myList();
        boolean had = false;
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id == t.id && list.get(i).kind.equals(t.kind)) { list.remove(i); had = true; }
        if (!had) list.add(0, t);
        JSONArray a = new JSONArray();
        try { for (Title x : list) a.put(x.json()); } catch (Exception ignored) {}
        prefs.edit().putString("mine", a.toString()).apply();
        notifyListeners();
        return !had;
    }
    /** Started watching: the title leaves My list (it's in Continue watching now). True if it was there. */
    boolean watching(String kind, int id) {
        if (id <= 0 || kind == null) return false;
        List<Title> list = myList();
        boolean had = false;
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id == id && list.get(i).kind.equals(kind)) { list.remove(i); had = true; }
        if (!had) return false;
        JSONArray a = new JSONArray();
        try { for (Title x : list) a.put(x.json()); } catch (Exception ignored) {}
        prefs.edit().putString("mine", a.toString()).apply();
        notifyListeners();
        return true;
    }

    static final class Review { String author = "", text = "", date = ""; double rating; boolean english; }
    static final class Provider { int id; String name = "", logo = "", pkg; }

    static final class Detail {
        Title t;
        String runtime = "", certification = "", director = "", tagline = "";
        final List<String> genreNames = new ArrayList<>();
        final List<Person> cast = new ArrayList<>();
        final List<Review> reviews = new ArrayList<>();
        final List<Provider> stream = new ArrayList<>(), rent = new ArrayList<>();
        final List<Title> similar = new ArrayList<>();
    }

    static final class Row {
        String title = "", subtitle = "";
        boolean ranked;
        final List<Title> items = new ArrayList<>();
    }

    static final class Home {
        Title hero;
        String heroLabel = "";
        final List<Row> rows = new ArrayList<>();
        long builtAt;
    }

    // MARK: - Genres (one list for movies and shows; TMDB numbers them differently)

    static final String[][] GENRES = {
        // key, name, movie id, tv id
        {"acao", "Ação", "28", "10759"}, {"aventura", "Aventura", "12", "10759"}, {"animacao", "Animação", "16", "16"},
        {"comedia", "Comédia", "35", "35"}, {"crime", "Crime", "80", "80"}, {"documentario", "Documentário", "99", "99"},
        {"drama", "Drama", "18", "18"}, {"familia", "Família", "10751", "10751"}, {"fantasia", "Fantasia", "14", "10765"},
        {"terror", "Terror", "27", ""}, {"misterio", "Mistério", "9648", "9648"}, {"romance", "Romance", "10749", ""},
        {"ficcao", "Ficção científica", "878", "10765"}, {"suspense", "Suspense", "53", "9648"}, {"guerra", "Guerra", "10752", "10768"},
        {"historia", "História", "36", ""}, {"reality", "Reality", "", "10764"}, {"infantil", "Infantil", "", "10762"},
    };

    static String genreName(String key) {
        for (String[] g : GENRES) if (g[0].equals(key)) return g[1];
        return key;
    }

    static String genreId(String key, String kind) {
        for (String[] g : GENRES) if (g[0].equals(key)) return kind.equals("tv") ? g[3] : g[2];
        return "";
    }

    /** The first genre name of a title (for the "2019 · Suspense" line). */
    static String firstGenre(Title t) {
        for (int id : t.genres) {
            String s = String.valueOf(id);
            for (String[] g : GENRES) if ((t.kind.equals("tv") ? g[3] : g[2]).equals(s)) return g[1];
        }
        return "";
    }

    // MARK: - Streaming services in Brazil (TMDB provider ids) and their TV apps

    static final Object[][] SERVICES = {
        {"Netflix", "8", "com.netflix.ninja"}, {"Prime Video", "119", "com.amazon.amazonvideo.livingroom"},
        {"Max", "1899|384", "com.wbd.stream"}, {"Globoplay", "307", "com.globo.globotv"}, {"Disney+", "337", "com.disney.disneyplus"},
        {"Apple TV", "350", "com.apple.atve.androidtv.appletv"}, {"Paramount+", "531", "com.cbs.ott"}, {"Crunchyroll", "283", "com.crunchyroll.crunchyroid"},
    };

    static String serviceName(int providerId) {
        for (Object[] s : SERVICES) for (String id : ((String) s[1]).split("\\|")) if (id.equals(String.valueOf(providerId))) return (String) s[0];
        return null;
    }

    static String servicePkg(int providerId) {
        for (Object[] s : SERVICES) for (String id : ((String) s[1]).split("\\|")) if (id.equals(String.valueOf(providerId))) return (String) s[2];
        return null;
    }

    // MARK: - Preferences

    private final SharedPreferences prefs;

    List<String> favourites() { return split(prefs.getString("fav", "suspense,ficcao,crime")); }
    List<String> avoid() { return split(prefs.getString("avoid", "")); }
    int minRating() { return prefs.getInt("min", 7); }
    /** Names of the services you pay for (default: the ones whose app is on the TV). */
    List<String> subscriptions() {
        String s = prefs.getString("subs", null);
        if (s != null) return split(s);
        List<String> out = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (Object[] x : SERVICES) {
            try {
                pm.getPackageInfo((String) x[2], 0);
                out.add((String) x[0]);
            } catch (Exception ignored) {}
        }
        return out;
    }

    void setFavourites(List<String> v) { prefs.edit().putString("fav", join(v)).apply(); homeStale(); }
    void setAvoid(List<String> v) { prefs.edit().putString("avoid", join(v)).apply(); homeStale(); }
    void setMinRating(int v) { prefs.edit().putInt("min", v).apply(); homeStale(); }
    void setSubscriptions(List<String> v) { prefs.edit().putString("subs", join(v)).apply(); homeStale(); }

    private String providerIds() { return providerIds(subscriptions()); }

    private static String providerIds(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (String name : names) for (Object[] s : SERVICES) if (s[0].equals(name)) b.append(b.length() == 0 ? "" : "|").append(s[1]);
        return b.toString();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split(",")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    private static String join(List<String> v) {
        StringBuilder b = new StringBuilder();
        for (String x : v) b.append(b.length() == 0 ? "" : ",").append(x);
        return b.toString();
    }

    // MARK: - Singleton, threads, listeners

    interface Listener { void onMovies(); }

    private static Movies instance;
    private final Context context;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    volatile Home home;
    private volatile boolean building, again;

    static synchronized Movies get(Context c) {
        if (instance == null) instance = new Movies(c.getApplicationContext());
        return instance;
    }

    private Movies(Context c) {
        context = c;
        Img.init(c);
        prefs = c.getSharedPreferences("movies", Context.MODE_PRIVATE);
        try {
            String saved = prefs.getString("home", null);
            if (saved != null) home = homeFrom(new JSONObject(saved));
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onMovies(); });
    }

    interface Callback<T> { void done(T result); }

    /** Runs the work in the background and hands the result (null on failure) back on the main thread. */
    <T> void run(java.util.concurrent.Callable<T> work, Callback<T> cb) {
        io.execute(() -> {
            T r = null;
            try {
                r = work.call();
            } catch (Exception e) {
                AppLog.w("movies: " + e.getMessage());
            }
            T done = r;
            main.post(() -> cb.done(done));
        });
    }

    // MARK: - The home rows (weekly)

    private static long week(long at) {
        Calendar c = Calendar.getInstance();
        c.setFirstDayOfWeek(Calendar.MONDAY);
        c.setTimeInMillis(at);
        return c.get(Calendar.YEAR) * 100L + c.get(Calendar.WEEK_OF_YEAR);
    }

    private void homeStale() {
        prefs.edit().putLong("homeWeek", 0).apply();
        refreshHome(true);
    }

    /** Builds the home rows if they're from another week (or `force`). */
    void refreshHome(boolean force) {
        if (Config.TMDB_KEY.trim().isEmpty()) return;
        if (building) { again |= force; return; }
        long now = System.currentTimeMillis();
        if (!force && home != null && prefs.getLong("homeWeek", 0) == week(now)) return;
        building = true;
        io.execute(() -> {
            try {
                Home h = buildHome();
                home = h;
                prefs.edit().putString("home", homeJson(h).toString()).putLong("homeWeek", week(System.currentTimeMillis())).apply();
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("movies home: " + e.getMessage());
            } finally {
                building = false;
                if (again) { again = false; main.post(() -> refreshHome(true)); }
            }
        });
    }

    private Home buildHome() throws Exception {
        Home h = new Home();
        h.builtAt = System.currentTimeMillis();
        Row top = new Row();
        top.title = "Top 10 nos streamings do Brasil";
        top.subtitle = "Os mais populares da semana";
        top.ranked = true;
        List<Title> hot = merge(discover("movie", new Filter().popular()), discover("tv", new Filter().popular()));
        for (int i = 0; i < hot.size() && top.items.size() < 10; i++) {
            Title t = hot.get(i);
            t.rank = top.items.size() + 1;
            top.items.add(t);
        }
        h.rows.add(top);
        List<String> fav = favourites();
        String[] names = {"Porque você curte %s", "%s pra você", "%s em alta"};
        Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < fav.size() && i < 3; i++) {
            Row r = new Row();
            String g = genreName(fav.get(i));
            r.title = String.format(names[i], g);
            r.subtitle = (i + 1) + "ª preferência · nota " + minRating() + "+ nos seus streamings";
            Filter f = new Filter();
            f.genres.add(fav.get(i));
            f.minRating = minRating();
            f.mine = true;
            f.sort = "popularity.desc";
            f.minVotes = 150;
            for (Title t : merge(discover("movie", f), discover("tv", f))) {
                if (r.items.size() >= 18) break;
                if (seen.add(t.id)) r.items.add(t);
            }
            if (!r.items.isEmpty()) h.rows.add(r);
        }
        // The week's highlight: the best-rated recent title of your 1st preference, else the top title.
        if (!fav.isEmpty()) {
            Filter f = new Filter();
            f.genres.add(fav.get(0));
            f.mine = true;
            f.sort = "vote_average.desc";
            f.minVotes = 80;
            f.fromYear = Calendar.getInstance().get(Calendar.YEAR) - 1;
            List<Title> recent = merge(discover("movie", f), discover("tv", f));
            for (Title t : recent) if (!t.backdrop.isEmpty()) { h.hero = t; break; }
            h.heroLabel = "DESTAQUE DA SEMANA · " + genreName(fav.get(0)).toUpperCase();
        }
        if (h.hero == null && !top.items.isEmpty()) {
            h.hero = top.items.get(0);
            h.heroLabel = "DESTAQUE DA SEMANA";
        }
        return h;
    }

    private static JSONObject homeJson(Home h) throws Exception {
        JSONObject o = new JSONObject().put("at", h.builtAt).put("hl", h.heroLabel);
        if (h.hero != null) o.put("hero", h.hero.json());
        JSONArray rows = new JSONArray();
        for (Row r : h.rows) {
            JSONArray items = new JSONArray();
            for (Title t : r.items) items.put(t.json());
            rows.put(new JSONObject().put("t", r.title).put("s", r.subtitle).put("r", r.ranked).put("i", items));
        }
        return o.put("rows", rows);
    }

    private static Home homeFrom(JSONObject o) {
        Home h = new Home();
        h.builtAt = o.optLong("at");
        h.heroLabel = o.optString("hl");
        if (o.optJSONObject("hero") != null) h.hero = Title.from(o.optJSONObject("hero"));
        JSONArray rows = o.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            Row row = new Row();
            row.title = r.optString("t");
            row.subtitle = r.optString("s");
            row.ranked = r.optBoolean("r");
            JSONArray items = r.optJSONArray("i");
            for (int j = 0; items != null && j < items.length(); j++) row.items.add(Title.from(items.optJSONObject(j)));
            h.rows.add(row);
        }
        return h;
    }

    /** Movies and shows together, alternating, best popularity first in each. */
    private static List<Title> merge(List<Title> a, List<Title> b) {
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    // MARK: - Discover

    static final class Filter {
        final List<String> genres = new ArrayList<>();
        /** Service names to look in (empty: your subscriptions, when `mine`). */
        final List<String> services = new ArrayList<>();
        int minRating, fromYear, toYear, minVotes = 50, page = 1;
        boolean mine, national;
        String sort = "popularity.desc";

        Filter popular() { sort = "popularity.desc"; mine = false; minVotes = 20; return this; }
    }

    /** One page (20) of movies or shows matching the filter, in Brazil. */
    List<Title> discover(String kind, Filter f) throws Exception {
        StringBuilder q = new StringBuilder("/discover/").append(kind).append("?language=pt-BR&watch_region=BR&include_adult=false")
                .append("&with_watch_monetization_types=flatrate&sort_by=").append(f.sort).append("&page=").append(f.page);
        if (!f.genres.isEmpty()) {
            StringBuilder g = new StringBuilder();
            for (String key : f.genres) {
                String id = genreId(key, kind);
                if (id.isEmpty()) continue;
                if (g.indexOf(id) < 0) g.append(g.length() == 0 ? "" : "|").append(id);   // any of them
            }
            if (g.length() == 0) return new ArrayList<>();
            q.append("&with_genres=").append(URLEncoder.encode(g.toString(), "UTF-8"));
        }
        StringBuilder without = new StringBuilder();
        for (String key : avoid()) {
            String id = genreId(key, kind);
            if (!id.isEmpty() && !f.genres.contains(key)) without.append(without.length() == 0 ? "" : ",").append(id);
        }
        if (without.length() > 0) q.append("&without_genres=").append(without);
        if (f.minRating > 0) q.append("&vote_average.gte=").append(f.minRating);
        if (f.minVotes > 0) q.append("&vote_count.gte=").append(f.minVotes);
        String date = kind.equals("tv") ? "first_air_date" : "primary_release_date";
        if (f.fromYear > 0) q.append("&").append(date).append(".gte=").append(f.fromYear).append("-01-01");
        if (f.toYear > 0) q.append("&").append(date).append(".lte=").append(f.toYear).append("-12-31");
        if (f.mine) {
            String ids = f.services.isEmpty() ? providerIds() : providerIds(f.services);
            if (!ids.isEmpty()) q.append("&with_watch_providers=").append(URLEncoder.encode(ids, "UTF-8"));
        }
        if (f.national) q.append("&with_origin_country=BR");
        JSONObject o = get(q.toString());
        lastTotal = o.optInt("total_results");
        return titles(o.optJSONArray("results"), kind);
    }

    /** total_results of the last discover() (for "1.234 títulos"). */
    volatile int lastTotal;

    /** The best-rated of all time (enough votes to count), optionally of one genre or Brazilian. */
    List<Title> topRated(String kind, String genre, boolean national) throws Exception {
        Filter f = new Filter();
        if (genre != null) f.genres.add(genre);
        f.sort = "vote_average.desc";
        f.minVotes = national ? 100 : kind.equals("tv") ? 800 : 2500;
        f.national = national;
        String q = "/discover/" + kind + "?language=pt-BR&include_adult=false&sort_by=vote_average.desc&vote_count.gte=" + f.minVotes
                + (genre != null && !genreId(genre, kind).isEmpty() ? "&with_genres=" + genreId(genre, kind) : "")
                + (national ? "&with_origin_country=BR" : "")
                + (kind.equals("tv") ? "&without_genres=10767,10763" : "");   // no talk shows or news
        List<Title> out = titles(get(q).optJSONArray("results"), kind);
        for (int i = 0; i < out.size(); i++) out.get(i).rank = i + 1;
        return out;
    }

    /** TMDB's best match for a name (and year, if known): an IPTV item without a TMDB number. */
    int search(String kind, String name, String year) throws Exception {
        String q = "/search/" + kind + "?language=pt-BR&include_adult=false&query=" + URLEncoder.encode(name, "UTF-8")
                + (year.isEmpty() ? "" : (kind.equals("tv") ? "&first_air_date_year=" : "&year=") + year);
        JSONArray a = get(q).optJSONArray("results");
        if ((a == null || a.length() == 0) && !year.isEmpty()) return search(kind, name, "");
        return a == null || a.length() == 0 ? 0 : a.getJSONObject(0).optInt("id");
    }

    /** Movies and shows (and the ones of people) matching a name, most popular first. */
    /** A search's titles and the people (actors, directors) it found. */
    static final class Found {
        final List<Title> titles = new ArrayList<>();
        final List<Person> people = new ArrayList<>();
    }

    Found searchEverything(String query) throws Exception {
        Found f = new Found();
        JSONArray a = get("/search/multi?language=pt-BR&include_adult=false&query=" + URLEncoder.encode(query, "UTF-8")).optJSONArray("results");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (!o.optString("media_type").equals("person") || o.isNull("profile_path")) continue;
            Person p = new Person();
            p.id = o.optInt("id");
            p.name = o.optString("name");
            p.photo = o.optString("profile_path");
            p.role = o.optString("known_for_department").equals("Directing") ? "Direção" : "Atuação";
            f.people.add(p);
        }
        f.titles.addAll(titlesOf(a));
        return f;
    }

    List<Title> searchAll(String query) throws Exception {
        return titlesOf(get("/search/multi?language=pt-BR&include_adult=false&query=" + URLEncoder.encode(query, "UTF-8")).optJSONArray("results"));
    }

    private List<Title> titlesOf(JSONArray a) throws Exception {
        List<Title> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String type = o.optString("media_type");
            if (type.equals("person")) {
                // An actor: the titles they're known for.
                JSONArray known = o.optJSONArray("known_for");
                for (int j = 0; known != null && j < known.length(); j++) {
                    JSONObject k = known.getJSONObject(j);
                    String kt = k.optString("media_type");
                    if ((kt.equals("movie") || kt.equals("tv")) && !k.isNull("poster_path")) out.add(title(k, kt));
                }
            } else if ((type.equals("movie") || type.equals("tv")) && !o.isNull("poster_path")) {
                out.add(title(o, type));
            }
        }
        return out;
    }

    private final Map<String, String> translations = new HashMap<>();

    /**
     * English to Portuguese with MyMemory's free service (no key; about 500 characters a request, so long reviews are
     * translated in pieces, up to ~1500 characters). Null if it can't.
     */
    String translate(String text) {
        synchronized (translations) { if (translations.containsKey(text)) return translations.get(text); }
        StringBuilder out = new StringBuilder();
        try {
            String rest = text.length() > 1500 ? text.substring(0, 1500) : text;
            while (!rest.isEmpty()) {
                int cut = rest.length() <= 480 ? rest.length() : Math.max(rest.lastIndexOf(". ", 480) + 1, rest.lastIndexOf(' ', 480));
                if (cut <= 0) cut = Math.min(480, rest.length());
                String piece = rest.substring(0, cut);
                rest = rest.substring(cut).trim();
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.mymemory.translated.net/get?langpair=en|pt-BR&q="
                        + URLEncoder.encode(piece, "UTF-8")).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    JSONObject o = new JSONObject(b.toString(StandardCharsets.UTF_8.name()));
                    String t = o.optJSONObject("responseData") == null ? "" : o.getJSONObject("responseData").optString("translatedText");
                    if (t.isEmpty() || o.optInt("responseStatus", 200) != 200) return null;
                    out.append(out.length() == 0 ? "" : " ").append(t);
                } finally {
                    c.disconnect();
                }
            }
            if (text.length() > 1500) out.append(" …");
        } catch (Exception e) {
            AppLog.w("translate: " + e.getMessage());
            return null;
        }
        String result = android.text.Html.fromHtml(out.toString(), android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        synchronized (translations) { translations.put(text, result); }
        return result;
    }

    /** What's popular on Brazil's streaming services this week (movies or shows). */
    List<Title> popular(String kind) throws Exception {
        List<Title> out = discover(kind, new Filter().popular());
        for (int i = 0; i < out.size(); i++) out.get(i).rank = i + 1;
        return out;
    }

    private static List<Title> titles(JSONArray list, String kind) {
        List<Title> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Title t = title(o, kind);
            if (!t.poster.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Title title(JSONObject o, String kind) {
        Title t = new Title();
        t.id = o.optInt("id");
        t.kind = o.optString("media_type", kind);
        t.name = o.optString(t.kind.equals("tv") ? "name" : "title", o.optString("name", o.optString("title")));
        t.overview = o.optString("overview");
        t.original = o.optString(t.kind.equals("tv") ? "original_name" : "original_title", "");
        t.poster = o.isNull("poster_path") ? "" : o.optString("poster_path");
        t.backdrop = o.isNull("backdrop_path") ? "" : o.optString("backdrop_path");
        String d = o.optString(t.kind.equals("tv") ? "first_air_date" : "release_date", "");
        t.year = d.length() >= 4 ? d.substring(0, 4) : "";
        t.rating = o.optDouble("vote_average", 0);
        t.votes = o.optInt("vote_count");
        JSONArray g = o.optJSONArray("genre_ids");
        for (int i = 0; g != null && i < g.length(); i++) t.genres.add(g.optInt(i));
        return t;
    }

    // MARK: - Where to watch (one title; cached)

    private final Map<String, List<Provider>> providerCache = new HashMap<>();

    /** The services (in Brazil, subscription first) a title is on; cached for the session. */
    List<Provider> providers(Title t) throws Exception {
        String key = t.kind + t.id;
        synchronized (providerCache) {
            if (providerCache.containsKey(key)) return providerCache.get(key);
        }
        JSONObject br = get("/" + t.kind + "/" + t.id + "/watch/providers").optJSONObject("results");
        List<Provider> out = new ArrayList<>();
        if (br != null) parseProviders(br.optJSONObject("BR"), out, null);
        synchronized (providerCache) { providerCache.put(key, out); }
        return out;
    }

    /** Cached providers (null if not fetched yet). */
    List<Provider> cachedProviders(Title t) {
        synchronized (providerCache) { return providerCache.get(t.kind + t.id); }
    }

    private void parseProviders(JSONObject br, List<Provider> stream, List<Provider> rent) {
        if (br == null) return;
        List<String> mine = subscriptions();
        for (String type : new String[] {"flatrate", "rent"}) {
            JSONArray a = br.optJSONArray(type);
            List<Provider> to = type.equals("flatrate") ? stream : rent;
            if (to == null) continue;
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                Provider p = new Provider();
                p.id = o.optInt("provider_id");
                String known = serviceName(p.id);
                p.name = known != null ? known : o.optString("provider_name").replace(" Amazon Channel", "").replace(" Standard with Ads", "");
                p.logo = o.optString("logo_path");
                p.pkg = servicePkg(p.id);
                if (!names.add(p.name)) continue;
                to.add(p);
            }
            // Your services first.
            java.util.Collections.sort(to, (x, y) -> Boolean.compare(!mine.contains(x.name), !mine.contains(y.name)));
        }
    }

    // MARK: - Details

    Detail detail(String kind, int id) throws Exception {
        List<Review> english = new ArrayList<>();
        String extra = kind.equals("tv") ? "content_ratings" : "release_dates";
        JSONObject o = get("/" + kind + "/" + id + "?language=pt-BR&append_to_response=credits,watch/providers,similar,recommendations," + extra);
        Detail d = new Detail();
        d.t = title(o, kind);
        d.t.kind = kind;
        d.tagline = o.optString("tagline");
        JSONArray genres = o.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) d.genreNames.add(genres.optJSONObject(i).optString("name"));
        if (kind.equals("tv")) {
            int seasons = o.optInt("number_of_seasons");
            d.runtime = seasons + (seasons == 1 ? " temporada" : " temporadas");
            JSONArray by = o.optJSONArray("created_by");
            for (int i = 0; by != null && i < by.length() && i < 2; i++) d.director += (i > 0 ? " e " : "") + by.optJSONObject(i).optString("name");
        } else {
            int min = o.optInt("runtime");
            if (min > 0) d.runtime = min / 60 + "h" + String.format(java.util.Locale.US, "%02d", min % 60);
        }
        JSONObject credits = o.optJSONObject("credits");
        if (credits != null) {
            JSONArray cast = credits.optJSONArray("cast");
            for (int i = 0; cast != null && i < cast.length() && d.cast.size() < 12; i++) {
                JSONObject c = cast.optJSONObject(i);
                Person p = new Person();
                p.id = c.optInt("id");
                p.name = c.optString("name");
                p.role = c.optString("character");
                p.photo = c.isNull("profile_path") ? "" : c.optString("profile_path");
                d.cast.add(p);
            }
            JSONArray crew = credits.optJSONArray("crew");
            if (kind.equals("movie")) {
                List<String> dirs = new ArrayList<>();
                for (int i = 0; crew != null && i < crew.length(); i++) {
                    JSONObject c = crew.optJSONObject(i);
                    if ("Director".equals(c.optString("job")) && dirs.size() < 2) dirs.add(c.optString("name"));
                }
                d.director = join(dirs).replace(",", " e ");
            }
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        if (wp != null && wp.optJSONObject("results") != null) parseProviders(wp.optJSONObject("results").optJSONObject("BR"), d.stream, d.rent);
        synchronized (providerCache) { providerCache.put(kind + id, d.stream); }
        // Certification in Brazil
        try {
            if (kind.equals("tv")) {
                JSONArray r = o.getJSONObject("content_ratings").getJSONArray("results");
                for (int i = 0; i < r.length(); i++) if ("BR".equals(r.getJSONObject(i).optString("iso_3166_1"))) d.certification = r.getJSONObject(i).optString("rating");
            } else {
                JSONArray r = o.getJSONObject("release_dates").getJSONArray("results");
                for (int i = 0; i < r.length(); i++) {
                    if (!"BR".equals(r.getJSONObject(i).optString("iso_3166_1"))) continue;
                    JSONArray rd = r.getJSONObject(i).getJSONArray("release_dates");
                    for (int j = 0; j < rd.length(); j++) if (!rd.getJSONObject(j).optString("certification").isEmpty()) d.certification = rd.getJSONObject(j).optString("certification");
                }
            }
        } catch (Exception ignored) {}
        for (String list : new String[] {"recommendations", "similar"}) {
            JSONObject s = o.optJSONObject(list);
            if (s == null) continue;
            for (Title t : titles(s.optJSONArray("results"), kind)) if (d.similar.size() < 12) d.similar.add(t);
            if (d.similar.size() >= 6) break;
        }
        // Reviews: Portuguese first; then English ones (the screen translates them), for shows too, which rarely have any in Portuguese.
        try {
            JSONArray r = get("/" + kind + "/" + id + "/reviews?language=pt-BR").optJSONArray("results");
            JSONArray en = d.reviews.size() < 6 ? get("/" + kind + "/" + id + "/reviews?language=en-US").optJSONArray("results") : null;
            for (int i = 0; en != null && i < en.length(); i++) {
                JSONObject x = en.optJSONObject(i);
                if (r == null) r = new JSONArray();
                boolean dup = false;
                for (int j = 0; j < r.length(); j++) if (r.optJSONObject(j).optString("id").equals(x.optString("id"))) dup = true;
                if (!dup) r.put(x);
            }
            for (int i = 0; r != null && i < r.length() && d.reviews.size() < 6; i++) {
                JSONObject x = r.optJSONObject(i);
                Review rv = new Review();
                rv.author = x.optString("author");
                rv.text = x.optString("content").replaceAll("\\s+", " ").trim();
                String date = x.optString("created_at");
                rv.date = date.length() >= 10 ? date.substring(8, 10) + "/" + date.substring(5, 7) + "/" + date.substring(0, 4) : "";
                JSONObject a = x.optJSONObject("author_details");
                rv.rating = a != null ? a.optDouble("rating", 0) : 0;
                if (looksPortuguese(rv.text)) d.reviews.add(rv);
                else if (rv.text.length() > 40) { rv.english = true; english.add(rv); }
            }
        } catch (Exception e) {
            AppLog.w("movies reviews: " + e.getMessage());
        }
        for (int i = 0; i < english.size() && d.reviews.size() < 6; i++) d.reviews.add(english.get(i));
        return d;
    }

    /** TMDB's language filter on reviews isn't reliable: a quick check for common Portuguese words. */
    private static boolean looksPortuguese(String s) {
        String t = " " + s.toLowerCase(java.util.Locale.ROOT) + " ";
        int hits = 0;
        for (String w : new String[] {" não ", " que ", " uma ", " filme ", " muito ", " é ", " mas ", " com ", " série ", " para ", " história "}) if (t.contains(w)) hits++;
        return hits >= 3;
    }

    // MARK: - Network

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("TMDB HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            c.disconnect();
        }
    }

    /** "8,1" */
    static String rating(double r) {
        return r <= 0 ? "—" : String.format(new java.util.Locale("pt", "BR"), "%.1f", r);
    }

    /** "12 mil votos", "850 votos" */
    static String votes(int v) {
        return v >= 1000 ? (v / 1000) + " mil votos" : v + " votos";
    }
}
