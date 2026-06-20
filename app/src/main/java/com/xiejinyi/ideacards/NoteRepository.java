package com.xiejinyi.ideacards;

import android.content.Context;
import android.util.Log;

import com.xiejinyi.ideacards.data.dao.NoteDao;
import com.xiejinyi.ideacards.data.db.AppDatabase;
import com.xiejinyi.ideacards.data.entity.NoteEntity;

import java.util.Collections;
import java.util.List;

/**
 * 统一数据仓库：所有笔记的保存操作都通过此类完成。
 * <p>
 * 职责：
 * ① 写入 Room 数据库
 * ② 若 Obsidian 已绑定，自动全量同步到本地库
 * <p>
 * 调用方（MainActivity / QuickInputActivity / 未来任何入口）
 * 只需一行：{@code NoteRepository.getInstance(ctx).saveNote(note)}
 */
public class NoteRepository {

    private static final String TAG = "NoteRepository";

    private static volatile NoteRepository instance;

    private final Context appContext;
    private final NoteDao noteDao;

    private NoteRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.noteDao = AppDatabase.getInstance(appContext).noteDao();
    }

    /** 获取单例（首次调用时传入任意 Context 即可） */
    public static NoteRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (NoteRepository.class) {
                if (instance == null) {
                    instance = new NoteRepository(context);
                }
            }
        }
        return instance;
    }

    /** 获取 DAO，供查询/删除等只读操作使用 */
    public NoteDao getDao() {
        return noteDao;
    }

    /**
     * 保存笔记（双轨写入）。
     * <p>
     * 必须在子线程调用（内部执行数据库插入和 Obsidian 同步，
     * 均为阻塞操作）。
     *
     * @param note 已构建好的笔记实体
     */
    public void saveNote(NoteEntity note) {
        // ① 入库
        noteDao.insert(note);

        // ② 全量同步到 Obsidian（若已绑定），失败不影响主流程
        ObsidianSyncManager obsidian = ObsidianSyncManager.getInstance();
        if (obsidian.isConnected(appContext)) {
            try {
                List<NoteEntity> allNotes = noteDao.getAllNotes();
                if (!allNotes.isEmpty()) {
                    String markdown = MarkdownUtils.buildExportMarkdown(
                            allNotes, Collections.emptyList());
                    obsidian.syncAllNotes(appContext, markdown);
                }
            } catch (Exception e) {
                Log.w(TAG, "Obsidian 同步失败（不影响笔记保存）", e);
            }
        }
    }
}
