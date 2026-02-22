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
    TextToSpeech tts; Vibrator vib; String mode;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        mode = getIntent().getStringExtra(SplashActivity.EXTRA_MODE);
        if (mode == null) mode = SplashActivity.MODE_BLIND;
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        tts = new TextToSpeech(this, this);
        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.SEND_SMS}, 1);
        TextView badge = findViewById(R.id.badge);
        badge.setText(mode.equals(SplashActivity.MODE_BLIND) ? "👁 BLIND" :
                      mode.equals(SplashActivity.MODE_DEAF)  ? "👂 DEAF"  : "♿ MOBILITY");

        findViewById(R.id.btn_sos).setOnClickListener(v -> {
            vibrate(400); startActivity(new Intent(this, SOSActivity.class)); });
        findViewById(R.id.btn_camera).setOnClickListener(v -> {
            speak("Opening camera scanner."); vibrate(100);
            startActivity(new Intent(this, CameraActivity.class).putExtra(SplashActivity.EXTRA_MODE, mode)); });
        findViewById(R.id.btn_voice).setOnClickListener(v -> listenVoice());
        findViewById(R.id.btn_nav).setOnClickListener(v -> openMaps());
        findViewById(R.id.btn_guardian).setOnClickListener(v ->
            startActivity(new Intent(this, GuardianActivity.class)));
    }

    private void openMaps() {
        speak("Opening Google Maps for navigation.");
        vibrate(150);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=&mode=w"));
            i.setPackage("com.google.android.apps.maps");
            if (i.resolveActivity(getPackageManager()) != null) { startActivity(i); return; }
        } catch (Exception ignored) {}
        // Fallback: browser maps
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")));
    }

    private void listenVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command...");
        try { startActivityForResult(i, 99); }
        catch (Exception e) { Toast.makeText(this, "Voice not available", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 99 && res == RESULT_OK && data != null) {
            String cmd = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).get(0).toLowerCase();
            if (cmd.contains("scan") || cmd.contains("camera")) startActivity(new Intent(this, CameraActivity.class).putExtra(SplashActivity.EXTRA_MODE, mode));
            else if (cmd.contains("sos") || cmd.contains("help")) startActivity(new Intent(this, SOSActivity.class));
            else if (cmd.contains("guardian")) startActivity(new Intent(this, GuardianActivity.class));
            else if (cmd.contains("navigate") || cmd.contains("map")) openMaps();
            else speak("Try: camera, SOS, guardian, or navigate.");
        }
    }

    public void speak(String t) { if (tts != null && !SplashActivity.MODE_DEAF.equals(mode)) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, null); }
    void vibrate(long ms) { if (vib == null) return; if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); else vib.vibrate(ms); }
    @Override public void onInit(int s) { if (s == TextToSpeech.SUCCESS) { tts.setLanguage(Locale.US); speak("NavAssist ready. Tap camera to scan. Tap Navigate for Google Maps."); } }
    @Override protected void onDestroy() { if (tts != null) { tts.stop(); tts.shutdown(); } super.onDestroy(); }
}
