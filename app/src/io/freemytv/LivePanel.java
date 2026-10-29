package io.freemytv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.freemytv.TvUi.MATCH;
import static io.freemytv.TvUi.WRAP;

/**
 * "Ao vivo" (a tab of Assistir): live TV from all your IPTV lists as one lineup (Lineup). Our groups on the left
 * (Favoritos, Jogos de hoje, Abertos, Esportes…), each channel once with the lists it's in, what's on now and its sources
 * on the right; each list's own categories are folded at the end. Channels in two columns; OK watches (the best source,
 * falling back to the others), holding OK opens ChannelMenu (source, favourite, mosaic). It doesn't take the focus when
 * built: the tab keeps it.
 */
final class LivePanel {
    final FrameLayout view;
    private final Activity a;
    private final TvUi ui;
    private final Iptv iptv;
    private Lineup lineup;
    private final LinearLayout cats, list;
    private final TextView guideTitle, guideNow, guideSource, empty, screens;
    private LinearLayout pair;   // the row of two channels being filled
    private String selection;               // FAV, a group, or "list:<id>:<category>"; null while searching
    private String query = "";
    private TvUi.Chip selected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int generation;
    private static final int PAGE = 80;
    static final String FAV = "★ Favoritos";
    private final java.util.Set<Integer> opened = new java.util.HashSet<>();   // lists whose categories are unfolded
    private android.widget.EditText searchBox;
    private boolean alive = true;

