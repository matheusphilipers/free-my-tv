package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static io.freemytv.TvUi.MATCH;
import static io.freemytv.TvUi.WRAP;

/**
 * "Onde assistir": a sheet over the screen with your lists' channels for a game (the broadcaster's channel in each list,
 * today's game entries), each with where it comes from. OK plays it (▲▼ in the player go through the others); the choice
 * is remembered and comes first next time.
 */
final class Watch {
    private Watch() {}

    static final String TAG = "watch-sheet";

    /** The sheet is on screen (the screen under it shouldn't take the remote's keys). */
    static boolean isOpen(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        return content != null && content.findViewWithTag(TAG) != null;
    }

    /** Opens the sheet; `memory` names the choice to remember ("soccer", "hockey", "f1"). */
    static void show(Activity a, String title, String subtitle, List<String> broadcasters, List<String> teams, List<String> extra, String memory) {
        Iptv iptv = Iptv.get(a);
        if (!iptv.connected()) {
            android.widget.Toast.makeText(a, "Conecte suas listas IPTV em Assistir › Ajustes para assistir por aqui.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Lineup ready = Lineup.ready(a);
        if (ready != null) { open(a, ready, title, subtitle, broadcasters, teams, extra, memory); return; }
        android.widget.Toast.makeText(a, "Procurando os canais nas suas listas…", android.widget.Toast.LENGTH_SHORT).show();
        Movies.get(a).run(() -> Lineup.get(a), l -> { if (l != null && !a.isFinishing()) open(a, l, title, subtitle, broadcasters, teams, extra, memory); });
    }

    private static void open(Activity a, Lineup lineup, String title, String subtitle, List<String> broadcasters, List<String> teams,
                             List<String> extra, String memory) {
        List<Lineup.Option> options = lineup.watchOptions(broadcasters, teams, extra, memory);
        TvUi ui = new TvUi(a, true);
        ViewGroup content = a.findViewById(android.R.id.content);
        FrameLayout shade = new FrameLayout(a);
        shade.setBackgroundColor(0x99000000);
        shade.setClickable(true);
        shade.setTag(TAG);
        LinearLayout sheet = ui.column();
        sheet.setPadding(ui.px(48), ui.px(56), ui.px(48), ui.px(40));
        sheet.setBackground(TvUi.round(0xF51C1C1E, 0));
        sheet.addView(ui.text("ONDE ASSISTIR", 17, Ui.semibold, TvUi.ACCENT));
        sheet.addView(ui.line(title, 36, Ui.semibold, 0xFFFFFFFF, 2), ui.margins(MATCH, WRAP, 0, 12, 0, 0));
        if (!subtitle.isEmpty()) sheet.addView(ui.line(subtitle, 20, Ui.medium, 0x99FFFFFF, 2), ui.margins(MATCH, WRAP, 0, 8, 0, 0));
        Runnable close = () -> { if (shade.getParent() != null) content.removeView(shade); };
        View first = null;
        // The broadcasters that are apps on this TV (Globoplay, Prime Video, Disney+…): open them straight.
        LinearLayout apps = ui.row();
        for (String b : broadcasters) {
            String pkg = appOf(b);
            if (pkg == null) continue;
            Intent launch = a.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = a.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            final Intent go = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            TvUi.Chip chip = ui.button("Abrir " + b, false, () -> { close.run(); a.startActivity(go); });
            apps.addView(chip, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
            if (first == null) first = chip;
        }
        if (apps.getChildCount() > 0) {
            sheet.addView(ui.text("TAMBÉM PASSA NO APP", 15, Ui.semibold, 0x73FFFFFF), ui.margins(WRAP, WRAP, 0, 26, 0, 10));
            sheet.addView(apps);
        }
        View firstApp = first;
        first = null;
        if (options.isEmpty()) {
            String where = broadcasters.isEmpty() ? "o canal ainda não foi confirmado" : "passa no " + android.text.TextUtils.join(", ", broadcasters);
            sheet.addView(ui.line("Não achei esse canal nas suas listas (" + where + ").", 22, Ui.medium, 0xCCFFFFFF, 3), ui.margins(MATCH, WRAP, 0, 30, 0, 0));
            TvUi.Chip go = ui.button("Abrir Ao vivo › Esportes", true, () -> {
                close.run();
                a.startActivity(new Intent(a, ChannelsActivity.class).putExtra("group", Lineup.SPORTS));
            });
            sheet.addView(go, ui.margins(WRAP, WRAP, 0, 24, 0, 0));
            first = go;
        } else {
            String count = options.size() == 1 ? "1 opção nas suas listas" : options.size() + " opções nas suas listas";
            if (broadcasters.isEmpty() && !extra.isEmpty()) count = "Canal ainda não confirmado · " + count;
            sheet.addView(ui.text(count, 17, Ui.medium, 0x73FFFFFF),
                    ui.margins(WRAP, WRAP, 0, 26, 0, 12));
            ScrollView sv = new ScrollView(a);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            LinearLayout rows = ui.column();
            rows.setPadding(0, ui.px(6), 0, ui.px(6));
            sv.addView(rows);
            List<Lineup.Channel> chans = new ArrayList<>();
            for (Lineup.Option o : options) chans.add(o.ch);
            for (int i = 0; i < options.size(); i++) {
                final int index = i;
                View row = row(a, ui, options.get(i), () -> {
                    close.run();
                    lineup.rememberWatch(memory, options.get(index).ch);
                    PlayerActivity.live(a, Lineup.toJson(chans), index, "Onde assistir");
                });
                rows.addView(row, ui.margins(MATCH, ui.px(104), 0, 0, 0, 10));
                if (first == null) first = row;
            }
            sheet.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1));
        }
        if (first == null) first = firstApp;
        sheet.addView(ui.text("OK assiste · ▲▼ no player troca entre as opções · VOLTAR fecha", 15, Ui.medium, 0x66FFFFFF), ui.margins(WRAP, WRAP, 0, 16, 0, 0));
        shade.addView(sheet, new FrameLayout.LayoutParams(ui.px(760), MATCH, Gravity.END));
        shade.setFocusable(true);
        shade.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) { close.run(); return true; }
            return code == KeyEvent.KEYCODE_BACK;
        });
        content.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        sheet.setTranslationX(ui.px(760));
        sheet.animate().translationX(0).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        View f = first;
        // BACK from a row closes the sheet too.
        if (f != null) f.post(f::requestFocus);
        backCloses(shade, close);
    }

    private static void backCloses(ViewGroup g, Runnable close) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.isFocusable()) {
                v.setOnKeyListener((x, code, e) -> {
                    if (code != KeyEvent.KEYCODE_BACK) return false;
                    if (e.getAction() == KeyEvent.ACTION_UP) close.run();
                    return true;
                });
            }
            if (v instanceof ViewGroup) backCloses((ViewGroup) v, close);
        }
    }

    /** A streaming service's app on Android TV, by the name the broadcaster lists give it. */
    private static String appOf(String name) {
        String n = Lineup.norm(name);
        if (n.startsWith("globoplay")) return "com.globo.globotv";
        if (n.startsWith("prime video") || n.equals("amazon prime video")) return "com.amazon.amazonvideo.livingroom";
        if (n.startsWith("disney")) return "com.disney.disneyplus";
        if (n.equals("max") || n.startsWith("hbo max")) return "com.wbd.stream";
        if (n.startsWith("paramount")) return "com.cbs.ott";
        if (n.startsWith("youtube") || n.startsWith("cazetv") || n.startsWith("caze tv")) return "com.google.android.youtube.tv";
        if (n.startsWith("premiere play")) return "br.tv.horizonte.android.premierefc";
        if (n.startsWith("espn") && n.contains("star")) return "com.disney.disneyplus";
        return null;
    }

    private static View row(Activity a, TvUi ui, Lineup.Option o, Runnable play) {
        LinearLayout r = ui.row();
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(ui.px(16), 0, ui.px(20), 0);
        GradientDrawable normal = TvUi.round(0x14FFFFFF, 18 * ui.k), focused = TvUi.round(0xFFFFFFFF, 18 * ui.k);
        r.setBackground(normal);
        FrameLayout logo = new FrameLayout(a);
        logo.setBackground(TvUi.round(0x1AFFFFFF, 12 * ui.k));
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setPadding(ui.px(6), ui.px(6), ui.px(6), ui.px(6));
        Img.into(img, o.ch.icon, ui.px(110));
        logo.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
        r.addView(logo, ui.margins(ui.px(110), ui.px(66), 0, 0, 18, 0));
        LinearLayout names = ui.column();
        String status = o.ch.status(System.currentTimeMillis());
        TextView why = ui.text((o.why + (status.isEmpty() ? "" : " · " + status)).toUpperCase(Ui.locale()), 14, Ui.semibold, TvUi.ACCENT);
        TextView name = ui.line(o.ch.name, 23, Ui.semibold, 0xFFFFFFFF, 1);
        TextView meta = ui.line(o.ch.meta() + (o.ch.listCount() > 1 ? " · " + o.ch.lists() : ""), 16, Ui.medium, 0x8CFFFFFF, 1);
        names.addView(why);
        names.addView(name, ui.margins(MATCH, WRAP, 0, 4, 0, 0));
        names.addView(meta, ui.margins(MATCH, WRAP, 0, 4, 0, 0));
        r.addView(names, new LinearLayout.LayoutParams(0, WRAP, 1));
        r.setOnFocusChangeListener((v, f) -> {
            r.setBackground(f ? focused : normal);
            name.setTextColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
            meta.setTextColor(f ? 0xFF6B6B70 : 0x8CFFFFFF);
            why.setTextColor(f ? 0xFFB86E00 : TvUi.ACCENT);
        });
        r.setOnClickListener(v -> { if (!Lineup.notNow(a, o.ch)) play.run(); });
        if (o.ch.state(System.currentTimeMillis()) == Lineup.ENDED) r.setAlpha(0.5f);
        return r;
    }
}
