package io.freemytv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The microphone, drawn (no emoji): a glowing orb that breathes and ripples with your voice while listening
 * (orb = true), or a round focusable button with the glyph (orb = false; white when focused).
 */
final class MicView extends View {
    private final boolean orb;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private float level, shown, phase;
    private boolean idle;   // not hearing anymore (thinking, or an error): calmer and grey
    private ValueAnimator spin;
    int restBg = 0x1F000000, restInk = 0xFF1C1C1E, focusBg = 0xFF1C1C1E, focusInk = 0xFFFFFFFF;

    MicView(Context c, boolean orb) {
        super(c);
        this.orb = orb;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        if (!orb) {
            setFocusable(true);
            setClickable(true);
            setOnFocusChangeListener((v, f) -> {
                animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(150).start();
                invalidate();
            });
        }
    }

    /** The voice's loudness, from the recognizer's onRmsChanged (dB, roughly -2 to 10). */
    void level(float db) { level = Math.max(0, Math.min(1, (db + 2) / 12f)); }

    void idle(boolean on) { idle = on; invalidate(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!orb) return;
        spin = ValueAnimator.ofFloat(0, 1);
        spin.setDuration(2400);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            shown += ((idle ? 0 : level) - shown) * 0.25f;   // smooth the loudness
            invalidate();
        });
        spin.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (spin != null) spin.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
        if (!orb) {
            boolean f = isFocused();
            fill.setShader(null);
            fill.setColor(f ? focusBg : restBg);
            c.drawCircle(cx, cy, Math.min(w, h) / 2, fill);
            glyph(c, cx, cy, Math.min(w, h) * 0.46f, f ? focusInk : restInk);
            return;
        }
        float r = Math.min(w, h) * 0.26f;
        // Ripples: three rings going out, bigger when you speak.
        for (int i = 0; i < 3; i++) {
            float p = (phase + i / 3f) % 1f;
            float rr = r * (1.05f + p * (0.55f + shown * 0.9f));
            int a = Math.round((1 - p) * (idle ? 40 : 90 + shown * 120));
            fill.setShader(null);
            fill.setColor(((idle ? 0x8E8E93 : 0xFFB340) & 0xFFFFFF) | (a << 24));
            c.drawCircle(cx, cy, rr, fill);
        }
        // The orb: amber to coral, breathing with your voice.
        float breathe = r * (1 + 0.04f * (float) Math.sin(phase * Math.PI * 2) + shown * 0.12f);
        fill.setShader(idle ? null : new LinearGradient(cx - breathe, cy - breathe, cx + breathe, cy + breathe,
                0xFFFFC24D, 0xFFFF5E7E, Shader.TileMode.CLAMP));
        fill.setColor(0xFF48484A);
        c.drawCircle(cx, cy, breathe, fill);
        fill.setShader(null);
        glyph(c, cx, cy, breathe * 1.05f, 0xFFFFFFFF);
    }

    /** The microphone glyph in a box of `size`, centred on (cx, cy). */
    private void glyph(Canvas c, float cx, float cy, float size, int color) {
        float u = size / 24f, x0 = cx - 12 * u, y0 = cy - 12 * u;
        fill.setShader(null);
        fill.setColor(color);
        tmp.set(x0 + 9 * u, y0 + 2.5f * u, x0 + 15 * u, y0 + 14.5f * u);
        c.drawRoundRect(tmp, 3 * u, 3 * u, fill);
        line.setColor(color);
        line.setStrokeWidth(2f * u);
        tmp.set(x0 + 5.5f * u, y0 + 5 * u, x0 + 18.5f * u, y0 + 18 * u);
        c.drawArc(tmp, 0, 180, false, line);
        c.drawLine(x0 + 12 * u, y0 + 18 * u, x0 + 12 * u, y0 + 21.5f * u, line);
        c.drawLine(x0 + 8.5f * u, y0 + 21.5f * u, x0 + 15.5f * u, y0 + 21.5f * u, line);
    }
}
