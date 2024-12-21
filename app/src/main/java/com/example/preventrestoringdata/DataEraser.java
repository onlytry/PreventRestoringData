package com.example.preventrestoringdata;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.Arrays;

import com.example.preventrestoringdata.EraseCallback;

public class DataEraser {
    private final Context context;
    private final File storageDir;
    private static final int CHUNK_SIZE = 4 * 1024 * 1024; // 4MB chunks
    private boolean isErasing = false;
    private volatile boolean isCancelled = false;
    private final SecureRandom secureRandom;
    private static final String WIPE_FOLDER = "DataWiper";  // 文件夹名称
    private boolean shouldCleanup = true; // 控制是否在停止时清理文件
    private volatile boolean isPaused = false;
    private long lastTotalWritten = 0;  // 记录已写入的总量
    private int lastFileCount = 0;      // 记录已创建的文件数

    public DataEraser(Context context) {
        this.context = context;
        // 在根目录下创建专门的文件夹
        File baseDir = Environment.getExternalStorageDirectory();
        this.storageDir = new File(baseDir, WIPE_FOLDER);
        this.secureRandom = new SecureRandom();
    }

    public void startErase(EraseCallback callback) {
        if (isErasing) {
            callback.onError("擦除进程已在运行");
            return;
        }

        new Thread(() -> {
            isErasing = true;
            try {
                if (!storageDir.exists() && !storageDir.mkdirs()) {
                    throw new IOException("无法创建文件夹: " + storageDir.getPath());
                }

                long availableSpace = Environment.getExternalStorageDirectory().getFreeSpace();
                long totalWritten = lastTotalWritten;  // 从上次的位置继续
                int fileCount = lastFileCount;         // 从上次的文件计数继续

                while (totalWritten < availableSpace && !isCancelled) {
                    while (isPaused && !isCancelled) {
                        Thread.sleep(1000); // 暂停时等待
                        continue;
                    }
                    if (isCancelled) break;

                    File file = new File(storageDir, "wipe_" + fileCount + ".tmp");
                    long fileSize = Math.min(100 * 1024 * 1024L, availableSpace - totalWritten);
                    
                    writeMixedPatterns(file, fileSize, totalWritten, availableSpace, callback);
                    
                    if (isCancelled) break;
                    
                    totalWritten += fileSize;
                    fileCount++;
                    
                    // 更新记录点
                    lastTotalWritten = totalWritten;
                    lastFileCount = fileCount;
                }

                if (isCancelled) {
                    if (shouldCleanup) {
                        callback.onProgress(0, "正在清理文件...");
                        cleanupFiles(callback);
                        // 重置记录点
                        lastTotalWritten = 0;
                        lastFileCount = 0;
                    }
                    callback.onComplete();
                } else {
                    cleanupFiles(callback);
                    // 重置记录点
                    lastTotalWritten = 0;
                    lastFileCount = 0;
                    callback.onComplete();
                }
            } catch (Exception e) {
                callback.onError(e.getMessage());
            } finally {
                isErasing = false;
                isCancelled = false;
                isPaused = false;
                shouldCleanup = true;
            }
        }).start();
    }

    private void writeMixedPatterns(File file, long fileSize, long totalWritten, long availableSpace, 
                                  EraseCallback callback) throws IOException {
        // 通知新文件创建
        callback.onFileCreated(file.getName(), fileSize);
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            long bytesWritten = 0;

            while (bytesWritten < fileSize && !isCancelled) {
                // 随机选择填充模式：0、1或随机数
                int pattern = secureRandom.nextInt(3);
                
                switch (pattern) {
                    case 0: // 填充0
                        Arrays.fill(buffer, (byte) 0x00);
                        break;
                    case 1: // 填充1
                        Arrays.fill(buffer, (byte) 0xFF);
                        break;
                    case 2: // 填充随机数
                        secureRandom.nextBytes(buffer);
                        break;
                }

                // 计算这次要写入的字节数
                int writeLength = (int) Math.min(CHUNK_SIZE, fileSize - bytesWritten);
                fos.write(buffer, 0, writeLength);
                bytesWritten += writeLength;

                // 更新总进度
                long currentTotal = totalWritten + bytesWritten;
                int progress = (int) ((currentTotal * 100) / availableSpace);
                
                // 使用传统的switch语句
                String patternName;
                switch (pattern) {
                    case 0:
                        patternName = "0";
                        break;
                    case 1:
                        patternName = "1";
                        break;
                    default:
                        patternName = "随机数";
                        break;
                }
                
                callback.onProgress(progress, String.format("写入%s - 已写入: %.2f GB", 
                    patternName, currentTotal / (1024.0 * 1024 * 1024)));
            }

            // 强制写入到存储设备
            fos.getFD().sync();
        }
    }

    private void cleanupFiles(EraseCallback callback) {
        File[] files = storageDir.listFiles((dir, name) -> name.startsWith("wipe_") && name.endsWith(".tmp"));
        if (files != null) {
            int total = files.length;
            int count = 0;
            for (File file : files) {
                file.delete();
                count++;
                if (count % 10 == 0) {
                    int progress = (count * 100) / total;
                    callback.onProgress(progress, String.format("正在清理文件 %d/%d", count, total));
                }
            }
        }
    }

    public void cancelErase() {
        isCancelled = true;
    }

    // 停止填充并保留文件
    public void stopAndKeepFiles() {
        shouldCleanup = false;
        isCancelled = true;
    }

    // 停止填充并删除文件
    public void stopAndCleanFiles() {
        shouldCleanup = true;
        isCancelled = true;
    }

    // 删除所有已填充的文件和文件夹
    public void cleanupAllFiles(EraseCallback callback) {
        new Thread(() -> {
            try {
                if (storageDir.exists()) {
                    callback.onProgress(0, "开始清理文件...");
                    File[] files = storageDir.listFiles((dir, name) -> name.startsWith("wipe_") && name.endsWith(".tmp"));
                    if (files != null) {
                        int total = files.length;
                        int count = 0;
                        for (File file : files) {
                            if (file.delete()) {
                                count++;
                                if (count % 10 == 0 || count == total) {
                                    int progress = (count * 100) / total;
                                    callback.onProgress(progress, 
                                        String.format("正在清理文件 %d/%d", count, total));
                                }
                            }
                        }
                    }
                    
                    // 删除文件夹
                    if (storageDir.delete()) {
                        callback.onProgress(100, "清理完成");
                    } else {
                        callback.onError("无法删除文件夹");
                    }
                } else {
                    callback.onProgress(100, "没有需要清理的文件");
                }
            } catch (Exception e) {
                callback.onError("清理文件时出错: " + e.getMessage());
            }
        }).start();
    }

    // 暂停擦除
    public void pauseErase() {
        isPaused = true;
    }

    // 继续擦除
    public void resumeErase() {
        isPaused = false;
    }

    // 检查是否暂停
    public boolean isPaused() {
        return isPaused;
    }

    // 检查是否正在运行（包括暂停状态）
    public boolean isRunning() {
        return isErasing;
    }
} 