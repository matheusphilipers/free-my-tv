package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Team logos for the sports rows: downloaded once (ESPN's CDN, asked for a small size), kept on disk for 30 days and
 * in memory while the app runs. get() never blocks: a logo not loaded yet is null (the caller draws the initials) and
 * the listener is told when it arrives.
 */
final class SportsLogos {
    interface Listener { void onLogo(); }

    private static final int SIZE = 160;
    private static final Map<String, Bitmap> MEMORY = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>(), FAILED = new HashSet<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Listener listener;

    private SportsLogos() {}

    static void setListener(Listener l) { listener = l; }

    static synchronized Bitmap get(Context c, String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = MEMORY.get(url);
        if (b != null || FAILED.contains(url) || LOADING.contains(url)) return b;
        LOADING.add(url);
        Context app = c.getApplicationContext();
        IO.execute(() -> load(app, url));
        return null;
    }

    private static void load(Context c, String url) {
        Bitmap b = null;
        try {
            File dir = new File(c.getCacheDir(), "logos");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no cache dir");
            File f = new File(dir, Integer.toHexString(url.hashCode()) + ".png");
            if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > 30L * 24 * 60 * 60_000L) download(small(url), f);
            b = BitmapFactory.decodeFile(f.getPath());
            if (b != null && (b.getWidth() > SIZE || b.getHeight() > SIZE)) {
                float s = (float) SIZE / Math.max(b.getWidth(), b.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
                if (scaled != b) b.recycle();
                b = scaled;
            }
        } catch (Exception e) {
            AppLog.w("logo " + url + ": " + e.getMessage());
        }
        Bitmap done = b;
        synchronized (SportsLogos.class) {
            LOADING.remove(url);
            if (done != null) MEMORY.put(url, done);
            else FAILED.add(url);
        }
        if (done != null) MAIN.post(() -> { if (listener != null) listener.onLogo(); });
    }

    /** ESPN's resizer: a 160 px logo instead of the 500 px original. */
    private static String small(String url) {
        String prefix = "https://a.espncdn.com/i/";
        if (url.startsWith(prefix)) return "https://a.espncdn.com/combiner/i?img=/i/" + url.substring(prefix.length()) + "&w=" + SIZE + "&h=" + SIZE;
        return url;
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            File tmp = new File(to.getPath() + ".tmp");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(to)) throw new java.io.IOException("rename");
        } finally {
            c.disconnect();
        }
    }
}
