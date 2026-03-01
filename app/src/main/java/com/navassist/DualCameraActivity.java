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
 * DualCameraActivity — Camera scanner for Dual-Disability users
 * (Visually Impaired + Hard of Hearing)
 *
 * NO audio. ALL feedback via HapticEngine.
 *
 * DETECTION FIX — Two-pass per frame:
 *   Pass 1 → ObjectDetector  : bounding box → screen POSITION (left/right/front)
 *   Pass 2 → ImageLabeler    : real object NAME from 400+ classes
 *
 * Haptic direction codes:
 *   1 pulse  = object on RIGHT
 *   2 pulses = object on LEFT
 *   3 pulses = object BEHIND / far area
 *   4 pulses = object STRAIGHT AHEAD / center
 *   5 rapid  = HAZARD/danger every 5 seconds while in frame
 */
public class DualCameraActivity extends AppCompatActivity {

    // ── UI ────────────────────────────────────────────────────────────────────
    private PreviewView preview;
    private TextView tvStatus, tvObjectName, tvObjectPos, tvHapticCode, tvHapticMeaning, tvSecondary;

    // ── ML Kit ────────────────────────────────────────────────────────────────
    private ObjectDetector objDet;   // bounding boxes → position only
    private ImageLabeler labeler;    // REAL names — 400+ classes
    private ExecutorService exec;

    // ── Haptic ────────────────────────────────────────────────────────────────
    private HapticEngine haptic;
    private String lastHapticDir = "";
    private String lastObjectName = "";
    private long lastDirectionHapticTime = 0;
    private long lastHazardHapticTime = 0;
    private static final long DIR_COOLDOWN    = 2500;
    private static final long HAZARD_INTERVAL = 5000;

    // Pending position from ObjectDetector
    private volatile String pendingDir = HapticEngine.DIR_FRONT;

    // ── Hazard vocabulary ─────────────────────────────────────────────────────
    private static final Set<String> HAZARDS = new HashSet<>(Arrays.asList(
        "hole","gap","crack","step","stairs","staircase","curb","kerb","slope","ramp",
        "obstacle","barrier","wall","fence","pole","pillar","column","door","gate",
        "car","vehicle","motorcycle","bicycle","truck","bus","scooter","wheel",
        "person","human","crowd","dog","cat","animal","bird",
        "puddle","water","mud","rock","stone","pit","ditch","drain","manhole",
        "construction","cone","traffic cone","bump","speed bump","cable","wire"
    ));

