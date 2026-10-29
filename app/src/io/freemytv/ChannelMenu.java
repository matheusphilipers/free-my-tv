package io.freemytv;

import android.app.Activity;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import static io.freemytv.TvUi.MATCH;
import static io.freemytv.TvUi.WRAP;

/**
 * Holding OK on a channel (Ao vivo, the player's channel list): a small sheet with what you can do with it. Watch,
 * choose the source (which list and quality), favourite, add to the mosaic. The source page is also the player's
 * "Fonte" button.
 */
final class ChannelMenu {
    private ChannelMenu() {}

    interface Pick { void alt(int index); }

    /** The sheet for a channel. `watch` plays it from its best source, `pick` from a chosen one; `changed` after favouriting. */
    static void show(Activity a, Lineup lineup, Lineup.Channel ch, Runnable watch, Pick pick, Runnable changed) {
        Sheet s = new Sheet(a, "CANAL", ch.name, ch.status(System.currentTimeMillis()).isEmpty() ? ch.meta() : ch.status(System.currentTimeMillis()) + " · " + ch.meta());
        JSONArray alts = Lineup.toJson(ch).optJSONArray("alts");
        int n = alts == null ? 0 : alts.length();
        View first = s.item("▶   Assistir", n > 0 ? "Pela " + alts.optJSONObject(0).optString("label") + (n > 1 ? ", e se falhar pelas outras" : "") : "", () -> {
            if (Lineup.notNow(a, ch)) return;
            s.close();
            watch.run();
        });
        if (n > 1) s.item("☰   Escolher a fonte", n + " opções nas suas listas", () -> {
            s.close();
            sources(a, ch.name, alts, -1, k -> {
                if (Lineup.notNow(a, ch)) return;
                Lineup.remember(a, ch.key, alts.optJSONObject(k).optString("pick"));
                pick.alt(k);
            });
        });
        boolean fav = lineup.favourite(ch);
        s.item(fav ? "★   Tirar dos favoritos" : "☆   Favoritar", fav ? "Sai de ★ Favoritos" : "Fica em ★ Favoritos, no topo do Ao vivo", () -> {
            lineup.toggleFavourite(ch);
            s.close();
            android.widget.Toast.makeText(a, lineup.favourite(ch) ? "Adicionado aos favoritos" : "Tirado dos favoritos", android.widget.Toast.LENGTH_SHORT).show();
            if (changed != null) changed.run();
        });
        s.item("▦   Adicionar ao mosaico", "Assista até 4 canais ao mesmo tempo", () -> {
            s.close();
            MultiActivity.add(a, ch.key);
        });
        s.open(first);
    }

    /** The channel's sources: OK plays from that one (and it comes first next time). `current`: the one playing, or -1. */
    static void sources(Activity a, String title, JSONArray alts, int current, Pick pick) {
        Sheet s = new Sheet(a, "ESCOLHER A FONTE", title, "A que você escolher vira a primeira deste canal.");
        View first = null;
        for (int k = 0; k < alts.length(); k++) {
            final int index = k;
            String label = alts.optJSONObject(k).optString("label");
            String note = k == current ? "Tocando agora" : k == 0 ? "A melhor (ou a última que você escolheu)" : "";
            View r = s.item((k == current ? "✓   " : "      ") + label, note, () -> { s.close(); if (index != current) pick.alt(index); });
            if (first == null || k == current) first = r;
        }
        s.open(first);
    }

    /** A sheet that slides in from the right, over whatever screen. */
    static final class Sheet {
        final Activity a;
        final TvUi ui;
        final FrameLayout shade;
        final LinearLayout rows;
        private final ViewGroup content;
        private final int width;

        Sheet(Activity a, String kicker, String title, String subtitle) {
            this.a = a;
            ui = new TvUi(a, true);
            content = a.findViewById(android.R.id.content);
            width = ui.px(640);
            shade = new FrameLayout(a);
            shade.setBackgroundColor(0x99000000);
            shade.setClickable(true);
            shade.setTag(Watch.TAG);
            LinearLayout sheet = ui.column();
            sheet.setPadding(ui.px(44), ui.px(56), ui.px(44), ui.px(40));
            sheet.setBackground(TvUi.round(0xF51C1C1E, 0));
            sheet.addView(ui.text(kicker, 16, Ui.semibold, TvUi.ACCENT));
            sheet.addView(ui.line(title, 34, Ui.semibold, 0xFFFFFFFF, 2), ui.margins(MATCH, WRAP, 0, 12, 0, 0));
            if (subtitle != null && !subtitle.isEmpty()) sheet.addView(ui.line(subtitle, 18, Ui.medium, 0x99FFFFFF, 2), ui.margins(MATCH, WRAP, 0, 8, 0, 0));
            ScrollView sv = new ScrollView(a);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            rows = ui.column();
            rows.setPadding(0, ui.px(8), 0, ui.px(8));
            sv.addView(rows);
            sheet.addView(sv, ui.margins(MATCH, 0, 0, 26, 0, 0));
            ((LinearLayout.LayoutParams) sv.getLayoutParams()).weight = 1;
            sheet.addView(ui.text("OK escolhe · VOLTAR fecha", 15, Ui.medium, 0x66FFFFFF), ui.margins(WRAP, WRAP, 0, 14, 0, 0));
            shade.addView(sheet, new FrameLayout.LayoutParams(width, MATCH, Gravity.END));
        }

        /** A row: a label and, under it, a short note. */
        View item(String label, String note, Runnable click) {
            LinearLayout r = ui.column();
            r.setFocusable(true);
            r.setClickable(true);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(ui.px(22), ui.px(14), ui.px(22), ui.px(14));
            android.graphics.drawable.GradientDrawable normal = TvUi.round(0x14FFFFFF, 18 * ui.k), on = TvUi.round(0xFFFFFFFF, 18 * ui.k);
            r.setBackground(normal);
            TextView t = ui.line(label, 22, Ui.semibold, 0xFFFFFFFF, 1);
            r.addView(t);
            TextView sub = null;
            if (note != null && !note.isEmpty()) {
                sub = ui.line(note, 16, Ui.medium, 0x8CFFFFFF, 1);
                r.addView(sub, ui.margins(MATCH, WRAP, 0, 4, 0, 0));
            }
            final TextView s = sub;
            r.setOnFocusChangeListener((v, f) -> {
                r.setBackground(f ? on : normal);
                t.setTextColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
                if (s != null) s.setTextColor(f ? 0xFF6B6B70 : 0x8CFFFFFF);
            });
            r.setOnClickListener(v -> click.run());
            rows.addView(r, ui.margins(MATCH, WRAP, 0, 0, 0, 10));
            return r;
        }

        void open(View first) {
            content.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
            View sheet = shade.getChildAt(0);
            sheet.setTranslationX(width);
            sheet.animate().translationX(0).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            keys(shade);
            if (first != null) first.post(first::requestFocus);
        }

        void close() { if (shade.getParent() != null) content.removeView(shade); }

        /** BACK closes; the arrows stay inside the sheet (the screen under it mustn't take them). */
        private void keys(ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                if (v.isFocusable()) {
                    v.setOnKeyListener((x, code, e) -> {
                        if (code == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) close(); return true; }
                        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
                            View next = x.focusSearch(code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
                            if (next != null && isInside(next)) next.requestFocus();
                            return true;
                        }
                        return false;
                    });
                }
                if (v instanceof ViewGroup) keys((ViewGroup) v);
            }
        }

        private boolean isInside(View v) {
            View p = v;
            while (p != null) {
                if (p == shade) return true;
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
            return false;
        }
    }
}
