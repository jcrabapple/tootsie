package org.joinmastodon.android.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.requests.accounts.GetAccountStatuses;
import org.joinmastodon.android.api.requests.statuses.GetFavoritedStatuses;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.AITopic;
import org.joinmastodon.android.model.HeaderPaginationList;
import org.joinmastodon.android.model.Status;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Orchestrates LLM calls for AI Personalization: topic inference from favorites/boosts,
 * and post ranking from the federated timeline.
 *
 * Uses OkHttp directly (not MastodonAPIRequest) because the LLM provider has a different
 * base URL and auth scheme than the Mastodon instance API.
 */
public class AIPersonalizationManager {
	private static final String TAG = "AIPersonalization";

	private static final Gson gson = new Gson();
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final Handler uiHandler = new Handler(Looper.getMainLooper());

	private static final OkHttpClient llmClient = new OkHttpClient.Builder()
			.connectTimeout(30, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.build();

	// Caps to manage API cost
	private static final int MAX_FAVORITES_TO_FETCH = 40;
	private static final int MAX_BOOSTS_TO_FETCH = 40;
	private static final long TOPIC_INFERENCE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

	// System prompt for topic inference
	private static final String SYSTEM_PROMPT =
			"You are an AI assistant embedded in a Mastodon social media client called Tootsie. " +
			"Your job is to help personalize the user's timeline by identifying their interests from their activity.\n\n" +
			"You will receive posts from the user's Mastodon favorites and boosts. Each post includes an ID, " +
			"the author's display name and username, the post content (which may contain HTML), and the language of the post.\n\n" +
			"Posts may be in any language. You should evaluate content regardless of language. Infer topic labels in English.\n\n" +
			"You must return your response as valid JSON. Do not include any text outside the JSON object. " +
			"Do not wrap the JSON in markdown code fences.\n\n" +
			"You should ignore:\n" +
			"- Posts that are primarily spam, cryptocurrency speculation, or obvious troll content\n" +
			"- Posts that are empty or contain only media with no text description\n" +
			"- Posts in languages you cannot understand\n\n" +
			"You should favor:\n" +
			"- Posts that are informative, thoughtful, or entertaining\n" +
			"- Posts that discuss topics the user has shown interest in\n" +
			"- Posts with substance over purely reactive posts (e.g., short reactions with no context)";

	// Topic inference prompt (appended to system prompt)
	private static final String TOPIC_INFERENCE_PROMPT =
			"\n\nYour task is to infer the topics and interests of a Mastodon user based on posts they have favorited and boosted.\n\n" +
			"You will receive a JSON object containing two arrays:\n" +
			"- \"favorites\": posts the user has favorited (starred)\n" +
			"- \"boosts\": posts the user has boosted (reblogged/shared)\n\n" +
			"Both actions indicate interest, but boosting may indicate stronger endorsement of the content.\n\n" +
			"Analyze the content of these posts and infer a list of topics that the user finds interesting. Topics should be:\n" +
			"- Concise (1-3 words, e.g., \"Linux\", \"Climate science\", \"Indie games\", \"Space exploration\")\n" +
			"- Specific enough to be useful but general enough to recur across posts\n" +
			"- In English, regardless of the post language\n" +
			"- Free of duplicates or near-duplicates\n\n" +
			"Return your response as JSON in this exact format:\n" +
			"{\n  \"topics\": [\"topic1\", \"topic2\", \"topic3\", ...]\n}\n\n" +
			"Aim for 5-15 topics. Order by estimated relevance (most prominent first).";

	// Post filtering prompt (DEPRECATED — replaced by embedding-based ranking)
	@Deprecated
	private static final String POST_FILTER_PROMPT =
			"\n\nYour task is to filter search results from a Mastodon timeline.\n\n" +
			"You will receive a JSON object containing:\n" +
			"- \"topics\": the user's topics of interest\n" +
			"- \"posts\": posts found by searching for those topics, each with an \"id\", \"author\", \"content\", \"language\", and \"matched_topic\"\n\n" +
			"The posts were found by keyword search, so some are false positives. " +
			"A post matched on the topic \"Space Exploration\" because it contains the word \"space\" " +
			"is NOT genuinely about space exploration — it might be about \"creating space\" in your life.\n\n" +
			"For each post, evaluate whether the matched_topic is a CENTRAL SUBJECT of the post, " +
			"not just a word that happens to appear. Remove posts where:\n" +
			"- The topic keyword is used in a different context (e.g., \"space\" meaning personal space, not outer space)\n" +
			"- The post is spam, ads, or low-quality content\n" +
			"- The post is not actually about the matched topic\n\n" +
			"Keep posts where the topic is clearly a main subject of discussion.\n\n" +
			"Return your response as JSON in this exact format:\n" +
			"{\n  \"relevant_ids\": [\"id_of_post_1\", \"id_of_post_2\", ...]\n}\n\n" +
			"Only include IDs of posts that genuinely match their topic. Order by relevance (most relevant first).";


	// ========== Topic Inference ==========

	/**
	 * Infer topics from the user's favorites and boosts.
	 * Callbacks run on the UI thread.
	 */
	public static void inferTopics(String accountID, Consumer<List<AITopic>> onSuccess, Consumer<String> onError) {
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountID);
		if (session == null) {
			uiHandler.post(() -> onError.accept("No active session"));
			return;
		}

		AccountLocalPreferences prefs = session.getLocalPreferences();
		String apiKey = prefs.aiApiKey;
		String apiUrl = prefs.aiApiUrl;
		String model = prefs.aiModel;

		if (apiKey == null || apiKey.isBlank()) {
			uiHandler.post(() -> onError.accept("No API key configured"));
			return;
		}

		// Fetch favorites
		new GetFavoritedStatuses(null, MAX_FAVORITES_TO_FETCH)
				.setCallback(new me.grishka.appkit.api.Callback<HeaderPaginationList<Status>>() {
					@Override
					public void onSuccess(HeaderPaginationList<Status> favorites) {
						// Fetch boosts (user's own statuses that are reblogs)
							String selfId = session.self.id;
							new GetAccountStatuses(selfId, null, null, MAX_BOOSTS_TO_FETCH,
									GetAccountStatuses.Filter.DEFAULT, null)
									.setCallback(new me.grishka.appkit.api.Callback<>() {
							@Override
							public void onSuccess(List<Status> accountStatuses) {
								// Filter to only reblogs
								List<Status> boosts = new ArrayList<>();
								for (Status s : accountStatuses) {
									if (s.reblog != null) {
										boosts.add(s.reblog); // use the original boosted post
									}
								}
								// Now call the LLM
								callLLMForTopics(accountID, favorites, boosts, apiUrl, apiKey, model, onSuccess, onError);
							}

							@Override
							public void onError(me.grishka.appkit.api.ErrorResponse error) {
								// If boosts fail, still try with just favorites
								callLLMForTopics(accountID, favorites, new ArrayList<>(), apiUrl, apiKey, model, onSuccess, onError);
							}
						}).exec(accountID);
					}

					@Override
					public void onError(me.grishka.appkit.api.ErrorResponse error) {
						// If favorites fail, try with just boosts
						String selfId = session.self.id;
						new GetAccountStatuses(selfId, null, null, MAX_BOOSTS_TO_FETCH,
								GetAccountStatuses.Filter.DEFAULT, null)
								.setCallback(new me.grishka.appkit.api.Callback<>() {
									@Override
									public void onSuccess(List<Status> accountStatuses) {
										List<Status> boosts = new ArrayList<>();
										for (Status s : accountStatuses) {
											if (s.reblog != null) boosts.add(s.reblog);
										}
										if (boosts.isEmpty()) {
											uiHandler.post(() -> onError.accept("No favorites or boosts found. Favorite and boost some posts first."));
										} else {
											callLLMForTopics(accountID, new ArrayList<>(), boosts, apiUrl, apiKey, model, onSuccess, onError);
										}
									}

									@Override
									public void onError(me.grishka.appkit.api.ErrorResponse error2) {
										uiHandler.post(() -> onError.accept("Failed to fetch favorites and boosts"));
									}
								}).exec(accountID);
					}
				}).exec(accountID);
	}

