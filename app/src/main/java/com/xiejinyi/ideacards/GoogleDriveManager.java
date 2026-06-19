package com.xiejinyi.ideacards;

import android.util.Log;

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Google Drive 云端同步管理器（单例）。
 * <p>
 * 负责：
 * 1. 通过已登录的 Google 账户凭证构建 Drive 服务实例。
 * 2. 智能覆盖写入：若云端已存在 IdeaCards_Sync.md 则更新，否则新建。
 * <p>
 * 权限范围：{@code DriveScopes.DRIVE_FILE}（仅访问本应用创建的文件）。
 */
public class GoogleDriveManager {

    private static final String TAG = "GoogleDriveManager";

    /** 云端同步文件名 */
    private static final String SYNC_FILE_NAME = "IdeaCards_Sync.md";

    /** Drive API 使用的应用 MIME 类型 */
    private static final String MIME_TYPE_MARKDOWN = "text/markdown";

    private static volatile GoogleDriveManager instance;

    private Drive driveService;

    private GoogleDriveManager() {}

    /** 获取单例实例 */
    public static GoogleDriveManager getInstance() {
        if (instance == null) {
            synchronized (GoogleDriveManager.class) {
                if (instance == null) {
                    instance = new GoogleDriveManager();
                }
            }
        }
        return instance;
    }

    /**
     * 根据已登录账户的凭证初始化 Drive 服务。
     * <p>
     * 必须在任何 Drive 操作之前调用。使用
     * {@link GoogleAccountCredential#usingOAuth2} 构建，
     * 内部自动处理 token 刷新。
     *
     * @param credential 已绑定 Google 账户的 OAuth2 凭证
     */
    public void initDriveService(GoogleAccountCredential credential) {
        this.driveService = new Drive.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("IdeaCards")
                .build();
        Log.d(TAG, "Drive 服务初始化完成");
    }

    /** 当前 Drive 服务是否已初始化 */
    public boolean isInitialized() {
        return driveService != null;
    }

    /**
     * 智能覆盖写入：将 Markdown 文本同步到 Google Drive。
     * <p>
     * 流程：查询云端是否已存在同名文件 → 存在则覆盖更新，不存在则新建。
     * 所有操作在当前线程执行，调用方需确保在子线程中调用。
     *
     * @param localFile 本地已写入 Markdown 内容的临时文件
     * @return true 表示同步成功，false 表示失败
     * @throws IOException 当 Drive API 调用失败时抛出（包括需要用户重新授权的情况）
     */
    public boolean uploadOrUpdateFile(java.io.File localFile) throws IOException {
        if (driveService == null) {
            Log.e(TAG, "Drive 服务未初始化，请先调用 initDriveService()");
            return false;
        }

        // 查重：在用户 Drive 中搜索同名且未删除的文件
        String query = "name = '" + SYNC_FILE_NAME + "' and trashed = false";
        FileList listResult = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();

        List<File> existingFiles = listResult.getFiles();
        FileContent mediaContent = new FileContent(MIME_TYPE_MARKDOWN, localFile);

        if (existingFiles != null && !existingFiles.isEmpty()) {
            // 文件已存在 → 覆盖更新
            String fileId = existingFiles.get(0).getId();
            Log.d(TAG, "云端文件已存在，fileId=" + fileId + "，执行覆盖更新");
            File updatedFile = driveService.files().update(fileId, null, mediaContent).execute();
            Log.d(TAG, "更新成功，fileId=" + updatedFile.getId());
        } else {
            // 文件不存在 → 新建
            Log.d(TAG, "云端无同名文件，创建新文件");
            File fileMetadata = new File();
            fileMetadata.setName(SYNC_FILE_NAME);

            File createdFile = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, name")
                    .execute();
            Log.d(TAG, "创建成功，fileId=" + createdFile.getId());
        }

        return true;
    }
}