    // ── Voice control ─────────────────────────────────────────────────────────
    private SpeechRecognizer camVoice;
    private boolean voiceActive = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Current detection
    private String currentObjectName = "";
    private String currentDir = HapticEngine.DIR_FRONT;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_dual_camera);

        haptic = new HapticEngine(this);
        exec   = Executors.newSingleThreadExecutor();

        preview         = findViewById(R.id.dcam_preview);
        tvStatus        = findViewById(R.id.tv_dcam_status);
        tvObjectName    = findViewById(R.id.tv_dcam_object);
        tvObjectPos     = findViewById(R.id.tv_dcam_pos);
        tvHapticCode    = findViewById(R.id.tv_dcam_haptic_code);
        tvHapticMeaning = findViewById(R.id.tv_dcam_haptic_meaning);
        tvSecondary     = findViewById(R.id.tv_dcam_secondary);

        findViewById(R.id.btn_dcam_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_dcam_rehaptic).setOnClickListener(v -> {
            haptic.direction(currentDir);
            updateHapticUI(currentDir, currentObjectName, isHazard(currentObjectName));
        });

        initMLKit();
        haptic.cameraOpen();  // double-tap = camera opened

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);

        handler.postDelayed(this::startVoiceControl, 1200);
    }

    // ── ML Kit Setup ─────────────────────────────────────────────────────────

    private void initMLKit() {
        // ObjectDetector — used ONLY to get bounding box positions
        // Its label output is ignored because it only knows 5 broad categories
        objDet = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build());

        // ImageLabeler — 400+ real object categories, used as name source
        labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.45f)
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

    // ── Frame Analysis ────────────────────────────────────────────────────────

    @androidx.camera.core.ExperimentalGetImage
    private void analyzeFrame(ImageProxy proxy) {
        if (proxy.getImage() == null) { proxy.close(); return; }
        InputImage img = InputImage.fromMediaImage(
            proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        int W = proxy.getWidth(), H = proxy.getHeight();

        // Pass 1: ObjectDetector → extract direction from bounding box
        objDet.process(img)
            .addOnSuccessListener(objects -> {
                if (!objects.isEmpty()) {
                    Rect box = objects.get(0).getBoundingBox();
                    float cx = box.centerX() / (float) W;
                    pendingDir = directionOf(cx);
                } else {
                    pendingDir = HapticEngine.DIR_FRONT;
                }
            })
            .addOnCompleteListener(objTask -> {
                // Pass 2: ImageLabeler → real object name from 400+ classes
                labeler.process(img)
                    .addOnSuccessListener(labels -> {
                        if (labels == null || labels.isEmpty()) {
                            runOnUiThread(() -> {
                                tvObjectName.setText("Scanning...");
                                tvObjectPos.setText("Move camera slowly");
                                if (tvSecondary != null) tvSecondary.setText("");
                            });
                            return;
                        }

                        // Sort by confidence — best match first
                        labels.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));

                        String primaryName = labels.get(0).getText();
                        int primaryConf = (int)(labels.get(0).getConfidence() * 100);
                        String dir = pendingDir;

                        // Build secondary labels for display (up to 3 more)
                        StringBuilder sb = new StringBuilder();
                        int count = Math.min(labels.size(), 4);
                        for (int i = 1; i < count; i++) {
                            if (sb.length() > 0) sb.append("  •  ");
                            sb.append(labels.get(i).getText())
                              .append(" ")
                              .append((int)(labels.get(i).getConfidence() * 100))
                              .append("%");
                        }

                        runOnUiThread(() -> {
                            if (tvSecondary != null) tvSecondary.setText(sb.toString());
                            tvStatus.setText("🟢 " + labels.size() + " object" + (labels.size() > 1 ? "s" : "") + " identified");
                        });

                        dispatchHaptic(primaryName, dir, primaryConf);
                    })
                    .addOnCompleteListener(t -> proxy.close());
            });
    }

    // ── Haptic Dispatch ───────────────────────────────────────────────────────

    private void dispatchHaptic(String name, String dir, int conf) {
        long now = System.currentTimeMillis();
        currentObjectName = name;
        currentDir = dir;

        boolean isHazard  = isHazard(name);
        boolean dirChanged = !dir.equals(lastHapticDir);
        boolean newObject  = !name.equalsIgnoreCase(lastObjectName);
        boolean cooldownExpired = (now - lastDirectionHapticTime) > DIR_COOLDOWN;

        // ── Direction haptic ─────────────────────────────────────────────────
        if (dirChanged || newObject || cooldownExpired) {
            lastHapticDir = dir;
            lastObjectName = name;
            lastDirectionHapticTime = now;
            haptic.direction(dir);
            updateHapticUI(dir, name, isHazard);
        }

        // ── Hazard haptic every 5 seconds ────────────────────────────────────
        if (isHazard && (now - lastHazardHapticTime) > HAZARD_INTERVAL) {
            lastHazardHapticTime = now;
            handler.postDelayed(() -> haptic.hazard(), 900);
            runOnUiThread(() -> tvStatus.setText("⚠️ HAZARD — 5-pulse warning repeating"));
        }

        // Update screen labels
        String posLabel = positionLabel(dir);
        String confLabel = conf + "% confidence";
        runOnUiThread(() -> {
            tvObjectName.setText(name);
            tvObjectPos.setText(posLabel + "  •  " + confLabel);
        });
    }

    // ── Haptic UI Labels ──────────────────────────────────────────────────────

    private void updateHapticUI(String dir, String name, boolean hazard) {
        String code, meaning;
        switch (dir) {
            case HapticEngine.DIR_RIGHT:
                code = "📳  1 PULSE"; meaning = "→ RIGHT"; break;
            case HapticEngine.DIR_LEFT:
                code = "📳📳  2 PULSES"; meaning = "← LEFT"; break;
            case HapticEngine.DIR_BACK:
                code = "📳📳📳  3 PULSES"; meaning = "↓ BACK"; break;
            default:
                code = "📳📳📳📳  4 PULSES"; meaning = "↑ FRONT"; break;
        }
        String fullMeaning = meaning + "  —  " + name + (hazard ? "  ⚠️" : "");
        runOnUiThread(() -> {
            tvHapticCode.setText(code);
            tvHapticMeaning.setText(fullMeaning);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String directionOf(float cx) {
        if (cx < 0.30f) return HapticEngine.DIR_LEFT;
        if (cx > 0.70f) return HapticEngine.DIR_RIGHT;
        return HapticEngine.DIR_FRONT;
    }

    private String positionLabel(String dir) {
        switch (dir) {
            case HapticEngine.DIR_LEFT:  return "Your left";
            case HapticEngine.DIR_RIGHT: return "Your right";
            case HapticEngine.DIR_BACK:  return "Behind you";
            default:                     return "Straight ahead";
        }
    }

    private boolean isHazard(String label) {
        if (label == null) return false;
        String l = label.toLowerCase(Locale.ROOT);
        for (String k : HAZARDS) if (l.contains(k)) return true;
        return false;
    }

    // ── Voice Control ─────────────────────────────────────────────────────────

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
                if (m != null && !m.isEmpty()) handleVoiceCmd(m.get(0).toLowerCase());
                if (voiceActive) handler.postDelayed(() -> loopVoice(), 700);
            }
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int e, Bundle p) {}
        });
        camVoice.startListening(intent);
    }

    private void handleVoiceCmd(String cmd) {
        if (cmd.contains("back") || cmd.contains("stop") || cmd.contains("close") || cmd.contains("exit")) {
            haptic.tap(); finish();
        } else if (cmd.contains("what") || cmd.contains("repeat") || cmd.contains("again")) {
            haptic.direction(currentDir);
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
        if (camVoice != null) camVoice.destroy();
        exec.shutdown();
        haptic.cancel();
        super.onDestroy();
    }
}