	private static void callLLMForTopics(String accountID, List<Status> favorites, List<Status> boosts,
										String apiUrl, String apiKey, String model,
										Consumer<List<AITopic>> onSuccess, Consumer<String> onError) {
		// Clear old embeddings so they get re-embedded with fresh vectors
		EmbeddingDatabase db = EmbeddingDatabase.getInstance(
				MastodonApp.context);
		db.clearEmbeddings(accountID);

		// Build the user content JSON
		JsonObject payload = new JsonObject();
		payload.add("favorites", buildPostArray(favorites));
		payload.add("boosts", buildPostArray(boosts));

		String userContent = gson.toJson(payload);
		String rankingPrompt = SYSTEM_PROMPT + TOPIC_INFERENCE_PROMPT;

		// Build chat completion request
		JsonObject requestBody = buildChatCompletionRequest(model, rankingPrompt, userContent);

		// Execute on background thread
		MastodonAPIController.runInBackground(() -> {
			try {
				String response = executeLLMRequest(apiUrl, apiKey, requestBody);
				List<AITopic> topics = parseTopicsResponse(response);

				// Merge with existing user-added topics
				AccountSession session = AccountSessionManager.getInstance().getAccount(accountID);
				AccountLocalPreferences prefs = session.getLocalPreferences();

				// Preserve user-added topics, replace AI-inferred ones
				List<AITopic> merged = new ArrayList<>();
				Set<String> newTopicLabels = new HashSet<>();
				for (AITopic t : topics) {
					newTopicLabels.add(t.label.toLowerCase());
					merged.add(t);
				}
				// Add back user-added topics that aren't duplicates
				if (prefs.aiTopics != null) {
					for (AITopic existing : prefs.aiTopics) {
						if (existing.userAdded && !newTopicLabels.contains(existing.label.toLowerCase())) {
							merged.add(existing);
						}
					}
				}

				prefs.aiTopics = new ArrayList<>(merged);
				prefs.aiTopicsLastUpdated = System.currentTimeMillis();
				prefs.save();

				// Embed favorites for vector ranking (fire-and-forget)
				embedFavorites(accountID, favorites, boosts,
						count -> Log.d(TAG, "Cached " + count + " embeddings after topic inference"),
						error -> Log.w(TAG, "Embedding after inference failed: " + error));

				List<AITopic> result = new ArrayList<>(merged);
				uiHandler.post(() -> onSuccess.accept(result));
			} catch (Exception e) {
				Log.e(TAG, "Topic inference failed", e);
				uiHandler.post(() -> onError.accept(e.getMessage() != null ? e.getMessage() : "Unknown error"));
			}
		});
	}

