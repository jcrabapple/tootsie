package org.joinmastodon.android.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.List;
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

		Request request = new Request.Builder()
				.url(chatUrl)
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.post(RequestBody.create(JSON, gson.toJson(requestBody)))
				.build();

		try (Response response = llmClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				String errorBody = response.body() != null ? response.body().string() : "";
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
