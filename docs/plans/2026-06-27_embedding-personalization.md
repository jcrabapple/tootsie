# Embedding-Based AI Personalization

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Replace the expensive LLM post-filtering step with embedding-based cosine similarity ranking, using OpenRouter's embeddings API (same URL/key users already have).

**Architecture:** Favorites/boosts are embedded once and cached in a local SQLite database. On timeline load, candidate posts are fetched from Mastodon, batch-embedded via the same OpenRouter provider, and ranked by cosine similarity against the user's cached interest vectors. No new dependencies — uses Android's built-in SQLite and the existing OkHttp client.

**Tech Stack:** Java, OkHttp 3.14.9, Gson 2.8.9, Android SQLite (built-in), OpenRouter Embeddings API

**Cost comparison:**

| | Current (LLM filter) | New (embedding ranking) |
|---|---|---|
| Per timeline load | ~$0.02-0.05 | ~$0.0002-0.001 |
| Latency | 2-5 seconds | <500ms |
| API calls | 1 chat completion | 1 embedding batch |

---

## Task 1: Create EmbeddingDatabase (SQLite helper)

**Objective:** Create the SQLite database that stores embedding vectors locally, keyed by account and status ID.

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/ai/EmbeddingDatabase.java`

**Step 1: Create the SQLiteOpenHelper class**

```java
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
    private static final int DB_VERSION = 1;
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations go here
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
```

**Step 2: Verify it compiles**

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:compileGithubDebugJavaWithJavac 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL (or unrelated warnings, no errors from EmbeddingDatabase.java)

**Step 3: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/EmbeddingDatabase.java
git commit -m "feat(ai): add EmbeddingDatabase SQLite helper for vector storage"
```

---

## Task 2: Create EmbeddingClient (API calls)

**Objective:** Create the client that calls OpenRouter's `/embeddings` endpoint and returns float vectors. Reuses the same OkHttp and auth patterns as AIPersonalizationManager.

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/ai/EmbeddingClient.java`

**Step 1: Create the EmbeddingClient class**

```java
package org.joinmastodon.android.ai;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Calls OpenRouter's /embeddings endpoint to convert text into vectors.
 * Uses the same API URL and key as the chat completions (just a different endpoint).
 *
 * Default model: openai/text-embedding-3-small (1536 dims, $0.02/1M tokens)
 * A 200-token Mastodon post costs ~$0.000004 to embed.
 */
public class EmbeddingClient {

    private static final String TAG = "EmbeddingClient";
    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // Default embedding dimensions for text-embedding-3-small
    public static final int DEFAULT_DIMENSIONS = 1536;

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * Embed a single text string. Returns the vector as a float array.
     * Blocks — call from a background thread.
     */
    public static float[] embed(String apiUrl, String apiKey, String model, String text) throws IOException {
        List<float[]> results = embedBatch(apiUrl, apiKey, model, List.of(text));
        return results.get(0);
    }

    /**
     * Embed multiple texts in a single API call. Returns vectors in the same order as input.
     * Blocks — call from a background thread.
     *
     * OpenRouter supports batching up to 2048 inputs per request.
     * For Mastodon posts (~200 tokens each), a batch of 60 posts = ~12K tokens = trivial.
     */
    public static List<float[]> embedBatch(String apiUrl, String apiKey, String model, List<String> texts) throws IOException {
        if (texts.isEmpty()) return new ArrayList<>();

        String embeddingsUrl = apiUrl.endsWith("/")
                ? apiUrl + "embeddings"
                : apiUrl + "/embeddings";

        // Build request body
        JsonObject body = new JsonObject();
        body.addProperty("model", model != null ? model : "openai/text-embedding-3-small");

        JsonArray inputArray = new JsonArray();
        for (String text : texts) {
            inputArray.add(text);
        }
        body.add("input", inputArray);

        Log.d(TAG, "Embedding batch of " + texts.size() + " texts via " + embeddingsUrl);

        Request request = new Request.Builder()
                .url(embeddingsUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                Log.e(TAG, "Embedding error " + response.code() + ": " + errorBody);
                throw new IOException("Embedding API error " + response.code() + ": " + response.message());
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            return parseEmbeddingResponse(responseBody, texts.size());
        }
    }