    LivePanel(Activity a, TvUi ui, String group) {
        this.a = a;
        this.ui = ui;
        iptv = Iptv.get(a);
        selection = group;
        view = new FrameLayout(a);
        LinearLayout page = ui.column();
        view.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
        // How many screens each list allows and how many the provider sees in use (most refusals come from this).
        screens = ui.text("", 17, Ui.semibold, TvUi.ACCENT);
        for (Iptv.Source src : iptv.sources()) {
            Movies.get(a).run(() -> Iptv.check(src), s -> {
                if (s == null || s.maxConnections == 0) return;
                String t = (screens.getText().length() == 0 ? "Telas em uso: " : screens.getText() + "  ·  ") + s.name + " " + s.activeConnections + "/" + s.maxConnections;
                screens.setText(t);
            });
        }
        LinearLayout body = ui.row();
        body.setGravity(Gravity.TOP);
        page.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1));

        ScrollView cs = new ScrollView(a);
        cs.setVerticalScrollBarEnabled(false);
        cs.setClipToPadding(false);
        cs.setPadding(0, ui.px(10), 0, ui.px(60));
        cats = ui.column();
        cs.addView(cats);
        body.addView(cs, ui.margins(ui.px(320), MATCH, 0, 0, 24, 0));

        ScrollView ls = new ScrollView(a);
        ls.setVerticalScrollBarEnabled(false);
        ls.setClipToPadding(false);
        ls.setPadding(ui.px(8), ui.px(10), ui.px(8), ui.px(60));
        list = ui.column();
        ls.addView(list);
        body.addView(ls, new LinearLayout.LayoutParams(0, MATCH, 1));

        // What's on: lean, the channels get the room. The source and the rest are behind holding OK.
        LinearLayout guide = ui.column();
        guide.setPadding(ui.px(28), ui.px(28), ui.px(28), ui.px(28));
        guide.setBackground(TvUi.round(ui.panel, 26 * ui.k));
        guideTitle = ui.line("", 28, Ui.semibold, ui.ink, 2);
        guideNow = ui.text("Escolha um canal para ver o que está passando.", 20, Ui.medium, ui.dim);
        guideNow.setLineSpacing(ui.px(8), 1f);
        guideSource = ui.text("", 17, Ui.semibold, ui.dim);
        guide.addView(ui.text("AGORA NO CANAL", 15, Ui.semibold, ui.faint));
        guide.addView(guideTitle, ui.margins(MATCH, WRAP, 0, 12, 0, 0));
        guide.addView(guideNow, ui.margins(MATCH, WRAP, 0, 16, 0, 0));
        guide.addView(guideSource, ui.margins(MATCH, WRAP, 0, 22, 0, 0));
        TextView hold = ui.text("Segure OK num canal para escolher a fonte, favoritar ou pôr no mosaico.", 16, Ui.medium, ui.faint);
        guide.addView(hold, ui.margins(MATCH, WRAP, 0, 18, 0, 0));
        guide.addView(screens, ui.margins(MATCH, WRAP, 0, 14, 0, 0));
        body.addView(guide, ui.margins(ui.px(420), WRAP, 24, 10, 0, 0));

        empty = ui.text("", 24, Ui.medium, ui.dim);
        view.addView(empty, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        build();
    }

    void destroy() { alive = false; handler.removeCallbacksAndMessages(null); }

    /** A list finished loading: rebuild once the new lineup is ready (keeps what you were looking at). */
    void onIptv() {
        if (alive && (lineup == null || Lineup.ready(a) == null)) build();
    }

    private void build() {
        if (!iptv.connected()) {
            cats.removeAllViews();
            empty.setText("Conecte suas listas IPTV em Assistir › Ajustes.");
            return;
        }
        Lineup l = Lineup.ready(a);
        if (l == null) {
            if (lineup == null) empty.setText(iptv.loading() ? "Baixando os canais das suas listas…" : "Organizando os canais das suas listas…");
            Movies.get(a).run(() -> Lineup.get(a), done -> { if (alive && done != null && Lineup.ready(a) == done) show(done); });
            return;
        }
        show(l);
    }

    private void show(Lineup l) {
        lineup = l;
        cats.removeAllViews();
        selected = null;
        if (l.all.isEmpty()) {
            empty.setText(iptv.loading() ? "Baixando os canais das suas listas…" : "Suas listas não trouxeram canais ao vivo.");
            return;
        }
        empty.setText("");
        cats.addView(searchBox(), ui.margins(MATCH, ui.px(60), 0, 0, 0, 16));
        if (selection == null && query.isEmpty()) selection = l.favourites().isEmpty() ? (l.groupNames().isEmpty() ? FAV : l.groupNames().get(0)) : FAV;
        chip(FAV, FAV, l.favourites().size());
        for (String g : l.groupNames()) chip(g, g, l.group(g).size());
        // Each list's own categories, folded (for whoever knows them).
        cats.addView(ui.text("CATEGORIAS DE CADA LISTA", 15, Ui.semibold, ui.faint), ui.margins(WRAP, WRAP, 6, 22, 0, 10));
        for (Iptv.Source s : iptv.sources()) {
            List<String> names = iptv.categories(s, "live");
            if (names.isEmpty()) continue;
            boolean open = opened.contains(s.id);
            TvUi.Chip head = ui.chip((open ? "▾  " : "▸  ") + s.name + "  (" + names.size() + ")", false, null);
            head.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            head.setOnClickListener(v -> {
                if (!opened.remove(s.id)) opened.add(s.id);
                show(lineup);
            });
            cats.addView(head, ui.margins(MATCH, WRAP, 0, 0, 0, 8));
            if (open) for (String n : names) chip("   " + n, "list:" + s.id + ":" + n, -1);
        }
        fill(0);
    }

    /** Opens a group (from the search: "jogos de hoje" → Jogos de hoje) and puts the focus on it. */
    void open(String group) {
        selection = group;
        query = "";
        if (lineup != null) show(lineup);
        if (selected != null) { View f = selected; f.post(f::requestFocus); }
    }

    /** Typed or said: searches the channels. */
    void search(String text) {
        if (searchBox == null) return;
        searchBox.setText(text);
        searchBox.setSelection(text.length());
        handler.removeCallbacks(searchRun);
        query = text.trim();
        searchRun.run();
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private View searchBox() {
        android.widget.EditText search = new android.widget.EditText(a);
        searchBox = search;
        search.setSingleLine(true);
        search.setHint("Buscar canal");
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 21 * ui.k);
        search.setTypeface(Ui.medium);
        search.setTextColor(0xFF1C1C1E);
        search.setHintTextColor(0xFF8E8E93);
        search.setPadding(ui.px(20), 0, ui.px(20), 0);
        GradientDrawable sn = TvUi.round(ui.dark ? 0xE6FFFFFF : 0xFFFFFFFF, 16 * ui.k), sf = TvUi.round(0xFFFFFFFF, 16 * ui.k);
        sf.setStroke(ui.px(4), TvUi.ACCENT);
        search.setBackground(sn);
        search.setOnFocusChangeListener((v, f) -> search.setBackground(f ? sf : sn));
        if (selection == null && !query.isEmpty()) search.setText(query);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int x, int y, int z) {}
            @Override public void onTextChanged(CharSequence c, int x, int y, int z) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                handler.removeCallbacks(searchRun);
                query = e.toString().trim();
                handler.postDelayed(searchRun, 350);
            }
        });
        return search;
    }

    private final Runnable searchRun = () -> {
        if (query.length() < 2) return;
        selection = null;
        if (selected != null) selected.set(false);
        selected = null;
        fill(0);
    };

    private TvUi.Chip chip(String label, String id, int count) {
        TvUi.Chip c = ui.chip(label + (count > 0 ? "  · " + count : ""), id.equals(selection), null);
        c.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        c.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.setOnFocusChangeListener((v, f) -> { c.paint(); if (f) select(id, c); });
        c.setOnClickListener(v -> { if (list.getChildCount() > 0) list.getChildAt(0).requestFocus(); });
        cats.addView(c, ui.margins(MATCH, WRAP, 0, 0, 0, 8));
        if (id.equals(selection)) selected = c;
        return c;
    }

    private void select(String id, TvUi.Chip chip) {
        if (id.equals(selection) && chip == selected) return;
        boolean same = id.equals(selection);
        selection = id;
        query = "";
        if (selected != null && selected != chip) selected.set(false);
        selected = chip;
        chip.set(true);
        if (!same || list.getChildCount() == 0) fill(0);
    }

    private List<Lineup.Channel> channels() {
        if (lineup == null) return new ArrayList<>();
        if (selection == null) return lineup.search(query, 200);
        if (FAV.equals(selection)) return lineup.favourites();
        if (selection.startsWith("list:")) {
            String[] p = selection.split(":", 3);
            for (Iptv.Source s : iptv.sources()) if (String.valueOf(s.id).equals(p[1])) return lineup.of(iptv.category(s, "live", p[2]));
            return new ArrayList<>();
        }
        return lineup.group(selection);
    }

    private String title() {
        if (selection == null) return "Busca";
        if (selection.startsWith("list:")) return selection.split(":", 3)[2];
        return selection;
    }

    /** MENU: tests the channels on screen (how fast each one's best source answers) and writes it next to each one. */
    void test() {
        List<Lineup.Channel> all = channels();
        int n = Math.min(all.size(), 12);
        android.widget.Toast.makeText(a, "Testando " + n + " canais…", android.widget.Toast.LENGTH_SHORT).show();
        int tested = 0;
        List<View> cells = new ArrayList<>();
        for (int c = 0; c < list.getChildCount(); c++) {
            View p = list.getChildAt(c);
            if (p.getTag() == PAIR) for (int j = 0; j < ((LinearLayout) p).getChildCount(); j++) cells.add(((LinearLayout) p).getChildAt(j));
        }
        for (View row : cells) {
            if (tested >= 12) break;
            if (!(row.getTag() instanceof Lineup.Channel)) continue;
            tested++;
            Iptv.Version ch = ((Lineup.Channel) row.getTag()).best();
            TextView star = (TextView) ((LinearLayout) row).getChildAt(((LinearLayout) row).getChildCount() - 1);
            star.setText("…");
            // One at a time, with a pause: the server allows few connections at once, so parallel tests would fail.
            probes.execute(() -> {
                long ts = Iptv.probe(ch, false);
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}   // the provider frees the connection
                handler.post(() -> {
                    star.setText(ts >= 0 ? String.format(Locale.US, "%.1fs", ts / 1000f) : "✗" + (ts < -1 ? -ts : ""));
                    star.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * ui.k);
                    star.setTextColor(ts >= 0 && ts < 3000 ? 0xFF21D07A : ts >= 0 ? 0xFFFFB340 : 0xFFFF6B6B);
                });
            });
        }
    }

    private final java.util.concurrent.ExecutorService probes = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void fill(int from) {
        if (from == 0) list.removeAllViews();
        else list.removeViewAt(list.getChildCount() - 1);   // the "more" row
        pair = null;
        List<Lineup.Channel> all = channels();
        if (all.isEmpty()) {
            list.addView(ui.text(selection == null ? "Nenhum canal com “" + query + "”." : FAV.equals(selection)
                    ? "Nenhum favorito ainda: segure OK num canal para favoritar." : "Nenhum canal aqui.", 21, Ui.medium, ui.dim));
            return;
        }
        if (Lineup.GAMES.equals(selection)) { games(all); return; }
        int to = Math.min(all.size(), from + PAGE);
        View firstNew = null;
        for (int i = from; i < to; i++) {
            View r = row(all, i);
            cell(r);
            if (firstNew == null) firstNew = r;
        }
        if (to < all.size()) {
            TvUi.Chip more = ui.chip("Mostrar mais " + Math.min(PAGE, all.size() - to) + " (faltam " + (all.size() - to) + ")", false, null);
            more.setOnClickListener(v -> fill(to));
            list.addView(more, ui.margins(WRAP, WRAP, 0, 10, 0, 0));
        }
        if (from > 0 && firstNew != null) firstNew.requestFocus();   // the "more" button is gone: keep the focus here
    }

    /** Today's games, all of them, in sections: on now, later, finished. */
    private void games(List<Lineup.Channel> all) {
        long now = System.currentTimeMillis();
        int[] counts = new int[4];
        for (Lineup.Channel ch : all) counts[ch.state(now)]++;
        int last = -1;
        for (int i = 0; i < all.size(); i++) {
            int st = all.get(i).state(now);
            if (st != last) {
                last = st;
                String title = st == Lineup.LIVE ? "Ao vivo agora" : st == Lineup.PRE ? "Mais tarde" : st == Lineup.ENDED ? "Encerrados" : "Outros";
                LinearLayout head = ui.row();
                TextView t = ui.text(title, 24, Ui.semibold, st == Lineup.LIVE ? 0xFFE5252A : ui.ink);
                head.addView(t);
                head.addView(ui.text(counts[st] + (counts[st] == 1 ? " jogo" : " jogos"), 18, Ui.medium, ui.dim), ui.margins(WRAP, WRAP, 12, 0, 0, 0));
                list.addView(head, ui.margins(MATCH, WRAP, 6, list.getChildCount() == 0 ? 2 : 22, 0, 12));
                pair = null;
            }
            cell(row(all, i));
        }
    }

    private static final Object PAIR = new Object();

    /** Adds a channel to the list, two per line (a lone last one keeps half the width, next to a spacer). */
    private void cell(View r) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1);
        if (pair != null && pair.getChildCount() == 2 && "spacer".equals(pair.getChildAt(1).getTag())) {
            pair.removeViewAt(1);
            lp.leftMargin = ui.px(10);
            pair.addView(r, lp);
            return;
        }
        pair = ui.row();
        pair.setTag(PAIR);
        pair.setBaselineAligned(false);
        list.addView(pair, ui.margins(MATCH, ui.px(92), 0, 0, 0, 10));
        pair.addView(r, lp);
        View spacer = new View(a);
        spacer.setTag("spacer");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, MATCH, 1);
        sp.leftMargin = ui.px(10);
        pair.addView(spacer, sp);
    }

    private View row(List<Lineup.Channel> all, int i) {
        Lineup.Channel ch = all.get(i);
        LinearLayout r = ui.row();
        r.setTag(ch);
        r.setFocusable(true);
        r.setClickable(true);
        r.setLongClickable(true);
        r.setPadding(ui.px(14), 0, ui.px(16), 0);
        GradientDrawable normal = TvUi.round(ui.chip, 18 * ui.k), focused = TvUi.round(0xFFFFFFFF, 18 * ui.k);
        if (!ui.dark) focused.setStroke(ui.px(3), TvUi.ACCENT);
        r.setBackground(normal);
        FrameLayout logo = new FrameLayout(a);
        logo.setBackground(TvUi.round(ui.dark ? 0x1AFFFFFF : 0xFFFFFFFF, 12 * ui.k));
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setPadding(ui.px(6), ui.px(6), ui.px(6), ui.px(6));
        Img.into(img, ch.icon, ui.px(96));
        logo.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
        r.addView(logo, ui.margins(ui.px(96), ui.px(60), 0, 0, 14, 0));
        LinearLayout names = ui.column();
        TextView name = ui.line(ch.name, 20, Ui.semibold, ui.ink, 1);
        TextView meta = ui.line(ch.meta(), 15, Ui.medium, ui.dim, 1);
        names.addView(name);
        names.addView(meta, ui.margins(MATCH, WRAP, 0, 4, 0, 0));
        r.addView(names, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView star = ui.text(lineup.favourite(ch) ? "★" : "", 22, Ui.semibold, TvUi.ACCENT);
        r.addView(star, ui.margins(WRAP, WRAP, 10, 0, 0, 0));
        r.setOnFocusChangeListener((v, f) -> {
            r.setBackground(f ? focused : normal);
            name.setTextColor(f ? 0xFF1C1C1E : ui.ink);
            meta.setTextColor(f ? 0xFF6B6B70 : ch.state(System.currentTimeMillis()) == Lineup.LIVE ? 0xFFE5252A : ui.dim);
            if (f) showGuide(ch);
        });
        r.setOnClickListener(v -> { if (!Lineup.notNow(a, ch)) watch(all, i); });
        int st = ch.state(System.currentTimeMillis());
        if (st == Lineup.ENDED) r.setAlpha(0.5f);
        if (st == Lineup.LIVE) meta.setTextColor(0xFFE5252A);
        r.setOnLongClickListener(v -> {
            ChannelMenu.show(a, lineup, ch, () -> watch(all, i), k -> watch(all, i, k), () -> {
                star.setText(lineup.favourite(ch) ? "★" : "");
                if (FAV.equals(selection)) show(lineup);
            });
            return true;
        });
        return r;
    }

    private Runnable guideRun;

    private void showGuide(Lineup.Channel ch) {
        int gen = ++generation;
        guideTitle.setText(ch.name);
        org.json.JSONArray alts = Lineup.toJson(ch).optJSONArray("alts");
        int n = alts == null ? 0 : alts.length();
        guideSource.setText(n == 0 ? "" : "Fonte: " + alts.optJSONObject(0).optString("label") + (n > 1 ? "  ·  " + n + " opções" : ""));
        guideNow.setText("Buscando a programação…");
        handler.removeCallbacks(guideRun);
        // Wait a moment: running down the list shouldn't ask the server for every channel passed.
        guideRun = () -> Movies.get(a).run(() -> {
            for (Iptv.Version v : ch.versions) {   // the first source that has a guide
                List<Iptv.Program> p = iptv.guide(v);
                if (!p.isEmpty()) return p;
            }
            return new ArrayList<Iptv.Program>();
        }, programs -> {
            if (gen != generation || !alive) return;
            if (programs == null || programs.isEmpty()) { guideNow.setText("Sem programação para este canal."); return; }
            StringBuilder s = new StringBuilder();
            java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", Locale.US);
            long now = System.currentTimeMillis();
            int shown = 0;
            for (int i = 0; i < programs.size() && shown < 2; i++) {
                Iptv.Program p = programs.get(i);
                if (p.end <= now) continue;
                shown++;
                boolean on = p.start <= now && p.end > now;
                s.append(on ? "AGORA  " : "DEPOIS  " + hm.format(new java.util.Date(p.start)) + "  ").append(p.title);
                if (on && p.end > p.start) s.append("  (").append((p.end - now) / 60_000).append(" min restantes)");
                s.append('\n');
            }
            guideNow.setText(s.toString().trim());
        });
        handler.postDelayed(guideRun, 350);
    }

    /** Plays the channel; ▲▼ in the player go through this list of channels. */
    private void watch(List<Lineup.Channel> all, int i) { watch(all, i, 0); }

    /** From the source chosen (`alt`: index in its sources). */
    private void watch(List<Lineup.Channel> all, int i, int alt) {
        // Up to 100 on each side: big groups would make the player's start message too large.
        int from = Math.max(0, i - 100), to = Math.min(all.size(), i + 101);
        PlayerActivity.live(a, Lineup.toJson(all.subList(from, to)), i - from, title(), alt);
    }
}
