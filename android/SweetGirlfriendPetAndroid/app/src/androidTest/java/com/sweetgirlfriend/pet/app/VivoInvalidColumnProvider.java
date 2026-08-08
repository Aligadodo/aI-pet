package com.sweetgirlfriend.pet.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test-APK-only provider that reproduces vivo rejecting the optional last_modified column.
 *
 * Kept in Java so the provider's standalone test process does not depend on the target APK's
 * Kotlin runtime class loader.
 */
public final class VivoInvalidColumnProvider extends ContentProvider {
    public static final String AUTHORITY =
            "com.sweetgirlfriend.pet.test.vivo-invalid-column";
    public static final String DOCUMENT_PATH = "selected.petpack";
    public static final String DOCUMENT_NAME = "vivo-selected.petpack";
    public static final String MIME_TYPE = "application/vnd.sweetpet.petpack+zip";
    public static final String PAYLOAD_TEXT =
            "vivo-invalid-last-modified-regression-payload";
    public static final String METHOD_RESET = "reset";
    public static final String METHOD_STATS = "stats";
    public static final String KEY_PROJECTIONS = "projections";
    public static final String KEY_REJECTED_LAST_MODIFIED = "rejectedLastModified";
    public static final String PROJECTION_SEPARATOR = "\u001f";
    public static final Uri DOCUMENT_URI = new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(DOCUMENT_PATH)
            .build();

    private static final String[] DEFAULT_PROJECTION = {
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
    };

    private final List<List<String>> observedProjections = new ArrayList<>();
    private int rejectedLastModifiedQueries;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        requireDocumentUri(uri);
        String[] requested = projection != null ? projection : DEFAULT_PROJECTION;
        synchronized (observedProjections) {
            observedProjections.add(Arrays.asList(requested.clone()));
            if (Arrays.asList(requested).contains(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )) {
                rejectedLastModifiedQueries += 1;
                throw new IllegalArgumentException("Invalid column last modified");
            }
        }

        MatrixCursor cursor = new MatrixCursor(requested, 1);
        Object[] row = new Object[requested.length];
        for (int index = 0; index < requested.length; index += 1) {
            String column = requested[index];
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[index] = DOCUMENT_NAME;
            } else if (OpenableColumns.SIZE.equals(column)) {
                row[index] = (long) payloadBytes().length;
            } else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) {
                row[index] = MIME_TYPE;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireDocumentUri(uri);
        if (!"r".equals(mode)) {
            throw new IllegalArgumentException("Fake provider is read-only");
        }
        File sourceFile = new File(requireContextCache(), DOCUMENT_NAME);
        try {
            java.nio.file.Files.write(sourceFile.toPath(), payloadBytes());
        } catch (java.io.IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        return ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_RESET.equals(method)) {
            synchronized (observedProjections) {
                observedProjections.clear();
                rejectedLastModifiedQueries = 0;
                new File(requireContextCache(), DOCUMENT_NAME).delete();
            }
            return Bundle.EMPTY;
        }
        if (METHOD_STATS.equals(method)) {
            synchronized (observedProjections) {
                Bundle result = new Bundle();
                ArrayList<String> projections = new ArrayList<>();
                for (List<String> projection : observedProjections) {
                    projections.add(String.join(PROJECTION_SEPARATOR, projection));
                }
                result.putStringArrayList(KEY_PROJECTIONS, projections);
                result.putInt(KEY_REJECTED_LAST_MODIFIED, rejectedLastModifiedQueries);
                return result;
            }
        }
        return super.call(method, arg, extras);
    }

    @Override
    public String getType(Uri uri) {
        requireDocumentUri(uri);
        return MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Fake provider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Fake provider is read-only");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("Fake provider is read-only");
    }

    public static byte[] payloadBytes() {
        return PAYLOAD_TEXT.getBytes(StandardCharsets.UTF_8);
    }

    private File requireContextCache() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        return getContext().getCacheDir();
    }

    private void requireDocumentUri(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (!AUTHORITY.equals(uri.getAuthority())
                || segments.size() != 1
                || !DOCUMENT_PATH.equals(segments.get(0))) {
            throw new IllegalArgumentException("Unknown fake-provider URI: " + uri);
        }
    }
}
