package com.navassist;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.*;
import android.speech.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import java.util.*;
import java.util.concurrent.*;

/**
 * DualCameraActivity
 * ──────────────────
 * Camera scanner for Dual-Disability (Visually Impaired + Hard of Hearing) users.
 *
 * NO audio output. ALL guidance via haptics:
 *
 *   Object direction:
 *     1 pulse  = object on RIGHT
 *     2 pulses = object on LEFT
 *     3 pulses = object BEHIND / far back area
 *     4 pulses = object straight AHEAD (center/front)
 *
 *   Distance intensity:
 *     Very close  → HIGH amplitude
 *     Nearby      → MEDIUM amplitude
 *     Far away    → LOW amplitude
 *
 *   Hazard (road gaps, obstacles):
 *     5 rapid pulses every 5 seconds while hazard stays in frame
 *
 *   Voice commands (user can still speak):
 *     "back/stop/exit" → close camera
 *     "what"           → re-trigger current haptic
 */
public class DualCameraActivity extends AppCompatActivity {

    // ── UI ────────────────────────────────────────────────────────────────────
    private PreviewView preview;
    private TextView tvStatus, tvObjectName, tvObjectPos, tvHapticCode, tvHapticMeaning;

    // ── ML Kit ────────────────────────────────────────────────────────────────
    private ObjectDetector objDet;
    private ImageLabeler labeler;
    private ExecutorService exec;

    // ── Haptic + state ────────────────────────────────────────────────────────
    private HapticEngine haptic;
    private String lastHapticDir = "";
    private long lastDirectionHapticTime = 0;
    private long lastHazardHapticTime = 0;
    private long lastNewObjectTime = 0;
    private String lastObjectName = "";

    private static final long DIR_HAPTIC_COOLDOWN = 2500;   // ms between direction re-fires
    private static final long HAZARD_REPEAT_MS    = 5000;   // ms between hazard bursts

    // ── Hazard keywords ───────────────────────────────────────────────────────
    private static final Set<String> HAZARDS = new HashSet<>(Arrays.asList(
        "hole","gap","crack","step","stairs","staircase","curb","kerb","slope","ramp",
        "obstacle","barrier","wall","fence","pole","pillar","column","door","gate",
        "car","vehicle","motorcycle","bicycle","truck","bus","scooter",
        "person","human","crowd","dog","cat","animal",
        "puddle","water","mud","rock","stone","pit","ditch",
        "construction","cone","traffic cone","bump","speed bump","manhole"
    ));

