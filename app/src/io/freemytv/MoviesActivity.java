package io.freemytv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static io.freemytv.TvUi.MATCH;
import static io.freemytv.TvUi.WRAP;

/**
 * "Movies & Shows": Explore (the week's highlight and rows from your preferences), Discover (filters and a grid),
 * Rankings (popular now, best of all time, by genre) and Preferences (favourite genres, genres to avoid, minimum
 * rating, your streaming services). Focusing a tab shows it. OK on a title opens its details.
 */
public class MoviesActivity extends Activity implements Movies.Listener, Iptv.Listener, Voice.Screen {
    private TvUi ui;
    private Movies movies;
    private FrameLayout content;
    private final List<TvUi.Chip> tabs = new ArrayList<>();
    private final List<Integer> tabIds = new ArrayList<>();
    private int tab = -1;
    // Tab ids. On screen: Buscar, Para você (Explore), Ao vivo, Filmes, Séries (your IPTV lists), Descobrir, Ajustes;
    // without IPTV lists: Buscar, Para você, Descobrir, Rankings, Ajustes.
    static final int T_EXPLORE = 0, T_DISCOVER = 1, T_RANKINGS = 2, T_PREFS = 3, T_IPTV_MOVIES = 4, T_IPTV_SHOWS = 5, T_SEARCH = 6, T_LIVE = 7;
    private LivePanel live;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The add-a-list form is on screen: don't rebuild it under the person typing. */
    private boolean typing;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        ui = new TvUi(this);
        movies = Movies.get(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ui.bg);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ui.bg));   // behind the keyboard: not white
        LinearLayout page = ui.column();
        page.setPadding(ui.px(80), ui.px(40), ui.px(80), 0);
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout head = ui.row();
        head.addView(ui.text("Assistir", 44, Ui.semibold, ui.ink));
        LinearLayout tabBar = ui.row();
        tabBar.setPadding(ui.px(28), 0, 0, 0);
        List<Object[]> names = new ArrayList<>();
        boolean lists = Iptv.get(this).connected();
        names.add(new Object[] {"Buscar", T_SEARCH});
        names.add(new Object[] {"Para você", T_EXPLORE});
        if (lists) {
            names.add(new Object[] {"Ao vivo", T_LIVE});
            names.add(new Object[] {"Filmes", T_IPTV_MOVIES});
            names.add(new Object[] {"Séries", T_IPTV_SHOWS});
        }
        names.add(new Object[] {"Descobrir", T_DISCOVER});
        if (!lists) names.add(new Object[] {"Rankings", T_RANKINGS});
        names.add(new Object[] {"Ajustes", T_PREFS});
        for (Object[] n : names) {
            final int id = (Integer) n[1];
            TvUi.Chip c = ui.new Chip((String) n[0], 22, false);
            // Only the arrows change the tab: when the focused view disappears (a button that removes itself),
            // Android hands the focus to the first tab, and that mustn't open Buscar.
            c.setOnFocusChangeListener((v, f) -> {
                c.paint();
                if (f && (tab == -1 || android.os.SystemClock.uptimeMillis() - keyAt < 600)) show(id);
            });
            tabs.add(c);
            tabIds.add(id);
            tabBar.addView(c, ui.margins(WRAP, WRAP, 0, 0, 10, 0));
        }
        head.addView(tabBar);
        if (lists) {
            // Mosaico: 2 to 4 live channels at once, from anywhere in Assistir (→ after the last tab).
            head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TvUi.Chip mosaic = ui.button("▦  Mosaico", false, () -> startActivity(new android.content.Intent(this, MultiActivity.class)));
            head.addView(mosaic);
        }
        page.addView(head, ui.margins(MATCH, ui.px(80), 0, 0, 0, 0));
        content = new FrameLayout(this);
        page.addView(content, ui.margins(MATCH, 0, 0, 20, 0, 0));
        ((LinearLayout.LayoutParams) content.getLayoutParams()).weight = 1;
        setContentView(root);
        show(getIntent().getIntExtra("tab", T_EXPLORE));
        tabs.get(Math.max(0, tabIds.indexOf(tab))).requestFocus();
        if (getIntent().getBooleanExtra("voice", false)) root.post(this::voiceSearch);
        if (Iptv.get(this).connected()) movies.run(() -> Lineup.get(this), l -> {});   // channels ready for the search
    }

    @Override public boolean onSearchRequested(android.view.SearchEvent e) { voiceSearch(); return true; }

    @Override protected void onResume() {
        super.onResume();
        Voice.resumed(this);
        movies.addListener(this);
        Iptv.get(this).addListener(this);
        Iptv.get(this).refresh(false);
        movies.refreshHome(false);
    }

    @Override protected void onStop() {
        Voice.stopped(this);
        super.onStop();
    }

    @Override protected void onPause() {
        Voice.cancel();
        movies.removeListener(this);
        Iptv.get(this).removeListener(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (live != null) live.destroy();
        super.onDestroy();
    }

    @Override public void onIptv() {
        if (tab == T_LIVE && live != null) live.onIptv();
        // The list's status changed (catalog downloaded): the Preferences panel shows it; the form isn't rebuilt mid-typing.
        if (tab == T_PREFS && Iptv.get(this).connected() && !typing) rebuild();
        if ((tab == T_IPTV_MOVIES || tab == T_IPTV_SHOWS) && content.getChildCount() > 0 && content.getChildAt(0).getTag() == null) rebuild();
    }

    @Override public void onMovies() {
        if (tab == T_EXPLORE || (tab == T_PREFS && !typing)) rebuild();
    }

    private void show(int index) {
        if (index == tab) return;
        tab = index;
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).set(tabIds.get(i) == index);
        rebuild();
    }

    private void rebuild() {
        content.removeAllViews();
        if (live != null) { live.destroy(); live = null; }
        View v;
        typing = false;
        switch (tab) {
            case T_LIVE:
                live = new LivePanel(this, ui, getIntent().getStringExtra("group"));
                getIntent().removeExtra("group");
                v = live.view;
                break;
            case T_DISCOVER: v = new Discover().view; break;
            case T_RANKINGS: v = new Rankings().view; break;
            case T_PREFS: v = preferences(); break;
            case T_IPTV_MOVIES: v = new IptvBrowser("movie").view; break;
            case T_IPTV_SHOWS: v = new IptvBrowser("tv").view; break;
            case T_SEARCH: v = new Search().view; break;
            default: v = explore(); break;
        }
        content.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    // MARK: - Explore

    private View explore() {
        Movies.Home home = movies.home;
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, ui.px(10), 0, ui.px(60));
        LinearLayout col = ui.column();
        col.setClipChildren(false);
        scroll.addView(col);
        if (home == null) {
            col.addView(ui.text(Config.TMDB_KEY.isEmpty() ? "Falta a chave do TMDB no config.properties." : "Carregando sugestões…", 26, Ui.medium, ui.dim));
            return scroll;
        }
        if (home.hero != null) col.addView(hero(home.hero, home.heroLabel), ui.margins(MATCH, ui.px(440), 0, 0, 0, 30));
        // What's yours first: where you stopped, your channels, your list; then the week.
        View going = resumeRow();
        if (going != null) col.addView(going);
        Lineup lineup = Lineup.ready(this);
        if (lineup != null && !lineup.favourites().isEmpty())
            col.addView(channelRow("Seus canais", "favoritos · ao vivo agora", lineup.favourites(), "Seus canais", () -> openLive(LivePanel.FAV)),
                    ui.margins(MATCH, WRAP, 0, 20, 0, 0));
        List<Movies.Title> mine = movies.myList();
        if (!mine.isEmpty()) col.addView(posterRow("Minha lista", "Seus favoritos · “+ Minha lista” nos detalhes de um filme ou série", mine, false));
        for (Movies.Row r : home.rows) col.addView(posterRow(r.title, r.subtitle, r.items, r.ranked));
        return scroll;
    }

    private View hero(Movies.Title t, String label) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(TvUi.round(0xFF1A2230, 32 * ui.k));
        box.setClipToOutline(true);
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Img.into(back, Img.tmdb(t.backdrop, "w1280"), ui.px(1760));
        box.addView(back, new FrameLayout.LayoutParams(MATCH, MATCH));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF20A0C12, 0xB30A0C12, 0x1A0A0C12}));
        box.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout info = ui.column();
        info.setPadding(ui.px(52), ui.px(44), ui.px(52), ui.px(40));
        info.addView(ui.text(label, 19, Ui.semibold, TvUi.ACCENT));
        info.addView(ui.line(t.name, 64, Ui.semibold, 0xFFFFFFFF, 1), ui.margins(ui.px(1100), WRAP, 0, 14, 0, 0));
        String meta = t.kindLabel() + (t.year.isEmpty() ? "" : " · " + t.year) + (Movies.firstGenre(t).isEmpty() ? "" : " · " + Movies.firstGenre(t));
        info.addView(ui.text(meta, 22, Ui.medium, 0xBFFFFFFF), ui.margins(WRAP, WRAP, 0, 14, 0, 0));
        LinearLayout chips = ui.row();
        TextView score = ui.text("TMDB " + Movies.rating(t.rating), 19, Ui.semibold, 0xFF0D253F);
        score.setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(8));
        score.setBackground(TvUi.round(0xFF01B4E4, 10 * ui.k));
        chips.addView(score);
        TextView votes = ui.text(Movies.votes(t.votes), 19, Ui.semibold, 0xFFFFFFFF);
        votes.setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(8));
        votes.setBackground(TvUi.round(0x24FFFFFF, 10 * ui.k));
        chips.addView(votes, ui.margins(WRAP, WRAP, 10, 0, 0, 0));
        TextView where = ui.text("", 19, Ui.semibold, 0xFFFFFFFF);
        where.setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(8));
        where.setBackground(TvUi.round(0x24FFFFFF, 10 * ui.k));
        where.setVisibility(View.GONE);
        chips.addView(where, ui.margins(WRAP, WRAP, 10, 0, 0, 0));
        info.addView(chips, ui.margins(WRAP, WRAP, 0, 16, 0, 0));
        info.addView(ui.line(t.overview, 22, Ui.regular, 0xD9FFFFFF, 3), ui.margins(ui.px(900), WRAP, 0, 16, 0, 0));
        LinearLayout buttons = ui.row();
        TvUi.Chip watch = ui.button("Ver onde assistir", true, () -> TvUi.open(this, t));
        TvUi.Chip more = ui.button("Mais detalhes", false, () -> TvUi.open(this, t));
        more.setOnFocusChangeListener((v, f) -> { more.paint(); if (!f) { more.setBackground(TvUi.round(0x33FFFFFF, 23 * 0.6f * ui.k)); more.setTextColor(0xFFFFFFFF); } });
        more.setBackground(TvUi.round(0x33FFFFFF, 23 * 0.6f * ui.k));
        more.setTextColor(0xFFFFFFFF);
        buttons.addView(watch);
        buttons.addView(more, ui.margins(WRAP, WRAP, 14, 0, 0, 0));
        info.addView(buttons, ui.margins(WRAP, WRAP, 0, 20, 0, 0));
        box.addView(info, new FrameLayout.LayoutParams(MATCH, MATCH));
        movies.run(() -> movies.providers(t), list -> {
            if (list == null || list.isEmpty()) return;
            Movies.Provider p = list.get(0);
            where.setText(p.name);
            where.setVisibility(View.VISIBLE);
            if (p.pkg != null) {
                watch.setText("Assistir no " + p.name);
                watch.setOnClickListener(v -> HomeApps.open(this, HomeApps.app(p.pkg, p.name), v, 0, 0, v.getWidth(), v.getHeight()));
            }
        });
        return box;
    }

    /** Continue watching: what you stopped in the middle of, with how much is left; OK picks it up. */
    private View resumeRow() {
        List<org.json.JSONObject> list = PlayerActivity.Resume.list(this, 10);
        if (list.isEmpty()) return null;
        LinearLayout col = ui.column();
        col.setClipChildren(false);
        LinearLayout head = ui.row();
        head.addView(ui.text("Continuar assistindo", 30, Ui.semibold, ui.ink));
        head.addView(ui.text("das suas listas", 21, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 16, 0, 0, 0));
        col.addView(head, ui.margins(WRAP, WRAP, 4, 20, 0, 18));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(ui.px(10), ui.px(16), ui.px(60), ui.px(24));
        LinearLayout line = ui.row();
        line.setClipChildren(false);
        line.setGravity(Gravity.TOP);
        for (org.json.JSONObject o : list) {
            LinearLayout card = ui.column();
            card.setFocusable(true);
            card.setClickable(true);
            FrameLayout tile = new FrameLayout(this);
            tile.setBackground(TvUi.round(ui.chip, 18 * ui.k));
            tile.setClipToOutline(true);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Img.into(img, o.optString("image"), ui.px(176));
            tile.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
            float done = o.optInt("dur") > 0 ? o.optInt("pos") / (float) o.optInt("dur") : 0;
            View track = new View(this);
            track.setBackgroundColor(0x66FFFFFF);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(MATCH, ui.px(6), Gravity.BOTTOM);
            tile.addView(track, tp);
            View bar = new View(this);
            bar.setBackgroundColor(TvUi.ACCENT);
            tile.addView(bar, new FrameLayout.LayoutParams(Math.round(ui.px(176) * done), ui.px(6), Gravity.BOTTOM | Gravity.START));
            card.addView(tile, new LinearLayout.LayoutParams(ui.px(176), ui.px(264)));
            int left = Math.max(1, (o.optInt("dur") - o.optInt("pos")) / 60000);
            card.addView(ui.line(o.optString("title"), 18, Ui.semibold, ui.ink, 1), ui.margins(ui.px(176), WRAP, 0, 10, 0, 0));
            card.addView(ui.line("faltam " + left + " min", 15, Ui.medium, ui.dim, 1), ui.margins(ui.px(176), WRAP, 0, 3, 0, 0));
            card.setOnFocusChangeListener((v, f) -> card.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start());
            card.setOnClickListener(v -> {
                org.json.JSONArray next;
                try { next = new org.json.JSONArray(o.optString("next", "[]")); } catch (Exception e) { next = new org.json.JSONArray(); }
                PlayerActivity.play(this, o.optString("url"), o.optString("title"), o.optString("sub"), o.optString("key"),
                        o.optString("image"), o.optString("kind"), o.optInt("tmdb"), next);
            });
            line.addView(card, ui.margins(ui.px(176), WRAP, 0, 0, 24, 0));
        }
        hs.addView(line);
        col.addView(hs);
        return col;
    }

    private View posterRow(String title, String subtitle, List<Movies.Title> items, boolean ranked) {
        LinearLayout col = ui.column();
        col.setClipChildren(false);
        LinearLayout head = ui.row();
        head.addView(ui.text(title, 30, Ui.semibold, ui.ink));
        head.addView(ui.text(subtitle, 21, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 16, 0, 0, 0));
        col.addView(head, ui.margins(WRAP, WRAP, 4, 20, 0, 18));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(ui.px(10), ui.px(16), ui.px(60), ui.px(24));
        LinearLayout line = ui.row();
        line.setClipChildren(false);
        line.setGravity(Gravity.BOTTOM);
        for (Movies.Title t : items) {
            if (ranked) {
                TextView n = ui.text(String.valueOf(t.rank), 150, Ui.semibold, ui.dark ? 0x26FFFFFF : 0x24000000);
                line.addView(n, ui.margins(WRAP, WRAP, 0, 0, -18, -10));
            }
            TvUi.Poster p = ui.new Poster(t, ui.px(176), ui.px(264), true);
            p.setOnClickListener(v -> TvUi.open(this, t));
            line.addView(p, ui.margins(ui.px(176), ui.px(264), 0, 0, 24, 0));
        }
        hs.addView(line);
        col.addView(hs);
        return col;
    }

    /** A row of live channels (each once, with the lists it's in); OK plays it, ▲▼ in the player go through the row. */
    View channelRow(String title, String subtitle, List<Lineup.Channel> chans, String playTitle, Runnable more) {
        LinearLayout box = ui.column();
        box.setClipChildren(false);
        LinearLayout head = ui.row();
        head.addView(ui.text(title, 28, Ui.semibold, ui.ink));
        head.addView(ui.text(subtitle, 20, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 14, 0, 0, 0));
        box.addView(head, ui.margins(WRAP, WRAP, 4, 6, 0, 10));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(MoviesActivity.this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(ui.px(10), ui.px(14), ui.px(60), ui.px(24));
        LinearLayout line = ui.row();
        line.setGravity(Gravity.TOP);
        line.setClipChildren(false);
        for (int i = 0; i < chans.size(); i++) {
            final int index = i;
            Lineup.Channel ch = chans.get(i);
            LinearLayout card = ui.column();
            card.setFocusable(true);
            card.setClickable(true);
            FrameLayout tile = new FrameLayout(MoviesActivity.this);
            android.graphics.drawable.GradientDrawable normal = TvUi.round(ui.chip, 18 * ui.k), on = TvUi.round(0xFFFFFFFF, 18 * ui.k);
            on.setStroke(ui.px(4), TvUi.ACCENT);
            tile.setBackground(normal);
            ImageView img = new ImageView(MoviesActivity.this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setPadding(ui.px(22), ui.px(18), ui.px(22), ui.px(18));
            Img.into(img, ch.icon, ui.px(240));
            tile.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
            int st = ch.state(System.currentTimeMillis());
            TextView live = ui.text(st == Lineup.PRE ? "HOJE " + ch.time() : st == Lineup.ENDED ? "ENCERRADO" : "AO VIVO", 13, Ui.semibold, 0xFFFFFFFF);
            live.setPadding(ui.px(8), ui.px(3), ui.px(8), ui.px(3));
            live.setBackground(TvUi.round(st == Lineup.PRE ? 0xCC2C2C2E : st == Lineup.ENDED ? 0x995A5A5E : 0xFFE5252A, 6 * ui.k));
            if (st == Lineup.ENDED) card.setAlpha(0.55f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
            lp.setMargins(ui.px(10), ui.px(10), 0, 0);
            tile.addView(live, lp);
            card.addView(tile, new LinearLayout.LayoutParams(ui.px(240), ui.px(136)));
            card.addView(ui.line(ch.name, 19, Ui.semibold, ui.ink, 1), ui.margins(ui.px(240), WRAP, 0, 10, 0, 0));
            card.addView(ui.line(ch.meta(), 15, Ui.medium, ui.dim, 1), ui.margins(ui.px(240), WRAP, 0, 2, 0, 0));
            card.setOnFocusChangeListener((v, f) -> {
                tile.setBackground(f ? on : normal);
                card.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start();
            });
            card.setOnClickListener(v -> { if (!Lineup.notNow(MoviesActivity.this, ch)) PlayerActivity.live(MoviesActivity.this, Lineup.toJson(chans), index, playTitle); });
            line.addView(card, ui.margins(ui.px(240), WRAP, 0, 0, 24, 0));
        }
        if (more != null) {
            TvUi.Chip all = ui.chip("Ver todos", false, null);
            all.setOnClickListener(v -> more.run());
            line.addView(all, ui.margins(WRAP, WRAP, 0, 44, 24, 0));
        }
        hs.addView(line);
        box.addView(hs);
        return box;
    }


    // MARK: - Discover

    interface Getter { String get(); }
    interface Setter { void set(String v); }

    private final class Discover {
        final LinearLayout view = ui.row();
        private final LinearLayout grid = ui.column();
        private final TextView count = ui.text("", 30, Ui.semibold, ui.ink), summary = ui.text("", 21, Ui.medium, ui.dim);
        private String kind = "movie", sort = "popularity.desc";
        private int min = 7;
        private boolean onlyIptv;
        private final List<String> genres = new ArrayList<>(), eras = new ArrayList<>(), services = new ArrayList<>();
        private int generation;

        Discover() {
            view.setGravity(Gravity.TOP);
            services.addAll(movies.subscriptions());
            ScrollView filters = new ScrollView(MoviesActivity.this);
            filters.setVerticalScrollBarEnabled(false);
            filters.setClipToPadding(false);
            LinearLayout f = ui.column();
            f.setPadding(ui.px(28), ui.px(26), ui.px(28), ui.px(26));
            GradientDrawable d = TvUi.round(ui.panel, 28 * ui.k);
            d.setStroke(ui.px(1.5f), ui.panelLine);
            f.setBackground(d);
            filters.addView(f);
            group(f, "TIPO", new String[] {"Filmes", "Séries"}, new String[] {"movie", "tv"}, true, () -> kind, v -> { kind = v; load(); });
            List<String> keys = new ArrayList<>(), names = new ArrayList<>();
            for (String[] g : Movies.GENRES) { keys.add(g[0]); names.add(g[1]); }
            multi(f, "GÊNEROS", names, keys, genres);
            group(f, "NOTA MÍNIMA", new String[] {"Todas", "6+", "7+", "8+"}, new String[] {"0", "6", "7", "8"}, true, () -> String.valueOf(min), v -> { min = Integer.parseInt(v); load(); });
            List<String> eraNames = java.util.Arrays.asList("2020s", "2010s", "2000s", "Anos 90", "Clássicos");
            multi(f, "ÉPOCA", eraNames, eraNames, eras);
            List<String> svc = new ArrayList<>();
            for (Object[] s : Movies.SERVICES) svc.add((String) s[0]);
            multi(f, "NOS STREAMINGS", svc, svc, services);
            if (Iptv.get(MoviesActivity.this).connected()) {
                label(f, "IPTV");
                TvUi.Chip mine = ui.chip("Só o que tem no meu IPTV", false, null);
                mine.setOnClickListener(x -> { onlyIptv = !onlyIptv; mine.set(onlyIptv); load(); });
                wrapRow(f).addView(mine);
            }
            group(f, "ORDENAR POR", new String[] {"Populares", "Melhor nota", "Recentes"}, new String[] {"popularity.desc", "vote_average.desc", "date"}, true, () -> sort, v -> { sort = v; load(); });
            view.addView(filters, ui.margins(ui.px(450), MATCH, 0, 0, 32, 30));
            LinearLayout right = ui.column();
            right.setClipChildren(false);
            LinearLayout head = ui.row();
            head.addView(count);
            head.addView(summary, ui.margins(WRAP, WRAP, 16, 0, 0, 0));
            right.addView(head, ui.margins(MATCH, WRAP, 0, 4, 0, 16));
            ScrollView gs = new ScrollView(MoviesActivity.this);
            gs.setVerticalScrollBarEnabled(false);
            gs.setClipChildren(false);
            gs.setClipToPadding(true);
            gs.setPadding(ui.px(14), ui.px(18), ui.px(14), ui.px(18));
            grid.setClipChildren(false);
            gs.addView(grid);
            right.addView(gs, new LinearLayout.LayoutParams(MATCH, MATCH));
            view.addView(right, new LinearLayout.LayoutParams(0, MATCH, 1));
            load();
        }

        private void label(LinearLayout f, String s) {
            f.addView(ui.text(s, 16, Ui.semibold, ui.dim), ui.margins(WRAP, WRAP, 0, 18, 0, 12));
        }

        private LinearLayout wrapRow(LinearLayout f) {
            LinearLayout r = ui.row();
            f.addView(r, ui.margins(MATCH, WRAP, 0, 0, 0, 10));
            return r;
        }

        /** One choice among a few. */
        private void group(LinearLayout f, String title, String[] labels, String[] values, boolean single, Getter get, Setter set) {
            label(f, title);
            LinearLayout r = wrapRow(f);
            List<TvUi.Chip> chips = new ArrayList<>();
            for (int i = 0; i < labels.length; i++) {
                String v = values[i];
                TvUi.Chip c = ui.chip(labels[i], v.equals(get.get()), null);
                c.setOnClickListener(x -> { set.set(v); for (int j = 0; j < chips.size(); j++) chips.get(j).set(values[j].equals(v)); });
                chips.add(c);
                r.addView(c, ui.margins(WRAP, WRAP, 0, 0, 10, 0));
            }
        }

        /** Several on or off, in rows of up to three. */
        private void multi(LinearLayout f, String title, List<String> labels, List<String> values, List<String> on) {
            label(f, title);
            LinearLayout r = null;
            float used = 999;
            for (int i = 0; i < labels.size(); i++) {
                String v = values.get(i);
                TvUi.Chip c = ui.chip(labels.get(i), on.contains(v), null);
                c.setOnClickListener(x -> {
                    if (on.contains(v)) on.remove(v); else on.add(v);
                    c.set(on.contains(v));
                    load();
                });
                float w = c.getPaint().measureText(labels.get(i)) + ui.px(48);
                if (r == null || used + w > ui.px(390)) { r = wrapRow(f); used = 0; }
                r.addView(c, ui.margins(WRAP, WRAP, 0, 0, 10, 0));
                used += w + ui.px(10);
            }
        }

        private final Runnable reload = this::fetch;

        private void load() {
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 450);
        }

        private void fetch() {
            int gen = ++generation;
            Movies.Filter f = new Movies.Filter();
            f.genres.addAll(genres);
            f.minRating = min;
            f.minVotes = sort.equals("vote_average.desc") ? 300 : 50;
            f.sort = sort.equals("date") ? (kind.equals("tv") ? "first_air_date.desc" : "primary_release_date.desc") : sort;
            int from = 0, to = 0;
            for (String e : eras) {
                int a, b;
                switch (e) {
                    case "2020s": a = 2020; b = Calendar.getInstance().get(Calendar.YEAR); break;
                    case "2010s": a = 2010; b = 2019; break;
                    case "2000s": a = 2000; b = 2009; break;
                    case "Anos 90": a = 1990; b = 1999; break;
                    default: a = 1900; b = 1989; break;
                }
                from = from == 0 ? a : Math.min(from, a);
                to = Math.max(to, b);
            }
            if (sort.equals("date")) to = to == 0 ? Calendar.getInstance().get(Calendar.YEAR) : to;
            f.fromYear = from;
            f.toYear = to;
            f.services.addAll(services);
            f.mine = !services.isEmpty();
            StringBuilder s = new StringBuilder();
            for (String g : genres) s.append(s.length() == 0 ? "" : " + ").append(Movies.genreName(g));
            if (s.length() == 0) s.append("Todos os gêneros");
            if (min > 0) s.append(" · ★ ").append(min).append("+");
            if (from > 0) s.append(" · ").append(from).append("–").append(to);
            if (!services.isEmpty()) s.append(" · ").append(services.size() == 1 ? services.get(0) : services.size() + " streamings");
            summary.setText(s);
            count.setText("Buscando…");
            String k = kind;
            movies.run(() -> {
                List<Movies.Title> all = new ArrayList<>();
                for (int p = 1; p <= (onlyIptv ? 8 : 3); p++) {
                    f.page = p;
                    List<Movies.Title> page = movies.discover(k, f);
                    all.addAll(page);
                    if (page.size() < 20) break;
                }
                return all;
            }, list -> {
                if (gen != generation) return;
                show(list == null ? new ArrayList<>() : list);
            });
        }

        private void show(List<Movies.Title> list) {
            grid.removeAllViews();
            int total = movies.lastTotal;
            if (onlyIptv) {
                Iptv iptv = Iptv.get(MoviesActivity.this);
                List<Movies.Title> mine = new ArrayList<>();
                for (Movies.Title t : list) if (iptv.has(t)) mine.add(t);
                list = mine;
                total = mine.size();
            }
            count.setText(list.isEmpty() ? "Nada encontrado" : total > list.size() ? total + " títulos" : list.size() + " títulos");
            LinearLayout r = null;
            int w = ui.px(178);
            for (int i = 0; i < list.size(); i++) {
                if (i % 6 == 0) {
                    r = ui.row();
                    r.setGravity(Gravity.TOP);
                    r.setClipChildren(false);
                    grid.addView(r, ui.margins(WRAP, WRAP, 0, 0, 0, 30));
                }
                Movies.Title t = list.get(i);
                r.addView(ui.card(t, w, () -> TvUi.open(MoviesActivity.this, t)), ui.margins(w, WRAP, 0, 0, 24, 0));
            }
        }
    }

    // MARK: - Rankings

    private final class Rankings {
        final LinearLayout view = ui.column();
        private final LinearLayout cols = ui.row();
        private String genre;
        private boolean national;
        private int generation;

        Rankings() {
            LinearLayout chips = ui.row();
            chips.addView(ui.text("RANKING POR GÊNERO", 17, Ui.semibold, ui.dim), ui.margins(WRAP, WRAP, 0, 0, 18, 0));
            String[][] options = {{"Todos", null}, {"Suspense", "suspense"}, {"Ficção científica", "ficcao"}, {"Comédia", "comedia"},
                    {"Drama", "drama"}, {"Terror", "terror"}, {"Animação", "animacao"}, {"Crime", "crime"}, {"Nacionais", "BR"}};
            List<TvUi.Chip> all = new ArrayList<>();
            for (String[] o : options) {
                TvUi.Chip c = ui.chip(o[0], o[1] == null, null);
                c.setOnClickListener(v -> {
                    national = "BR".equals(o[1]);
                    genre = national ? null : o[1];
                    for (TvUi.Chip x : all) x.set(x == c);
                    load();
                });
                all.add(c);
                chips.addView(c, ui.margins(WRAP, WRAP, 0, 0, 10, 0));
            }
            view.addView(chips, ui.margins(MATCH, WRAP, 0, 0, 0, 22));
            cols.setGravity(Gravity.TOP);
            view.addView(cols, new LinearLayout.LayoutParams(MATCH, 0, 1));
            load();
        }

        private void load() {
            int gen = ++generation;
            cols.removeAllViews();
            String[] titles = {"POPULARES NOS STREAMINGS", "FILMES MAIS BEM AVALIADOS", "SÉRIES MAIS BEM AVALIADAS"};
            String[] subs = {"Esta semana, no Brasil", "De todos os tempos, nota dos usuários", "De todos os tempos, nota dos usuários"};
            for (int i = 0; i < 3; i++) {
                LinearLayout col = ui.column();
                col.addView(ui.text(titles[i], 17, Ui.semibold, ui.dim));
                col.addView(ui.text(subs[i], 17, Ui.medium, ui.faint), ui.margins(WRAP, WRAP, 0, 6, 0, 10));
                View box = ui.panel(col, 26);
                cols.addView(box, new LinearLayout.LayoutParams(0, MATCH, 1));
                if (i < 2) ((LinearLayout.LayoutParams) box.getLayoutParams()).rightMargin = ui.px(28);
                final int which = i;
                movies.run(() -> {
                    if (which == 0) {
                        if (genre == null && !national) return merge10(movies.popular("movie"), movies.popular("tv"));
                        Movies.Filter f = new Movies.Filter().popular();
                        if (genre != null) f.genres.add(genre);
                        f.national = national;
                        return rank(merge10(movies.discover("movie", f), movies.discover("tv", f)));
                    }
                    return movies.topRated(which == 1 ? "movie" : "tv", genre, national);
                }, list -> {
                    if (gen != generation) return;
                    if (list == null || list.isEmpty()) {
                        col.addView(ui.text("Sem títulos aqui.", 20, Ui.medium, ui.dim));
                        return;
                    }
                    for (int j = 0; j < list.size() && j < 10; j++) col.addView(rankRow(list.get(j)), ui.margins(MATCH, ui.px(70), 0, 0, 0, 0));
                });
            }
        }

        private List<Movies.Title> merge10(List<Movies.Title> a, List<Movies.Title> b) {
            List<Movies.Title> out = new ArrayList<>();
            for (int i = 0; out.size() < 10 && i < Math.max(a.size(), b.size()); i++) {
                if (i < a.size()) out.add(a.get(i));
                if (i < b.size() && out.size() < 10) out.add(b.get(i));
            }
            return rank(out);
        }

        private List<Movies.Title> rank(List<Movies.Title> l) {
            for (int i = 0; i < l.size(); i++) l.get(i).rank = i + 1;
            return l;
        }

        private View rankRow(Movies.Title t) {
            LinearLayout r = ui.row();
            r.setFocusable(true);
            r.setClickable(true);
            r.setPadding(ui.px(8), 0, ui.px(8), 0);
            r.setOnClickListener(v -> TvUi.open(MoviesActivity.this, t));
            r.setOnFocusChangeListener((v, f) -> v.setBackground(f ? TvUi.round(0xFFFFFFFF, 14 * ui.k) : null));
            TextView n = ui.text(String.valueOf(t.rank), 30, Ui.semibold, t.rank <= 3 ? ui.ink : ui.faint);
            n.setGravity(Gravity.END);
            r.addView(n, TvUi.lp(ui.px(44), WRAP));
            ImageView poster = new ImageView(MoviesActivity.this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(TvUi.round(ui.dark ? 0xFF2A2D35 : 0xFFCDD1D8, 8 * ui.k));
            poster.setClipToOutline(true);
            Img.into(poster, Img.tmdb(t.poster, "w92"), ui.px(40));
            r.addView(poster, ui.margins(ui.px(40), ui.px(58), 16, 0, 16, 0));
            LinearLayout text = ui.column();
            text.addView(ui.line(t.name, 20, Ui.semibold, ui.ink, 1));
            String meta = (t.year.isEmpty() ? t.kindLabel() : t.year) + (Movies.firstGenre(t).isEmpty() ? "" : " · " + Movies.firstGenre(t));
            text.addView(ui.line(meta, 16, Ui.medium, ui.dim, 1), ui.margins(WRAP, WRAP, 0, 4, 0, 0));
            r.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1));
            TextView rate = ui.text("★ " + Movies.rating(t.rating), 18, Ui.semibold, 0xFFFFFFFF);
            rate.setPadding(ui.px(10), ui.px(7), ui.px(10), ui.px(7));
            rate.setBackground(TvUi.round(t.rating >= 8.5 ? 0xFF0A7A3F : t.rating >= 7.5 ? 0xFF2E8B57 : 0xFF8A6D1F, 9 * ui.k));
            r.addView(rate);
            // Focused rows go dark on white: recolor the texts.
            r.setOnFocusChangeListener((v, f) -> {
                v.setBackground(f ? TvUi.round(0xFFFFFFFF, 14 * ui.k) : null);
                int ink = f ? 0xFF1C1C1E : ui.ink, dim = f ? 0xFF6B6B70 : ui.dim;
                ((TextView) text.getChildAt(0)).setTextColor(ink);
                ((TextView) text.getChildAt(1)).setTextColor(dim);
                n.setTextColor(f ? 0xFF1C1C1E : t.rank <= 3 ? ui.ink : ui.faint);
            });
            return r;
        }
    }

    // MARK: - Preferences

    private View preferences() {
        LinearLayout view = ui.row();
        view.setGravity(Gravity.TOP);
        ScrollView left = new ScrollView(this);
        left.setVerticalScrollBarEnabled(false);
        left.setClipToPadding(false);
        LinearLayout col = ui.column();
        col.setPadding(0, 0, 0, ui.px(60));
        left.addView(col);
        // Favourite genres, ranked 1 to 3
        LinearLayout fav = ui.column();
        fav.addView(ui.text("O QUE VOCÊ MAIS GOSTA DE ASSISTIR", 17, Ui.semibold, ui.dim));
        fav.addView(ui.text("OK marca a 1ª, a 2ª e a 3ª preferência; OK de novo desmarca.", 19, Ui.medium, ui.faint), ui.margins(WRAP, WRAP, 0, 8, 0, 14));
        List<String> favs = new ArrayList<>(movies.favourites());
        List<TvUi.Chip> favChips = new ArrayList<>();
        LinearLayout r = null;
        for (int i = 0; i < Movies.GENRES.length; i++) {
            String key = Movies.GENRES[i][0];
            if (i % 6 == 0) { r = ui.row(); fav.addView(r, ui.margins(WRAP, WRAP, 0, 0, 0, 12)); }
            TvUi.Chip c = ui.chip(label(key, favs), favs.contains(key), null);
            c.setOnClickListener(v -> {
                if (favs.contains(key)) favs.remove(key);
                else if (favs.size() < 3) favs.add(key);
                else return;
                for (int j = 0; j < favChips.size(); j++) {
                    String kj = Movies.GENRES[j][0];
                    favChips.get(j).setText(label(kj, favs));
                    favChips.get(j).set(favs.contains(kj));
                }
                movies.setFavourites(favs);
            });
            favChips.add(c);
            r.addView(c, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
        }
        col.addView(ui.panel(fav, 28), ui.margins(MATCH, WRAP, 0, 0, 0, 24));
        // Avoid
        LinearLayout avoid = ui.column();
        avoid.addView(ui.text("NÃO QUERO VER", 17, Ui.semibold, ui.dim), ui.margins(WRAP, WRAP, 0, 0, 0, 14));
        List<String> avoided = new ArrayList<>(movies.avoid());
        r = null;
        String[] avoidKeys = {"terror", "romance", "animacao", "documentario", "reality", "infantil", "guerra", "familia"};
        for (int i = 0; i < avoidKeys.length; i++) {
            String key = avoidKeys[i];
            if (i % 6 == 0) { r = ui.row(); avoid.addView(r, ui.margins(WRAP, WRAP, 0, 0, 0, 12)); }
            TvUi.Chip c = ui.chip(Movies.genreName(key), avoided.contains(key), null);
            c.setOnClickListener(v -> {
                if (avoided.contains(key)) avoided.remove(key); else avoided.add(key);
                c.set(avoided.contains(key));
                movies.setAvoid(avoided);
            });
            r.addView(c, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
        }
        col.addView(ui.panel(avoid, 28), ui.margins(MATCH, WRAP, 0, 0, 0, 24));
        // Minimum rating
        LinearLayout min = ui.column();
        min.addView(ui.text("NOTA MÍNIMA NAS SUGESTÕES", 17, Ui.semibold, ui.dim), ui.margins(WRAP, WRAP, 0, 0, 0, 14));
        LinearLayout mr = ui.row();
        List<TvUi.Chip> minChips = new ArrayList<>();
        for (int v : new int[] {6, 7, 8}) {
            TvUi.Chip c = ui.chip("★ " + v + "+", movies.minRating() == v, null);
            c.setOnClickListener(x -> { movies.setMinRating(v); for (int j = 0; j < minChips.size(); j++) minChips.get(j).set(j + 6 == v); });
            minChips.add(c);
            mr.addView(c, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
        }
        min.addView(mr);
        col.addView(ui.panel(min, 28), ui.margins(MATCH, WRAP, 0, 0, 0, 24));
        // Services
        LinearLayout subs = ui.column();
        subs.addView(ui.text("STREAMINGS QUE VOCÊ ASSINA", 17, Ui.semibold, ui.dim));
        subs.addView(ui.text("Começa com os apps instalados na TV. As sugestões mostram o que está nesses.", 19, Ui.medium, ui.faint), ui.margins(WRAP, WRAP, 0, 8, 0, 14));
        List<String> mine = new ArrayList<>(movies.subscriptions());
        LinearLayout sr = null;
        int si = 0;
        for (Object[] s : Movies.SERVICES) {
            if (si++ % 4 == 0) { sr = ui.row(); subs.addView(sr, ui.margins(WRAP, WRAP, 0, 0, 0, 12)); }
            String name = (String) s[0];
            TvUi.Chip c = ui.chip(name, mine.contains(name), null);
            c.setOnClickListener(v -> {
                if (mine.contains(name)) mine.remove(name); else mine.add(name);
                c.set(mine.contains(name));
                movies.setSubscriptions(mine);
            });
            sr.addView(c, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
        }
        col.addView(ui.panel(subs, 28), ui.margins(MATCH, WRAP, 0, 0, 0, 24));
        col.addView(ui.panel(iptvPanel(), 28), ui.margins(MATCH, WRAP, 0, 0, 0, 24));
        view.addView(left, new LinearLayout.LayoutParams(0, MATCH, 1));
        // What the home screen shows this week
        LinearLayout prev = ui.column();
        prev.addView(ui.text("NA TELA INICIAL, ESTA SEMANA", 17, Ui.semibold, ui.dim));
        prev.addView(ui.text("Atualiza toda segunda-feira, ou quando você muda algo aqui.", 19, Ui.medium, ui.faint), ui.margins(WRAP, WRAP, 0, 8, 0, 10));
        Movies.Home home = movies.home;
        if (home != null) {
            if (home.hero != null) prev.addView(previewLine("★", "Destaque: " + home.hero.name, home.heroLabel.replace("DESTAQUE DA SEMANA · ", ""), TvUi.ACCENT));
            int i = 0;
            int[] colors = {0xFF111111, 0xFF0A84FF, 0xFF5E5CE6, 0xFF30B0C7};
            for (Movies.Row row : home.rows) {
                prev.addView(previewLine(row.ranked ? "10" : String.valueOf(i), row.title, row.items.size() + " títulos", colors[Math.min(i, 3)]));
                i++;
            }
        } else {
            prev.addView(ui.text("Montando…", 20, Ui.medium, ui.dim));
        }
        TvUi.Chip refresh = ui.button("Atualizar agora", true, null);
        refresh.setOnClickListener(v -> { refresh.setText("Atualizando…"); movies.refreshHome(true); });
        prev.addView(refresh, ui.margins(WRAP, WRAP, 0, 24, 0, 0));
        view.addView(ui.panel(prev, 30), ui.margins(ui.px(580), WRAP, 32, 0, 0, 0));
        return view;
    }

    // MARK: - Your IPTV list

    private android.widget.EditText field(String hint, String value, boolean password) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * ui.k);
        e.setTypeface(Ui.medium);
        e.setTextColor(0xFF1C1C1E);
        e.setHintTextColor(0xFF8E8E93);
        e.setPadding(ui.px(22), 0, ui.px(22), 0);
        e.setInputType(password ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable normal = TvUi.round(0xFFFFFFFF, 16 * ui.k);
        normal.setStroke(ui.px(2), 0x14000000);
        GradientDrawable focused = TvUi.round(0xFFFFFFFF, 16 * ui.k);
        focused.setStroke(ui.px(4), TvUi.ACCENT);
        e.setBackground(normal);
        e.setOnFocusChangeListener((v, f) -> e.setBackground(f ? focused : normal));
        return e;
    }

    private View iptvPanel() {
        Iptv iptv = Iptv.get(this);
        LinearLayout p = ui.column();
        p.addView(ui.text("MINHAS LISTAS IPTV", 17, Ui.semibold, ui.dim));
        java.text.SimpleDateFormat when = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US);
        for (Iptv.Source src : iptv.sources()) {
            LinearLayout box = ui.column();
            box.setPadding(ui.px(20), ui.px(16), ui.px(20), ui.px(16));
            box.setBackground(TvUi.round(ui.dark ? 0x14FFFFFF : 0x0A000000, 18 * ui.k));
            box.addView(ui.text(src.name, 24, Ui.semibold, ui.ink));
            String state = src.loading ? "Baixando o catálogo…"
                    : !src.error.isEmpty() ? src.error
                    : src.movies + " filmes · " + src.shows + " séries · " + src.channels + " canais"
                    + (src.at == 0 ? "" : " · atualizada " + when.format(new java.util.Date(src.at)))
                    + (src.expires.isEmpty() ? "" : " · vence em " + src.expires)
                    + (src.maxConnections > 0 ? " · " + src.maxConnections + (src.maxConnections == 1 ? " tela" : " telas") : "");
            box.addView(ui.text(state, 18, Ui.medium, src.error.isEmpty() ? ui.dim : 0xFFB42318), ui.margins(WRAP, WRAP, 0, 6, 0, 12));
            LinearLayout r = ui.row();
            TvUi.Chip update = ui.chip("Atualizar agora", false, null);
            update.setOnClickListener(v -> { iptv.refresh(true); update.setText("Atualizando…"); });
            TvUi.Chip out = ui.chip("Remover lista", false, null);
            // Two OKs: the first only asks (for 5 s).
            out.setOnClickListener(v -> {
                if (out.getTag() == null) {
                    out.setTag("ask");
                    out.setText("OK de novo para remover “" + src.name + "”");
                    out.setTextColor(0xFFB42318);
                    out.postDelayed(() -> { out.setTag(null); out.setText("Remover lista"); out.paint(); }, 5000);
                    return;
                }
                iptv.remove(src);
                rebuild();
            });
            r.addView(update, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
            r.addView(out);
            box.addView(r);
            p.addView(box, ui.margins(MATCH, WRAP, 0, 14, 0, 0));
        }
        Iptv.Source gone = iptv.removed();
        if (gone != null) {
            TvUi.Chip back = ui.button("↺  Restaurar a lista “" + gone.name + "”", false, () -> { iptv.restore(); recreate(); });
            p.addView(back, ui.margins(WRAP, WRAP, 0, 14, 0, 0));
        }
        if (iptv.connected()) {
            LinearLayout r = ui.row();
            TvUi.Chip dub = ui.chip("Preferir dublado", iptv.preferDubbed(), null);
            TvUi.Chip leg = ui.chip("Preferir legendado", !iptv.preferDubbed(), null);
            dub.setOnClickListener(v -> { iptv.setPreferDubbed(true); dub.set(true); leg.set(false); });
            leg.setOnClickListener(v -> { iptv.setPreferDubbed(false); dub.set(false); leg.set(true); });
            TvUi.Chip hd = ui.chip("Full HD primeiro", !iptv.prefer4k(), null);
            TvUi.Chip uhd = ui.chip("4K primeiro", iptv.prefer4k(), null);
            hd.setOnClickListener(v -> { iptv.setPrefer4k(false); hd.set(true); uhd.set(false); });
            uhd.setOnClickListener(v -> { iptv.setPrefer4k(true); hd.set(false); uhd.set(true); });
            for (TvUi.Chip c : new TvUi.Chip[] {dub, leg, hd, uhd}) r.addView(c, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
            p.addView(r, ui.margins(WRAP, WRAP, 0, 18, 0, 0));
            // Adult channels: hidden until turned on here (Ao vivo, the search and the mosaic leave them out).
            boolean adultOn = getSharedPreferences("iptv", MODE_PRIVATE).getBoolean("adult", false);
            TvUi.Chip adult = ui.chip(adultOn ? "Canais adultos: aparecendo" : "Canais adultos: escondidos", adultOn, null);
            adult.setOnClickListener(v -> {
                boolean on = !getSharedPreferences("iptv", MODE_PRIVATE).getBoolean("adult", false);
                Lineup.setAdult(this, on);
                adult.setText(on ? "Canais adultos: aparecendo" : "Canais adultos: escondidos");
                adult.set(on);
            });
            p.addView(adult, ui.margins(WRAP, WRAP, 0, 12, 0, 0));
        }
        // Add a list.
        p.addView(ui.text(iptv.connected() ? "ADICIONAR OUTRA LISTA" : "ADICIONAR UMA LISTA", 16, Ui.semibold, ui.dim), ui.margins(WRAP, WRAP, 0, 26, 0, 6));
        p.addView(ui.text("Os dados que o seu provedor enviou. Ficam salvos só nesta TV.", 19, Ui.medium, ui.faint), ui.margins(WRAP, WRAP, 0, 0, 0, 14));
        android.widget.EditText name = field("Nome da lista (ex.: Lista da casa)", "", false);
        android.widget.EditText server = field("Servidor (ex.: http://exemplo.com:8080)", "", false);
        android.widget.EditText user = field("Usuário", "", false);
        android.widget.EditText pass = field("Senha", "", true);
        for (android.widget.EditText e : new android.widget.EditText[] {name, server, user, pass}) {
            View.OnFocusChangeListener was = e.getOnFocusChangeListener();
            e.setOnFocusChangeListener((v, f) -> { was.onFocusChange(v, f); if (f) typing = true; });
        }
        LinearLayout one = ui.row();
        one.addView(name, new LinearLayout.LayoutParams(0, ui.px(64), 1));
        one.addView(server, ui.margins(0, ui.px(64), 12, 0, 0, 0));
        ((LinearLayout.LayoutParams) server.getLayoutParams()).weight = 2;
        p.addView(one, ui.margins(MATCH, WRAP, 0, 0, 0, 12));
        LinearLayout two = ui.row();
        two.addView(user, new LinearLayout.LayoutParams(0, ui.px(64), 1));
        two.addView(pass, ui.margins(0, ui.px(64), 12, 0, 0, 0));
        ((LinearLayout.LayoutParams) pass.getLayoutParams()).weight = 1;
        p.addView(two, ui.margins(MATCH, WRAP, 0, 0, 0, 14));
        TextView state = ui.text("", 20, Ui.medium, 0xFFB42318);
        TvUi.Chip go = ui.button("Conectar", true, null);
        go.setOnClickListener(v -> {
            go.setText("Conectando…");
            state.setText("");
            iptv.add(name.getText().toString(), server.getText().toString(), user.getText().toString(), pass.getText().toString(), err -> {
                if (err != null) {
                    go.setText("Conectar");
                    state.setText(err);
                } else {
                    typing = false;
                    rebuild();
                }
            });
        });
        p.addView(go);
        p.addView(state, ui.margins(WRAP, WRAP, 0, 12, 0, 0));
        return p;
    }

    // MARK: - IPTV: the provider's own categories

    /** An IPTV item: its TMDB page when we know (or find) it; a movie we can't find just plays. */
    private void openIptv(Iptv.Version v) {
        if (v.tmdb > 0) { TvUi.open(this, v.kind, v.tmdb); return; }
        android.widget.Toast.makeText(this, "Procurando…", android.widget.Toast.LENGTH_SHORT).show();
        String name = v.title.isEmpty() ? Iptv.display(v) : v.title;
        movies.run(() -> movies.search(v.kind, name, v.year), id -> {
            if (id != null && id > 0) TvUi.open(this, v.kind, id);
            else if (v.kind.equals("movie")) PlayerActivity.play(this, Iptv.movieUrl(v), Iptv.display(v), v.label() + " · " + v.source.name, "iptv:" + v.source.id + ":" + v.id, v.icon, "movie", 0, null);
            else android.widget.Toast.makeText(this, "Não achei essa série no TMDB.", android.widget.Toast.LENGTH_LONG).show();
        });
    }

    /** A poster card for an IPTV item (its own picture and name). */
    private View iptvCard(Iptv.Version v, int w, boolean showSource) {
        LinearLayout box = ui.column();
        FrameLayout pic = new FrameLayout(this);
        pic.setFocusable(true);
        pic.setClickable(true);
        pic.setBackground(TvUi.round(ui.dark ? 0xFF2A2D35 : 0xFFCDD1D8, 16 * ui.k));
        pic.setClipToOutline(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Img.into(img, v.icon, w);
        pic.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView fallback = ui.line(Iptv.display(v), 18, Ui.semibold, ui.dim, 4);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(ui.px(12), 0, ui.px(12), 0);
        if (v.icon.isEmpty()) pic.addView(fallback, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        if (!v.quality.isEmpty()) {
            TextView q = ui.text(v.quality, 13, Ui.semibold, 0xFFFFFFFF);
            q.setPadding(ui.px(8), ui.px(5), ui.px(8), ui.px(5));
            q.setBackground(TvUi.round(0xC7000000, 8 * ui.k));
            FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(WRAP, WRAP);
            qp.setMargins(ui.px(10), ui.px(10), 0, 0);
            pic.addView(q, qp);
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(16 * ui.k);
        ring.setStroke(ui.px(5), 0xFFFFFFFF);
        pic.setOnFocusChangeListener((x, f) -> {
            pic.setForeground(f ? ring : null);
            pic.setTranslationZ(f ? 1 : 0);
            pic.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start();
        });
        pic.setOnClickListener(x -> openIptv(v));
        box.addView(pic, TvUi.lp(w, Math.round(w * 1.5f)));
        box.addView(ui.line(Iptv.display(v), 18, Ui.semibold, ui.ink, 1), ui.margins(w, WRAP, 0, 10, 0, 0));
        String meta = v.audio + (showSource ? (v.audio.isEmpty() ? "" : " · ") + v.source.name : "");
        box.addView(ui.line(meta, 15, Ui.medium, ui.dim, 1), ui.margins(w, WRAP, 0, 3, 0, 0));
        return box;
    }

    private final class IptvBrowser {
        final LinearLayout view = ui.row();
        private final String kind;
        private final LinearLayout cats = ui.column(), grid = ui.column();
        private final TextView title = ui.text("", 30, Ui.semibold, ui.ink), count = ui.text("", 21, Ui.medium, ui.dim);
        private String category;   // the merged category's key
        private List<Iptv.Version> items = new ArrayList<>();
        private static final int PAGE = 60;

        IptvBrowser(String kind) {
            this.kind = kind;
            view.setGravity(Gravity.TOP);
            Iptv iptv = Iptv.get(MoviesActivity.this);
            List<Iptv.Source> sources = iptv.sources();
            boolean any = false;
            for (Iptv.Source s : sources) if (!iptv.categories(s, kind).isEmpty()) any = true;
            if (!any) {
                view.addView(ui.text(iptv.loading() ? "Baixando o catálogo da sua lista…" : "Sua lista não trouxe " + (kind.equals("tv") ? "séries." : "filmes."), 24, Ui.medium, ui.dim));
                return;
            }
            view.setTag("ready");
            ScrollView cs = new ScrollView(MoviesActivity.this);
            cs.setVerticalScrollBarEnabled(false);
            cs.setClipToPadding(false);
            cs.setPadding(0, ui.px(6), 0, ui.px(60));
            cs.addView(cats);
            view.addView(cs, ui.margins(ui.px(380), MATCH, 0, 0, 28, 0));
            // All lists' categories together: the same name in two lists is one category with both lists' titles.
            View first = null;
            String firstKey = null, firstLabel = null;
            for (String[] cat : iptv.mergedCategories(kind)) {
                final String key = cat[0], label = cat[1];
                TvUi.Chip c = ui.chip(label, false, null);
                c.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                c.setEllipsize(android.text.TextUtils.TruncateAt.END);
                c.setOnFocusChangeListener((x, f) -> { c.paint(); if (f) select(key, label, c); });
                c.setOnClickListener(x -> { if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus(); });
                cats.addView(c, ui.margins(MATCH, WRAP, 0, 0, 0, 8));
                if (first == null) { first = c; firstKey = key; firstLabel = label; }
            }
            LinearLayout right = ui.column();
            right.setClipChildren(false);
            LinearLayout head = ui.row();
            head.addView(title);
            head.addView(count, ui.margins(WRAP, WRAP, 16, 0, 0, 0));
            right.addView(head, ui.margins(MATCH, WRAP, 0, 4, 0, 16));
            ScrollView gs = new ScrollView(MoviesActivity.this);
            gs.setVerticalScrollBarEnabled(false);
            gs.setClipChildren(false);
            gs.setClipToPadding(true);
            gs.setPadding(ui.px(14), ui.px(18), ui.px(14), ui.px(18));
            grid.setClipChildren(false);
            gs.addView(grid);
            right.addView(gs, new LinearLayout.LayoutParams(MATCH, MATCH));
            view.addView(right, new LinearLayout.LayoutParams(0, MATCH, 1));
            // No focus grab here: the tab keeps it, so ▶ goes on to the next tab and ▼ comes into the categories.
            if (first != null) select(firstKey, firstLabel, (TvUi.Chip) first);
        }

        private TvUi.Chip selected;

        private void select(String key, String label, TvUi.Chip chip) {
            if (key.equals(category)) return;
            category = key;
            if (selected != null) selected.set(false);
            selected = chip;
            chip.set(true);
            items = Iptv.get(MoviesActivity.this).mergedCategory(kind, key);
            title.setText(label);
            count.setText(items.size() + (kind.equals("tv") ? " séries" : " filmes"));
            grid.removeAllViews();
            fill(0);
        }

        private void fill(int from) {
            if (from > 0) grid.removeViewAt(grid.getChildCount() - 1);   // the "more" button
            int w = ui.px(178), to = Math.min(items.size(), from + PAGE);
            LinearLayout r = null;
            for (int i = from; i < to; i++) {
                if ((i - from) % 6 == 0) {
                    r = ui.row();
                    r.setGravity(Gravity.TOP);
                    r.setClipChildren(false);
                    grid.addView(r, ui.margins(WRAP, WRAP, 0, 0, 0, 30));
                }
                r.addView(iptvCard(items.get(i), w, Iptv.get(MoviesActivity.this).sources().size() > 1), ui.margins(w, WRAP, 0, 0, 24, 0));
            }
            if (to < items.size()) {
                TvUi.Chip more = ui.chip("Mostrar mais (" + (items.size() - to) + ")", false, null);
                more.setOnClickListener(x -> fill(to));
                grid.addView(more, ui.margins(WRAP, WRAP, 0, 0, 0, 30));
            }
        }
    }

    // MARK: - Search: TMDB and your IPTV lists

    private final class Search {
        final LinearLayout view = ui.column();
        private final LinearLayout results = ui.column();
        private int generation;
        private final Runnable run = this::run;
        private final android.widget.EditText box = field("Um filme, série, ator, canal ou gênero (ex.: filmes de suspense)…", "", false);

        Search() {
            box.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            LinearLayout bar = ui.row();
            bar.addView(box, ui.margins(ui.px(1100), ui.px(70), 0, 0, 0, 0));
            search = this;
            MicView talk = new MicView(MoviesActivity.this, false);
            talk.restBg = ui.chip;
            talk.restInk = ui.ink;
            talk.focusBg = ui.chipOn;
            talk.focusInk = ui.chipOnText;
            talk.setContentDescription("Buscar por voz");
            talk.setOnClickListener(x -> listenNow());
            bar.addView(talk, ui.margins(ui.px(70), ui.px(70), 18, 0, 0, 0));
            view.addView(bar, ui.margins(WRAP, WRAP, 0, 6, 0, 18));
            box.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
                @Override public void afterTextChanged(android.text.Editable e) { handler.removeCallbacks(run); handler.postDelayed(run, 600); }
            });
            box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            box.setOnEditorActionListener((v, a, e) -> { handler.removeCallbacks(run); run(); return false; });
            ScrollView sv = new ScrollView(MoviesActivity.this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            sv.setClipChildren(false);
            sv.setPadding(0, 0, 0, ui.px(60));
            results.setClipChildren(false);
            sv.addView(results);
            view.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1));
            results.addView(ui.text("Aperte o microfone do controle e fale, ou OK no campo para digitar. Dá para pedir por nome (“Duna”, “Wagner Moura”, “SporTV”) ou por tipo: “filmes de suspense”, “séries de terror”, “comédia dos anos 90”, “ação brasileiro”, “lançamentos de ficção”, “documentários da Netflix”.", 21, Ui.medium, ui.dim));
        }

        void listenNow() {
            listen(text -> {
                String pick = pickHeard(text);
                box.setText(pick);
                box.setSelection(pick.length());
                handler.removeCallbacks(run);
                run();
            });
        }

        /**
         * Of what the microphone thought it heard ("Dona", "Duna", "Luna"), the first that is something on your lists
         * (a channel, a movie or show, a kind of title); else the most likely one.
         */
        private String pickHeard(String first) {
            Iptv iptv = Iptv.get(MoviesActivity.this);
            Lineup lineup = Lineup.ready(MoviesActivity.this);
            List<String> heard = new ArrayList<>(Voice.heardAlso);
            // Best: a guess that is a whole title of your lists ("Duna"), a kind of title, or a channel's name.
            for (String a : heard) if (iptv.hasTitle(a) || Ask.understand(a) != null || (lineup != null && !lineup.search(a, 1).isEmpty())) return a;
            // Then: one that is part of a title ("Bruna" is in "Bruna Surfistinha").
            for (String a : heard) if (!iptv.search(a, 1).isEmpty()) return a;
            return first;
        }

        /**
         * "Você quis dizer: Duna · Luna": the microphone's other guesses, and titles of your lists that sound like
         * what was heard ("Bruna" → "Duna"), one OK away.
         */
        private void heardAlso(String q, int gen) {
            List<String> others = new ArrayList<>();
            boolean ours = false;
            for (String a : Voice.heardAlso) {
                if (a.equalsIgnoreCase(q)) ours = true;
                else if (others.size() < 3) others.add(a);
            }
            if (!ours) others.clear();
            LinearLayout line = ui.row();
            line.setClipChildren(false);
            line.addView(ui.text("Você quis dizer:", 19, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 4, 0, 14, 0));
            line.setVisibility(others.isEmpty() ? View.GONE : View.VISIBLE);
            for (String a : others) addGuess(line, a);
            results.addView(line, 0, ui.margins(WRAP, WRAP, 0, 4, 0, 14));
            // Titles of your lists that sound like it, when it isn't a title itself (off the main thread: it goes
            // through every title).
            Iptv iptv = Iptv.get(MoviesActivity.this);
            if (iptv.hasTitle(q) || Ask.understand(q) != null) return;
            movies.run(() -> iptv.soundsLike(q, 4), like -> {
                if (gen != generation || like == null) return;
                for (String t : like) {
                    boolean dup = false;
                    for (String o : others) if (Iptv.normalize(o).equals(Iptv.normalize(t))) dup = true;
                    if (dup || others.size() >= 6) continue;
                    others.add(t);
                    addGuess(line, t);
                }
                if (!others.isEmpty()) line.setVisibility(View.VISIBLE);
            });
        }

        private void addGuess(LinearLayout line, String a) {
            TvUi.Chip c = ui.chip(a, false, null);
            c.setOnClickListener(v -> { box.setText(a); box.setSelection(a.length()); handler.removeCallbacks(run); run(); });
            line.addView(c, ui.margins(WRAP, WRAP, 0, 0, 10, 0));
        }

        private void run() {
            String q = box.getText().toString().trim();
            int gen = ++generation;
            results.removeAllViews();
            if (q.length() < 2) return;
            heardAlso(q, gen);
            // What the words mean decides the order: a channel group ("jogos de hoje"), a team ("Galo"), a channel's
            // name, or a title. Sections say what they are: Ao vivo agora, Filmes / Séries nas suas listas, Pessoas,
            // Fora das suas listas.
            Iptv iptv = Iptv.get(MoviesActivity.this);
            Lineup lineup = Lineup.ready(MoviesActivity.this);
            if (lineup == null && iptv.connected()) {
                movies.run(() -> Lineup.get(MoviesActivity.this), l -> { if (gen == generation && l != null) run(); });
            }
            String group = lineup == null ? null : Lineup.groupFor(q);
            if (group != null && !lineup.group(group).isEmpty()) {
                List<Lineup.Channel> g = lineup.group(group);
                final String gname = group;
                results.addView(channelRow("Ao vivo agora · " + group, g.size() + (g.size() == 1 ? " canal" : " canais") + " nas suas listas",
                        g.subList(0, Math.min(20, g.size())), group, () -> openLive(gname)));
            }
            boolean games = addGames(q);
            // A kind of title ("filmes de suspense", "séries de terror", "comédia dos anos 90"): titles of that kind,
            // with your settings, instead of looking for the words in names.
            // ("documentários", "infantil": the channels above, then the titles of that kind.)
            Ask ask = !games ? Ask.understand(q) : null;
            if (ask != null) { askRows(ask, gen); return; }
            List<Lineup.Channel> chans = lineup == null || group != null ? new ArrayList<>() : lineup.search(q, 16);
            List<Iptv.Version> mine = iptv.search(q, 30);
            List<Iptv.Version> films = new ArrayList<>(), shows = new ArrayList<>();
            for (Iptv.Version v : mine) (v.kind.equals("tv") ? shows : films).add(v);
            boolean channelFirst = !chans.isEmpty() && (games || Lineup.norm(chans.get(0).name).startsWith(Lineup.norm(q)));
            View chanRow = chans.isEmpty() ? null : channelRow("Ao vivo agora", "canais com “" + q + "”", chans, "Busca", null);
            if (chanRow != null && channelFirst) results.addView(chanRow);
            iptvRow("Filmes nas suas listas", films);
            iptvRow("Séries nas suas listas", shows);
            if (chanRow != null && !channelFirst) results.addView(chanRow);
            if (group != null || (games && mine.isEmpty())) return;   // a group or a team: no need for the catalog
            TextView wait = ui.text("Buscando no catálogo…", 21, Ui.medium, ui.dim);
            results.addView(wait, ui.margins(WRAP, WRAP, 4, 20, 0, 0));
            movies.run(() -> movies.searchEverything(q), found -> {
                if (gen != generation) return;
                results.removeView(wait);
                if (found == null) return;
                // A name said or typed is most often a title: titles whose name is exactly it first, people after
                // (the catalog's search is loose: "Duna" also brings a "Bruna").
                String exact = Iptv.normalize(q);
                List<Movies.Title> others = new ArrayList<>(), first = new ArrayList<>();
                for (Movies.Title t : found.titles) {
                    if (iptv.has(t)) continue;
                    (Iptv.normalize(t.name).equals(exact) || Iptv.normalize(t.original).equals(exact) || Iptv.normalize(t.name).startsWith(exact + " ") ? first : others).add(t);
                }
                others.addAll(0, first);
                boolean person = false;
                for (Movies.Person p : found.people) if (Iptv.normalize(p.name).startsWith(exact)) person = true;
                if (person && !found.people.isEmpty()) peopleRow(found.people);
                if (others.isEmpty()) {
                    if (!person && !found.people.isEmpty()) peopleRow(found.people);
                    if (results.getChildCount() == 0) results.addView(ui.text("Nada encontrado para “" + q + "”.", 21, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 4, 20, 0, 0));
                    return;
                }
                LinearLayout head = ui.row();
                head.addView(ui.text("Fora das suas listas", 28, Ui.semibold, ui.ink));
                head.addView(ui.text("veja em qual streaming está", 20, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 14, 0, 0, 0));
                results.addView(head, ui.margins(WRAP, WRAP, 4, 20, 0, 14));
                LinearLayout grid = ui.column();
                grid.setClipChildren(false);
                grid.setPadding(ui.px(10), ui.px(10), 0, 0);
                int w = ui.px(178);
                LinearLayout r = null;
                for (int i = 0; i < others.size(); i++) {
                    if (i % 8 == 0) {
                        r = ui.row();
                        r.setGravity(Gravity.TOP);
                        r.setClipChildren(false);
                        grid.addView(r, ui.margins(WRAP, WRAP, 0, 0, 0, 30));
                    }
                    Movies.Title t = others.get(i);
                    r.addView(ui.card(t, w, () -> TvUi.open(MoviesActivity.this, t)), ui.margins(w, WRAP, 0, 0, 24, 0));
                }
                results.addView(grid);
                if (!person && !found.people.isEmpty()) peopleRow(found.people);
            });
        }

        /** The results of a search by kind: your lists' own category, then what's on your lists, your streaming, the best rated. */
        private void askRows(Ask a, int gen) {
            Iptv iptv = Iptv.get(MoviesActivity.this);
            String label = a.label();
            int min = movies.minRating();
            results.addView(ui.text("Entendi: " + label + (min > 0 ? " · nota " + min + "+" : "") + " · sem os gêneros que você evita",
                    19, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 4, 0, 0, 10));
            // The providers' own categories with that name ("SUSPENSE", "Filmes | Terror").
            if (a.services.isEmpty() && iptv.connected()) {
                for (String g : a.genres) {
                    String word = Lineup.norm(Movies.genreName(g)).split(" ")[0];
                    for (String kind : a.kinds()) {
                        for (String[] c : iptv.mergedCategories(kind)) {
                            if (!(" " + c[0] + " ").contains(" " + word)) continue;
                            List<Iptv.Version> items = iptv.mergedCategory(kind, c[0]);
                            if (items.isEmpty()) continue;
                            iptvRow("Categoria “" + c[1] + "” das suas listas", items.subList(0, Math.min(40, items.size())));
                            break;
                        }
                    }
                }
            }
            TextView wait = ui.text("Procurando " + label.toLowerCase(Ui.locale()) + "…", 21, Ui.medium, ui.dim);
            results.addView(wait, ui.margins(WRAP, WRAP, 4, 20, 0, 0));
            movies.run(() -> {
                List<Movies.Title> onLists = new ArrayList<>(), streaming = new ArrayList<>(), best = new ArrayList<>();
                if (a.services.isEmpty()) {
                    if (iptv.connected()) for (Movies.Title t : movies.ask(a, "lists")) if (iptv.has(t)) onLists.add(t);
                    for (Movies.Title t : movies.ask(a, "best")) {
                        boolean dup = false;
                        for (Movies.Title x : onLists) if (x.id == t.id && x.kind.equals(t.kind)) { dup = true; break; }
                        if (!dup) best.add(t);
                    }
                }
                streaming.addAll(movies.ask(a, "streaming"));
                return new Object[] {onLists, streaming, best};
            }, got -> {
                if (gen != generation) return;
                results.removeView(wait);
                if (got == null) {
                    results.addView(ui.text("Não deu para buscar agora (sem internet ou sem a chave do TMDB).", 21, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 4, 20, 0, 0));
                    return;
                }
                @SuppressWarnings("unchecked") List<Movies.Title> onLists = (List<Movies.Title>) got[0], streaming = (List<Movies.Title>) got[1], best = (List<Movies.Title>) got[2];
                if (!onLists.isEmpty()) results.addView(posterRow(label + " nas suas listas", "dá para assistir agora", onLists, false));
                if (!streaming.isEmpty()) results.addView(posterRow(label + " no seu streaming", a.services.isEmpty() ? "nos serviços que você assina" : "", streaming, false));
                if (!best.isEmpty()) results.addView(posterRow("Mais " + label.toLowerCase(Ui.locale()) + " bem avaliados", "veja onde assistir nos detalhes", best, false));
                if (onLists.isEmpty() && streaming.isEmpty() && best.isEmpty())
                    results.addView(ui.text("Nada de " + label.toLowerCase(Ui.locale()) + " com nota " + min + "+. Dá para baixar a nota mínima em Ajustes.", 21, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 4, 20, 0, 0));
            });
        }

        private void iptvRow(String title, List<Iptv.Version> items) {
            if (items.isEmpty()) return;
            results.addView(ui.text(title, 28, Ui.semibold, ui.ink), ui.margins(WRAP, WRAP, 4, 10, 0, 14));
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(MoviesActivity.this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.setPadding(ui.px(10), ui.px(16), ui.px(60), ui.px(20));
            LinearLayout line = ui.row();
            line.setGravity(Gravity.TOP);
            line.setClipChildren(false);
            boolean many = Iptv.get(MoviesActivity.this).sources().size() > 1;
            for (Iptv.Version v : items) line.addView(iptvCard(v, ui.px(160), many), ui.margins(ui.px(160), WRAP, 0, 0, 22, 0));
            hs.addView(line);
            results.addView(hs);
        }

        /** Pessoas: actors and directors (round photos); OK opens their page with what's on your lists. */
        private void peopleRow(List<Movies.Person> people) {
            results.addView(ui.text("Pessoas", 28, Ui.semibold, ui.ink), ui.margins(WRAP, WRAP, 4, 20, 0, 14));
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(MoviesActivity.this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.setPadding(ui.px(10), ui.px(14), ui.px(60), ui.px(20));
            LinearLayout line = ui.row();
            line.setGravity(Gravity.TOP);
            line.setClipChildren(false);
            for (Movies.Person p : people) {
                if (line.getChildCount() >= 10) break;
                LinearLayout box = ui.column();
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                box.setFocusable(true);
                box.setClickable(true);
                ImageView img = new ImageView(MoviesActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(TvUi.round(ui.chip, 80 * ui.k));
                img.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
                });
                img.setClipToOutline(true);
                Img.into(img, Img.tmdb(p.photo, "w185"), ui.px(140));
                box.addView(img, new LinearLayout.LayoutParams(ui.px(140), ui.px(140)));
                TextView name = ui.line(p.name, 18, Ui.semibold, ui.ink, 2);
                name.setGravity(Gravity.CENTER);
                box.addView(name, ui.margins(ui.px(170), WRAP, 0, 10, 0, 0));
                TextView role = ui.text(p.role, 15, Ui.medium, ui.dim);
                box.addView(role, ui.margins(WRAP, WRAP, 0, 2, 0, 0));
                box.setOnFocusChangeListener((v, f) -> box.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start());
                box.setOnClickListener(v -> startActivity(new android.content.Intent(MoviesActivity.this, PersonActivity.class).putExtra("id", p.id)));
                line.addView(box, ui.margins(ui.px(170), WRAP, 0, 0, 20, 0));
            }
            hs.addView(line);
            results.addView(hs);
        }

        /** A team ("Galo", "Atlético", "Cruzeiro") or F1: the game with "Onde assistir". True if it added any. */
        private boolean addGames(String q) {
            Sports.All all = HomeData.get(MoviesActivity.this).sports;
            if (all == null) return false;
            String n = " " + Lineup.norm(q) + " ";
            for (String filler : new String[] {" jogo do ", " jogo da ", " jogo de ", " jogos do ", " jogos da ", " assistir ", " o ", " a ", " ver "}) n = n.replace(filler, " ");
            n = n.trim();
            if (n.length() < 2) return false;
            boolean added = false;
            if (all.f1 != null && all.f1.current != null && (n.equals("f1") || n.startsWith("formula") || n.contains("formula 1"))) {
                Sports.F1 f = all.f1;
                f.roll(System.currentTimeMillis());
                Sports.Race race = f.current;
                Sports.Session next = null;
                long now = System.currentTimeMillis();
                for (Sports.Session ss : race.sessions) if (ss.at + 2 * 3600_000L > now) { next = ss; break; }
                String sub = f.live != null ? "AO VIVO · " + f.live.name : next != null ? next.name + " · " + Sports.when(next.at) : race.place;
                gameCard("FÓRMULA 1", race.name, sub + (f.tv.isEmpty() ? "" : " · " + android.text.TextUtils.join(", ", f.tv)), () ->
                        Watch.show(MoviesActivity.this, "Fórmula 1 · " + race.name, sub, f.tv, new ArrayList<>(), java.util.Arrays.asList("F1", "Formula 1"), "f1"));
                added = true;
            }
            for (int kind = 0; kind < 2; kind++) {
                Sports.Club club = kind == 0 ? all.soccer : all.hockey;
                if (club == null) continue;
                List<Sports.Match> matches = new ArrayList<>();
                if (club.live != null) matches.add(club.live);
                matches.addAll(club.upcoming);
                boolean ours = matchesName(n, club.label, club.me.name, club.me.abbrev, kind == 0 ? Config.SPORTS_SOCCER_FULL : "");
                int shown = 0;
                for (Sports.Match m : matches) {
                    if (shown >= 2) break;
                    if (!ours && !matchesName(n, m.home.name, m.home.abbrev, m.away.name, m.away.abbrev)) continue;
                    final int k = kind;
                    String title = m.home.name + " × " + m.away.name;
                    String sub = (m.comp.isEmpty() ? "" : m.comp + " · ") + (m.state == Sports.LIVE ? "AO VIVO" : Sports.when(m.at))
                            + " · " + (m.tv.isEmpty() ? "canal a confirmar" : android.text.TextUtils.join(", ", m.tv));
                    List<String> teams = new ArrayList<>(java.util.Arrays.asList(m.home.name, m.away.name, m.home.abbrev, m.away.abbrev, club.label));
                    List<String> usual = m.tv.isEmpty() && k == 0 ? java.util.Arrays.asList("Premiere", "SporTV", "Globo") : new ArrayList<>();
                    gameCard(m.state == Sports.LIVE ? "AO VIVO AGORA" : shown == 0 ? "PRÓXIMO JOGO" : "DEPOIS", title, sub, () ->
                            Watch.show(MoviesActivity.this, title, sub, m.tv, teams, usual, k == 0 ? "soccer" : "hockey"));
                    shown++;
                    added = true;
                }
            }
            return added;
        }

        private boolean matchesName(String q, String... names) {
            for (String name : names) {
                String n = Lineup.norm(name);
                if (n.isEmpty()) continue;
                if (n.equals(q) || (" " + n + " ").contains(" " + q + " ") || (q.length() >= 4 && (" " + q + " ").contains(" " + n + " "))) return true;
            }
            return false;
        }

        private void gameCard(String label, String title, String sub, Runnable watch) {
            LinearLayout card = ui.row();
            card.setPadding(ui.px(30), ui.px(24), ui.px(24), ui.px(24));
            card.setBackground(TvUi.round(ui.panel, 24 * ui.k));
            LinearLayout text = ui.column();
            text.addView(ui.text(label, 16, Ui.semibold, label.startsWith("AO VIVO") ? 0xFFE5252A : TvUi.ACCENT));
            text.addView(ui.line(title, 30, Ui.semibold, ui.ink, 1), ui.margins(MATCH, WRAP, 0, 8, 0, 0));
            text.addView(ui.line(sub, 19, Ui.medium, ui.dim, 1), ui.margins(MATCH, WRAP, 0, 6, 0, 0));
            card.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1));
            TvUi.Chip go = ui.button("▶  Onde assistir", true, watch);
            card.addView(go, ui.margins(WRAP, WRAP, 20, 0, 0, 0));
            results.addView(card, ui.margins(ui.px(1180), WRAP, 4, 6, 0, 14));
        }
    }

    // MARK: - Voice: the TV's speech recognition (the remote's microphone)

    private Search search;

    void listen(Movies.Callback<String> done) { Voice.listen(this, done::done); }

    /** The remote's microphone: open Buscar and listen, wherever you are on this screen. */
    @Override public void voiceSearch() {
        if (tab == T_LIVE && live != null) {   // on Ao vivo: channels (of what was heard, the guess that is a channel)
            Voice.listen(this, text -> {
                Lineup l = Lineup.ready(this);
                String pick = text;
                if (l != null) for (String x : new ArrayList<>(Voice.heardAlso)) if (!l.search(x, 1).isEmpty()) { pick = x; break; }
                live.search(pick);
            });
            return;
        }
        int at = tabIds.indexOf(T_SEARCH);
        if (tab != T_SEARCH && at >= 0) { show(T_SEARCH); tabs.get(at).requestFocus(); }
        if (search != null) search.listenNow();
    }

    private long keyAt;

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        keyAt = android.os.SystemClock.uptimeMillis();
        if (Voice.isMicKey(e.getKeyCode()) && !Voice.listening()) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                AppLog.w("voice: mic key " + e.getKeyCode());
                voiceSearch();
            }
            return true;
        }
        if (tab == T_LIVE && live != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_MENU && !Watch.isOpen(this)) {
            // MENU on a channel: the same menu as holding OK; elsewhere, tests the channels on screen.
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                View f = getCurrentFocus();
                if (f != null && f.getTag() instanceof Lineup.Channel) f.performLongClick(); else live.test();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Opens Ao vivo on a group (from the search). */
    void openLive(String group) {
        int at = tabIds.indexOf(T_LIVE);
        if (at < 0) return;
        if (tab == T_LIVE && live != null) { live.open(group); return; }
        getIntent().putExtra("group", group);
        show(T_LIVE);
        tabs.get(at).requestFocus();
        if (live != null) live.open(group);
    }

    @Override public boolean onSearchRequested() { voiceSearch(); return true; }

    @Override public void onRequestPermissionsResult(int request, String[] p, int[] results) {
        super.onRequestPermissionsResult(request, p, results);
        Voice.onPermission(this, request, results);
    }

    private String label(String key, List<String> favs) {
        int i = favs.indexOf(key);
        return Movies.genreName(key) + (i >= 0 ? "  " + (i + 1) + "ª" : "");
    }

    private View previewLine(String n, String title, String sub, int color) {
        LinearLayout r = ui.row();
        r.setPadding(0, ui.px(14), 0, ui.px(14));
        TextView badge = ui.text(n, 18, Ui.semibold, 0xFFFFFFFF);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(TvUi.round(color, 12 * ui.k));
        r.addView(badge, TvUi.lp(ui.px(42), ui.px(42)));
        LinearLayout t = ui.column();
        t.addView(ui.line(title, 21, Ui.semibold, ui.ink, 1));
        t.addView(ui.line(sub, 17, Ui.medium, ui.dim, 1), ui.margins(WRAP, WRAP, 0, 4, 0, 0));
        r.addView(t, ui.margins(0, WRAP, 16, 0, 0, 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        return r;
    }
}
