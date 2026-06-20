package com.xiejinyi.ideacards;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.xiejinyi.ideacards.data.db.AppDatabase;
import com.xiejinyi.ideacards.data.dao.NoteDao;
import com.xiejinyi.ideacards.data.entity.NoteEntity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * 主界面：管理笔记列表的展示、新增与数据库交互。
 * 支持行内标签（#标签）语法高亮，以及最近使用标签的快速插入。
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvQuote;
    private RecyclerView rvNotes;
    private EditText etInput;
    private TextView btnSend;
    private ImageView btnArchive;
    private TextView btnTag;
    private ImageView btnOcr;
    private HorizontalScrollView hsvTags;
    private LinearLayout llTags;

    private BubbleAdapter adapter;
    private NoteDao noteDao;

    /** 标签匹配正则：# 开头，后跟一个或多个非空白非 # 字符 */
    private static final Pattern TAG_PATTERN = Pattern.compile("#[^\\s#]+");

    /** 默认兜底标签（冷启动、数据库无历史标签时使用） */
    private static final String[] DEFAULT_RECENT_TAGS = {"灵感", "生活", "摘录"};

    /** 标签高亮色：莫兰迪灰蓝 */
    private int tagHighlightColor;

    /** 单线程池，保证数据库操作串行执行，避免并发冲突 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** 相册选图启动器：PickVisualMedia 支持 Android 10+ 照片选择器 */
    private final ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    onImagePicked(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // 适配系统栏 + 键盘内边距，避免输入区被导航栏或软键盘遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // 底部取系统栏和键盘中较大的值，确保输入框始终在键盘上方
            int bottom = Math.max(systemBars.bottom, ime.bottom);
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottom);
            return insets;
        });

        // 初始化 Room 数据库，获取 DAO
        noteDao = AppDatabase.getInstance(this).noteDao();

        // 预取标签高亮色，避免在 TextWatcher 中重复调用 getColor
        tagHighlightColor = getColor(R.color.tag_highlight);

        // 绑定视图
        tvQuote = findViewById(R.id.tv_quote);
        tvQuote.setSelected(true); // 启用跑马灯滚动
        // 长按每日一言 → 弹出"保存为笔记"
        tvQuote.setOnLongClickListener(v -> {
            String quote = tvQuote.getText().toString();
            if (quote.isEmpty() || quote.equals("今日灵感获取失败")) return false;

            PopupMenu popup = new PopupMenu(this, tvQuote);
            popup.getMenu().add(0, 1, 0, "保存为笔记");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    saveQuoteAsNote(quote);
                    return true;
                }
                return false;
            });
            popup.show();
            return true;
        });
        rvNotes = findViewById(R.id.rv_notes);
        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        btnArchive = findViewById(R.id.btn_archive);
        btnTag = findViewById(R.id.btn_tag);
        btnOcr = findViewById(R.id.btn_ocr);
        hsvTags = findViewById(R.id.hsv_tags);
        llTags = findViewById(R.id.ll_tags);

        // 设置 RecyclerView：纵向列表，聊天气泡风格
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        rvNotes.setLayoutManager(layoutManager);

        adapter = new BubbleAdapter(this);
        rvNotes.setAdapter(adapter);

        // 长按卡片：弹出 PopupMenu（编辑卡片 / 彻底删除）
        adapter.setOnNoteLongClickListener((noteId, anchorView) ->
                showNotePopup(noteId, anchorView));

        // 标签按钮：切换最近标签气泡的显隐，展开时追加 #，关闭时清除未使用的 #
        btnTag.setOnClickListener(v -> {
            if (hsvTags.getVisibility() == View.VISIBLE) {
                hsvTags.setVisibility(View.GONE);
                // 关闭时：若光标前是我们插入的 # 且后面没写标签名，删掉它
                int cursor = etInput.getSelectionStart();
                String text = etInput.getText().toString();
                String before = text.substring(0, Math.max(0, cursor));
                if (before.endsWith("#") && (cursor >= text.length()
                        || text.charAt(cursor) == ' ' || text.charAt(cursor) == '\n')) {
                    etInput.getText().delete(cursor - 1, cursor);
                }
            } else {
                hsvTags.setVisibility(View.VISIBLE);
                insertAtCursor("#");
            }
            etInput.requestFocus();
        });

        // OCR 识图按钮：唤起系统相册选择图片
        btnOcr.setOnClickListener(v ->
                pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        // 添加 TextWatcher：实时为 #标签 语法高亮
        etInput.addTextChangedListener(new TagHighlightWatcher());

        // 发送按钮点击：校验输入 -> 提取标签 -> 子线程写入数据库 -> 刷新列表
        btnSend.setOnClickListener(v -> onSendClicked());

        // 归档按钮点击：跳转到归档列表页
        btnArchive.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, ArchiveActivity.class));
        });

        // 首次加载：从数据库读取历史笔记
        loadNotes();

        // 异步加载最近使用标签气泡
        loadRecentTags();

        // 请求网络获取每日一言，展示在顶部 TextView
        fetchDailyQuote();
    }

    /**
     * 异步加载最近使用标签，动态生成气泡。
     * 数据库有历史标签 → 使用历史标签；
     * 数据库为空 → 兜底使用默认标签（灵感、生活、摘录）。
     */
    private void loadRecentTags() {
        executor.execute(() -> {
            List<String> tags = noteDao.getRecentTags();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                llTags.removeAllViews();

                // 决定使用哪组标签：数据库有记录则用历史，否则用默认兜底
                String[] tagArray;
                if (tags != null && !tags.isEmpty()) {
                    tagArray = tags.toArray(new String[0]);
                } else {
                    tagArray = DEFAULT_RECENT_TAGS;
                }

                // 动态生成标签气泡
                for (String tag : tagArray) {
                    TextView bubble = createTagBubble(tag);
                    bubble.setOnClickListener(v -> {
                        // 点击气泡：若光标前已有 #（由标签按钮插入），直接追加标签名；
                        // 否则插入完整 #标签名
                        int cursor = etInput.getSelectionStart();
                        String textBefore = etInput.getText().toString()
                                .substring(0, Math.max(0, cursor));
                        if (textBefore.endsWith("#")) {
                            insertAtCursor(tag + " ");
                        } else {
                            insertAtCursor("#" + tag + " ");
                        }
                        hsvTags.setVisibility(View.GONE);
                        etInput.requestFocus();
                    });
                    llTags.addView(bubble);
                }

                // 标签区域的显隐由 btnTag 点击控制，此处不自动显示
            });
        });
    }

    /**
     * 创建单个标签气泡 TextView，使用项目统一的圆角胶囊风格。
     *
     * @param tag 标签文字（不含 # 前缀）
     * @return 配置好的 TextView
     */
    private TextView createTagBubble(String tag) {
        TextView tv = new TextView(this);
        tv.setText("#" + tag);
        tv.setTextSize(13);
        tv.setTextColor(tagHighlightColor);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(5), dp(14), dp(5));
        // 圆角背景：复用治愈蓝背景，与项目风格统一
        tv.setBackgroundResource(R.drawable.bg_tag_bubble);

        // 设置外边距：气泡之间留出间距
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        tv.setLayoutParams(lp);

        return tv;
    }

    /**
     * 向 EditText 的当前光标位置插入文本，并将光标移至插入内容之后。
     *
     * @param text 要插入的文本
     */
    private void insertAtCursor(String text) {
        int start = etInput.getSelectionStart();
        Editable editable = etInput.getText();
        if (editable != null) {
            editable.insert(start, text);
        }
    }

    /**
     * 用户从相册选中图片后，调用 OCR 提取文字并追加到输入框。
     */
    private void onImagePicked(Uri imageUri) {
        Toast.makeText(this, "正在提取文字，请稍候...", Toast.LENGTH_SHORT).show();

        OcrManager.getInstance().extractTextFromUri(this, imageUri,
                new OcrManager.OcrCallback() {
                    @Override
                    public void onSuccess(String text) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            // 前后补换行，保持版面整洁
                            insertAtCursor("\n" + text + "\n");
                            Toast.makeText(MainActivity.this,
                                    "文字已提取", Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(MainActivity.this,
                                    error, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    /**
     * 发送按钮点击处理。
     * 1. 从输入文本中用正则提取第一个 #标签 作为 tag 字段
     * 2. 从正文中剔除该标签文字，只保留纯笔记内容存入 content
     * 3. 子线程写入数据库，主线程清空输入框并刷新
     */
    private void onSendClicked() {
        String rawText = etInput.getText().toString().trim();

        // 空内容拦截，给出提示
        if (TextUtils.isEmpty(rawText)) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show();
            return;
        }

        // 防快速连点：发送期间禁用按钮
        btnSend.setEnabled(false);

        // 用正则提取第一个 #标签 作为 tag 字段
        String extractedTag = null;
        String content = rawText;
        Matcher matcher = TAG_PATTERN.matcher(rawText);
        if (matcher.find()) {
            // 取匹配到的第一个标签，去掉 # 前缀存入数据库
            extractedTag = matcher.group().substring(1);
            // 从正文中剔除该标签文字，清理多余空格
            // 注意：不能复用同一个 Matcher 调 replaceFirst，
            // find()/group() 后 Matcher 内部状态会干扰替换结果，
            // 必须用一个新的 Matcher 来执行替换。
            content = TAG_PATTERN.matcher(rawText).replaceFirst("").trim();
        }

        // 剔除标签后如果正文为空，给出提示
        if (TextUtils.isEmpty(content)) {
            Toast.makeText(this, "请输入正文内容", Toast.LENGTH_SHORT).show();
            btnSend.setEnabled(true);
            return;
        }

        // 构造笔记实体
        NoteEntity note = new NoteEntity(content, System.currentTimeMillis(), 0);
        note.setTag(extractedTag);

        // 通过统一仓库保存（入库 + Obsidian 同步）
        executor.execute(() -> {
            NoteRepository.getInstance(MainActivity.this).saveNote(note);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                etInput.setText("");
                btnSend.setEnabled(true);
                loadNotes();
                loadRecentTags();
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
                if (isFinishing() || isDestroyed()) return;
                adapter.setData(notes);
                // scrollToPosition 必须用 adapter 的 item 数量（含时间分隔线），
                // 而非 notes.size()，否则会滚错位置
                int lastPos = adapter.getItemCount() - 1;
                if (lastPos >= 0) {
                    rvNotes.post(() -> rvNotes.scrollToPosition(lastPos));
                }
            });
        });
    }

    /**
     * TextWatcher 实现：实时为输入框中的 #标签 文本设置莫兰迪灰蓝高亮。
     * 每次文本变化时，清除旧的 ForegroundColorSpan，重新扫描并着色。
     */
    private class TagHighlightWatcher implements TextWatcher {

        /** 标记是否正在由本 Watcher 修改文本，防止死循环 */
        private boolean isModifying = false;

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // 不需要处理
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // 不需要处理
        }

        @Override
        public void afterTextChanged(Editable editable) {
            // 如果是本 Watcher 自身触发的修改，跳过避免死循环
            if (isModifying) return;

            isModifying = true;

            // 第一步：清除所有旧的 ForegroundColorSpan（标签高亮色）
            ForegroundColorSpan[] oldSpans = editable.getSpans(0, editable.length(), ForegroundColorSpan.class);
            for (ForegroundColorSpan span : oldSpans) {
                // 只清除标签高亮色的 span，保留其他可能存在的颜色
                if (span.getForegroundColor() == tagHighlightColor) {
                    editable.removeSpan(span);
                }
            }

            // 第二步：用正则重新扫描，为每个 #标签 设置高亮
            String text = editable.toString();
            Matcher matcher = TAG_PATTERN.matcher(text);
            while (matcher.find()) {
                int matchStart = matcher.start();
                int matchEnd = matcher.end();
                // 设置莫兰迪灰蓝前景色
                editable.setSpan(
                        new ForegroundColorSpan(tagHighlightColor),
                        matchStart,
                        matchEnd,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }

            isModifying = false;
        }
    }

    /** dp 转 px 工具方法 */
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 在长按的气泡卡片旁边弹出浮动菜单。
     * "编辑卡片"跳转详情页，"彻底删除"弹出二次确认后直接删除。
     */
    private void showNotePopup(long noteId, View anchorView) {
        PopupMenu popup = new PopupMenu(this, anchorView);
        popup.getMenu().add(0, 1, 0, "编辑卡片");
        popup.getMenu().add(0, 2, 1, "彻底删除");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                Intent intent = new Intent(this, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_NOTE_ID, noteId);
                startActivity(intent);
                return true;
            } else if (item.getItemId() == 2) {
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

    @Override
    protected void onResume() {
        super.onResume();
        loadNotes();
        // 从归档页返回后，也可能带了新标签，刷新一下
        loadRecentTags();
    }

    /**
     * 将每日一言保存为笔记，自动添加 #摘录 标签。
     */
    private void saveQuoteAsNote(String quote) {
        NoteEntity note = new NoteEntity(quote, System.currentTimeMillis(), 0);
        note.setTag("摘录");
        executor.execute(() -> {
            NoteRepository.getInstance(MainActivity.this).saveNote(note);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, "已保存为笔记", Toast.LENGTH_SHORT).show();
                loadNotes();
                loadRecentTags();
            });
        });
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
                URL url = new URL("https://v1.hitokoto.cn/?c=d&c=i&c=k&c=e");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                String hitokoto = json.optString("hitokoto", "");
                String from = json.optString("from", "");

                StringBuilder quoteText = new StringBuilder(hitokoto);
                if (!from.isEmpty()) {
                    quoteText.append(" —— ").append(from);
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvQuote.setText(quoteText.toString());
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
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
        executor.shutdownNow();
    }
}