    /**
     * Parse the OpenAI-compatible embeddings response.
     * Response format: { "data": [{ "embedding": [0.1, 0.2, ...], "index": 0 }, ...] }
     */
    private static List<float[]> parseEmbeddingResponse(String responseBody, int expectedCount) {
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray data = json.getAsJsonArray("data");

        // Pre-fill with nulls so we can place by index
        List<float[]> results = new ArrayList<>(expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            results.add(null);
        }

        for (JsonElement el : data) {
            JsonObject item = el.getAsJsonObject();
            int index = item.has("index") ? item.get("index").getAsInt() : 0;
            JsonArray embeddingArray = item.getAsJsonArray("embedding");

            float[] vector = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                vector[i] = embeddingArray.get(i).getAsFloat();
            }
            results.set(index, vector);
        }

        // Check for missing entries
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                Log.w(TAG, "Missing embedding at index " + i + ", returning zero vector");
                results.set(i, new float[DEFAULT_DIMENSIONS]);
            }
        }

        return results;
    }

    /**
     * Compute cosine similarity between two vectors.
     * Returns a value between -1.0 and 1.0 (higher = more similar).
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions mismatch: " + a.length + " vs " + b.length);
        }
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denominator == 0 ? 0 : dot / denominator;
    }
}
```

Note: Add the missing import at the top:
```java
import com.google.gson.JsonParser;
```

**Step 2: Verify it compiles**

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:compileGithubDebugJavaWithJavac 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/EmbeddingClient.java
git commit -m "feat(ai): add EmbeddingClient for OpenRouter embeddings API"
```

---

## Task 3: Add embedding model preference to AccountLocalPreferences

**Objective:** Store the user's chosen embedding model alongside their existing AI config.

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/api/session/AccountLocalPreferences.java`

**Step 1: Add the new field**

After line 55 (`public long aiTopicsLastUpdated;`), add:

```java
	public String aiEmbeddingModel;      // embedding model, e.g. "openai/text-embedding-3-small"
```

**Step 2: Load from SharedPreferences**

After line 97 (`aiTopicsLastUpdated=prefs.getLong("aiTopicsLastUpdated", 0);`), add:

```java
		aiEmbeddingModel=prefs.getString("aiEmbeddingModel", "openai/text-embedding-3-small");
```

**Step 3: Save to SharedPreferences**

After line 138 (`prefs.putLong("aiTopicsLastUpdated", aiTopicsLastUpdated)`), add:

```java
			.putString("aiEmbeddingModel", aiEmbeddingModel)
```

**Step 4: Verify it compiles**

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:compileGithubDebugJavaWithJavac 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/api/session/AccountLocalPreferences.java
git commit -m "feat(ai): add aiEmbeddingModel preference for embedding model selection"
```

---

## Task 4: Add embed-and-cache methods to AIPersonalizationManager

**Objective:** Add methods to embed the user's favorites/boosts and cache the vectors in SQLite. These are called after topic inference and on first load.

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ai/AIPersonalizationManager.java`

**Step 1: Add the embedFavorites method**

Add these methods after the `filterPosts` method (around line 353). These replace `filterPosts` as the core ranking mechanism.

```java
	// ========== Embedding-Based Ranking ==========

	/**
	 * Embed the user's favorites and boosts and cache vectors in SQLite.
	 * Called after topic inference or when embeddings are stale.
	 * Only embeds posts that don't already have cached embeddings.
	 * Callbacks run on UI thread.
	 */
	public static void embedFavorites(String accountID, List<Status> favorites, List<Status> boosts,
									  Consumer<Integer> onSuccess, Consumer<String> onError) {
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountID);
		if (session == null) {
			uiHandler.post(() -> onError.accept("No active session"));
			return;
		}

		AccountLocalPreferences prefs = session.getLocalPreferences();
		String apiKey = prefs.aiApiKey;
		String apiUrl = prefs.aiApiUrl;
		String model = prefs.aiEmbeddingModel;

		if (apiKey == null || apiKey.isBlank()) {
			uiHandler.post(() -> onError.accept("No API key configured"));
			return;
		}

		// Combine favorites and boosts, strip HTML
		List<Status> allPosts = new ArrayList<>();
		List<String> sources = new ArrayList<>();
		for (Status s : favorites) {
			Status actual = s.reblog != null ? s.reblog : s;
			allPosts.add(actual);
			sources.add("favorite");
		}
		for (Status s : boosts) {
			Status actual = s.reblog != null ? s.reblog : s;
			allPosts.add(actual);
			sources.add("boost");
		}

		if (allPosts.isEmpty()) {
			uiHandler.post(() -> onSuccess.accept(0));
			return;
		}

		// Check which posts already have cached embeddings
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(
				AccountSessionManager.getInstance().getApplication());
		List<String> cachedIds = db.getEmbeddedStatusIds(accountID);
		Set<String> cachedSet = new HashSet<>(cachedIds);

		// Filter to only posts that need embedding
		List<Status> toEmbed = new ArrayList<>();
		List<String> toEmbedSources = new ArrayList<>();
		List<String> toEmbedIds = new ArrayList<>();
		for (int i = 0; i < allPosts.size(); i++) {
			Status s = allPosts.get(i);
			if (!cachedSet.contains(s.id)) {
				toEmbed.add(s);
				toEmbedSources.add(sources.get(i));
				toEmbedIds.add(s.id);
			}
		}

		if (toEmbed.isEmpty()) {
			uiHandler.post(() -> onSuccess.accept(cachedIds.size()));
			return;
		}

		// Build text for embedding (strip HTML)
		List<String> texts = new ArrayList<>();
		for (Status s : toEmbed) {
			texts.add(stripHtml(s.content));
		}

		// Embed on background thread
		MastodonAPIController.runInBackground(() -> {
			try {
				List<float[]> vectors = EmbeddingClient.embedBatch(apiUrl, apiKey, model, texts);

				// Cache in SQLite
				db.putEmbeddings(accountID, toEmbedIds, vectors, toEmbedSources.get(0));

				int totalCached = cachedIds.size() + vectors.size();
				Log.d(TAG, "Embedded " + toEmbed.size() + " posts, " + totalCached + " total cached");
				uiHandler.post(() -> onSuccess.accept(totalCached));
			} catch (Exception e) {
				Log.e(TAG, "Embedding favorites failed", e);
				uiHandler.post(() -> onError.accept(e.getMessage() != null ? e.getMessage() : "Embedding failed"));
			}
		});
	}

	/**
	 * Rank candidate posts by similarity to the user's cached interest embeddings.
	 * Replaces the old LLM-based filterPosts() method.
	 *
	 * For each candidate, computes max cosine similarity against all cached favorites.
	 * Returns candidates sorted by similarity score (highest first).
	 * Callbacks run on UI thread.
	 */
	public static void rankCandidates(String accountID, List<Status> candidates,
									  Consumer<List<RankedPost>> onSuccess, Consumer<String> onError) {
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountID);
		if (session == null) {
			uiHandler.post(() -> onError.accept("No active session"));
			return;
		}

		AccountLocalPreferences prefs = session.getLocalPreferences();
		String apiKey = prefs.aiApiKey;
		String apiUrl = prefs.aiApiUrl;
		String model = prefs.aiEmbeddingModel;

		if (apiKey == null || apiKey.isBlank()) {
			// No API key — return candidates unranked (by recency)
			List<RankedPost> unranked = new ArrayList<>();
			for (Status s : candidates) {
				unranked.add(new RankedPost(s, 0f));
			}
			uiHandler.post(() -> onSuccess.accept(unranked));
			return;
		}

		// Load cached interest vectors
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(
				AccountSessionManager.getInstance().getApplication());
		Map<String, float[]> interestVectors = db.getEmbeddings(accountID);

		if (interestVectors.isEmpty()) {
			// No cached embeddings — return unranked
			List<RankedPost> unranked = new ArrayList<>();
			for (Status s : candidates) {
				unranked.add(new RankedPost(s, 0f));
			}
			uiHandler.post(() -> onSuccess.accept(unranked));
			return;
		}

		// Build text for candidates
		List<String> texts = new ArrayList<>();
		for (Status s : candidates) {
			Status actual = s.reblog != null ? s.reblog : s;
			texts.add(stripHtml(actual.content));
		}

		// Embed candidates on background thread
		MastodonAPIController.runInBackground(() -> {
			try {
				List<float[]> candidateVectors = EmbeddingClient.embedBatch(apiUrl, apiKey, model, texts);

				// Compute similarity: for each candidate, find max similarity to any interest
				List<RankedPost> ranked = new ArrayList<>();
				for (int i = 0; i < candidates.size(); i++) {
					float maxSim = 0f;
					float[] cVec = candidateVectors.get(i);
					if (cVec != null) {
						for (float[] iVec : interestVectors.values()) {
							float sim = EmbeddingClient.cosineSimilarity(cVec, iVec);
							if (sim > maxSim) maxSim = sim;
						}
					}
					ranked.add(new RankedPost(candidates.get(i), maxSim));
				}

				// Sort by similarity descending
				ranked.sort((a, b) -> Float.compare(b.similarity, a.similarity));

				uiHandler.post(() -> onSuccess.accept(ranked));
			} catch (Exception e) {
				Log.e(TAG, "Candidate ranking failed", e);
				// On failure, return unranked (graceful degradation)
				List<RankedPost> unranked = new ArrayList<>();
				for (Status s : candidates) {
					unranked.add(new RankedPost(s, 0f));
				}
				uiHandler.post(() -> onSuccess.accept(unranked));
			}
		});
	}

	/**
	 * A post with its computed similarity score.
	 */
	public static class RankedPost {
		public final Status status;
		public final float similarity; // 0.0 to 1.0

		public RankedPost(Status status, float similarity) {
			this.status = status;
			this.similarity = similarity;
		}
	}
