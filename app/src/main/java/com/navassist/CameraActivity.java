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

/**
 * CameraActivity — Object detection + Text scanner
 *
 * WHY "Unknown Object" was happening and how we fixed it:
 * ────────────────────────────────────────────────────────
 * ML Kit ObjectDetector (object-detection:17.0.0) only classifies objects into
 * 5 broad categories: Fashion, Food, Home goods, Place, Plant.
 * Everything else returns "Unknown" or "Physical object". This is by design —
 * ObjectDetector's job is bounding boxes, not naming.
 *
 * FIX — Two-Pass Detection every frame:
 *   Pass 1 → ObjectDetector  : get bounding box → screen POSITION (left/right/center)
 *   Pass 2 → ImageLabeler    : get real NAME from 400+ class vocabulary
 *
 * Result: "Chair — on your left, nearby"  instead of  "Unknown Object"
 */
public class CameraActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private PreviewView preview;
    private TextView tvStatus, tvObjectName, tvObjectPos, tvObjectConf, tvAllLabels;
    private LinearLayout llResult;
    private Button btnToggle;

    // ML Kit
    private ObjectDetector objDet;   // bounding boxes only
    private ImageLabeler labeler;    // REAL object names — 400+ classes
    private TextRecognizer txtRec;

    private TextToSpeech tts;
    private ExecutorService exec;
    private Vibrator vib;
    private String mode;
    private boolean textMode = false;
    private long lastSpeakTime = 0;
    private String lastSpokenText = "";
    private String currentDetection = "";

    // Position from ObjectDetector to pair with labeler result
    private volatile String pendingPosition = "straight ahead";

    // Always-on voice inside camera
    private SpeechRecognizer camVoice;
    private boolean camVoiceOn = false;
    private Handler handler = new Handler(Looper.getMainLooper());

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
        tvAllLabels  = findViewById(R.id.tv_all_labels);
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
            if (tvAllLabels != null) tvAllLabels.setText("");
            speak(textMode ? "Text reading mode." : "Object detection mode.");
        });

        initMLKit();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
    }

    private void initMLKit() {
        // ObjectDetector — we use ONLY its bounding box output, ignore the label
        objDet = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build());

        // ImageLabeler — PRIMARY name source. Knows 400+ object types.
        // Threshold 0.45 catches most objects without too many false positives.
        labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.45f)
            .build());

        txtRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

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
                        String display = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                        showResult("📝 " + display, "Text detected", "OCR", text, 4000);
                    } else {
                        runOnUiThread(() -> {
                            tvObjectName.setText("No text found");
                            tvObjectPos.setText("Point at a sign or label");
                        });
                    }
                })
                .addOnCompleteListener(t -> proxy.close());
            return;
        }

        // ── OBJECT MODE: Two-pass detection ───────────────────────────────────
        // Pass 1: ObjectDetector → bounding box → position string
        // Pass 2: ImageLabeler  → real object name from 400+ classes
        int imgW = proxy.getWidth(), imgH = proxy.getHeight();

        objDet.process(image)
            .addOnSuccessListener(objects -> {
                // Extract position from the primary (largest/first) bounding box
                if (!objects.isEmpty()) {
                    Rect box = objects.get(0).getBoundingBox();
                    pendingPosition = getPosition(box, imgW, imgH);
                } else {
                    pendingPosition = "straight ahead";
                }
            })
            .addOnCompleteListener(objTask -> {
                // Pass 2: ImageLabeler — always runs, gives us the real name
                labeler.process(image)
                    .addOnSuccessListener(labels -> {
                        if (labels == null || labels.isEmpty()) {
                            runOnUiThread(() -> {
                                tvObjectName.setText("Scanning...");
                                tvObjectPos.setText("Move camera slowly");
                                tvStatus.setText("🟡 Looking...");
                                if (tvAllLabels != null) tvAllLabels.setText("");
                            });
                            return;
                        }

                        // Sort labels by confidence — best first
                        labels.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));

                        ImageLabel best = labels.get(0);
                        String primaryName = best.getText();
                        int primaryConf = (int)(best.getConfidence() * 100);
                        String pos = pendingPosition;

                        // Build secondary labels line (up to 4 more)
                        StringBuilder sb = new StringBuilder();
                        int extra = Math.min(labels.size(), 5);
                        for (int i = 1; i < extra; i++) {
                            if (sb.length() > 0) sb.append("  •  ");
                            sb.append(labels.get(i).getText())
                              .append(" ")
                              .append((int)(labels.get(i).getConfidence() * 100))
                              .append("%");
                        }

                        runOnUiThread(() -> {
                            if (tvAllLabels != null) tvAllLabels.setText(sb.toString());
                            tvStatus.setText("🟢 " + labels.size() + " object" + (labels.size() > 1 ? "s" : "") + " identified");
                        });

                        showResult(primaryName, pos, primaryConf + "% confidence",
                                   primaryName + ", " + pos, 2500);
                    })
                    .addOnCompleteListener(t -> proxy.close());
            });
    }

    private void showResult(String name, String pos, String conf, String spoken, long cooldown) {
        currentDetection = spoken;
        runOnUiThread(() -> {
            tvObjectName.setText(name);
            tvObjectPos.setText("📍 " + pos);
            tvObjectConf.setText(conf);
            llResult.setVisibility(View.VISIBLE);
        });
        long now = System.currentTimeMillis();
        if (now - lastSpeakTime > cooldown && !spoken.equals(lastSpokenText)) {
            lastSpeakTime = now;
            lastSpokenText = spoken;
            speak(spoken);
            vibrate();
        }
    }

    private String getPosition(Rect box, int w, int h) {
        float cx = box.centerX() / (float) w;
        float area = (float)(box.width() * box.height()) / (w * h);
        String side = cx < 0.30f ? "on your left"
                    : cx > 0.70f ? "on your right"
                    : "straight ahead";
        String dist = area > 0.30f ? "very close"
                    : area > 0.10f ? "nearby"
                    : area > 0.03f ? "a few meters away"
                    : "far away";
        return side + ", " + dist;
    }

    public void speak(String t) {
        if (tts != null && !SplashActivity.MODE_DEAF.equals(mode))
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void vibrate() {
        if (vib == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vib.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        else
            vib.vibrate(60);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            speak("Camera scanner ready. I will identify everything I see.");
            if (SplashActivity.MODE_BLIND.equals(mode)) {
                handler.postDelayed(this::startCamVoice, 3000);
            }
        }
    }

    private void startCamVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        camVoiceOn = true;
        loopCamVoice();
    }

    private void loopCamVoice() {
        if (!camVoiceOn) return;
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
                if (camVoiceOn) handler.postDelayed(() -> loopCamVoice(), 1200);
            }
            @Override public void onResults(Bundle r) {
                ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (m != null && !m.isEmpty()) handleCamCmd(m.get(0).toLowerCase());
                if (camVoiceOn) handler.postDelayed(() -> loopCamVoice(), 700);
            }
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int e, Bundle p) {}
        });
        camVoice.startListening(intent);
    }

    private void handleCamCmd(String cmd) {
        if (cmd.contains("back") || cmd.contains("stop") || cmd.contains("close")) {
            speak("Closing camera.");
            finish();
        } else if (cmd.contains("text") || cmd.contains("read")) {
            if (!textMode) { textMode = true; speak("Text mode."); }
        } else if (cmd.contains("object") || cmd.contains("scan")) {
            if (textMode) { textMode = false; speak("Object mode."); }
        } else if (cmd.contains("what") || cmd.contains("repeat")) {
            speak(currentDetection.isEmpty() ? "Nothing detected yet." : currentDetection);
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
        if (camVoiceOn) handler.postDelayed(() -> loopCamVoice(), 500);
    }

    @Override protected void onDestroy() {
        camVoiceOn = false;
        handler.removeCallbacksAndMessages(null);
        if (camVoice != null) camVoice.destroy();
        exec.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
