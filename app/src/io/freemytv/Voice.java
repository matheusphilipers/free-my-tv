package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Voice search inside the app, with Android's own speech recognition (no Gemini or Assistant screen): a small
 * "Listening…" card over the screen, the words appear as they're heard, and `done` gets the final text.
 * The remote's microphone button starts it (see isMicKey).
 */
final class Voice {
    interface Heard { void done(String text); }

    static final int PERMISSION = 78;
    private static Voice current;
    private static Heard waiting;   // the listen() that asked for the microphone permission

    private final Activity activity;
    private final Heard heard;
    private SpeechRecognizer recognizer;
    private FrameLayout card;
    private TextView words, hint;
    private MicView mic;
    private boolean finished;

    private Voice(Activity a, Heard h) { activity = a; heard = h; }

    /** The remote's microphone: Search on most remotes, Voice Assist on some (Assist is kept by the system and
     *  reaches us through VoiceActivity). Xiaomi's TV Box S remote sends its mic button (scan code 217, Linux's
     *  KEY_SEARCH) as PROG_BLUE. */
    static boolean isMicKey(int code) {
        return code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_PROG_BLUE;
    }

    static boolean listening() { return current != null; }

    /** Everything the recognizer thought it heard last time, most likely first ("Dona", "Duna", "Luna"). */
    static final List<String> heardAlso = new ArrayList<>();