```

**Step 2: Verify it compiles**

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:compileGithubDebugJavaWithJavac 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/AIPersonalizationManager.java
git commit -m "feat(ai): add embedFavorites() and rankCandidates() to replace LLM filtering"
```

---

## Task 5: Rewrite PersonalTimelineFragment to use embedding ranking

**Objective:** Replace the current "keyword search → LLM filter" flow with "keyword search → embed rank". The fragment now calls `rankCandidates()` instead of `filterPosts()`.

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/discover/PersonalTimelineFragment.java`

**Step 1: Replace the filterPosts call with rankCandidates**

In `onAllTopicsComplete()`, replace lines 168-176 (the LLM filtering block):

```java
		// If API key is configured, refine in background via LLM
		AccountLocalPreferences prefs = getLocalPrefs();
		if (prefs.aiApiKey != null && !prefs.aiApiKey.isBlank()) {
			AIPersonalizationManager.filterPosts(accountID, result, topicMap,
					this::applyFilteredResults,
					error -> {
						// Filtering failed — keep the unfiltered results, no action needed
					});
		}
```

With:

```java
		// If API key is configured, rank by embedding similarity
		AccountLocalPreferences prefs = getLocalPrefs();
		if (prefs.aiApiKey != null && !prefs.aiApiKey.isBlank()) {
			AIPersonalizationManager.rankCandidates(accountID, result,
					this::applyRankedResults,
					error -> {
						// Ranking failed — keep unranked results
					});
		}
