package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** The Sports screen, opened from a card on the home screen's sports page (the tab of that row). */
public class SportsActivity extends Activity {
    private SportsView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new SportsView(this, getIntent().getIntExtra("tab", 0));
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (Watch.isOpen(this)) return super.dispatchKeyEvent(event);   // "Onde assistir" is on screen
        if (Voice.openSearch(this, event)) return true;
        if (view != null && view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
