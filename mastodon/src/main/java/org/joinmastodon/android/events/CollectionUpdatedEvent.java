package org.joinmastodon.android.events;

import org.joinmastodon.android.model.Collection;

/**
 * Otto event posted when a Fediverse Collection (FEP-7aa9 / Mastodon 4.6) is
 * created, updated, or deleted by the current account or an admin action.
 *
 * <p>Subscribers (like {@code CollectionDetailFragment}) listen for their specific
 * collection ID and refresh the cache entry + reload the member list.
 */
public class CollectionUpdatedEvent {
	public final String accountID;
	public final String collectionID;
	public final Collection collection;

	public CollectionUpdatedEvent(String accountID, String collectionID, Collection collection) {
		this.accountID = accountID;
		this.collectionID = collectionID;
		this.collection = collection;
	}
}