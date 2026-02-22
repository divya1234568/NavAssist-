package com.navassist;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.*;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    TextToSpeech tts;
    Vibrator vib;
    String mode;

    // Always-on voice listening state
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean alwaysOnEnabled = false;
    private Handler restartHandler = new Handler(Looper.getMainLooper());
    private TextView tvVoiceStatus;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        mode = getIntent().getStringExtra(SplashActivity.EXTRA_MODE);
        if (mode == null) mode = SplashActivity.MODE_BLIND;

        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        tts = new TextToSpeech(this, this);

        tvVoiceStatus = findViewById(R.id.tv_voice_status);

        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS
        }, 1);

        TextView badge = findViewById(R.id.badge);
        badge.setText(mode.equals(SplashActivity.MODE_BLIND)  ? "👁 BLIND"    :
                      mode.equals(SplashActivity.MODE_DEAF)   ? "👂 DEAF"     : "♿ MOBILITY");

        // Buttons
        findViewById(R.id.btn_sos).setOnClickListener(v -> {
            vibrate(400);
            startActivity(new Intent(this, SOSActivity.class));
        });

        findViewById(R.id.btn_camera).setOnClickListener(v -> {
            speak("Opening camera scanner.");
            vibrate(100);
            startActivity(new Intent(this, CameraActivity.class)
                .putExtra(SplashActivity.EXTRA_MODE, mode));
        });

        // Manual voice button still works
        findViewById(R.id.btn_voice).setOnClickListener(v -> listenVoiceOnce());

        findViewById(R.id.btn_nav).setOnClickListener(v -> openMaps());

        findViewById(R.id.btn_guardian).setOnClickListener(v ->
            startActivity(new Intent(this, GuardianActivity.class)));

        // Toggle always-on voice (especially for blind mode)
        Button btnAlwaysOn = findViewById(R.id.btn_always_on);
        if (SplashActivity.MODE_BLIND.equals(mode)) {
            btnAlwaysOn.setVisibility(android.view.View.VISIBLE);
            btnAlwaysOn.setOnClickListener(v -> toggleAlwaysOnVoice(btnAlwaysOn));
        } else {
            btnAlwaysOn.setVisibility(android.view.View.GONE);
        }
    }

    // ─── Always-On Voice ───────────────────────────────────────────────────────

    private void toggleAlwaysOnVoice(Button btn) {
        if (alwaysOnEnabled) {
            alwaysOnEnabled = false;
            stopAlwaysOnListening();
            btn.setText("🎙 ALWAYS-ON VOICE: OFF");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1E2540));
            tvVoiceStatus.setText("Voice listening: OFF");
            speak("Always-on voice turned off.");
        } else {
            alwaysOnEnabled = true;
            btn.setText("🎙 ALWAYS-ON VOICE: ON");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00A86B));
            tvVoiceStatus.setText("🎤 Listening continuously...");
            speak("Always-on voice activated. I am listening. Say: camera, SOS, navigate, or guardian.");
            startAlwaysOnListening();
        }
    }

    private void startAlwaysOnListening() {
        if (!alwaysOnEnabled) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvVoiceStatus.setText("❌ Speech recognition not available");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                isListening = true;
                runOnUiThread(() -> tvVoiceStatus.setText("🎤 Listening..."));
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                isListening = false;
                runOnUiThread(() -> tvVoiceStatus.setText("⏳ Processing..."));
            }
            @Override public void onError(int error) {
                isListening = false;
                runOnUiThread(() -> tvVoiceStatus.setText("🎤 Listening..."));
                // Auto-restart after short delay
                if (alwaysOnEnabled) {
                    restartHandler.postDelayed(() -> startAlwaysOnListening(), 1000);
                }
            }
            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    handleVoiceCommand(matches.get(0).toLowerCase());
                }
                // Auto-restart to keep listening
                if (alwaysOnEnabled) {
                    restartHandler.postDelayed(() -> startAlwaysOnListening(), 500);
                }
            }
            @Override public void onPartialResults(Bundle partial) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        speechRecognizer.startListening(intent);
    }

    private void stopAlwaysOnListening() {
        alwaysOnEnabled = false;
        restartHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        isListening = false;
    }

    private void handleVoiceCommand(String cmd) {
        runOnUiThread(() -> tvVoiceStatus.setText("💬 Heard: " + cmd));
        vibrate(80);

        if (cmd.contains("scan") || cmd.contains("camera")) {
            speak("Opening camera.");
            startActivity(new Intent(this, CameraActivity.class).putExtra(SplashActivity.EXTRA_MODE, mode));
        } else if (cmd.contains("sos") || cmd.contains("help") || cmd.contains("emergency")) {
            speak("Opening SOS.");
            startActivity(new Intent(this, SOSActivity.class));
        } else if (cmd.contains("guardian") || cmd.contains("guard")) {
            speak("Opening guardian.");
            startActivity(new Intent(this, GuardianActivity.class));
        } else if (cmd.contains("navigate") || cmd.contains("map") || cmd.contains("direction")) {
            openMaps();
        } else {
            speak("Say: camera, SOS, navigate, or guardian.");
        }
    }

    // ─── Manual Voice ──────────────────────────────────────────────────────────

    private void listenVoiceOnce() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command...");
        try {
            startActivityForResult(i, 99);
        } catch (Exception e) {
            Toast.makeText(this, "Voice not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 99 && res == RESULT_OK && data != null) {
            String cmd = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).get(0).toLowerCase();
            handleVoiceCommand(cmd);
        }
    }

    // ─── Navigation & Helpers ─────────────────────────────────────────────────

    private void openMaps() {
        speak("Opening Google Maps for navigation.");
        vibrate(150);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=&mode=w"));
            i.setPackage("com.google.android.apps.maps");
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return;
            }
        } catch (Exception ignored) {}
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")));
    }

    public void speak(String t) {
        if (tts != null && !SplashActivity.MODE_DEAF.equals(mode))
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    void vibrate(long ms) {
        if (vib == null) return;
        if (Build.VERSION.SDK_INT >= 26)
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        else
            vib.vibrate(ms);
    }

    @Override
    public void onInit(int s) {
        if (s == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            if (SplashActivity.MODE_BLIND.equals(mode)) {
                speak("NavAssist ready. Tap Always-On Voice to start hands-free control. " +
                      "Say camera, SOS, navigate, or guardian.");
            } else {
                speak("NavAssist ready.");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop listening when app goes to background (battery saving)
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resume always-on listening when app comes back
        if (alwaysOnEnabled && !isListening) {
            startAlwaysOnListening();
        }
    }

    @Override
    protected void onDestroy() {
        stopAlwaysOnListening();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
