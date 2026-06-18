package com.example.ideacards.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import android.database.Cursor;

import com.example.ideacards.data.entity.NoteEntity;

import java.util.List;

@Dao
public interface NoteDao {

    @Insert
    long insert(NoteEntity note);

    @Delete
    int delete(NoteEntity note);

    @Update
    int update(NoteEntity note);

    @Query("SELECT * FROM notes WHERE status = :status")
    List<NoteEntity> getNotesByStatus(int status);

    @Query("SELECT * FROM notes WHERE id = :id")
    NoteEntity getNoteById(long id);

    @Query("SELECT * FROM notes")
    List<NoteEntity> getAllNotes();

    @Query("SELECT id AS _id, content, timestamp, status FROM notes")
    Cursor getAllNotesCursor();

    @Query("SELECT id AS _id, content, timestamp, status FROM notes WHERE id = :id")
    Cursor getNoteByIdCursor(long id);

    /**
     * 根据 ID 列表批量删除笔记，用于多选批量删除功能。
     */
    @Query("DELETE FROM notes WHERE id IN (:ids)")
    void deleteNotesByIds(List<Long> ids);

    /**
     * 按标签筛选笔记，用于归档页的标签过滤功能。
     * tag 为 null 时不会命中此查询（由调用方决定走 getAllNotes 还是此方法）。
     */
    @Query("SELECT * FROM notes WHERE tag = :tag ORDER BY timestamp DESC")
    List<NoteEntity> getNotesByTag(String tag);
}
