package org.joinmastodon.android.api.requests.collections;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

/**
 * PATCH /api/v1/collections/:id
 *
 * Updates a collection's metadata.
 * Response is wrapped: {"collection": {...}}
 *
 * Uses JsonObject for the request body so optional fields are only
 * included when non-empty (avoids Mastodon 4.6 rejecting empty strings).
 */
public class UpdateCollection extends MastodonAPIRequest<UpdateCollection.Response> {
    public UpdateCollection(String id, String name, String description, String language, String tagName, Boolean sensitive, Boolean discoverable) {
        super(HttpMethod.PATCH, "/collections/" + id, new TypeToken<>() {});
        JsonObject body = new JsonObject();
        if (name != null)
            body.addProperty("name", name);
        if (description != null && !description.isEmpty())
            body.addProperty("description", description);
        if (language != null && !language.isEmpty())
            body.addProperty("language", language);
        if (tagName != null && !tagName.isEmpty())
            body.addProperty("tag_name", tagName);
        if (sensitive != null)
            body.addProperty("sensitive", sensitive);
        if (discoverable != null)
            body.addProperty("discoverable", discoverable);
        setRequestBody(body);
    }

    public static class Response {
        public Collection collection;
    }
}
