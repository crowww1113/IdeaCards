package com.xiejinyi.ideacards.data.provider;

import android.net.Uri;

public final class NoteContract {

    public static final String AUTHORITY = "com.xiejinyi.ideacards.provider";

    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/notes");

    public static final String _ID = "_id";
    public static final String COLUMN_CONTENT = "content";
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_STATUS = "status";

    private NoteContract() {
    }
}
