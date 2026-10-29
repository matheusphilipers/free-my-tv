package io.freemytv;

import android.app.Activity;
import android.graphics.Outline;
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

/** An actor (or director): photo and biography, then what's on your IPTV lists, their movies and their shows. Extra: "id". */
public class PersonActivity extends Activity {
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        return Voice.openSearch(this, e) || super.dispatchKeyEvent(e);
    }

    private TvUi ui;
    private Movies movies;
    private FrameLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        ui = new TvUi(this, true);
        movies = Movies.get(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1117);
        TextView wait = ui.text("Carregando…", 28, Ui.medium, 0xB3FFFFFF);
        root.addView(wait, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        setContentView(root);
        int id = getIntent().getIntExtra("id", 0);
        if (id == 0) { finish(); return; }
        movies.run(() -> movies.person(id), p -> {
            if (isFinishing()) return;
            if (p == null) { wait.setText("Não deu para carregar agora."); return; }
            show(p);
        });
    }

    private void show(Movies.PersonDetail p) {
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout page = ui.column();
        page.setClipChildren(false);
        page.setPadding(ui.px(96), ui.px(80), ui.px(96), ui.px(90));
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout top = ui.row();
        top.setGravity(Gravity.TOP);
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setBackground(TvUi.round(0xFF2A2D35, 130 * ui.k));
        photo.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        photo.setClipToOutline(true);
        Img.into(photo, Img.tmdb(p.photo, "w342"), ui.px(260));
        top.addView(photo, ui.margins(ui.px(260), ui.px(260), 0, 0, 56, 0));
        LinearLayout info = ui.column();
        String role = p.known.equals("Acting") ? "ATUAÇÃO" : p.known.equals("Directing") ? "DIREÇÃO" : p.known.toUpperCase(java.util.Locale.ROOT);
        info.addView(ui.text(role, 19, Ui.semibold, TvUi.ACCENT));
        info.addView(ui.line(p.name, 62, Ui.semibold, 0xFFFFFFFF, 1), ui.margins(MATCH, WRAP, 0, 10, 0, 0));
        String facts = (p.born.isEmpty() ? "" : "Nasceu em " + p.born) + (p.place.isEmpty() ? "" : (p.born.isEmpty() ? "" : " · ") + p.place);
        if (!facts.isEmpty()) info.addView(ui.text(facts, 22, Ui.medium, 0xB3FFFFFF), ui.margins(WRAP, WRAP, 0, 12, 0, 0));
        TextView bio = ui.line(p.bio.isEmpty() ? "Sem biografia." : p.bio, 22, Ui.regular, 0xD9FFFFFF, 6);
        bio.setLineSpacing(ui.px(6), 1f);
        info.addView(bio, ui.margins(ui.px(1180), WRAP, 0, 18, 0, 0));
        if (p.bioEnglish) {
            TextView note = ui.text("em inglês · traduzindo…", 15, Ui.medium, 0x80FFFFFF);
            info.addView(note, ui.margins(WRAP, WRAP, 0, 8, 0, 0));
            movies.run(() -> movies.translate(p.bio), pt -> {
                if (pt == null || pt.isEmpty()) { note.setText("em inglês"); return; }
                bio.setText(pt);
                note.setText("traduzido do inglês");
            });
        }
        top.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1));
        page.addView(top);

        // What you can watch now (on your IPTV lists), then all their movies and shows.
        Iptv iptv = Iptv.get(this);
        List<Movies.Title> mine = new ArrayList<>(), films = new ArrayList<>(), shows = new ArrayList<>();
        for (Movies.Title t : p.titles) {
            if (iptv.has(t)) mine.add(t);
            (t.kind.equals("tv") ? shows : films).add(t);
        }
        View first = null;
        if (!mine.isEmpty()) first = row(page, "Nas suas listas IPTV", mine.size() + " para assistir agora", mine);
        View f = row(page, "Filmes", films.size() + " títulos", films);
        if (first == null) first = f;
        View s = row(page, "Séries", shows.size() + " títulos", shows);
        if (first == null) first = s;
        if (first != null) first.requestFocus();
    }

    private View row(LinearLayout page, String title, String sub, List<Movies.Title> items) {
        if (items.isEmpty()) return null;
        LinearLayout head = ui.row();
        head.addView(ui.text(title, 30, Ui.semibold, 0xFFFFFFFF));
        head.addView(ui.text(sub, 20, Ui.medium, 0x8CFFFFFF), ui.margins(WRAP, WRAP, 16, 0, 0, 0));
        page.addView(head, ui.margins(WRAP, WRAP, 0, 56, 0, 6));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(ui.px(12), ui.px(16), ui.px(60), ui.px(20));
        LinearLayout line = ui.row();
        line.setGravity(Gravity.TOP);
        line.setClipChildren(false);
        View first = null;
        for (int i = 0; i < items.size() && i < 40; i++) {
            Movies.Title t = items.get(i);
            View card = ui.card(t, ui.px(176), () -> TvUi.open(this, t));
            line.addView(card, ui.margins(ui.px(176), WRAP, 0, 0, 24, 0));
            if (first == null) first = ((LinearLayout) card).getChildAt(0);
        }
        hs.addView(line);
        page.addView(hs);
        return first;
    }
}
