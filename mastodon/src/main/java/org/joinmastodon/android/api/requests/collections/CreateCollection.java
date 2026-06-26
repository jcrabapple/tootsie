package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

/**
 * POST /api/v1/collections
 *
 * Creates a new collection.
 */
public class CreateCollection extends MastodonAPIRequest<Collection> {
    public CreateCollection(String name, String description, String language, String tagName) {
        super(HttpMethod.POST, "/collections", Collection.class);
        setRequestBody(new Request(name, description, language, tagName));
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