    // ── Voice (user speaks to control, no audio back) ─────────────────────────
    private SpeechRecognizer camVoice;
    private boolean voiceActive = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Current best detection
    private String currentObjectName = "";
    private String currentDirection = HapticEngine.DIR_FRONT;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_dual_camera);

        haptic = new HapticEngine(this);
        exec   = Executors.newSingleThreadExecutor();

        preview        = findViewById(R.id.dcam_preview);
        tvStatus       = findViewById(R.id.tv_dcam_status);
        tvObjectName   = findViewById(R.id.tv_dcam_object);
        tvObjectPos    = findViewById(R.id.tv_dcam_pos);
        tvHapticCode   = findViewById(R.id.tv_dcam_haptic_code);
        tvHapticMeaning = findViewById(R.id.tv_dcam_haptic_meaning);

        // Back button
        findViewById(R.id.btn_dcam_back).setOnClickListener(v -> finish());

        // Re-trigger haptic button
        findViewById(R.id.btn_dcam_rehaptic).setOnClickListener(v -> {
            haptic.direction(currentDirection);
            updateHapticUI(currentDirection, currentObjectName, false);
        });

        initMLKit();

        // Open-camera haptic immediately
        haptic.cameraOpen();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        // Start voice after brief delay
        handler.postDelayed(this::startVoiceControl, 1200);
    }

    // ── ML Kit ────────────────────────────────────────────────────────────────

    private void initMLKit() {
        objDet = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build());

        labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.40f)  // Lower = catch more object types
            .build());
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider prov = ProcessCameraProvider.getInstance(this).get();
                Preview prev = new Preview.Builder().build();
                prev.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(exec, this::analyzeFrame);
                prov.unbindAll();
                prov.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis);
                runOnUiThread(() -> tvStatus.setText("🟢 SCANNING — Haptics active"));
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("❌ Camera error"));
                haptic.error();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Frame analysis ────────────────────────────────────────────────────────

    @androidx.camera.core.ExperimentalGetImage
    private void analyzeFrame(ImageProxy proxy) {
        if (proxy.getImage() == null) { proxy.close(); return; }
        InputImage img = InputImage.fromMediaImage(
            proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        int W = proxy.getWidth(), H = proxy.getHeight();

        objDet.process(img)
            .addOnSuccessListener(objects -> {
                if (!objects.isEmpty()) {
                    resolveAndDisplay(objects, img, W, H, proxy);
                } else {
                    // Fallback to scene labeler
                    labeler.process(img)
                        .addOnSuccessListener(labels -> {
                            if (!labels.isEmpty()) {
                                labels.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));
                                String name = labels.get(0).getText();
                                dispatchHaptic(name, HapticEngine.DIR_FRONT, 0.5f, W, H,
                                    W/2, H/2, W/4, H/4);
                            }
                        })
                        .addOnCompleteListener(t -> proxy.close());
                    return;
                }
            })
            .addOnFailureListener(e -> proxy.close());
    }

    private void resolveAndDisplay(List<DetectedObject> objects, InputImage img,
                                    int W, int H, ImageProxy proxy) {
        DetectedObject primary = objects.get(0);
        String rawLabel = primary.getLabels().isEmpty() ? "" : primary.getLabels().get(0).getText();
        boolean needsResolution = rawLabel.isEmpty()
            || rawLabel.equalsIgnoreCase("Unknown")
            || rawLabel.equalsIgnoreCase("Physical object");

        Rect box = primary.getBoundingBox();
        float cx = box.centerX() / (float) W;
        float area = (float)(box.width() * box.height()) / (W * H);
        String dir = directionOf(cx);

        if (needsResolution) {
            labeler.process(img)
                .addOnSuccessListener(labels -> {
                    String name = (!labels.isEmpty()) ? labels.get(0).getText() : "Object";
                    dispatchHaptic(name, dir, area, W, H, box.centerX(), box.centerY(),
                        box.width(), box.height());
                })
                .addOnCompleteListener(t -> proxy.close());
        } else {
            dispatchHaptic(rawLabel, dir, area, W, H, box.centerX(), box.centerY(),
                box.width(), box.height());
            proxy.close();
        }
    }

    /**
     * Central haptic dispatch:
     *   • Direction (1/2/3/4 pulses) when object enters or changes side
     *   • Hazard burst every 5s while hazard is in frame
     *   • Updates UI labels (for sighted helper to read)
     */
    private void dispatchHaptic(String name, String dir, float area,
                                  int W, int H, int cx, int cy, int bw, int bh) {
        long now = System.currentTimeMillis();
        currentObjectName = name;
        currentDirection  = dir;

        boolean isHazard = isHazard(name);
        boolean dirChanged = !dir.equals(lastHapticDir);
        boolean newObject  = !name.equalsIgnoreCase(lastObjectName);

        // ── Direction haptic ─────────────────────────────────
        // Fire when: direction changed OR new object OR cooldown expired
        if (dirChanged || newObject || (now - lastDirectionHapticTime > DIR_HAPTIC_COOLDOWN)) {
            lastHapticDir = dir;
            lastObjectName = name;
            lastDirectionHapticTime = now;
            haptic.direction(dir);
            updateHapticUI(dir, name, isHazard);
        }

        // ── Hazard haptic (every 5 seconds) ─────────────────
        if (isHazard && (now - lastHazardHapticTime > HAZARD_REPEAT_MS)) {
            lastHazardHapticTime = now;
            // Short delay so direction haptic finishes first
            handler.postDelayed(() -> haptic.hazard(), 900);
            runOnUiThread(() -> tvStatus.setText("⚠️ HAZARD detected! 5-pulse warning every 5s"));
        }

        // Update object/position display
        String pos = positionLabel(dir, area);
        runOnUiThread(() -> {
            tvObjectName.setText(name);
            tvObjectPos.setText(pos);
        });
    }

    // ── Haptic UI update ──────────────────────────────────────────────────────

    private void updateHapticUI(String dir, String name, boolean isHazard) {
        String code, meaning;
        switch (dir) {
            case HapticEngine.DIR_RIGHT:
                code = "📳 1 PULSE"; meaning = "→ RIGHT  " + name; break;
            case HapticEngine.DIR_LEFT:
                code = "📳📳 2 PULSES"; meaning = "← LEFT  " + name; break;
            case HapticEngine.DIR_BACK:
                code = "📳📳📳 3 PULSES"; meaning = "↓ BACK  " + name; break;
            default:
                code = "📳📳📳📳 4 PULSES"; meaning = "↑ FRONT  " + name; break;
        }
        if (isHazard) meaning += "  ⚠️";
        String finalCode = code, finalMeaning = meaning;
        runOnUiThread(() -> {
            tvHapticCode.setText(finalCode);
            tvHapticMeaning.setText(finalMeaning);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String directionOf(float cx) {
        if (cx < 0.30f) return HapticEngine.DIR_LEFT;
        if (cx > 0.70f) return HapticEngine.DIR_RIGHT;
        return HapticEngine.DIR_FRONT;
    }

    private String positionLabel(String dir, float area) {
        String side = dir.equals(HapticEngine.DIR_LEFT)  ? "Your left" :
                      dir.equals(HapticEngine.DIR_RIGHT) ? "Your right" :
                      dir.equals(HapticEngine.DIR_BACK)  ? "Behind you" : "Straight ahead";
        String dist = area > 0.30f ? "very close!" : area > 0.10f ? "nearby" : "far away";
        return side + " — " + dist;
    }

    private boolean isHazard(String label) {
        if (label == null) return false;
        String l = label.toLowerCase(Locale.ROOT);
        for (String k : HAZARDS) if (l.contains(k)) return true;
        return false;
    }

    // ── Voice control ─────────────────────────────────────────────────────────

    private void startVoiceControl() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        voiceActive = true;
        loopVoice();
    }

    private void loopVoice() {
        if (!voiceActive) return;
        camVoice = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());

        camVoice.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int e) {
                if (voiceActive) handler.postDelayed(() -> loopVoice(), 1200);
            }
            @Override public void onResults(Bundle r) {
                ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (m != null && !m.isEmpty()) handleCamVoice(m.get(0).toLowerCase());
                if (voiceActive) handler.postDelayed(() -> loopVoice(), 700);
            }
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int e, Bundle p) {}
        });
        camVoice.startListening(intent);
    }

    private void handleCamVoice(String cmd) {
        if (cmd.contains("back") || cmd.contains("stop") || cmd.contains("close") || cmd.contains("exit")) {
            haptic.tap();
            finish();
        } else if (cmd.contains("what") || cmd.contains("repeat") || cmd.contains("again")) {
            haptic.direction(currentDirection);
        } else if (cmd.contains("hazard") || cmd.contains("danger")) {
            haptic.hazard();
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
    }

    @Override protected void onPause() {
        super.onPause();
        if (camVoice != null) camVoice.stopListening();
    }

    @Override protected void onResume() {
        super.onResume();
        if (voiceActive) handler.postDelayed(() -> loopVoice(), 500);
    }

    @Override protected void onDestroy() {
        voiceActive = false;
        handler.removeCallbacksAndMessages(null);
        if (camVoice != null) { camVoice.destroy(); }
        exec.shutdown();
        haptic.cancel();
        super.onDestroy();
    }
}
