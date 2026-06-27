# For You Feed: Federated Timeline + Multi-Signal Scoring

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Transform the Personal timeline from "keyword search + embed rank" into a true For You feed: federated timeline candidates ranked by a multi-signal formula (similarity + recency + engagement + diversity), with implicit feedback learning from user behavior.

**Architecture:** Replace per-topic keyword search with a single federated timeline fetch. Rank candidates using a weighted formula across 5 signals. Track user interactions (favorite/boost/skip) in SQLite to build a feedback profile that improves ranking over time.

**Tech Stack:** Java, OkHttp 3.14.9, Gson, Android SQLite (built-in), existing Mastodon API classes

---

## Phase 1: Federated Timeline + Multi-Signal Scoring

### Task 1: Create ForYouScorer — the multi-signal ranking engine

**Objective:** A pure-logic class that takes a list of candidate posts + user embedding vectors and produces a ranked list using a weighted formula across 5 signals. No network calls, no UI — just math.

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/ai/ForYouScorer.java`

**Scoring formula:**

```java
score = (similarity   × 0.50)   // cosine similarity to interest vectors
      + (recency      × 0.20)   // freshness: 1 / (1 + age_hours)
      + (engagement   × 0.15)   // normalized (favs + boosts + replies)
      + (diversity    × 0.10)   // bonus if author/topic underrepresented
      + (exploration  × 0.05)   // random jitter for serendipity
```

**Implementation:**

```java
package org.joinmastodon.android.ai;

import org.joinmastodon.android.model.Status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Multi-signal scorer for the For You feed.
 * Combines embedding similarity, recency, engagement, diversity, and exploration
 * into a single ranking score.
 */
public class ForYouScorer {

	// Tunable weights (sum to 1.0)
	private static final float W_SIMILARITY  = 0.50f;
	private static final float W_RECENCY     = 0.20f;
	private static final float W_ENGAGEMENT  = 0.15f;
	private static final float W_DIVERSITY   = 0.10f;
	private static final float W_EXPLORATION = 0.05f;

	// Diversity caps
	private static final int MAX_POSTS_PER_AUTHOR = 3;

	// Exploration jitter range (±)
	private static final float JITTER_RANGE = 0.08f;

	private static final Random random = new Random();

	/**
	 * Score and rank candidates. Returns them sorted by composite score (highest first).
	 *
	 * @param candidates  Posts to rank
	 * @param similarities Pre-computed cosine similarity per candidate (same order, same size)
	 * @param topicMap    statusId → list of matched topic labels (can be empty)
	 * @return Ranked list of ScoredPost, sorted by score descending
	 */
	public static List<ScoredPost> score(List<Status> candidates, List<Float> similarities,
										  Map<String, List<String>> topicMap) {
		if (candidates.isEmpty()) return new ArrayList<>();

		// Step 1: Normalize engagement signals to 0..1
		long maxEngagement = 0;
		for (Status s : candidates) {
			long eng = engagementScore(s);
			if (eng > maxEngagement) maxEngagement = eng;
		}

		// Step 2: Score each candidate
		List<ScoredPost> scored = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			Status s = candidates.get(i);
			Status actual = s.reblog != null ? s.reblog : s;

			float similarity = (i < similarities.size()) ? similarities.get(i) : 0f;
			float recency = recencyScore(actual.createdAt);
			float engagement = maxEngagement > 0
					? (float) engagementScore(actual) / maxEngagement : 0f;
			float exploration = (random.nextFloat() * 2 - 1) * JITTER_RANGE; // ±JITTER_RANGE

			// Diversity is applied as a post-pass, not per-post
			float rawScore = (similarity * W_SIMILARITY)
					+ (recency * W_RECENCY)
					+ (engagement * W_ENGAGEMENT)
					+ (exploration * W_EXPLORATION);

			scored.add(new ScoredPost(s, rawScore, similarity, recency, engagement,
					topicMap.get(actual.id)));
		}

