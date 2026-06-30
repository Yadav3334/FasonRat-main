package com.fason.app.features.screen;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import com.fason.app.R;

public class ConnectionRequestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_connection_request);

        findViewById(R.id.btn_accept).setOnClickListener(v -> {
            Intent captureIntent = new Intent(this, ScreenCaptureActivity.class);
            captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(captureIntent);
            finish();
        });

        findViewById(R.id.btn_decline).setOnClickListener(v -> {
            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc != null) svc.stopCapture();
            finish();
        });

        View outside = findViewById(android.R.id.content);
        outside.setOnClickListener(v -> { /* ignore taps outside dialog */ });
    }

    @Override
    public void onBackPressed() {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc != null) svc.stopCapture();
        super.onBackPressed();
    }
}
