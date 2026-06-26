package org.joinmastodon.android.api.requests.collections;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

/**
 * PATCH /api/v1/collections/:id
 *
 * Updates a collection's metadata.
 * Response is wrapped: {"collection": {...}}
 */
public class UpdateCollection extends MastodonAPIRequest<UpdateCollection.Response> {
    public UpdateCollection(String id, String name, String description, String language, String tagName) {
        super(HttpMethod.PATCH, "/collections/" + id, new TypeToken<>() {});
        setRequestBody(new Request(name, description, language, tagName));
    }

    public static class Response {
        public Collection collection;
    }

    private static class Request {
        public String name;
        public String description;
        public String language;
        public String tagName;

        public Request(String name, String description, String language, String tagName) {
            this.name = name;
            this.description = description;
            this.language = language;
            this.tagName = tagName;
        }
    }
}