		// Step 3: Apply diversity enforcement
		List<ScoredPost> diversified = enforceDiversity(scored);

		// Step 4: Sort by final score
		diversified.sort((a, b) -> Float.compare(b.finalScore, a.finalScore));

		return diversified;
	}

	/**
	 * Recency score: 1 / (1 + age_hours).
	 * A 1-hour-old post scores 0.5, 24h old scores ~0.04.
	 */
	private static float recencyScore(Instant createdAt) {
		if (createdAt == null) return 0f;
		long ageHours = ChronoUnit.HOURS.between(createdAt, Instant.now());
		if (ageHours < 0) ageHours = 0;
		return 1.0f / (1.0f + ageHours);
	}

	/**
	 * Raw engagement: favorites + 2*boosts + replies.
	 * Boosts weighted higher because they indicate stronger endorsement.
	 */
	private static long engagementScore(Status s) {
		return s.favouritesCount + (s.reblogsCount * 2) + s.repliesCount;
	}

	/**
	 * Enforce author diversity: cap at MAX_POSTS_PER_AUTHOR per author.
	 * Posts beyond the cap get a heavy penalty instead of being removed.
	 */
	private static List<ScoredPost> enforceDiversity(List<ScoredPost> scored) {
		Map<String, Integer> authorCounts = new HashMap<>();
		List<ScoredPost> result = new ArrayList<>();

		// Process in score order (highest first)
		scored.sort((a, b) -> Float.compare(b.rawScore, a.rawScore));

		for (ScoredPost sp : scored) {
			Status actual = sp.status.reblog != null ? sp.status.reblog : sp.status;
			String authorId = actual.account != null ? actual.account.id : "";

			int count = authorCounts.getOrDefault(authorId, 0);
			if (count >= MAX_POSTS_PER_AUTHOR) {
				// Diversity penalty: halve the score
				sp.finalScore = sp.rawScore * 0.5f;
			} else {
				sp.finalScore = sp.rawScore + (W_DIVERSITY * (1.0f - count * 0.33f));
				authorCounts.put(authorId, count + 1);
			}
			result.add(sp);
		}

		return result;
	}

	/**
	 * A post with its full scoring breakdown.
	 */
	public static class ScoredPost {
		public final Status status;
		public final float rawScore;
		public float finalScore;
		public final float similarity;
		public final float recency;
		public final float engagement;
		public final List<String> matchedTopics;

		public ScoredPost(Status status, float rawScore, float similarity,
						  float recency, float engagement, List<String> matchedTopics) {
			this.status = status;
			this.rawScore = rawScore;
			this.finalScore = rawScore;
			this.similarity = similarity;
			this.recency = recency;
			this.engagement = engagement;
			this.matchedTopics = matchedTopics;
		}
	}
}
```

**Verify:** Compile with `./gradlew :mastodon:compileGithubDebugJavaWithJavac`

**Commit:**
```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/ForYouScorer.java
git commit -m "feat(ai): add ForYouScorer — multi-signal ranking engine"
```

---

### Task 2: Update PersonalTimelineFragment to use federated timeline

**Objective:** Replace per-topic keyword search with a single `GetPublicTimeline` call. This is the biggest quality improvement — candidates come from the wider Mastodon universe, not just keyword matches.

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/discover/PersonalTimelineFragment.java`

**Changes:**

Replace the entire `doLoadData` method. Instead of looping over `enabledTopics` and searching per topic, make one call to `GetPublicTimeline`:

