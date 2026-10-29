package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters, backdrops and photos for the movie screens: downloaded once (kept on disk for 14 days), decoded down to
 * the size they're shown at, and kept in a small memory cache. Never blocks: into(view, url) shows it when it arrives;
 * get(url) returns null until then and tells the listeners.
 */
final class Img {
    interface Listener { void onImage(); }

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private static final Map<String, List<ImageView>> WAITING = new HashMap<>();
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>(), LOADING = new java.util.HashSet<>();
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static Context app;

    private Img() {}

    static void init(Context c) { app = c.getApplicationContext(); }
    static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    static void removeListener(Listener l) { LISTENERS.remove(l); }

    /** TMDB image URL at a given width ("w342", "w780", "w1280", "w185"). */
    static String tmdb(String path, String size) {
        return path == null || path.isEmpty() || path.equals("null") ? "" : "https://image.tmdb.org/t/p/" + size + path;
    }

    /** The bitmap if it's loaded; otherwise starts loading it (maxW: the width it's drawn at) and returns null. */
    static Bitmap get(String url, int maxW) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = MEMORY.get(url);
        if (b != null) return b;
        start(url, maxW);
        return null;
    }

    /** Shows the image in the view (now if it's loaded, else when it arrives, if the view still wants it). */
    static void into(ImageView v, String url, int maxW) {
        v.setTag(url);
        if (url == null || url.isEmpty()) { v.setImageBitmap(null); return; }
        Bitmap b = MEMORY.get(url);
        if (b != null) { v.setImageBitmap(b); return; }
        v.setImageBitmap(null);
        synchronized (WAITING) {
            List<ImageView> list = WAITING.get(url);
            if (list == null) WAITING.put(url, list = new ArrayList<>());
            list.add(v);
        }
        start(url, maxW);
    }

    private static void start(String url, int maxW) {
        synchronized (LOADING) {
            if (FAILED.contains(url) || !LOADING.add(url)) return;
        }
        IO.execute(() -> {
            Bitmap b = null;
            try {
                b = load(url, maxW);
            } catch (Exception e) {
                AppLog.w("image " + url + ": " + e.getMessage());
            }
            Bitmap done = b;
            synchronized (LOADING) {
                LOADING.remove(url);
                if (done == null) FAILED.add(url);
            }
            if (done != null) MEMORY.put(url, done);
            MAIN.post(() -> {
                List<ImageView> list;
                synchronized (WAITING) { list = WAITING.remove(url); }
                if (list != null && done != null) for (ImageView v : list) if (url.equals(v.getTag())) v.setImageBitmap(done);
                if (done != null) for (Listener l : new ArrayList<>(LISTENERS)) l.onImage();
            });
        });
    }

    private static Bitmap load(String url, int maxW) throws Exception {
        File dir = new File(app.getCacheDir(), "img");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no cache dir");
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > 14L * 24 * 60 * 60_000L) download(url, f);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (maxW > 0 && o.outWidth / (sample * 2) >= maxW) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        // TMDB's images are JPEGs with no transparency: 16 bits a pixel is half the memory and looks the same on a TV.
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b != null && maxW > 0 && b.getWidth() > maxW * 1.3f) {
            Bitmap s = Bitmap.createScaledBitmap(b, maxW, Math.max(1, Math.round(b.getHeight() * (float) maxW / b.getWidth())), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            File tmp = new File(to.getPath() + ".part");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(to)) throw new java.io.IOException("rename");
        } finally {
            c.disconnect();
        }
    }
}
