package com.navassist;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.text.SimpleDateFormat;
import java.util.*;
public class GuardianActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle s){
        super.onCreate(s);
        setContentView(R.layout.activity_guardian);
        findViewById(R.id.btn_back3).setOnClickListener(v->finish());
        findViewById(R.id.btn_call2).setOnClickListener(v->Toast.makeText(this,"📞 Calling...",Toast.LENGTH_SHORT).show());
        findViewById(R.id.btn_msg2).setOnClickListener(v->Toast.makeText(this,"💬 Message sent!",Toast.LENGTH_SHORT).show());
        TextView tvLoc=findViewById(R.id.tv_gloc), tvTime=findViewById(R.id.tv_gtime);
        String[]locs={"Anna Nagar, Chennai","Koyambedu, Chennai","Arumbakkam, Chennai"};
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){
            int i=0; public void run(){
                tvLoc.setText("📍 "+locs[i++%locs.length]);
                tvTime.setText("Updated "+new SimpleDateFormat("hh:mm:ss a",Locale.getDefault()).format(new Date()));
                new Handler(Looper.getMainLooper()).postDelayed(this,5000);
            }
        },2000);
    }
}
