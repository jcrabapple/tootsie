package org.joinmastodon.android.fragments.discover;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ai.AIPersonalizationManager;
import org.joinmastodon.android.ai.EmbeddingClient;
import org.joinmastodon.android.ai.EmbeddingDatabase;
import org.joinmastodon.android.ai.FeedbackTracker;
import org.joinmastodon.android.ai.ForYouScorer;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.requests.timelines.GetPublicTimeline;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.StatusListFragment;
import org.joinmastodon.android.model.AITopic;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.displayitems.NotInterestedStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.TopicTagStatusDisplayItem;
import org.joinmastodon.android.utils.ProvidesAssistContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/**
 * Personal timeline: fetches from the federated timeline and ranks posts using
 * the For You scoring pipeline (embedding similarity + recency + engagement + diversity).
 *
 * Candidates come from the wider Mastodon universe, not keyword search.
 * Results are shown immediately (by recency), then re-ranked by the multi-signal
 * scorer in the background.
 */
public class PersonalTimelineFragment extends StatusListFragment
		implements ProvidesAssistContent.ProvidesWebUri {

	private static final int CANDIDATE_FETCH_LIMIT = 40;

	/** Maps status ID → list of topic labels for display. */
	private Map<String, List<String>> topicMap = new java.util.concurrent.ConcurrentHashMap<>();
	private List<String> enabledTopics = new ArrayList<>();
	private String lastMaxId = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
	}

	@Override
	protected List<StatusDisplayItem> buildDisplayItems(Status s) {
		List<StatusDisplayItem> items = new ArrayList<>();
		Status content = s.reblog != null ? s.reblog : s;
		List<String> topics = topicMap.get(content.id);
		if (topics != null && !topics.isEmpty()) {
			items.add(new TopicTagStatusDisplayItem(content.id, null, getContext(), topics));
		}
		items.addAll(super.buildDisplayItems(s));

		// "Not interested" button — dismisses the post and records feedback
		items.add(new NotInterestedStatusDisplayItem(content.id, null, getContext(),
				content.id, accountID, () -> dismissPost(s)));

		return items;
	}

	private void dismissPost(Status s) {
		data.remove(s);
		displayItems.clear();
		for (Status status : data) {
			displayItems.addAll(buildDisplayItems(status));
		}
		adapter.notifyDataSetChanged();
	}

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

		enabledTopics = new ArrayList<>();
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

		// Fetch candidates from federated (or local) timeline
		boolean localOnly = "local".equals(prefs.aiTimelineSource);
		String maxId = (offset > 0 && lastMaxId != null) ? lastMaxId : null;

		new GetPublicTimeline(localOnly, !localOnly && false, maxId, null, CANDIDATE_FETCH_LIMIT, null, null)
				.setCallback(new Callback<>() {
					@Override
					public void onSuccess(List<Status> result) {
						if (getActivity() == null) return;

						// Apply account-level filters
						if (!result.isEmpty()) {
							AccountSessionManager.get(accountID).filterStatuses(result, getFilterContext());
						}

						if (result.isEmpty()) {
							onDataLoaded(result, false);
							return;
						}

						// Track max_id for pagination
						lastMaxId = result.get(result.size() - 1).id;

						// Clear topic map — will be populated after ranking
						topicMap.clear();

						// Sort by date for immediate display
						result.sort(Comparator.comparing((Status s) -> s.createdAt).reversed());

						// Show unranked results immediately
						onDataLoaded(result, true);

						// Re-rank with For You scoring in background
						if (prefs.aiApiKey != null && !prefs.aiApiKey.isBlank()) {
							rankWithForYouScoring(result);
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
	 * 2. Compute cosine similarity against cached interest vectors
	 * 3. Score with ForYouScorer (similarity + recency + engagement + diversity)
	 * 4. Update displayed results
	 */
	private void rankWithForYouScoring(List<Status> candidates) {
		AccountLocalPreferences prefs = getLocalPrefs();
		String apiUrl = prefs.aiApiUrl;
		String apiKey = prefs.aiApiKey;
		String model = prefs.aiEmbeddingModel;

		// Build text for embedding
		List<String> texts = new ArrayList<>();
		for (Status s : candidates) {
			Status actual = s.reblog != null ? s.reblog : s;
			texts.add(AIPersonalizationManager.stripHtml(actual.content));
		}

		// Load cached interest vectors
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(MastodonApp.context);
		Map<String, float[]> interestVectors = db.getEmbeddings(accountID);

		if (interestVectors.isEmpty()) {
			return; // No embeddings yet, keep unranked
		}

		// Load dismissed IDs for feedback
		Set<String> dismissedIds = FeedbackTracker.getDismissedIds(accountID);

		// Embed topic labels for per-topic matching
		List<String> topicLabels = new ArrayList<>(enabledTopics);

		MastodonAPIController.runInBackground(() -> {
			try {
				// Embed all candidates + topic labels in one batch call
				List<String> allTexts = new ArrayList<>(texts);
				allTexts.addAll(topicLabels);
				List<float[]> allVectors = EmbeddingClient.embedBatch(apiUrl, apiKey, model, allTexts);

				// Split: first N are candidates, last M are topic vectors
				List<float[]> candidateVectors = allVectors.subList(0, texts.size());
				List<float[]> topicVectors = allVectors.subList(texts.size(), allVectors.size());

				// Compute max similarity per candidate against interest vectors
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

				// Score with ForYouScorer
				List<ForYouScorer.ScoredPost> ranked = ForYouScorer.score(
						candidates, similarities, topicMap, dismissedIds);

				// Cap to top 20 results
				int maxResults = Math.min(ranked.size(), 20);

				// Build per-post topic tags by comparing each post against each topic vector
				Map<String, List<String>> newTopicMap = new java.util.concurrent.ConcurrentHashMap<>();
				for (int i = 0; i < candidateVectors.size(); i++) {
					float[] cVec = candidateVectors.get(i);
					if (cVec == null) continue;
					Status s = candidates.get(i);
					Status actual = s.reblog != null ? s.reblog : s;

					List<String> matchedTopics = new ArrayList<>();
					for (int t = 0; t < topicVectors.size(); t++) {
						float sim = EmbeddingClient.cosineSimilarity(cVec, topicVectors.get(t));
						if (sim > 0.35f) {
							matchedTopics.add(topicLabels.get(t));
						}
					}
					if (!matchedTopics.isEmpty()) {
						newTopicMap.put(actual.id, matchedTopics);
					}
				}

				// Extract sorted statuses (capped)
				List<Status> sorted = new ArrayList<>();
				for (int i = 0; i < maxResults; i++) {
					sorted.add(ranked.get(i).status);
				}

				// Update topic map and UI on main thread
				Map<String, List<String>> finalTopicMap = newTopicMap;
				new Handler(Looper.getMainLooper()).post(() -> {
					if (getActivity() == null) return;
					topicMap.clear();
					topicMap.putAll(finalTopicMap);
					data.clear();
					displayItems.clear();
					for (Status s : sorted) {
						data.add(s);
						displayItems.addAll(buildDisplayItems(s));
					}
					adapter.notifyDataSetChanged();
				});
			} catch (Exception e) {
				// Ranking failed — keep unranked results (graceful degradation)
			}
		});
	}

	@Override
	protected FilterContext getFilterContext() {
		return FilterContext.PUBLIC;
	}

	@Override
	public Uri getWebUri(Uri.Builder base) {
		return base.path("/public").build();
	}
}
