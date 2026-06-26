package org.joinmastodon.android.fragments;

import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.collections.AddAccountsToCollection;
import org.joinmastodon.android.api.requests.collections.GetCollectionItems;
import org.joinmastodon.android.api.requests.collections.RemoveAccountFromCollection;
import org.joinmastodon.android.events.CollectionUpdatedEvent;
import org.joinmastodon.android.fragments.account_list.AddNewListMembersFragment;
import org.joinmastodon.android.fragments.account_list.BaseAccountListFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Collection;
import org.joinmastodon.android.model.InteractionPolicy;
import org.joinmastodon.android.model.HeaderPaginationList;
import org.joinmastodon.android.model.Relationship;
import org.joinmastodon.android.model.viewmodel.AccountViewModel;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.Snackbar;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.viewholders.AccountViewHolder;
import org.joinmastodon.android.ui.views.CurlyArrowEmptyView;
import org.parceler.Parcels;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.api.SimpleCallback;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.FragmentRootLinearLayout;

/**
 * TOOTSIE: FEP-7aa9 / Mastodon 4.6 — manage members of a Collection.
 *
 * <p>Shows the current members of a collection with Add/Remove buttons, and
 * provides a search overlay ({@link AddCollectionMembersSearchFragment}) for
 * finding new members to add. The add flow enforces the target account's
 * {@code interactionPolicy.canFeature} setting:
 *
 * <ul>
 *   <li><b>PUBLIC</b> — add immediately without confirmation</li>
 *   <li><b>FOLLOWERS</b> — reject if the viewer doesn't follow the account;
 *       otherwise add</li>
 *   <li><b>MANUAL</b> — show a "request sent" dialog; the account owner
 *       will approve or deny</li>
 * </ul>
 *
 * <p>Accounts with {@code discoverable == false} are blocked from being
 * added with an inline notice.
 *
 * <p>Modeled on {@link CreateListAddMembersFragment} but for Collections.
 * Collection membership cap is 25 (enforced server-side; we check locally
 * too).
 */
public class AddToCollectionFragment extends BaseAccountListFragment implements AddNewListMembersFragment.Listener{
	private Collection collection;
	private Button doneButton;
	private View buttonBar;
	private FragmentRootLinearLayout rootView;
	private FrameLayout searchFragmentContainer;
	private FrameLayout fragmentContentWrap;
	private AddCollectionMembersSearchFragment searchFragment;
	private WindowInsets lastInsets;
	private boolean dismissingSearchFragment;
	private HashSet<String> accountIDsInCollection=new HashSet<>();

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setTitle(R.string.collection_members);
		setLayout(R.layout.fragment_login);
		setEmptyText(R.string.collection_no_members);
		setHasOptionsMenu(true);