	private static List<AITopic> parseTopicsResponse(String response) {
		JsonObject json = JsonParser.parseString(response).getAsJsonObject();
		// Extract from the LLM response (which is inside the choices[0].message.content)
		String content = extractContentFromResponse(json);
		JsonObject contentJson = JsonParser.parseString(content).getAsJsonObject();
		JsonArray topicsArray = contentJson.getAsJsonArray("topics");

		List<AITopic> topics = new ArrayList<>();
		for (JsonElement el : topicsArray) {
			topics.add(new AITopic(el.getAsString(), false));
		}
		return topics;
	}


	// ========== Post Filtering ==========

	/**
	 * Filter search results through the LLM to remove false positives.
	 * Each post is tagged with its matched topic so the LLM can verify relevance.
	 * Callbacks run on the UI thread.
	 *
	 * @deprecated Replaced by {@link #rankCandidates} which uses embedding-based similarity.
	 *             Kept for reference. Will be removed in a future version.
	 */
	@Deprecated
	public static void filterPosts(String accountID, List<Status> posts, Map<String, List<String>> topicMap,
									Consumer<List<Status>> onSuccess, Consumer<String> onError) {
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountID);
		if (session == null) {
			uiHandler.post(() -> onError.accept("No active session"));
			return;
		}

		AccountLocalPreferences prefs = session.getLocalPreferences();
		String apiKey = prefs.aiApiKey;
		String apiUrl = prefs.aiApiUrl;
		String model = prefs.aiModel;

		if (apiKey == null || apiKey.isBlank()) {
			// No API key — skip filtering, return posts as-is
			uiHandler.post(() -> onSuccess.accept(posts));
			return;
		}

		// Build posts array with matched_topic annotation
		JsonArray postsArray = new JsonArray();
		for (Status s : posts) {
			Status actual = s.reblog != null ? s.reblog : s;
			List<String> topics = topicMap.get(actual.id);
			String matchedTopic = (topics != null && !topics.isEmpty()) ? topics.get(0) : "";

			JsonObject postObj = new JsonObject();
			postObj.addProperty("id", actual.id);
			postObj.addProperty("author", actual.account != null ? actual.account.getDisplayName() : "");
			postObj.addProperty("username", actual.account != null ? actual.account.acct : "");
			postObj.addProperty("content", stripHtml(actual.content));
			postObj.addProperty("language", actual.language != null ? actual.language : "");
			postObj.addProperty("matched_topic", matchedTopic);
			postsArray.add(postObj);
		}

		// Build topics list
		Set<String> allTopics = new HashSet<>();
		for (List<String> tl : topicMap.values()) allTopics.addAll(tl);
		JsonArray topicsArray = new JsonArray();
		for (String t : allTopics) topicsArray.add(t);

