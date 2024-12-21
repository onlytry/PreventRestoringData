package com.example.preventrestoringdata;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private Button startEraseButton;
    private Button stopButton;
    private Button pauseResumeButton;
    private Button cleanupButton;
    private ProgressBar eraseProgress;
    private TextView statusText;
    private DataEraser dataEraser;
    private static final int REQUEST_CODE = 1001;
    private TextView fileListText;
    private StringBuilder fileListBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        dataEraser = new DataEraser(this);
    }

    private void initViews() {
        startEraseButton = findViewById(R.id.startEraseButton);
        stopButton = findViewById(R.id.stopButton);
        pauseResumeButton = findViewById(R.id.pauseResumeButton);
        cleanupButton = findViewById(R.id.cleanupButton);
        eraseProgress = findViewById(R.id.eraseProgress);
        statusText = findViewById(R.id.statusText);
        fileListText = findViewById(R.id.fileListText);

        startEraseButton.setOnClickListener(v -> startEraseProcess());
        stopButton.setOnClickListener(v -> stopEraseProcess());
        pauseResumeButton.setOnClickListener(v -> togglePauseResume());
        cleanupButton.setOnClickListener(v -> cleanupFiles());
        
        // 初始状态
        stopButton.setEnabled(false);
        pauseResumeButton.setEnabled(false);
        cleanupButton.setEnabled(true);
    }

    private void togglePauseResume() {
        if (dataEraser.isPaused()) {
            // 当前是暂停状态，继续执行
            dataEraser.resumeErase();
            pauseResumeButton.setText("暂停擦除");
            statusText.setText("继续擦除...");
        } else {
            // 当前是执行状态，暂停执行
            dataEraser.pauseErase();
            pauseResumeButton.setText("继续擦除");
            statusText.setText("已暂停");
        }
    }

    private void startEraseProcess() {
        if (!checkStoragePermission()) {
            requestStoragePermission();
            return;
        }

        startEraseButton.setEnabled(false);
        stopButton.setEnabled(true);
        pauseResumeButton.setEnabled(true);
        pauseResumeButton.setText("暂停擦除");
        cleanupButton.setEnabled(false);
        
        fileListBuilder.setLength(0);  // 清空文件列表
        fileListText.setText("");
        
        dataEraser.startErase(new EraseCallback() {
            @Override
            public void onProgress(int progress, String status) {
                runOnUiThread(() -> {
                    eraseProgress.setProgress(progress);
                    statusText.setText(status);
                });
            }

            @Override
            public void onFileCreated(String fileName, long size) {
                runOnUiThread(() -> {
                    fileListBuilder.insert(0, String.format("%s (%.2f MB)\n", 
                        fileName, size / (1024.0 * 1024)));
                    fileListText.setText(fileListBuilder.toString());
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    statusText.setText("操作完成");
                    startEraseButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    pauseResumeButton.setEnabled(false);
                    cleanupButton.setEnabled(true);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    statusText.setText("错误: " + error);
                    startEraseButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    pauseResumeButton.setEnabled(false);
                    cleanupButton.setEnabled(true);
                });
            }
        });
    }

    private void stopEraseProcess() {
        dataEraser.stopAndKeepFiles();
        stopButton.setEnabled(false);
        pauseResumeButton.setEnabled(false);
        cleanupButton.setEnabled(true);
        startEraseButton.setEnabled(true);
        statusText.setText("已停止擦除");
    }

    private void cleanupFiles() {
        cleanupButton.setEnabled(false);
        fileListBuilder.setLength(0);
        fileListText.setText("");
        dataEraser.cleanupAllFiles(new EraseCallback() {
            @Override
            public void onProgress(int progress, String status) {
                runOnUiThread(() -> {
                    eraseProgress.setProgress(progress);
                    statusText.setText(status);
                });
            }

            @Override
            public void onFileCreated(String fileName, long size) {
                // 清理文件时不需要处理文件创建事件
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    statusText.setText("清理完成");
                    cleanupButton.setEnabled(true);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    statusText.setText("错误: " + error);
                    cleanupButton.setEnabled(true);
                });
            }
        });
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 及以上版本
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 到 Android 10
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 及以上版本需要请求所有文件访问权限
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.addCategory("android.intent.category.DEFAULT");
                intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                startActivityForResult(intent, REQUEST_CODE);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 到 Android 10
            requestPermissions(
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_CODE
            );
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    startEraseProcess();
                } else {
                    statusText.setText("需要存储权限才能继续操作");
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startEraseProcess();
            } else {
                statusText.setText("需要存储权限才能继续操作");
            }
        }
    }
}