```

**Step 2: Replace applyFilteredResults with applyRankedResults**

Replace the `applyFilteredResults` method (lines 179-193):

```java
	private void applyFilteredResults(List<Status> filtered) {
		if (getActivity() == null || filtered.isEmpty()) return;

		// Only update if the filtered list is different (shorter) than what's currently shown
		if (filtered.size() >= data.size()) return;

		// Replace displayed data with filtered results
		data.clear();
		displayItems.clear();
		for (Status s : filtered) {
			data.add(s);
			displayItems.addAll(buildDisplayItems(s));
		}
		adapter.notifyDataSetChanged();
	}
```

With:

```java
	private void applyRankedResults(List<AIPersonalizationManager.RankedPost> ranked) {
		if (getActivity() == null || ranked.isEmpty()) return;

		// Replace displayed data with ranked results
		data.clear();
		displayItems.clear();
		for (AIPersonalizationManager.RankedPost rp : ranked) {
			data.add(rp.status);
			displayItems.addAll(buildDisplayItems(rp.status));
		}
		adapter.notifyDataSetChanged();
	}
```

**Step 3: Verify it compiles**

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:compileGithubDebugJavaWithJavac 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/discover/PersonalTimelineFragment.java
git commit -m "feat(ai): replace LLM post filtering with embedding-based ranking"
```

