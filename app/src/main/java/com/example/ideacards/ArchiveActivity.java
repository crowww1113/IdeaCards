package com.example.ideacards;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.ideacards.data.db.AppDatabase;
import com.example.ideacards.data.dao.NoteDao;
import com.example.ideacards.data.entity.NoteEntity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 归档页面：展示笔记列表，支持返回、提醒、导出 Markdown、
 * 长按快捷操作（PopupMenu）和批量多选删除功能。
 */
public class ArchiveActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "note_remind";
    private static final String CHANNEL_NAME = "笔记提醒";
    private static final int REQUEST_CODE_NOTIFICATION = 1001;
    private static final int REQUEST_DETAIL = 1002;

    private RecyclerView rvArchiveNotes;
    private ImageButton btnBack;
    private TextView btnRemind;
    private TextView btnExport;
    private TextView btnSelect;

    private NoteListAdapter adapter;
    private NoteDao noteDao;

    /** 标签过滤栏容器 */
    private LinearLayout llFilterTags;
    /** 当前筛选标签，null 表示显示全部 */
    private String currentFilterTag = null;
    /** 默认兜底标签（数据库无历史标签时使用） */
    private static final String[] DEFAULT_FILTER_TAGS = {"灵感", "生活", "摘录"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_archive);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        noteDao = AppDatabase.getInstance(this).noteDao();

        // 绑定视图
        rvArchiveNotes = findViewById(R.id.rv_archive_notes);
        btnBack = findViewById(R.id.btn_back);
        btnRemind = findViewById(R.id.btn_remind);
        btnExport = findViewById(R.id.btn_export);
        btnSelect = findViewById(R.id.btn_select);
        llFilterTags = findViewById(R.id.ll_filter_tags);

        // 初始化标签过滤栏
        setupFilterTags();

        // RecyclerView
        rvArchiveNotes.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NoteListAdapter(this);
        rvArchiveNotes.setAdapter(adapter);

        // ── 列表项单击：普通模式 → 详情页；编辑模式 → 切换复选框（Adapter 内部处理）──
        adapter.setOnNoteClickListener(noteId -> {
            if (!adapter.isSelectionMode()) {
                Intent intent = new Intent(ArchiveActivity.this, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_NOTE_ID, noteId);
                startActivityForResult(intent, REQUEST_DETAIL);
            }
        });

        // ── 列表项长按：弹出 PopupMenu（编辑/删除） ──
        adapter.setOnNoteLongClickListener((noteId, anchorView) ->
                showNotePopup(noteId, anchorView));

        // ── 按钮点击 ──
        btnBack.setOnClickListener(v -> onBackPressed());
        btnRemind.setOnClickListener(v -> onRemindClicked());
        btnSelect.setOnClickListener(v -> toggleSelectionMode());
        btnExport.setOnClickListener(v -> {
            if (adapter.isSelectionMode()) {
                onBatchDeleteClicked();
            } else {
                exportToMarkdown();
            }
        });

        loadNotes();
    }

    /**
     * 从详情页返回后，刷新标签筛选栏（用户可能新增/修改了标签）和笔记列表。
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadFilterTagsFromDb();
    }

    // ═══════════════════════════════════════
    //  长按 PopupMenu
    // ═══════════════════════════════════════

    /**
     * 在长按的卡片旁边弹出浮动菜单，提供"编辑卡片"和"彻底删除"两个选项。
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
                // 编辑：跳转详情页
                Intent intent = new Intent(this, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_NOTE_ID, noteId);
                startActivityForResult(intent, REQUEST_DETAIL);
                return true;
            } else if (item.getItemId() == 2) {
                // 删除：二次确认后执行
                confirmDeleteSingle(noteId);
                return true;
            }
            return false;
        });

        popup.show();
    }

    /**
     * 单条删除的二次确认弹窗。
     * 用户确认后在子线程中通过 getNoteById + delete 删除笔记。
     */
    private void confirmDeleteSingle(long noteId) {
        if (isFinishing() || isDestroyed()) return;

        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要彻底删除这条笔记吗？此操作不可撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    executor.execute(() -> {
                        // 通过 ID 查询实体，再用 @Delete 注解方法删除
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

    // ═══════════════════════════════════════
    //  编辑模式（多选批量删除）
    // ═══════════════════════════════════════

    /**
     * 切换编辑模式：显示/隐藏所有复选框，
     * 同时将顶部按钮在"导出 MD"和"删除选中"之间切换。
     */
    private void toggleSelectionMode() {
        boolean entering = !adapter.isSelectionMode();
        adapter.setSelectionMode(entering);
        updateToolbarForSelectionMode(entering);
    }

    /**
     * 根据当前模式更新导航栏按钮的显隐和文字。
     *
     * @param inSelection true=编辑模式，false=普通模式
     */
    private void updateToolbarForSelectionMode(boolean inSelection) {
        if (inSelection) {
            // 进入编辑模式：显示"取消"，隐藏"提醒"，导出按钮变为红色"删除选中"
            btnSelect.setText("取消");
            btnRemind.setVisibility(View.GONE);
            btnExport.setText("删除选中");
            // 动态改为柔粉背景（与删除语义一致）
            View card = (View) btnExport.getParent();
            if (card instanceof com.google.android.material.card.MaterialCardView) {
                ((com.google.android.material.card.MaterialCardView) card)
                        .setCardBackgroundColor(getColor(R.color.accent_pastel_pink));
            }
        } else {
            // 退出编辑模式：恢复原始状态
            btnSelect.setText("选择");
            btnRemind.setVisibility(View.VISIBLE);
            btnExport.setText("导出 MD");
            View card = (View) btnExport.getParent();
            if (card instanceof com.google.android.material.card.MaterialCardView) {
                ((com.google.android.material.card.MaterialCardView) card)
                        .setCardBackgroundColor(getColor(R.color.accent_pastel_pink));
            }
            adapter.clearSelection();
        }
    }

    /**
     * 批量删除选中笔记的处理。
     * 未选中任何笔记时给出提示；选中后弹出二次确认，确认后子线程批量删除。
     */
    private void onBatchDeleteClicked() {
        List<Long> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的笔记", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("确定要删除选中的 " + ids.size() + " 条笔记吗？此操作不可撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    executor.execute(() -> {
                        noteDao.deleteNotesByIds(ids);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, "已删除 " + ids.size() + " 条笔记",
                                    Toast.LENGTH_SHORT).show();
                            // 退出编辑模式并刷新列表
                            adapter.setSelectionMode(false);
                            updateToolbarForSelectionMode(false);
                            loadNotes();
                        });
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ═══════════════════════════════════════
    //  返回键处理
    // ═══════════════════════════════════════

    @Override
    public void onBackPressed() {
        // 编辑模式下按返回键 → 退出编辑模式而非退出页面
        if (adapter.isSelectionMode()) {
            adapter.setSelectionMode(false);
            updateToolbarForSelectionMode(false);
        } else {
            super.onBackPressed();
        }
    }

    // ═══════════════════════════════════════
    //  通知权限 & 发送通知（原有逻辑不变）
    // ═══════════════════════════════════════

    private void onRemindClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int state = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS);
            if (state == PackageManager.PERMISSION_GRANTED) {
                sendNotification();
            } else {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_NOTIFICATION);
            }
        } else {
            sendNotification();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_NOTIFICATION) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendNotification();
        }
    }

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
            loadNotes();
        }
    }

    private void sendNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("用于提醒用户整理未归档的灵感笔记");
            manager.createNotificationChannel(channel);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("整理提醒")
                .setContentText("你有未归档的灵感等待处理")
                .setAutoCancel(true);
        manager.notify(1, builder.build());
    }

    // ═══════════════════════════════════════
    //  导出 Markdown（原有逻辑不变）
    // ═══════════════════════════════════════

    private void exportToMarkdown() {
        btnExport.setEnabled(false);
        executor.execute(() -> {
            List<NoteEntity> notes = noteDao.getAllNotes();
            if (notes.isEmpty()) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnExport.setEnabled(true);
                    Toast.makeText(this, "暂无笔记可导出", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            StringBuilder md = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            String exportTime = sdf.format(new Date(System.currentTimeMillis()));

            md.append("# IdeaCards 随笔\n");
            md.append("> 导出时间：").append(exportTime).append("\n\n");

            for (NoteEntity note : notes) {
                md.append("---\n\n");
                String noteTime = sdf.format(new Date(note.getTimestamp()));
                md.append("**[").append(noteTime).append("]**\n");
                md.append(note.getContent()).append("\n\n");
            }
            md.append("---\n");

            String markdown = md.toString();
            Uri fileUri = null;
            try {
                File exportDir = new File(getCacheDir(), "export");
                if (!exportDir.exists()) exportDir.mkdirs();
                String fileName = "IdeaCards_" + exportTime.replace(":", "-") + ".md";
                File mdFile = new File(exportDir, fileName);
                FileWriter writer = new FileWriter(mdFile);
                writer.write(markdown);
                writer.close();
                fileUri = FileProvider.getUriForFile(
                        this, "com.example.ideacards.fileprovider", mdFile);
            } catch (IOException e) {
                e.printStackTrace();
            }

            final Uri shareUri = fileUri;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnExport.setEnabled(true);
                if (shareUri == null) {
                    Toast.makeText(this, "文件创建失败，以文本方式分享", Toast.LENGTH_SHORT).show();
                    Intent fallback = new Intent(Intent.ACTION_SEND);
                    fallback.setType("text/plain");
                    fallback.putExtra(Intent.EXTRA_TEXT, markdown);
                    startActivity(Intent.createChooser(fallback, "分享笔记"));
                    return;
                }
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/markdown");
                shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "IdeaCards 随笔导出");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "分享笔记"));
            });
        });
    }

    // ═══════════════════════════════════════
    //  标签过滤栏
    // ═══════════════════════════════════════

    /**
     * 初始化标签过滤栏：先添加"全部"按钮，再异步加载数据库中的历史标签。
     * 数据库无标签时使用默认兜底标签（灵感、生活、摘录）。
     */
    private void setupFilterTags() {
        // "全部"标签始终在首位
        TextView allBubble = createFilterBubble("全部", false);
        allBubble.setOnClickListener(v -> {
            currentFilterTag = null;
            refreshFilterHighlight();
            loadNotes();
        });
        llFilterTags.addView(allBubble);

        // 异步加载数据库中的去重标签
        loadFilterTagsFromDb();
    }

    /**
     * 从数据库异步读取所有去重标签，动态生成筛选气泡。
     * 如果数据库无标签，使用默认兜底标签。
     * 如果当前筛选标签在新列表中仍存在，保持选中状态。
     */
    private void loadFilterTagsFromDb() {
        executor.execute(() -> {
            List<String> tags = noteDao.getAllDistinctTags();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                // 移除"全部"以外的所有旧气泡
                while (llFilterTags.getChildCount() > 1) {
                    llFilterTags.removeViewAt(llFilterTags.getChildCount() - 1);
                }

                // 决定使用哪组标签
                String[] tagArray;
                if (tags != null && !tags.isEmpty()) {
                    tagArray = tags.toArray(new String[0]);
                } else {
                    tagArray = DEFAULT_FILTER_TAGS;
                }

                // 动态生成筛选气泡
                for (String tag : tagArray) {
                    TextView bubble = createFilterBubble("#" + tag, true);
                    bubble.setOnClickListener(v -> {
                        currentFilterTag = tag;
                        refreshFilterHighlight();
                        loadNotesByTag(tag);
                    });
                    llFilterTags.addView(bubble);
                }

                // 如果当前筛选标签已不在列表中，重置为"全部"
                if (currentFilterTag != null) {
                    boolean found = false;
                    for (String t : tagArray) {
                        if (t.equals(currentFilterTag)) { found = true; break; }
                    }
                    if (!found) {
                        currentFilterTag = null;
                    }
                }

                refreshFilterHighlight();
            });
        });
    }

    /**
     * 创建过滤栏中的单个标签 TextView。
     *
     * @param text   显示文字
     * @param isTag  true=标签气泡使用标签高亮色，false="全部"使用主文本色
     */
    private TextView createFilterBubble(String text, boolean isTag) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(isTag ? getColor(R.color.tag_highlight) : getColor(R.color.text_primary));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(6), dp(16), dp(6));
        return tv;
    }

    /**
     * 刷新过滤栏的高亮状态。
     * 选中的标签：治愈蓝背景；未选中：透明背景。
     */
    private void refreshFilterHighlight() {
        for (int i = 0; i < llFilterTags.getChildCount(); i++) {
            View child = llFilterTags.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                String text = tv.getText().toString();
                // 判断是否是当前选中的标签
                boolean isSelected = false;
                if (currentFilterTag == null && "全部".equals(text)) {
                    isSelected = true;
                } else if (currentFilterTag != null && text.equals("#" + currentFilterTag)) {
                    isSelected = true;
                }
                if (isSelected) {
                    tv.setBackgroundColor(getColor(R.color.accent_pastel_blue));
                } else {
                    // 标签气泡使用浅蓝灰底色，"全部"使用透明
                    if (text.equals("全部")) {
                        tv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    } else {
                        // 复用标签气泡背景 drawable
                        tv.setBackgroundResource(R.drawable.bg_tag_bubble);
                    }
                }
            }
        }
    }

    /**
     * 按标签查询笔记并刷新列表。
     */
    private void loadNotesByTag(String tag) {
        executor.execute(() -> {
            List<NoteEntity> notes = noteDao.getNotesByTag(tag);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setData(notes);
            });
        });
    }

    /** dp 转 px 工具方法 */
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ═══════════════════════════════════════
    //  数据加载
    // ═══════════════════════════════════════

    private void loadNotes() {
        executor.execute(() -> {
            // 根据当前筛选标签决定查询方式
            List<NoteEntity> notes = (currentFilterTag == null)
                    ? noteDao.getAllNotes()
                    : noteDao.getNotesByTag(currentFilterTag);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setData(notes);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
