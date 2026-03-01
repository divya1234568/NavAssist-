package com.navassist;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Random;

/**
 * PairingManager - Guardian sync without Firebase
 *
 * HOW IT WORKS (No internet/Firebase needed):
 * 1. User generates a 6-digit PAIRING CODE
 * 2. User sends that code to the guardian via SMS (auto or manual)
 * 3. Guardian opens app → enters the code → stores user's phone number
 * 4. Both phones are now linked locally
 * 5. When SOS fires, SMS is sent to the stored guardian number
 *
 * Storage: SharedPreferences (local, no server needed)
 */
public class PairingManager {

    private static final String PREFS_NAME     = "navassist_pairing";
    private static final String KEY_MY_CODE    = "my_pairing_code";
    private static final String KEY_GUARDIAN_NUMBER = "guardian_phone";
    private static final String KEY_USER_NAME  = "user_name";
    private static final String KEY_IS_GUARDIAN = "is_guardian_mode";
    private static final String KEY_PAIRED_USER_NUMBER = "paired_user_number";

    private final SharedPreferences prefs;

    public PairingManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ─── Generate / Get My Code ───────────────────────────────────────────────

    /** Returns existing code or generates a new one */
    public String getMyPairingCode() {
        String existing = prefs.getString(KEY_MY_CODE, null);
        if (existing != null) return existing;
        return generateNewCode();
    }

    public String generateNewCode() {
        // 6-digit numeric code
        String code = String.format("%06d", new Random().nextInt(999999));
        prefs.edit().putString(KEY_MY_CODE, code).apply();
        return code;
    }

    // ─── Guardian Phone Number (for SOS SMS) ─────────────────────────────────

    public void saveGuardianNumber(String phoneNumber) {
        prefs.edit().putString(KEY_GUARDIAN_NUMBER, phoneNumber.trim()).apply();
    }

    public String getGuardianNumber() {
        return prefs.getString(KEY_GUARDIAN_NUMBER, "");
    }

    public boolean hasGuardian() {
        return !getGuardianNumber().isEmpty();
    }

    // ─── User Name ────────────────────────────────────────────────────────────

    public void saveUserName(String name) {
        prefs.edit().putString(KEY_USER_NAME, name).apply();
    }

    public String getUserName() {
        return prefs.getString(KEY_USER_NAME, "NavAssist User");
    }

    // ─── Guardian Mode (Guardian's phone) ────────────────────────────────────

    public void setGuardianMode(boolean isGuardian) {
        prefs.edit().putBoolean(KEY_IS_GUARDIAN, isGuardian).apply();
    }

    public boolean isGuardianMode() {
        return prefs.getBoolean(KEY_IS_GUARDIAN, false);
    }

    /** Guardian stores the user's phone number to receive SOS */
    public void savePairedUserNumber(String userPhone) {
        prefs.edit().putString(KEY_PAIRED_USER_NUMBER, userPhone.trim()).apply();
    }

    public String getPairedUserNumber() {
        return prefs.getString(KEY_PAIRED_USER_NUMBER, "");
    }

    // ─── SOS Message Builder ──────────────────────────────────────────────────

    public String buildSosMessage(double lat, double lng) {
        String name = getUserName();
        String link = "https://maps.google.com/?q=" + lat + "," + lng;
        return "🚨 SOS EMERGENCY!\n" +
               name + " needs help urgently!\n" +
               "📍 Live Location: " + link + "\n" +
               "📱 Sent via NavAssist app";
    }

    /** Message user sends to guardian with the pairing code */
    public String buildPairingMessage(String myPhoneNumber) {
        String code = getMyPairingCode();
        String name = getUserName();
        return "Hi! I'm using NavAssist app for navigation assistance.\n" +
               "Please install NavAssist and enter this code to monitor me:\n" +
               "🔑 PAIRING CODE: " + code + "\n" +
               "📱 My number: " + myPhoneNumber + "\n" +
               "(Enter code in app → Guardian → Enter Code)";
    }

    // ─── Clear All ────────────────────────────────────────────────────────────

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