```java
@Override
protected void doLoadData(int offset, int count) {
	if (!GlobalUserPreferences.aiPersonalizationEnabled) {
		onDataLoaded(new ArrayList<>(), false);
		return;
	}

	AccountLocalPreferences prefs = getLocalPrefs();

	if (prefs.aiTopics == null || prefs.aiTopics.isEmpty()) {
		Toast.makeText(getActivity(), R.string.mo_personal_no_topics, Toast.LENGTH_LONG).show();
		onDataLoaded(new ArrayList<>(), false);
		return;
	}

	List<String> enabledTopics = new ArrayList<>();
	for (AITopic t : prefs.aiTopics) {
		if (t.enabled && t.label != null && !t.label.isBlank()) {
			enabledTopics.add(t.label);
		}
	}

	if (enabledTopics.isEmpty()) {
		Toast.makeText(getActivity(), R.string.mo_personal_no_topics, Toast.LENGTH_LONG).show();
		onDataLoaded(new ArrayList<>(), false);
		return;
	}

	// Fetch from federated timeline (one call instead of N keyword searches)
	String maxId = offset > 0 && !data.isEmpty()
			? data.get(data.size() - 1).id : null;

	int fetchLimit = Math.max(count * 3, 40); // fetch more candidates than needed

	new GetPublicTimeline(false, false, maxId, null, fetchLimit, null, null)
			.setCallback(new Callback<>() {
				@Override
				public void onSuccess(List<Status> result) {
					if (getActivity() == null) return;

					// Filter statuses through account-level filters
					if (!result.isEmpty()) {
						AccountSessionManager.get(accountID).filterStatuses(result, getFilterContext());
					}

					if (result.isEmpty()) {
						onDataLoaded(result, false);
						return;
					}

					// Show unranked immediately
					result.sort(Comparator.comparing((Status s) -> s.createdAt).reversed());
					onDataLoaded(result, true);

					// Rank by embedding similarity in background
					if (prefs.aiApiKey != null && !prefs.aiApiKey.isBlank()) {
						rankWithForYouScoring(result, enabledTopics);
					}
				}

				@Override
				public void onError(ErrorResponse error) {
					if (getActivity() == null) return;
					Toast.makeText(getActivity(),
							getString(R.string.mo_personal_error),
							Toast.LENGTH_SHORT).show();
					onDataLoaded(new ArrayList<>(), false);
				}
			}).exec(accountID);
}

/**
 * Rank candidates using the For You scoring pipeline:
 * 1. Embed candidates via API
 * 2. Score with ForYouScorer (similarity + recency + engagement + diversity)
 * 3. Update displayed results
 */
private void rankWithForYouScoring(List<Status> candidates, List<String> enabledTopics) {
	// Build text for embedding
	List<String> texts = new ArrayList<>();
	for (Status s : candidates) {
		Status actual = s.reblog != null ? s.reblog : s;
		texts.add(AIPersonalizationManager.stripHtml(actual.content));
	}

	AccountLocalPreferences prefs = getLocalPrefs();
	String apiUrl = prefs.aiApiUrl;
	String apiKey = prefs.aiApiKey;
	String model = prefs.aiEmbeddingModel;

	// Load cached interest vectors
	EmbeddingDatabase db = EmbeddingDatabase.getInstance(
			MastodonApp.context);
	Map<String, float[]> interestVectors = db.getEmbeddings(accountID);

	if (interestVectors.isEmpty()) {
		return; // No embeddings yet, keep unranked
	}

	MastodonAPIController.runInBackground(() -> {
		try {
			// Embed candidates
			List<float[]> candidateVectors = EmbeddingClient.embedBatch(apiUrl, apiKey, model, texts);

			// Compute similarities
			List<Float> similarities = new ArrayList<>();
			for (float[] cVec : candidateVectors) {
				float maxSim = 0f;
				if (cVec != null) {
					for (float[] iVec : interestVectors.values()) {
						float sim = EmbeddingClient.cosineSimilarity(cVec, iVec);
						if (sim > maxSim) maxSim = sim;
					}
				}
				similarities.add(maxSim);
			}

			// Build topic map for display
			Map<String, List<String>> topicMap = new java.util.concurrent.ConcurrentHashMap<>();
			for (Status s : candidates) {
				Status actual = s.reblog != null ? s.reblog : s;
				// Tag each post with the topics it's closest to
				// (simplified: use the first enabled topic as a label)
				if (!enabledTopics.isEmpty()) {
					topicMap.put(actual.id, new ArrayList<>(enabledTopics));
				}
			}

			// Score with ForYouScorer
			List<ForYouScorer.ScoredPost> ranked = ForYouScorer.score(
					candidates, similarities, topicMap);

			// Extract sorted statuses
			List<Status> sorted = new ArrayList<>();
			for (ForYouScorer.ScoredPost sp : ranked) {
				sorted.add(sp.status);
			}

			new Handler(Looper.getMainLooper()).post(() -> {
				if (getActivity() == null) return;
				data.clear();
				displayItems.clear();
				for (Status s : sorted) {
					data.add(s);
					displayItems.addAll(buildDisplayItems(s));
				}
				adapter.notifyDataSetChanged();
			});
		} catch (Exception e) {
			// Ranking failed — keep unranked results
		}
	});
}
```

