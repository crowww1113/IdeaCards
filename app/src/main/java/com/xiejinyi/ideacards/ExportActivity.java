package com.xiejinyi.ideacards;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.services.drive.DriveScopes;

import androidx.core.content.FileProvider;

import com.xiejinyi.ideacards.data.dao.NoteDao;
import com.xiejinyi.ideacards.data.db.AppDatabase;
import com.xiejinyi.ideacards.data.entity.NoteEntity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 导出与知识同步页面。
 * <p>
 * 功能：按标签多选过滤笔记 → 拼接 Markdown → 一键同步至 Google Drive（供 NotebookLM 使用）。
 * <p>
 * 注意：GoogleSignIn.requestPermissions 仅接受 int requestCode，
 * 因此此页面使用 startActivityForResult + onActivityResult 处理所有 Google 授权流程。
 */
public class ExportActivity extends AppCompatActivity {

    private static final String TAG = "ExportActivity";

    /** Google 登录请求码 */
    private static final int RC_SIGN_IN = 9001;
    /** Drive 文件访问权限请求码 */
    private static final int RC_DRIVE_PERM = 4097;
    /** UserRecoverableAuthIOException 二次授权请求码 */
    private static final int RC_AUTH_RECOVERY = 4098;

    // ── 视图绑定 ──
    private TextView tvAccountEmail;
    private TextView tvAccountHint;
    private MaterialCardView btnLoginToggle;
    private ChipGroup chipGroupTags;
    private Chip chipSelectAll;
    private TextView tvNoTags;
    private MaterialCardView cardSyncButton;
    private TextView tvSyncButton;
    private ProgressBar progressSyncOverlay;
    // Obsidian 连接卡片
    private TextView tvObsidianStatus;
    private TextView tvObsidianHint;
    private MaterialCardView btnObsidianBind;
    private TextView tvObsidianBtn;
    // 底部双按钮
    private MaterialCardView cardExportButton;
    private TextView tvExportButton;

    // ── 数据与线程 ──
    private NoteDao noteDao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> allTags = new ArrayList<>();

    // ── Google Drive 同步 ──
    private GoogleDriveManager driveManager;
    /** 上次拼接好的 Markdown（授权回调后重试用） */
    private String pendingMarkdown;
    /** 上次写入的临时文件路径（授权回调后重试用） */
    private String pendingFilePath;

    // ── Obsidian SAF 授权 ──
    private ObsidianSyncManager obsidianManager;