    /** For screens without their own search: the mic button opens Movies & Shows on Buscar, listening. */
    static boolean openSearch(Activity a, KeyEvent e) {
        if (!isMicKey(e.getKeyCode())) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0)
            a.startActivity(new Intent(a, MoviesActivity.class).putExtra("tab", MoviesActivity.T_SEARCH).putExtra("voice", true));
        return true;
    }

    /** A screen with its own voice search (Channels, Movies & Shows). */
    interface Screen { void voiceSearch(); }

    private static java.lang.ref.WeakReference<Activity> shown = new java.lang.ref.WeakReference<>(null);
    private static Activity pending;

    /** The voice screen on top, if any (set from onResume, cleared in onStop). */
    static Activity screen() { return shown.get(); }

    static void requestOn(Activity a) { pending = a; }

    /** Call from a Screen's onResume: it becomes the target, and listens if the mic button was just pressed. */
    static void resumed(Activity a) {
        shown = new java.lang.ref.WeakReference<>(a);
        if (pending == a) {
            pending = null;
            if (a instanceof Screen) a.getWindow().getDecorView().post(((Screen) a)::voiceSearch);
        }
    }

    /** Call from a Screen's onStop. */
    static void stopped(Activity a) { if (shown.get() == a) shown = new java.lang.ref.WeakReference<>(null); }

    static void listen(Activity a, Heard done) {
        if (current != null) { current.stop(); return; }   // the mic button again: stop
        if (!SpeechRecognizer.isRecognitionAvailable(a)) {
            android.widget.Toast.makeText(a, "Esta TV não tem reconhecimento de voz disponível.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            waiting = done;
            a.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, PERMISSION);
            return;
        }
        current = new Voice(a, done);
        current.start();
    }

    /** Call from the activity's onRequestPermissionsResult. */
    static void onPermission(Activity a, int request, int[] results) {
        if (request != PERMISSION) return;
        Heard h = waiting;
        waiting = null;
        if (h != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen(a, h);
        else android.widget.Toast.makeText(a, "Sem permissão para o microfone, a busca por voz não funciona.", android.widget.Toast.LENGTH_LONG).show();
    }

    /** Call from the activity's onPause: a recognizer must not outlive its screen. */
    static void cancel() { if (current != null) current.stop(); }

    private void start() {
        TvUi ui = new TvUi(activity, true);
        // Like Apple TV's Siri: the screen dims, a dark band rises from the bottom with the orb and what you say.
        card = new FrameLayout(activity);
        card.setBackgroundColor(0x59000000);
        card.setClickable(true);
        android.view.View band = new android.view.View(activity);
        band.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xFA05070B, 0xE605070B, 0x0005070B}));
        card.addView(band, new FrameLayout.LayoutParams(TvUi.MATCH, ui.px(560), Gravity.BOTTOM));
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        mic = new MicView(activity, true);
        row.addView(mic, new LinearLayout.LayoutParams(ui.px(260), ui.px(260)));
        LinearLayout text = ui.column();
        hint = ui.text("OUVINDO", 19, Ui.semibold, TvUi.ACCENT);
        hint.setLetterSpacing(0.12f);
        text.addView(hint);
        words = ui.line("Fale um filme, série, ator, canal ou gênero (“filmes de suspense”)", 46, Ui.semibold, 0x8CFFFFFF, 2);
        words.setLineSpacing(ui.px(4), 1f);
        text.addView(words, ui.margins(ui.px(1300), TvUi.WRAP, 0, 12, 0, 0));
        TextView back = ui.text("Aperte o microfone de novo ou VOLTAR para cancelar", 18, Ui.medium, 0x66FFFFFF);
        text.addView(back, ui.margins(TvUi.WRAP, TvUi.WRAP, 0, 22, 0, 0));
        row.addView(text, ui.margins(TvUi.WRAP, TvUi.WRAP, 20, 0, 0, 0));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(TvUi.WRAP, TvUi.WRAP, Gravity.BOTTOM | Gravity.START);
        rp.leftMargin = ui.px(70);
        rp.bottomMargin = ui.px(60);
        card.addView(row, rp);
        card.setFocusable(true);
        card.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (code == KeyEvent.KEYCODE_BACK || isMicKey(code)) stop();
            return true;
        });
        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(card,
                new FrameLayout.LayoutParams(TvUi.MATCH, TvUi.MATCH));
        card.requestFocus();
        card.setAlpha(0);
        row.setTranslationY(ui.px(60));
        card.animate().alpha(1).setDuration(180).start();
        row.animate().translationY(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        // The TV's default recognizer (Android's speech service), not the Assistant.
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { hint.setText("OUVINDO"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float db) { mic.level(db); }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { hint.setText("ENTENDENDO…"); mic.idle(true); }
            @Override public void onError(int error) {
                AppLog.w("voice: error " + error);
                if (finished) return;
                mic.idle(true);
                hint.setText("NÃO ENTENDI");
                words.setTextColor(0xFFFFFFFF);
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    words.setText("Aperte o microfone e fale de novo.");
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    words.setText("Sem permissão para o microfone.");
                } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER) {
                    words.setText("Sem conexão com o reconhecimento de voz agora.");
                } else {
                    words.setText("Não deu para ouvir agora.");
                }
                finished = true;
                card.postDelayed(Voice.this::stop, 2200);
            }
            @Override public void onResults(Bundle b) {
                finished = true;
                ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = said == null || said.isEmpty() ? "" : said.get(0).trim();
                heardAlso.clear();
                if (said != null) for (String x : said) if (x != null && !x.trim().isEmpty() && !heardAlso.contains(x.trim())) heardAlso.add(x.trim());
                AppLog.w("voice: heard " + heardAlso);
                stop();
                if (!text.isEmpty()) heard.done(text);
            }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (said == null || said.isEmpty() || said.get(0).trim().isEmpty()) return;
                words.setTextColor(0xFFFFFFFF);
                words.setText(said.get(0));
            }
            @Override public void onEvent(int type, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            AppLog.w("voice: " + e);
            hint.setText("NÃO DEU");
            words.setText("Não deu para ouvir agora.");
            card.postDelayed(this::stop, 2200);
        }
    }

    private void stop() {
        if (current == this) current = null;
        finished = true;
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        if (card != null && card.getParent() != null) ((ViewGroup) card.getParent()).removeView(card);
    }
}
