package com.navassist;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import android.speech.tts.*;
import android.telephony.SmsManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.Locale;
import java.util.ArrayList;

public class SOSActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    TextToSpeech tts;
    Vibrator vib;
    double lat = 13.0827, lng = 80.2707;
    boolean sent = false;
    TextView tvStatus, tvLoc, tvGuardianInfo;
    PairingManager pm;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_sos);

        pm  = new PairingManager(this);
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        tts = new TextToSpeech(this, this);

        tvStatus       = findViewById(R.id.tv_sos_status);
        tvLoc          = findViewById(R.id.tv_sos_loc);
        tvGuardianInfo = findViewById(R.id.tv_guardian_info);

        // Show guardian info
        String guardianNum = pm.getGuardianNumber();
        if (!guardianNum.isEmpty()) {
            tvGuardianInfo.setText("📲 SOS will be sent to: " + guardianNum);
            tvGuardianInfo.setTextColor(0xFF00C97B);
        } else {
            tvGuardianInfo.setText("⚠ No guardian set! Go to Guardian Hub to add one.");
            tvGuardianInfo.setTextColor(0xFFFF8A9B);
        }

        alertVibrate();

        // Get GPS location
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l != null) { lat = l.getLatitude(); lng = l.getLongitude(); }

            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, loc -> {
                lat = loc.getLatitude();
                lng = loc.getLongitude();
                tvLoc.setText("📍 " + f(lat) + ", " + f(lng));
            });
        }

        tvLoc.setText("📍 " + f(lat) + ", " + f(lng));

        findViewById(R.id.btn_send).setOnClickListener(v -> sendSOS());
        findViewById(R.id.btn_cancel2).setOnClickListener(v -> finish());
    }

    String f(double d) { return String.format("%.5f", d); }

    void sendSOS() {
        if (sent) return;
        sent = true;
        alertVibrate();

        tvStatus.setText("⏳ Sending SOS...");
        speak("SOS sent! Alerting your guardian. Help is coming. Stay calm.");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sendSMSToGuardian();
            tvStatus.setText("✅ SOS sent to guardian!");
            tvStatus.setTextColor(0xFF00C97B);
            ((Button) findViewById(R.id.btn_send)).setEnabled(false);
        }, 1000);
    }

    void sendSMSToGuardian() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setText("❌ SMS permission denied");
            return;
        }

        String guardianNumber = pm.getGuardianNumber();
        if (guardianNumber.isEmpty()) {
            tvStatus.setText("❌ No guardian number! Set it in Guardian Hub.");
            return;
        }

        String message = pm.buildSosMessage(lat, lng);

        try {
            SmsManager sm = SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(message);
            sm.sendMultipartTextMessage(guardianNumber, null, parts, null, null);
            tvStatus.setText("✅ SOS SMS sent to " + guardianNumber);
        } catch (Exception e) {
            tvStatus.setText("❌ SMS failed: " + e.getMessage());
        }
    }

    void alertVibrate() {
        if (vib == null) return;
        long[] p = {0, 400, 150, 400, 150, 800};
        if (Build.VERSION.SDK_INT >= 26)
            vib.vibrate(VibrationEffect.createWaveform(p, -1));
        else
            vib.vibrate(p, -1);
    }

    public void speak(String t) {
        if (tts != null) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    @Override
    public void onInit(int s) {
        if (s == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            speak("SOS screen. Press Send SOS Now to alert your guardian immediately.");
        }
    }

    @Override
    protected void onDestroy() {
        if (vib != null) vib.cancel();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
