package org.joinmastodon.android.ai;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

		// CORRECT for this project's OkHttp version (old API):
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
				if (response.code() == 401) {
					throw new IOException("Authentication failed — check your API key in Settings → AI Personalization");
				}
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
