package com.example.preventrestoringdata;

public interface EraseCallback {
    void onProgress(int progress, String status);
    void onFileCreated(String fileName, long size);
    void onComplete();
    void onError(String error);
} 