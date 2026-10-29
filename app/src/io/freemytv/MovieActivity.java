package io.freemytv;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static io.freemytv.TvUi.MATCH;
import static io.freemytv.TvUi.WRAP;

/**
 * One title: backdrop, poster, rating, where to watch (OK opens the app), synopsis, cast, reviews in Portuguese and
 * similar titles. Opened with the extras "kind" ("movie" or "tv") and "id" (TMDB).
 */
public class MovieActivity extends Activity {
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        return Voice.openSearch(this, e) || super.dispatchKeyEvent(e);
    }

    private TvUi ui;
    private Movies movies;
    private FrameLayout root;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        ui = new TvUi(this, true);
        movies = Movies.get(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0C12);
        status = ui.text("Carregando…", 28, Ui.medium, 0xB3FFFFFF);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER);
        root.addView(status, sp);
        setContentView(root);
        String kind = getIntent().getStringExtra("kind");
        int id = getIntent().getIntExtra("id", 0);
        if (kind == null || id == 0) { finish(); return; }
        movies.run(() -> movies.detail(kind, id), d -> {
            if (isFinishing() || isDestroyed()) return;
            if (d == null) { status.setText("Não deu para carregar agora. Tente de novo em instantes."); return; }
            show(d);
        });
    }

    private void show(Movies.Detail d) {
        Movies.Title t = d.t;
        root.removeAllViews();
        // Backdrop, darkened to the left and the bottom so the text reads.
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Img.into(back, Img.tmdb(t.backdrop, "w1280"), ui.px(1920));
        root.addView(back, new FrameLayout.LayoutParams(MATCH, ui.px(1080)));
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF50A0C12, 0xCC0A0C12, 0x330A0C12}));
        root.addView(left, new FrameLayout.LayoutParams(MATCH, ui.px(1080)));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x000A0C12, 0x800A0C12, 0xFF0A0C12}));
        root.addView(bottom, new FrameLayout.LayoutParams(MATCH, ui.px(1080)));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setSmoothScrollingEnabled(true);
        LinearLayout page = ui.column();
        page.setClipChildren(false);
        page.setPadding(ui.px(96), ui.px(90), ui.px(96), ui.px(90));
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Top: poster and the facts.
        LinearLayout top = ui.row();
        top.setGravity(Gravity.TOP);
        top.setClipChildren(false);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(TvUi.round(0xFF2A2D35, 22 * ui.k));
        poster.setOutlineProvider(rounded(22 * ui.k));
        poster.setClipToOutline(true);
        poster.setElevation(ui.px(16));
        Img.into(poster, Img.tmdb(t.poster, "w500"), ui.px(340));
        top.addView(poster, ui.margins(ui.px(340), ui.px(510), 0, 0, 56, 0));

        LinearLayout info = ui.column();
        info.addView(ui.text(t.kindLabel().toUpperCase() + (t.year.isEmpty() ? "" : " · " + t.year), 19, Ui.semibold, TvUi.ACCENT));
        info.addView(ui.line(t.name, 62, Ui.semibold, 0xFFFFFFFF, 2), ui.margins(MATCH, WRAP, 0, 12, 0, 0));
        StringBuilder meta = new StringBuilder();
        for (int i = 0; i < d.genreNames.size() && i < 3; i++) meta.append(i == 0 ? "" : ", ").append(d.genreNames.get(i));
        if (!d.runtime.isEmpty()) meta.append(meta.length() == 0 ? "" : " · ").append(d.runtime);
        if (!d.director.isEmpty()) meta.append(meta.length() == 0 ? "" : " · ").append(t.kind.equals("tv") ? "Criação: " : "Direção: ").append(d.director);
        LinearLayout metaRow = ui.row();
        if (!d.certification.isEmpty()) {
            TextView cert = ui.text(d.certification, 18, Ui.semibold, 0xFFFFFFFF);
            cert.setGravity(Gravity.CENTER);
            cert.setPadding(ui.px(10), ui.px(5), ui.px(10), ui.px(5));
            cert.setBackground(TvUi.round(certColor(d.certification), 7 * ui.k));
            metaRow.addView(cert, ui.margins(WRAP, WRAP, 0, 0, 14, 0));
        }
        metaRow.addView(ui.line(meta.toString(), 22, Ui.medium, 0xCCFFFFFF, 1));
        info.addView(metaRow, ui.margins(MATCH, WRAP, 0, 16, 0, 0));

        // Rating: the score big, the votes under it.
        LinearLayout score = ui.row();
        TextView big = ui.text(Movies.rating(t.rating), 54, Ui.semibold, 0xFFFFFFFF);
        score.addView(big);
        LinearLayout sc = ui.column();
        TextView label = ui.text("Nota TMDB", 18, Ui.semibold, ratingColor(t.rating));
        label.setSingleLine(true);
        sc.addView(label);
        sc.addView(ui.text(Movies.votes(t.votes), 18, Ui.medium, 0x99FFFFFF), ui.margins(WRAP, WRAP, 0, 5, 0, 0));
        score.addView(sc, ui.margins(WRAP, WRAP, 16, 0, 0, 0));
        View bar = new View(this);
        bar.setBackground(TvUi.round(0x33FFFFFF, 4 * ui.k));
        FrameLayout meter = new FrameLayout(this);
        meter.addView(bar, new FrameLayout.LayoutParams(MATCH, MATCH));
        View fill = new View(this);
        fill.setBackground(TvUi.round(ratingColor(t.rating), 4 * ui.k));
        meter.addView(fill, new FrameLayout.LayoutParams((int) (ui.px(220) * Math.max(0, Math.min(1, t.rating / 10))), MATCH));
        score.addView(meter, ui.margins(ui.px(220), ui.px(8), 32, 0, 0, 0));
        info.addView(score, ui.margins(WRAP, WRAP, 0, 22, 0, 0));

        if (!d.tagline.isEmpty()) info.addView(ui.line("“" + d.tagline + "”", 24, Ui.medium, 0xE6FFFFFF, 2), ui.margins(ui.px(1100), WRAP, 0, 22, 0, 0));
        String overview = t.overview.isEmpty() ? "Sem sinopse em português." : t.overview;
        TextView ov = ui.line(overview, 23, Ui.regular, 0xCCFFFFFF, 6);
        ov.setLineSpacing(ui.px(6), 1f);
        info.addView(ov, ui.margins(ui.px(1100), WRAP, 0, 14, 0, 0));

        // Where to watch: your IPTV list first (it plays here), then the streaming apps.
        LinearLayout buttons = ui.row();
        buttons.setClipChildren(false);
        TvUi.Chip first = null;
        List<Iptv.Version> iptv = Iptv.get(this).versions(t);
        LinearLayout versionsBox = ui.column();
        versionsBox.setVisibility(View.GONE);
        if (!iptv.isEmpty()) {
            Iptv.Version best = iptv.get(0);
            if (t.kind.equals("movie")) {
                int at = PlayerActivity.Resume.position(this, "movie:" + t.id);
                TvUi.Chip play = ui.button(at > 0 ? "▶  Continuar de " + clock(at) : "▶  Assistir · " + best.label(), true, () -> playMovie(t, best));
                buttons.addView(play, ui.margins(WRAP, WRAP, 0, 0, 14, 0));
                first = play;
            } else {
                TvUi.Chip eps = ui.button("▶  Episódios", true, null);
                eps.setOnClickListener(v -> { if (episodesFocus != null) episodesFocus.requestFocus(); });
                buttons.addView(eps, ui.margins(WRAP, WRAP, 0, 0, 14, 0));
                first = eps;
            }
            if (iptv.size() > 1) {
                TvUi.Chip vers = ui.button("Versões (" + iptv.size() + ")", false, null);
                vers.setOnClickListener(v -> versionsBox.setVisibility(versionsBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
                buttons.addView(vers, ui.margins(WRAP, WRAP, 0, 0, 14, 0));
                for (Iptv.Version v : iptv) versionsBox.addView(versionRow(t, v, v == best), ui.margins(ui.px(900), WRAP, 0, 0, 0, 10));
            }
        }
        for (int i = 0; i < d.stream.size() && i < 4; i++) {
            Movies.Provider p = d.stream.get(i);
            boolean main = first == null;
            TvUi.Chip c = ui.button(main ? "▶  Assistir no " + p.name : p.name, main, null);
            if (p.pkg != null) {
                c.setOnClickListener(v -> {
                    if (!HomeApps.open(this, HomeApps.app(p.pkg, p.name), v, 0, 0, v.getWidth(), v.getHeight()))
                        android.widget.Toast.makeText(this, p.name + " não está instalado na TV.", android.widget.Toast.LENGTH_SHORT).show();
                });
            } else {
                c.setOnClickListener(v -> android.widget.Toast.makeText(this, p.name + " não tem app configurado aqui.", android.widget.Toast.LENGTH_SHORT).show());
            }
            if (first == null) first = c;
            buttons.addView(c, ui.margins(WRAP, WRAP, 0, 0, 14, 0));
        }
        // My list: favourites, on the home screen and in Explore.
        TvUi.Chip mine = ui.button(movies.inMyList(t) ? "✓  Na minha lista" : "+  Minha lista", false, null);
        mine.setOnClickListener(v -> mine.setText(movies.toggleMyList(t) ? "✓  Na minha lista" : "+  Minha lista"));
        buttons.addView(mine, ui.margins(WRAP, WRAP, 0, 0, 14, 0));
        if (first == null) first = mine;
        info.addView(buttons, ui.margins(WRAP, WRAP, 0, 28, 0, 0));
        info.addView(versionsBox, ui.margins(WRAP, WRAP, 0, 16, 0, 0));
        if (iptv.isEmpty() && d.stream.isEmpty()) {
            info.addView(ui.text(d.rent.isEmpty() ? "Não está em nenhum streaming no Brasil agora." : "Só para alugar ou comprar: " + names(d.rent, 4), 21, Ui.medium, 0x99FFFFFF), ui.margins(WRAP, WRAP, 0, 26, 0, 0));
        } else if (d.stream.size() > 4) {
            info.addView(ui.text("Também em " + names(d.stream.subList(4, d.stream.size()), 4), 19, Ui.medium, 0x99FFFFFF), ui.margins(WRAP, WRAP, 0, 14, 0, 0));
        }
        if (!iptv.isEmpty()) {
            // Which of your lists have it.
            List<String> lists = new ArrayList<>();
            for (Iptv.Version v : iptv) if (!lists.contains(v.source.name)) lists.add(v.source.name);
            info.addView(ui.text("No IPTV: " + android.text.TextUtils.join(", ", lists) + (iptv.size() > 1 ? " · " + iptv.size() + " versões" : ""), 18, Ui.semibold, 0xFFB9A8FF),
                    ui.margins(WRAP, WRAP, 0, 14, 0, 0));
        }
        top.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1));
        page.addView(top, ui.margins(MATCH, WRAP, 0, 0, 0, 0));

        // A show on your list: seasons and episodes.
        if (!iptv.isEmpty() && t.kind.equals("tv")) {
            LinearLayout box = ui.column();
            page.addView(box, ui.margins(MATCH, WRAP, 0, 56, 0, 0));
            box.addView(ui.text("Carregando episódios…", 22, Ui.medium, 0x99FFFFFF));
            Iptv.Version show = iptv.get(0);
            movies.run(() -> Iptv.get(this).episodes(show), list -> {
                if (isFinishing()) return;
                box.removeAllViews();
                if (list == null || list.isEmpty()) {
                    box.addView(ui.text("Não deu para carregar os episódios da sua lista.", 22, Ui.medium, 0x99FFFFFF));
                    return;
                }
                episodes(box, t, list);
            });
        }

        // Cast.
        if (!d.cast.isEmpty()) {
            page.addView(section("Elenco"), ui.margins(WRAP, WRAP, 0, 70, 0, 22));
            HorizontalScrollView hs = hscroll();
            LinearLayout line = ui.row();
            line.setGravity(Gravity.TOP);
            line.setClipChildren(false);
            for (Movies.Person p : d.cast) line.addView(person(p), ui.margins(ui.px(168), WRAP, 0, 0, 26, 0));
            hs.addView(line);
            page.addView(hs);
        }

        // Reviews (Portuguese only).
        page.addView(section("O que o público diz"), ui.margins(WRAP, WRAP, 0, 56, 0, 22));
        if (d.reviews.isEmpty()) {
            page.addView(ui.text("Ainda não há comentários sobre " + (t.kind.equals("tv") ? "esta série." : "este filme."), 22, Ui.medium, 0x99FFFFFF));
        } else {
            HorizontalScrollView hs = hscroll();
            LinearLayout line = ui.row();
            line.setGravity(Gravity.TOP);
            line.setClipChildren(false);
            for (Movies.Review r : d.reviews) line.addView(review(r), ui.margins(ui.px(560), ui.px(300), 0, 0, 26, 0));
            hs.addView(line);
            page.addView(hs);
        }

        // Similar.
        if (!d.similar.isEmpty()) {
            page.addView(section("Se você gostou, veja também"), ui.margins(WRAP, WRAP, 0, 56, 0, 6));
            HorizontalScrollView hs = hscroll();
            LinearLayout line = ui.row();
            line.setClipChildren(false);
            for (Movies.Title s : d.similar) {
                TvUi.Poster p = ui.new Poster(s, ui.px(176), ui.px(264), true);
                p.setOnClickListener(v -> TvUi.open(this, s));
                line.addView(p, ui.margins(ui.px(176), ui.px(264), 0, 0, 24, 0));
            }
            hs.addView(line);
            page.addView(hs);
        }

        // The backdrop scrolls away with the page.
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float shift = -y * 0.5f;
            back.setTranslationY(shift);
            left.setTranslationY(shift);
            bottom.setTranslationY(shift);
        });
        View focus = first != null ? first : page;
        if (first == null) { page.setFocusable(true); }
        focus.requestFocus();
    }

    private View episodesFocus;

    private void playMovie(Movies.Title t, Iptv.Version v) {
        PlayerActivity.play(this, Iptv.movieUrl(v), t.name, (t.year.isEmpty() ? "" : t.year + " · ") + v.label(), "movie:" + t.id,
                Img.tmdb(t.backdrop, "w780"), "movie", t.id, null);
    }

    private View versionRow(Movies.Title t, Iptv.Version v, boolean best) {
        LinearLayout r = ui.column();
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(ui.px(18), ui.px(12), ui.px(18), ui.px(12));
        GradientDrawable normal = TvUi.round(0x1FFFFFFF, 14 * ui.k), focused = TvUi.round(0xFFFFFFFF, 14 * ui.k);
        r.setBackground(normal);
        TextView label = ui.text(v.label() + "  ·  " + v.source.name + (best ? "   ✓ escolhida" : "") + (Iptv.plays(v.codec) ? "" : "   · esta TV não toca"), 21, Ui.semibold, 0xFFFFFFFF);
        TextView raw = ui.line("no provedor: “" + v.raw + "”", 16, Ui.medium, 0x99FFFFFF, 1);
        r.addView(label);
        r.addView(raw, ui.margins(WRAP, WRAP, 0, 4, 0, 0));
        r.setOnFocusChangeListener((x, f) -> {
            r.setBackground(f ? focused : normal);
            label.setTextColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
            raw.setTextColor(f ? 0xFF6B6B70 : 0x99FFFFFF);
        });
        r.setOnClickListener(x -> playMovie(t, v));
        return r;
    }

    private void episodes(LinearLayout box, Movies.Title t, List<Iptv.Episode> all) {
        java.util.TreeMap<Integer, List<Iptv.Episode>> seasons = new java.util.TreeMap<>();
        for (Iptv.Episode e : all) {
            List<Iptv.Episode> l = seasons.get(e.season);
            if (l == null) seasons.put(e.season, l = new ArrayList<>());
            l.add(e);
        }
        // Where you stopped: the newest saved episode of this show.
        String prefix = "tv:" + t.id + ":";
        org.json.JSONObject last = null;
        for (org.json.JSONObject o : PlayerActivity.Resume.list(this, 50)) if (o.optString("key").startsWith(prefix)) { last = o; break; }
        int lastId = last == null ? -1 : Integer.parseInt(last.optString("key").substring(prefix.length()));
        int startSeason = seasons.firstKey();
        for (Iptv.Episode e : all) if (e.id == lastId) startSeason = e.season;
        LinearLayout tabs = ui.row();
        box.addView(section("Episódios"), ui.margins(WRAP, WRAP, 0, 0, 0, 18));
        box.addView(tabs, ui.margins(WRAP, WRAP, 0, 0, 0, 10));
        HorizontalScrollView hs = hscroll();
        LinearLayout line = ui.row();
        line.setGravity(Gravity.TOP);
        line.setClipChildren(false);
        hs.addView(line);
        box.addView(hs);
        List<TvUi.Chip> chips = new ArrayList<>();
        final int lastEpisode = lastId;
        for (int season : seasons.keySet()) {
            TvUi.Chip c = ui.chip("Temporada " + season, season == startSeason, null);
            c.setOnClickListener(v -> {
                for (TvUi.Chip x : chips) x.set(x == c);
                fillEpisodes(line, t, seasons.get(season), lastEpisode);
            });
            chips.add(c);
            tabs.addView(c, ui.margins(WRAP, WRAP, 0, 0, 10, 0));
        }
        episodesFocus = chips.get(0);
        fillEpisodes(line, t, seasons.get(startSeason), lastId);
    }

    private void fillEpisodes(LinearLayout line, Movies.Title t, List<Iptv.Episode> list, int lastId) {
        line.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            Iptv.Episode e = list.get(i);
            String key = "tv:" + t.id + ":" + e.id;
            int at = PlayerActivity.Resume.position(this, key);
            LinearLayout card = ui.column();
            FrameLayout thumb = new FrameLayout(this);
            thumb.setFocusable(true);
            thumb.setClickable(true);
            thumb.setBackground(TvUi.round(0xFF2A2D35, 16 * ui.k));
            thumb.setOutlineProvider(rounded(16 * ui.k));
            thumb.setClipToOutline(true);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Img.into(img, e.image.isEmpty() ? Img.tmdb(t.backdrop, "w780") : e.image, ui.px(330));
            thumb.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
            if (at > 0 && e.seconds > 0) {
                View prog = new View(this);
                prog.setBackgroundColor(TvUi.ACCENT);
                thumb.addView(prog, new FrameLayout.LayoutParams((int) (ui.px(330) * Math.min(1, at / (e.seconds * 1000f))), ui.px(6), Gravity.BOTTOM));
            }
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(16 * ui.k);
            ring.setStroke(ui.px(5), 0xFFFFFFFF);
            thumb.setOnFocusChangeListener((v, f) -> {
                thumb.setForeground(f ? ring : null);
                thumb.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start();
            });
            // The episodes after this one play next, on their own.
            org.json.JSONArray next = new org.json.JSONArray();
            for (int j = i + 1; j < list.size(); j++) {
                Iptv.Episode n = list.get(j);
                try {
                    next.put(new org.json.JSONObject().put("url", Iptv.episodeUrl(n)).put("title", t.name)
                            .put("subtitle", "T" + n.season + ":E" + n.number + " · " + n.title).put("key", "tv:" + t.id + ":" + n.id));
                } catch (Exception ignored) {}
            }
            thumb.setOnClickListener(v -> PlayerActivity.play(this, Iptv.episodeUrl(e), t.name, "T" + e.season + ":E" + e.number + " · " + e.title,
                    key, e.image.isEmpty() ? Img.tmdb(t.backdrop, "w780") : e.image, "tv", t.id, next));
            card.addView(thumb, TvUi.lp(ui.px(330), ui.px(186)));
            card.addView(ui.line(e.number + ". " + e.title, 20, Ui.semibold, 0xFFFFFFFF, 1), ui.margins(ui.px(330), WRAP, 0, 12, 0, 0));
            String meta = (e.seconds > 0 ? e.seconds / 60 + " min" : "") + (at > 0 ? (e.seconds > 0 ? " · " : "") + "parou em " + clock(at) : "");
            card.addView(ui.line(meta, 17, Ui.medium, 0x99FFFFFF, 1), ui.margins(ui.px(330), WRAP, 0, 4, 0, 0));
            line.addView(card, ui.margins(ui.px(330), WRAP, 0, 0, 24, 0));
            if (e.id == lastId) thumb.post(thumb::requestFocus);
        }
    }

    private static String clock(int ms) {
        int s = ms / 1000;
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private TextView section(String s) {
        return ui.text(s, 30, Ui.semibold, 0xFFFFFFFF);
    }

    private HorizontalScrollView hscroll() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(ui.px(12), ui.px(16), ui.px(60), ui.px(20));
        return hs;
    }

    private View person(Movies.Person p) {
        LinearLayout box = ui.column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setClickable(true);
        box.setOnClickListener(v -> startActivity(new android.content.Intent(this, PersonActivity.class).putExtra("id", p.id)));
        box.setClipChildren(false);
        FrameLayout photo = new FrameLayout(this);
        photo.setBackground(TvUi.round(0xFF2A2D35, 84 * ui.k));
        photo.setOutlineProvider(rounded(84 * ui.k));
        photo.setClipToOutline(true);
        if (p.photo.isEmpty()) {
            TextView ini = ui.text(initials(p.name), 44, Ui.semibold, 0x99FFFFFF);
            photo.addView(ini, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        } else {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Img.into(img, Img.tmdb(p.photo, "w185"), ui.px(168));
            photo.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        box.addView(photo, TvUi.lp(ui.px(168), ui.px(168)));
        TextView name = ui.line(p.name, 19, Ui.semibold, 0xFFFFFFFF, 2);
        name.setGravity(Gravity.CENTER);
        box.addView(name, ui.margins(MATCH, WRAP, 0, 14, 0, 0));
        TextView role = ui.line(p.role, 16, Ui.medium, 0x99FFFFFF, 2);
        role.setGravity(Gravity.CENTER);
        box.addView(role, ui.margins(MATCH, WRAP, 0, 4, 0, 0));
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(ui.px(5), 0xFFFFFFFF);
        box.setOnFocusChangeListener((v, f) -> {
            photo.setForeground(f ? ring : null);
            photo.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start();
        });
        return box;
    }

    private View review(Movies.Review r) {
        LinearLayout box = ui.column();
        box.setPadding(ui.px(28), ui.px(24), ui.px(28), ui.px(24));
        box.setFocusable(true);
        GradientDrawable normal = TvUi.round(0x1FFFFFFF, 24 * ui.k);
        normal.setStroke(ui.px(1.5f), 0x24FFFFFF);
        GradientDrawable focused = TvUi.round(0xFFFFFFFF, 24 * ui.k);
        box.setBackground(normal);
        LinearLayout head = ui.row();
        TextView avatar = ui.text(initials(r.author), 18, Ui.semibold, 0xFFFFFFFF);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(TvUi.round(avatarColor(r.author), 22 * ui.k));
        head.addView(avatar, TvUi.lp(ui.px(44), ui.px(44)));
        LinearLayout who = ui.column();
        TextView author = ui.line(r.author, 20, Ui.semibold, 0xFFFFFFFF, 1);
        TextView date = ui.text(r.date, 16, Ui.medium, 0x99FFFFFF);
        who.addView(author);
        who.addView(date, ui.margins(WRAP, WRAP, 0, 4, 0, 0));
        head.addView(who, ui.margins(0, WRAP, 14, 0, 0, 0));
        ((LinearLayout.LayoutParams) who.getLayoutParams()).weight = 1;
        if (r.rating > 0) {
            TextView rate = ui.text("★ " + Movies.rating(r.rating), 18, Ui.semibold, 0xFFFFFFFF);
            rate.setPadding(ui.px(10), ui.px(6), ui.px(10), ui.px(6));
            rate.setBackground(TvUi.round(ratingColor(r.rating), 8 * ui.k));
            head.addView(rate);
        }
        box.addView(head);
        TextView text = ui.line(r.text, 19, Ui.regular, 0xD9FFFFFF, 7);
        text.setLineSpacing(ui.px(4), 1f);
        box.addView(text, ui.margins(MATCH, WRAP, 0, 16, 0, 0));
        if (r.english) {
            // In English: translated to Portuguese when it arrives (the original stays if the translation fails).
            TextView note = ui.text("em inglês · traduzindo…", 15, Ui.medium, 0x80FFFFFF);
            box.addView(note, ui.margins(WRAP, WRAP, 0, 10, 0, 0));
            movies.run(() -> movies.translate(r.text), pt -> {
                if (pt == null || pt.isEmpty()) { note.setText("em inglês"); return; }
                text.setText(pt);
                note.setText("traduzido do inglês");
            });
        }
        box.setOnFocusChangeListener((v, f) -> {
            box.setBackground(f ? focused : normal);
            author.setTextColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
            date.setTextColor(f ? 0xFF6B6B70 : 0x99FFFFFF);
            text.setTextColor(f ? 0xFF2C2C2E : 0xD9FFFFFF);
            // Focused, the whole review shows.
            text.setMaxLines(f ? 40 : 7);
            box.getLayoutParams().height = f ? WRAP : ui.px(300);
            box.requestLayout();
        });
        return box;
    }

    private static String names(List<Movies.Provider> list, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size() && i < max; i++) b.append(i == 0 ? "" : ", ").append(list.get(i).name);
        return b.toString();
    }

    private static String initials(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.trim().split("\\s+")) if (!w.isEmpty() && b.length() < 2) b.append(Character.toUpperCase(w.charAt(0)));
        return b.length() == 0 ? "?" : b.toString();
    }

    private static int avatarColor(String s) {
        int[] colors = {0xFF0A84FF, 0xFF5E5CE6, 0xFF30B0C7, 0xFFFF9F0A, 0xFF34C759, 0xFFFF375F};
        return colors[Math.abs(s.hashCode()) % colors.length];
    }

    static int ratingColor(double r) {
        return r >= 8 ? 0xFF21D07A : r >= 7 ? 0xFF7CCB3E : r >= 6 ? 0xFFD2B530 : r > 0 ? 0xFFDB6B30 : 0xFF8E8E93;
    }

    /** Brazil's age ratings have fixed colors (L green, 10 blue, 12 yellow, 14 orange, 16 red, 18 black). */
    private static int certColor(String c) {
        switch (c) {
            case "L": return 0xFF0C9447;
            case "10": return 0xFF0F7DC2;
            case "12": return 0xFFE5B00C;
            case "14": return 0xFFE3730F;
            case "16": return 0xFFD5221E;
            case "18": return 0xFF1C1C1E;
            default: return 0x55FFFFFF;
        }
    }

    private static ViewOutlineProvider rounded(float r) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        };
    }
}
