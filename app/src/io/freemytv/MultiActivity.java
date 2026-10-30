package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.List;

import static io.freemytv.TvUi.MATCH;
import static io.freemytv.TvUi.WRAP;

/**
 * Mosaico: 2, 3 or 4 live channels at once. The selected square (white frame) has the sound; the arrows move between
 * squares. OK on an empty square adds a channel; on a full one, its options (full screen, change, remove, layout). Each
 * list allows only so many screens at once, so each square plays from a list that still has one free.
 */
public class MultiActivity extends Activity {
    /** Opens the mosaic, putting this channel (a Lineup key) in the first empty square. */
    static void add(Context c, String key) {
        c.startActivity(HomeApps.fresh(c, new Intent(c, MultiActivity.class)).putExtra("add", key));
    }

    private final class Tile {
        final FrameLayout box = new FrameLayout(MultiActivity.this);
        final SurfaceView surface = new SurfaceView(MultiActivity.this);
        final View frame = new View(MultiActivity.this);
        final TextView label, status;
        ExoPlayer player;
        Lineup.Channel ch;
        Iptv.Version source;
        int tries;
        boolean overlay;
        final List<Iptv.Version> tried = new ArrayList<>();

        Tile() {
            box.setBackgroundColor(0xFF0B0D12);
            box.addView(surface, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
            status = ui.text("", 22, Ui.medium, 0xCCFFFFFF);
            status.setGravity(Gravity.CENTER);
            box.addView(status, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
            label = ui.text("", 17, Ui.semibold, 0xFFFFFFFF);
            label.setPadding(ui.px(12), ui.px(6), ui.px(12), ui.px(6));
            label.setBackground(TvUi.round(0xA6000000, 8 * ui.k));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START);
            lp.setMargins(ui.px(14), 0, 0, ui.px(14));
            box.addView(label, lp);
            box.addView(frame, new FrameLayout.LayoutParams(MATCH, MATCH));
        }

        void empty() {
            release();
            surface.setVisibility(View.INVISIBLE);
            ch = null;
            source = null;
            label.setVisibility(View.GONE);
            status.setText("＋\nOK adiciona um canal");
        }

        void release() {
            if (player != null) { player.release(); player = null; }
        }

        void play(Lineup.Channel c, Iptv.Version v) {
            release();
            surface.setVisibility(View.VISIBLE);
            ch = c;
            source = v;
            label.setVisibility(View.VISIBLE);
            label.setText(c.name + "  ·  " + v.source.name + (Lineup.quality(v).isEmpty() ? "" : " · " + Lineup.quality(v)));
            status.setText("Abrindo…");
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(PlayerActivity.AGENT)
                    .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(20000);
            androidx.media3.extractor.DefaultExtractorsFactory extractors = new androidx.media3.extractor.DefaultExtractorsFactory()
                    .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            | androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
            // A small buffer each: four players share the box's memory.
            DefaultLoadControl load = new DefaultLoadControl.Builder().setBufferDurationsMs(4000, 15000, 1000, 2000).build();
            player = new ExoPlayer.Builder(MultiActivity.this, new DefaultRenderersFactory(MultiActivity.this).setEnableDecoderFallback(true))
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(MultiActivity.this, http), extractors))
                    .setLoadControl(load).build();
            player.setVideoSurfaceView(surface);
            player.setVolume(this == selectedTile() ? 1f : 0f);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) { status.setText(""); tries = 0; }
                    else if (state == Player.STATE_BUFFERING && status.getText().length() == 0) status.setText("");
                }
                @Override public void onVideoSizeChanged(VideoSize s) { fit(Tile.this, s); }
                @Override public void onPlayerError(PlaybackException e) { failed(Tile.this); }
            });
            player.setMediaItem(new MediaItem.Builder().setUri(Iptv.liveUrl(v, false)).build());
            player.prepare();
            player.setPlayWhenReady(true);
            save();
        }

        void playHls() {
            if (player == null || source == null) return;
            player.setMediaItem(new MediaItem.Builder().setUri(Iptv.liveUrl(source, true)).setMimeType(MimeTypes.APPLICATION_M3U8).build());
            player.prepare();
        }
    }

    private TvUi ui;
    private FrameLayout root, sheet;
    private final Tile[] tiles = new Tile[4];
    private int count = 2, selected;
    private Lineup lineup;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui = new TvUi(this, true);
        prefs = getSharedPreferences("mosaic", MODE_PRIVATE);
        count = Math.max(2, Math.min(4, prefs.getInt("count", 2)));
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        for (int i = 0; i < 4; i++) {
            tiles[i] = new Tile();
            root.addView(tiles[i].box);
        }
        setContentView(root);
        root.post(this::layout);
        for (Iptv.Source s : Iptv.get(this).sources()) Movies.get(this).run(() -> Iptv.check(s), x -> {});   // screens per list
        Lineup ready = Lineup.ready(this);
        if (ready != null) start(ready);
        else {
            for (Tile t : tiles) t.status.setText("Carregando os canais…");
            Movies.get(this).run(() -> Lineup.get(this), l -> { if (l != null && !isFinishing()) start(l); });
        }
    }

    private void start(Lineup l) {
        lineup = l;
        String add = getIntent().getStringExtra("add");
        for (int i = 0; i < 4; i++) {
            Lineup.Channel ch = l.find(prefs.getString("tile" + i, ""));
            if (i >= count || ch == null || ch.key.equals(add)) tiles[i].empty();
            else tiles[i].ch = ch;
        }
        if (add != null && l.find(add) != null) {
            int slot = -1;
            for (int i = 0; i < count && slot < 0; i++) if (tiles[i].ch == null) slot = i;
            if (slot < 0) slot = 0;
            tiles[slot].ch = l.find(add);
            selected = slot;
        }
        // Start them one after the other: a list frees the screen of the channel just closed after a few seconds.
        int delay = 1500;
        for (int i = 0; i < count; i++) {
            Tile t = tiles[i];
            if (t.ch == null) { t.empty(); continue; }
            Lineup.Channel ch = t.ch;
            t.status.setText("Abrindo…");
            t.label.setVisibility(View.VISIBLE);
            t.label.setText(ch.name);
            handler.postDelayed(() -> { if (!isFinishing()) assign(t, ch); }, delay);
            delay += 1200;
        }
        select(selected);
    }

    // MARK: - Layout: 2 side by side, 3 (one big and two small), 4 in a grid

    /** The arrangements for each number of screens: [count - 2][style]. */
    static final String[][] STYLES = {{"Lado a lado", "Imagem na imagem"}, {"Um grande e dois", "Três iguais"}, {"Quatro iguais", "Um grande e três"}};

    private int style() { return Math.max(0, Math.min(1, prefs.getInt("style" + count, 0))); }

    /** The style has one big square (the first): "Trazer para o quadro grande" makes sense. */
    private boolean hasBig() { return (count == 2 && style() == 1) || (count == 3 && style() == 0) || (count == 4 && style() == 1); }

    private int[][] rects = new int[0][];

    private void layout() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0) return;
        int[][] r;
        int st = style();
        if (count == 2 && st == 1) {
            // Picture in picture: the first fills the screen, the second sits in a corner.
            int pw = w * 3 / 10, ph = pw * 9 / 16, m = w / 40;
            r = new int[][] {{0, 0, w, h}, {w - pw - m, h - ph - m, pw, ph}};
        } else if (count == 2) {
            int th = w / 2 * 9 / 16, top = (h - th) / 2;
            r = new int[][] {{0, top, w / 2, th}, {w / 2, top, w / 2, th}};
        } else if (count == 3 && st == 1) {
            int tw = w / 3, th = tw * 9 / 16, top = (h - th) / 2;
            r = new int[][] {{0, top, tw, th}, {tw, top, tw, th}, {2 * tw, top, w - 2 * tw, th}};
        } else if (count == 3) {
            int bw = w * 2 / 3, bh = bw * 9 / 16, sw = w - bw, sh = bh / 2, top = (h - bh) / 2;
            r = new int[][] {{0, top, bw, bh}, {bw, top, sw, sh}, {bw, top + sh, sw, bh - sh}};
        } else if (st == 1) {
            int bw = w * 3 / 4, bh = bw * 9 / 16, sw = w - bw, sh = bh / 3, top = (h - bh) / 2;
            r = new int[][] {{0, top, bw, bh}, {bw, top, sw, sh}, {bw, top + sh, sw, sh}, {bw, top + 2 * sh, sw, bh - 2 * sh}};
        } else {
            r = new int[][] {{0, 0, w / 2, h / 2}, {w / 2, 0, w - w / 2, h / 2}, {0, h / 2, w / 2, h - h / 2}, {w / 2, h / 2, w - w / 2, h - h / 2}};
        }
        rects = r;
        boolean pip = count == 2 && st == 1;
        for (int i = 0; i < 4; i++) {
            Tile t = tiles[i];
            if (i >= r.length) { t.box.setVisibility(View.GONE); continue; }
            t.box.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(r[i][2], r[i][3]);
            lp.leftMargin = r[i][0];
            lp.topMargin = r[i][1];
            t.box.setLayoutParams(lp);
            overlay(t, pip && i == 1);
            // A video surface isn't clipped by its square: fit it again to the new size (or fill until the video's known).
            t.surface.setLayoutParams(new FrameLayout.LayoutParams(r[i][2], r[i][3], Gravity.CENTER));
            t.box.post(() -> { if (t.player != null) fit(t, t.player.getVideoSize()); });
        }
        if (pip) tiles[1].box.bringToFront();
        if (sheet != null) sheet.bringToFront();
    }

    /** A square drawn over another (picture in picture): its video surface goes above the other one's. */
    private void overlay(Tile t, boolean on) {
        if (t.overlay == on) return;
        t.overlay = on;
        t.box.removeView(t.surface);
        t.surface.setZOrderMediaOverlay(on);
        t.box.addView(t.surface, 0);
    }

    /** Swaps the selected square with the big one: the channels keep playing, only their places change. */
    private void toBig() {
        if (selected == 0) return;
        Tile a = tiles[0], b = tiles[selected];
        tiles[0] = b;
        tiles[selected] = a;
        selected = 0;
        layout();
        select(0);
        save();
    }

    private void fit(Tile t, VideoSize v) {
        if (v.width == 0 || v.height == 0) return;
        int bw = t.box.getWidth(), bh = t.box.getHeight();
        if (bw == 0) return;
        float ratio = v.width * v.pixelWidthHeightRatio / v.height;
        int w = bw, h = Math.round(bw / ratio);
        if (h > bh) { h = bh; w = Math.round(bh * ratio); }
        t.surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    private Tile selectedTile() { return tiles[selected]; }

    /** The selected square: a white frame, and the only one with sound. */
    private void select(int i) {
        selected = Math.max(0, Math.min(count - 1, i));
        for (int k = 0; k < 4; k++) {
            Tile t = tiles[k];
            GradientDrawable d = new GradientDrawable();
            d.setColor(0);
            d.setStroke(ui.px(k == selected ? 5 : 1), k == selected ? 0xFFFFFFFF : 0x33FFFFFF);
            t.frame.setBackground(d);
            if (t.player != null) t.player.setVolume(k == selected ? 1f : 0f);
        }
    }

    // MARK: - Sources: each square from a list with a screen free

    /** How many screens a list allows (what the provider said; 1 until it answers). */
    private static int screens(Iptv.Source s) { return s.maxConnections > 0 ? s.maxConnections : 1; }

    private int inUse(Iptv.Source s, Tile except) {
        int n = 0;
        for (int i = 0; i < count; i++) if (tiles[i] != except && tiles[i].source != null && tiles[i].source.source.id == s.id) n++;
        return n;
    }

    private void assign(Tile t, Lineup.Channel ch) {
        t.tried.clear();
        t.tries = 0;
        Iptv.Version v = free(t, ch);
        if (v == null) {
            t.release();
            t.ch = ch;
            t.source = null;
            t.label.setVisibility(View.VISIBLE);
            t.label.setText(ch.name);
            t.status.setText("As listas deste canal já estão em uso\nnos outros quadros (cada lista tem poucas telas).");
            save();
            return;
        }
        t.play(ch, v);
    }

    /** The channel's best source whose list still has a screen free (not tried yet in this square). */
    private Iptv.Version free(Tile t, Lineup.Channel ch) {
        for (Iptv.Version v : ch.versions) if (!t.tried.contains(v) && inUse(v.source, t) < screens(v.source)) return v;
        return null;
    }

    private void failed(Tile t) {
        if (t.ch == null || isFinishing()) return;
        t.tries++;
        if (t.tries <= 2) {
            // The list may still be freeing a screen: wait and try again (MPEG-TS, then HLS).
            t.status.setText("Aguardando a lista liberar uma tela…");
            boolean hls = t.tries == 2;
            handler.postDelayed(() -> { if (t.player != null) { if (hls) t.playHls(); else { t.player.prepare(); } } }, 2500);
            return;
        }
        if (t.source != null) t.tried.add(t.source);
        Iptv.Version next = free(t, t.ch);
        if (next != null) {
            t.tries = 0;
            t.status.setText("Tentando " + next.source.name + "…");
            Lineup.Channel ch = t.ch;
            handler.postDelayed(() -> { if (!isFinishing()) t.play(ch, next); }, 1200);
            return;
        }
        t.release();
        t.status.setText("Este canal não abriu agora.\nOK para trocar.");
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit().putInt("count", count);
        for (int i = 0; i < 4; i++) e.putString("tile" + i, tiles[i].ch == null ? "" : tiles[i].ch.key);
        e.apply();
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (sheet != null) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeSheet();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return e.getKeyCode() != KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) { if (selectedTile().ch == null) picker(); else options(); }
                return true;
            case KeyEvent.KEYCODE_MENU:
                options();
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** The nearest square in the arrow's direction (from the squares' centres): works for every style. */
    private void move(int dx, int dy) {
        if (rects.length == 0) return;
        int[] me = rects[selected];
        float cx = me[0] + me[2] / 2f, cy = me[1] + me[3] / 2f;
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            if (i == selected) continue;
            float x = rects[i][0] + rects[i][2] / 2f - cx, y = rects[i][1] + rects[i][3] / 2f - cy;
            boolean ahead = dx > 0 ? x > 1 : dx < 0 ? x < -1 : dy > 0 ? y > 1 : y < -1;
            if (!ahead) continue;
            float along = dx != 0 ? Math.abs(x) : Math.abs(y), across = dx != 0 ? Math.abs(y) : Math.abs(x);
            float d = along + across * 2;
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best >= 0) select(best);
    }

    @Override protected void onStop() {
        super.onStop();
        for (Tile t : tiles) t.release();
        finish();   // its players hold the lists' screens: don't keep them in the background
    }

    // MARK: - Sheets: a square's options, picking a channel

    private LinearLayout openSheet(String title, String subtitle) {
        closeSheet();
        sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x80000000);
        sheet.setClickable(true);
        LinearLayout box = ui.column();
        box.setPadding(ui.px(44), ui.px(50), ui.px(44), ui.px(36));
        box.setBackground(TvUi.round(0xF51C1C1E, 0));
        box.addView(ui.text("MOSAICO", 16, Ui.semibold, TvUi.ACCENT));
        box.addView(ui.line(title, 32, Ui.semibold, 0xFFFFFFFF, 2), ui.margins(MATCH, WRAP, 0, 10, 0, 0));
        if (!subtitle.isEmpty()) box.addView(ui.line(subtitle, 18, Ui.medium, 0x99FFFFFF, 3), ui.margins(MATCH, WRAP, 0, 8, 0, 18));
        sheet.addView(box, new FrameLayout.LayoutParams(ui.px(720), MATCH, Gravity.END));
        root.addView(sheet, new FrameLayout.LayoutParams(MATCH, MATCH));
        return box;
    }

    private void closeSheet() {
        if (sheet != null) { root.removeView(sheet); sheet = null; }
    }

    private void options() {
        Tile t = selectedTile();
        LinearLayout box = openSheet(t.ch == null ? "Quadro vazio" : t.ch.name,
                t.source == null ? "" : "Tocando por " + t.source.source.name + ". O quadro com a moldura branca é o que tem som.");
        List<View> buttons = new ArrayList<>();
        if (t.ch != null) {
            buttons.add(ui.button("⤢  Tela cheia", true, () -> {
                Lineup.Channel ch = t.ch;
                closeSheet();
                for (Tile x : tiles) x.release();
                PlayerActivity.live(this, Lineup.toJson(java.util.Collections.singletonList(ch)), 0, "Mosaico");
                finish();
            }));
            if (hasBig() && selected != 0) buttons.add(ui.button("⬒  Trazer para o quadro grande", false, () -> { closeSheet(); toBig(); }));
            buttons.add(ui.button("⇄  Trocar o canal", false, this::picker));
            buttons.add(ui.button("✕  Tirar do mosaico", false, () -> { t.empty(); save(); closeSheet(); }));
        } else {
            buttons.add(ui.button("＋  Adicionar um canal", true, this::picker));
        }
        for (View v : buttons) box.addView(v, ui.margins(WRAP, WRAP, 0, 0, 0, 12));
        box.addView(ui.text("QUANTAS TELAS", 15, Ui.semibold, 0x73FFFFFF), ui.margins(WRAP, WRAP, 0, 22, 0, 10));
        LinearLayout row = ui.row();
        for (int n = 2; n <= 4; n++) {
            final int k = n;
            TvUi.Chip c = ui.chip(n + " telas", n == count, null);
            c.setOnClickListener(v -> {
                closeSheet();
                setCount(k);
            });
            row.addView(c, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
        }
        box.addView(row);
        box.addView(ui.text("ESTILO", 15, Ui.semibold, 0x73FFFFFF), ui.margins(WRAP, WRAP, 0, 22, 0, 10));
        LinearLayout styles = ui.row();
        for (int k = 0; k < 2; k++) {
            final int st = k;
            TvUi.Chip c = ui.chip(STYLES[count - 2][k], k == style(), null);
            c.setOnClickListener(v -> {
                prefs.edit().putInt("style" + count, st).apply();
                closeSheet();
                layout();
                select(selected);
            });
            styles.addView(c, ui.margins(WRAP, WRAP, 0, 0, 12, 0));
        }
        box.addView(styles);
        box.addView(ui.line("Cada lista aceita poucas telas ao mesmo tempo (" + screensText() + "). Com mais quadros, cada um toca por uma lista diferente.",
                15, Ui.medium, 0x66FFFFFF, 4), ui.margins(MATCH, WRAP, 0, 20, 0, 0));
        buttons.get(0).post(buttons.get(0)::requestFocus);
    }

    /** "Casa 1 · Sala 1" — the screens each list still has free (this square's own doesn't count). */
    private String freeText(Tile t) {
        StringBuilder b = new StringBuilder();
        for (Iptv.Source s : Iptv.get(this).sources()) {
            int left = screens(s) - inUse(s, t);
            b.append(b.length() == 0 ? "" : " · ").append(s.name).append(" ").append(Math.max(0, left));
        }
        return b.toString();
    }

    /** Which full lists are taken, and by which square, so you know where to go to swap them; null if none is full. */
    private String busyText(Tile t) {
        StringBuilder b = new StringBuilder();
        for (Iptv.Source s : Iptv.get(this).sources()) {
            if (screens(s) - inUse(s, t) > 0) continue;
            StringBuilder who = new StringBuilder();
            for (int i = 0; i < count; i++) {
                Tile o = tiles[i];
                if (o == t || o.source == null || o.source.source.id != s.id) continue;
                who.append(who.length() == 0 ? "" : ", ").append("quadro ").append(i + 1).append(o.ch != null ? " (" + o.ch.name + ")" : "");
            }
            b.append(b.length() == 0 ? "" : "\n").append(s.name).append(" ocupada: ").append(who);
        }
        if (b.length() == 0) return null;
        return b.append("\nOs canais só dessas listas não aparecem. Para usá-las, troque o canal desse quadro ou tire ele.").toString();
    }

    private String screensText() {
        StringBuilder b = new StringBuilder();
        for (Iptv.Source s : Iptv.get(this).sources()) b.append(b.length() == 0 ? "" : ", ").append(s.name).append(": ").append(screens(s));
        return b.toString();
    }

    private void setCount(int n) {
        if (n < count) for (int i = n; i < count; i++) tiles[i].empty();
        int old = count;
        count = n;
        layout();
        for (int i = old; i < n; i++) tiles[i].empty();
        select(Math.min(selected, n - 1));
        save();
    }

    private void picker() {
        if (lineup == null) return;
        Tile t = selectedTile();
        LinearLayout box = openSheet("Escolha um canal", "Telas livres para este quadro: " + freeText(t) + ". Digite para buscar.");
        android.widget.EditText search = new android.widget.EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar canal");
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 21 * ui.k);
        search.setTextColor(0xFF1C1C1E);
        search.setHintTextColor(0xFF8E8E93);
        search.setPadding(ui.px(18), 0, ui.px(18), 0);
        search.setBackground(TvUi.round(0xFFFFFFFF, 14 * ui.k));
        box.addView(search, ui.margins(MATCH, ui.px(60), 0, 0, 0, 12));
        // Categories, quicker than typing: OK on one lists its channels.
        final String[] cat = {null};
        android.widget.HorizontalScrollView cs = new android.widget.HorizontalScrollView(this);
        cs.setHorizontalScrollBarEnabled(false);
        cs.setClipToPadding(false);
        cs.setPadding(ui.px(4), ui.px(8), ui.px(40), ui.px(10));
        LinearLayout chips = ui.row();
        cs.addView(chips);
        box.addView(cs, ui.margins(MATCH, WRAP, 0, 0, 0, 10));
        List<String> names = new ArrayList<>();
        names.add("Sugeridos");
        names.add(LivePanel.FAV);
        names.add("Eventos ao vivo");
        for (String g : lineup.groupNames()) if (!g.equals(Lineup.GAMES)) names.add(g);
        final List<TvUi.Chip> catChips = new ArrayList<>();
        final Runnable[] refill = {null};
        for (String g : names) {
            TvUi.Chip c = ui.chip(g, g.equals("Sugeridos"), null);
            c.setOnClickListener(v -> {
                cat[0] = g.equals("Sugeridos") ? null : g;
                for (TvUi.Chip x : catChips) x.set(x == c);
                search.setText("");
                refill[0].run();
            });
            catChips.add(c);
            chips.addView(c, ui.margins(WRAP, WRAP, 0, 0, 10, 0));
        }
        final boolean[] opened = {false};
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout rows = ui.column();
        sv.addView(rows);
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1));
        Runnable fill = () -> {
            rows.removeAllViews();
            String q = search.getText().toString().trim();
            List<Lineup.Channel> list = new ArrayList<>();
            long when = System.currentTimeMillis();
            if (q.length() >= 2) list.addAll(lineup.search(q, 40));
            else if (LivePanel.FAV.equals(cat[0])) list.addAll(lineup.favourites());
            else if ("Eventos ao vivo".equals(cat[0])) { for (Lineup.Channel ch : lineup.group(Lineup.GAMES)) if (ch.state(when) == Lineup.LIVE) list.add(ch); }
            else if (cat[0] != null) { List<Lineup.Channel> g = lineup.group(cat[0]); list.addAll(g.subList(0, Math.min(150, g.size()))); }
            else {
                list.addAll(lineup.favourites());
                long now = System.currentTimeMillis();
                for (Lineup.Channel ch : lineup.group(Lineup.GAMES)) if (ch.state(now) == Lineup.LIVE && !list.contains(ch)) list.add(ch);
                for (Lineup.Channel ch : lineup.group(Lineup.SPORTS)) if (list.size() < 40 && !list.contains(ch)) list.add(ch);
            }
            View first = null;
            // Only channels with a list still free here show up: a full list's screen comes back only when the square
            // using it changes channel or closes (this square's own list counts as free, to swap in place).
            String busyNote = busyText(t);
            if (busyNote != null) {
                TextView note = ui.text(busyNote, 16, Ui.medium, 0xFFFFC56B);
                note.setPadding(ui.px(16), ui.px(10), ui.px(16), ui.px(10));
                note.setBackground(TvUi.round(0x22FFB020, 14 * ui.k));
                rows.addView(note, ui.margins(MATCH, WRAP, 0, 0, 0, 10));
            }
            int hidden = 0;
            List<Lineup.Channel> ok = new ArrayList<>();
            for (Lineup.Channel ch : list) { t.tried.clear(); if (free(t, ch) != null) ok.add(ch); else hidden++; }
            for (Lineup.Channel ch : ok) {
                View r = row(ch, () -> { closeSheet(); assign(t, ch); });
                Iptv.Version v = free(t, ch);
                if (v != null && ch.listCount() > 1) {
                    TextView meta = (TextView) ((LinearLayout) ((LinearLayout) r).getChildAt(1)).getChildAt(1);
                    meta.setText("Vai tocar pela " + v.source.name + " · " + ch.meta());
                }
                rows.addView(r, ui.margins(MATCH, ui.px(84), 0, 0, 0, 8));
                if (first == null) first = r;
            }
            if (ok.isEmpty() && hidden > 0) rows.addView(ui.text("Nenhum canal aqui tem lista livre agora. Para liberar uma tela, troque o canal ou tire o quadro que usa a lista.", 19, Ui.medium, 0x99FFFFFF));
            if (ok.isEmpty() && hidden == 0) rows.addView(ui.text(q.length() >= 2 ? "Nenhum canal com “" + q + "”." : "Digite o nome de um canal.", 19, Ui.medium, 0x99FFFFFF));
            // The first time, the focus goes to the first channel; picking a category takes it to its first channel too.
            if (first != null && q.length() < 2) { View f = first; f.post(f::requestFocus); }
            opened[0] = true;
        };
        refill[0] = fill;
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { handler.removeCallbacks(fill); handler.postDelayed(fill, 350); }
        });
        fill.run();
    }

    private View row(Lineup.Channel ch, Runnable pick) {
        LinearLayout r = ui.row();
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(ui.px(14), 0, ui.px(16), 0);
        GradientDrawable normal = TvUi.round(0x14FFFFFF, 16 * ui.k), on = TvUi.round(0xFFFFFFFF, 16 * ui.k);
        r.setBackground(normal);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setPadding(ui.px(5), ui.px(5), ui.px(5), ui.px(5));
        img.setBackground(TvUi.round(0x1AFFFFFF, 10 * ui.k));
        Img.into(img, ch.icon, ui.px(96));
        r.addView(img, ui.margins(ui.px(96), ui.px(58), 0, 0, 16, 0));
        LinearLayout names = ui.column();
        TextView name = ui.line(ch.name, 21, Ui.semibold, 0xFFFFFFFF, 1);
        TextView meta = ui.line(ch.meta(), 15, Ui.medium, 0x8CFFFFFF, 1);
        names.addView(name);
        names.addView(meta, ui.margins(MATCH, WRAP, 0, 3, 0, 0));
        r.addView(names, new LinearLayout.LayoutParams(0, WRAP, 1));
        r.setOnFocusChangeListener((v, f) -> {
            r.setBackground(f ? on : normal);
            name.setTextColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
            meta.setTextColor(f ? 0xFF6B6B70 : 0x8CFFFFFF);
        });
        r.setOnClickListener(v -> pick.run());
        return r;
    }
}
