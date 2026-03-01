package com.navassist;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import android.view.View;
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
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.*;
import java.util.concurrent.*;

public class CameraActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    // ── UI ────────────────────────────────────────────────────────────────────
    private PreviewView preview;
    private TextView tvStatus, tvObjectName, tvObjectPos, tvObjectConf, tvHapticInfo;
    private LinearLayout llResult;
    private Button btnToggle;

    // ── ML Kit ────────────────────────────────────────────────────────────────
    private ObjectDetector objDet;
    private ImageLabeler labeler;
    private TextRecognizer txtRec;

    // ── Speech / Haptics ──────────────────────────────────────────────────────
    private TextToSpeech tts;
    private ExecutorService exec;
    private Vibrator vib;
    private String mode;
    private boolean textMode = false;
    private long lastSpeakTime = 0;
    private String lastSpokenText = "";
    private String currentDetection = "";

    // ── Directional haptic state ──────────────────────────────────────────────
    private String lastHapticDirection = "";
    private long lastHazardHapticTime = 0;
    private static final long HAZARD_HAPTIC_INTERVAL = 5000; // 5 seconds

    // ── Hazard keywords that trigger road-gap vibration ───────────────────────
    private static final Set<String> HAZARD_KEYWORDS = new HashSet<>(Arrays.asList(
        "hole", "gap", "crack", "step", "stairs", "staircase", "curb", "kerb",
        "slope", "ramp", "obstacle", "barrier", "wall", "fence", "pole", "column",
        "door", "gate", "car", "vehicle", "motorcycle", "bicycle", "truck", "bus",
        "person", "crowd", "dog", "animal", "puddle", "water", "mud", "rock", "stone",
        "pit", "ditch", "construction", "hazard", "traffic cone", "cone", "bump"
    ));

    // ── Always-on voice for Camera ───────────────────────────────────────────
    private SpeechRecognizer camSpeechRecognizer;
    private boolean camVoiceActive = false;
    private Handler voiceRestartHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        mode = getIntent().getStringExtra(SplashActivity.EXTRA_MODE);
        vib  = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        tts  = new TextToSpeech(this, this);
        exec = Executors.newSingleThreadExecutor();

        preview      = findViewById(R.id.cam_preview);
        tvStatus     = findViewById(R.id.tv_scan_status);
        tvObjectName = findViewById(R.id.tv_object_name);
        tvObjectPos  = findViewById(R.id.tv_object_pos);
        tvObjectConf = findViewById(R.id.tv_object_conf);
        tvHapticInfo = findViewById(R.id.tv_haptic_info);
        llResult     = findViewById(R.id.ll_result);
        btnToggle    = findViewById(R.id.btn_toggle2);

        findViewById(R.id.btn_back2).setOnClickListener(v -> finish());
        findViewById(R.id.btn_speak2).setOnClickListener(v ->
            speak(currentDetection.isEmpty() ? "Nothing detected yet" : currentDetection));

        btnToggle.setOnClickListener(v -> {
            textMode = !textMode;
            btnToggle.setText(textMode ? "🔍 Objects" : "📝 Text");
            tvObjectName.setText(textMode ? "TEXT MODE" : "SCANNING...");
            tvObjectPos.setText(textMode ? "Point at signs or labels" : "Point camera at any object");
            tvObjectConf.setText("");
            speak(textMode ? "Text reading mode." : "Object detection mode.");
        });

        initMLKit();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
    }

    // ── ML Kit Setup ─────────────────────────────────────────────────────────

    private void initMLKit() {
        // Object detector: stream mode, multi-object, classification ON
        objDet = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build());

        // Labeler with lower threshold so more objects are identified (not "Unknown")
        labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.40f)   // was 0.60 — now catches more labels
            .build());

        txtRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview prev = new Preview.Builder().build();
                prev.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(exec, this::analyzeFrame);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis);
                runOnUiThread(() -> tvStatus.setText("🟢 LIVE — Scanning"));
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("❌ Camera error: " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Frame Analysis ───────────────────────────────────────────────────────

    @androidx.camera.core.ExperimentalGetImage
    private void analyzeFrame(ImageProxy proxy) {
        if (proxy.getImage() == null) { proxy.close(); return; }
        InputImage image = InputImage.fromMediaImage(
            proxy.getImage(), proxy.getImageInfo().getRotationDegrees());

        if (textMode) {
            txtRec.process(image)
                .addOnSuccessListener(result -> {
                    String text = result.getText().trim();
                    if (!text.isEmpty()) {
                        String display = text.length() > 80 ? text.substring(0, 80) + "..." : text;
                        showResult("📝 " + display, "Text detected", "OCR", text, 4000,
                                   "center", 0);
                    } else {
                        runOnUiThread(() -> {
                            tvObjectName.setText("No text found");
                            tvObjectPos.setText("Point at a sign or label");
                        });
                    }
                })
                .addOnCompleteListener(t -> proxy.close());
        } else {
            int imgW = proxy.getWidth(), imgH = proxy.getHeight();
            objDet.process(image)
                .addOnSuccessListener(objects -> {
                    if (!objects.isEmpty()) {
                        // Resolve all labels, replacing "Unknown" via labeler
                        processDetectedObjects(objects, image, imgW, imgH, proxy);
                    } else {
                        // No objects from objDet — use labeler as primary
                        labeler.process(image)
                            .addOnSuccessListener(labels -> {
                                if (!labels.isEmpty()) {
                                    // Sort by confidence, pick top
                                    labels.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));
                                    ImageLabel top = labels.get(0);
                                    int c = (int)(top.getConfidence() * 100);
                                    String pos = "center";
                                    // Build spoken text combining top labels
                                    StringBuilder sb = new StringBuilder(top.getText());
                                    if (labels.size() > 1) {
                                        sb.append(", also: ").append(labels.get(1).getText());
                                    }
                                    boolean isHazard = isHazardLabel(top.getText());
                                    showResult(top.getText(), "Scene / Background", c + "% confidence",
                                               sb.toString(), 3000, pos, isHazard ? imgW/2 : -1);
                                    runOnUiThread(() -> tvStatus.setText("🟡 Scene detected"));
                                } else {
                                    runOnUiThread(() -> {
                                        tvObjectName.setText("Scanning...");
                                        tvObjectPos.setText("Move camera slowly");
                                        tvStatus.setText("🟡 Looking...");
                                    });
                                    proxy.close();
                                }
                            })
                            .addOnCompleteListener(t -> proxy.close());
                        return;
                    }
                })
                .addOnFailureListener(e -> proxy.close());
        }
    }

    /**
     * For objects detected by objDet that have "Unknown" labels,
     * re-run ImageLabeler to get a real name.
     */
    private void processDetectedObjects(List<DetectedObject> objects,
                                         InputImage image, int imgW, int imgH, ImageProxy proxy) {
        // Check if primary object needs label resolution
        DetectedObject primary = objects.get(0);
        String primaryLabel = primary.getLabels().isEmpty() ? "" : primary.getLabels().get(0).getText();
        boolean needsResolution = primary.getLabels().isEmpty()
            || primaryLabel.equalsIgnoreCase("Unknown")
            || primaryLabel.equalsIgnoreCase("Physical object");

        if (needsResolution) {
            // Use labeler to get real name
            labeler.process(image)
                .addOnSuccessListener(labels -> {
                    String resolvedName;
                    int resolvedConf;
                    if (!labels.isEmpty()) {
                        labels.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));
                        resolvedName = labels.get(0).getText();
                        resolvedConf = (int)(labels.get(0).getConfidence() * 100);
                    } else {
                        resolvedName = "Object";
                        resolvedConf = 0;
                    }
                    buildAndShowResult(objects, resolvedName, resolvedConf, imgW, imgH);
                })
                .addOnCompleteListener(t -> proxy.close());
        } else {
            buildAndShowResult(objects, primaryLabel,
                (int)(primary.getLabels().get(0).getConfidence() * 100), imgW, imgH);
            proxy.close();
        }
    }

    private void buildAndShowResult(List<DetectedObject> objects, String primaryName,
                                     int primaryConf, int imgW, int imgH) {
        DetectedObject primary = objects.get(0);
        String primaryPos = getPosition(primary.getBoundingBox(), imgW, imgH);
        float primaryCx = primary.getBoundingBox().centerX() / (float) imgW;

        StringBuilder details = new StringBuilder();
        for (int i = 1; i < objects.size(); i++) {
            DetectedObject obj = objects.get(i);
            String name = obj.getLabels().isEmpty() ? "Object" : obj.getLabels().get(0).getText();
            if (name.equalsIgnoreCase("Unknown") || name.equalsIgnoreCase("Physical object"))
                name = "Object";
            int conf = obj.getLabels().isEmpty() ? 0 : (int)(obj.getLabels().get(0).getConfidence() * 100);
            String pos = getPosition(obj.getBoundingBox(), imgW, imgH);
            if (details.length() > 0) details.append("\n");
            details.append("• ").append(name).append(" (").append(conf).append("%) — ").append(pos);
        }

        String confText = primaryConf + "% confidence"
            + (objects.size() > 1 ? "  |  +" + (objects.size()-1) + " more" : "");
        String displayName = objects.size() > 1
            ? primaryName + "\n" + details.toString().trim()
            : primaryName;

        String spoken = primaryName + ", " + primaryPos;
        boolean isHazard = isHazardLabel(primaryName);
        int objectCenterX = primary.getBoundingBox().centerX();

        showResult(displayName, primaryPos, confText, spoken, 3000,
                   getDirectionKey(primaryCx), isHazard ? objectCenterX : -1);
        runOnUiThread(() -> tvStatus.setText("🟢 " + objects.size()
            + " object" + (objects.size() > 1 ? "s" : "") + " detected"));
    }

    // ── Result Display + Haptic Dispatch ─────────────────────────────────────

    /**
     * @param directionKey  "left" | "right" | "center"
     * @param hazardCenterX  x pixel of hazard object center (-1 if no hazard)
     */
    private void showResult(String name, String pos, String conf, String spoken,
                             long cooldown, String directionKey, int hazardCenterX) {
        currentDetection = spoken;
        runOnUiThread(() -> {
            tvObjectName.setText(name);
            tvObjectPos.setText("📍 " + pos);
            tvObjectConf.setText(conf);
            llResult.setVisibility(View.VISIBLE);
        });

        long now = System.currentTimeMillis();
        boolean newObject = !spoken.equals(lastSpokenText) && (now - lastSpeakTime > cooldown);

        if (newObject) {
            lastSpeakTime = now;
            lastSpokenText = spoken;
            speak(spoken);

            // Directional haptic on new object detection (direction changed)
            if (!directionKey.equals(lastHapticDirection)) {
                lastHapticDirection = directionKey;
                triggerDirectionalHaptic(directionKey);
                updateHapticUI(directionKey);
            }
        }

        // Hazard gap haptic: fires every 5 seconds while hazard is in frame
        if (hazardCenterX >= 0) {
            if (now - lastHazardHapticTime > HAZARD_HAPTIC_INTERVAL) {
                lastHazardHapticTime = now;
                triggerHazardHaptic();
                runOnUiThread(() -> tvHapticInfo.setText("⚠️ ROAD HAZARD — vibrating every 5s"));
            }
        } else {
            runOnUiThread(() -> tvHapticInfo.setText(""));
        }
    }

    // ── Haptic Patterns ──────────────────────────────────────────────────────

    /**
     * Directional:
     *   RIGHT  → 1 short pulse
     *   LEFT   → 2 short pulses
     *   BACK   → 3 short pulses  (object behind — not applicable from camera, used for TTS "behind you")
     *   FRONT  → 4 short pulses  (straight ahead / center)
     */
    private void triggerDirectionalHaptic(String direction) {
        switch (direction) {
            case "right":  vibratePattern(1); break;
            case "left":   vibratePattern(2); break;
            case "back":   vibratePattern(3); break;
            case "center":
            default:       vibratePattern(4); break;  // front / center = 4 pulses
        }
    }

    /**
     * Hazard gap: rapid triple-triple SOS-style burst
     */
    private void triggerHazardHaptic() {
        if (vib == null) return;
        // Pattern: 3 short + pause + 3 long + pause + 3 short  (SOS-style warning)
        long[] pattern = {
            0,
            100, 80, 100, 80, 100,   // 3 short
            200,
            250, 100, 250, 100, 250, // 3 long
            200,
            100, 80, 100, 80, 100    // 3 short
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else
            vib.vibrate(pattern, -1);
    }

    /**
     * Fires N distinct pulses (80ms on, 120ms off each)
     */
    private void vibratePattern(int pulses) {
        if (vib == null) return;
        long[] pattern = new long[1 + pulses * 2];
        pattern[0] = 0;
        for (int i = 0; i < pulses; i++) {
            pattern[1 + i * 2]     = 90;   // ON
            pattern[1 + i * 2 + 1] = 130;  // OFF
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else
            vib.vibrate(pattern, -1);
    }

    private void updateHapticUI(String direction) {
        String label;
        switch (direction) {
            case "right":  label = "📳 1 pulse — RIGHT";  break;
            case "left":   label = "📳 2 pulses — LEFT";  break;
            case "back":   label = "📳 3 pulses — BACK";  break;
            default:       label = "📳 4 pulses — FRONT"; break;
        }
        runOnUiThread(() -> tvHapticInfo.setText(label));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getDirectionKey(float cx) {
        if (cx < 0.30f) return "left";
        if (cx > 0.70f) return "right";
        return "center";   // front
    }

    private String getPosition(Rect box, int w, int h) {
        float cx = box.centerX() / (float) w;
        float area = (float)(box.width() * box.height()) / (w * h);
        String side = cx < 0.30f ? "on your left" : cx > 0.70f ? "on your right" : "straight ahead";
        String dist = area > 0.30f ? "very close" : area > 0.10f ? "nearby"
                    : area > 0.03f ? "a few meters away" : "far away";
        return side + ", " + dist;
    }

    private boolean isHazardLabel(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase(Locale.ROOT);
        for (String keyword : HAZARD_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    // ── Always-On Voice in Camera ─────────────────────────────────────────────
    // Blind/deaf users can say "back", "stop", "text mode", "object mode" etc.

    private void startCameraVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        camVoiceActive = true;
        listenForCameraCommand();
    }

    private void listenForCameraCommand() {
        if (!camVoiceActive) return;
        camSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        camSpeechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                if (camVoiceActive) voiceRestartHandler.postDelayed(() -> listenForCameraCommand(), 1500);
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    handleCameraVoiceCommand(matches.get(0).toLowerCase());
                }
                if (camVoiceActive) voiceRestartHandler.postDelayed(() -> listenForCameraCommand(), 800);
            }
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int e, Bundle p) {}
        });
        camSpeechRecognizer.startListening(intent);
    }

    private void handleCameraVoiceCommand(String cmd) {
        if (cmd.contains("back") || cmd.contains("stop") || cmd.contains("close") || cmd.contains("exit")) {
            speak("Closing camera.");
            finish();
        } else if (cmd.contains("text") || cmd.contains("read")) {
            if (!textMode) {
                textMode = true;
                runOnUiThread(() -> btnToggle.setText("🔍 Objects"));
                speak("Text reading mode.");
            }
        } else if (cmd.contains("object") || cmd.contains("scan") || cmd.contains("detect")) {
            if (textMode) {
                textMode = false;
                runOnUiThread(() -> btnToggle.setText("📝 Text"));
                speak("Object detection mode.");
            }
        } else if (cmd.contains("what") || cmd.contains("repeat") || cmd.contains("say again")) {
            speak(currentDetection.isEmpty() ? "Nothing detected yet" : currentDetection);
        }
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    public void speak(String t) {
        if (tts != null && !SplashActivity.MODE_DEAF.equals(mode))
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            speak("Camera scanner ready. I will name everything I see. Vibration guide: 1 pulse right, 2 pulses left, 4 pulses ahead.");
            // Auto-enable voice for blind and deaf modes
            if (SplashActivity.MODE_BLIND.equals(mode) || SplashActivity.MODE_DEAF.equals(mode)) {
                voiceRestartHandler.postDelayed(this::startCameraVoice, 4000);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (camSpeechRecognizer != null) camSpeechRecognizer.stopListening();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (camVoiceActive) voiceRestartHandler.postDelayed(this::listenForCameraCommand, 500);
    }

    @Override
    protected void onDestroy() {
        camVoiceActive = false;
        voiceRestartHandler.removeCallbacksAndMessages(null);
        if (camSpeechRecognizer != null) { camSpeechRecognizer.destroy(); }
        exec.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
