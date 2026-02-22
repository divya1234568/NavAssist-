package com.navassist;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.widget.*;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.text.SimpleDateFormat;
import java.util.*;

public class GuardianActivity extends AppCompatActivity {

    private PairingManager pm;
    private TextView tvMyCode, tvStatus, tvGuardianNumber, tvPairedStatus;
    private EditText etGuardianPhone, etUserName, etPairingCode;
    private LinearLayout layoutUserSection, layoutGuardianSection;
    private Button btnToggleMode;
    private boolean isGuardianMode = false;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_guardian);

        pm = new PairingManager(this);

        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        }, 2);

        // Views
        tvMyCode        = findViewById(R.id.tv_my_code);
        tvStatus        = findViewById(R.id.tv_pairing_status);
        tvGuardianNumber = findViewById(R.id.tv_guardian_saved);
        tvPairedStatus  = findViewById(R.id.tv_paired_status);
        etGuardianPhone = findViewById(R.id.et_guardian_phone);
        etUserName      = findViewById(R.id.et_user_name);
        etPairingCode   = findViewById(R.id.et_pairing_code);
        layoutUserSection     = findViewById(R.id.layout_user_section);
        layoutGuardianSection = findViewById(R.id.layout_guardian_section);
        btnToggleMode   = findViewById(R.id.btn_toggle_mode);

        // Load saved data
        etUserName.setText(pm.getUserName());
        String savedGuardian = pm.getGuardianNumber();
        if (!savedGuardian.isEmpty()) {
            tvGuardianNumber.setText("✅ Guardian: " + savedGuardian);
        }

        // Display my pairing code
        tvMyCode.setText(pm.getMyPairingCode());
        refreshPairedStatus();

        // Toggle User / Guardian mode
        isGuardianMode = pm.isGuardianMode();
        updateModeUI();
        btnToggleMode.setOnClickListener(v -> {
            isGuardianMode = !isGuardianMode;
            pm.setGuardianMode(isGuardianMode);
            updateModeUI();
        });

        // Save user name
        findViewById(R.id.btn_save_name).setOnClickListener(v -> {
            String name = etUserName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show();
                return;
            }
            pm.saveUserName(name);
            Toast.makeText(this, "✅ Name saved: " + name, Toast.LENGTH_SHORT).show();
        });

        // Save guardian phone number
        findViewById(R.id.btn_save_guardian).setOnClickListener(v -> {
            String phone = etGuardianPhone.getText().toString().trim();
            if (phone.length() < 10) {
                Toast.makeText(this, "Enter a valid phone number (with country code)", Toast.LENGTH_SHORT).show();
                return;
            }
            pm.saveGuardianNumber(phone);
            tvGuardianNumber.setText("✅ Guardian saved: " + phone);
            Toast.makeText(this, "✅ Guardian number saved!", Toast.LENGTH_SHORT).show();
        });

        // Send pairing code via SMS to guardian
        findViewById(R.id.btn_send_code).setOnClickListener(v -> sendPairingCodeSMS());

        // Regenerate code
        findViewById(R.id.btn_regen_code).setOnClickListener(v -> {
            String newCode = pm.generateNewCode();
            tvMyCode.setText(newCode);
            Toast.makeText(this, "New code: " + newCode, Toast.LENGTH_SHORT).show();
        });

        // Guardian enters pairing code (on guardian's phone)
        findViewById(R.id.btn_enter_code).setOnClickListener(v -> enterPairingCode());

        // Back
        findViewById(R.id.btn_back3).setOnClickListener(v -> finish());

        // Live location update loop (simulated - uses real GPS in SOSActivity)
        startLocationUpdateLoop();
    }

    private void updateModeUI() {
        if (isGuardianMode) {
            btnToggleMode.setText("👁 Switch to: USER Mode");
            layoutUserSection.setVisibility(View.GONE);
            layoutGuardianSection.setVisibility(View.VISIBLE);
        } else {
            btnToggleMode.setText("🛡 Switch to: GUARDIAN Mode");
            layoutUserSection.setVisibility(View.VISIBLE);
            layoutGuardianSection.setVisibility(View.GONE);
        }
    }

    private void sendPairingCodeSMS() {
        String guardianPhone = pm.getGuardianNumber();
        if (guardianPhone.isEmpty()) {
            Toast.makeText(this, "First save your guardian's phone number above!", Toast.LENGTH_LONG).show();
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "SMS permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }

        String message = pm.buildPairingMessage(guardianPhone);
        try {
            SmsManager sms = SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(message);
            sms.sendMultipartTextMessage(guardianPhone, null, parts, null, null);
            tvStatus.setText("✅ Pairing code sent to " + guardianPhone);
            tvStatus.setTextColor(0xFF00C97B);
            Toast.makeText(this, "✅ Pairing SMS sent to guardian!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // Fallback: open SMS app
            Intent smsIntent = new Intent(Intent.ACTION_SENDTO);
            smsIntent.setData(Uri.parse("smsto:" + guardianPhone));
            smsIntent.putExtra("sms_body", message);
            startActivity(smsIntent);
        }
    }

    private void enterPairingCode() {
        // This is used on the GUARDIAN'S phone
        // Guardian enters the code they received from the user
        String code = etPairingCode.getText().toString().trim();
        if (code.length() != 6) {
            Toast.makeText(this, "Please enter the 6-digit code from the user", Toast.LENGTH_SHORT).show();
            return;
        }

        // In a no-server setup, the code itself contains the user's phone number
        // embedded in the SMS. The guardian needs to also save the user's number.
        // We ask them to confirm the pairing:
        String userPhone = ""; // Will be extracted from pairing SMS
        // For simplicity, ask guardian to enter the user's phone too
        EditText etUserPhone = findViewById(R.id.et_paired_user_phone);
        if (etUserPhone != null) {
            userPhone = etUserPhone.getText().toString().trim();
        }

        if (!userPhone.isEmpty()) {
            pm.savePairedUserNumber(userPhone);
        }

        // Save the code as confirmed
        getSharedPreferences("navassist_pairing", MODE_PRIVATE)
            .edit()
            .putString("confirmed_code", code)
            .putBoolean("is_paired", true)
            .apply();

        refreshPairedStatus();
        Toast.makeText(this, "✅ Paired successfully! You will receive SOS alerts.", Toast.LENGTH_LONG).show();
        tvPairedStatus.setText("✅ PAIRED — Monitoring active");
        tvPairedStatus.setTextColor(0xFF00C97B);
    }

    private void refreshPairedStatus() {
        boolean isPaired = getSharedPreferences("navassist_pairing", MODE_PRIVATE)
            .getBoolean("is_paired", false);
        if (isPaired) {
            tvPairedStatus.setText("✅ PAIRED — Monitoring active");
            tvPairedStatus.setTextColor(0xFF00C97B);
        } else {
            tvPairedStatus.setText("⚠ Not paired yet");
            tvPairedStatus.setTextColor(0xFFFF8A9B);
        }
    }

    private void startLocationUpdateLoop() {
        TextView tvLoc  = findViewById(R.id.tv_gloc);
        TextView tvTime = findViewById(R.id.tv_gtime);

        // Show real location if available, else show placeholder
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            android.location.Location loc =
                lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            if (loc != null) {
                tvLoc.setText("📍 " + String.format("%.5f", loc.getLatitude())
                    + ", " + String.format("%.5f", loc.getLongitude()));
            }
        }

        // Update timestamp every 5 seconds
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                tvTime.setText("Updated " + new SimpleDateFormat("hh:mm:ss a",
                    Locale.getDefault()).format(new Date()));
                new Handler(Looper.getMainLooper()).postDelayed(this, 5000);
            }
        });
    }
}
