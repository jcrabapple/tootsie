package org.joinmastodon.android.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.collections.GetCollection;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.CollectionUpdatedEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Collection;
import org.joinmastodon.android.model.SearchResult;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.displayitems.AccountStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.CollectionHeaderStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.parceler.Parcels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/**
 * Read-only viewer for a single Fediverse Collection (Mastodon 4.6 / FEP-7aa9).

 * <p>Displays the collection's metadata (title, description, hashtag badge,
 * member count) in a header section, then lists member accounts below. Tapping
 * an account navigates to its profile. This is the fragment that opens when
 * the user taps "View" on a Featured-tab collection card.

 * <p>Extends {@link BaseStatusListFragment} with {@link Collection} as the data
 * type because Collection implements {@link org.joinmastodon.android.model.DisplayItemsParent}
 * via {@link org.joinmastodon.android.model.BaseModel}. We override
 * {@link #buildDisplayItems} to produce {@link AccountStatusDisplayItem} rows
 * for member accounts and a {@link CollectionHeaderStatusDisplayItem} for the
 * collection metadata header.

 * <p>Notification events ({@link CollectionUpdatedEvent}) invalidate the cache
 * entry and trigger a refresh. No Room — persistence is via {@code @Parcel}
 * + Moshidon's in-memory cache layer.
 */
public class CollectionDetailFragment extends BaseStatusListFragment<Collection> {
	private String collectionID;

	public CollectionDetailFragment() {
		setListLayoutId(R.layout.recycler_fragment_no_refresh);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		collectionID = getArguments().getString("collectionID");
		// If a pre-populated Collection was passed (e.g. from Featured tab),
		// load it immediately without an API call
		Collection preloaded = Parcels.unwrap(getArguments().getParcelable("collection"));
		if (preloaded != null && preloaded.accounts != null && !preloaded.accounts.isEmpty()) {
			// The Featured tab already included accounts — skip the API call
			onDataLoaded(Collections.singletonList(preloaded), false);
		} else if (preloaded != null && collectionID == null) {
			// Preloaded with no accounts — use the ID from the preloaded collection
			collectionID = preloaded.id;
		}
		E.register(this);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		E.unregister(this);
	}

	@Override
	protected List<StatusDisplayItem> buildDisplayItems(Collection c) {
		ArrayList<StatusDisplayItem> items = new ArrayList<>();

		// Header: collection metadata (title, description, hashtag, member count)
		items.add(new CollectionHeaderStatusDisplayItem(
				c.id,
				this,
				getActivity(),
				c.name,
				c.description,
				c.tag,
				c.itemCount,
				c.language,
				c.createdAt,
				c.updatedAt,
				accountID
		));

		// Member accounts: rendered as account status display items
		if (c.accounts != null) {
			for (Account a : c.accounts) {
				items.add(new AccountStatusDisplayItem(
						c.id,
						this,
						getActivity(),
						a,
						accountID
				));
			}
		}

		return items;
	}

	@Override
	protected void addAccountToKnown(Collection c) {
		if (c.accounts != null) {
			for (Account a : c.accounts) {
				if (!knownAccounts.containsKey(a.id)) {
					knownAccounts.put(a.id, a);
				}
			}
		}
	}

	@Override
	public void onItemClick(String id) {
		Collection c = getDataItemByID(id);
		if (c == null) return;

		// Navigate to the account's profile for account-type items
		StatusDisplayItem item = getDisplayItemByID(id);
		if (item instanceof AccountStatusDisplayItem asi) {
			Bundle args = new Bundle();
			args.putString("account", accountID);
			args.putParcelable("profileAccount", Parcels.wrap(asi.account.account));
			Nav.go(getActivity(), ProfileFragment.class, args);
		}
	}

	@Override
	protected void doLoadData(int offset, int count) {
		if (collectionID == null) {
			// No ID — must have been pre-populated. Nothing to load.
			onDataLoaded(Collections.emptyList(), false);
			return;
		}

		new GetCollection(collectionID)
				.setCallback(new Callback<>() {
					@Override
					public void onSuccess(GetCollection.Response result) {
						Collection c = result.collection;
						if (result.accounts != null) {
							c.accounts = result.accounts;
						}
						AccountSessionManager.get(accountID).getCacheController().addCollection(c);
						onDataLoaded(Collections.singletonList(c), false);
					}

					@Override
					public void onError(ErrorResponse error) {
						error.showToast(getActivity());
						onDataLoaded(Collections.emptyList(), false);
					}
				})
				.wrapProgress(getActivity(), R.string.loading, true)
				.exec(accountID);
	}

	@Override
	protected Status asStatus(Collection c) {
		return null;
	}

	@Override
	public void onRefresh() {
		super.onRefresh();
	}

	@Subscribe
	public void onCollectionUpdated(CollectionUpdatedEvent ev) {
		if (ev.collectionID.equals(collectionID)) {
			// A collection we're viewing was updated — refresh
			AccountSessionManager.get(accountID).getCacheController().invalidateCollection(collectionID);
			onRefresh();
		}
	}

	@Override
	public Uri getWebUri(Uri.Builder base) {
		Collection c = findFirstDataItem();
		if (c != null && !TextUtils.isEmpty(c.url)) {
			return Uri.parse(c.url);
		}
		return null;
	}

	private Collection getDataItemByID(String id) {
		for (Collection c : data) {
			if (c.id.equals(id)) return c;
		}
		return null;
	}

	private StatusDisplayItem getDisplayItemByID(String id) {
		// Walk the display item list to find the matching item
		// data list is flat Collection items; display items are from buildDisplayItems
		if (list == null || list.getAdapter() == null) return null;
		// The display items are in the adapter — use the RecyclerView's child views
		// Since our display items are flat, find the one whose parentID matches
		int count = list.getAdapter().getItemCount();
		for (int i = 0; i < count; i++) {
			Object holder = list.getChildViewHolder(list.getChildAt(i));
			if (holder instanceof StatusDisplayItem.Holder<?> sdiHolder) {
				StatusDisplayItem sdi = (StatusDisplayItem) sdiHolder.getItem();
				if (id.equals(sdi.parentID)) return sdi;
			}
		}
		return null;
	}

	private Collection findFirstDataItem() {
		return data != null && !data.isEmpty() ? data.get(0) : null;
	}
}