package org.joinmastodon.android.fragments.discover;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ai.AIPersonalizationManager;
import org.joinmastodon.android.api.requests.search.GetSearchResults;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.StatusListFragment;
import org.joinmastodon.android.model.AITopic;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.SearchResults;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.TopicTagStatusDisplayItem;
import org.joinmastodon.android.utils.ProvidesAssistContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/**
 * Personal timeline: searches Mastodon globally for posts matching the user's
 * enabled AI topics, merges and dedupes results across topics.
 *
 * Results are shown immediately after search (sorted by date), then optionally
 * refined through LLM filtering in the background to remove false positives.
 */
public class PersonalTimelineFragment extends StatusListFragment
		implements ProvidesAssistContent.ProvidesWebUri {

	private int searchOffset = 0;
	private static final int POSTS_PER_TOPIC = 10;

	/** Maps status ID → list of topic labels that matched this post. */
	private final Map<String, List<String>> topicMap = new ConcurrentHashMap<>();

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
		return items;
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

		// Search Mastodon for each topic in parallel
		AtomicInteger remaining = new AtomicInteger(enabledTopics.size());
		Set<String> seenIds = Collections.synchronizedSet(new HashSet<>());
		List<Status> merged = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger errorCount = new AtomicInteger(0);

		for (String topic : enabledTopics) {
			new GetSearchResults(topic, GetSearchResults.Type.STATUSES, false, null, searchOffset, POSTS_PER_TOPIC)
					.limit(POSTS_PER_TOPIC)
					.setCallback(new Callback<>() {
						@Override
						public void onSuccess(SearchResults result) {
							if (getActivity() == null) return;
							if (result.statuses != null) {
								for (Status s : result.statuses) {
									Status content = s.reblog != null ? s.reblog : s;
									if (seenIds.add(content.id)) {
										merged.add(s);
									}
									// Track which topic(s) matched this post
									topicMap.computeIfAbsent(content.id, k -> new ArrayList<>()).add(topic);
								}
							}
							if (remaining.decrementAndGet() == 0) {
								onAllTopicsComplete(merged);
							}
						}

						@Override
						public void onError(ErrorResponse error) {
							if (getActivity() == null) return;
							errorCount.incrementAndGet();
							if (remaining.decrementAndGet() == 0) {
								if (errorCount.get() == enabledTopics.size()) {
									Toast.makeText(getActivity(),
											getString(R.string.mo_personal_error),
											Toast.LENGTH_SHORT).show();
									onDataLoaded(new ArrayList<>(), false);
								} else {
									onAllTopicsComplete(merged);
								}
							}
						}
					})
					.exec(accountID);
		}
	}

	private void onAllTopicsComplete(List<Status> merged) {
		if (getActivity() == null) return;

		List<Status> result = new ArrayList<>(new LinkedHashSet<>(merged));

		// Filter statuses through account-level filters
		if (!result.isEmpty()) {
			AccountSessionManager.get(accountID).filterStatuses(result, getFilterContext());
		}

		// Sort by date descending (newest first)
		result.sort(Comparator.comparing((Status s) -> s.createdAt).reversed());

		searchOffset += POSTS_PER_TOPIC;

		if (result.isEmpty()) {
			onDataLoaded(result, false);
			return;
		}

		// Show results immediately — user sees posts right away
		onDataLoaded(result, true);

		// If API key is configured, refine in background via LLM
		AccountLocalPreferences prefs = getLocalPrefs();
		if (prefs.aiApiKey != null && !prefs.aiApiKey.isBlank()) {
			AIPersonalizationManager.filterPosts(accountID, result, topicMap,
					this::applyFilteredResults,
					error -> {
						// Filtering failed — keep the unfiltered results, no action needed
					});
		}
	}

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

	@Override
	protected FilterContext getFilterContext() {
		return FilterContext.PUBLIC;
	}

	@Override
	public Uri getWebUri(Uri.Builder base) {
		return base.path("/public").build();
	}
}
