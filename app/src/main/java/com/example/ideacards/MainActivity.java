package com.example.ideacards;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

/**
 * 主界面：管理笔记列表的展示、新增与数据库交互。
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvQuote;
    private RecyclerView rvNotes;
    private EditText etInput;
    private TextView btnSend;
    private TextView btnArchive;

    private BubbleAdapter adapter;
    private NoteDao noteDao;

    /** 标签容器，用于动态创建标签气泡 */
    private LinearLayout llTags;
    /** 当前选中的标签，null 表示未选中 */
    private String selectedTag = null;

    /** 默认标签列表 */
    private static final String[] DEFAULT_TAGS = {"工作", "生活", "学习"};

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
        tvQuote = findViewById(R.id.tv_quote);
        rvNotes = findViewById(R.id.rv_notes);
        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        btnArchive = findViewById(R.id.btn_archive);
        llTags = findViewById(R.id.ll_tags);

        // 初始化标签气泡
        setupTagBubbles();

        // 设置 RecyclerView：纵向列表，新条目从底部堆叠（聊天气泡风格）
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvNotes.setLayoutManager(layoutManager);

        adapter = new BubbleAdapter(this);
        rvNotes.setAdapter(adapter);

        // 长按卡片：弹出 PopupMenu（编辑卡片 / 彻底删除）
        adapter.setOnNoteLongClickListener((noteId, anchorView) ->
                showNotePopup(noteId, anchorView));

        // 发送按钮点击：校验输入 -> 子线程写入数据库 -> 刷新列表
        btnSend.setOnClickListener(v -> onSendClicked());

        // 归档按钮点击：跳转到归档列表页
        btnArchive.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, ArchiveActivity.class));
        });

        // 首次加载：从数据库读取历史笔记
        loadNotes();

        // 请求网络获取每日一言，展示在顶部 TextView
        fetchDailyQuote();
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

        // 构造笔记实体，status 默认 0（普通笔记），绑定当前选中的标签
        NoteEntity note = new NoteEntity(content, System.currentTimeMillis(), 0);
        note.setTag(selectedTag);

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
                    return;
                }
                adapter.setData(notes);
            });
        });
    }

    /**
     * 动态创建标签气泡，放入 llTags 容器中。
     * 点击气泡切换选中状态：选中时高亮（治愈蓝背景），再次点击取消选中。
     */
    private void setupTagBubbles() {
        for (String tag : DEFAULT_TAGS) {
            TextView bubble = createTagBubble(tag);
            bubble.setOnClickListener(v -> {
                if (tag.equals(selectedTag)) {
                    // 再次点击同一个标签 → 取消选中
                    selectedTag = null;
                    refreshTagHighlight();
                } else {
                    selectedTag = tag;
                    refreshTagHighlight();
                }
            });
            llTags.addView(bubble);
        }
    }

    /**
     * 创建单个标签气泡 TextView，使用 MaterialCardView 包裹实现圆角胶囊效果。
     *
     * @param tag 标签文字
     * @return 配置好的 TextView
     */
    private TextView createTagBubble(String tag) {
        TextView tv = new TextView(this);
        tv.setText("#" + tag);
        tv.setTextSize(13);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(6), dp(16), dp(6));
        return tv;
    }

    /**
     * 刷新所有标签气泡的高亮状态。
     * 选中的标签：治愈蓝背景；未选中：透明背景。
     */
    private void refreshTagHighlight() {
        for (int i = 0; i < llTags.getChildCount(); i++) {
            View child = llTags.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                // 从文字中取出标签名（去掉 # 前缀）
                String tagName = tv.getText().toString().replace("#", "");
                if (tagName.equals(selectedTag)) {
                    tv.setBackgroundColor(getColor(R.color.accent_pastel_blue));
                } else {
                    tv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                }
            }
        }
    }

    /** dp 转 px 工具方法 */
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 在长按的气泡卡片旁边弹出浮动菜单。
     * "编辑卡片"跳转详情页，"彻底删除"弹出二次确认后直接删除。
     *
     * @param noteId    笔记 ID
     * @param anchorView 被长按的 View，PopupMenu 锚定在此 View 旁弹出
     */
    private void showNotePopup(long noteId, View anchorView) {
        PopupMenu popup = new PopupMenu(this, anchorView);
        popup.getMenu().add(0, 1, 0, "编辑卡片");
        popup.getMenu().add(0, 2, 1, "彻底删除");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                // 编辑：跳转到详情页
                Intent intent = new Intent(this, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_NOTE_ID, noteId);
                startActivity(intent);
                return true;
            } else if (item.getItemId() == 2) {
                // 删除：二次确认后执行
                confirmDelete(noteId);
                return true;
            }
            return false;
        });

        popup.show();
    }

    /**
     * 单条删除的二次确认弹窗。
     */
    private void confirmDelete(long noteId) {
        if (isFinishing() || isDestroyed()) return;

        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要彻底删除这条笔记吗？此操作不可撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    executor.execute(() -> {
                        NoteEntity note = noteDao.getNoteById(noteId);
                        if (note != null) {
                            noteDao.delete(note);
                        }
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                            loadNotes();
                        });
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 场景：用户在归档页编辑/删除笔记后按返回键回到主页，
     *       onResume 被调用，确保主页列表与数据库保持同步。
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadNotes();
    }

    /**
     * 在子线程通过 HttpURLConnection 请求一言 API，
     * 解析返回的 JSON 中的 hitokoto 和 from 字段，
     * 拼接为 "句子" —— 来源 格式显示在顶部 tv_quote 中。
     */
    private void fetchDailyQuote() {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                // 请求一言 API，参数 c=d/i/k/e 混合获取不同类型句子
                URL url = new URL("https://v1.hitokoto.cn/?c=d&c=i&c=k&c=e");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);  // 连接超时 8 秒
                connection.setReadTimeout(8000);     // 读取超时 8 秒

                // 读取响应流
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // 解析 JSON
                JSONObject json = new JSONObject(response.toString());
                String hitokoto = json.optString("hitokoto", "");
                String from = json.optString("from", "");

                // 拼接展示文本
                StringBuilder quoteText = new StringBuilder(hitokoto);
                if (!from.isEmpty()) {
                    quoteText.append(" —— ").append(from);
                }

                // 回到主线程设置 TextView
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    tvQuote.setText(quoteText.toString());
                });

            } catch (Exception e) {
                // 网络异常或解析失败时显示默认文案
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    tvQuote.setText("今日灵感获取失败");
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Activity 销毁时关闭线程池，避免资源泄漏
        executor.shutdownNow();
    }
}
