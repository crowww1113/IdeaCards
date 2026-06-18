package com.example.ideacards;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
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

    /** 通知渠道 ID，用于创建和管理通知渠道 */
    private static final String CHANNEL_ID = "note_remind";
    /** 通知渠道名称，用户可在系统设置的通知管理页面看到此名称 */
    private static final String CHANNEL_NAME = "笔记提醒";
    /** 权限申请请求码，用于在 onRequestPermissionsResult 中识别回调来源 */
    private static final int REQUEST_CODE_NOTIFICATION = 1001;
    /** 详情页请求码，用于在 onActivityResult 中识别回调来源 */
    private static final int REQUEST_DETAIL = 1002;

    private RecyclerView rvArchiveNotes;
    private ImageButton btnBack;
    private TextView btnRemind;

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
        btnRemind = findViewById(R.id.btn_remind);

        // 设置 RecyclerView：纵向列表，从上到下排列
        rvArchiveNotes.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NoteListAdapter(this);
        rvArchiveNotes.setAdapter(adapter);

        // 列表项点击：跳转到详情页查看/编辑笔记
        adapter.setOnNoteClickListener(noteId -> {
            Intent intent = new Intent(ArchiveActivity.this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_NOTE_ID, noteId);
            startActivityForResult(intent, REQUEST_DETAIL);
        });

        // 返回按钮点击：关闭当前页，回到 MainActivity
        btnBack.setOnClickListener(v -> finish());

        // 提醒按钮点击：先检查权限，再决定是申请还是直接发送通知
        btnRemind.setOnClickListener(v -> onRemindClicked());

        // 首次加载：从数据库读取笔记
        loadNotes();
    }

    /**
     * "提醒我整理"按钮的点击处理。
     * Android 13（API 33）起，通知权限改为运行时权限，需要动态申请。
     * 已授权 → 直接发通知；未授权 → 向系统请求权限。
     */
    private void onRemindClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：检查 POST_NOTIFICATIONS 权限是否已授予
            int state = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS);

            if (state == PackageManager.PERMISSION_GRANTED) {
                // 已有权限，直接发送通知
                sendNotification();
            } else {
                // 未授权，弹出系统权限申请弹窗
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_NOTIFICATION);
            }
        } else {
            // Android 13 以下无需运行时权限，直接发送
            sendNotification();
        }
    }

    /**
     * 系统权限申请弹窗的结果回调。
     * 用户点击"允许"后，立即发送通知，确保按钮承诺被兑现。
     * 用户点击"拒绝"，则静默不做额外处理。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // 仅处理我们发出的通知权限请求，忽略其他请求码
        if (requestCode != REQUEST_CODE_NOTIFICATION) {
            return;
        }

        // grantResults 长度 > 0 且为 PERMISSION_GRANTED 表示用户点了"允许"
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendNotification();
        }
    }

    /**
     * 详情页返回结果回调。
     * 当用户在详情页保存修改或删除笔记后，刷新归档列表以反映最新数据。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DETAIL && resultCode == RESULT_OK && data != null) {
            int action = data.getIntExtra(DetailActivity.RESULT_ACTION, 0);
            if (action == DetailActivity.ACTION_UPDATED) {
                Toast.makeText(this, "笔记已更新", Toast.LENGTH_SHORT).show();
            } else if (action == DetailActivity.ACTION_DELETED) {
                Toast.makeText(this, "笔记已删除", Toast.LENGTH_SHORT).show();
            }
            // 刷新列表，显示最新数据
            loadNotes();
        }
    }

    /**
     * 创建通知渠道（Android 8.0+ 必须），并发送一条"整理提醒"通知。
     * 创建通知渠道是幂等操作——重复创建同 ID 渠道不会产生副作用。
     */
    private void sendNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Android 8.0+ 必须创建 NotificationChannel，否则通知无法展示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT // 重要性默认：会发出声音、显示在状态栏
            );
            channel.setDescription("用于提醒用户整理未归档的灵感笔记");
            manager.createNotificationChannel(channel);
        }

        // 使用 NotificationCompat.Builder 构造兼容性通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)  // 状态栏小图标，暂用系统图标
                .setContentTitle("整理提醒")                       // 通知标题
                .setContentText("你有未归档的灵感等待处理")          // 通知正文
                .setAutoCancel(true);                              // 用户点击通知后自动清除

        // 发送通知，id 固定为 1（后续如需取消通知，通过 manager.cancel(1) 操作）
        manager.notify(1, builder.build());
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
