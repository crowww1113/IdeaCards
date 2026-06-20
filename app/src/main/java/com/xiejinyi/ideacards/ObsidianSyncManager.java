package com.xiejinyi.ideacards;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Obsidian 本地库同步管理器（单例）。
 * <p>
 * 通过 SAF（Storage Access Framework）的 DocumentFile API，
 * 将笔记静默写入用户授权的 Obsidian Vault 文件夹。
 * <p>
 * 写入路径：{用户选择的根目录}/IdeaCards/{文件名}.md
 * 所有笔记统一存放在 IdeaCards 子目录中，不污染 Obsidian 库根目录。
 */
public class ObsidianSyncManager {

    private static final String TAG = "ObsidianSyncManager";
    private static final String PREFS_NAME = "obsidian_sync";
    private static final String KEY_TREE_URI = "tree_uri";
    /** 笔记存放的子目录名 */
    private static final String SUBDIRECTORY_NAME = "IdeaCards";

    /** 同步文件名（单文件，所有笔记追加到此文件中） */
    private static final String SYNC_FILE_NAME = "IdeaCards_Sync.md";

    private static volatile ObsidianSyncManager instance;

    private ObsidianSyncManager() {}

    public static ObsidianSyncManager getInstance() {
        if (instance == null) {
            synchronized (ObsidianSyncManager.class) {
                if (instance == null) {
                    instance = new ObsidianSyncManager();
                }
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════
    //  Uri 持久化
    // ═══════════════════════════════════════

    /**
     * 保存用户授权的 Tree Uri，并申请永久读写权限。
     *
     * @param context 上下文
     * @param treeUri 用户通过文件夹选择器返回的 Uri
     */
    public void saveTreeUri(Context context, Uri treeUri) {
        // 申请永久读写权限（跨重启仍有效）
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        context.getContentResolver().takePersistableUriPermission(treeUri, flags);

        // 持久化 Uri 字符串
        getPrefs(context).edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .apply();
        Log.d(TAG, "Obsidian 库已绑定：" + treeUri);
    }

    /**
     * 获取已保存的 Tree Uri。
     *
     * @return Uri 对象，未绑定时返回 null
     */
    public Uri getTreeUri(Context context) {
        String uriStr = getPrefs(context).getString(KEY_TREE_URI, null);
        return uriStr != null ? Uri.parse(uriStr) : null;
    }

    /**
     * 当前是否已绑定 Obsidian 库。
     */
    public boolean isConnected(Context context) {
        return getTreeUri(context) != null;
    }

    /**
     * 解除绑定：清除保存的 Uri 并释放持久化权限。
     */
    public void disconnect(Context context) {
        Uri uri = getTreeUri(context);
        if (uri != null) {
            // 释放持久化权限
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try {
                context.getContentResolver().releasePersistableUriPermission(uri, flags);
            } catch (SecurityException e) {
                Log.w(TAG, "释放权限失败（可能已过期）", e);
            }
        }
        getPrefs(context).edit().remove(KEY_TREE_URI).apply();
        Log.d(TAG, "Obsidian 库已解绑");
    }

    // ═══════════════════════════════════════
    //  静默写入（单文件追加模式）
    // ═══════════════════════════════════════

    /**
     * 将完整 Markdown 覆写到 Obsidian 库的 IdeaCards_Sync.md。
     * 每次保存笔记时调用，用全量笔记内容整体覆盖，等同于自动导出。
     *
     * @param context  上下文
     * @param markdown 全量笔记的完整 Markdown 文本（与导出格式一致）
     * @return true 写入成功，false 失败
     */
    public boolean syncAllNotes(Context context, String markdown) {
        Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            Log.w(TAG, "未绑定 Obsidian 库，跳过写入");
            return false;
        }

        DocumentFile rootDir = DocumentFile.fromTreeUri(context, treeUri);
        if (rootDir == null || !rootDir.exists()) {
            Log.e(TAG, "根目录不可访问：" + treeUri);
            return false;
        }

        // 查找或创建 IdeaCards 子目录
        DocumentFile ideaCardsDir = findOrCreateSubdirectory(rootDir, SUBDIRECTORY_NAME);
        if (ideaCardsDir == null) {
            Log.e(TAG, "无法创建 IdeaCards 子目录");
            return false;
        }

        // 查找已有的同步文件，存在则覆写，不存在则新建
        DocumentFile syncFile = findFileByName(ideaCardsDir, SYNC_FILE_NAME);
        if (syncFile == null) {
            syncFile = ideaCardsDir.createFile("text/markdown", SYNC_FILE_NAME);
            if (syncFile == null) {
                Log.e(TAG, "创建同步文件失败");
                return false;
            }
            Log.d(TAG, "新建同步文件：" + SYNC_FILE_NAME);
        }

        // 覆写全部内容
        try (OutputStream os = context.getContentResolver().openOutputStream(syncFile.getUri(), "wt")) {
            if (os == null) {
                Log.e(TAG, "无法打开 OutputStream");
                return false;
            }
            os.write(markdown.getBytes(StandardCharsets.UTF_8));
            os.flush();
            Log.d(TAG, "同步文件覆写成功");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "写入异常", e);
            return false;
        }
    }

    // ═══════════════════════════════════════
    //  内部工具方法
    // ═══════════════════════════════════════

    /**
     * 在父目录中查找指定名称的子目录，不存在则创建。
     */
    private DocumentFile findOrCreateSubdirectory(DocumentFile parent, String name) {
        // 先查找已存在的子目录
        for (DocumentFile file : parent.listFiles()) {
            if (file.isDirectory() && name.equals(file.getName())) {
                return file;
            }
        }
        // 不存在则创建
        return parent.createDirectory(name);
    }

    /**
     * 在目录中查找指定名称的文件。
     */
    private DocumentFile findFileByName(DocumentFile directory, String fileName) {
        for (DocumentFile file : directory.listFiles()) {
            if (!file.isDirectory() && fileName.equals(file.getName())) {
                return file;
            }
        }
        return null;
    }

    private SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
