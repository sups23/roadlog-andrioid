package com.example.roadlog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** Test-only document destination. Its standalone process must not depend on Kotlin runtime. */
public class TestExportDocumentProvider extends ContentProvider {
    public static final String AUTHORITY = "com.example.roadlog.test.export";
    public static final String ARCHIVE_NAME = "activity-export.zip";
    public static final Uri documentUri = Uri.parse("content://" + AUTHORITY + "/archive.zip");

    @Override
    public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) { return "application/zip"; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection : new String[]{"_display_name", "_size"};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if ("_display_name".equals(columns[i])) values[i] = ARCHIVE_NAME;
            else if ("_size".equals(columns[i])) values[i] = archiveFile().length();
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("The export test provider accepts only a fixed document URI");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!documentUri.equals(uri)) throw new IllegalArgumentException("Unknown test export URI: " + uri);
        File file = archiveFile();
        if (!file.exists()) return 0;
        if (!file.delete()) throw new IllegalStateException("Could not clear test export archive");
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("The export test provider does not support update");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!documentUri.equals(uri)) throw new FileNotFoundException("Unknown test export URI: " + uri);
        if ("r".equals(mode)) {
            return ParcelFileDescriptor.open(archiveFile(), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!"w".equals(mode) && !"wt".equals(mode)) {
            throw new FileNotFoundException("Unsupported test export mode: " + mode);
        }
        File file = archiveFile();
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
    }

    private File archiveFile() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context is unavailable");
        return new File(context.getFilesDir(), ARCHIVE_NAME);
    }

    public static void clear(Context context) {
        context.getContentResolver().delete(documentUri, null, null);
    }

    public static byte[] readArchive(Context context) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(documentUri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new FileNotFoundException("Could not read test export archive");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
