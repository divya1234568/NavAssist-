package com.navassist;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_BLIND    = "blind";
    public static final String MODE_DEAF     = "deaf";
    public static final String MODE_MOB      = "mobility";
    public static final String MODE_GUARDIAN = "guardian";
    public static final String MODE_DUAL     = "dual";   // NEW: Visually Impaired + Hard of Hearing

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_splash);

        findViewById(R.id.card_blind).setOnClickListener(v -> go(MODE_BLIND));
        findViewById(R.id.card_deaf).setOnClickListener(v  -> go(MODE_DEAF));
        findViewById(R.id.card_mob).setOnClickListener(v   -> go(MODE_MOB));

        // NEW: Dual disability — launches its own dedicated activity
        findViewById(R.id.card_dual).setOnClickListener(v ->
            startActivity(new Intent(this, DualDisabilityActivity.class)));

        // Guardian opens sync/pairing screen
        findViewById(R.id.card_guardian).setOnClickListener(v ->
            startActivity(new Intent(this, GuardianActivity.class)));
    }

    void go(String mode) {
        startActivity(new Intent(this, MainActivity.class).putExtra(EXTRA_MODE, mode));
    }
}
