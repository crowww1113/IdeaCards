package com.xiejinyi.ideacards;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.IOException;

/**
 * OCR 文字识别管理器（单例）。
 * 基于 ML Kit Text Recognition v2（中文模型），从图片 Uri 中提取文字。
 * <p>
 * 使用方式：
 * <pre>
 *   OcrManager.getInstance().extractTextFromUri(context, uri, new OcrManager.OcrCallback() {
 *       public void onSuccess(String text) { ... }
 *       public void onFailure(String error) { ... }
 *   });
 * </pre>
 */
public class OcrManager {

    private static final String TAG = "OcrManager";

    private static volatile OcrManager instance;

    private OcrManager() {}

    public static OcrManager getInstance() {
        if (instance == null) {
            synchronized (OcrManager.class) {
                if (instance == null) {
                    instance = new OcrManager();
                }
            }
        }
        return instance;
    }

    /**
     * OCR 识别结果回调。
     */
    public interface OcrCallback {
        /** 识别成功，text 为拼接后的完整文字 */
        void onSuccess(String text);
        /** 识别失败，error 为错误描述 */
        void onFailure(String error);
    }

    /**
     * 从图片 Uri 中提取文字（中英文混合识别）。
     * 内部异步执行，结果通过 callback 回调到调用线程。
     *
     * @param context  上下文
     * @param imageUri 图片 Uri（来自相册选择器）
     * @param callback 结果回调
     */
    public void extractTextFromUri(Context context, Uri imageUri, OcrCallback callback) {
        InputImage image;
        try {
            image = InputImage.fromFilePath(context, imageUri);
        } catch (IOException e) {
            Log.e(TAG, "图片加载失败", e);
            callback.onFailure("图片加载失败：" + e.getMessage());
            return;
        }

        // 使用中文识别模型（同时支持英文）
        TextRecognizer recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());

        recognizer.process(image)
                .addOnSuccessListener(text -> {
                    String result = extractPlainText(text);
                    if (result.isEmpty()) {
                        callback.onFailure("未识别到任何文字");
                    } else {
                        callback.onSuccess(result);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR 识别失败", e);
                    callback.onFailure("识别失败：" + e.getMessage());
                });
    }

    /**
     * 将 ML Kit Text 对象拼接为纯文本字符串。
     * 按 TextBlock → Line 层级遍历，每行末尾补换行符。
     */
    private String extractPlainText(Text text) {
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                sb.append(line.getText()).append("\n");
            }
        }
        // 去掉末尾多余换行
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString().trim();
    }
}
