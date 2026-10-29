package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.List;

/**
 * Draws the sports cards (the rows on the home screen and the Sports screen), in the 1920×1080 space scaled by k.
 * Day: white glass and dark text; night: smoked glass and white text. A focused card is solid white; a live game is a
 * dark card with a red "LIVE" badge.
 */
final class SportsDraw {
    static final int LIVE_RED = 0xFFE5252A, F1_RED = 0xFFE10600;
    private final Context context;
    final float k;
    float night;   // 0 day, 1 night
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG), fit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Rect src = new Rect();

    SportsDraw(Context context, float k) {
        this.context = context;
        this.k = k;
        stroke.setStyle(Paint.Style.STROKE);
        shadow.setColor(0);
    }

    // MARK: - Colors

    int ink(boolean focused) { return focused ? 0xFF1C1C1E : blend(0xFF1C1C1E, 0xFFFFFFFF, night); }
    int dim(boolean focused) { return focused ? 0xFF6B6B70 : blend(0xFF6B6B70, 0xB3FFFFFF, night); }
    int faint(boolean focused) { return focused ? 0xFF8E8E93 : blend(0xFF8E8E93, 0x8CFFFFFF, night); }

    static int blend(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    // MARK: - Shapes

    /** The card's glass, solid white when focused (f = focus 0..1, it grows a little). Returns the drawn rect. */
    RectF card(Canvas c, RectF r, float f, int solid) {
        float s = 1 + 0.04f * f;
        tmp.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2, r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
        float radius = 28 * k * s;
        if (f > 0.01f || solid != 0) {
            shadow.setColor(0);
            shadow.setShadowLayer(30 * k, 0, 14 * k, Ui.alpha(0xFF000000, 0.12f + 0.16f * f));
            c.drawRoundRect(tmp, radius, radius, shadow);
        }
        if (solid != 0) {
            fill.setColor(solid);
            c.drawRoundRect(tmp, radius, radius, fill);
            if (f > 0.01f) {
                stroke.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * f));
                stroke.setStrokeWidth(5 * k);
                c.drawRoundRect(tmp, radius, radius, stroke);
            }
        } else {
            fill.setColor(blend(0x9EFFFFFF, 0x24FFFFFF, night));
            c.drawRoundRect(tmp, radius, radius, fill);
            stroke.setColor(blend(0xCCFFFFFF, 0x1FFFFFFF, night));
            stroke.setStrokeWidth(1.5f * k);
            c.drawRoundRect(tmp, radius, radius, stroke);
            if (f > 0.01f) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(tmp, radius, radius, fill);
            }
        }
        return tmp;
    }

    void pill(Canvas c, String label, float x, float base, boolean onDark, boolean focused) {
        text(Ui.semibold, 17, onDark ? 0xFFFFFFFF : ink(focused));
        float w = text.measureText(label) + 24 * k, h = 32 * k;
        tmp.set(x, base - h + 9 * k, x + w, base + 9 * k);
        fill.setColor(onDark ? 0x24FFFFFF : focused ? 0x0F000000 : blend(0x0F000000, 0x1FFFFFFF, night));
        c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
        c.drawText(label, x + 12 * k, base, text);
    }

    float pillWidth(String label) {
        text(Ui.semibold, 17, 0);
        return text.measureText(label) + 24 * k;
    }

    /** A team's logo (or its initials in a circle while it loads, or if there is none). */
    void crest(Canvas c, String url, String abbrev, float cx, float cy, float size, boolean onDark) {
        Bitmap b = SportsLogos.get(context, url);
        if (b != null) {
            float s = size / Math.max(b.getWidth(), b.getHeight());
            float w = b.getWidth() * s, h = b.getHeight() * s;
            if (onDark) {
                // Dark logos (Atlético's black crest) vanish on a dark card: a soft white disc behind them.
                fill.setColor(0x26FFFFFF);
                c.drawCircle(cx, cy, size * 0.62f, fill);
            }
            src.set(0, 0, b.getWidth(), b.getHeight());
            tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            c.drawBitmap(b, src, tmp, bitmapPaint);
            return;
        }
        fill.setColor(onDark ? 0x33FFFFFF : blend(0x14000000, 0x29FFFFFF, night));
        c.drawCircle(cx, cy, size / 2, fill);
        text(Ui.semibold, size * 0.26f / k, onDark ? 0xFFFFFFFF : ink(false));
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(abbrev, cx, cy + Ui.halfCap(text), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // MARK: - Text

    TextPaint text(android.graphics.Typeface face, float size, int color) {
        text.setTypeface(face);
        text.setTextSize(size * k);
        text.setColor(color);
        text.setTextAlign(Paint.Align.LEFT);
        text.setFontFeatureSettings("'tnum'");
        return text;
    }

    String ellipsize(String s, float width) {
        fit.set(text);
        return TextUtils.ellipsize(s, fit, Math.max(0, width), TextUtils.TruncateAt.END).toString();
    }

    void left(Canvas c, String s, float x, float base, float width) {
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(width > 0 ? ellipsize(s, width) : s, x, base, text);
    }

    void right(Canvas c, String s, float x, float base) {
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, x, base, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    void center(Canvas c, String s, float x, float base, float width) {
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(width > 0 ? ellipsize(s, width) : s, x, base, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // MARK: - Cards

    /** A row's title: the logo, the team's name and where it stands. */
    void rowTitle(Canvas c, float x, float base, String logo, String abbrev, String title, String summary, boolean f1) {
        float size = 42 * k, cx = x + size / 2;
        if (f1) {
            text(Ui.semibold, 20, 0xFFFFFFFF);
            text.setTextSkewX(-0.2f);
            float w = text.measureText("F1") + 22 * k;
            tmp.set(x, base - 32 * k, x + w, base + 10 * k);
            fill.setColor(F1_RED);
            c.drawRoundRect(tmp, 10 * k, 10 * k, fill);
            c.drawText("F1", x + 11 * k, base - 3 * k, text);
            text.setTextSkewX(0);
            size = w;
        } else {
            crest(c, logo, abbrev, cx, base - 11 * k, size, false);
        }
        text(Ui.semibold, 30, ink(false));
        float tx = x + size + 16 * k;
        c.drawText(title, tx, base, text);
        tx += text.measureText(title) + 18 * k;
        text(Ui.medium, 22, dim(false));
        c.drawText(summary, tx, base, text);
    }

    /** A row's title with no logo (the movie rows). */
    void plainTitle(Canvas c, float x, float base, String title, String summary) {
        text(Ui.semibold, 30, ink(false));
        c.drawText(title, x, base, text);
        float tx = x + text.measureText(title) + 18 * k;
        text(Ui.medium, 22, dim(false));
        c.drawText(summary, tx, base, text);
    }

    private final android.graphics.Matrix matrix = new android.graphics.Matrix();
    private final Paint posterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final java.util.WeakHashMap<Bitmap, android.graphics.BitmapShader> shaders = new java.util.WeakHashMap<>();

    /**
     * A movie poster (rounded, cropped to the card), the rating in the corner, the rank number to its left in a
     * Top 10 row, and the title under it when focused. f = focus (0..1): it grows and gets a white ring.
     */
    /** A poster with its tags: the streaming service (top right) and IPTV (bottom left) when it's on your lists. */
    void poster(Canvas c, RectF r, Bitmap b, String rating, int rank, String title, float f, String service, boolean iptv) {
        poster(c, r, b, rating, rank, title, f);
        // Along the bottom: IPTV (yours) on the left, the streaming service after it.
        float s = 1 + 0.08f * f, h = 26 * k * s, x = tmp.left + 10 * k, y = tmp.bottom - 10 * k - h;
        if (iptv) {
            text(Ui.semibold, 13 * s, 0xFFFFFFFF);
            float w = text.measureText("IPTV") + 16 * k * s;
            tagRect.set(x, y, x + w, y + h);
            fill.setColor(0xFF7C5CFF);
            c.drawRoundRect(tagRect, 8 * k, 8 * k, fill);
            c.drawText("IPTV", tagRect.left + 8 * k * s, tagRect.centerY() + Ui.halfCap(text), text);
            x = tagRect.right + 6 * k;
        }
        if (service != null && !service.isEmpty()) {
            text(Ui.semibold, 13 * s, 0xFF1C1C1E);
            float room = tmp.right - 10 * k - x;
            if (room < 40 * k) return;
            float w = Math.min(room, text.measureText(service) + 16 * k * s);
            tagRect.set(x, y, x + w, y + h);
            fill.setColor(0xEBFFFFFF);
            c.drawRoundRect(tagRect, 8 * k, 8 * k, fill);
            left(c, service, tagRect.left + 8 * k * s, tagRect.centerY() + Ui.halfCap(text), w - 16 * k * s);
        }
    }

    private final RectF tagRect = new RectF();

    void poster(Canvas c, RectF r, Bitmap b, String rating, int rank, String title, float f) {
        if (rank > 0) {
            text(Ui.semibold, 170, blend(0x1F000000, 0x2EFFFFFF, night));
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(rank), r.left + 26 * k, r.bottom - 6 * k, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        float s = 1 + 0.08f * f;
        tmp.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2, r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
        float radius = 18 * k * s;
        shadow.setColor(0xFF000000);
        shadow.setShadowLayer(24 * k, 0, 12 * k, Ui.alpha(0xFF000000, 0.18f + 0.22f * f));
        c.drawRoundRect(tmp, radius, radius, shadow);
        shadow.clearShadowLayer();
        if (b != null) {
            android.graphics.BitmapShader sh = shaders.get(b);
            if (sh == null) shaders.put(b, sh = new android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
            float sc = Math.max(tmp.width() / b.getWidth(), tmp.height() / b.getHeight());
            matrix.setScale(sc, sc);
            matrix.postTranslate(tmp.centerX() - b.getWidth() * sc / 2, tmp.centerY() - b.getHeight() * sc / 2);
            sh.setLocalMatrix(matrix);
            posterPaint.setShader(sh);
            c.drawRoundRect(tmp, radius, radius, posterPaint);
        } else {
            fill.setColor(blend(0xFFCDD1D8, 0xFF2A2D35, night));
            c.drawRoundRect(tmp, radius, radius, fill);
            text(Ui.semibold, 20, dim(false));
            center(c, title, tmp.centerX(), tmp.centerY(), tmp.width() - 24 * k);
        }
        // Rating badge
        text(Ui.semibold, 16 * s, 0xFFFFFFFF);
        float bw = text.measureText(rating) + 18 * k, bh = 28 * k;
        RectF badge = new RectF(tmp.left + 10 * k, tmp.top + 10 * k, tmp.left + 10 * k + bw, tmp.top + 10 * k + bh);
        fill.setColor(0xC7000000);
        c.drawRoundRect(badge, 8 * k, 8 * k, fill);
        c.drawText(rating, badge.left + 9 * k, badge.centerY() + Ui.halfCap(text), text);
        if (f > 0.01f) {
            stroke.setColor(Ui.alpha(0xFFFFFFFF, f));
            stroke.setStrokeWidth(5 * k);
            c.drawRoundRect(tmp, radius, radius, stroke);
            text(Ui.semibold, 20, Ui.alpha(ink(false), f));
            center(c, title, tmp.centerX(), tmp.bottom + 34 * k, tmp.width() + 80 * k);
        }
    }

    /** The main card: the game on now, the result just in, or the next game. */
    void matchCard(Canvas c, RectF r, Sports.Match m, String fallbackTitle, float f) {
        boolean live = m != null && m.state == Sports.LIVE;
        boolean focused = f > 0.5f && !live;
        RectF b = new RectF(card(c, r, f, live ? 0xFF111111 : f > 0.01f ? 0xFFFFFFFF : 0));
        float pad = 30 * k * b.width() / r.width();
        float x = b.left + pad, right = b.right - pad;
        if (m == null) {
            text(Ui.medium, 22, dim(focused));
            c.drawText(fallbackTitle, x, b.top + 70 * k, text);
            return;
        }
        boolean dark = live;
        int ink = dark ? 0xFFFFFFFF : ink(focused), dim = dark ? 0xB3FFFFFF : dim(focused);
        float top = b.top + 46 * k;
        // Header
        float hx = x;
        if (live) {
            text(Ui.semibold, 18, 0xFFFFFFFF);
            float w = text.measureText(Ui.tr("LIVE")) + 40 * k;
            tmp.set(hx, top - 25 * k, hx + w, top + 9 * k);
            fill.setColor(LIVE_RED);
            c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(hx + 15 * k, top - 8 * k, 5 * k, fill);
            c.drawText(Ui.tr("LIVE"), hx + 27 * k, top, text);
            hx += w + 14 * k;
        }
        String label = m.state == Sports.FINAL ? Ui.tr("FULL TIME") : m.state == Sports.PRE ? Ui.tr("NEXT GAME") : "";
        text(Ui.semibold, 19, dim);
        String head = (label.isEmpty() ? "" : label + " · ") + m.comp.toUpperCase(Ui.locale());
        float venueW = 0;
        if (!m.venue.isEmpty()) {
            text(Ui.medium, 19, dim);
            String v = ellipsize(m.venue, (right - x) * 0.4f);
            venueW = text.measureText(v);
            right(c, v, right, top);
        }
        text(Ui.semibold, 19, dim);
        left(c, head, hx, top, right - hx - venueW - 20 * k);
        // Teams
        float cy = b.top + b.height() * 0.48f, crest = 76 * k * b.width() / r.width();
        float lx = x + 75 * k, rx = right - 75 * k;
        crest(c, m.home.logo, m.home.abbrev, lx, cy - 10 * k, crest, dark);
        crest(c, m.away.logo, m.away.abbrev, rx, cy - 10 * k, crest, dark);
        text(Ui.semibold, 21, ink);
        center(c, m.home.name, lx, cy + crest / 2 + 22 * k, 160 * k);
        center(c, m.away.name, rx, cy + crest / 2 + 22 * k, 160 * k);
        float mid = (x + right) / 2;
        if (m.state == Sports.PRE) {
            text(Ui.semibold, 54, ink);
            center(c, Sports.time(m.at), mid, cy + 8 * k, 0);
            text(Ui.medium, 20, dim);
            center(c, Sports.day(m.at), mid, cy + 42 * k, 0);
        } else {
            text(Ui.semibold, 60, ink);
            center(c, m.homeScore + " – " + m.awayScore, mid, cy + 12 * k, 0);
            text(Ui.semibold, 22, live ? LIVE_RED : dim);
            center(c, live ? m.clock : Ui.tr("Full time"), mid, cy + 48 * k, 0);
        }
        // Where it's on
        float base = b.bottom - 26 * k;
        text(Ui.medium, 16, dark ? 0x99FFFFFF : faint(focused));
        if (m.tv.isEmpty()) {
            c.drawText(Ui.tr("Channel to be confirmed"), x, base, text);
        } else {
            String w = Ui.tr("Where to watch");
            c.drawText(w, x, base, text);
            float px = x + text.measureText(w) + 14 * k;
            for (String t : m.tv) {
                pill(c, t, px, base, dark, focused);
                px += pillWidth(t) + 10 * k;
            }
        }
    }

    /** A following game: when, the two logos, who, the competition and the channel. */
    void fixtureCard(Canvas c, RectF r, Sports.Match m, String us, float f) {
        boolean focused = f > 0.5f;
        RectF b = new RectF(card(c, r, f, f > 0.01f ? 0xFFFFFFFF : 0));
        // Centered, like the big card next to it.
        float cx = b.centerX(), w = b.width() - 40 * k;
        text(Ui.semibold, 18, dim(focused));
        center(c, Sports.when(m.at), cx, b.top + 44 * k, w);
        float cy = b.top + 100 * k, size = 54 * k, gap = 34 * k;
        crest(c, m.home.logo, m.home.abbrev, cx - gap - size / 2, cy, size, false);
        text(Ui.semibold, 20, faint(focused));
        center(c, "×", cx, cy + 7 * k, 0);
        crest(c, m.away.logo, m.away.abbrev, cx + gap + size / 2, cy, size, false);
        text(Ui.semibold, 20, ink(focused));
        String title = (m.weHome ? us : m.home.name) + " × " + (m.weHome ? m.away.name : us);
        center(c, title, cx, b.top + 168 * k, w);
        text(Ui.medium, 17, dim(focused));
        center(c, m.comp, cx, b.top + 196 * k, w);
        text(Ui.medium, 16, faint(focused));
        center(c, m.tv.isEmpty() ? Ui.tr("Channel to be confirmed") : TextUtils.join(" · ", m.tv), cx, b.top + 224 * k, w);
    }

    /** Five rows of the table around your team (highlighted). */
    void miniTable(Canvas c, RectF r, String title, List<Sports.Row> rows, float f) {
        boolean focused = f > 0.5f;
        RectF b = new RectF(card(c, r, f, f > 0.01f ? 0xFFFFFFFF : 0));
        float x = b.left + 28 * k, right = b.right - 28 * k;
        text(Ui.semibold, 18, dim(focused));
        left(c, title.toUpperCase(Ui.locale()), x, b.top + 44 * k, right - x - 100 * k);
        right(c, Ui.tr("GP · PTS"), right, b.top + 44 * k);
        int mine = 0;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).mine) mine = i;
        int from = Math.max(0, Math.min(rows.size() - 5, mine - 2));
        for (int i = from, j = 0; i < rows.size() && j < 5; i++, j++) {
            Sports.Row row = rows.get(i);
            float top = b.top + 60 * k + j * 36 * k;
            int ink = ink(focused);
            if (row.mine) {
                tmp.set(x - 10 * k, top, right + 10 * k, top + 34 * k);
                fill.setColor(focused ? 0xDB000000 : blend(0xDB000000, 0xF2FFFFFF, night));
                c.drawRoundRect(tmp, 10 * k, 10 * k, fill);
                ink = focused || night < 0.5f ? 0xFFFFFFFF : 0xFF1C1C1E;
            }
            float base = top + 24 * k;
            text(Ui.semibold, 19, ink);
            right(c, String.valueOf(row.pos), x + 26 * k, base);
            crest(c, row.logo, row.abbrev, x + 52 * k, top + 17 * k, 26 * k, false);
            text(Ui.medium, 20, ink);
            left(c, row.name, x + 76 * k, base, right - x - 76 * k - 90 * k);
            text(Ui.medium, 19, Ui.alpha(ink, 0.7f));
            right(c, String.valueOf(row.played), right - 50 * k, base);
            text(Ui.semibold, 20, ink);
            right(c, String.valueOf(row.pts), right, base);
        }
    }

    /** An F1 session: the day, the name and the time; the race in red; the session on now with a LIVE badge. */
    void sessionCard(Canvas c, RectF r, Sports.Session s, boolean race, boolean live, String tv, float f) {
        boolean focused = f > 0.5f && !race && !live;
        RectF b = new RectF(card(c, r, f, race || live ? (live ? 0xFF111111 : F1_RED) : f > 0.01f ? 0xFFFFFFFF : 0));
        boolean dark = race || live;
        float x = b.left + 26 * k, w = b.width() - 52 * k;
        text(Ui.semibold, 18, dark ? 0xD9FFFFFF : dim(focused));
        String day = Sports.day(s.at) + (race && !tv.isEmpty() ? " · " + tv : "");
        left(c, day, x, b.top + 46 * k, w);
        text(Ui.semibold, 26, dark ? 0xFFFFFFFF : ink(focused));
        left(c, s.name, x, b.top + 96 * k, w);
        text(Ui.semibold, 42, dark ? 0xFFFFFFFF : ink(focused));
        c.drawText(Sports.time(s.at), x, b.top + 160 * k, text);
        String until = Sports.until(s.at);
        if (!live && !until.isEmpty()) {
            text(Ui.medium, 18, dark ? 0xCCFFFFFF : faint(focused));
            left(c, until, x, b.bottom - 30 * k, w);
        }
        if (live) {
            text(Ui.semibold, 18, 0xFFFFFFFF);
            float lw = text.measureText(Ui.tr("LIVE")) + 40 * k, top = b.bottom - 30 * k;
            tmp.set(x, top - 25 * k, x + lw, top + 9 * k);
            fill.setColor(LIVE_RED);
            c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(x + 15 * k, top - 8 * k, 5 * k, fill);
            c.drawText(Ui.tr("LIVE"), x + 27 * k, top, text);
        }
    }

    /** The big card on the Sports screen: the game on now (live), the result just in, or the next game. */
    void heroCard(Canvas c, RectF r, Sports.Match m, Sports.Club club) {
        boolean live = m != null && m.state == Sports.LIVE;
        fill.setColor(0xFF111111);
        shadow.setShadowLayer(40 * k, 0, 20 * k, 0x40000000);
        c.drawRoundRect(r, 32 * k, 32 * k, shadow);
        c.drawRoundRect(r, 32 * k, 32 * k, fill);
        float x = r.left + 44 * k, right = r.right - 44 * k, top = r.top + 56 * k;
        if (m == null) {
            text(Ui.medium, 26, 0xB3FFFFFF);
            c.drawText(Ui.tr("No games scheduled"), x, top + 40 * k, text);
            return;
        }
        float hx = x;
        if (live) {
            text(Ui.semibold, 20, 0xFFFFFFFF);
            float w = text.measureText(Ui.tr("LIVE")) + 44 * k;
            tmp.set(hx, top - 27 * k, hx + w, top + 10 * k);
            fill.setColor(LIVE_RED);
            c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(hx + 16 * k, top - 8 * k, 5.5f * k, fill);
            c.drawText(Ui.tr("LIVE"), hx + 29 * k, top, text);
            hx += w + 16 * k;
        }
        String label = m.state == Sports.FINAL ? Ui.tr("FULL TIME") : m.state == Sports.PRE ? Ui.tr("NEXT GAME") : "";
        text(Ui.semibold, 20, 0xB3FFFFFF);
        left(c, (label.isEmpty() ? "" : label + " · ") + m.comp.toUpperCase(Ui.locale()), hx, top, (right - hx) * 0.6f);
        text(Ui.medium, 20, 0xB3FFFFFF);
        right(c, ellipsize(m.venue, (right - x) * 0.35f), right, top);
        float cy = r.top + r.height() * 0.47f, size = 120 * k;
        float lx = x + 150 * k, rx = right - 150 * k;
        crest(c, m.home.logo, m.home.abbrev, lx, cy - 16 * k, size, true);
        crest(c, m.away.logo, m.away.abbrev, rx, cy - 16 * k, size, true);
        text(Ui.semibold, 28, 0xFFFFFFFF);
        center(c, m.home.name, lx, cy + size / 2 + 30 * k, 260 * k);
        center(c, m.away.name, rx, cy + size / 2 + 30 * k, 260 * k);
        String homeInfo = standing(club, m.home), awayInfo = standing(club, m.away);
        text(Ui.medium, 18, 0x99FFFFFF);
        if (!homeInfo.isEmpty()) center(c, homeInfo, lx, cy + size / 2 + 58 * k, 260 * k);
        if (!awayInfo.isEmpty()) center(c, awayInfo, rx, cy + size / 2 + 58 * k, 260 * k);
        float mid = (x + right) / 2;
        if (m.state == Sports.PRE) {
            text(Ui.medium, 22, 0xB3FFFFFF);
            center(c, Sports.day(m.at), mid, cy - 58 * k, 0);
            text(Ui.semibold, 96, 0xFFFFFFFF);
            center(c, Sports.time(m.at), mid, cy + 26 * k, 0);
            text(Ui.medium, 20, 0x99FFFFFF);
            center(c, Sports.until(m.at), mid, cy + 64 * k, 0);
        } else {
            text(Ui.semibold, 104, 0xFFFFFFFF);
            center(c, m.homeScore + " – " + m.awayScore, mid, cy + 26 * k, 0);
            text(Ui.semibold, 26, live ? LIVE_RED : 0xB3FFFFFF);
            center(c, live ? m.clock : Ui.tr("Full time"), mid, cy + 70 * k, 0);
        }
        float base = r.bottom - 34 * k;
        text(Ui.medium, 18, 0x99FFFFFF);
        if (m.tv.isEmpty()) {
            c.drawText(Ui.tr("Channel to be confirmed"), x, base, text);
        } else {
            String w = Ui.tr("Where to watch");
            c.drawText(w, x, base, text);
            float px = x + text.measureText(w) + 14 * k;
            for (String t : m.tv) {
                pill(c, t, px, base, true, false);
                px += pillWidth(t) + 10 * k;
            }
        }
    }

    private static String standing(Sports.Club club, Sports.Team t) {
        if (club == null) return "";
        for (Sports.Row r : club.table) {
            if (r.abbrev.equalsIgnoreCase(t.abbrev) || r.name.equalsIgnoreCase(t.name)) return Ui.trf("{pos}th", "pos", r.pos) + " · " + Ui.trf("{n} pts", "n", r.pts);
        }
        return "";
    }

    /** A plain glass panel (no focus) for the Sports screen. */
    void panel(Canvas c, RectF r) {
        card(c, r, 0, 0);
    }

    /** A colored block (team color, zone) of a table row. */
    void bar(Canvas c, float x, float top, float w, float h, int color) {
        tmp.set(x, top, x + w, top + h);
        fill.setColor(color);
        c.drawRoundRect(tmp, w / 2, w / 2, fill);
    }

    void highlight(Canvas c, float l, float t, float r, float b) {
        tmp.set(l, t, r, b);
        fill.setColor(blend(0xFF111111, 0xF2FFFFFF, night));
        c.drawRoundRect(tmp, 10 * k, 10 * k, fill);
    }

    void line(Canvas c, float l, float y, float r) {
        fill.setColor(blend(0x0F000000, 0x1AFFFFFF, night));
        c.drawRect(l, y, r, y + 1.5f * k, fill);
    }
}

