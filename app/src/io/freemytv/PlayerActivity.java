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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.freemytv.TvUi.MATCH;
import static io.freemytv.TvUi.WRAP;

/**
 * Our player for the IPTV lists, on Google's Media3 (ExoPlayer): it opens the stream formats IPTV servers send (HLS,
 * MPEG-TS, MKV, MP4), follows their redirects, keeps a bigger buffer for 4K, and switches audio and subtitles.
 * Films: OK pauses, ← → jump 10 s (held, faster). Live: ← → and CH± change channel, OK shows what's on, holding OK
 * chooses the source. Under the time bar, always in sight with it, a row of buttons that ▼ (or MENU) goes into:
 * live, Canais, Fonte, Áudio e legendas, Favoritar, Mosaico, Programação; films, audio and subtitles, start over, next.
 * BACK closes what's open, then leaves.
 * It remembers where you stopped (Continue watching on the home screen) and, in a show, plays the next episode.
 * Extras: "url", "title", "subtitle", "key" (what to remember it by), "image", "kind", "tmdb", "next" (JSON of the
 * episodes that follow: url, title, subtitle, key); or, for live TV, "channels" (JSON: url, ts, title) and "index".
 */
public class PlayerActivity extends Activity implements Player.Listener {
    private TvUi ui;
    private ExoPlayer player;
    private SurfaceView surface;
    private FrameLayout stage;
    private LinearLayout overlay, panel, tools, chanList;
    private android.widget.ScrollView chanScroll;
    private TextView title, sub, clock, left, status, caption, center, nextCard, quality, nowNext;
    private Bar bar;
    private ProgressBar spinner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String key, url;
    private long seekTarget = -1;
    private int repeat;
    private JSONArray channels;
    private int channel;
    private boolean triedTs, resumed, backup;
    /** IPTV players identify themselves like this; many servers turn away Android's default one. */
    static final String AGENT = "VLC/3.0.20 LibVLC/3.0.20";

    static void play(Context c, String url, String title, String subtitle, String key, String image, String kind, int tmdb, JSONArray next) {
        c.startActivity(HomeApps.fresh(c, new Intent(c, PlayerActivity.class)).putExtra("url", url).putExtra("title", title).putExtra("subtitle", subtitle)
                .putExtra("key", key).putExtra("image", image).putExtra("kind", kind).putExtra("tmdb", tmdb)
                .putExtra("next", next == null ? "[]" : next.toString()));
    }

    /** A live channel; ▲▼ change to the previous and next of `channels` (JSON: url, ts, title). */
    static void live(Context c, JSONArray channels, int index, String category) { live(c, channels, index, category, 0); }

    /** `alt`: which of the first channel's sources to start with (chosen in Ao vivo's panel). */
    static void live(Context c, JSONArray channels, int index, String category, int alt) {
        c.startActivity(HomeApps.fresh(c, new Intent(c, PlayerActivity.class)).putExtra("channels", channels.toString()).putExtra("index", index)
                .putExtra("category", category).putExtra("alt", alt));
    }

    private boolean firstTune = true;
    private TextView sources;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui = new TvUi(this, true);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        stage = new FrameLayout(this);
        surface = new SurfaceView(this);
        stage.addView(surface, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        root.addView(stage, new FrameLayout.LayoutParams(MATCH, MATCH));
        caption = ui.text("", 36, Ui.medium, 0xFFFFFFFF);
        caption.setGravity(Gravity.CENTER);
        caption.setShadowLayer(ui.px(6), 0, ui.px(2), 0xFF000000);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        cp.bottomMargin = ui.px(90);
        cp.leftMargin = cp.rightMargin = ui.px(160);
        root.addView(caption, cp);
        status = ui.text("", 28, Ui.medium, 0xE6FFFFFF);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(ui.px(1200), WRAP, Gravity.CENTER));
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        root.addView(spinner, new FrameLayout.LayoutParams(ui.px(90), ui.px(90), Gravity.CENTER));
        center = ui.text("", 60, Ui.semibold, 0xFFFFFFFF);
        center.setGravity(Gravity.CENTER);
        center.setBackground(TvUi.round(0x8C000000, 70 * ui.k));
        center.setAlpha(0);
        root.addView(center, new FrameLayout.LayoutParams(ui.px(140), ui.px(140), Gravity.CENTER));

