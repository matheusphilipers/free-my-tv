package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Sports screen: one tab per team (your club, your NHL team, Formula 1). ← and → change tabs, Back closes.
 * A club's tab: the game on now or next (large), where the team stands, the next games with their channels and the
 * full table. F1: this weekend's sessions, the next round, the rest of the season and both championships.
 */
final class SportsView extends View implements HomeData.Listener {
    private final HomeData data;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> tabs = new ArrayList<>();
    private int tab;
    private float k = 1;
    private SportsDraw sd;
    private final Paint bg = new Paint();
    private final RectF r = new RectF();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            invalidate();   // "in 3 days", the session on now
            handler.postDelayed(this, 15_000);
        }
    };

    SportsView(Context c, int startKind) {
        super(c);
        data = HomeData.get(c);
        setFocusable(true);
        tabKinds();
        tab = Math.max(0, tabs.indexOf(startKind));
    }

    private void tabKinds() {
        tabs.clear();
        Sports.All a = data.sports;
        if (a == null) return;
        if (a.soccer != null) tabs.add(0);
        if (a.hockey != null) tabs.add(1);
        if (a.f1 != null) tabs.add(2);
    }

    void resume() {
        running = true;
        data.addListener(this);
        SportsLogos.setListener(this::invalidate);
        data.refreshIfStale();
        handler.postDelayed(tick, 15_000);
        invalidate();
    }

    void pause() {
        running = false;
        data.removeListener(this);
        handler.removeCallbacks(tick);
    }

    @Override public void onData() {
        int kind = tabs.isEmpty() ? -1 : tabs.get(tab);
        tabKinds();
        tab = Math.max(0, tabs.indexOf(kind));
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        sd = new SportsDraw(getContext(), k);
    }

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (tab > 0) { tab--; invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (tab < tabs.size() - 1) { tab++; invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) watch();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return false;
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (sd == null) return;
        boolean night = data.isNight(System.currentTimeMillis());
        sd.night = night ? 1 : 0;
        bg.setColor(night ? 0xFF0E0F13 : 0xFFE9EBEF);
        c.drawRect(0, 0, getWidth(), getHeight(), bg);
        float x = 72 * k;
        sd.text(Ui.semibold, 44, sd.ink(false));
        c.drawText(Ui.tr("Sports"), x, 104 * k, sd.text);
        float tx = x + sd.text.measureText(Ui.tr("Sports")) + 36 * k;
        Sports.All a = data.sports;
        if (a == null || tabs.isEmpty()) {
            sd.text(Ui.medium, 26, sd.dim(false));
            c.drawText(Ui.tr(a == null ? "Loading games…" : "No games scheduled"), x, 220 * k, sd.text);
            return;
        }
        // Tabs
        for (int i = 0; i < tabs.size(); i++) {
            int kind = tabs.get(i);
            String name = kind == 0 ? a.soccer.label : kind == 1 ? a.hockey.label : Ui.tr("Formula 1");
            sd.text(Ui.semibold, 22, i == tab ? 0xFFFFFFFF : sd.ink(false));
            float w = sd.text.measureText(name) + 52 * k;
            r.set(tx, 58 * k, tx + w, 110 * k);
            if (i == tab) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(kind == 2 ? SportsDraw.F1_RED : 0xFF111111);
                c.drawRoundRect(r, 14 * k, 14 * k, p);
            }
            c.drawText(name, tx + 26 * k, 92 * k, sd.text);
            tx += w + 10 * k;
        }
        sd.text(Ui.medium, 20, sd.faint(false));
        sd.right(c, Ui.tr("← → change tab") + (Iptv.get(getContext()).connected() ? "   ·   " + Ui.tr("OK: where to watch") : ""), getWidth() - 72 * k, 92 * k);
        int kind = tabs.get(tab);
        if (kind == 2) {
            a.f1.roll(System.currentTimeMillis());
            drawF1(c, a.f1);
        }
        else drawClub(c, kind == 0 ? a.soccer : a.hockey, kind == 0);
    }

    // MARK: - Where to watch (your IPTV lists)

    private static Sports.Match featured(Sports.Club club) {
        return club.live != null ? club.live
                : club.recent != null && System.currentTimeMillis() - club.recent.at < 6 * 60 * 60_000L ? club.recent
                : club.upcoming.isEmpty() ? club.recent : club.upcoming.get(0);
    }

    /** The channel isn't confirmed yet: where Brazilian football usually is (the pay-per-view first). */
    private static List<String> usual(Sports.Match m, int kind) {
        return m.tv.isEmpty() && kind == 0 ? java.util.Arrays.asList("Premiere", "SporTV", "Globo") : new ArrayList<>();
    }

    /** OK: the channels of your lists that show the game on screen (or this F1 weekend). */
    private void watch() {
        Sports.All a = data.sports;
        if (a == null || tabs.isEmpty() || !(getContext() instanceof android.app.Activity)) return;
        android.app.Activity act = (android.app.Activity) getContext();
        int kind = tabs.get(tab);
        if (kind == 2) {
            Sports.F1 f = a.f1;
            f.roll(System.currentTimeMillis());
            Sports.Race race = f.current;
            String sub = f.live != null ? f.live.name + " · " + Ui.tr("LIVE") : race != null && !race.sessions.isEmpty() ? Sports.when(race.sessions.get(race.sessions.size() - 1).at) : "";
            Watch.show(act, Ui.tr("Formula 1") + (race == null ? "" : " · " + race.name), sub, f.tv, new ArrayList<>(),
                    java.util.Arrays.asList("F1", "Formula 1"), "f1");
            return;
        }
        Sports.Club club = kind == 0 ? a.soccer : a.hockey;
        Sports.Match m = featured(club);
        if (m == null) return;
        List<String> teams = new ArrayList<>(java.util.Arrays.asList(m.home.name, m.away.name, m.home.abbrev, m.away.abbrev, club.label));
        String title = m.home.name + " × " + m.away.name;
        String sub = (m.comp.isEmpty() ? "" : m.comp + " · ") + (m.state == Sports.LIVE ? Ui.tr("LIVE") : Sports.when(m.at));
        Watch.show(act, title, sub, m.tv, teams, usual(m, kind), kind == 0 ? "soccer" : "hockey");
    }

    // MARK: - A club's tab

    private void drawClub(Canvas c, Sports.Club club, boolean soccer) {
        float left = 72 * k, right = 1848 * k, tableW = 600 * k, gap = 32 * k;
        float colR = right - tableW - gap;
        Sports.Match featured = featured(club);
        r.set(left, 150 * k, colR, 530 * k);
        sd.heroCard(c, r, featured, club);
        // Where the team stands
        float bw = (colR - left - 20 * k) / 2;
        r.set(left, 556 * k, left + bw, 686 * k);
        sd.panel(c, r);
        sd.text(Ui.semibold, 18, sd.dim(false));
        c.drawText(club.league.toUpperCase(Ui.locale()), r.left + 28 * k, r.top + 40 * k, sd.text);
        sd.text(Ui.semibold, 32, sd.ink(false));
        c.drawText(club.pos > 0 ? Ui.trf("{pos}th place", "pos", club.pos) : "—", r.left + 28 * k, r.top + 84 * k, sd.text);
        sd.text(Ui.medium, 19, sd.dim(false));
        c.drawText(club.record + (club.pts > 0 ? " · " + Ui.trf("{n} pts", "n", club.pts) : ""), r.left + 28 * k, r.top + 114 * k, sd.text);
        r.set(left + bw + 20 * k, 556 * k, colR, 686 * k);
        sd.panel(c, r);
        String[] cup = club.cup.isEmpty() ? new String[] {Ui.tr("NEXT GAME"), club.upcoming.isEmpty() ? "—" : Sports.when(club.upcoming.get(0).at)}
                : club.cup.contains(" · ") ? club.cup.split(" · ", 2) : new String[] {Ui.tr("FORM"), club.cup};
        sd.text(Ui.semibold, 18, sd.dim(false));
        c.drawText(cup[0].toUpperCase(Ui.locale()), r.left + 28 * k, r.top + 40 * k, sd.text);
        sd.text(Ui.semibold, 32, soccer || !club.cup.contains("loss") ? sd.ink(false) : SportsDraw.LIVE_RED);
        sd.left(c, cup[1], r.left + 28 * k, r.top + 84 * k, r.width() - 56 * k);
        // Next games
        r.set(left, 712 * k, colR, 1040 * k);
        sd.panel(c, r);
        sd.text(Ui.semibold, 18, sd.dim(false));
        c.drawText(Ui.tr("NEXT GAMES"), r.left + 28 * k, r.top + 40 * k, sd.text);
        float y = r.top + 62 * k;
        int shown = 0;
        for (Sports.Match m : club.upcoming) {
            if (m == featured) continue;
            if (shown++ >= 4) break;
            sd.line(c, r.left + 28 * k, y, r.right - 28 * k);
            float base = y + 40 * k, x = r.left + 28 * k;
            sd.text(Ui.semibold, 20, sd.ink(false));
            sd.left(c, Sports.when(m.at), x, base, 210 * k);
            sd.crest(c, m.them().logo, m.them().abbrev, x + 240 * k, y + 32 * k, 40 * k, false);
            sd.text(Ui.medium, 21, sd.ink(false));
            String title = (m.weHome ? club.label + " × " + m.them().name : m.them().name + " × " + club.label);
            sd.left(c, title, x + 276 * k, base, 300 * k);
            sd.text(Ui.medium, 18, sd.dim(false));
            sd.left(c, m.comp, x + 600 * k, base, 230 * k);
            sd.text(Ui.medium, 18, sd.faint(false));
            sd.left(c, m.tv.isEmpty() ? Ui.tr("Channel to be confirmed") : TextUtils.join(" · ", m.tv), x + 850 * k, base, r.right - 28 * k - x - 850 * k);
            y += 62 * k;
        }
        if (shown == 0) {
            sd.text(Ui.medium, 20, sd.dim(false));
            c.drawText(Ui.tr("No games scheduled"), r.left + 28 * k, r.top + 100 * k, sd.text);
        }
        // Table
        r.set(right - tableW, 150 * k, right, 1040 * k);
        sd.panel(c, r);
        float tl = r.left + 26 * k, tr = r.right - 26 * k;
        sd.text(Ui.semibold, 18, sd.dim(false));
        sd.left(c, Ui.trf("STANDINGS · {league}", "league", club.league.toUpperCase(Ui.locale())), tl + 10 * k, r.top + 42 * k, tableW - 230 * k);
        sd.right(c, Ui.tr("GP"), tr - 108 * k, r.top + 42 * k);
        sd.right(c, Ui.tr("GD"), tr - 58 * k, r.top + 42 * k);
        sd.right(c, Ui.tr("PTS"), tr - 10 * k, r.top + 42 * k);
        int n = club.table.size();
        float rowH = Math.min(40 * k, (r.height() - 110 * k) / Math.max(1, n));
        float ty = r.top + 62 * k;
        for (Sports.Row row : club.table) {
            int ink = sd.ink(false);
            if (row.mine) {
                sd.highlight(c, tl, ty, tr, ty + rowH - 2 * k);
                ink = sd.night > 0.5f ? 0xFF1C1C1E : 0xFFFFFFFF;
            }
            int zone = soccer ? (row.pos <= 6 ? 0xFF0A84FF : row.pos <= 12 ? 0xFF30B0C7 : row.pos > n - 4 ? 0xFFD70015 : 0)
                    : (row.pos <= 3 ? 0xFF0A84FF : 0);
            if (zone != 0) sd.bar(c, tl + 8 * k, ty + rowH * 0.22f, 6 * k, rowH * 0.56f, zone);
            float base = ty + rowH * 0.66f;
            sd.text(Ui.semibold, 18, ink);
            sd.right(c, String.valueOf(row.pos), tl + 50 * k, base);
            sd.crest(c, row.logo, row.abbrev, tl + 76 * k, ty + rowH / 2, rowH * 0.62f, false);
            sd.text(Ui.medium, 19, ink);
            sd.left(c, row.name, tl + 100 * k, base, tableW - 300 * k);
            sd.text(Ui.medium, 18, Ui.alpha(ink, 0.7f));
            sd.right(c, String.valueOf(row.played), tr - 108 * k, base);
            sd.right(c, (row.diff > 0 ? "+" : "") + row.diff, tr - 58 * k, base);
            sd.text(Ui.semibold, 19, ink);
            sd.right(c, String.valueOf(row.pts), tr - 10 * k, base);
            ty += rowH;
        }
        if (soccer) {
            float ly = r.bottom - 26 * k, lx = tl + 10 * k;
            String[] names = {Ui.tr("Libertadores"), Ui.tr("Sudamericana"), Ui.tr("Relegation")};
            int[] colors = {0xFF0A84FF, 0xFF30B0C7, 0xFFD70015};
            sd.text(Ui.medium, 15, sd.dim(false));
            for (int i = 0; i < 3; i++) {
                sd.bar(c, lx, ly - 11 * k, 10 * k, 10 * k, colors[i]);
                c.drawText(names[i], lx + 16 * k, ly, sd.text);
                lx += 16 * k + sd.text.measureText(names[i]) + 24 * k;
            }
        }
    }

    // MARK: - Formula 1

    private void drawF1(Canvas c, Sports.F1 f) {
        float left = 72 * k, right = 1848 * k, sideW = 600 * k, gap = 32 * k;
        float colR = right - sideW - gap;
        Sports.Race race = f.current;
        r.set(left, 150 * k, colR, 560 * k);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF15151E);
        c.drawRoundRect(r, 32 * k, 32 * k, p);
        float x = r.left + 40 * k;
        if (race != null) {
            boolean now = f.live != null;
            sd.text(Ui.semibold, 20, 0xFFFF5A4F);
            c.drawText(now ? Ui.tr("LIVE") : Ui.tr("THIS WEEKEND"), x, r.top + 52 * k, sd.text);
            sd.text(Ui.semibold, 50, 0xFFFFFFFF);
            sd.left(c, race.name, x, r.top + 112 * k, r.width() - 80 * k);
            sd.text(Ui.medium, 22, 0xB3FFFFFF);
            c.drawText(race.circuit + (race.place.isEmpty() ? "" : " · " + race.place), x, r.top + 150 * k, sd.text);
            if (!f.tv.isEmpty()) {
                float px = r.right - 40 * k;
                for (int i = f.tv.size() - 1; i >= 0; i--) {
                    px -= sd.pillWidth(f.tv.get(i));
                    sd.pill(c, f.tv.get(i), px, r.top + 60 * k, true, false);
                    px -= 10 * k;
                }
            }
            int n = race.sessions.size();
            float tw = (r.width() - 80 * k - 14 * k * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                Sports.Session s = race.sessions.get(i);
                boolean isRace = i == n - 1, live = s == f.live;
                RectF t = new RectF(x + i * (tw + 14 * k), r.top + 196 * k, x + i * (tw + 14 * k) + tw, r.bottom - 34 * k);
                p.setColor(live ? SportsDraw.LIVE_RED : isRace ? SportsDraw.F1_RED : 0x14FFFFFF);
                c.drawRoundRect(t, 20 * k, 20 * k, p);
                float tx = t.left + 22 * k;
                sd.text(Ui.semibold, 18, 0xBFFFFFFF);
                sd.left(c, Sports.day(s.at), tx, t.top + 42 * k, tw - 44 * k);
                sd.text(Ui.semibold, 23, 0xFFFFFFFF);
                sd.left(c, s.name, tx, t.top + 82 * k, tw - 44 * k);
                sd.text(Ui.semibold, 40, 0xFFFFFFFF);
                c.drawText(Sports.time(s.at), tx, t.top + 136 * k, sd.text);
                if (live) {
                    sd.text(Ui.semibold, 17, 0xFFFFFFFF);
                    c.drawText(Ui.tr("LIVE"), tx, t.bottom - 20 * k, sd.text);
                }
            }
        }
        // Next round and the rest of the season
        float bw = colR - left - 420 * k - 20 * k;
        r.set(left, 586 * k, left + bw, 1040 * k);
        sd.panel(c, r);
        x = r.left + 30 * k;
        sd.text(Ui.semibold, 18, sd.dim(false));
        c.drawText(Ui.tr("NEXT ROUND"), x, r.top + 44 * k, sd.text);
        if (f.next != null) {
            sd.text(Ui.semibold, 34, sd.ink(false));
            sd.left(c, f.next.name, x, r.top + 92 * k, r.width() - 60 * k);
            sd.text(Ui.medium, 20, sd.dim(false));
            sd.left(c, f.next.circuit, x, r.top + 126 * k, r.width() - 60 * k);
            float y = r.top + 180 * k;
            for (Sports.Session s : f.next.sessions) {
                if (y > r.bottom - 30 * k) break;
                sd.line(c, x, y - 30 * k, r.right - 30 * k);
                boolean isRace = s == f.next.sessions.get(f.next.sessions.size() - 1);
                sd.text(Ui.medium, 20, sd.dim(false));
                c.drawText(s.name, x, y, sd.text);
                sd.text(Ui.semibold, 21, isRace ? SportsDraw.F1_RED : sd.ink(false));
                sd.right(c, Sports.when(s.at), r.right - 30 * k, y);
                y += 46 * k;
            }
        }
        r.set(left + bw + 20 * k, 586 * k, colR, 1040 * k);
        sd.panel(c, r);
        x = r.left + 30 * k;
        sd.text(Ui.semibold, 18, sd.dim(false));
        c.drawText(Ui.tr("LATER"), x, r.top + 44 * k, sd.text);
        float y = r.top + 96 * k;
        for (Sports.Race later : f.later) {
            if (y > r.bottom - 20 * k) break;
            sd.line(c, x, y - 32 * k, r.right - 30 * k);
            sd.text(Ui.medium, 20, sd.ink(false));
            sd.left(c, later.name, x, y, r.width() - 200 * k);
            sd.text(Ui.medium, 19, sd.dim(false));
            sd.right(c, Sports.day(later.at), r.right - 30 * k, y);
            y += 54 * k;
        }
        // Championships
        r.set(right - sideW, 150 * k, right, 600 * k);
        standings(c, r, Ui.tr("DRIVERS"), f.drivers, true);
        r.set(right - sideW, 624 * k, right, 1040 * k);
        standings(c, r, Ui.tr("CONSTRUCTORS"), f.teams, false);
    }

    private void standings(Canvas c, RectF box, String title, List<Sports.Standing> list, boolean drivers) {
        sd.panel(c, box);
        float l = box.left + 30 * k, rr = box.right - 30 * k;
        sd.text(Ui.semibold, 18, sd.dim(false));
        c.drawText(title, l, box.top + 42 * k, sd.text);
        sd.right(c, Ui.tr("PTS"), rr, box.top + 42 * k);
        float y = box.top + 60 * k, rowH = 37 * k;
        for (Sports.Standing s : list) {
            if (y + rowH > box.bottom - 10 * k) break;
            float base = y + rowH * 0.66f;
            sd.text(Ui.semibold, 18, sd.ink(false));
            sd.right(c, String.valueOf(s.pos), l + 26 * k, base);
            sd.bar(c, l + 40 * k, y + rowH * 0.2f, 6 * k, rowH * 0.6f, Sports.teamColor(s.teamId));
            sd.text(Ui.medium, 19, sd.ink(false));
            sd.left(c, s.name, l + 60 * k, base, drivers ? 280 * k : 400 * k);
            if (drivers) {
                sd.text(Ui.medium, 16, sd.dim(false));
                sd.left(c, s.team, l + 350 * k, base, 120 * k);
            }
            sd.text(Ui.semibold, 19, sd.ink(false));
            sd.right(c, String.valueOf(s.pts), rr, base);
            y += rowH;
        }
    }
}
