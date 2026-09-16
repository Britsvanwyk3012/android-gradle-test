package com.example.gradletest;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public class MainActivity extends Activity { @Override public void onCreate(Bundle b) { super.onCreate(b); TextView t=new TextView(this); t.setText("GitHub Gradle Test OK"); t.setTextSize(24); setContentView(t); } }
