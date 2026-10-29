package io.freemytv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Small building blocks for the movie screens, in the 1920×1080 space scaled to the screen: text, focusable chips and
 * buttons (white when focused, a little bigger), and poster cards with the rating and the streaming service. Day and
 * night palettes follow the home screen.
 */
final class TvUi {
    final Context c;
    final float k;
    final boolean dark;
    final int bg, ink, dim, faint, panel, panelLine, chip, chipOn, chipOnText;
    static final int ACCENT = 0xFFFFB340, GOOD = 0xFF21D07A;

    TvUi(Context c) {
        this(c, HomeData.get(c).isNight(System.currentTimeMillis()));
    }

    /** dark: the night palette whatever the time (screens on a dark picture: details, player, channels). */
    TvUi(Context c, boolean dark) {
        this.c = c;
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        this.dark = dark;
        bg = dark ? 0xFF0E1015 : 0xFFE9EBEF;
        ink = dark ? 0xFFF5F5F7 : 0xFF1C1C1E;
        dim = dark ? 0xB3FFFFFF : 0xFF6B6B70;
        faint = dark ? 0x80FFFFFF : 0xFF8E8E93;
        panel = dark ? 0x14FFFFFF : 0x9EFFFFFF;
        panelLine = dark ? 0x1AFFFFFF : 0xCCFFFFFF;
        chip = dark ? 0x1AFFFFFF : 0x0F000000;
        chipOn = dark ? 0xFFF5F5F7 : 0xFF111111;
        chipOnText = dark ? 0xFF111111 : 0xFFFFFFFF;
    }

    int px(float v) { return Math.round(v * k); }

