package io.freemytv;

import android.util.Log;

import java.util.regex.Pattern;

/** The app's log (adb logcat -s FreeMyTV). Never logs keys, nor the IPTV lists' users and passwords. */
final class AppLog {
    private AppLog() {}

    // Xtream addresses carry the account: ".../live/USER/PASS/123.ts" and "player_api.php?username=U&password=P".
    private static final Pattern PATH = Pattern.compile("(/(?:live|movie|series|timeshift))/[^/\\s]+/[^/\\s]+/"),
            QUERY = Pattern.compile("((?:username|password|api_key|apiKey)=)[^&\\s]+");

    static void w(String message) {
        Log.i("FreeMyTV", redact(message));
    }

    /** The text with any IPTV account in it hidden (for the log and for error messages shown on screen). */
    static String redact(String s) {
        if (s == null || s.isEmpty()) return s;
        return QUERY.matcher(PATH.matcher(s).replaceAll("$1/***/***/")).replaceAll("$1***");
    }
}
