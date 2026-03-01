package com.navassist;

import android.content.Context;
import android.os.*;

/**
 * HapticEngine — Centralised vibration patterns for Dual-Disability mode.
 *
 * DIRECTION CODES (camera frame / navigation):
 *   RIGHT  → 1 distinct pulse
 *   LEFT   → 2 distinct pulses
 *   BACK   → 3 distinct pulses
 *   FRONT  → 4 distinct pulses
 *
 * SPECIAL PATTERNS:
 *   SOS_CONFIRM  → SOS pattern (3-3-3 morse)
 *   HAZARD       → rapid 5-pulse danger burst
 *   ARRIVED      → long-short-long success pattern
 *   CAMERA_OPEN  → gentle double tap
 *   NAV_START    → rising 3-pulse
 *   ERROR        → slow 2-pulse low amplitude
 */
public class HapticEngine {

    public static final String DIR_RIGHT  = "right";
    public static final String DIR_LEFT   = "left";
    public static final String DIR_BACK   = "back";
    public static final String DIR_FRONT  = "front";

    private final Vibrator vib;

    public HapticEngine(Context ctx) {
        vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    // ── Core: N distinct countable pulses ─────────────────────────────────────
    // Pulse: 100ms ON, 150ms OFF — clearly separable even for light users
    public void pulses(int count) {
        if (vib == null || count <= 0) return;
        // pattern: [delay, on, off, on, off, ...]
        long[] p = new long[1 + count * 2];
        p[0] = 0;
        for (int i = 0; i < count; i++) {
            p[1 + i * 2]     = 110;   // vibrate ON
            p[2 + i * 2]     = 160;   // vibrate OFF
        }
        fire(p, -1);
    }

    // ── Direction haptics ─────────────────────────────────────────────────────
    public void direction(String dir) {
        switch (dir) {
            case DIR_RIGHT: pulses(1); break;
            case DIR_LEFT:  pulses(2); break;
            case DIR_BACK:  pulses(3); break;
            case DIR_FRONT:
            default:        pulses(4); break;
        }
    }

    // ── SOS confirmed (3-3-3 Morse) ──────────────────────────────────────────
    public void sosConfirm() {
        // · · ·  — — —  · · ·
        long dot = 100, dash = 300, s = 120, g = 250;
        long[] p = {
            0,
            dot, s, dot, s, dot, g,        // S  (3 dots)
            dash, s, dash, s, dash, g,     // O  (3 dashes)
            dot, s, dot, s, dot, 0         // S  (3 dots)
        };
        fire(p, -1);
    }

    // ── Road hazard / gap (urgent 5-pulse rapid) ─────────────────────────────
    public void hazard() {
        long[] p = {0, 80,60, 80,60, 80,60, 80,60, 80, 0};
        fire(p, -1);
    }

    // ── Camera opened (double tap) ────────────────────────────────────────────
    public void cameraOpen() {
        long[] p = {0, 60, 80, 60, 0};
        fire(p, -1);
    }

    // ── Navigation started (rising triple) ────────────────────────────────────
    public void navStart() {
        long[] p = {0, 80,100, 120,100, 180, 0};
        fire(p, -1);
    }

    // ── Success / arrived ─────────────────────────────────────────────────────
    public void arrived() {
        long[] p = {0, 200,80, 80,80, 200, 0};
        fire(p, -1);
    }

    // ── Error / not understood ────────────────────────────────────────────────
    public void error() {
        long[] p = {0, 300, 200, 300, 0};
        fire(p, -1);
    }

    // ── Single short confirmation tap ─────────────────────────────────────────
    public void tap() {
        if (vib == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        else
            vib.vibrate(40);
    }

    // ── Cancel all vibrations ─────────────────────────────────────────────────
    public void cancel() {
        if (vib != null) vib.cancel();
    }

    // ── Internal fire ─────────────────────────────────────────────────────────
    private void fire(long[] pattern, int repeat) {
        if (vib == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vib.vibrate(VibrationEffect.createWaveform(pattern, repeat));
        else
            vib.vibrate(pattern, repeat);
    }
}