		collection=Parcels.unwrap(getArguments().getParcelable("collection"));
		if(savedInstanceState!=null || getArguments().getBoolean("needLoadMembers", false)){
			loadData();
		}else{
			onDataLoaded(List.of());
		}
	}

	@Override
	protected void doLoadData(int offset, int count){
		currentRequest=new GetCollectionItems(collection.id, null, 0)
				.setCallback(new SimpleCallback<>(this){
					@Override
					public void onSuccess(List<Account> result){
						for(Account acc:result)
							accountIDsInCollection.add(acc.id);
						onDataLoaded(result.stream().map(a->new AccountViewModel(a, accountID, getActivity())).collect(Collectors.toList()));
					}
				})
				.exec(accountID);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
		View view=super.onCreateView(inflater, container, savedInstanceState);
		FrameLayout wrapper=new FrameLayout(getActivity());
		wrapper.addView(view);
		rootView=(FragmentRootLinearLayout) view;
		fragmentContentWrap=wrapper;
		return wrapper;
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		doneButton=view.findViewById(R.id.btn_next);
		doneButton.setOnClickListener(this::onDoneClick);
		doneButton.setText(R.string.done);
		buttonBar=view.findViewById(R.id.button_bar);

		super.onViewCreated(view, savedInstanceState);
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		lastInsets=insets;
		if(searchFragment!=null)
			searchFragment.onApplyWindowInsets(insets);
		insets=UiUtils.applyBottomInsetToFixedView(buttonBar, insets);
		rootView.dispatchApplyWindowInsets(insets);
	}

	@Override
	protected List<View> getViewsForElevationEffect(){
		return List.of(getToolbar(), buttonBar);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater){
		MenuItem item=menu.add(R.string.add_member);
		item.setIcon(R.drawable.ic_add_24px);
		item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		showSearchFragment();
		return true;
	}

	private void showSearchFragment(){
		if(searchFragment!=null)
			return;
		searchFragmentContainer=new FrameLayout(getActivity());
		searchFragment=new AddCollectionMembersSearchFragment(this);
		Bundle args=new Bundle();
		args.putString("account", accountID);
		searchFragment.setArguments(args);
		searchFragmentContainer.setLayoutTransition(new android.animation.LayoutTransition());
		searchFragmentContainer.addView(searchFragment.onCreateView(getLayoutInflater(), searchFragmentContainer, null));
		getChildFragmentManager().beginTransaction().add(searchFragmentContainer.getId(), searchFragment).commit();
		getChildFragmentManager().executePendingTransactions();
		searchFragment.onAttach(getActivity());
		searchFragment.onCreate(null);
		searchFragment.onViewCreated(searchFragment.getView(), null);
		((ViewGroup)fragmentContentWrap).addView(searchFragmentContainer);
		searchFragmentContainer.setTranslationY(V.dp(100));
		searchFragmentContainer.animate().translationY(0).setInterpolator(CubicBezierInterpolator.DEFAULT).setDuration(200).start();
		rootView.setAlpha(0.5f);
	}

	private void dismissSearchFragment(){
		if(searchFragment==null)
			return;
		dismissingSearchFragment=true;
		searchFragmentContainer.animate().translationY(V.dp(100)).setInterpolator(CubicBezierInterpolator.DEFAULT).setDuration(200).withEndAction(()->{
			((ViewGroup)fragmentContentWrap).removeView(searchFragmentContainer);
			getChildFragmentManager().beginTransaction().remove(searchFragment).commit();
			searchFragmentContainer=null;
			searchFragment=null;
			dismissingSearchFragment=false;
		}).start();
		rootView.setAlpha(1f);
		getActivity().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(contentView.getWindowToken(), 0);
	}

	private void onDoneClick(View v){
		Nav.finish(this);
	}

	@Override
	public boolean isAccountInList(AccountViewModel account){
		return accountIDsInCollection.contains(account.account.id);
	}

	@Override
	public void addAccountToList(AccountViewModel account, Runnable onDone){
		Account target=account.account;

		// Check: account must be discoverable
		if(!target.discoverable){
			new Snackbar.Builder(getActivity())
					.setText(R.string.collection_not_discoverable)
					.show();
			if(onDone!=null) onDone.run();
			return;
		}

		// Check: collection not full (25 max)
		if(accountIDsInCollection.size()>=25){
			new Snackbar.Builder(getActivity())
					.setText(R.string.collection_max_members)
					.show();
			if(onDone!=null) onDone.run();
			return;
		}

		// Enforce interactionPolicy.canFeature
		InteractionPolicy policy=target.interactionPolicy;
		if(policy!=null){
			InteractionPolicy.CanFeature canFeature=policy.canFeature;
			if(canFeature==InteractionPolicy.CanFeature.MANUAL){
				// Show "request sent" dialog — the server queues it for approval
				new M3AlertDialogBuilder(getActivity())
						.setTitle(R.string.add_to_collection)
						.setMessage(getString(R.string.collection_can_feature_manual)+"\n\n"+getString(R.string.collection_manual_pending))
						.setPositiveButton(R.string.ok, null)
						.show();
				// Still send the request — the server handles the approval queue
			}else if(canFeature==InteractionPolicy.CanFeature.FOLLOWERS){
				Relationship rel=relationships.get(target.id);
				if(rel!=null && !rel.following){
					new M3AlertDialogBuilder(getActivity())
							.setTitle(R.string.add_to_collection)
							.setMessage(R.string.collection_not_following)
							.setPositiveButton(R.string.ok, null)
							.show();
					if(onDone!=null) onDone.run();
					return;
				}
			}
			// PUBLIC → add without confirmation
		}

		new AddAccountsToCollection(collection.id, Set.of(target.id))
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(Void result){
						accountIDsInCollection.add(target.id);
						if(onDone!=null)
							onDone.run();
						int i=0;
						for(AccountViewModel acc:data){
							if(acc.account.id.equals(target.id)){
								list.getAdapter().notifyItemChanged(i);
								return;
							}
							i++;
						}
						int pos=data.size();
						data.add(account);
						list.getAdapter().notifyItemInserted(pos);
					}

					@Override
					public void onError(ErrorResponse error){
						error.showToast(getActivity());
					}
				})
				.exec(accountID);
	}

	@Override
	public void removeAccountAccountFromList(AccountViewModel account, Runnable onDone){
		new RemoveAccountFromCollection(collection.id, account.account.id)
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(Void result){
						accountIDsInCollection.remove(account.account.id);
						if(onDone!=null)
							onDone.run();
						int i=0;
						for(AccountViewModel acc:data){
							if(acc.account.id.equals(account.account.id)){
								list.getAdapter().notifyItemChanged(i);
								return;
							}
							i++;
						}
					}

					@Override
					public void onError(ErrorResponse error){
						error.showToast(getActivity());
					}
				})
				.exec(accountID);
	}

	@Override
	protected void onConfigureViewHolder(AccountViewHolder holder){
		holder.setStyle(AccountViewHolder.AccessoryType.CUSTOM_BUTTON, false);
		holder.setOnLongClickListener(vh->false);
		Button button=holder.getButton();
		button.setPadding(V.dp(24), 0, V.dp(24), 0);
		button.setMinimumWidth(0);
		button.setMinWidth(0);
		button.setOnClickListener(v->{
			holder.setActionProgressVisible(true);
			holder.itemView.setHasTransientState(true);
			Runnable onDone=()->{
				holder.setActionProgressVisible(false);
				holder.itemView.setHasTransientState(false);
				onBindViewHolder(holder);
			};
			AccountViewModel account=holder.getItem();
			if(isAccountInList(account)){
				removeAccountAccountFromList(account, onDone);
			}else{
				addAccountToList(account, onDone);
			}
		});
	}

	@Override
	protected void onBindViewHolder(AccountViewHolder holder){
		Button button=holder.getButton();
		int textRes, styleRes;
		if(isAccountInList(holder.getItem())){
			textRes=R.string.remove;
			styleRes=R.style.Widget_Mastodon_M3_Button_Tonal_Error;
		}else{
			textRes=R.string.add;
			styleRes=R.style.Widget_Mastodon_M3_Button_Filled;
		}
		button.setText(textRes);
		TypedArray ta=button.getContext().obtainStyledAttributes(styleRes, new int[]{android.R.attr.background});
		button.setBackground(ta.getDrawable(0));
		ta.recycle();
		ta=button.getContext().obtainStyledAttributes(styleRes, new int[]{android.R.attr.textColor});
		button.setTextColor(ta.getColorStateList(0));
		ta.recycle();
	}

	@Override
	protected void loadRelationships(List<AccountViewModel> accounts){
		// We need relationships to check canFeature FOLLOWERS policy
		Set<String> ids=accounts.stream().map(ai->ai.account.id).collect(Collectors.toSet());
		if(ids.isEmpty())
			return;
		org.joinmastodon.android.api.requests.accounts.GetAccountRelationships req=new org.joinmastodon.android.api.requests.accounts.GetAccountRelationships(ids);
		relationshipsRequests.add(req);
		req.setCallback(new Callback<>(){
			@Override
			public void onSuccess(List<Relationship> result){
				relationshipsRequests.remove(req);
				for(Relationship rel:result){
					relationships.put(rel.id, rel);
				}
			}

			@Override
			public void onError(ErrorResponse error){
				relationshipsRequests.remove(req);
			}
		}).exec(accountID);
	}

	@Override
	public Uri getWebUri(Uri.Builder base){
		return null;
	}
}
