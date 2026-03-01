package com.navassist;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.speech.*;
import android.telephony.SmsManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.*;

/**
 * DualDisabilityActivity
 * ─────────────────────
 * For users who are BOTH Visually Impaired AND Hard of Hearing.
 *
 * Design principles:
 *   • NO sound/TTS output (hard of hearing — can't rely on audio)
 *   • NO visual reading needed (visually impaired — can't see screen)
 *   • ALL feedback through haptics (vibration patterns)
 *   • Always-on voice input (they CAN speak, just not hear/see)
 *   • SOS dispatched instantly on voice command
 *   • Navigation opens Google Maps by voice destination
 *   • Camera opens automatically with directional haptics
 *
 * Haptic language (direction codes):
 *   1 pulse  = RIGHT
 *   2 pulses = LEFT
 *   3 pulses = BACK
 *   4 pulses = FRONT / CONFIRM
 */
public class DualDisabilityActivity extends AppCompatActivity {

    private HapticEngine haptic;
    private PairingManager pm;

    // Location for SOS
    private double lat = 13.0827, lng = 80.2707;

    // Voice recognition
    private SpeechRecognizer speechRecognizer;
    private boolean voiceActive = false;
    private boolean capturingDestination = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // UI
    private TextView tvStatus, tvVoiceHeard, tvHapticGuide;
    private Button btnVoiceToggle;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_dual_disability);

        haptic = new HapticEngine(this);
        pm     = new PairingManager(this);

        tvStatus      = findViewById(R.id.tv_dual_status);
        tvVoiceHeard  = findViewById(R.id.tv_dual_voice_heard);
        tvHapticGuide = findViewById(R.id.tv_haptic_guide);
        btnVoiceToggle = findViewById(R.id.btn_dual_voice_toggle);

        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA
        }, 1);

        startLocationTracking();

        // Buttons — large, accessible
        findViewById(R.id.btn_dual_sos).setOnClickListener(v -> sendSOSDirectly());
        findViewById(R.id.btn_dual_camera).setOnClickListener(v -> openDualCamera());
        findViewById(R.id.btn_dual_nav).setOnClickListener(v -> askDestinationVoice());
        btnVoiceToggle.setOnClickListener(v -> toggleVoice());
        findViewById(R.id.btn_dual_guardian).setOnClickListener(v ->
            startActivity(new Intent(this, GuardianActivity.class)));

        // Welcome haptic: 4 pulses (front = "hello, I'm ready")
        handler.postDelayed(() -> haptic.pulses(4), 600);

        // Auto-start voice after 1.5 seconds
        handler.postDelayed(this::enableVoice, 1500);
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private void startLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) { lat = last.getLatitude(); lng = last.getLongitude(); }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, loc -> {
                lat = loc.getLatitude(); lng = loc.getLongitude();
            });
        }
    }

    // ── SOS (direct send, no page navigation) ────────────────────────────────

    private void sendSOSDirectly() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            haptic.error();
            return;
        }
        String guardianNumber = pm.getGuardianNumber();
        if (guardianNumber.isEmpty()) {
            haptic.error(); // 2 slow pulses = error
            runOnUiThread(() -> tvStatus.setText("❌ No guardian set. Add in Guardian Hub."));
            return;
        }
        // SOS haptic: morse S-O-S
        haptic.sosConfirm();
        runOnUiThread(() -> tvStatus.setText("🆘 SOS SENT to " + guardianNumber));

        handler.postDelayed(() -> {
            String message = pm.buildSosMessage(lat, lng);
            try {
                SmsManager sm = SmsManager.getDefault();
                ArrayList<String> parts = sm.divideMessage(message);
                sm.sendMultipartTextMessage(guardianNumber, null, parts, null, null);
                runOnUiThread(() -> tvStatus.setText("✅ SOS delivered to guardian!"));
                // Confirm haptic: 4 pulses
                handler.postDelayed(() -> haptic.pulses(4), 500);
            } catch (Exception e) {
                haptic.error();
                runOnUiThread(() -> tvStatus.setText("❌ SOS failed: " + e.getMessage()));
            }
        }, 600);
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void openDualCamera() {
        haptic.cameraOpen();
        runOnUiThread(() -> tvStatus.setText("📸 Opening camera with haptic guidance..."));
        handler.postDelayed(() -> {
            startActivity(new Intent(this, DualCameraActivity.class));
        }, 400);
    }

    // ── Navigation by voice ───────────────────────────────────────────────────

    private void askDestinationVoice() {
        capturingDestination = true;
        haptic.navStart();
        runOnUiThread(() -> {
            tvStatus.setText("🗺️ Say your destination now...");
            tvVoiceHeard.setText("🎤 Listening for destination...");
        });

        // Temporarily pause always-on loop
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        handler.postDelayed(this::startDestinationCapture, 600);
    }

    private void startDestinationCapture() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { haptic.error(); return; }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = buildSpeechIntent();
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int e) {
                capturingDestination = false;
                haptic.error();
                runOnUiThread(() -> tvStatus.setText("❌ Not heard. Try again."));
                if (voiceActive) handler.postDelayed(() -> startListeningLoop(), 2000);
            }
            @Override public void onResults(Bundle results) {
                capturingDestination = false;
                ArrayList<String> m = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (m != null && !m.isEmpty()) {
                    String dest = m.get(0);
                    openMapsTo(dest);
                }
                if (voiceActive) handler.postDelayed(() -> startListeningLoop(), 3500);
            }
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int e, Bundle p) {}
        });
        speechRecognizer.startListening(intent);
    }

    private void openMapsTo(String destination) {
        haptic.navStart();
        runOnUiThread(() -> {
            tvStatus.setText("🗺️ Navigating to: " + destination);
            tvVoiceHeard.setText("📍 " + destination);
        });
        try {
            String encoded = Uri.encode(destination);
            Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + encoded + "&mode=w"));
            i.setPackage("com.google.android.apps.maps");
            if (i.resolveActivity(getPackageManager()) != null) { startActivity(i); return; }
        } catch (Exception ignored) {}
        startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://maps.google.com/maps?daddr=" + Uri.encode(destination))));
    }

    // ── Always-On Voice ───────────────────────────────────────────────────────

    private void enableVoice() {
        voiceActive = true;
        runOnUiThread(() -> {
            btnVoiceToggle.setText("🎤 VOICE: ON");
            btnVoiceToggle.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF00A86B));
            tvStatus.setText("✅ Ready. Say: camera, SOS, navigate, or guardian.");
        });
        haptic.tap();
        startListeningLoop();
    }

    private void toggleVoice() {
        if (voiceActive) {
            voiceActive = false;
            stopVoice();
            haptic.error();
            runOnUiThread(() -> {
                btnVoiceToggle.setText("🎤 VOICE: OFF");
                btnVoiceToggle.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1E2540));
                tvStatus.setText("Voice OFF. Tap to re-enable.");
            });
        } else {
            enableVoice();
        }
    }

    private void startListeningLoop() {
        if (!voiceActive || capturingDestination) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                runOnUiThread(() -> tvVoiceHeard.setText("🎤 Listening..."));
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int e) {
                if (voiceActive && !capturingDestination)
                    handler.postDelayed(() -> startListeningLoop(), 1000);
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> m = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (m != null && !m.isEmpty()) handleCommand(m.get(0).toLowerCase());
                if (voiceActive && !capturingDestination)
                    handler.postDelayed(() -> startListeningLoop(), 600);
            }
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int e, Bundle p) {}
        });
        speechRecognizer.startListening(buildSpeechIntent());
    }

    private void handleCommand(String cmd) {
        runOnUiThread(() -> tvVoiceHeard.setText("💬 Heard: " + cmd));
        haptic.tap();

        if (cmd.contains("camera") || cmd.contains("scan")) {
            openDualCamera();
        } else if (cmd.contains("sos") || cmd.contains("help") || cmd.contains("emergency")) {
            sendSOSDirectly();
        } else if (cmd.contains("navigate") || cmd.contains("direction") || cmd.contains("go to")
                || cmd.contains("map") || cmd.contains("take me")) {
            askDestinationVoice();
        } else if (cmd.contains("guardian")) {
            startActivity(new Intent(this, GuardianActivity.class));
        } else {
            haptic.error();
            runOnUiThread(() -> tvStatus.setText("❓ Say: camera, SOS, navigate, or guardian"));
        }
    }

    private Intent buildSpeechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        return i;
    }

    private void stopVoice() {
        handler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (speechRecognizer != null) speechRecognizer.stopListening();
    }

    @Override protected void onResume() {
        super.onResume();
        if (voiceActive && speechRecognizer == null && !capturingDestination)
            handler.postDelayed(() -> startListeningLoop(), 500);
    }

    @Override protected void onDestroy() {
        stopVoice();
        haptic.cancel();
        super.onDestroy();
    }
}
