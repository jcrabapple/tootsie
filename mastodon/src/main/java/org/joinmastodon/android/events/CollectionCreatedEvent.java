package org.joinmastodon.android.events;

import org.joinmastodon.android.model.Collection;

/**
 * Otto event posted when a Fediverse Collection (FEP-7aa9 / Mastodon 4.6) is
 * created by the current account. Subscribers (like
 * {@code ManageCollectionsFragment}) append the new collection to their list.
 */
public class CollectionCreatedEvent{
	public final String accountID;
	public final Collection collection;

	public CollectionCreatedEvent(String accountID, Collection collection){
		this.accountID=accountID;
		this.collection=collection;
	}
}
