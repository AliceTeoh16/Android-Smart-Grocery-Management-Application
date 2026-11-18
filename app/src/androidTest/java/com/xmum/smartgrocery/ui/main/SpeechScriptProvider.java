package com.xmum.smartgrocery.ui.main;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Test-only IPC configuration; uses Android classes available in the standalone test APK. */
public final class SpeechScriptProvider extends ContentProvider {
    static String text = "";
    static String language = "en-US";
    static int confidence = 3;
    static int switchResult = 1;
    static boolean reportLanguage = true;
    static int error = 0;

    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        text = extras.getString("text");
        language = extras.getString("language");
        confidence = extras.getInt("confidence", 3);
        switchResult = extras.getInt("switch_result", 1);
        reportLanguage = extras.getBoolean("report_language", true);
        error = extras.getInt("error", 0);
        Bundle result = new Bundle();
        result.putBoolean("ready", true);
        return result;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