    TextView text(String s, float size, Typeface face, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView line(String s, float size, Typeface face, int color, int lines) {
        TextView t = text(s, size, face, color);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    View panel(View content, float pad) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = round(panel, 28 * k);
        d.setStroke(Math.max(1, px(1.5f)), panelLine);
        box.setBackground(d);
        box.setPadding(px(pad), px(pad), px(pad), px(pad));
        box.addView(content);
        return box;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT, MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    LinearLayout.LayoutParams margins(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = lp(w, h);
        p.setMargins(px(l), px(t), px(r), px(b));
        return p;
    }

    // MARK: - Chips and buttons

    /** A pill: `on` = selected (dark fill). Focused: white, a bit bigger. */
    final class Chip extends TextView {
        boolean on;
        private final float size;

        Chip(String label, float size, boolean on) {
            super(c);
            this.on = on;
            this.size = size;
            setText(label);
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
            setTypeface(Ui.semibold);
            setGravity(Gravity.CENTER);
            setIncludeFontPadding(false);
            setSingleLine(true);
            setPadding(px(size * 0.85f), 0, px(size * 0.85f), 0);
            setHeight(px(size * 2.3f));
            setFocusable(true);
            setClickable(true);
            setOnFocusChangeListener((v, f) -> paint());
            paint();
        }

        void set(boolean on) {
            this.on = on;
            paint();
        }

        /** Focused, a chip grows a little: the rows and panels it's in must let it show past their edges. */
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            android.view.ViewParent p = getParent();
            for (int i = 0; i < 4 && p instanceof ViewGroup; i++, p = p.getParent()) {
                if (p instanceof android.widget.ScrollView || p instanceof android.widget.HorizontalScrollView) {
                    ((ViewGroup) p).setClipToPadding(false);
                    break;   // a scrolling area keeps clipping at its own edge
                }
                ((ViewGroup) p).setClipChildren(false);
                ((ViewGroup) p).setClipToPadding(false);
            }
        }

        void paint() {
            boolean f = isFocused();
            int fill = f ? 0xFFFFFFFF : on ? chipOn : chip;
            int text = f ? 0xFF111111 : on ? chipOnText : ink;
            GradientDrawable d = round(fill, size * 0.6f * k);
            if (f && on) d.setStroke(px(3), ACCENT);
            setBackground(d);
            setTextColor(text);
            setTranslationZ(f ? 1 : 0);   // above its neighbours while it's bigger (no shadow: they flicker on this GPU)
            animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start();
        }
    }

    Chip chip(String label, boolean on, Runnable click) {
        Chip ch = new Chip(label, 20, on);
        if (click != null) ch.setOnClickListener(v -> click.run());
        return ch;
    }

    Chip button(String label, boolean primary, Runnable click) {
        Chip b = new Chip(label, 23, primary);
        if (click != null) b.setOnClickListener(v -> click.run());
        return b;
    }

    // MARK: - Posters

    /** A focusable poster with the rating (top left) and, once known, the streaming service (top right). */
    final class Poster extends FrameLayout {
        final Movies.Title t;
        final ImageView image;
        final TextView provider;

        Poster(Movies.Title t, int w, int h, boolean withProvider) {
            super(c);
            this.t = t;
            setLayoutParams(new LinearLayout.LayoutParams(w, h));
            setFocusable(true);
            setClickable(true);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(dark ? 0xFF2A2D35 : 0xFFCDD1D8);
            addView(image, new LayoutParams(MATCH, MATCH));
            float r = 18 * k;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            setClipToOutline(true);
            Img.into(image, Img.tmdb(t.poster, w > px(220) ? "w500" : "w342"), w);
            TextView rate = new TextView(c);
            rate.setText("★ " + Movies.rating(t.rating));
            rate.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * k);
            rate.setTypeface(Ui.semibold);
            rate.setTextColor(Color.WHITE);
            rate.setIncludeFontPadding(false);
            rate.setGravity(Gravity.CENTER);
            rate.setPadding(px(9), 0, px(9), 0);
            rate.setBackground(round(0xC7000000, 8 * k));
            LayoutParams rp = new LayoutParams(WRAP, px(28));
            rp.setMargins(px(10), px(10), 0, 0);
            addView(rate, rp);
            provider = new TextView(c);
            provider.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13 * k);
            provider.setTypeface(Ui.semibold);
            provider.setTextColor(0xFF1C1C1E);
            provider.setIncludeFontPadding(false);
            provider.setGravity(Gravity.CENTER);
            provider.setPadding(px(8), 0, px(8), 0);
            provider.setBackground(round(0xEBFFFFFF, 8 * k));
            provider.setVisibility(GONE);
            LayoutParams pp = new LayoutParams(WRAP, px(28), Gravity.END);
            pp.setMargins(0, px(10), px(10), 0);
            addView(provider, pp);
            if (Iptv.get(c).has(t)) {
                // On your IPTV list: a purple tag at the bottom.
                TextView tag = new TextView(c);
                tag.setText("IPTV");
                tag.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13 * k);
                tag.setTypeface(Ui.semibold);
                tag.setTextColor(Color.WHITE);
                tag.setIncludeFontPadding(false);
                tag.setGravity(Gravity.CENTER);
                tag.setPadding(px(8), 0, px(8), 0);
                tag.setBackground(round(0xFF7C5CFF, 8 * k));
                LayoutParams tp = new LayoutParams(WRAP, px(26), Gravity.BOTTOM | Gravity.START);
                tp.setMargins(px(10), 0, 0, px(10));
                addView(tag, tp);
            }
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(r);
            ring.setStroke(px(5), 0xFFFFFFFF);
            ring.setColor(0);
            setForeground(null);
            // No shadows: on the TV box's GPU, dozens of them redrawn while scrolling made the posters flicker.
            setOnFocusChangeListener((v, f) -> {
                setForeground(f ? ring : null);
                setTranslationZ(f ? 1 : 0);   // above its neighbours while it's bigger
                animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start();
            });
            if (withProvider) loadProvider();
        }

        private void loadProvider() {
            List<Movies.Provider> known = Movies.get(c).cachedProviders(t);
            if (known != null) { showProvider(known); return; }
            Movies.get(c).run(() -> Movies.get(c).providers(t), this::showProvider);
        }

        private void showProvider(List<Movies.Provider> list) {
            if (list == null || list.isEmpty()) return;
            provider.setText(list.get(0).name);
            provider.setVisibility(VISIBLE);
        }
    }

    /** A poster with its title and "2019 · Suspense" under it. */
    LinearLayout card(Movies.Title t, int w, Runnable click) {
        LinearLayout box = column();
        Poster p = new Poster(t, w, Math.round(w * 1.5f), true);
        if (click != null) p.setOnClickListener(v -> click.run());
        box.addView(p);
        TextView name = line(t.name, 19, Ui.semibold, ink, 1);
        box.addView(name, margins(w, WRAP, 0, 12, 0, 0));
        String meta = (t.year.isEmpty() ? t.kindLabel() : t.year) + (Movies.firstGenre(t).isEmpty() ? "" : " · " + Movies.firstGenre(t));
        box.addView(line(meta, 16, Ui.medium, dim, 1), margins(w, WRAP, 0, 4, 0, 0));
        return box;
    }

    static void open(Context c, String kind, int id) {
        c.startActivity(HomeApps.fresh(c, new android.content.Intent(c, MovieActivity.class).putExtra("kind", kind).putExtra("id", id)));
    }

    static void open(Context c, Movies.Title t) {
        c.startActivity(HomeApps.fresh(c, new android.content.Intent(c, MovieActivity.class).putExtra("kind", t.kind).putExtra("id", t.id)));
    }
}
