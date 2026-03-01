package com.navassist;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import android.telephony.SmsManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.*;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    TextToSpeech tts;
    Vibrator vib;
    String mode;

    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean alwaysOnEnabled = false;
    private Handler restartHandler = new Handler(Looper.getMainLooper());
    private TextView tvVoiceStatus;

    // For voice navigation destination capture
    private boolean capturingDestination = false;

    // For direct SOS dispatch
    private double lat = 13.0827, lng = 80.2707;
    private PairingManager pm;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        mode = getIntent().getStringExtra(SplashActivity.EXTRA_MODE);
        if (mode == null) mode = SplashActivity.MODE_BLIND;

        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        tts = new TextToSpeech(this, this);
        pm = new PairingManager(this);
        tvVoiceStatus = findViewById(R.id.tv_voice_status);

        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS
        }, 1);

        // Track GPS continuously so SOS always has a location ready
        startLocationTracking();

        TextView badge = findViewById(R.id.badge);
        badge.setText(mode.equals(SplashActivity.MODE_BLIND)  ? "👁 BLIND"    :
                      mode.equals(SplashActivity.MODE_DEAF)   ? "👂 DEAF"     : "♿ MOBILITY");

        findViewById(R.id.btn_sos).setOnClickListener(v -> {
            vibrate(400);
            // FIX 1: For blind mode send SOS directly without going to another page
            if (SplashActivity.MODE_BLIND.equals(mode)) {
                sendSOSDirectly();
            } else {
                startActivity(new Intent(this, SOSActivity.class));
            }
        });

        findViewById(R.id.btn_camera).setOnClickListener(v -> {
            speak("Opening camera scanner.");
            vibrate(100);
            startActivity(new Intent(this, CameraActivity.class)
                .putExtra(SplashActivity.EXTRA_MODE, mode));
        });

        findViewById(R.id.btn_voice).setOnClickListener(v -> listenVoiceOnce());

        findViewById(R.id.btn_nav).setOnClickListener(v -> {
            if (SplashActivity.MODE_BLIND.equals(mode)) {
                askForDestinationVoice();
            } else {
                openMaps(null);
            }
        });

        findViewById(R.id.btn_guardian).setOnClickListener(v ->
            startActivity(new Intent(this, GuardianActivity.class)));

        Button btnAlwaysOn = findViewById(R.id.btn_always_on);
        if (SplashActivity.MODE_BLIND.equals(mode)) {
            btnAlwaysOn.setVisibility(android.view.View.VISIBLE);
            btnAlwaysOn.setOnClickListener(v -> toggleAlwaysOnVoice(btnAlwaysOn));
        } else {
            btnAlwaysOn.setVisibility(android.view.View.GONE);
        }
    }

    // ── GPS Tracking ──────────────────────────────────────────────────────────

    private void startLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) { lat = last.getLatitude(); lng = last.getLongitude(); }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, loc -> {
                lat = loc.getLatitude();
                lng = loc.getLongitude();
            });
        }
    }

    // ── Direct SOS — no page navigation ─────────────────────────────────────

    private void sendSOSDirectly() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            speak("SMS permission denied. Cannot send SOS.");
            return;
        }
        String guardianNumber = pm.getGuardianNumber();
        if (guardianNumber.isEmpty()) {
            speak("No guardian set. Please go to Guardian Hub and add a guardian first.");
            return;
        }
        alertVibrate();
        speak("SOS sent! Alerting your guardian. Help is coming. Stay calm.");
        runOnUiThread(() -> tvVoiceStatus.setText("🆘 SOS sent to guardian!"));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String message = pm.buildSosMessage(lat, lng);
            try {
                SmsManager sm = SmsManager.getDefault();
                ArrayList<String> parts = sm.divideMessage(message);
                sm.sendMultipartTextMessage(guardianNumber, null, parts, null, null);
            } catch (Exception e) {
                speak("SOS failed to send. Please try again.");
            }
        }, 800);
    }

    void alertVibrate() {
        if (vib == null) return;
        long[] p = {0, 400, 150, 400, 150, 800};
        if (Build.VERSION.SDK_INT >= 26)
            vib.vibrate(VibrationEffect.createWaveform(p, -1));
        else
            vib.vibrate(p, -1);
    }

    // ── Voice Navigation with Destination ────────────────────────────────────

    private void askForDestinationVoice() {
        capturingDestination = true;
        speak("Where do you want to go? Please say the place name.");
        runOnUiThread(() -> tvVoiceStatus.setText("🎤 Say your destination..."));
        // Destroy current always-on recognizer to free the mic
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        // Give TTS time to finish, then start destination capture
        restartHandler.postDelayed(this::startDestinationCapture, 2800);
    }

    private void startDestinationCapture() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                runOnUiThread(() -> tvVoiceStatus.setText("🎤 Say destination now..."));
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {
                runOnUiThread(() -> tvVoiceStatus.setText("⏳ Processing..."));
            }
            @Override public void onError(int error) {
                capturingDestination = false;
                speak("Could not hear the destination. Please try again.");
                runOnUiThread(() -> tvVoiceStatus.setText("🎤 Listening..."));
                if (alwaysOnEnabled) restartHandler.postDelayed(() -> startAlwaysOnListening(), 2000);
            }
            @Override public void onResults(Bundle results) {
                capturingDestination = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String destination = matches.get(0);
                    speak("Navigating to " + destination);
                    openMaps(destination);
                }
                if (alwaysOnEnabled) restartHandler.postDelayed(() -> startAlwaysOnListening(), 3500);
            }
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int e, Bundle p) {}
        });

        speechRecognizer.startListening(intent);
    }

    // ── Always-On Voice ───────────────────────────────────────────────────────

    private void enableAlwaysOnVoice() {
        alwaysOnEnabled = true;
        Button btnAlwaysOn = findViewById(R.id.btn_always_on);
        if (btnAlwaysOn != null) {
            btnAlwaysOn.setText("🎙 ALWAYS-ON VOICE: ON");
            btnAlwaysOn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00A86B));
        }
        if (tvVoiceStatus != null) tvVoiceStatus.setText("🎤 Listening continuously...");
        startAlwaysOnListening();
    }

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
            speak("Always-on voice activated. Say: camera, SOS, navigate, or guardian.");
            startAlwaysOnListening();
        }
    }

    private void startAlwaysOnListening() {
        if (!alwaysOnEnabled || capturingDestination) return;
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
                if (alwaysOnEnabled && !capturingDestination) {
                    restartHandler.postDelayed(() -> startAlwaysOnListening(), 1000);
                }
            }
            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    handleVoiceCommand(matches.get(0).toLowerCase());
                }
                if (alwaysOnEnabled && !capturingDestination) {
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
            // FIX 1: Blind mode sends SOS directly — no page navigation
            if (SplashActivity.MODE_BLIND.equals(mode)) {
                sendSOSDirectly();
            } else {
                speak("Opening SOS.");
                startActivity(new Intent(this, SOSActivity.class));
            }

        } else if (cmd.contains("guardian") || cmd.contains("guard")) {
            speak("Opening guardian.");
            startActivity(new Intent(this, GuardianActivity.class));

        } else if (cmd.contains("navigate") || cmd.contains("map") || cmd.contains("direction")
                || cmd.contains("go to") || cmd.contains("take me")) {
            // FIX 3: Ask for voice destination, then open maps with directions
            askForDestinationVoice();

        } else {
            speak("Say: camera, SOS, navigate, or guardian.");
        }
    }

    // ── Manual Voice ──────────────────────────────────────────────────────────

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

    // ── Navigation ────────────────────────────────────────────────────────────

    private void openMaps(String destination) {
        vibrate(150);
        try {
            Uri navUri;
            if (destination != null && !destination.isEmpty()) {
                // FIX 3: Open Google Maps navigation with voice-spoken destination pre-filled
                String encoded = Uri.encode(destination);
                navUri = Uri.parse("google.navigation:q=" + encoded + "&mode=w");
            } else {
                navUri = Uri.parse("google.navigation:q=&mode=w");
            }
            Intent i = new Intent(Intent.ACTION_VIEW, navUri);
            i.setPackage("com.google.android.apps.maps");
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return;
            }
        } catch (Exception ignored) {}
        // Browser fallback
        if (destination != null && !destination.isEmpty()) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/maps?daddr=" + Uri.encode(destination))));
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")));
        }
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
                // FIX 2: Auto-start always-on voice for blind users immediately
                speak("NavAssist ready. Voice control is now active. Say camera, SOS, navigate, or guardian.");
                restartHandler.postDelayed(this::enableAlwaysOnVoice, 3800);
            } else {
                speak("NavAssist ready.");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speechRecognizer != null) speechRecognizer.stopListening();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (alwaysOnEnabled && !isListening && !capturingDestination) {
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
