package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Light mode, for boxes with about 1 GB of RAM: no TMDB (no picks, no Explore, Discover, Rankings or movie pages),
 * no title index for the TMDB screens, no mosaic. What stays: the home screen, the shelf, the clock and weather, and
 * your IPTV lists (Ao vivo, Filmes, Séries) with their player. Automatic on weak boxes; lite= in config.properties
 * and the switch in Assistir > Ajustes ("auto", "on", "off") override it. Read once per process: a change applies
 * after the app starts again.
 */
final class Lite {
    private Lite() {}

    /** 1.5 GB: a "1 GB" box reports ~1 GB, a "2 GB" one ~1.8 GB. */
    private static final long LIMIT = 1536L * 1024 * 1024;

    static volatile boolean ON;
    private static volatile boolean ready;

    static void init(Context c) {
        ON = decide(c);
        ready = true;
        AppLog.w("lite: " + (ON ? "on" : "off") + " (" + choice(c) + ", " + (totalMem(c) >> 20) + " MB)");
    }

    static boolean on() { return ON; }

    /** The TMDB key, or nothing in light mode (every TMDB screen already copes with no key). */
    static String tmdbKey() {
        return ready && ON ? "" : Config.TMDB_KEY;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("lite", Context.MODE_PRIVATE);
    }

    /** "auto", "on" or "off": what was picked on the TV, else the build's lite= (config.properties). */
    static String choice(Context c) {
        return sp(c).getString("mode", Config.LITE);
    }

    static void setChoice(Context c, String mode) {
        sp(c).edit().putString("mode", mode).commit();
    }

    /** What "auto" decides on this box. */
    static boolean weak(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && am.isLowRamDevice()) return true;
        long total = totalMem(c);
        return total > 0 && total <= LIMIT;
    }

    private static boolean decide(Context c) {
        String m = choice(c);
        if (m.equals("on")) return true;
        if (m.equals("off")) return false;
        return weak(c);
    }

    static long totalMem(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;
    }
}
