package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Opened by the remote's microphone button when Free My TV is the TV's assistant (instead of Gemini).
 * If Channels or Movies & Shows is on screen, that screen listens; otherwise Movies & Shows opens on Buscar, listening.
 */
public class VoiceActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.w("voice: assist button (" + getIntent().getAction() + ")");
        Activity top = Voice.screen();
        if (top != null && !top.isFinishing()) Voice.requestOn(top);
        else startActivity(new Intent(this, MoviesActivity.class).putExtra("tab", MoviesActivity.T_SEARCH).putExtra("voice", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
        overridePendingTransition(0, 0);
    }
}
