package com.navassist;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.*;
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

    private PreviewView preview;
    private TextView tvStatus, tvObjectName, tvObjectPos, tvObjectConf;
    private LinearLayout llResult;
    private Button btnToggle;

    private ObjectDetector objDet;
    private ImageLabeler labeler;
    private TextRecognizer txtRec;

    private TextToSpeech tts;
    private ExecutorService exec;
    private Vibrator vib;
    private String mode;
    private boolean textMode = false;
    private long lastSpeakTime = 0;
    private String lastSpokenText = "";
    private String currentDetection = "";

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

    private void initMLKit() {
        objDet = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build());

        labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.60f)
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
                        String display = text.length() > 80 ? text.substring(0, 80) + "..." : text;
                        showResult("📝 " + display, "Text detected", "OCR", text, 4000);
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
                        StringBuilder sb = new StringBuilder();
                        String primaryName = "Object"; int primaryConf = 0; String primaryPos = "";
                        for (int i = 0; i < objects.size(); i++) {
                            DetectedObject obj = objects.get(i);
                            String name = obj.getLabels().isEmpty() ? "Unknown Object" : obj.getLabels().get(0).getText();
                            int conf = obj.getLabels().isEmpty() ? 0 : (int)(obj.getLabels().get(0).getConfidence() * 100);
                            String pos = getPosition(obj.getBoundingBox(), imgW, imgH);
                            if (i == 0) { primaryName = name; primaryConf = conf; primaryPos = pos; }
                            if (i > 0) sb.append("\n");
                            sb.append("• ").append(name).append(" (").append(conf).append("%) — ").append(pos);
                        }
                        String confText = primaryConf + "% confidence" + (objects.size() > 1 ? "  |  +" + (objects.size()-1) + " more" : "");
                        String displayName = primaryName;
                        if (objects.size() > 1) displayName += "\n" + sb.toString().trim();
                        String spoken = primaryName + ", " + primaryPos;
                        showResult(displayName, primaryPos, confText, spoken, 3000);
                        runOnUiThread(() -> tvStatus.setText("🟢 " + objects.size() + " object" + (objects.size()>1?"s":"") + " detected"));
                    } else {
                        labeler.process(image)
                            .addOnSuccessListener(labels -> {
                                if (!labels.isEmpty()) {
                                    ImageLabel top = labels.get(0);
                                    int c = (int)(top.getConfidence() * 100);
                                    showResult(top.getText(), "Scene / Background", c + "% confidence", top.getText(), 3000);
                                    runOnUiThread(() -> tvStatus.setText("🟡 Scene detected"));
                                } else {
                                    runOnUiThread(() -> {
                                        tvObjectName.setText("Scanning...");
                                        tvObjectPos.setText("Move camera slowly");
                                        tvStatus.setText("🟡 Looking...");
                                    });
                                }
                            })
                            .addOnCompleteListener(t -> proxy.close());
                        return;
                    }
                })
                .addOnCompleteListener(t -> proxy.close());
        }
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
            lastSpeakTime = now; lastSpokenText = spoken;
            speak(spoken); vibrate();
        }
    }

    private String getPosition(Rect box, int w, int h) {
        float cx = box.centerX() / (float) w;
        float area = (float)(box.width() * box.height()) / (w * h);
        String side = cx < 0.30f ? "on your left" : cx > 0.70f ? "on your right" : "straight ahead";
        String dist = area > 0.30f ? "very close" : area > 0.10f ? "nearby" : area > 0.03f ? "a few meters away" : "far away";
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
        else vib.vibrate(60);
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            speak("Camera scanner ready. I will automatically name everything I see.");
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
    }

    @Override protected void onDestroy() {
        exec.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
