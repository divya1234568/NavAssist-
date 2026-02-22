package com.navassist;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.*;
import android.speech.tts.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.*;
import com.google.mlkit.vision.objects.*;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.*;
import java.util.concurrent.*;
public class CameraActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    PreviewView preview; TextView tvRes; TextToSpeech tts;
    ExecutorService exec; boolean textMode=false;
    long lastSpeak=0; String lastResult=""; Vibrator vib; String mode;
    ObjectDetector objDet; ImageLabeler labeler; TextRecognizer txtRec;
    @Override protected void onCreate(Bundle s){
        super.onCreate(s);
        setContentView(R.layout.activity_camera);
        mode=getIntent().getStringExtra(SplashActivity.EXTRA_MODE);
        vib=(Vibrator)getSystemService(VIBRATOR_SERVICE);
        tts=new TextToSpeech(this,this);
        exec=Executors.newSingleThreadExecutor();
        preview=findViewById(R.id.cam_preview);
        tvRes=findViewById(R.id.tv_res);
        findViewById(R.id.btn_back2).setOnClickListener(v->finish());
        findViewById(R.id.btn_speak2).setOnClickListener(v->speak(tvRes.getText().toString()));
        findViewById(R.id.btn_toggle2).setOnClickListener(v->{
            textMode=!textMode;
            ((Button)findViewById(R.id.btn_toggle2)).setText(textMode?"🔍 Objects":"📝 Text");
            speak(textMode?"Text mode. Point at signs.":"Object detection mode.");
        });
        initML();
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCam();
        else ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA},10);
    }
    void initML(){
        objDet=ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE).enableMultipleObjects().enableClassification().build());
        labeler=ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build());
        txtRec=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }
    void startCam(){
        ProcessCameraProvider.getInstance(this).addListener(()->{
            try{
                ProcessCameraProvider p=ProcessCameraProvider.getInstance(this).get();
                Preview prev=new Preview.Builder().build();
                prev.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis ia=new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                ia.setAnalyzer(exec,this::analyze);
                p.unbindAll();
                p.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,prev,ia);
            }catch(Exception e){e.printStackTrace();}
        },ContextCompat.getMainExecutor(this));
    }
    @androidx.camera.core.ExperimentalGetImage
    void analyze(ImageProxy proxy){
        if(proxy.getImage()==null){proxy.close();return;}
        InputImage img=InputImage.fromMediaImage(proxy.getImage(),proxy.getImageInfo().getRotationDegrees());
        if(textMode){
            txtRec.process(img).addOnSuccessListener(r->{
                String t=r.getText().trim();
                if(!t.isEmpty())update("📝 TEXT:\n"+t,t,4000);
            }).addOnCompleteListener(x->proxy.close());
        }else{
            objDet.process(img).addOnSuccessListener(objs->{
                List<String> lines=new ArrayList<>();
                for(DetectedObject o:objs){
                    String lbl=o.getLabels().isEmpty()?"Object":o.getLabels().get(0).getText();
                    int conf=o.getLabels().isEmpty()?0:(int)(o.getLabels().get(0).getConfidence()*100);
                    String pos=pos(o.getBoundingBox(),proxy.getWidth(),proxy.getHeight());
                    lines.add("• "+lbl+" ("+conf+"%) — "+pos);
                }
                if(!lines.isEmpty())update(String.join("\n",lines),lines.get(0).replaceAll("[•(\\d+%)]","").trim(),3000);
            }).addOnCompleteListener(x->proxy.close());
        }
    }
    String pos(Rect b,int w,int h){
        float cx=b.centerX()/(float)w, a=(float)(b.width()*b.height())/(w*h);
        return(cx<0.35f?"on your left":cx>0.65f?"on your right":"straight ahead")+", "+(a>0.25f?"very close":a>0.08f?"nearby":"far away");
    }
    void update(String display,String spoken,long cool){
        runOnUiThread(()->tvRes.setText(display));
        long now=System.currentTimeMillis();
        if(now-lastSpeak>cool&&!spoken.equals(lastResult)){lastSpeak=now;lastResult=spoken;speak(spoken);buzz();}
    }
    void buzz(){if(vib==null)return;if(Build.VERSION.SDK_INT>=26)vib.vibrate(VibrationEffect.createOneShot(60,VibrationEffect.DEFAULT_AMPLITUDE));else vib.vibrate(60);}
    public void speak(String t){if(tts!=null&&!SplashActivity.MODE_DEAF.equals(mode))tts.speak(t,TextToSpeech.QUEUE_FLUSH,null,null);}
    @Override public void onInit(int s){if(s==TextToSpeech.SUCCESS){tts.setLanguage(Locale.US);speak("Camera ready. Identifying objects automatically.");}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCam();}
    @Override protected void onDestroy(){exec.shutdown();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
