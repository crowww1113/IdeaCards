package com.xiejinyi.ideacards.data.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;

import com.xiejinyi.ideacards.data.db.AppDatabase;
import com.xiejinyi.ideacards.data.entity.NoteEntity;

public class NoteContentProvider extends ContentProvider {

    private static final int NOTES = 1;
    private static final int NOTE_ID = 2;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(NoteContract.AUTHORITY, "notes", NOTES);
        uriMatcher.addURI(NoteContract.AUTHORITY, "notes/#", NOTE_ID);
    }

    private AppDatabase db;

    @Override
    public boolean onCreate() {
        db = AppDatabase.getInstance(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Cursor cursor;
        switch (uriMatcher.match(uri)) {
            case NOTE_ID:
                long id = Long.parseLong(uri.getLastPathSegment());
                cursor = db.noteDao().getNoteByIdCursor(id);
                break;
            case NOTES:
            default:
                cursor = db.noteDao().getAllNotesCursor();
                break;
        }
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (uriMatcher.match(uri) != NOTES) {
            throw new IllegalArgumentException("Unsupported URI for insert: " + uri);
        }
        NoteEntity note = new NoteEntity();
        if (values.containsKey(NoteContract.COLUMN_CONTENT)) {
            note.setContent(values.getAsString(NoteContract.COLUMN_CONTENT));
        }
        if (values.containsKey(NoteContract.COLUMN_TIMESTAMP)) {
            note.setTimestamp(values.getAsLong(NoteContract.COLUMN_TIMESTAMP));
        }
        if (values.containsKey(NoteContract.COLUMN_STATUS)) {
            note.setStatus(values.getAsInteger(NoteContract.COLUMN_STATUS));
        }
        long newId = db.noteDao().insert(note);
        getContext().getContentResolver().notifyChange(uri, null);
        return Uri.parse("content://" + NoteContract.AUTHORITY + "/notes/" + newId);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count;
        switch (uriMatcher.match(uri)) {
            case NOTE_ID:
                long id = Long.parseLong(uri.getLastPathSegment());
                NoteEntity note = db.noteDao().getNoteById(id);
                if (note != null) {
                    count = db.noteDao().delete(note);
                } else {
                    count = 0;
                }
                break;
            case NOTES:
            default:
                throw new UnsupportedOperationException("Bulk delete not supported");
        }
        if (count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        int count;
        switch (uriMatcher.match(uri)) {
            case NOTE_ID:
                long id = Long.parseLong(uri.getLastPathSegment());
                NoteEntity note = db.noteDao().getNoteById(id);
                if (note == null) {
                    count = 0;
                } else {
                    if (values.containsKey(NoteContract.COLUMN_CONTENT)) {
                        note.setContent(values.getAsString(NoteContract.COLUMN_CONTENT));
                    }
                    if (values.containsKey(NoteContract.COLUMN_TIMESTAMP)) {
                        note.setTimestamp(values.getAsLong(NoteContract.COLUMN_TIMESTAMP));
                    }
                    if (values.containsKey(NoteContract.COLUMN_STATUS)) {
                        note.setStatus(values.getAsInteger(NoteContract.COLUMN_STATUS));
                    }
                    count = db.noteDao().update(note);
                }
                break;
            case NOTES:
            default:
                throw new UnsupportedOperationException("Bulk update not supported");
        }
        if (count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public String getType(Uri uri) {
        switch (uriMatcher.match(uri)) {
            case NOTES:
                return "vnd.android.cursor.dir/" + NoteContract.AUTHORITY + ".notes";
            case NOTE_ID:
                return "vnd.android.cursor.item/" + NoteContract.AUTHORITY + ".notes";
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }
}
