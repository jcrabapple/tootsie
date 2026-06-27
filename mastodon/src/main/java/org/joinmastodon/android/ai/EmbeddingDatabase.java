package org.joinmastodon.android.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local SQLite store for embedding vectors.
 * Stores per-account embeddings for the user's favorites/boosts,
 * cached to avoid re-embedding on every timeline load.
 *
 * Schema:
 *   ai_embeddings(account_id, status_id, vector BLOB, source, updated_at)
 *     - vector: float[] stored as little-endian byte[]
 *     - source: "favorite" | "boost"
 */
public class EmbeddingDatabase extends SQLiteOpenHelper {

	private static final String DB_NAME = "tootsie_embeddings.db";
	private static final int DB_VERSION = 2;
	private static final String TABLE = "ai_embeddings";

	private static EmbeddingDatabase instance;

	public static synchronized EmbeddingDatabase getInstance(Context context) {
		if (instance == null) {
			instance = new EmbeddingDatabase(context.getApplicationContext());
		}
		return instance;
	}

	private EmbeddingDatabase(Context context) {
		super(context, DB_NAME, null, DB_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE " + TABLE + " ("
				+ "account_id TEXT NOT NULL, "
				+ "status_id TEXT NOT NULL, "
				+ "vector BLOB NOT NULL, "
				+ "source TEXT, "
				+ "updated_at INTEGER, "
				+ "PRIMARY KEY (account_id, status_id)"
				+ ")");
		db.execSQL("CREATE INDEX idx_embeddings_account ON " + TABLE + "(account_id)");

		// Feedback table for implicit signals
		db.execSQL("CREATE TABLE ai_feedback ("
				+ "account_id TEXT NOT NULL, "
				+ "status_id TEXT NOT NULL, "
				+ "action TEXT NOT NULL, "
				+ "timestamp INTEGER NOT NULL, "
				+ "PRIMARY KEY (account_id, status_id, action)"
				+ ")");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion < 2) {
			db.execSQL("CREATE TABLE IF NOT EXISTS ai_feedback ("
					+ "account_id TEXT NOT NULL, "
					+ "status_id TEXT NOT NULL, "
					+ "action TEXT NOT NULL, "
					+ "timestamp INTEGER NOT NULL, "
					+ "PRIMARY KEY (account_id, status_id, action)"
					+ ")");
		}
	}

	/** Store a single embedding vector. */
	public void putEmbedding(String accountId, String statusId, float[] vector, String source) {
		ContentValues cv = new ContentValues();
		cv.put("account_id", accountId);
		cv.put("status_id", statusId);
		cv.put("vector", floatsToBytes(vector));
		cv.put("source", source);
		cv.put("updated_at", System.currentTimeMillis());
		getWritableDatabase().insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
	}

	/** Store multiple embeddings in a single transaction. */
	public void putEmbeddings(String accountId, List<String> statusIds, List<float[]> vectors, String source) {
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		try {
			long now = System.currentTimeMillis();
			for (int i = 0; i < statusIds.size(); i++) {
				ContentValues cv = new ContentValues();
				cv.put("account_id", accountId);
				cv.put("status_id", statusIds.get(i));
				cv.put("vector", floatsToBytes(vectors.get(i)));
				cv.put("source", source);
				cv.put("updated_at", now);
				db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	/** Get all cached embeddings for an account. Returns statusId → vector map. */
	public Map<String, float[]> getEmbeddings(String accountId) {
		Map<String, float[]> result = new HashMap<>();
		Cursor c = getReadableDatabase().query(TABLE,
				new String[]{"status_id", "vector"},
				"account_id = ?", new String[]{accountId},
				null, null, null);
		try {
			while (c.moveToNext()) {
				String statusId = c.getString(0);
				float[] vector = bytesToFloats(c.getBlob(1));
				result.put(statusId, vector);
			}
		} finally {
			c.close();
		}
		return result;
	}

	/** Get status IDs that already have cached embeddings for an account. */
	public List<String> getEmbeddedStatusIds(String accountId) {
		List<String> ids = new ArrayList<>();
		Cursor c = getReadableDatabase().query(TABLE,
				new String[]{"status_id"},
				"account_id = ?", new String[]{accountId},
				null, null, null);
		try {
			while (c.moveToNext()) {
				ids.add(c.getString(0));
			}
		} finally {
			c.close();
		}
		return ids;
	}

	/** Delete all embeddings for an account (used when re-inferring topics). */
	public void clearEmbeddings(String accountId) {
		getWritableDatabase().delete(TABLE, "account_id = ?", new String[]{accountId});
	}

	/** Delete embeddings older than the given timestamp. */
	public void pruneOlderThan(String accountId, long timestampMs) {
		getWritableDatabase().delete(TABLE,
				"account_id = ? AND updated_at < ?",
				new String[]{accountId, String.valueOf(timestampMs)});
	}

	// ========== Vector serialization ==========

	private static byte[] floatsToBytes(float[] floats) {
		ByteBuffer buf = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		buf.asFloatBuffer().put(floats);
		return buf.array();
	}

	private static float[] bytesToFloats(byte[] bytes) {
		float[] floats = new float[bytes.length / 4];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats);
		return floats;
	}
}