		JsonObject payload = new JsonObject();
		payload.add("topics", topicsArray);
		payload.add("posts", postsArray);

		String userContent = gson.toJson(payload);
		String systemPrompt = SYSTEM_PROMPT + POST_FILTER_PROMPT;
		JsonObject requestBody = buildChatCompletionRequest(model, systemPrompt, userContent);

		// Build ID→Status map for lookup after filtering
		Map<String, Status> statusById = new LinkedHashMap<>();
		for (Status s : posts) {
			Status actual = s.reblog != null ? s.reblog : s;
			statusById.put(actual.id, s);
		}

		MastodonAPIController.runInBackground(() -> {
			try {
				String response = executeLLMRequest(apiUrl, apiKey, requestBody);
				List<String> relevantIds = parseFilteredIdsResponse(response);

				// Map back to Status objects in LLM's relevance order
				List<Status> result = new ArrayList<>();
				for (String id : relevantIds) {
					Status s = statusById.get(id);
					if (s != null) result.add(s);
				}

				List<Status> finalResult = result;
				uiHandler.post(() -> onSuccess.accept(finalResult));
			} catch (Exception e) {
				Log.e(TAG, "Post filtering failed", e);
				// On failure, fall back to unfiltered results
				uiHandler.post(() -> onSuccess.accept(posts));
			}
		});
	}

	private static List<String> parseFilteredIdsResponse(String response) {
		JsonObject json = JsonParser.parseString(response).getAsJsonObject();
		String content = extractContentFromResponse(json);
		JsonObject contentJson = JsonParser.parseString(content).getAsJsonObject();
		JsonArray idsArray = contentJson.getAsJsonArray("relevant_ids");

		List<String> ids = new ArrayList<>();
		for (JsonElement el : idsArray) {
			ids.add(el.getAsString());
		}
		return ids;
	}


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
				MastodonApp.context);
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
				MastodonApp.context);
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


	// ========== OpenRouter Models ==========

	/**
	 * Fetch available models from the LLM provider.
	 * Callbacks run on the UI thread.
	 */
	public static void fetchModels(String accountID, Consumer<List<ModelInfo>> onSuccess, Consumer<String> onError) {
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountID);
		if (session == null) {
			uiHandler.post(() -> onError.accept("No active session"));
			return;
		}

		AccountLocalPreferences prefs = session.getLocalPreferences();
		String apiKey = prefs.aiApiKey;
		String apiUrl = prefs.aiApiUrl;

		if (apiKey == null || apiKey.isBlank()) {
			uiHandler.post(() -> onError.accept("No API key configured"));
			return;
		}

		String modelsUrl = apiUrl.endsWith("/") ? apiUrl + "models" : apiUrl + "/models";

		MastodonAPIController.runInBackground(() -> {
			try {
				Request request = new Request.Builder()
						.url(modelsUrl)
						.header("Authorization", "Bearer " + apiKey)
						.header("Content-Type", "application/json")
						.build();

				try (Response response = llmClient.newCall(request).execute()) {
					if (!response.isSuccessful()) {
						throw new IOException("HTTP " + response.code() + ": " + response.message());
					}
					String body = response.body() != null ? response.body().string() : "{}";
					JsonObject json = JsonParser.parseString(body).getAsJsonObject();
					JsonArray data = json.getAsJsonArray("data");

					List<ModelInfo> models = new ArrayList<>();
					for (JsonElement el : data) {
						JsonObject modelObj = el.getAsJsonObject();
						ModelInfo info = new ModelInfo();
						info.id = modelObj.has("id") ? modelObj.get("id").getAsString() : "";
						info.name = modelObj.has("name") ? modelObj.get("name").getAsString() : info.id;
						if (modelObj.has("context_length") && !modelObj.get("context_length").isJsonNull()) {
							info.contextLength = modelObj.get("context_length").getAsLong();
						}
						if (modelObj.has("pricing") && !modelObj.get("pricing").isJsonNull()) {
							JsonObject pricing = modelObj.getAsJsonObject("pricing");
							if (pricing.has("prompt") && !pricing.get("prompt").isJsonNull()) {
								info.promptPrice = pricing.get("prompt").getAsString();
							}
						}
						if (!info.id.isEmpty()) models.add(info);
					}

					List<ModelInfo> finalModels = models;
					uiHandler.post(() -> onSuccess.accept(finalModels));
				}
			} catch (Exception e) {
				Log.e(TAG, "Failed to fetch models", e);
				uiHandler.post(() -> onError.accept(e.getMessage() != null ? e.getMessage() : "Unknown error"));
			}
		});
	}


	// ========== Model Info ==========

	public static class ModelInfo {
		public String id;
		public String name;
		public long contextLength;
		public String promptPrice; // per-token price as string
	}


	// ========== Helpers ==========

	/**
	 * Check if topics need re-inference (older than 24 hours or never inferred).
	 */
	public static boolean topicsNeedRefresh(AccountLocalPreferences prefs) {
		if (prefs.aiTopicsLastUpdated == 0) return true;
		return System.currentTimeMillis() - prefs.aiTopicsLastUpdated > TOPIC_INFERENCE_TTL_MS;
	}

	/**
	 * Build a JSON array of post summaries for the LLM.
	 */
	private static JsonArray buildPostArray(List<Status> posts) {
		JsonArray arr = new JsonArray();
		for (Status s : posts) {
			// Use reblog content if it's a reblog
			Status actual = s.reblog != null ? s.reblog : s;
			JsonObject postObj = new JsonObject();
			postObj.addProperty("id", actual.id);
			postObj.addProperty("author", actual.account != null ? actual.account.getDisplayName() : "");
			postObj.addProperty("username", actual.account != null ? actual.account.acct : "");
			postObj.addProperty("content", stripHtml(actual.content));
			postObj.addProperty("language", actual.language != null ? actual.language : "");
			arr.add(postObj);
		}
		return arr;
	}

	/**
	 * Strip HTML tags from Mastodon post content for cleaner LLM input.
	 */
	private static String stripHtml(String html) {
		if (html == null) return "";
		// Basic HTML tag removal — Mastodon content is simple HTML
		String text = html.replaceAll("<[^>]+>", "");
		// Decode common HTML entities
		text = text.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&nbsp;", " ")
				.replace("&apos;", "'");
		return text.trim();
	}

	/**
	 * Build an OpenRouter chat completion request body.
	 */
	private static JsonObject buildChatCompletionRequest(String model, String systemPrompt, String userContent) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);

		JsonArray messages = new JsonArray();

		JsonObject systemMsg = new JsonObject();
		systemMsg.addProperty("role", "system");
		systemMsg.addProperty("content", systemPrompt);
		messages.add(systemMsg);

		JsonObject userMsg = new JsonObject();
		userMsg.addProperty("role", "user");
		userMsg.addProperty("content", userContent);
		messages.add(userMsg);

		body.add("messages", messages);
		body.addProperty("temperature", 0.3);

		return body;
	}

	/**
	 * Execute an LLM chat completion request and return the raw response string.
	 */
	private static String executeLLMRequest(String apiUrl, String apiKey, JsonObject requestBody) throws IOException {
		String chatUrl = apiUrl.endsWith("/") ? apiUrl + "chat/completions" : apiUrl + "/chat/completions";

		Log.d(TAG, "LLM request to " + chatUrl + " with key length=" + (apiKey != null ? apiKey.length() : 0));

		Request request = new Request.Builder()
				.url(chatUrl)
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.post(RequestBody.create(JSON, gson.toJson(requestBody)))
				.build();

		try (Response response = llmClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				String errorBody = response.body() != null ? response.body().string() : "";
				Log.e(TAG, "LLM error " + response.code() + ": " + errorBody);
				if (response.code() == 401) {
					throw new IOException("Authentication failed — check your API key in Settings → AI Personalization");
				}
				throw new IOException("HTTP " + response.code() + ": " + response.message() + " — " + errorBody);
			}
			return response.body() != null ? response.body().string() : "{}";
		}
	}

	/**
	 * Extract the content from the first choice in a chat completion response.
	 */
	private static String extractContentFromResponse(JsonObject responseJson) {
		JsonArray choices = responseJson.getAsJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new RuntimeException("No choices in LLM response");
		}
		JsonObject firstChoice = choices.get(0).getAsJsonObject();
		JsonObject message = firstChoice.getAsJsonObject("message");
		String content = message.get("content").getAsString();

		// Strip markdown code fences if present (LLMs sometimes wrap JSON in ```json ... ```)
		content = content.trim();
		if (content.startsWith("```")) {
			// Remove first line (```json or ```)
			int firstNewline = content.indexOf('\n');
			if (firstNewline > 0) {
				content = content.substring(firstNewline + 1);
			}
			// Remove trailing ```
			if (content.endsWith("```")) {
				content = content.substring(0, content.length() - 3);
			}
			content = content.trim();
		}

		return content;
	}
}
