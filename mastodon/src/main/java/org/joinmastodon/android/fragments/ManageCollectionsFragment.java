package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageButton;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.collections.DeleteCollection;
import org.joinmastodon.android.api.requests.collections.GetCollections;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.CollectionCreatedEvent;
import org.joinmastodon.android.events.CollectionDeletedEvent;
import org.joinmastodon.android.events.CollectionUpdatedEvent;
import org.joinmastodon.android.fragments.settings.BaseSettingsFragment;
import org.joinmastodon.android.model.Collection;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.model.viewmodel.ListItemWithOptionsMenu;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.parceler.Parcels;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.api.SimpleCallback;

/**
 * TOOTSIE: FEP-7aa9 / Mastodon 4.6 — list of collections owned by the
 * current account. Modeled on {@link ManageListsFragment} but for
 * Collections. Each row shows the collection title with a kebab menu
 * (Edit / Delete) and opens {@link CollectionDetailFragment} on tap.
 *
 * <p>The FAB launches {@link CreateCollectionFragment} for creating a new
 * collection. Otto events keep the list in sync: created collections are
 * appended, updated titles are rebound, deleted collections are removed.
 *
 * <p>Entry point: the "Manage collections" menu item on the user's own
 * profile menu ({@code profile_own.xml}).
 */
public class ManageCollectionsFragment extends BaseSettingsFragment<Collection> implements ListItemWithOptionsMenu.OptionsMenuListener<Collection>{
	private ImageButton fab;

	public ManageCollectionsFragment(){
		setListLayoutId(R.layout.recycler_fragment_with_fab);
	}

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setTitle(R.string.manage_collections);
		loadData();
		setRefreshEnabled(true);
		E.register(this);
	}

	@Override
	public void onDestroy(){
		super.onDestroy();
		E.unregister(this);
	}

	@Override
	protected void doLoadData(int offset, int count){
		new GetCollections(getArguments().getString("account"))
			.setCallback(new SimpleCallback<>(this){
				@Override
				public void onSuccess(GetCollections.Response result){
					onDataLoaded(result.collections.stream().map(ManageCollectionsFragment.this::makeItem).collect(Collectors.toList()), false);
				}
			})
			.exec(accountID);
	}

	private ListItem<Collection> makeItem(Collection c){
		String subtitle=getResources().getQuantityString(R.plurals.collection_member_count, c.itemCount, c.itemCount);
		return new ListItemWithOptionsMenu<>(c.name, subtitle, ManageCollectionsFragment.this, R.drawable.ic_list_alt_24px, ManageCollectionsFragment.this::onCollectionClick, c, false);
	}

	private void onCollectionClick(ListItemWithOptionsMenu<Collection> item){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		args.putString("collectionID", item.parentObject.id);
		args.putParcelable("collection", Parcels.wrap(item.parentObject));
		Nav.go(getActivity(), CollectionDetailFragment.class, args);
	}

	@Override
	public void onConfigureListItemOptionsMenu(ListItemWithOptionsMenu<Collection> item, Menu menu){
		menu.add(0, R.id.edit, 0, R.string.edit_collection);
		menu.add(0, R.id.delete, 1, R.string.delete_collection);
	}

	@Override
	public void onListItemOptionSelected(ListItemWithOptionsMenu<Collection> item, MenuItem menuItem){
		int id=menuItem.getItemId();
		if(id==R.id.edit){
			Bundle args=new Bundle();
			args.putString("account", accountID);
			args.putParcelable("collection", Parcels.wrap(item.parentObject));
			Nav.go(getActivity(), CreateCollectionFragment.class, args);
		}else if(id==R.id.delete){
			new M3AlertDialogBuilder(getActivity())
					.setTitle(R.string.delete_collection)
					.setMessage(getString(R.string.delete_collection_confirm, item.parentObject.name))
					.setPositiveButton(R.string.delete, (dlg, which)->doDeleteCollection(item.parentObject))
					.setNegativeButton(R.string.cancel, null)
					.show();
		}
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		fab=view.findViewById(R.id.fab);
		fab.setImageResource(R.drawable.ic_add_24px);
		fab.setContentDescription(getString(R.string.create_collection));
		fab.setOnClickListener(v->onFabClick());
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		super.onApplyWindowInsets(insets);
		UiUtils.applyBottomInsetToFAB(fab, insets);
	}

	private void doDeleteCollection(Collection collection){
		new DeleteCollection(collection.id)
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(Void result){
						AccountSessionManager.get(accountID).getCacheController().invalidateCollection(collection.id);
						E.post(new CollectionDeletedEvent(accountID, collection.id));
						for(int i=0;i<data.size();i++){
							if(data.get(i).parentObject.id.equals(collection.id)){
								data.remove(i);
								itemsAdapter.notifyItemRemoved(i);
								break;
							}
						}
					}

					@Override
					public void onError(ErrorResponse error){
						Activity activity=getActivity();
						if(activity==null)
							return;
						error.showToast(activity);
					}
				})
				.wrapProgress(getActivity(), R.string.loading, true)
				.exec(accountID);
	}

	@Subscribe
	public void onCollectionUpdated(CollectionUpdatedEvent ev){
		if(!ev.accountID.equals(accountID))
			return;
		for(ListItem<Collection> item:data){
			if(item.parentObject.id.equals(ev.collectionID)){
				item.parentObject=ev.collection;
				item.title=ev.collection.name;
				item.subtitle=getResources().getQuantityString(R.plurals.collection_member_count, ev.collection.itemCount, ev.collection.itemCount);
				rebindItem(item);
				break;
			}
		}
	}

	@Subscribe
	public void onCollectionDeleted(CollectionDeletedEvent ev){
		if(!ev.accountID.equals(accountID))
			return;
		int i=0;
		for(ListItem<Collection> item:data){
			if(item.parentObject.id.equals(ev.collectionID)){
				data.remove(i);
				itemsAdapter.notifyItemRemoved(i);
				break;
			}
			i++;
		}
	}

	@Subscribe
	public void onCollectionCreated(CollectionCreatedEvent ev){
		if(!ev.accountID.equals(accountID))
			return;
		ListItem<Collection> item=makeItem(ev.collection);
		data.add(item);
		((List<ListItem<Collection>>)data).sort(Comparator.comparing(l->l.parentObject.name));
		itemsAdapter.notifyItemInserted(data.indexOf(item));
	}

	private void onFabClick(){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		Nav.go(getActivity(), CreateCollectionFragment.class, args);
	}
}
