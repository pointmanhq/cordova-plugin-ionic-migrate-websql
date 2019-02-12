package com.migrate.android;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import android.util.Pair;

import com.appunite.leveldb.LevelDB;
import com.appunite.leveldb.LevelIterator;
import com.appunite.leveldb.Utils;
import com.appunite.leveldb.WriteBatch;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;

import org.apache.cordova.CordovaWebView;
import java.io.File;
import java.security.Key;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main class that is instantiated by cordova
 * Acts as a "bridge" between the SDK and the cordova layer
 * 
 * This plugin migrates WebSQL and localStorage from the old webview to the new webview
 * 
 * TODO
 * - Make `localhost:8080` a configurable setting
 * - Test if we can we remove old file:// keys?
 * - Properly handle exceptions? We have a catch-all at the moment that is dealt with in the `initialize` function
 * - migrating IndexedDB (may not be possible because of leveldb complexities)
 */
public class MigrateStorage extends CordovaPlugin {
    // Switch this value to enable debug mode
    private static final boolean DEBUG_MODE = false;

    private static final String TAG = "com.migrate.android";
    private static final String FILE_PROTOCOL = "file://";
    private static final String HTTP_LOCALHOST_PROTOCOL = "http://localhost:8080";

    private static final String WEBSQL_FILE_DIR_NAME = "file__0";
    private static final String WEBSQL_HTTP_LOCALHOST_DIR_NAME = "http_localhost_8080";


    private void logDebug(String message) {
        if(DEBUG_MODE) Log.d(TAG, message);
    }

    private String getRootPath() {
        Context context = cordova.getActivity().getApplicationContext();
        return context.getFilesDir().getAbsolutePath().replaceAll("/files", "");
    }

    private String getWebViewRootPath() {
        return this.getRootPath() + "/app_webview";
    }

    private String getLocalStorageRootPath() {
        return this.getWebViewRootPath() + "/Local Storage";
    }

    private String getWebSQLDatabasesPath() {
        return this.getWebViewRootPath() + "/databases";
    }

    private String getWebSQLReferenceDbPath() {
        return this.getWebSQLDatabasesPath() + "/Databases.db";
    }

    /**
     * Migrate localStorage from `file://` to `http://localhost:8080`
     *
     * TODO Test if we can we remove old file:// keys?
     *
     * @throws Exception - Can throw LevelDBException
     */
    private void migrateLocalStorage() throws Exception {
        this.logDebug("migrateLocalStorage: Migrating localStorage..");

        String levelDbPath = this.getLocalStorageRootPath() + "/leveldb";
        this.logDebug("migrateLocalStorage: levelDbPath: " + levelDbPath);

        File levelDbDir = new File(levelDbPath);
        if(!levelDbDir.isDirectory() || !levelDbDir.exists()) {
            this.logDebug("migrateLocalStorage: '" + levelDbPath + "' is not a directory or was not found; Exiting");
            return;
        }

        LevelDB db = new LevelDB(levelDbPath);

        if(db.exists(Utils.stringToBytes("META:" + HTTP_LOCALHOST_PROTOCOL))) {
            this.logDebug("migrateLocalStorage: Found 'META:" + HTTP_LOCALHOST_PROTOCOL+ "' key; Skipping migration");
            db.close();
            return;
        }

        // Yes, there is a typo here; `newInterator` 😔
        LevelIterator iterator = db.newInterator();

        // To update in bulk!
        WriteBatch batch = new WriteBatch();


        // 🔃 Loop through the keys and replace `file://` with `http://localhost:8080`
        logDebug("migrateLocalStorage: Starting replacements;");
        for(iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
            String key = Utils.bytesToString(iterator.key());
            byte[] value = iterator.value();

            if (key.contains(FILE_PROTOCOL)) {
                String newKey = key.replace(FILE_PROTOCOL, HTTP_LOCALHOST_PROTOCOL);

                logDebug("migrateLocalStorage: Changing key:" + key + " to '" + newKey + "'");

                // Add new key to db
                batch.putBytes(Utils.stringToBytes(newKey), value);
            } else {
                logDebug("migrateLocalStorage: Skipping key:" + key);
            }
        }

        // Commit batch to DB
        db.write(batch);

        iterator.close();
        db.close();

        this.logDebug("migrateLocalStorage: Successfully migrated localStorage..");
    }


    /**
     * Migrate WebSQL from using `file://` to `http://localhost:8080`
     *
     */
    private void migrateWebSQL() {
        this.logDebug("migrateWebSQL: Migrating WebSQL..");

        String databasesPath = this.getWebSQLDatabasesPath();
        String referenceDbPath = this.getWebSQLReferenceDbPath();

        if(!new File(referenceDbPath).exists()) {
            logDebug("migrateWebSQL: Databases.db was not found in path: '" + referenceDbPath + "'; Exiting..");
            return;
        }

        File originalWebSQLDir = new File(databasesPath + "/" + WEBSQL_FILE_DIR_NAME);
        File targetWebSQLDir = new File(databasesPath + "/" + WEBSQL_HTTP_LOCALHOST_DIR_NAME);

        if(!originalWebSQLDir.exists()) {
            logDebug("migrateWebSQL: original DB does not exist at '" + originalWebSQLDir.getAbsolutePath() + "'; Exiting..");
            return;
        }

        if(targetWebSQLDir.exists()) {
            logDebug("migrateWebSQL: target DB already exists at '" + targetWebSQLDir.getAbsolutePath() + "'; Skipping..");
            return;
        }

        logDebug("migrateWebSQL: Databases.db path: '" + referenceDbPath + "';");

        SQLiteDatabase db = SQLiteDatabase.openDatabase(referenceDbPath, null, 0);

        // Update reference DB to point to `localhost:8080`
        db.execSQL("UPDATE Databases SET origin = '?' WHERE origin = '?'", new String[] { WEBSQL_HTTP_LOCALHOST_DIR_NAME, WEBSQL_FILE_DIR_NAME });


        // rename `databases/file__0` dir to `databases/localhost_http_8080`
        boolean renamed = originalWebSQLDir.renameTo(targetWebSQLDir);

        if(!renamed) {
            logDebug("migrateWebSQL: Tried renaming '" + originalWebSQLDir.getAbsolutePath() + "' to '" + targetWebSQLDir.getAbsolutePath() + "' but failed; Exiting...");
            return;
        }
        
        db.close();

        this.logDebug("migrateWebSQL: Successfully migrated WebSQL..");
    }


    /**
     * Sets up the plugin interface
     *
     * @param cordova - cdvInterface that contains cordova goodies
     * @param webView - the webview that we're running
     */
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        try {
            super.initialize(cordova, webView);

            logDebug("Starting migration;");

            this.migrateLocalStorage();
            this.migrateWebSQL();

            logDebug("Migration completed;");
        } catch (Exception ex) {
            logDebug("Migration filed due to error: " + ex.getMessage());
        }
    }
}