---

## Task 6: Embed favorites after topic inference

**Objective:** After inferring topics, also embed the favorites/boosts so the vectors are cached before the first timeline load. This avoids a cold-start delay.

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ai/AIPersonalizationManager.java`

**Step 1: Call embedFavorites after inferTopics succeeds**

In the `callLLMForTopics` method, after the topics are saved (around line 246, after `prefs.save();`), add the embedding call:

```java
				// Embed favorites for vector ranking (fire-and-forget)
				embedFavorites(accountID, favorites, boosts,
						count -> Log.d(TAG, "Cached " + count + " embeddings after topic inference"),
						error -> Log.w(TAG, "Embedding after inference failed: " + error));
```

This should go right before the `uiHandler.post(() -> onSuccess.accept(result));` line.

**Step 2: Clear stale embeddings when re-inferring**

In `callLLMForTopics`, before building the user content JSON (around line 205), add:

```java
		// Clear old embeddings so they get re-embedded with fresh vectors
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(
				AccountSessionManager.getInstance().getApplication());
		db.clearEmbeddings(accountID);
```

**Step 3: Verify it compiles**

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:compileGithubDebugJavaWithJavac 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/AIPersonalizationManager.java
git commit -m "feat(ai): auto-embed favorites after topic inference"
```

---

## Task 7: Add embedding model setting to Settings UI

**Objective:** Let users configure which embedding model to use, defaulting to `openai/text-embedding-3-small`. This reuses the existing model picker pattern.

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/settings/SettingsAIPersonalizationFragment.java`

**Step 1: Add the new field and list item**

After the `modelItem` field declaration (line 42), add:

```java
	private ListItem<Void> embeddingModelItem;
```

**Step 2: Add the list item in rebuildItems()**

After the model item is added (after line 117), add:

```java
		// Embedding Model
		String embeddingModelDisplay = prefs.aiEmbeddingModel != null
				? prefs.aiEmbeddingModel : "openai/text-embedding-3-small";
		embeddingModelItem = new ListItem<>(
				getString(R.string.mo_settings_ai_embedding_model),
				embeddingModelDisplay,
				R.drawable.ic_fluent_bot_24_regular,
				this::onEmbeddingModelClick);
		items.add(embeddingModelItem);
