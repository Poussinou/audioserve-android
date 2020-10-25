package eu.zderadicka.audioserve.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.provider.BaseColumns
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import android.widget.Toast
import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.RecentContract.RecentEntry
import java.io.File


private const val LOG_TAG = "Bookmarks"

object RecentContract {
    const val PATH = "recent"
    const val CONTENT_AUTHORITY = "eu.zderadicka.audioserve"

    object RecentEntry {
        val CONTENT_URI = Uri.parse("content://$CONTENT_AUTHORITY/$PATH")
        val CONTENT_URI_LATEST = CONTENT_URI.buildUpon().appendPath("latest").build()
        const val TABLE_NAME = "recent"
        const val _ID = BaseColumns._ID
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_MEDIA_ID = "media_id"
        const val COLUMN_FOLDER_PATH = "folder_path"
        const val COLUMN_NAME = "name"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_POSITION = "position"
        const val COLUMN_DURATION = "duration"

        val DEFAULT_PROJECTION = arrayOf(_ID, COLUMN_TIMESTAMP, COLUMN_MEDIA_ID, COLUMN_FOLDER_PATH, COLUMN_NAME,
                COLUMN_DESCRIPTION, COLUMN_POSITION, COLUMN_DURATION)

    }
}

object BookmarkContract {
    const val PATH = "bookmark"
    const val CONTENT_AUTHORITY = "eu.zderadicka.audioserve"

    object BookmarkEntry {
        val CONTENT_URI: Uri = Uri.parse("content://$CONTENT_AUTHORITY/$PATH")
        const val TABLE_NAME = "bookmark"
        const val _ID = BaseColumns._ID
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_MEDIA_ID = "media_id"
        const val COLUMN_FOLDER_PATH = "folder_path"
        const val COLUMN_NAME = "name"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_POSITION = "position"
        const val COLUMN_CATEGORY = "category"


        val DEFAULT_PROJECTION = arrayOf(_ID, COLUMN_TIMESTAMP, COLUMN_MEDIA_ID, COLUMN_FOLDER_PATH, COLUMN_NAME,
                COLUMN_DESCRIPTION, COLUMN_POSITION, COLUMN_CATEGORY)

    }
}


private object UriType {
    const val Recents = 1
    const val RecentsLast = 2
    const val Bookmarks = 3
    const val BookmarkItem = 4

    private const val a = RecentContract.CONTENT_AUTHORITY
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    init {
        matcher.addURI(a, RecentContract.PATH, Recents)
        matcher.addURI(a, "${RecentContract.PATH}/latest", RecentsLast)
        matcher.addURI(a, BookmarkContract.PATH, Bookmarks)
        matcher.addURI(a, "${BookmarkContract.PATH}/#", BookmarkItem)
    }

    fun match(uri: Uri) = matcher.match(uri)
}

private val _f = UriType


internal const val RECENT_DATABASE_NAME = "recent.db"
private const val DATABASE_VERSION = 2

