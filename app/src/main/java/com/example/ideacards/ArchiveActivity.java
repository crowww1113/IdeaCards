package com.example.ideacards;

import android.os.Bundle;
import android.widget.ImageButton;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.ideacards.data.db.AppDatabase;
import com.example.ideacards.data.dao.NoteDao;
import com.example.ideacards.data.entity.NoteEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 归档页面：以卡片列表形式展示所有笔记（或已归档笔记），
 * 支持返回主界面和一键提醒整理功能。
 */
public class ArchiveActivity extends AppCompatActivity {

    private RecyclerView rvArchiveNotes;
    private ImageButton btnBack;

    private NoteListAdapter adapter;
    private NoteDao noteDao;

    /** 单线程池，与 MainActivity 保持一致的串行数据库操作模式 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_archive);

        // 适配系统栏内边距，避免列表被导航栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化 Room 数据库
        noteDao = AppDatabase.getInstance(this).noteDao();

        // 绑定视图
        rvArchiveNotes = findViewById(R.id.rv_archive_notes);
        btnBack = findViewById(R.id.btn_back);

        // 设置 RecyclerView：纵向列表，从上到下排列
        rvArchiveNotes.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NoteListAdapter(this);
        rvArchiveNotes.setAdapter(adapter);

        // 返回按钮点击：关闭当前页，回到 MainActivity
        btnBack.setOnClickListener(v -> finish());

        // 首次加载：从数据库读取笔记
        loadNotes();
    }

    /**
     * 在子线程从数据库读取全部笔记，回到主线程刷新适配器。
     * 后续如需只展示已归档笔记，可将 getAllNotes() 替换为 getNotesByStatus(1)。
     */
    private void loadNotes() {
        executor.execute(() -> {
            // 当前查询全部笔记；后续实现归档功能后改为 getNotesByStatus(1)
            List<NoteEntity> notes = noteDao.getAllNotes();

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return; // Activity 已销毁，跳过 UI 更新
                }
                adapter.setData(notes);
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