```

**Step 3: Add the click handler**

After the `onModelClick` method, add:

```java
	private void onEmbeddingModelClick(ListItem<?> item) {
		EditText input = new EditText(getActivity());
		if (prefs.aiEmbeddingModel != null) {
			input.setText(prefs.aiEmbeddingModel);
		} else {
			input.setText("openai/text-embedding-3-small");
		}
		input.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
		LinearLayout container = createDialogContainer(input);

		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.mo_settings_ai_embedding_model)
				.setMessage(getString(R.string.mo_settings_ai_embedding_model_hint))
				.setView(container)
				.setPositiveButton(R.string.ok, (d, w) -> {
					String value = input.getText().toString().trim();
					if (!value.isEmpty()) {
						prefs.aiEmbeddingModel = value;
						prefs.save();
						embeddingModelItem.subtitle = value;
						rebindItem(embeddingModelItem);
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}
```

**Step 4: Add string resources**

In `mastodon/src/main/res/values/strings.xml`, add:

```xml
	<string name="mo_settings_ai_embedding_model">Embedding Model</string>
	<string name="mo_settings_ai_embedding_model_hint">Model used for semantic ranking of posts. Default: openai/text-embedding-3-small. Uses the same API key as your LLM provider.</string>
```

**Step 5: Verify it compiles**

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:compileGithubDebugJavaWithJavac 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/settings/SettingsAIPersonalizationFragment.java
git add mastodon/src/main/res/values/strings.xml
git commit -m "feat(ai): add embedding model setting to AI Personalization UI"
```

---

## Task 8: Deprecate / remove the old LLM filter path

**Objective:** Clean up the now-unused `filterPosts()` and related methods from AIPersonalizationManager. Keep the code available behind a comment for reference, but remove it from the active call path.

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ai/AIPersonalizationManager.java`

**Step 1: Mark filterPosts as deprecated**

Add `@Deprecated` annotation to the `filterPosts` method and the `POST_FILTER_PROMPT` constant. Add a comment explaining the replacement:

```java
	/**
	 * @deprecated Replaced by {@link #rankCandidates} which uses embedding-based similarity.
	 *             Kept for reference. Will be removed in a future version.
	 */
	@Deprecated
	public static void filterPosts(...) { ... }
```

**Step 2: Verify no remaining callers**

```bash
cd ~/projects/tootsie
grep -rn "filterPosts" mastodon/src/main/java/ --include="*.java" | grep -v "AIPersonalizationManager.java"
```

Expected: No output (PersonalTimelineFragment was updated in Task 5)

**Step 3: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/AIPersonalizationManager.java
git commit -m "refactor(ai): deprecate LLM filterPosts in favor of embedding ranking"
```

---

## Task 9: Build, deploy, and manual test

**Objective:** Build the APK, install on device, and verify the full flow works end-to-end.

**Step 1: Build the debug APK**

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:assembleGithubDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, APK at `mastodon/build/outputs/apk/githubDebug/tootsie-githubDebug.apk`

**Step 2: Install on device**

```bash
adb install -r mastodon/build/outputs/apk/githubDebug/tootsie-githubDebug.apk
```

**Step 3: Manual test checklist**

1. Open Settings → AI Personalization
2. Verify "Embedding Model" setting appears with default value
3. Tap "Re-infer Topics" — should complete and show topic count
4. Switch to Personal timeline — should load with ranked results
5. Verify results appear faster than before (no 2-5s LLM filter wait)
6. Check logcat for `EmbeddingClient` and `AIPersonalization` tags:

```bash
adb logcat -s AIPersonalization:D EmbeddingClient:D
```

Expected log output:
```
D/AIPersonalization: Embedded X posts, Y total cached
D/EmbeddingClient: Embedding batch of N texts via https://openrouter.ai/api/v1/embeddings
```

**Step 4: Verify cost is minimal**

After testing, check OpenRouter dashboard. The embedding calls should show as a tiny fraction of the previous LLM filter costs.

---

## Future Enhancements (not in this plan)

These are natural next steps once the embedding foundation is in place:

1. **Federated timeline as candidate source** — Replace per-topic keyword search with a single federated timeline fetch, ranked by embedding similarity. Eliminates N parallel API calls.

2. **Engagement feedback loop** — Track which ranked posts the user favorites/boosts/skips. Boost similarity scores for engaged topics, decay for skipped ones.

3. **Recency weighting** — Blend similarity score with post age: `score = similarity × (1 / (1 + age_hours))`. Fresh posts get a boost.

4. **Diversity enforcement** — Cap max 3 posts from the same author in the top 20. Prevents one prolific user from dominating the feed.

5. **Incremental embedding** — On re-inference, only embed new favorites (delta), not all 40. The `getEmbeddedStatusIds()` check in `embedFavorites()` already supports this.

6. **Negative examples** — Let users mark posts as "not interested" and store those as negative vectors. Rank candidates against both positive and negative vectors: `score = max_pos_sim - max_neg_sim`.
