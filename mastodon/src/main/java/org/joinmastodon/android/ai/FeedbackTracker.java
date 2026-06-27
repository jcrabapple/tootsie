package org.joinmastodon.android.ai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.joinmastodon.android.MastodonApp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks user interactions with posts for implicit feedback.
 * Used to improve For You feed ranking over time.
 *
 * Actions:
 *   "favorite"  — user favorited the post (positive signal)
 *   "boost"     — user boosted the post (strong positive signal)
 *   "reply"     — user replied to the post (engagement signal)
 *   "dismiss"   — user tapped "Not interested" (negative signal)
 *   "skip"      — post was shown but user scrolled past (weak negative signal)
 */
public class FeedbackTracker {

	private static final String TABLE = "ai_feedback";

	/** Record that the user performed an action on a post. */
	public static void record(String accountId, String statusId, String action) {
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(MastodonApp.context);
		ContentValues cv = new ContentValues();
		cv.put("account_id", accountId);
		cv.put("status_id", statusId);
		cv.put("action", action);
		cv.put("timestamp", System.currentTimeMillis());
		db.getWritableDatabase().insertWithOnConflict(TABLE, null, cv,
				SQLiteDatabase.CONFLICT_REPLACE);
	}

	/** Get all status IDs the user dismissed (not interested). */
	public static Set<String> getDismissedIds(String accountId) {
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(MastodonApp.context);
		Set<String> ids = new HashSet<>();
		Cursor c = db.getReadableDatabase().rawQuery(
				"SELECT status_id FROM " + TABLE + " WHERE account_id = ? AND action = 'dismiss'",
				new String[]{accountId});
		try {
			while (c.moveToNext()) {
				ids.add(c.getString(0));
			}
		} finally {
			c.close();
		}
		return ids;
	}

	/** Get positive interaction IDs (favorites + boosts + replies). */
	public static Set<String> getPositiveIds(String accountId) {
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(MastodonApp.context);
		Set<String> ids = new HashSet<>();
		Cursor c = db.getReadableDatabase().rawQuery(
				"SELECT DISTINCT status_id FROM " + TABLE
						+ " WHERE account_id = ? AND action IN ('favorite', 'boost', 'reply')",
				new String[]{accountId});
		try {
			while (c.moveToNext()) {
				ids.add(c.getString(0));
			}
		} finally {
			c.close();
		}
		return ids;
	}

	/** Clear all feedback for an account. */
	public static void clearAll(String accountId) {
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(MastodonApp.context);
		db.getWritableDatabase().delete(TABLE, "account_id = ?", new String[]{accountId});
	}
}