Remove the old `onAllTopicsComplete`, `applyRankedResults`, and the `topicMap` field (topic tracking moves into the scoring call).

Add imports for `GetPublicTimeline`, `ForYouScorer`, `EmbeddingClient`, `EmbeddingDatabase`, `MastodonApp`, `Handler`, `Looper`.

**Verify:** Compile.

**Commit:**
```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/discover/PersonalTimelineFragment.java
git commit -m "feat(ai): use federated timeline as candidate source for For You feed"
```

---

### Task 3: Make `stripHtml` public in AIPersonalizationManager

**Objective:** The `PersonalTimelineFragment` needs to call `stripHtml()` for embedding text preparation. Currently it's private.

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ai/AIPersonalizationManager.java`

**Change:** Add `public` visibility to `stripHtml`:

```java
public static String stripHtml(String html) {
```

**Verify:** Compile.

**Commit:**
```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/AIPersonalizationManager.java
git commit -m "refactor(ai): make stripHtml public for reuse by PersonalTimelineFragment"
```

---

### Task 4: Add "Timeline Source" setting

**Objective:** Let users choose between "Federated" (global Mastodon) and "Local" (their instance only) as the candidate source. Some users may prefer local content.

**Files:**
- Modify: `AccountLocalPreferences.java` — add `aiTimelineSource` field
- Modify: `SettingsAIPersonalizationFragment.java` — add setting UI
- Modify: `PersonalTimelineFragment.java` — use the preference
- Modify: `strings_mo.xml` — add string resources

**AccountLocalPreferences changes:**

```java
public String aiTimelineSource;       // "federated" | "local", default "federated"
```

Load: `aiTimelineSource=prefs.getString("aiTimelineSource", "federated");`

Save: `.putString("aiTimelineSource", aiTimelineSource)`

**Settings UI:** Add a list item after the embedding model setting that opens a dialog with two choices: "Federated (global)" and "Local (your instance)".

**PersonalTimelineFragment:** Use the preference to set the `local` parameter:

```java
boolean localOnly = "local".equals(prefs.aiTimelineSource);
new GetPublicTimeline(localOnly, false, maxId, null, fetchLimit, null, null)
```

**Strings:**
```xml
<string name="mo_settings_ai_timeline_source">Timeline Source</string>
<string name="mo_settings_ai_timeline_source_federated">Federated (global)</string>
<string name="mo_settings_ai_timeline_source_local">Local (your instance)</string>
```

**Verify:** Compile.

**Commit:**
```bash
git add -A && git commit -m "feat(ai): add timeline source setting (federated vs local)"
```

---

## Phase 2: Implicit Feedback Loop

### Task 5: Create FeedbackTracker — interaction tracking in SQLite

**Objective:** Track which posts the user interacts with (favorite, boost, reply, dismiss) and store the feedback in SQLite for future scoring improvements.

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/ai/FeedbackTracker.java`
- Modify: `EmbeddingDatabase.java` — add feedback table

**New SQLite table:**

```sql
CREATE TABLE ai_feedback (
    account_id TEXT NOT NULL,
    status_id TEXT NOT NULL,
    action TEXT NOT NULL,         -- 'favorite' | 'boost' | 'reply' | 'dismiss' | 'skip'
    timestamp INTEGER NOT NULL,
    PRIMARY KEY (account_id, status_id, action)
);
```

**FeedbackTracker class:**

```java
package org.joinmastodon.android.ai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.joinmastodon.android.MastodonApp;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks user interactions with posts for implicit feedback.
 * Used to improve For You feed ranking over time.
 */
public class FeedbackTracker {

	/** Record that the user performed an action on a post. */
	public static void record(String accountId, String statusId, String action) {
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(MastodonApp.context);
		ContentValues cv = new ContentValues();
		cv.put("account_id", accountId);
		cv.put("status_id", statusId);
		cv.put("action", action);
		cv.put("timestamp", System.currentTimeMillis());
		db.getWritableDatabase().insertWithOnConflict("ai_feedback", null, cv,
				SQLiteDatabase.CONFLICT_REPLACE);
	}

	/** Get all status IDs the user dismissed (not interested). */
	public static List<String> getDismissedIds(String accountId) {
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(MastodonApp.context);
		List<String> ids = new ArrayList<>();
		Cursor c = db.getReadableDatabase().rawQuery(
				"SELECT status_id FROM ai_feedback WHERE account_id = ? AND action = 'dismiss'",
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

	/** Get positive interaction IDs (favorites + boosts). */
	public static List<String> getPositiveIds(String accountId) {
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(MastodonApp.context);
		List<String> ids = new ArrayList<>();
		Cursor c = db.getReadableDatabase().rawQuery(
				"SELECT DISTINCT status_id FROM ai_feedback WHERE account_id = ? AND action IN ('favorite', 'boost', 'reply')",
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
}
```

**Add the feedback table to EmbeddingDatabase.onCreate():**

After the existing `ai_embeddings` table creation, add:

```java
db.execSQL("CREATE TABLE IF NOT EXISTS ai_feedback ("
		+ "account_id TEXT NOT NULL, "
		+ "status_id TEXT NOT NULL, "
		+ "action TEXT NOT NULL, "
		+ "timestamp INTEGER NOT NULL, "
		+ "PRIMARY KEY (account_id, status_id, action)"
		+ ")");
```

Bump `DB_VERSION` to 2 and add migration in `onUpgrade`:

```java
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
```

**Verify:** Compile.

**Commit:**
```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/FeedbackTracker.java
git add mastodon/src/main/java/org/joinmastodon/android/ai/EmbeddingDatabase.java
git commit -m "feat(ai): add FeedbackTracker for implicit feedback collection"
```

---

### Task 6: Hook feedback tracking into the UI

**Objective:** Record feedback events when the user interacts with posts in the Personal timeline.

**Files:**
- Modify: `PersonalTimelineFragment.java`

**Changes:** In the existing event bus handler (from `StatusListFragment`), intercept favorite/boost/reply events and record them:

```java
// In the event handler for status interactions:
FeedbackTracker.record(accountID, statusId, "favorite");  // or "boost", "reply"
```

For the "dismiss" action, add a long-press menu option or swipe action (depends on Tootsie's existing gesture system). As a simpler first pass, add it to the existing status menu:

In `buildDisplayItems`, if this is the Personal timeline, append a "Not interested" display item. On tap, call:
```java
FeedbackTracker.record(accountID, status.id, "dismiss");
data.remove(status);
// rebuild display items
```

**Verify:** Compile.

**Commit:**
```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/discover/PersonalTimelineFragment.java
git commit -m "feat(ai): hook feedback tracking into Personal timeline interactions"
```

---

### Task 7: Integrate feedback into ForYouScorer

**Objective:** Use collected feedback to adjust scores: dismissed posts get negative weight, positively-interacted posts reinforce their similarity vectors.

**Files:**
- Modify: `ForYouScorer.java`

**Changes:** Add a `feedbackPenalties` parameter to `score()`:

```java
public static List<ScoredPost> score(List<Status> candidates, List<Float> similarities,
									  Map<String, List<String>> topicMap,
									  Set<String> dismissedIds) {
	// ... existing scoring logic ...

	// Apply dismissal penalty
	if (dismissedIds.contains(actual.id)) {
		rawScore *= 0.1f; // 90% penalty for dismissed posts
	}

	// ... rest of scoring ...
}
```

Update `PersonalTimelineFragment.rankWithForYouScoring()` to pass dismissed IDs:

```java
Set<String> dismissed = new HashSet<>(FeedbackTracker.getDismissedIds(accountID));
List<ForYouScorer.ScoredPost> ranked = ForYouScorer.score(
		candidates, similarities, topicMap, dismissed);
```

**Verify:** Compile.

**Commit:**
```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/ForYouScorer.java
git add mastodon/src/main/java/org/joinmastodon/android/fragments/discover/PersonalTimelineFragment.java
git commit -m "feat(ai): integrate feedback dismissals into For You scoring"
```

---

## Phase 3: Polish

### Task 8: Add similarity threshold

**Objective:** Don't show posts with very low similarity scores. A post with 0.05 cosine similarity to your interests is noise, not signal.

**Files:**
- Modify: `ForYouScorer.java`

**Change:** Add a minimum similarity threshold:

```java
private static final float MIN_SIMILARITY_THRESHOLD = 0.15f;

// In score(), after computing similarity:
if (similarity < MIN_SIMILARITY_THRESHOLD) {
	continue; // Skip posts that don't match interests at all
}
```

**Verify:** Compile.

**Commit:**
```bash
git add mastodon/src/main/java/org/joinmastodon/android/ai/ForYouScorer.java
git commit -m "feat(ai): add similarity threshold to filter out irrelevant posts"
```

---

### Task 9: Build and deploy

**Objective:** Full build, push to GitHub, install on device.

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:assembleGithubDebug
git push origin main
```

Install via ADB and verify:
1. Open Settings → AI Personalization → verify "Timeline Source" setting
2. Load Personal timeline — should fetch from federated timeline
3. Check logcat for embedding and scoring activity
4. Verify engagement-weighted posts appear higher (popular posts should rank well)
5. Verify author diversity (no more than 3 posts from same author)
6. Verify recency (fresh posts near the top)

---

## Scoring Weights — Tuning Guide

These weights are starting points. Adjust based on user feedback:

| Signal | Weight | What it does |
|---|---|---|
| `W_SIMILARITY` | 0.50 | Core relevance — how well the post matches your interests |
| `W_RECENCY` | 0.20 | Freshness — newer posts get a boost |
| `W_ENGAGEMENT` | 0.15 | Social proof — popular posts rank higher |
| `W_DIVERSITY` | 0.10 | Author variety — prevents one person dominating |
| `W_EXPLORATION` | 0.05 | Serendipity — random jitter discovers unexpected content |
| `MIN_SIMILARITY` | 0.15 | Floor — posts below this are filtered out entirely |

If users report the feed is too random, increase `W_SIMILARITY` to 0.65 and decrease `W_EXPLORATION` to 0.02.
If users report the feed is too stale, increase `W_RECENCY` to 0.30 and decrease `W_SIMILARITY` to 0.40.

---

## Future: Beyond This Plan

These are natural next steps once the For You feed is working:

1. **Negative embeddings** — Store dismissed post embeddings as negative vectors. Score = max_pos_sim - (0.3 × max_neg_sim). This gives the feed a "learn what you don't like" capability.

2. **Social graph candidates** — Fetch favorites/boosts from accounts the user follows. These are curated by people they trust — higher signal than federated timeline.

3. **Topic clustering** — Instead of flat topic labels, cluster the user's embedding space into interest regions. Show balanced representation across clusters.

4. **Time-of-day awareness** — Adjust engagement weight based on when the user typically reads. Morning users might prefer newsy content, evening users might prefer longer reads.

5. **Cross-instance intelligence** — If the user's instance is small, federated timeline is thin. Use the embedding search across multiple instances via their public timelines.
