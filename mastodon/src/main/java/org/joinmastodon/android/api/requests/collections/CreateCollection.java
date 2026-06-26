package org.joinmastodon.android.api.requests.collections;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

/**
 * POST /api/v1/collections
 *
 * Creates a new collection.
 * Response is wrapped: {"collection": {...}}
 *
 * Uses JsonObject for the request body so optional fields (description,
 * language, tag_name) are only included when non-empty. The Mastodon 4.6
 * server rejects empty strings for tag_name with a misleading validation
 * error ("Name can't be blank").
 */
public class CreateCollection extends MastodonAPIRequest<CreateCollection.Response> {
    public CreateCollection(String name, String description, String language, String tagName, boolean sensitive, boolean discoverable) {
        super(HttpMethod.POST, "/collections", new TypeToken<>() {});
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        if (description != null && !description.isEmpty())
            body.addProperty("description", description);
        if (language != null && !language.isEmpty())
            body.addProperty("language", language);
        if (tagName != null && !tagName.isEmpty())
            body.addProperty("tag_name", tagName);
        body.addProperty("sensitive", sensitive);
        body.addProperty("discoverable", discoverable);
        setRequestBody(body);
    }

    public static class Response {
        public Collection collection;
    }
}