class BookmarksDbHelper(context: Context) : SQLiteOpenHelper(context, RECENT_DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        val query = """CREATE TABLE ${RecentEntry.TABLE_NAME} (
            ${RecentEntry._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${RecentEntry.COLUMN_TIMESTAMP} INTEGER NOT NULL,
            ${RecentEntry.COLUMN_MEDIA_ID} TEXT NOT NULL UNIQUE,
            ${RecentEntry.COLUMN_FOLDER_PATH} TEXT NOT NULL,
            ${RecentEntry.COLUMN_NAME} TEXT NOT NULL,
            ${RecentEntry.COLUMN_DESCRIPTION} TEXT,
            ${RecentEntry.COLUMN_POSITION} INTEGER,
            ${RecentEntry.COLUMN_DURATION} INTEGER
            )
        """
        db.execSQL(query)

        val query2 = """CREATE TABLE ${BookmarkContract.BookmarkEntry.TABLE_NAME} (
            ${BookmarkContract.BookmarkEntry._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP} INTEGER NOT NULL,
            ${BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID} TEXT NOT NULL UNIQUE,
            ${BookmarkContract.BookmarkEntry.COLUMN_FOLDER_PATH} TEXT NOT NULL,
            ${BookmarkContract.BookmarkEntry.COLUMN_NAME} TEXT NOT NULL,
            ${BookmarkContract.BookmarkEntry.COLUMN_DESCRIPTION} TEXT,
            ${BookmarkContract.BookmarkEntry.COLUMN_POSITION} INTEGER,
            ${BookmarkContract.BookmarkEntry.COLUMN_CATEGORY} TEXT NOT NULL
            )
        """
        db.execSQL(query2)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {

        if (oldVersion == 1) {
            val query2 = """CREATE TABLE IF NOT EXISTS ${BookmarkContract.BookmarkEntry.TABLE_NAME} (
            ${BookmarkContract.BookmarkEntry._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP} INTEGER NOT NULL,
            ${BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID} TEXT NOT NULL UNIQUE,
            ${BookmarkContract.BookmarkEntry.COLUMN_FOLDER_PATH} TEXT NOT NULL,
            ${BookmarkContract.BookmarkEntry.COLUMN_NAME} TEXT NOT NULL,
            ${BookmarkContract.BookmarkEntry.COLUMN_DESCRIPTION} TEXT,
            ${BookmarkContract.BookmarkEntry.COLUMN_POSITION} INTEGER,
            ${BookmarkContract.BookmarkEntry.COLUMN_CATEGORY} TEXT
            )
        """
            db!!.execSQL(query2)

        }
    }

}

internal const val MAX_RECENT_RECORDS = 100

class BookmarksProvider : ContentProvider() {
    lateinit var dbHelper: BookmarksDbHelper

    override fun insert(uri: Uri, values: ContentValues?): Uri?  =
            when (uri) {
                RecentEntry.CONTENT_URI -> insertRecent(uri, values)
                BookmarkContract.BookmarkEntry.CONTENT_URI -> insertBookmark(uri, values)
                else -> throw UnsupportedOperationException("Invalid content URI $uri")
            }