    /** Obsidian 文件夹选择器回调（OpenDocumentTree 兼容 registerForActivityResult） */
    private final ActivityResultLauncher<Uri> obsidianTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    obsidianManager.saveTreeUri(this, uri);
                    Toast.makeText(this, "已成功绑定本地库", Toast.LENGTH_SHORT).show();
                    updateObsidianUI();
                }
            });

    // ═══════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_export);

        // 系统栏内边距适配
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_export), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        noteDao = AppDatabase.getInstance(this).noteDao();
        driveManager = GoogleDriveManager.getInstance();
        obsidianManager = ObsidianSyncManager.getInstance();

        // ── 绑定视图 ──
        ImageButton btnBack = findViewById(R.id.btn_export_back);
        tvAccountEmail = findViewById(R.id.tv_account_email);
        tvAccountHint = findViewById(R.id.tv_account_hint);
        btnLoginToggle = findViewById(R.id.btn_login_toggle);
        chipGroupTags = findViewById(R.id.chip_group_tags);
        chipSelectAll = findViewById(R.id.chip_select_all);
        tvNoTags = findViewById(R.id.tv_no_tags);
        cardSyncButton = findViewById(R.id.card_sync_button);
        tvSyncButton = findViewById(R.id.tv_sync_button);
        progressSyncOverlay = findViewById(R.id.progress_sync_overlay);
        tvObsidianStatus = findViewById(R.id.tv_obsidian_status);
        tvObsidianHint = findViewById(R.id.tv_obsidian_hint);
        btnObsidianBind = findViewById(R.id.btn_obsidian_bind);
        tvObsidianBtn = findViewById(R.id.tv_obsidian_btn);
        cardExportButton = findViewById(R.id.card_export_button);
        tvExportButton = findViewById(R.id.tv_export_button);

        // 返回按钮
        btnBack.setOnClickListener(v -> finish());

        // 登录/切换账号按钮
        btnLoginToggle.setOnClickListener(v -> launchGoogleSignIn());

        // 同步按钮
        cardSyncButton.setOnClickListener(v -> onSyncClicked());

        // 导出文件按钮
        cardExportButton.setOnClickListener(v -> onExportClicked());

        // Obsidian 绑定按钮：已绑定时解除，未绑定时启动文件夹选择器
        btnObsidianBind.setOnClickListener(v -> {
            if (obsidianManager.isConnected(this)) {
                obsidianManager.disconnect(this);
                updateObsidianUI();
                Toast.makeText(this, "已解除 Obsidian 绑定", Toast.LENGTH_SHORT).show();
            } else {
                obsidianTreeLauncher.launch(null);
            }
        });

        // 冷启动：加载标签 + 刷新账户卡片 + 刷新 Obsidian 状态
        loadTags();
        updateAccountUI();
        updateObsidianUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ═══════════════════════════════════════
    //  Google 授权结果处理（startActivityForResult 回调）
    // ═══════════════════════════════════════

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // ── Google 登录结果 ──
        if (requestCode == RC_SIGN_IN) {
            try {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
                GoogleSignInAccount account = task.getResult(ApiException.class);
                Log.d(TAG, "Google 登录成功：" + account.getEmail());
                updateAccountUI();
                // 检查是否已有 Drive 文件访问权限
                if (GoogleSignIn.hasPermissions(account, new Scope(DriveScopes.DRIVE_FILE))) {
                    initDriveAndSync();
                } else {
                    // 请求 Drive 文件访问权限（使用 int requestCode）
                    GoogleSignIn.requestPermissions(
                            this, RC_DRIVE_PERM, account, new Scope(DriveScopes.DRIVE_FILE));
                }
            } catch (ApiException e) {
                // 打印具体错误码，用于排查控制台配置问题
                Log.e("ExportHub", "Google 登录失败，statusCode=" + e.getStatusCode(), e);
                Toast.makeText(this, "登录失败，请重试", Toast.LENGTH_SHORT).show();
                setSyncIdleState();
            }
            return;
        }

        // ── Drive 文件访问权限结果 ──
        if (requestCode == RC_DRIVE_PERM) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "Drive 权限授权成功");
                initDriveAndSync();
            } else {
                Log.w(TAG, "Drive 权限被拒绝");
                Toast.makeText(this, "未授权 Drive 访问，同步取消", Toast.LENGTH_SHORT).show();
                setSyncIdleState();
            }
            return;
        }

        // ── UserRecoverableAuthIOException 二次授权结果 ──
        if (requestCode == RC_AUTH_RECOVERY) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "二次授权成功，重试同步");
                syncToDrive(pendingMarkdown);
            } else {
                Toast.makeText(this, "授权失败，同步取消", Toast.LENGTH_SHORT).show();
                setSyncIdleState();
            }
        }
    }

    // ═══════════════════════════════════════
    //  冷启动：标签加载
    // ═══════════════════════════════════════

    /**
     * 子线程查询数据库所有去重标签，主线程动态创建 Chip 塞入 ChipGroup。
     */
    private void loadTags() {
        executor.execute(() -> {
            List<String> tags = noteDao.getAllDistinctTags();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                allTags.clear();
                if (tags != null) allTags.addAll(tags);
                buildTagChips();
            });
        });
    }

    /**
     * 根据 allTags 列表动态生成标签 Chip，插入 chipGroupTags。
     * 同时设置全选 Chip 的联动逻辑。
     */
    private void buildTagChips() {
        chipGroupTags.removeAllViews();

        // 无标签时显示空状态提示
        if (allTags.isEmpty()) {
            tvNoTags.setVisibility(View.VISIBLE);
            chipSelectAll.setVisibility(View.GONE);
            return;
        }
        tvNoTags.setVisibility(View.GONE);
        chipSelectAll.setVisibility(View.VISIBLE);

        // 重置全选状态
        chipSelectAll.setOnCheckedChangeListener(null);
        chipSelectAll.setChecked(false);

        for (String tag : allTags) {
            Chip chip = new Chip(this);
            chip.setText("#" + tag);
            chip.setCheckable(true);
            chip.setChecked(false);
            chip.setTextSize(13f);
            chip.setChipCornerRadius(20f * getResources().getDisplayMetrics().density);
            chip.setChipBackgroundColorResource(R.color.chip_bg_state);
            chip.setCheckedIconVisible(true);
            // 选中时文字变白，未选时保持深褐色
            chip.setOnCheckedChangeListener((btn, checked) -> {
                btn.setTextColor(checked ? 0xFFFFFFFF : 0xFF4A4543);
            });
            chipGroupTags.addView(chip);
        }

        // 全选 Chip：点击后同步切换所有标签
        chipSelectAll.setOnCheckedChangeListener((btn, checked) -> {
            for (int i = 0; i < chipGroupTags.getChildCount(); i++) {
                View child = chipGroupTags.getChildAt(i);
                if (child instanceof Chip) {
                    ((Chip) child).setChecked(checked);
                }
            }
        });

        // ChipGroup 子项变化时，同步更新全选 Chip 的勾选状态
        chipGroupTags.setOnCheckedStateChangeListener((group, checkedIds) -> {
            boolean allChecked = checkedIds.size() == allTags.size();
            // 暂时移除监听，避免循环触发
            chipSelectAll.setOnCheckedChangeListener(null);
            chipSelectAll.setChecked(allChecked);
            chipSelectAll.setOnCheckedChangeListener((btn, checked) -> {
                for (int i = 0; i < chipGroupTags.getChildCount(); i++) {
                    View child = chipGroupTags.getChildAt(i);
                    if (child instanceof Chip) {
                        ((Chip) child).setChecked(checked);
                    }
                }
            });
        });
    }

    // ═══════════════════════════════════════
    //  Google 账户管理
    // ═══════════════════════════════════════

    /** 检查当前是否已登录 Google */
    @SuppressWarnings("deprecation")
    private boolean isGoogleSignedIn() {
        return GoogleSignIn.getLastSignedInAccount(this) != null;
    }

    /**
     * 刷新账户卡片 UI：已登录显示邮箱，未登录显示提示。
     */
    @SuppressWarnings("deprecation")
    private void updateAccountUI() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account != null && account.getEmail() != null) {
            tvAccountEmail.setText(account.getEmail());
            tvAccountHint.setText("点击右侧按钮可切换账号");
        } else {
            tvAccountEmail.setText("未连接谷歌账号");
            tvAccountHint.setText("登录后即可同步至 Drive");
        }
    }

    // ═══════════════════════════════════════
    //  Obsidian 本地库状态
    // ═══════════════════════════════════════

    /**
     * 刷新 Obsidian 连接卡片 UI：已绑定显示路径和"已绑定"按钮，未绑定显示提示和"绑定"按钮。
     */
    private void updateObsidianUI() {
        if (obsidianManager.isConnected(this)) {
            tvObsidianStatus.setText("Obsidian 库已绑定");
            tvObsidianHint.setText("笔记保存时将自动写入");
            tvObsidianBtn.setText("已绑定");
        } else {
            tvObsidianStatus.setText("连接 Obsidian 库");
            tvObsidianHint.setText("绑定后笔记将自动同步写入");
            tvObsidianBtn.setText("绑定");
        }
    }

    /**
     * 启动 Google 登录流程。
     * 仅在有残留登录状态时先 signOut，避免首次登录的无意义网络开销。
     */
    @SuppressWarnings("deprecation")
    private void launchGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
                .build();
        GoogleSignInClient client = GoogleSignIn.getClient(this, gso);

        if (GoogleSignIn.getLastSignedInAccount(this) != null) {
            // 有残留状态 → 先清除再拉起新窗口
            client.signOut().addOnCompleteListener(this, task -> {
                Log.d(TAG, "signOut 完成，拉起全新登录窗口");
                startActivityForResult(client.getSignInIntent(), RC_SIGN_IN);
            });
        } else {
            // 首次登录 → 直接拉起，无需等待
            startActivityForResult(client.getSignInIntent(), RC_SIGN_IN);
        }
    }

    /**
     * 用已登录账户初始化 Drive 服务，有权限则直接同步，无权限则先请求。
     */
    @SuppressWarnings("deprecation")
    private void initDriveAndSync() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) return;

        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Collections.singletonList(DriveScopes.DRIVE_FILE));
        credential.setSelectedAccount(account.getAccount());
        driveManager.initDriveService(credential);

        // 检查是否已有 Drive 文件访问权限
        if (GoogleSignIn.hasPermissions(account, new Scope(DriveScopes.DRIVE_FILE))) {
            syncToDrive(pendingMarkdown);
        } else {
            // 请求 Drive 文件访问权限（使用 int requestCode）
            GoogleSignIn.requestPermissions(
                    this, RC_DRIVE_PERM, account, new Scope(DriveScopes.DRIVE_FILE));
        }
    }

    // ═══════════════════════════════════════
    //  同步按钮点击 → Markdown 拼接
    // ═══════════════════════════════════════

    /** 同步按钮点击入口：进入加载态，子线程拼接 Markdown 并上传 */
    private void onSyncClicked() {
        setSyncLoadingState();
        buildMarkdownAndSync();
    }

    // ═══════════════════════════════════════
    //  导出按钮点击 → 生成文件 → 系统分享
    // ═══════════════════════════════════════

    /** 导出按钮点击入口：子线程拼接 Markdown，写入临时文件后弹出系统分享面板 */
    private void onExportClicked() {
        cardExportButton.setEnabled(false);
        executor.execute(() -> {
            List<String> selectedTags = getSelectedTags();
            List<NoteEntity> notes;
            if (selectedTags.isEmpty()) {
                notes = noteDao.getAllNotes();
            } else {
                notes = noteDao.getNotesByTags(selectedTags);
            }

            if (notes.isEmpty()) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "没有匹配的笔记可导出", Toast.LENGTH_SHORT).show();
                    cardExportButton.setEnabled(true);
                });
                return;
            }

            // 拼接 Markdown（与同步共用格式）
            String markdown = buildMarkdownString(notes, selectedTags);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA);
            String timestamp = sdf.format(new Date());

            // 写入临时文件并通过 FileProvider 分享
            try {
                File exportDir = new File(getCacheDir(), "export");
                if (!exportDir.exists()) exportDir.mkdirs();
                String fileName = "IdeaCards_" + timestamp + ".md";
                File mdFile = new File(exportDir, fileName);
                FileWriter writer = new FileWriter(mdFile);
                writer.write(markdown);
                writer.close();

                Uri shareUri = FileProvider.getUriForFile(
                        this, "com.xiejinyi.ideacards.fileprovider", mdFile);

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    cardExportButton.setEnabled(true);
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/markdown");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, "IdeaCards 导出");
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "导出笔记"));
                });
            } catch (IOException e) {
                Log.e(TAG, "导出文件创建失败", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    cardExportButton.setEnabled(true);
                    Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 子线程：按用户选中的标签过滤笔记，拼接 Markdown，写入临时文件后触发 Drive 上传。
     */
    private void buildMarkdownAndSync() {
        executor.execute(() -> {
            List<String> selectedTags = getSelectedTags();
            List<NoteEntity> notes;
            if (selectedTags.isEmpty()) {
                notes = noteDao.getAllNotes();
            } else {
                notes = noteDao.getNotesByTags(selectedTags);
            }

            if (notes.isEmpty()) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "没有匹配的笔记可导出", Toast.LENGTH_SHORT).show();
                    setSyncIdleState();
                });
                return;
            }

            String markdown = buildMarkdownString(notes, selectedTags);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            String exportTime = sdf.format(new Date());

            // 写入临时文件
            boolean writeSuccess = false;
            try {
                File exportDir = new File(getCacheDir(), "export");
                if (!exportDir.exists()) exportDir.mkdirs();
                String fileName = "IdeaCards_Sync_" + exportTime.replace(":", "-") + ".md";
                File mdFile = new File(exportDir, fileName);
                FileWriter writer = new FileWriter(mdFile);
                writer.write(markdown);
                writer.close();
                pendingFilePath = mdFile.getAbsolutePath();
                writeSuccess = true;
            } catch (IOException e) {
                Log.e(TAG, "Markdown 写入临时文件失败", e);
            }

            final boolean success = writeSuccess;
            final String finalMarkdown = markdown;

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (success) {
                    syncToDrive(finalMarkdown);
                } else {
                    Toast.makeText(this, "文件创建失败", Toast.LENGTH_SHORT).show();
                    setSyncIdleState();
                }
            });
        });
    }

    /**
     * 拼接标准化 Markdown 文本（同步与导出共用）。
     * 包含导出时间、筛选标签信息、逐条笔记（时间戳+标签+正文）。
     */
    private String buildMarkdownString(List<NoteEntity> notes, List<String> selectedTags) {
        StringBuilder md = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        String exportTime = sdf.format(new Date());

        // 1. 保留 Obsidian 标准 YAML Frontmatter
        md.append("---\n");
        md.append("title: 💡灵感归档\n");
        md.append("date: ").append(exportTime).append("\n");
        if (!selectedTags.isEmpty()) {
            md.append("tags: [");
            for (int i = 0; i < selectedTags.size(); i++) {
                if (i > 0) md.append(", ");
                md.append(selectedTags.get(i)); // 属性区标签无需 #
            }
            md.append("]\n");
        }
        md.append("---\n\n");

        // 3. 笔记内容主体 (极简流式，拒绝大纲污染)
        for (NoteEntity note : notes) {
            String noteTime = sdf.format(new Date(note.getTimestamp()));

            // 判断是否有有效标签，决定是否添加标签小尾巴
            if (note.getTag() != null && !note.getTag().trim().isEmpty()) {
                md.append("**[").append(noteTime).append("] · ").append(note.getTag().trim()).append("**\n");
            } else {
                // 无标签时，极致留白，仅显示时间
                md.append("**[").append(noteTime).append("]**\n");
            }

            String cleanContent = note.getContent();
            if (cleanContent != null) {
                // 清理正文中多余的行内标签，保持纯净
                cleanContent = cleanContent.replaceAll("(?m)(^|\\s)#([^\\s#]+)", "").trim();
            }

            // 追加正文，并使用两个换行符(\n\n)作为天然的视觉与语义分割
            md.append(cleanContent).append("\n\n");
        }

        return md.toString();
    }

    /**
     * 收集 ChipGroup 中所有已选中的标签文本（去掉 # 前缀）。
     */
    private List<String> getSelectedTags() {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < chipGroupTags.getChildCount(); i++) {
            View child = chipGroupTags.getChildAt(i);
            if (child instanceof Chip && ((Chip) child).isChecked()) {
                // 去掉显示时添加的 # 前缀，还原为数据库中的原始标签
                String text = ((Chip) child).getText().toString();
                if (text.startsWith("#")) text = text.substring(1);
                selected.add(text);
            }
        }
        return selected;
    }

    // ═══════════════════════════════════════
    //  Google Drive 上传
    // ═══════════════════════════════════════

    /**
     * 子线程执行 Drive 上传，处理各种异常（未登录、需授权、网络错误）。
     *
     * @param markdown 已拼接好的完整 Markdown 文本
     */
    @SuppressWarnings("deprecation")
    private void syncToDrive(String markdown) {
        if (markdown == null || pendingFilePath == null) {
            Log.w(TAG, "无待同步数据");
            setSyncIdleState();
            return;
        }

        pendingMarkdown = markdown;
        File localFile = new File(pendingFilePath);
        if (!localFile.exists()) {
            Log.w(TAG, "临时文件不存在：" + pendingFilePath);
            setSyncIdleState();
            return;
        }

        executor.execute(() -> {
            // Drive 服务未初始化 → 触发登录
            if (!driveManager.isInitialized()) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "请先登录 Google 账号", Toast.LENGTH_SHORT).show();
                    setSyncIdleState();
                    launchGoogleSignIn();
                });
                return;
            }

            try {
                boolean success = driveManager.uploadOrUpdateFile(localFile);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pendingMarkdown = null;
                    pendingFilePath = null;
                    setSyncIdleState();
                    if (success) {
                        Toast.makeText(this,
                                "同步成功！NotebookLM 已就绪",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this,
                                "Drive 同步失败，请重试",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (UserRecoverableAuthIOException e) {
                // 用户未授权 Drive 文件访问，引导二次授权界面
                Log.w(TAG, "需要用户授权 Drive 访问", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "需要授权 Drive 访问权限", Toast.LENGTH_SHORT).show();
                    try {
                        startActivityForResult(e.getIntent(), RC_AUTH_RECOVERY);
                    } catch (Exception ex) {
                        Log.e(TAG, "无法启动授权界面", ex);
                        pendingMarkdown = null;
                        pendingFilePath = null;
                        setSyncIdleState();
                        Toast.makeText(this, "授权失败，请重试", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "Drive 同步 IO 异常", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pendingMarkdown = null;
                    pendingFilePath = null;
                    setSyncIdleState();
                    Toast.makeText(this, "网络异常，同步失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ═══════════════════════════════════════
    //  UI 状态切换
    // ═══════════════════════════════════════

    /** 同步中：禁用按钮，显示加载动画 */
    private void setSyncLoadingState() {
        cardSyncButton.setEnabled(false);
        tvSyncButton.setVisibility(View.INVISIBLE);
        progressSyncOverlay.setVisibility(View.VISIBLE);
    }

    /** 空闲态：恢复按钮，隐藏加载动画 */
    private void setSyncIdleState() {
        cardSyncButton.setEnabled(true);
        tvSyncButton.setVisibility(View.VISIBLE);
        progressSyncOverlay.setVisibility(View.GONE);
    }
}
