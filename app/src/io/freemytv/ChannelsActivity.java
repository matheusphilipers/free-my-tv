package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Live TV is the "Ao vivo" tab of Assistir now: this opens it (the remote's TV button, the home screen's shortcut). */
public class ChannelsActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = new Intent(this, MoviesActivity.class).putExtra("tab", MoviesActivity.T_LIVE);
        String group = getIntent().getStringExtra("group");
        if (group != null) i.putExtra("group", group);
        startActivity(i);
        finish();
        overridePendingTransition(0, 0);
    }
}
