package org.joinmastodon.android.events;

/**
 * Otto event posted when a Fediverse Collection (FEP-7aa9 / Mastodon 4.6) is
 * deleted by the current account. Subscribers (like
 * {@code ManageCollectionsFragment}) remove it from their list.
 */
public class CollectionDeletedEvent{
	public final String accountID;
	public final String collectionID;

	public CollectionDeletedEvent(String accountID, String collectionID){
		this.accountID=accountID;
		this.collectionID=collectionID;
	}
}
