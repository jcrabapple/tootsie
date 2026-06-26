package org.joinmastodon.android.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.collections.CreateCollection;
import org.joinmastodon.android.api.requests.collections.UpdateCollection;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.CollectionCreatedEvent;
import org.joinmastodon.android.events.CollectionUpdatedEvent;
import org.joinmastodon.android.fragments.settings.BaseSettingsFragment;
import org.joinmastodon.android.model.Collection;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.FloatingHintEditTextLayout;
import org.parceler.Parcels;

import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.MergeRecyclerAdapter;
import me.grishka.appkit.utils.SingleViewRecyclerAdapter;

import java.util.List;

/**
 * TOOTSIE: FEP-7aa9 / Mastodon 4.6 — create or edit a Collection.
 *
 * <p>Used for both creating a new collection and editing an existing one.
 * If a {@code Collection} is passed via the {@code "collection"} argument
 * (parcelized), the fragment is in edit mode — fields are pre-populated and
 * the title shows {@code edit_collection}. Otherwise it's a create form.
 *
 * <p>Fields: title (required), description (optional, multi-line), language
 * (optional, ISO 639-1 like "en"), and tag (optional, hashtag name without #).
 *
 * <p>On submit, fires {@link CreateCollection} or {@link UpdateCollection},
 * posts a {@link CollectionCreatedEvent} or {@link CollectionUpdatedEvent},
 * caches the result, and finishes the fragment.
 *
 * <p>Modeled on {@link CreateListFragment} but standalone (not extending
 * {@link BaseEditListFragment}) because Collections have different fields
 * (no replies-policy or exclusive toggle; has description/language/tag).
 */
public class CreateCollectionFragment extends BaseSettingsFragment<Void>{
	private Button nextButton;
	private View buttonBar;
	private Collection existingCollection;