        // Overlay: title at the top, the time bar at the bottom (like Apple TV).
        overlay = ui.column();
        overlay.setPadding(ui.px(90), ui.px(56), ui.px(90), ui.px(56));
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xCC000000, 0x00000000, 0x00000000, 0x00000000, 0xF2000000}));
        LinearLayout top = ui.row();
        top.setGravity(Gravity.TOP);
        LinearLayout names = ui.column();
        sub = ui.text("", 20, Ui.semibold, 0xB3FFFFFF);
        sub.setLetterSpacing(0.06f);
        title = ui.line("", 46, Ui.semibold, 0xFFFFFFFF, 1);
        names.addView(sub);
        names.addView(title, ui.margins(MATCH, WRAP, 0, 8, 0, 0));
        // Live: what's on now and next (▲, and for a few seconds after each zap).
        nowNext = ui.text("", 21, Ui.medium, 0xD9FFFFFF);
        nowNext.setLineSpacing(ui.px(6), 1f);
        nowNext.setVisibility(View.GONE);
        names.addView(nowNext, ui.margins(MATCH, WRAP, 0, 12, 0, 0));
        top.addView(names, new LinearLayout.LayoutParams(0, WRAP, 1));
        quality = ui.text("", 18, Ui.semibold, 0xFFFFFFFF);
        quality.setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(8));
        quality.setVisibility(View.GONE);
        top.addView(quality);
        // Live with several sources (lists, qualities): say so where it shows, next to AO VIVO.
        sources = ui.text("", 17, Ui.semibold, 0xFFFFFFFF);
        sources.setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(8));
        sources.setBackground(TvUi.round(0x40FFFFFF, 10 * ui.k));
        sources.setVisibility(View.GONE);
        top.addView(sources, ui.margins(WRAP, WRAP, 12, 0, 0, 0));
        overlay.addView(top);
        overlay.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1));
        bar = new Bar();
        overlay.addView(bar, new LinearLayout.LayoutParams(MATCH, ui.px(110)));
        LinearLayout row = ui.row();
        clock = ui.text("0:00", 24, Ui.semibold, 0xFFFFFFFF);
        row.addView(clock);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        left = ui.text("", 24, Ui.semibold, 0xB3FFFFFF);
        row.addView(left);
        overlay.addView(row, ui.margins(MATCH, WRAP, 0, 6, 0, 0));
        // The buttons, always in sight with the time bar (▼ goes into them), and the remote's keys under them.
        tools = ui.row();
        tools.setClipChildren(false);
        tools.setClipToPadding(false);
        tools.setPadding(0, ui.px(8), 0, ui.px(8));
        overlay.addView(tools, ui.margins(MATCH, WRAP, 0, 18, 0, 0));
        boolean liveTv0 = getIntent().getStringExtra("channels") != null;
        LinearLayout keys = ui.row();
        String[][] legend = liveTv0
                ? new String[][] {{"◀ ▶", "Trocar de canal"}, {"OK", "O que está passando"}, {"Segure OK", "Trocar a fonte"}, {"▼", "Botões"}}
                : new String[][] {{"OK", "Pausar"}, {"◀ ▶", "10 segundos"}, {"▼", "Botões"}};
        for (String[] k : legend) {
            TextView cap = ui.text(k[0], 16, Ui.semibold, 0xFF1C1C1E);
            cap.setPadding(ui.px(10), ui.px(4), ui.px(10), ui.px(4));
            cap.setBackground(TvUi.round(0xE6FFFFFF, 8 * ui.k));
            keys.addView(cap);
            keys.addView(ui.text(k[1], 17, Ui.medium, 0xCCFFFFFF), ui.margins(WRAP, WRAP, 10, 0, 28, 0));
        }
        overlay.addView(keys, ui.margins(WRAP, WRAP, 0, 14, 0, 0));
        root.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        nextCard = ui.text("", 21, Ui.semibold, 0xFF1C1C1E);
        nextCard.setPadding(ui.px(24), ui.px(18), ui.px(24), ui.px(18));
        nextCard.setBackground(TvUi.round(0xF2FFFFFF, 18 * ui.k));
        nextCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END | Gravity.BOTTOM);
        np.setMargins(0, 0, ui.px(90), ui.px(230));
        root.addView(nextCard, np);
        // Live, OK: the channels of the category on the left; OK tunes, holding OK opens the channel's menu.
        chanScroll = new android.widget.ScrollView(this);
        chanScroll.setVerticalScrollBarEnabled(false);
        chanScroll.setClipToPadding(false);
        chanScroll.setPadding(ui.px(24), ui.px(60), ui.px(24), ui.px(60));
        chanScroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF0101012, 0xE6101012, 0x00101012}));
        chanList = ui.column();
        chanScroll.addView(chanList);
        chanScroll.setVisibility(View.GONE);
        root.addView(chanScroll, new FrameLayout.LayoutParams(ui.px(700), MATCH, Gravity.START));
        panel = ui.column();
        panel.setPadding(ui.px(26), ui.px(26), ui.px(26), ui.px(26));
        panel.setBackground(TvUi.round(0xEB1C1C1E, 26 * ui.k));
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ui.px(560), WRAP, Gravity.END | Gravity.TOP);
        pp.setMargins(0, ui.px(150), ui.px(80), 0);
        root.addView(panel, pp);
        setContentView(root);

        // The player: a bigger buffer (4K over Wi-Fi), redirects followed even from https to http, decoder fallback.
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(AGENT)
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(20000);
        // Live: start after 1 s of picture (zapping feels quick); films: 2.5 s, and a bigger buffer for 4K.
        boolean liveTv = getIntent().getStringExtra("channels") != null;
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(liveTv ? 8000 : 20000, liveTv ? 30000 : 90000, liveTv ? 1000 : 2500, liveTv ? 3000 : 6000).build();
        // MPEG-TS from IPTV servers: start on any keyframe and don't scan far for timestamps (faster first picture).
        androidx.media3.extractor.DefaultExtractorsFactory extractors = new androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setTsExtractorTimestampSearchBytes(600 * 188);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http), extractors))
                .setLoadControl(load).build();
        player.setVideoSurfaceView(surface);
        player.addListener(this);
        boolean dub = Iptv.get(this).preferDubbed();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguages(dub ? new String[] {"pt", "por"} : new String[] {"en", "eng"})
                .setPreferredTextLanguage("pt").build());

        String list = getIntent().getStringExtra("channels");
        if (list != null) {
            try { channels = new JSONArray(list); } catch (Exception e) { channels = new JSONArray(); }
            channel = getIntent().getIntExtra("index", 0);
            tune(channel);
        } else {
            start(getIntent());
        }
    }

    /** Live: the channel at `i` (wrapping around the category). */
    private void tune(int i) {
        int n = channels.length();
        if (n == 0) { finish(); return; }
        channel = (i % n + n) % n;
        JSONObject ch = channels.optJSONObject(channel);
        triedTs = false;
        backup = false;
        alt = 0;
        JSONArray alts = ch.optJSONArray("alts");
        int pick = firstTune ? getIntent().getIntExtra("alt", 0) : nextAlt;   // the source chosen, else the best
        firstTune = false;
        nextAlt = 0;
        if (alts != null && alts.length() > 0) setAlt(ch, Math.max(0, Math.min(pick, alts.length() - 1)));
        updateSourcePill();
        // MPEG-TS first: Xtream servers send it straight away; their HLS is made on the fly and starts slower.
        start(new Intent().putExtra("url", ch.optString("ts", ch.optString("url"))).putExtra("title", ch.optString("title"))
                .putExtra("subtitle", liveSubtitle()));
        nowNext.setText("");
        nowNext.setVisibility(View.GONE);
        handler.removeCallbacks(guideRun);
        handler.postDelayed(guideRun, 700);   // zapping through doesn't ask the server for every channel passed
    }

    private int nextAlt;

    /** "Fonte: Casa · Full HD" next to AO VIVO, when the channel has more than one. */
    private void updateSourcePill() {
        JSONArray a = alts();
        int count = a == null ? 0 : a.length();
        sources.setText(count > 1 ? "Fonte: " + a.optJSONObject(Math.min(alt, count - 1)).optString("label") + "  ·  " + count + " opções" : "");
        sources.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
    }

    /** The lineup's channel behind the one playing (for its guide and favourite), or null while the lineup loads. */
    private Lineup.Channel lineupChannel() {
        Lineup l = Lineup.ready(this);
        if (l == null || channels == null) return null;
        return l.find(channels.optJSONObject(channel).optString("key"));
    }

    private int guideCount = 2;

    /** What's on now and next, under the channel's name. */
    private final Runnable guideRun = new Runnable() { @Override public void run() { guide(); } };

    private void guide() {
        final int at = channel;
        final int many = guideCount;
        Lineup.Channel ch = lineupChannel();
        if (ch == null) {
            // The lineup is still being built (the player opened before it): once it's ready, try again.
            if (channels != null && Lineup.ready(this) == null) withLineup(l -> { if (at == channel) handler.post(guideRun); });
            return;
        }
        Iptv iptv = Iptv.get(this);
        Movies.get(this).run(() -> {
            for (Iptv.Version v : ch.versions) {
                List<Iptv.Program> p = iptv.guide(v);
                if (!p.isEmpty()) return p;
            }
            return new ArrayList<Iptv.Program>();
        }, programs -> {
            if (at != channel || isFinishing()) return;
            if (programs == null || programs.isEmpty()) { nowNext.setText(many > 2 ? "Sem programação para este canal." : ""); nowNext.setVisibility(many > 2 ? View.VISIBLE : View.GONE); return; }
            java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", Locale.US);
            long now = System.currentTimeMillis();
            StringBuilder b = new StringBuilder();
            int shown = 0;
            for (Iptv.Program p : programs) {
                if (p.end <= now || shown >= many) continue;
                boolean on = p.start <= now;
                b.append(b.length() == 0 ? "" : "\n").append(on ? "AGORA   " : hm.format(new java.util.Date(p.start)) + "   ").append(p.title);
                if (on && p.end > p.start) b.append("   · até ").append(hm.format(new java.util.Date(p.end)));
                shown++;
            }
            nowNext.setText(b.toString());
            nowNext.setVisibility(b.length() == 0 ? View.GONE : View.VISIBLE);
        });
    }

    /** The source the channel plays from (index in its "alts": each list and quality it's in, best first). */
    private int alt;

    private JSONArray alts() {
        JSONObject ch = channels == null ? null : channels.optJSONObject(channel);
        return ch == null ? null : ch.optJSONArray("alts");
    }

    private void setAlt(JSONObject ch, int a) {
        JSONObject o = ch.optJSONArray("alts").optJSONObject(a);
        try { ch.put("url", o.optString("url")).put("ts", o.optString("ts")); } catch (Exception ignored) {}
        alt = a;
    }

    private String liveSubtitle() {
        JSONArray a = alts();
        String label = a != null && alt < a.length() ? a.optJSONObject(alt).optString("label") : "";
        return (channel + 1) + " · " + getIntent().getStringExtra("category") + " · AO VIVO" + (label.isEmpty() ? "" : " · " + label);
    }

    /** Plays the channel from another of its sources (a failure, or chosen in MENU). */
    private void switchAlt(int a, String why) {
        JSONObject ch = channels.optJSONObject(channel);
        setAlt(ch, a);
        attempts = 0;
        url = ch.optString("ts");
        sub.setText(liveSubtitle().toUpperCase(Locale.ROOT));
        updateSourcePill();
        if (!inTools) buildTools();   // "Fonte: …" names the new one
        status.setText(why);
        spinner.setVisibility(View.VISIBLE);
        player.stop();
        final int at = channel;
        handler.postDelayed(() -> { if (at == channel && !isFinishing()) open(ch.optString("ts")); }, why.isEmpty() ? 300 : 1200);
    }

    private void start(Intent i) {
        url = i.getStringExtra("url");
        key = i.getStringExtra("key");
        title.setText(i.getStringExtra("title"));
        String s = i.getStringExtra("subtitle") == null ? "" : i.getStringExtra("subtitle");
        sub.setText(s.toUpperCase(Locale.ROOT));
        String badge = s.contains("AO VIVO") ? "AO VIVO" : s.contains("4K") ? "4K" : s.contains("Full HD") ? "Full HD" : "";
        quality.setText(badge);
        quality.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
        quality.setBackground(TvUi.round(badge.equals("AO VIVO") ? 0xFFE5252A : 0x33FFFFFF, 10 * ui.k));
        status.setText("");
        caption.setText("");
        spinner.setVisibility(View.VISIBLE);
        resumed = false;
        retries = 0;
        attempts = 0;
        open(url);
        buildTools();
        showOverlay();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void open(String address) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(address);
        if (address.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
        player.prepare();
        player.setPlayWhenReady(true);
    }

    // MARK: - Player events

    @Override public void onPlaybackStateChanged(int state) {
        spinner.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        if (state == Player.STATE_READY) {
            status.setText("");
            if (!resumed && channels != null) {
                // The source that played comes first next time (when it wasn't the first already).
                JSONArray a = alts();
                if (a != null && alt > 0) Lineup.remember(this, channels.optJSONObject(channel).optString("key"), a.optJSONObject(alt).optString("pick"));
            }
            if (!resumed) {
                resumed = true;
                // A movie or show from My list that started playing moves to Continue watching.
                if (channels == null) {
                    Intent in = getIntent();
                    if (Movies.get(this).watching(in.getStringExtra("kind"), in.getIntExtra("tmdb", 0))) AppLog.w("player: left My list (watching)");
                }
                long at = channels != null ? 0 : Resume.position(this, key);
                long dur = player.getDuration();
                if (at > 0 && dur != C.TIME_UNSET && at < dur - 60_000) player.seekTo(at);
            }
        } else if (state == Player.STATE_ENDED) {
            Resume.finished(this, key);
            playNext();
        }
    }

    @Override public void onPlayerError(PlaybackException e) {
        AppLog.w("player: " + e.getErrorCodeName() + " " + e.getMessage());
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        // Game shortcuts ("BAH x SAO … (Globo)") get fewer tries: the real channel behind them is the better bet.
        String named = channels == null ? "" : channels.optJSONObject(channel).optString("title");
        boolean game = !backup && named.toLowerCase(Locale.ROOT).contains(" x ") && named.contains("(");
        JSONArray alts = alts();
        boolean more = alts != null && alt + 1 < alts.length();
        if (channels != null && attempts < (game || more ? 3 : 7)) {
            // IPTV servers often refuse a channel while the previous one's connection is still open (one connection per
            // account): wait a moment and try again, alternating MPEG-TS and HLS.
            attempts++;
            JSONObject ch = channels.optJSONObject(channel);
            String next = attempts % 2 == 1 ? ch.optString("url") : ch.optString("ts");
            status.setText(attempts > 1 ? "Aguardando o provedor liberar a conexão anterior…" : "");
            spinner.setVisibility(View.VISIBLE);
            final int at = channel;
            // A list with one screen: the provider takes a few seconds to free the channel just closed.
            handler.postDelayed(() -> { if (at == channel && !isFinishing()) open(next); }, attempts == 1 ? 800 : 2500);
            return;
        }
        if (channels == null && player.getCurrentPosition() == 0 && attempts < 2) {
            attempts++;
            handler.postDelayed(() -> { if (!isFinishing()) open(url); }, 1500);
            return;
        }
        if (channels == null && player.getCurrentPosition() > 0 && retries < 3) {
            // A film that stopped mid-way (the connection dropped): pick it up where it was.
            retries++;
            long at = player.getCurrentPosition();
            status.setText("Reconectando…");
            handler.postDelayed(() -> { player.prepare(); player.seekTo(at); }, 1500);
            return;
        }
        if (channels != null && more) {
            // This source didn't open: the channel's next one (another list, or another quality).
            AppLog.w("player: next source " + alts.optJSONObject(alt + 1).optString("label"));
            switchAlt(alt + 1, "Essa fonte não abriu. Tentando " + alts.optJSONObject(alt + 1).optString("label") + "…");
            return;
        }
        if (channels != null && !backup) {
            // A game-of-the-day shortcut that failed: open the real channel named in it, which shows the same game.
            backup = true;
            JSONObject ch = channels.optJSONObject(channel);
            Iptv.Version v = Iptv.get(this).stationFor(ch.optString("title"));
            if (v != null) {
                try { ch.put("url", Iptv.liveUrl(v, true)).put("ts", Iptv.liveUrl(v, false)); } catch (Exception ignored) {}
                AppLog.w("player: trying " + v.raw + " instead");
                attempts = 0;
                status.setText("Abrindo pelo canal " + v.raw + "…");
                handler.postDelayed(() -> { if (!isFinishing()) open(ch.optString("ts")); }, 1500);
                return;
            }
        }
        spinner.setVisibility(View.GONE);
        status.setText(channels != null ? "Este canal não abriu agora. ▲ ▼ para outro canal."
                : "Não deu para tocar este vídeo agora. Volte e tente outra versão em Versões.");
        // Most refusals are the account's screen limit: say so when that's it.
        Iptv.Source src = Iptv.get(this).sourceOf(url);
        if (src != null) {
            String failed = status.getText().toString();
            Movies.get(this).run(() -> Iptv.check(src), s -> {
                if (s == null || isFinishing()) return;
                AppLog.w("player: account " + s.activeConnections + "/" + s.maxConnections + " connections");
                if (s.maxConnections > 0 && s.activeConnections >= s.maxConnections)
                    status.setText(failed + "\n\nSua lista “" + s.name + "” permite " + s.maxConnections + (s.maxConnections == 1 ? " tela" : " telas")
                            + " e o provedor diz que " + (s.activeConnections == 1 ? "ela está" : "elas estão") + " em uso agora (em outro aparelho, ou a conexão anterior ainda não foi liberada).");
                else
                    status.setText(failed + "\n\nO provedor recusou este canal agora (erro do servidor da lista).");
            });
        }
    }

    private int retries, attempts;

    @Override public void onVideoSizeChanged(VideoSize v) {
        if (v.width == 0 || v.height == 0) return;
        // Fit the picture in the screen keeping its shape.
        float ratio = v.width * v.pixelWidthHeightRatio / v.height;
        int sw = stage.getWidth(), sh = stage.getHeight();
        if (sw == 0 || sh == 0) return;
        int w = sw, h = Math.round(sw / ratio);
        if (h > sh) { h = sh; w = Math.round(sh * ratio); }
        surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        if (quality.getVisibility() == View.GONE && channels == null) {
            String q = v.height >= 2000 ? "4K" : v.height >= 1000 ? "Full HD" : v.height >= 700 ? "HD" : "";
            if (!q.isEmpty()) { quality.setText(q); quality.setVisibility(View.VISIBLE); quality.setBackground(TvUi.round(0x33FFFFFF, 10 * ui.k)); }
        }
    }

    @Override public void onCues(CueGroup cues) {
        StringBuilder s = new StringBuilder();
        for (Cue c : cues.cues) if (c.text != null) s.append(s.length() == 0 ? "" : "\n").append(c.text);
        caption.setText(s);
    }

    @Override public void onIsPlayingChanged(boolean playing) {
        if (!playing) save();
        // Playing again (after pausing): the information goes after a moment.
        if (playing && overlay.getAlpha() > 0 && !busy()) { handler.removeCallbacks(hide); handler.postDelayed(hide, 5000); }
    }

    /** The next episode, if this was one. */
    private void playNext() {
        try {
            JSONArray next = new JSONArray(getIntent().getStringExtra("next"));
            if (next.length() == 0) { finish(); return; }
            JSONObject n = next.getJSONObject(0);
            JSONArray rest = new JSONArray();
            for (int j = 1; j < next.length(); j++) rest.put(next.get(j));
            Intent i = new Intent(getIntent()).putExtra("url", n.optString("url")).putExtra("title", n.optString("title"))
                    .putExtra("subtitle", n.optString("subtitle")).putExtra("key", n.optString("key")).putExtra("next", rest.toString());
            setIntent(i);
            start(i);
        } catch (Exception e) {
            finish();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null) {
                long pos = seekTarget >= 0 ? seekTarget : player.getCurrentPosition(), dur = player.getDuration();
                if (dur != C.TIME_UNSET && dur > 0 && !player.isCurrentMediaItemLive()) {
                    bar.set(pos / (float) dur, player.getBufferedPosition() / (float) dur, seekTarget >= 0 ? time(pos) : null);
                    clock.setText(time(pos));
                    left.setText("−" + time(dur - pos));
                    showNext(dur - pos);
                    if (seekTarget < 0 && player.isPlaying() && System.currentTimeMillis() % 10_000 < 500) save();
                } else {
                    bar.set(1, 1, null);
                    clock.setText(new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date()));
                    left.setText("AO VIVO");
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    /**
     * The information leaves the screen 5 s after the last key. Still loading: it waits for the picture and then
     * goes. It stays while paused (that's when you look at it) and while a menu is open (closing it starts again).
     */
    private final Runnable hide = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            if (busy()) {
                if (Watch.isOpen(PlayerActivity.this)) handler.postDelayed(this, 1000);   // a sheet closes on its own: look again
                return;
            }
            if (!player.isPlaying() && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED) {
                handler.postDelayed(this, 1000);   // buffering: check again
                return;
            }
            if (player.isPlaying()) overlay.animate().alpha(0).setDuration(300).start();
        }
    };

    /** Something open that takes the arrows (audio panel, tools bar, channel list, a sheet). */
    private boolean busy() {
        return panel.getVisibility() == View.VISIBLE || inTools || chanScroll.getVisibility() == View.VISIBLE || Watch.isOpen(this);
    }

    private boolean inTools, longPress;

    private void showOverlay() {
        overlay.animate().alpha(1).setDuration(150).start();
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, 5000);
    }

    private final Runnable doSeek = () -> {
        if (seekTarget >= 0) player.seekTo(seekTarget);
        seekTarget = -1;
        repeat = 0;
    };

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        boolean live = channels != null;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (Watch.isOpen(this)) return super.onKeyDown(code, e);
            if (panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); return true; }
            if (chanScroll.getVisibility() == View.VISIBLE) { closeList(); return true; }
            if (inTools) { closeTools(); return true; }
            save();
            finish();
            return true;
        }
        if (busy()) {
            // ▲ from the buttons leaves them.
            if (code == KeyEvent.KEYCODE_DPAD_UP && inTools && panel.getVisibility() != View.VISIBLE && !Watch.isOpen(this)) { closeTools(); return true; }
            if (code == KeyEvent.KEYCODE_MENU && inTools) { closeTools(); return true; }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                // Live: a press shows what's on (on release), holding it chooses the source.
                if (live) {
                    if (e.getRepeatCount() == 0) { longPress = false; e.startTracking(); }
                    return true;
                }
                togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                boolean back = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND;
                if (live) { if (e.getRepeatCount() == 0) zap(back ? -1 : 1); return true; }
                long dur = player.getDuration();
                if (dur == C.TIME_UNSET) { showOverlay(); return true; }
                repeat = e.getRepeatCount() > 0 ? repeat + 1 : 0;
                long step = repeat > 20 ? 60_000 : repeat > 6 ? 30_000 : 10_000;
                long from = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
                seekTarget = Math.max(0, Math.min(dur - 1000, from + (back ? -step : step)));
                handler.removeCallbacks(doSeek);
                handler.postDelayed(doSeek, 450);
                showOverlay();
                tick.run();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (live && e.getRepeatCount() == 0) zap(code == KeyEvent.KEYCODE_CHANNEL_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (live) {
                    // What's on: a longer look at the guide.
                    guideCount = 4;
                    handler.removeCallbacks(guideRun);
                    guideRun.run();
                    guideCount = 2;
                }
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MENU:
                openTools();
                return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyLongPress(int code, KeyEvent e) {
        if (channels != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && !busy()) {
            longPress = true;
            chooseSource();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (channels != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && e.isTracking() && !e.isCanceled()) {
            if (!longPress && !busy()) showInfo();
            longPress = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    /** What's on now and next, with the time bar and the buttons. */
    private void showInfo() {
        guideCount = 3;
        handler.removeCallbacks(guideRun);
        guideRun.run();
        guideCount = 2;
        overlay.animate().alpha(1).setDuration(150).start();
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, 7000);
    }

    /** The channel's sources (holding OK, or the Fonte button). */
    private void chooseSource() {
        JSONObject ch = channels.optJSONObject(channel);
        JSONArray a = alts();
        if (a == null || a.length() < 2) {
            android.widget.Toast.makeText(this, "Este canal tem uma fonte só.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ChannelMenu.sources(this, ch.optString("title"), a, alt, k -> {
            Lineup.remember(this, ch.optString("key"), a.optJSONObject(k).optString("pick"));
            switchAlt(k, "");
        });
    }

    private void togglePause() {
        if (player.isPlaying()) { player.pause(); flash("❚❚"); } else { player.play(); flash("▶"); }
        showOverlay();
    }

    // MARK: - Tools bar (▼)

    /** The row of buttons under the time bar: shown with it, reached with ▼. */
    private void buildTools() {
        tools.removeAllViews();
        if (channels != null) {
            JSONObject ch = channels.optJSONObject(channel);
            tool("☰  Canais", () -> { closeTools(); openList(); });
            JSONArray a = alts();
            if (a != null && a.length() > 1) tool("Fonte: " + a.optJSONObject(Math.min(alt, a.length() - 1)).optString("label"), () -> { closeTools(); chooseSource(); });
            tool("Áudio e legendas", () -> { closeTools(); openPanel(); });
            TvUi.Chip[] fav = new TvUi.Chip[1];
            fav[0] = tool("☆  Favoritar", () -> withLineup(l -> {
                Lineup.Channel lc = l.find(ch.optString("key"));
                if (lc == null) return;
                l.toggleFavourite(lc);
                fav[0].setText(l.favourite(lc) ? "★  Favorito" : "☆  Favoritar");
            }));
            withLineup(l -> {
                Lineup.Channel lc = l.find(ch.optString("key"));
                if (lc != null && l.favourite(lc)) fav[0].setText("★  Favorito");
            });
            String key = ch.optString("key");
            tool("▦  Mosaico", () -> {
                player.stop();   // free this list's screen for the mosaic
                if (key.isEmpty()) startActivity(new Intent(this, MultiActivity.class)); else MultiActivity.add(this, key);
                finish();
            });
            tool("Programação", () -> { closeTools(); openGuide(); });
        } else {
            tool("Áudio e legendas", () -> { closeTools(); openPanel(); });
            tool("↺  Recomeçar do início", () -> { closeTools(); player.seekTo(0); player.play(); });
            String next = getIntent().getStringExtra("next");
            if (next != null && next.length() > 5) tool("⏭  Próximo episódio", () -> { closeTools(); save(); playNext(); });
        }
        focusable(false);
    }

    private void focusable(boolean on) {
        for (int i = 0; i < tools.getChildCount(); i++) tools.getChildAt(i).setFocusable(on);
    }

    /** ▼: into the buttons. */
    private void openTools() {
        if (tools.getChildCount() == 0) buildTools();
        inTools = true;
        focusable(true);
        overlay.animate().alpha(1).setDuration(150).start();
        handler.removeCallbacks(hide);
        View f = tools.getChildAt(0);
        f.post(f::requestFocus);
    }

    private TvUi.Chip tool(String label, Runnable click) {
        TvUi.Chip c = ui.button(label, false, click);
        tools.addView(c, ui.margins(WRAP, WRAP, 0, 0, 14, 0));
        return c;
    }

    private void closeTools() {
        inTools = false;
        focusable(false);   // the arrows change channel again
        showOverlay();
    }

    /** Runs with the lineup (loads it first if it isn't ready yet). */
    private void withLineup(java.util.function.Consumer<Lineup> then) {
        Lineup l = Lineup.ready(this);
        if (l != null) { then.accept(l); return; }
        Movies.get(this).run(() -> Lineup.get(this), got -> { if (got != null && !isFinishing()) then.accept(got); });
    }

    /** Programação: the channel's guide in the side panel. */
    private void openGuide() {
        panel.removeAllViews();
        JSONObject ch = channels.optJSONObject(channel);
        panel.addView(ui.text("PROGRAMAÇÃO", 16, Ui.semibold, 0x8CFFFFFF), ui.margins(WRAP, WRAP, 0, 0, 0, 6));
        panel.addView(ui.line(ch.optString("title"), 24, Ui.semibold, 0xFFFFFFFF, 2), ui.margins(MATCH, WRAP, 0, 0, 0, 14));
        TextView wait = ui.text("Buscando a programação…", 19, Ui.medium, 0xB3FFFFFF);
        panel.addView(wait);
        TvUi.Chip close = ui.chip("Fechar", false, null);
        close.setOnClickListener(v -> { panel.setVisibility(View.GONE); showOverlay(); });
        panel.addView(close, ui.margins(WRAP, WRAP, 0, 18, 0, 0));
        panel.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1).setDuration(150).start();
        handler.removeCallbacks(hide);
        close.post(close::requestFocus);
        final int at = channel;
        withLineup(l -> {
            Lineup.Channel lc = l.find(ch.optString("key"));
            if (lc == null) { wait.setText("Sem programação para este canal."); return; }
            Iptv iptv = Iptv.get(this);
            Movies.get(this).run(() -> {
                for (Iptv.Version v : lc.versions) {
                    List<Iptv.Program> p = iptv.guide(v, 10);
                    if (!p.isEmpty()) return p;
                }
                return new ArrayList<Iptv.Program>();
            }, programs -> {
                if (at != channel || isFinishing() || panel.getVisibility() != View.VISIBLE) return;
                if (programs == null || programs.isEmpty()) { wait.setText("Sem programação para este canal (a lista não envia o guia dele)."); return; }
                java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", Locale.US);
                long now = System.currentTimeMillis();
                int index = panel.indexOfChild(wait);
                panel.removeView(wait);
                for (Iptv.Program p : programs) {
                    boolean on = p.start <= now && p.end > now;
                    LinearLayout row = ui.row();
                    row.setGravity(Gravity.TOP);
                    row.addView(ui.text(on ? "AGORA" : hm.format(new java.util.Date(p.start)), 17, Ui.semibold, on ? 0xFFE5252A : 0x99FFFFFF), TvUi.lp(ui.px(90), WRAP));
                    row.addView(ui.line(p.title, 19, on ? Ui.semibold : Ui.medium, 0xFFFFFFFF, 2), new LinearLayout.LayoutParams(0, WRAP, 1));
                    panel.addView(row, index++, ui.margins(MATCH, WRAP, 0, 0, 0, 10));
                }
            });
        });
    }

    // MARK: - Channel list (live, OK)

    private void openList() {
        chanList.removeAllViews();
        chanList.addView(ui.text(String.valueOf(getIntent().getStringExtra("category")).toUpperCase(Locale.ROOT), 16, Ui.semibold, TvUi.ACCENT), ui.margins(WRAP, WRAP, 8, 0, 0, 14));
        View current = null;
        for (int i = 0; i < channels.length(); i++) {
            final int index = i;
            JSONObject c = channels.optJSONObject(i);
            LinearLayout r = ui.row();
            r.setFocusable(true);
            r.setClickable(true);
            r.setLongClickable(true);
            r.setPadding(ui.px(12), 0, ui.px(16), 0);
            GradientDrawable normal = TvUi.round(i == channel ? 0x33FFFFFF : 0x00000000, 16 * ui.k), on = TvUi.round(0xFFFFFFFF, 16 * ui.k);
            r.setBackground(normal);
            TextView num = ui.text(String.valueOf(i + 1), 17, Ui.semibold, 0x80FFFFFF);
            num.setGravity(Gravity.END);
            r.addView(num, TvUi.lp(ui.px(44), WRAP));
            android.widget.ImageView img = new android.widget.ImageView(this);
            img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            img.setPadding(ui.px(4), ui.px(4), ui.px(4), ui.px(4));
            img.setBackground(TvUi.round(0x1AFFFFFF, 10 * ui.k));
            Img.into(img, c.optString("icon"), ui.px(84));
            r.addView(img, ui.margins(ui.px(84), ui.px(50), 14, 0, 14, 0));
            TextView name = ui.line(c.optString("title"), 20, Ui.semibold, 0xFFFFFFFF, 1);
            r.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
            if (i == channel) r.addView(ui.text("● AGORA", 14, Ui.semibold, 0xFFE5252A), ui.margins(WRAP, WRAP, 10, 0, 0, 0));
            r.setOnFocusChangeListener((v, f) -> {
                r.setBackground(f ? on : normal);
                name.setTextColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
                num.setTextColor(f ? 0xFF6B6B70 : 0x80FFFFFF);
            });
            r.setOnClickListener(v -> { closeList(); if (index != channel) { player.stop(); tune(index); } });
            r.setOnLongClickListener(v -> {
                Lineup l = Lineup.ready(this);
                Lineup.Channel lc = l == null ? null : l.find(c.optString("key"));
                if (lc == null) return false;
                ChannelMenu.show(this, l, lc, () -> { closeList(); player.stop(); tune(index); },
                        k -> { closeList(); player.stop(); nextAlt = k; tune(index); }, null);
                return true;
            });
            chanList.addView(r, ui.margins(MATCH, ui.px(70), 0, 0, 0, 6));
            if (i == channel) current = r;
        }
        chanList.addView(ui.text("OK assiste  ·  segure OK: fonte, favoritar, mosaico", 15, Ui.medium, 0x66FFFFFF), ui.margins(WRAP, WRAP, 8, 12, 0, 0));
        chanScroll.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hide);
        View f = current;
        if (f != null) f.post(() -> { f.requestFocus(); chanScroll.scrollTo(0, Math.max(0, f.getTop() - chanScroll.getHeight() / 3)); });
    }

    private void closeList() {
        chanScroll.setVisibility(View.GONE);
        chanList.removeAllViews();
        showOverlay();
    }

    private void zap(int d) {
        player.stop();
        tune(channel + d);
    }

    private void flash(String symbol) {
        center.setText(symbol);
        center.animate().cancel();
        center.setAlpha(1);
        center.setScaleX(0.9f);
        center.setScaleY(0.9f);
        center.animate().scaleX(1).scaleY(1).alpha(player.isPlaying() ? 0 : 1).setStartDelay(350).setDuration(300).start();
    }

    private void showNext(long remaining) {
        String next = getIntent().getStringExtra("next");
        if (next == null || next.length() < 5 || remaining > 60_000) { nextCard.setVisibility(View.GONE); return; }
        try {
            JSONObject n = new JSONArray(next).getJSONObject(0);
            nextCard.setText("A seguir: " + n.optString("subtitle") + "  ·  em " + Math.max(0, remaining / 1000) + " s");
            nextCard.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            nextCard.setVisibility(View.GONE);
        }
    }

    private final class Bar extends View {
        private float played, loaded;
        private String bubble;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        Bar() {
            super(PlayerActivity.this);
            paint.setTypeface(Ui.semibold);
            paint.setTextSize(24 * ui.k);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        void set(float played, float loaded, String bubble) {
            this.played = Math.max(0, Math.min(1, played));
            this.loaded = Math.max(this.played, Math.min(1, loaded));
            this.bubble = bubble;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), th = 10 * ui.k, y = h - 24 * ui.k, r = th / 2;
            paint.setColor(0x40FFFFFF);
            rect.set(0, y - r, w, y + r);
            c.drawRoundRect(rect, r, r, paint);
            paint.setColor(0x66FFFFFF);
            rect.set(0, y - r, w * loaded, y + r);
            c.drawRoundRect(rect, r, r, paint);
            paint.setColor(0xFFFFFFFF);
            rect.set(0, y - r, w * played, y + r);
            c.drawRoundRect(rect, r, r, paint);
            float x = w * played, knob = (bubble != null ? 16 : 12) * ui.k;
            c.drawCircle(x, y, knob, paint);
            if (bubble != null) {
                float bw = paint.measureText(bubble) + 36 * ui.k, bh = 48 * ui.k, bx = Math.max(bw / 2, Math.min(w - bw / 2, x));
                rect.set(bx - bw / 2, y - 30 * ui.k - bh, bx + bw / 2, y - 30 * ui.k);
                c.drawRoundRect(rect, 12 * ui.k, 12 * ui.k, paint);
                paint.setColor(0xFF1C1C1E);
                c.drawText(bubble, bx, rect.centerY() + 9 * ui.k, paint);
            }
        }
    }


    /** Audio and subtitles: the tracks in the stream. */
    private void openPanel() {
        panel.removeAllViews();
        Tracks tracks = player.getCurrentTracks();
        View first = null;
        panel.addView(ui.text("ÁUDIO", 16, Ui.semibold, 0x8CFFFFFF), ui.margins(WRAP, WRAP, 0, 0, 0, 10));
        int audioCount = 0, textCount = 0;
        boolean textOn = false;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int t = 0; t < g.length; t++) {
                Format f = g.getTrackFormat(t);
                TvUi.Chip c = option(name(f, ++audioCount), g.isTrackSelected(t));
                final Tracks.Group group = g;
                final int index = t;
                c.setOnClickListener(v -> {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), index)).build());
                    panel.setVisibility(View.GONE);
                });
                if (first == null) first = c;
            }
        }
        if (audioCount == 0) panel.addView(ui.text("Só uma faixa de áudio.", 19, Ui.medium, 0xB3FFFFFF));
        panel.addView(ui.text("LEGENDAS", 16, Ui.semibold, 0x8CFFFFFF), ui.margins(WRAP, WRAP, 0, 18, 0, 10));
        for (Tracks.Group g : tracks.getGroups()) if (g.getType() == C.TRACK_TYPE_TEXT && g.isSelected()) textOn = true;
        boolean disabled = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        TvUi.Chip off = option("Desligadas", disabled || !textOn);
        off.setOnClickListener(v -> {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            caption.setText("");
            panel.setVisibility(View.GONE);
        });
        if (first == null) first = off;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int t = 0; t < g.length; t++) {
                Format f = g.getTrackFormat(t);
                TvUi.Chip c = option(name(f, ++textCount), !disabled && g.isTrackSelected(t));
                final Tracks.Group group = g;
                final int index = t;
                c.setOnClickListener(v -> {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), index)).build());
                    panel.setVisibility(View.GONE);
                });
            }
        }
        if (textCount == 0) panel.addView(ui.text("Este vídeo não traz legendas.", 19, Ui.medium, 0xB3FFFFFF));
        panel.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1).setDuration(150).start();
        first.requestFocus();
    }

    private TvUi.Chip option(String label, boolean on) {
        TvUi.Chip c = ui.chip((on ? "✓  " : "") + label, false, null);
        c.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        panel.addView(c, ui.margins(MATCH, WRAP, 0, 0, 0, 8));
        return c;
    }

    private static String name(Format f, int n) {
        String label = f.label;
        String lang = f.language;
        String name = "";
        if (lang != null && !lang.isEmpty() && !lang.equals("und")) {
            String d = new Locale(lang).getDisplayLanguage(new Locale("pt", "BR"));
            name = d.isEmpty() ? lang : Character.toUpperCase(d.charAt(0)) + d.substring(1);
        }
        if (label != null && !label.isEmpty() && !label.equalsIgnoreCase(name)) name = name.isEmpty() ? label : name + " (" + label + ")";
        if (f.channelCount >= 6) name += " · 5.1";
        return name.isEmpty() ? "Faixa " + n : name;
    }

    private void save() {
        if (player == null || key == null || channels != null) return;
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) return;
        Intent i = getIntent();
        Resume.save(this, key, (int) player.getCurrentPosition(), (int) dur, i.getStringExtra("title"), i.getStringExtra("subtitle"),
                i.getStringExtra("image"), url, i.getStringExtra("kind"), i.getIntExtra("tmdb", 0), i.getStringExtra("next"));
    }

    @Override protected void onPause() {
        save();
        if (player != null) player.pause();
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
        showOverlay();   // back to the player: the information shows, then goes
    }

    @Override protected void onDestroy() {
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /** Where you stopped each movie and episode (Continue watching), newest first. */
    static final class Resume {
        static SharedPreferences prefs(Context c) { return c.getSharedPreferences("resume", Context.MODE_PRIVATE); }

        static int position(Context c, String key) {
            if (key == null) return 0;
            try { return new JSONObject(prefs(c).getString(key, "{}")).optInt("pos"); } catch (Exception e) { return 0; }
        }

        static void save(Context c, String key, int pos, int dur, String title, String sub, String image, String url, String kind, int tmdb, String next) {
            try {
                JSONObject o = new JSONObject().put("pos", pos).put("dur", dur).put("title", title).put("sub", sub).put("image", image)
                        .put("url", url).put("kind", kind).put("tmdb", tmdb).put("next", next).put("at", System.currentTimeMillis());
                // Nearly at the end: done, not "continue".
                if (pos > dur - Math.min(5 * 60_000, dur / 10)) { finished(c, key); return; }
                prefs(c).edit().putString(key, o.toString()).apply();
            } catch (Exception ignored) {}
        }

        static void finished(Context c, String key) {
            if (key != null) prefs(c).edit().remove(key).apply();
        }

        /** Up to `max` items, newest first: each JSON with key added. */
        static List<JSONObject> list(Context c, int max) {
            List<JSONObject> out = new ArrayList<>();
            for (java.util.Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
                try {
                    JSONObject o = new JSONObject(String.valueOf(e.getValue())).put("key", e.getKey());
                    out.add(o);
                } catch (Exception ignored) {}
            }
            java.util.Collections.sort(out, (a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
            return out.size() > max ? out.subList(0, max) : out;
        }
    }
}
