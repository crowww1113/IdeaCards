package com.xiejinyi.ideacards;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.xiejinyi.ideacards.data.entity.NoteEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极速输入浮窗：从控制中心磁贴一键呼出，快速记录灵感。
 * <p>
 * 以悬浮 Activity 形式呈现（Theme.QuickInput），保存时执行双轨写入：
 * ① Room 数据库 ② Obsidian 本地库（若已绑定）。
 */
public class QuickInputActivity extends AppCompatActivity {

    private static final String TAG = "QuickInputActivity";

    /** 标签提取正则：匹配首个 #tag */
    private static final Pattern TAG_PATTERN = Pattern.compile("#([^\\s#]+)");

    private EditText etInput;
    private View btnSave;
    private View btnCancel;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_input);

        // 设置窗口：居中悬浮卡片样式，宽高自适应屏幕
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            // 宽度为屏幕的 85%，高度自适应内容
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
        }

        etInput = findViewById(R.id.et_quick_input);
        btnSave = findViewById(R.id.btn_save);
        btnCancel = findViewById(R.id.btn_cancel);

        btnSave.setOnClickListener(v -> onSaveClicked());
        btnCancel.setOnClickListener(v -> finish());

        // 点击卡片外部区域关闭浮窗（卡片自身消费点击，阻止冒泡到根布局）
        findViewById(R.id.root_overlay).setOnClickListener(v -> finish());
        findViewById(R.id.card_input).setOnClickListener(v -> { /* 消费点击，不关闭 */ });

        // 自动弹出软键盘
        etInput.requestFocus();
        etInput.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }

    /**
     * 保存逻辑：通过统一仓库双轨写入（入库 + Obsidian 同步）。
     */
    private void onSaveClicked() {
        String raw = etInput.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSave.setEnabled(false);

        // 提取首个 #tag
        String extractedTag = null;
        String content = raw;
        Matcher matcher = TAG_PATTERN.matcher(raw);
        if (matcher.find()) {
            extractedTag = matcher.group(1);
            content = raw.replaceFirst("#" + Matcher.quoteReplacement(extractedTag), "").trim();
        }

        if (content.isEmpty()) {
            Toast.makeText(this, "请输入正文内容", Toast.LENGTH_SHORT).show();
            btnSave.setEnabled(true);
            return;
        }

        // 构造笔记实体
        NoteEntity note = new NoteEntity(content, System.currentTimeMillis(), 0);
        note.setTag(extractedTag);

        // 通过统一仓库保存（入库 + Obsidian 同步）
        executor.execute(() -> {
            NoteRepository.getInstance(QuickInputActivity.this).saveNote(note);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
