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
}
