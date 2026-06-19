package com.example.ideacards;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 归档页面：支持搜索、多标签筛选、导出、长按操作和批量删除。
 */
public class ArchiveActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "note_remind";
    private static final String CHANNEL_NAME = "笔记提醒";
    private static final int REQUEST_CODE_NOTIFICATION = 1001;
    private static final int REQUEST_DETAIL = 1002;

    private RecyclerView rvArchiveNotes;
    private ImageButton btnBack;
    private ImageButton btnRemind;
    private ImageButton btnExport;
    private ImageButton btnSelect;
    private EditText etSearch;
    private View cardFilter;
    private TextView tvFilterLabel;
    private TextView tvEmptyHint;

    private NoteListAdapter adapter;
    private NoteDao noteDao;

    /** 当前搜索关键词 */
    private String searchKeyword = "";
    /** 当前选中的筛选标签集合 */
    private final Set<String> selectedFilterTags = new HashSet<>();

    /** 标签匹配正则：用于导出时剔除 content 中的行内标签 */
    private static final Pattern TAG_PATTERN = Pattern.compile("#[^\\s#]+");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_archive);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(systemBars.bottom, ime.bottom);
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottom);
            return insets;
        });

        noteDao = AppDatabase.getInstance(this).noteDao();

        // 绑定视图
        rvArchiveNotes = findViewById(R.id.rv_archive_notes);
        btnBack = findViewById(R.id.btn_back);
        btnRemind = findViewById(R.id.btn_remind);
        btnExport = findViewById(R.id.btn_export);
        btnSelect = findViewById(R.id.btn_select);
        etSearch = findViewById(R.id.et_search);
        cardFilter = findViewById(R.id.card_filter);
        tvFilterLabel = findViewById(R.id.tv_filter_label);
        tvEmptyHint = findViewById(R.id.tv_empty_hint);

        // RecyclerView
        rvArchiveNotes.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NoteListAdapter(this);
        rvArchiveNotes.setAdapter(adapter);

        // 列表项单击
        adapter.setOnNoteClickListener(noteId -> {
            if (!adapter.isSelectionMode()) {
                Intent intent = new Intent(ArchiveActivity.this, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_NOTE_ID, noteId);
                startActivityForResult(intent, REQUEST_DETAIL);
            }
        });

        // 列表项长按
        adapter.setOnNoteLongClickListener((noteId, anchorView) ->
                showNotePopup(noteId, anchorView));

        // 搜索框：实时过滤
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                searchKeyword = s.toString().trim();
                loadNotes();
            }
        });

        // 按钮点击
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

        // 筛选标签按钮：弹出 BottomSheet
        cardFilter.setOnClickListener(v -> showFilterBottomSheet());

        loadNotes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadNotes();
    }

    // ═══════════════════════════════════════
    //  搜索 + 多标签筛选
    // ═══════════════════════════════════════

    /**
     * 根据当前搜索关键词和选中标签，组合查询并刷新列表。
     * 优先级：搜索关键词 > 标签筛选 > 全部。
     */
    private void loadNotes() {
        executor.execute(() -> {
            List<NoteEntity> notes;

            if (!searchKeyword.isEmpty()) {
                // 有搜索关键词 → 按内容模糊匹配
                notes = noteDao.searchNotes(searchKeyword);
                // 如果同时有标签筛选，再用 Java 层过滤
                if (!selectedFilterTags.isEmpty()) {
                    List<NoteEntity> filtered = new ArrayList<>();
                    for (NoteEntity n : notes) {
                        if (n.getTag() != null && selectedFilterTags.contains(n.getTag())) {
                            filtered.add(n);
                        }
                    }
                    notes = filtered;
                }
            } else if (!selectedFilterTags.isEmpty()) {
                // 无搜索但有标签筛选 → 按标签查询
                notes = noteDao.getNotesByTags(new ArrayList<>(selectedFilterTags));
            } else {
                // 无筛选 → 全部
                notes = noteDao.getAllNotes();
            }

            final List<NoteEntity> result = notes;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setData(result);
                tvEmptyHint.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /**
     * 弹出标签筛选 BottomSheet 弹窗。
     * 从数据库加载所有标签，支持多选，底部有"确认筛选"和"重置全部"按钮。
     */
    private void showFilterBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View contentView = LayoutInflater.from(this).inflate(R.layout.dialog_tag_filter, null);
        dialog.setContentView(contentView);

        // 设置 BottomSheet 圆角顶部背景
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            bottomSheet.setBackgroundResource(R.drawable.bg_bottom_sheet);
        }

        RecyclerView rvFilterTags = contentView.findViewById(R.id.rv_filter_tags);
        TextView btnConfirm = contentView.findViewById(R.id.btn_confirm_filter);
        TextView btnReset = contentView.findViewById(R.id.btn_reset_filter);

        rvFilterTags.setLayoutManager(new LinearLayoutManager(this));

        // 异步加载标签
        executor.execute(() -> {
            List<String> tags = noteDao.getAllDistinctTags();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                // 无标签时给出提示
                if (tags == null || tags.isEmpty()) {
                    tvEmptyHint.setVisibility(View.VISIBLE);
                    dialog.dismiss();
                    return;
                }

                // 创建适配器，传入当前已选中的标签
                FilterTagAdapter tagAdapter = new FilterTagAdapter(tags, selectedFilterTags);
                rvFilterTags.setAdapter(tagAdapter);

                // 确认筛选：收集选中标签，关闭弹窗，刷新列表
                btnConfirm.setOnClickListener(v -> {
                    selectedFilterTags.clear();
                    selectedFilterTags.addAll(tagAdapter.getSelectedTags());
                    updateFilterLabel();
                    loadNotes();
                    dialog.dismiss();
                });

                // 重置全部：清空选中状态
                btnReset.setOnClickListener(v -> {
                    tagAdapter.clearAll();
                    selectedFilterTags.clear();
                    updateFilterLabel();
                    loadNotes();
                    dialog.dismiss();
                });

                dialog.show();
            });
        });
    }

    /**
     * 更新筛选按钮上的文字，显示已选中标签数量。
     */
    private void updateFilterLabel() {
        if (selectedFilterTags.isEmpty()) {
            tvFilterLabel.setText("筛选");
        } else {
            tvFilterLabel.setText("筛选(" + selectedFilterTags.size() + ")");
        }
    }

    // ═══════════════════════════════════════
    //  标签筛选 BottomSheet 适配器
    // ═══════════════════════════════════════

    /**
     * BottomSheet 弹窗内的标签列表适配器，支持多选 CheckBox。
     */
    static class FilterTagAdapter extends RecyclerView.Adapter<FilterTagAdapter.ViewHolder> {

        private final List<String> tags;
        private final Set<String> selected;

        FilterTagAdapter(List<String> tags, Set<String> preSelected) {
            this.tags = tags;
            this.selected = new HashSet<>(preSelected);
        }

        Set<String> getSelectedTags() {
            return selected;
        }

        void clearAll() {
            selected.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_filter_tag, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String tag = tags.get(position);
            holder.tvTagName.setText("#" + tag);

            // 防止复用时触发 listener
            holder.cbTag.setOnCheckedChangeListener(null);
            holder.cbTag.setChecked(selected.contains(tag));
            holder.cbTag.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    selected.add(tag);
                } else {
                    selected.remove(tag);
                }
            });

            // 点击整行也能切换
            holder.itemView.setOnClickListener(v -> holder.cbTag.toggle());
        }

        @Override
        public int getItemCount() {
            return tags.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final android.widget.CheckBox cbTag;
            final TextView tvTagName;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                cbTag = itemView.findViewById(R.id.cb_tag);
                tvTagName = itemView.findViewById(R.id.tv_tag_name);
            }
        }
    }

    // ═══════════════════════════════════════
    //  长按 PopupMenu
    // ═══════════════════════════════════════

    private void showNotePopup(long noteId, View anchorView) {
        PopupMenu popup = new PopupMenu(this, anchorView);
        popup.getMenu().add(0, 1, 0, "编辑卡片");
        popup.getMenu().add(0, 2, 1, "彻底删除");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                Intent intent = new Intent(this, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_NOTE_ID, noteId);
                startActivityForResult(intent, REQUEST_DETAIL);
                return true;
            } else if (item.getItemId() == 2) {
                confirmDeleteSingle(noteId);
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void confirmDeleteSingle(long noteId) {
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

    // ═══════════════════════════════════════
    //  编辑模式（多选批量删除）
    // ═══════════════════════════════════════

    private void toggleSelectionMode() {
        boolean entering = !adapter.isSelectionMode();
        adapter.setSelectionMode(entering);
        updateToolbarForSelectionMode(entering);
    }

    private void updateToolbarForSelectionMode(boolean inSelection) {
        if (inSelection) {
            btnSelect.setImageResource(R.drawable.ic_close);
            btnRemind.setVisibility(View.GONE);
            // 导出按钮变为删除语义
            btnExport.setImageResource(R.drawable.ic_select);
        } else {
            btnSelect.setImageResource(R.drawable.ic_select);
            btnRemind.setVisibility(View.VISIBLE);
            btnExport.setImageResource(R.drawable.ic_export);
            adapter.clearSelection();
        }
    }

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
        if (adapter.isSelectionMode()) {
            adapter.setSelectionMode(false);
            updateToolbarForSelectionMode(false);
        } else {
            super.onBackPressed();
        }
    }

    // ═══════════════════════════════════════
    //  通知权限 & 发送通知
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
    //  导出 Markdown
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
                md.append("**[").append(noteTime).append("]**");
                if (note.getTag() != null && !note.getTag().isEmpty()) {
                    md.append("　`#").append(note.getTag()).append("`");
                }
                md.append("\n");
                String cleanContent = stripTags(note.getContent());
                md.append(cleanContent).append("\n\n");
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
    //  工具方法
    // ═══════════════════════════════════════

    /**
     * 剔除文本中的所有行内标签（#xxx），返回纯正文。
     */
    private String stripTags(String text) {
        if (text == null) return "";
        return TAG_PATTERN.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
