package io.freemytv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sofascore's API through an off-screen WebView: it refuses plain HTTP clients (HTTP 403) but answers a browser. Called
 * from a background thread; one request at a time; each waits up to 15 s for a page whose text is JSON.
 */
final class SofaWeb {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WebView web;

    private SofaWeb() {}

    @SuppressLint("SetJavaScriptEnabled")
    static synchronized String get(Context c, String url) throws IOException {
        final String[] result = {null};
        final CountDownLatch done = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                if (web == null) {
                    web = new WebView(c.getApplicationContext());
                    web.getSettings().setJavaScriptEnabled(true);
                    web.getSettings().setBlockNetworkImage(true);
                }
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String u) {
                        read(v, result, done, 0);
                    }
                });
                web.loadUrl(url);
            } catch (Throwable t) {
                AppLog.w("sofa web: " + t.getMessage());
                done.countDown();
            }
        });
        try {
            if (!done.await(15, TimeUnit.SECONDS)) throw new IOException("sofa web timeout");
        } catch (InterruptedException e) {
            throw new IOException("interrupted");
        }
        if (result[0] == null) throw new IOException("sofa web: no JSON");
        return result[0];
    }

    private static boolean onSite;

    /**
     * Like Sofascore's own site does: the page www.sofascore.com open (off-screen), and the API asked from inside it
     * (same origin, its cookies). `path` is "/team/1977/events/next/0".
     */
    @SuppressLint("SetJavaScriptEnabled")
    static synchronized String fromSite(Context c, String path) throws IOException {
        final String[] result = {null};
        final CountDownLatch done = new CountDownLatch(1);
        final String js = "(function(){window.__fmtv=null;fetch('/api/v1" + path.replace("'", "") + "',{credentials:'include'})"
                + ".then(function(r){return r.text();}).then(function(t){window.__fmtv=t;})"
                + ".catch(function(e){window.__fmtv=JSON.stringify({error:String(e)});});})()";
        MAIN.post(() -> {
            try {
                if (web == null) {
                    web = new WebView(c.getApplicationContext());
                    web.getSettings().setJavaScriptEnabled(true);
                    web.getSettings().setDomStorageEnabled(true);
                    web.getSettings().setBlockNetworkImage(true);
                }
                Runnable ask = () -> {
                    web.evaluateJavascript(js, null);
                    poll(result, done, 0);
                };
                if (onSite) { ask.run(); return; }
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String u) {
                        if (onSite) return;
                        onSite = true;
                        MAIN.postDelayed(ask, 1500);   // let the page's own scripts settle (a challenge, its cookies)
                    }
                });
                web.loadUrl("https://www.sofascore.com/");
            } catch (Throwable t) {
                AppLog.w("sofa site: " + t.getMessage());
                done.countDown();
            }
        });
        try {
            if (!done.await(25, TimeUnit.SECONDS)) throw new IOException("sofa site timeout");
        } catch (InterruptedException e) {
            throw new IOException("interrupted");
        }
        if (result[0] == null) throw new IOException("sofa site: no answer");
        return result[0];
    }

    private static void poll(String[] result, CountDownLatch done, int tries) {
        web.evaluateJavascript("window.__fmtv", value -> {
            String text = null;
            try {
                if (value != null && !value.equals("null")) text = new JSONArray("[" + value + "]").getString(0).trim();
            } catch (Exception ignored) {}
            if (text != null) {
                result[0] = text;
                done.countDown();
            } else if (tries < 30) {
                MAIN.postDelayed(() -> poll(result, done, tries + 1), 500);
            } else {
                done.countDown();
            }
        });
    }

    /** The page's text, if it's JSON; a challenge page first: look again a few times. */
    private static void read(WebView v, String[] result, CountDownLatch done, int tries) {
        v.evaluateJavascript("document.body ? document.body.innerText : ''", value -> {
            String text = "";
            try {
                text = new JSONArray("[" + value + "]").getString(0).trim();
            } catch (Exception ignored) {}
            if (text.startsWith("{")) {
                result[0] = text;
                done.countDown();
            } else if (tries < 10) {
                MAIN.postDelayed(() -> read(v, result, done, tries + 1), 800);
            } else {
                done.countDown();
            }
        });
    }
}
