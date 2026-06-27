package org.joinmastodon.android.ai;

import org.joinmastodon.android.model.Status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 *
 * Scoring formula:
 *   score = (similarity  × 0.50)
 *         + (recency     × 0.20)
 *         + (engagement  × 0.15)
 *         + (diversity   × 0.10)
 *         + (exploration × 0.05)
 *
 * Diversity is enforced as a post-pass (author cap).
 * Exploration adds ±8% random jitter for serendipity.
 */
public class ForYouScorer {

	// Tunable weights (sum to 1.0)
	private static final float W_SIMILARITY  = 0.50f;
	private static final float W_RECENCY     = 0.20f;
	private static final float W_ENGAGEMENT  = 0.15f;
	private static final float W_DIVERSITY   = 0.10f;
	private static final float W_EXPLORATION = 0.05f;

	// Diversity cap: max posts per author before penalty
	private static final int MAX_POSTS_PER_AUTHOR = 3;

	// Exploration jitter range (±)
	private static final float JITTER_RANGE = 0.08f;

	// Minimum similarity threshold — posts below this are filtered out
	private static final float MIN_SIMILARITY_THRESHOLD = 0.15f;

	private static final Random random = new Random();

	/**
	 * Score and rank candidates. Returns them sorted by composite score (highest first).
	 *
	 * @param candidates   Posts to rank
	 * @param similarities Pre-computed cosine similarity per candidate (same order, same size)
	 * @param topicMap     statusId → list of matched topic labels (can be empty)
	 * @param dismissedIds Set of status IDs the user has dismissed (optional, can be empty)
	 * @return Ranked list of ScoredPost, sorted by score descending
	 */
	public static List<ScoredPost> score(List<Status> candidates, List<Float> similarities,
										  Map<String, List<String>> topicMap,
										  Set<String> dismissedIds) {
		if (candidates.isEmpty()) return new ArrayList<>();
		if (topicMap == null) topicMap = new HashMap<>();
		if (dismissedIds == null) dismissedIds = new HashSet<>();

		// Step 1: Find max engagement for normalization
		long maxEngagement = 0;
		for (Status s : candidates) {
			Status actual = s.reblog != null ? s.reblog : s;
			long eng = engagementRaw(actual);
			if (eng > maxEngagement) maxEngagement = eng;
		}

		// Step 2: Score each candidate
		List<ScoredPost> scored = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			Status s = candidates.get(i);
			Status actual = s.reblog != null ? s.reblog : s;

			// Get similarity (0 if not available)
			float similarity = (i < similarities.size()) ? similarities.get(i) : 0f;

			// Filter out posts below similarity threshold
			if (similarity < MIN_SIMILARITY_THRESHOLD) {
				continue;
			}

			float recency = recencyScore(actual.createdAt);
			float engagement = maxEngagement > 0
					? (float) engagementRaw(actual) / maxEngagement : 0f;
			float exploration = (random.nextFloat() * 2 - 1) * JITTER_RANGE;

			// Diversity is applied as a post-pass
			float rawScore = (similarity * W_SIMILARITY)
					+ (recency * W_RECENCY)
					+ (engagement * W_ENGAGEMENT)
					+ (exploration * W_EXPLORATION);

			// Dismissal penalty (90% reduction)
			if (dismissedIds.contains(actual.id)) {
				rawScore *= 0.1f;
			}

			List<String> topics = topicMap.get(actual.id);
			scored.add(new ScoredPost(s, rawScore, similarity, recency, engagement,
					topics != null ? topics : new ArrayList<>()));
		}

		// Step 3: Apply diversity enforcement
		List<ScoredPost> diversified = enforceDiversity(scored);

		// Step 4: Sort by final score
		diversified.sort((a, b) -> Float.compare(b.finalScore, a.finalScore));

		return diversified;
	}

	/**
	 * Convenience overload without dismissed IDs.
	 */
	public static List<ScoredPost> score(List<Status> candidates, List<Float> similarities,
										  Map<String, List<String>> topicMap) {
		return score(candidates, similarities, topicMap, new HashSet<>());
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
	private static long engagementRaw(Status s) {
		return s.favouritesCount + (s.reblogsCount * 2) + s.repliesCount;
	}

	/**
	 * Enforce author diversity: cap at MAX_POSTS_PER_AUTHOR per author.
	 * Posts beyond the cap get a penalty. Posts within the cap get a diversity bonus.
	 */
	private static List<ScoredPost> enforceDiversity(List<ScoredPost> scored) {
		Map<String, Integer> authorCounts = new HashMap<>();
		List<ScoredPost> result = new ArrayList<>();

		// Process in raw score order (highest first)
		scored.sort((a, b) -> Float.compare(b.rawScore, a.rawScore));

		for (ScoredPost sp : scored) {
			Status actual = sp.status.reblog != null ? sp.status.reblog : sp.status;
			String authorId = actual.account != null ? actual.account.id : "";

			int count = authorCounts.getOrDefault(authorId, 0);
			if (count >= MAX_POSTS_PER_AUTHOR) {
				// Diversity penalty: halve the score
				sp.finalScore = sp.rawScore * 0.5f;
			} else {
				// Diversity bonus: proportional to how few posts this author has
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