	private FloatingHintEditTextLayout titleEditLayout;
	private EditText titleEdit;
	private FloatingHintEditTextLayout descriptionEditLayout;
	private EditText descriptionEdit;
	private FloatingHintEditTextLayout languageEditLayout;
	private EditText languageEdit;
	private FloatingHintEditTextLayout tagEditLayout;
	private EditText tagEdit;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		existingCollection=Parcels.unwrap(getArguments().getParcelable("collection"));
		setTitle(existingCollection!=null ? R.string.edit_collection : R.string.create_collection);
		setLayout(R.layout.fragment_login);
	}

	@Override
	protected void doLoadData(int offset, int count){}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		nextButton=view.findViewById(R.id.btn_next);
		nextButton.setOnClickListener(this::onSubmitClick);
		nextButton.setText(existingCollection!=null ? R.string.save : R.string.create);
		buttonBar=view.findViewById(R.id.button_bar);
		super.onViewCreated(view, savedInstanceState);
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		super.onApplyWindowInsets(UiUtils.applyBottomInsetToFixedView(buttonBar, insets));
	}

	@Override
	protected List<View> getViewsForElevationEffect(){
		return List.of(getToolbar(), buttonBar);
	}

	@Override
	protected RecyclerView.Adapter<?> getAdapter(){
		LinearLayout topView=new LinearLayout(getActivity());
		topView.setOrientation(LinearLayout.VERTICAL);

		// Title
		titleEditLayout=(FloatingHintEditTextLayout) getActivity().getLayoutInflater().inflate(R.layout.floating_hint_edit_text, topView, false);
		titleEdit=titleEditLayout.findViewById(R.id.edit);
		titleEdit.setHint(R.string.collection_name);
		titleEditLayout.updateHint();
		if(existingCollection!=null)
			titleEdit.setText(existingCollection.title);
		topView.addView(titleEditLayout);

		// Description (multi-line)
		descriptionEditLayout=(FloatingHintEditTextLayout) getActivity().getLayoutInflater().inflate(R.layout.floating_hint_edit_text, topView, false);
		descriptionEdit=descriptionEditLayout.findViewById(R.id.edit);
		descriptionEdit.setHint(R.string.collection_description_hint);
		descriptionEditLayout.updateHint();
		descriptionEdit.setSingleLine(false);
		descriptionEdit.setMinLines(2);
		if(existingCollection!=null && existingCollection.description!=null)
			descriptionEdit.setText(existingCollection.description);
		topView.addView(descriptionEditLayout);

		// Language
		languageEditLayout=(FloatingHintEditTextLayout) getActivity().getLayoutInflater().inflate(R.layout.floating_hint_edit_text, topView, false);
		languageEdit=languageEditLayout.findViewById(R.id.edit);
		languageEdit.setHint(R.string.collection_language_hint);
		languageEditLayout.updateHint();
		if(existingCollection!=null && existingCollection.language!=null)
			languageEdit.setText(existingCollection.language);
		topView.addView(languageEditLayout);

		// Tag (hashtag name without #)
		tagEditLayout=(FloatingHintEditTextLayout) getActivity().getLayoutInflater().inflate(R.layout.floating_hint_edit_text, topView, false);
		tagEdit=tagEditLayout.findViewById(R.id.edit);
		tagEdit.setHint(R.string.collection_tag_hint);
		tagEditLayout.updateHint();
		if(existingCollection!=null && existingCollection.tag!=null)
			tagEdit.setText(existingCollection.tag.name);
		topView.addView(tagEditLayout);

		MergeRecyclerAdapter adapter=new MergeRecyclerAdapter();
		adapter.addAdapter(new SingleViewRecyclerAdapter(topView));
		// No settings list items — just the form above
		return adapter;
	}

	@Override
	protected int indexOfItemsAdapter(){
		return 0;
	}

	private void onSubmitClick(View v){
		String title=titleEdit.getText().toString().trim();
		if(TextUtils.isEmpty(title)){
			titleEditLayout.setErrorState(getString(R.string.required_form_field_blank));
			return;
		}
		String description=descriptionEdit.getText().toString().trim();
		String language=languageEdit.getText().toString().trim();
		String tag=tagEdit.getText().toString().trim();
		// Strip leading # if user typed it
		if(tag.startsWith("#"))
			tag=tag.substring(1);

		if(existingCollection==null){
			// Create
			new CreateCollection(title, description, language, tag)
					.setCallback(new Callback<>(){
						@Override
						public void onSuccess(Collection result){
							AccountSessionManager.get(accountID).getCacheController().addCollection(result);
							E.post(new CollectionCreatedEvent(accountID, result));
							Nav.finish(CreateCollectionFragment.this);
						}

						@Override
						public void onError(ErrorResponse error){
							error.showToast(getActivity());
						}
					})
					.wrapProgress(getActivity(), R.string.loading, true)
					.exec(accountID);
		}else{
			// Update — only if something changed
			String oldDesc=existingCollection.description!=null ? existingCollection.description : "";
			String oldLang=existingCollection.language!=null ? existingCollection.language : "";
			String oldTag=existingCollection.tag!=null ? existingCollection.tag.name : "";
			if(!title.equals(existingCollection.title) || !description.equals(oldDesc) || !language.equals(oldLang) || !tag.equals(oldTag)){
				new UpdateCollection(existingCollection.id, title, description, language, tag)
						.setCallback(new Callback<>(){
							@Override
							public void onSuccess(Collection result){
								AccountSessionManager.get(accountID).getCacheController().addCollection(result);
								E.post(new CollectionUpdatedEvent(accountID, result.id, result));
								Nav.finish(CreateCollectionFragment.this);
							}

							@Override
							public void onError(ErrorResponse error){
								error.showToast(getActivity());
							}
						})
						.wrapProgress(getActivity(), R.string.loading, true)
						.exec(accountID);
			}else{
				// Nothing changed — just finish
				Nav.finish(this);
			}
		}
		getActivity().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(contentView.getWindowToken(), 0);
	}
}
