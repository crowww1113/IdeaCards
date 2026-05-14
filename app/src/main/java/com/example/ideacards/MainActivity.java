package com.example.ideacards;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

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
 * 主界面：管理笔记列表的展示、新增与数据库交互。
 */
public class MainActivity extends AppCompatActivity {

    private RecyclerView rvNotes;
    private EditText etInput;
    private Button btnSend;

    private BubbleAdapter adapter;
    private NoteDao noteDao;

    /** 单线程池，保证数据库操作串行执行，避免并发冲突 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // 适配系统栏内边距，避免输入区被导航栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化 Room 数据库，获取 DAO
        noteDao = AppDatabase.getInstance(this).noteDao();

        // 绑定视图
        rvNotes = findViewById(R.id.rv_notes);
        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);

        // 设置 RecyclerView：纵向列表，新条目从底部堆叠（聊天气泡风格）
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvNotes.setLayoutManager(layoutManager);

        adapter = new BubbleAdapter(this);
        rvNotes.setAdapter(adapter);

        // 发送按钮点击：校验输入 -> 子线程写入数据库 -> 刷新列表
        btnSend.setOnClickListener(v -> onSendClicked());

        // 首次加载：从数据库读取历史笔记
        loadNotes();
    }

    /**
     * 发送按钮点击处理。
     * 校验输入内容非空后，在子线程中将笔记写入数据库，
     * 回到主线程清空输入框并刷新列表。
     */
    private void onSendClicked() {
        String content = etInput.getText().toString().trim();

        // 空内容拦截，给出提示
        if (TextUtils.isEmpty(content)) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show();
            return;
        }

        // 防快速连点：发送期间禁用按钮
        btnSend.setEnabled(false);

        // 构造笔记实体，status 默认 0（普通笔记）
        NoteEntity note = new NoteEntity(content, System.currentTimeMillis(), 0);

        executor.execute(() -> {
            // 子线程写入数据库（Room 不允许在主线程操作数据库）
            noteDao.insert(note);

            // 回到主线程更新 UI，先检查 Activity 是否还存活
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return; // Activity 已销毁，避免操作已释放的视图
                }
                etInput.setText("");
                btnSend.setEnabled(true);
                loadNotes();
            });
        });
    }

    /**
     * 在子线程从数据库读取全部笔记，回到主线程刷新适配器。
     */
    private void loadNotes() {
        executor.execute(() -> {
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