    private fun insertRecent(uri: Uri?, values: ContentValues?): Uri {
        val db = dbHelper.writableDatabase
        val path = values?.getAsString(RecentEntry.COLUMN_FOLDER_PATH)
        db.beginTransaction()
        try {
            val deleted = db.delete(RecentEntry.TABLE_NAME, "${RecentEntry.COLUMN_FOLDER_PATH}=?",
                    arrayOf(path))
            val c = db.query(RecentEntry.TABLE_NAME, arrayOf(RecentEntry._ID), null, null,
                    null, null, "${RecentEntry.COLUMN_TIMESTAMP} DESC", "${MAX_RECENT_RECORDS - 1},$MAX_RECENT_RECORDS")
            c.use {
                while (c.moveToNext()) {
                    db.delete(RecentEntry.TABLE_NAME, "${RecentEntry._ID}=?", arrayOf(c.getString(0)))
                }
            }

            val id = db.insert(RecentEntry.TABLE_NAME, null, values)
            db.setTransactionSuccessful()
            return uri!!.buildUpon().appendPath(id.toString()).build()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertBookmark(uri: Uri, values: ContentValues?): Uri {
        val db = dbHelper.writableDatabase
        var id = -1L;
        db.beginTransaction()
        try {
            val c = db.query(BookmarkContract.BookmarkEntry.TABLE_NAME, arrayOf(BookmarkContract.BookmarkEntry._ID),
                    "${BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID}=?",
                    arrayOf(values!!.getAsString(BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID)),
                    null, null, null)


            if (c.moveToFirst()) {
                id = c.getLong(0)
                db.update(BookmarkContract.BookmarkEntry.TABLE_NAME,
                        values,
                        "${BookmarkContract.BookmarkEntry._ID}=?",
                        arrayOf(id.toString()))

            } else {
                id = db.insert(BookmarkContract.BookmarkEntry.TABLE_NAME, null, values)
            }
            if (id < 0) {
                throw SQLiteException("Insert failed")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return uri.buildUpon().appendPath(id.toString()).build()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            when (uri) {
                RecentEntry.CONTENT_URI, RecentEntry.CONTENT_URI_LATEST ->
                    queryRecent(uri, projection, selection, selectionArgs, sortOrder)
                BookmarkContract.BookmarkEntry.CONTENT_URI ->
                    queryBookmark(uri, projection, selection, selectionArgs, sortOrder)
                else -> throw UnsupportedOperationException("Invalid content URI $uri")
            }


    private fun queryRecent(uri: Uri?, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {

        val limit = if (uri == RecentEntry.CONTENT_URI_LATEST) "1" else null

        val db = dbHelper.readableDatabase
        val c = db.query(RecentEntry.TABLE_NAME, projection
                ?: RecentEntry.DEFAULT_PROJECTION, selection, selectionArgs,
                null, null, RecentEntry.COLUMN_TIMESTAMP + " DESC", limit)
        c.setNotificationUri(context!!.contentResolver, uri)
        return c

    }

    private fun queryBookmark(uri: Uri?, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {

        val db = dbHelper.readableDatabase
        val c = db.query(BookmarkContract.BookmarkEntry.TABLE_NAME, projection
                ?: BookmarkContract.BookmarkEntry.DEFAULT_PROJECTION, selection, selectionArgs,
                null, null, BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP + " DESC", null)
        c.setNotificationUri(context!!.contentResolver, uri)
        return c
    }

    override fun onCreate(): Boolean {
        dbHelper = BookmarksDbHelper(context!!)
        return true
    }



    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =

            when (UriType.match(uri)) {
                UriType.BookmarkItem -> {
                    uri.lastPathSegment?.let{
                        delete_bookmark(it)
                    }?:0

                }

                else -> throw UnsupportedOperationException("Invalid content URI $uri")
            }


    private fun delete_bookmark(id: String): Int {
        val db = dbHelper.writableDatabase
        return db.delete(BookmarkContract.BookmarkEntry.TABLE_NAME,
                "${BookmarkContract.BookmarkEntry._ID}=?", arrayOf(id))
    }

    override fun getType(uri: Uri): String {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}

fun saveRecent(item: MediaBrowserCompat.MediaItem, context: Context) {
    val v = ContentValues()
    v.put(RecentContract.RecentEntry.COLUMN_TIMESTAMP, item.description.extras?.getLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP))
    v.put(RecentContract.RecentEntry.COLUMN_MEDIA_ID, item.mediaId)
    val path = File(item.mediaId).parent
    v.put(RecentContract.RecentEntry.COLUMN_FOLDER_PATH, path)
    v.put(RecentContract.RecentEntry.COLUMN_NAME, item.description.title.toString())
    v.put(RecentContract.RecentEntry.COLUMN_DESCRIPTION, item.description.subtitle.toString())
    v.put(RecentContract.RecentEntry.COLUMN_POSITION, item.description.extras?.getLong(METADATA_KEY_LAST_POSITION))
    v.put(RecentContract.RecentEntry.COLUMN_DURATION, item.description.extras?.getLong(METADATA_KEY_DURATION))

    context.contentResolver.insert(RecentEntry.CONTENT_URI, v)
}

fun saveBookmark(item: MediaBrowserCompat.MediaItem, context: Context) {
    val t = typeAndFolderPathFromMediaId(item.mediaId!!)
    if (t == null) {
        Log.w(LOG_TAG, "Unsupported item for bookmarks")
        return
    }
    val v = ContentValues()
    v.put(BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP, System.currentTimeMillis())
    v.put(BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID, item.mediaId)
    v.put(BookmarkContract.BookmarkEntry.COLUMN_FOLDER_PATH, t.second)
    v.put(BookmarkContract.BookmarkEntry.COLUMN_NAME, item.description.title.toString())
    v.put(BookmarkContract.BookmarkEntry.COLUMN_DESCRIPTION, item.description.subtitle.toString())
    v.put(BookmarkContract.BookmarkEntry.COLUMN_POSITION, item.description.extras?.getLong(METADATA_KEY_LAST_POSITION))
    v.put(BookmarkContract.BookmarkEntry.COLUMN_CATEGORY, t.first)

    context.contentResolver.insert(BookmarkContract.BookmarkEntry.CONTENT_URI, v)
}

fun deleteBookmark(id: Long, context: Context): Int {
    val uri = BookmarkContract.BookmarkEntry.CONTENT_URI.buildUpon().appendPath(id.toString()).build()
    return context.contentResolver.delete(uri, null, null)

}

fun getLastRecent(context: Context, cb: (MediaBrowserCompat.MediaItem?)-> Unit) {
    class Fetcher(val ctx:Context):AsyncTask<Unit,Unit,MediaBrowserCompat.MediaItem?>() {
        override fun onPostExecute(result: MediaBrowserCompat.MediaItem?) {
           cb(result)
        }

        override fun doInBackground(vararg params: Unit?): MediaBrowserCompat.MediaItem? {
            val list = getRecents(ctx,onlyLatest = true)
           return list.firstOrNull()
        }

    }

    val task = Fetcher(context)
    task.execute()
}

fun getRecents(context: Context, exceptPath: String? = null, onlyLatest: Boolean = false): List<MediaBrowserCompat.MediaItem> {
    var selection: String? = null
    var selectionArgs: Array<String?>? = null
    if (exceptPath != null) {
        selection = "${RecentEntry.COLUMN_FOLDER_PATH} != ?"
        selectionArgs = arrayOf(exceptPath)
    }
    val l = ArrayList<MediaBrowserCompat.MediaItem>()
    val c = context.contentResolver.query(
            if (onlyLatest) RecentEntry.CONTENT_URI_LATEST else RecentEntry.CONTENT_URI,
            null, selection, selectionArgs,
            null
    )
    c.use {
        while (c?.moveToNext() == true) {
            val extras = Bundle()
            val descBuilder = MediaDescriptionCompat.Builder()
                    .setMediaId(c.getString(c.getColumnIndex(RecentEntry.COLUMN_MEDIA_ID)))
                    .setTitle(c.getString(c.getColumnIndex(RecentEntry.COLUMN_NAME)))
                    .setSubtitle(c.getString(c.getColumnIndex(RecentEntry.COLUMN_DESCRIPTION)))

            extras.putLong(METADATA_KEY_DURATION, c.getLong(c.getColumnIndex(RecentEntry.COLUMN_DURATION)))
            extras.putLong(METADATA_KEY_LAST_POSITION, c.getLong(c.getColumnIndex(RecentEntry.COLUMN_POSITION)))
            extras.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP, c.getLong(c.getColumnIndex(RecentEntry.COLUMN_TIMESTAMP)))
            extras.putBoolean(METADATA_KEY_IS_BOOKMARK, true)

            descBuilder.setExtras(extras)
            val item = MediaBrowserCompat.MediaItem(descBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)

            l.add(item)
        }
    }

    return l
}

class BookMarkInsertTask(val ctx: Context):  AsyncTask<MediaBrowserCompat.MediaItem, Unit, Unit>() {
    override fun onPostExecute(result: Unit?) {
        super.onPostExecute(result)
        Toast.makeText(ctx, "Bookmark added", Toast.LENGTH_SHORT).show()
    }

    override fun doInBackground(vararg params: MediaBrowserCompat.MediaItem?) {
        saveBookmark(params[0]!!, ctx)
    }

}

class BookmarkDeleteTask(val ctx:Context, val cb: (Boolean)-> Unit) : AsyncTask<Long, Unit, Int>() {
    override fun doInBackground(vararg params: Long?):Int =
            deleteBookmark(params[0]!!, ctx)

    override fun onPostExecute(result: Int?) {
        result?.let {
            val res = if (it == 0) {
                Log.w(LOG_TAG, "Delete of bookmark failed")
                false
            } else true

            cb(res)

        }
    }

}