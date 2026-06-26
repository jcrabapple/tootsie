package org.joinmastodon.android.model;

import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.AllFieldsAreRequired;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.api.RequiredField;
import org.parceler.Parcel;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * A Fediverse Collection, as introduced in Mastodon 4.6 (FEP-7aa9).
 *
 * Collections are publicly-shared, curated sets of up to 25 accounts
 * intended for discovery and recommendation — *not* follow management.
 * Safety model: accounts must opt into "Feature me in discovery
 * experiences" to appear; members are notified when added and can
 * self-revoke at any time.
 *
 * Moshidon's existing {@link FollowList} is a different concept (private
 * follow filtering). Collections live alongside, not as a replacement.
 */
@AllFieldsAreRequired
@Parcel
public class Collection extends BaseModel {
    @RequiredField
    public String id;
    @RequiredField
    public String title;
    public String description;
    public int accountsCount;
    public List<Account> accounts; // null on list endpoints, populated on detail
    public String featured; // public shareable URL
    public String language; // ISO 639-1 (e.g. "en")
    public Hashtag tag; // optional single topic hashtag
    @RequiredField
    public Instant createdAt;
    public Instant updatedAt;

    @Override
    public void postprocess() throws ObjectValidationException {
        super.postprocess();
        // FEP-7aa9: accounts is optional. Some endpoints don't include it.
        if (accounts != null) {
            for (Account a : accounts) {
                a.postprocess();
            }
        } else {
            accounts = Collections.emptyList();
        }
        if (tag != null) {
            tag.postprocess();
        }
        if (description == null) description = "";
        if (featured == null) featured = "";
        if (language == null) language = "";
    }

    @Override
    public String toString() {
        return "Collection{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", accountsCount=" + accountsCount +
                ", language='" + language + '\'' +
                ", tag=" + tag +
                '}';
    }
}