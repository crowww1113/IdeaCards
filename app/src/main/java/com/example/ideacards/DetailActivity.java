package com.example.ideacards;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.ideacards.data.dao.NoteDao;
import com.example.ideacards.data.db.AppDatabase;
import com.example.ideacards.data.entity.NoteEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 笔记详情页：接收笔记 ID，从数据库加载内容并展示在 EditText 中。
 * 支持保存修改（Update）和删除笔记（Delete）两种操作。
 */
public class DetailActivity extends AppCompatActivity {

    /** Intent 传参 key：笔记 ID */
    public static final String EXTRA_NOTE_ID = "note_id";

    /** 结果标记：笔记已被更新 */
    public static final String RESULT_ACTION = "result_action";
    public static final int ACTION_UPDATED = 1;
    public static final int ACTION_DELETED = 2;

    private EditText etContent;
    private Button btnSave;
    private Button btnDelete;
    private ImageButton btnBack;

    private NoteDao noteDao;
    private NoteEntity currentNote;

    /** 单线程池，与项目其他 Activity 保持一致的串行数据库操作模式 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_detail);

        // 适配系统栏内边距
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化 Room 数据库
        noteDao = AppDatabase.getInstance(this).noteDao();

        // 绑定视图
        etContent = findViewById(R.id.et_detail_content);
        btnSave = findViewById(R.id.btn_detail_save);
        btnDelete = findViewById(R.id.btn_detail_delete);
        btnBack = findViewById(R.id.btn_detail_back);

        // 返回按钮：不保存直接关闭
        btnBack.setOnClickListener(v -> finish());

        // 保存按钮：校验内容后更新数据库
        btnSave.setOnClickListener(v -> onSaveClicked());

        // 删除按钮：弹出二次确认对话框
        btnDelete.setOnClickListener(v -> onDeleteClicked());

        // 从 Intent 获取笔记 ID 并加载数据
        long noteId = getIntent().getLongExtra(EXTRA_NOTE_ID, -1);
        if (noteId == -1) {
            // 无效 ID，给出提示并关闭页面
            Toast.makeText(this, "无效的笔记", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loadNote(noteId);
    }

    /**
     * 保存按钮点击处理。
     * 校验内容非空后，在子线程更新数据库，回到主线程通知结果并关闭页面。
     */
    private void onSaveClicked() {
        String content = etContent.getText().toString().trim();

        // 空内容拦截
        if (TextUtils.isEmpty(content)) {
            Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentNote == null) {
            return; // 数据尚未加载完成，忽略点击
        }

        // 更新笔记内容和时间戳
        currentNote.setContent(content);
        currentNote.setTimestamp(System.currentTimeMillis());

        // 禁用按钮防止重复点击
        btnSave.setEnabled(false);

        executor.execute(() -> {
            noteDao.update(currentNote);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();

                // 设置结果：告知列表页数据已变更，需要刷新
                Intent resultIntent = new Intent();
                resultIntent.putExtra(RESULT_ACTION, ACTION_UPDATED);
                setResult(RESULT_OK, resultIntent);
                finish();
            });
        });
    }

    /**
     * 删除按钮点击处理。
     * 弹出 AlertDialog 二次确认，确认后在子线程删除笔记，关闭页面。
     */
    private void onDeleteClicked() {
        if (currentNote == null) {
            return; // 数据尚未加载完成，忽略点击
        }

        // 弹出确认对话框，防止误删
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除这条笔记吗？此操作不可撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    executor.execute(() -> {
                        noteDao.delete(currentNote);

                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();

                            // 设置结果：告知列表页数据已变更，需要刷新
                            Intent resultIntent = new Intent();
                            resultIntent.putExtra(RESULT_ACTION, ACTION_DELETED);
                            setResult(RESULT_OK, resultIntent);
                            finish();
                        });
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 在子线程从数据库加载笔记详情，回到主线程填充 EditText。
     *
     * @param noteId 笔记 ID
     */
    private void loadNote(long noteId) {
        executor.execute(() -> {
            NoteEntity note = noteDao.getNoteById(noteId);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (note == null) {
                    Toast.makeText(this, "笔记不存在", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                currentNote = note;
                etContent.setText(note.getContent());
                // 光标移到末尾，方便用户直接编辑
                etContent.setSelection(etContent.getText().length());
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Activity 销毁时关闭线程池，避免资源泄漏
        executor.shutdownNow();
    }
}
