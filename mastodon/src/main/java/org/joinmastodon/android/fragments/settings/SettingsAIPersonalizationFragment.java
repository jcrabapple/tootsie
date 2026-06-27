package org.joinmastodon.android.fragments.settings;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ai.AIPersonalizationManager;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.AITopic;
import org.joinmastodon.android.model.TimelineDefinition;
import org.joinmastodon.android.model.viewmodel.CheckableListItem;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.model.viewmodel.SectionHeaderListItem;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import me.grishka.appkit.utils.V;

/**
 * Settings screen for AI Personalization: enable/disable, configure LLM provider,
 * select model, manage inferred topics, set post count.
 */
public class SettingsAIPersonalizationFragment extends BaseSettingsFragment<Void> {
	private CheckableListItem<Void> enableItem;
	private ListItem<Void> postCountItem;
	private ListItem<Void> apiUrlItem;
	private ListItem<Void> apiKeyItem;
	private ListItem<Void> modelItem;
	private ListItem<Void> reinferItem;
	private ListItem<Void> addTopicItem;
	private ListItem<Void> lastUpdatedItem;

	private ArrayList<ListItem<?>> items = new ArrayList<>();
	private ArrayList<AITopic> topics;
	private AccountLocalPreferences prefs;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle(R.string.mo_settings_ai_personalization);

		prefs = AccountSessionManager.get(accountID).getLocalPreferences();
		topics = prefs.aiTopics != null ? new ArrayList<>(prefs.aiTopics) : new ArrayList<>();

		rebuildItems();
	}

	private void rebuildItems() {
		items.clear();

		// Master toggle
		enableItem = new CheckableListItem<>(
				R.string.mo_settings_ai_enable, 0,
				CheckableListItem.Style.SWITCH,
				GlobalUserPreferences.aiPersonalizationEnabled,
				R.drawable.ic_fluent_sparkle_24_regular,
				this::toggleCheckableItem);
		items.add(enableItem);

		// Posts to display
		String postCountStr = prefs.aiPostCount + "";
		postCountItem = new ListItem<>(
				getString(R.string.mo_settings_ai_posts_to_display),
				postCountStr,
				R.drawable.ic_fluent_apps_list_24_regular,
				this::onPostCountClick);
		items.add(postCountItem);

		// Section: LLM Provider
		items.add(new SectionHeaderListItem(R.string.mo_settings_ai_provider));

		// API URL
		String apiUrlDisplay = prefs.aiApiUrl != null ? prefs.aiApiUrl : "https://openrouter.ai/api/v1";
		apiUrlItem = new ListItem<>(
				getString(R.string.mo_settings_ai_api_url),
				apiUrlDisplay,
				R.drawable.ic_link_24px,
				this::onApiUrlClick);
		items.add(apiUrlItem);

		// API Key
		String keyDisplay;
		if (prefs.aiApiKey != null && !prefs.aiApiKey.isBlank()) {
			int len = Math.min(prefs.aiApiKey.length(), 20);
			keyDisplay = "\u2022".repeat(len);
		} else {
			keyDisplay = getString(R.string.mo_settings_ai_no_api_key);
		}
		apiKeyItem = new ListItem<>(
				getString(R.string.mo_settings_ai_api_key),
				keyDisplay,
				R.drawable.ic_fluent_key_24_regular,
				this::onApiKeyClick);
		items.add(apiKeyItem);

		// Model
		String modelDisplay = prefs.aiModel != null ? prefs.aiModel : "openai/gpt-4o-mini";
		modelItem = new ListItem<>(
				getString(R.string.mo_settings_ai_model),
				modelDisplay,
				R.drawable.ic_fluent_bot_24_regular,
				this::onModelClick);
		items.add(modelItem);

		// Section: Inferred Topics
		items.add(new SectionHeaderListItem(R.string.mo_settings_ai_inferred_topics));

		// Last updated
		String lastUpdated;
		if (prefs.aiTopicsLastUpdated == 0) {
			lastUpdated = getString(R.string.mo_settings_ai_never_inferred);
		} else {
			DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
			lastUpdated = getString(R.string.mo_settings_ai_last_updated, df.format(new Date(prefs.aiTopicsLastUpdated)));
		}
		lastUpdatedItem = new ListItem<>(lastUpdated, null, null);
		lastUpdatedItem.isEnabled = false;
		items.add(lastUpdatedItem);

		// Topic chips
		if (topics.isEmpty()) {
			ListItem<Void> noTopics = new ListItem<>(getString(R.string.mo_settings_ai_no_topics), null, null);
			noTopics.isEnabled = false;
			items.add(noTopics);
		} else {
			for (AITopic topic : topics) {
				CheckableListItem<Void> topicItem = new CheckableListItem<>(
						topic.label, null,
						CheckableListItem.Style.SWITCH,
						topic.enabled,
						0,
						item -> {
							topic.enabled = item.checked;
							saveTopics();
						});
				items.add(topicItem);
			}
		}

		// Add custom topic
		addTopicItem = new ListItem<>(
				getString(R.string.mo_settings_ai_add_topic),
				null,
				R.drawable.ic_add_24px,
				this::onAddTopicClick);
		items.add(addTopicItem);

		// Re-infer topics
		reinferItem = new ListItem<>(
				getString(R.string.mo_settings_ai_reinfer_topics),
				null,
				R.drawable.ic_fluent_arrow_sync_24_regular,
				this::onReinferClick);
		items.add(reinferItem);

		// Privacy notice
		items.add(new SectionHeaderListItem(R.string.mo_settings_ai_privacy));
		ListItem<Void> privacyItem = new ListItem<>(
				getString(R.string.mo_settings_ai_privacy_notice),
				null,
				R.drawable.ic_info_24px,
				null);
		privacyItem.isEnabled = false;
		privacyItem.dividerAfter = true;
		items.add(privacyItem);

		//noinspection unchecked
		onDataLoaded((List<ListItem<Void>>) (Object) items);
	}

	// ========== Click Handlers ==========

	private void onPostCountClick(ListItem<?> item) {
		String[] counts = {"5", "10", "15", "20", "25"};
		int selected = -1;
		for (int i = 0; i < counts.length; i++) {
			if (Integer.parseInt(counts[i]) == prefs.aiPostCount) {
				selected = i;
				break;
			}
		}

		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.mo_settings_ai_posts_to_display)
				.setSingleChoiceItems(counts, selected, (dlg, which) -> {
					prefs.aiPostCount = Integer.parseInt(counts[which]);
					prefs.save();
					postCountItem.subtitle = counts[which];
					rebindItem(postCountItem);
					dlg.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void onApiUrlClick(ListItem<?> item) {
		EditText input = new EditText(getActivity());
		input.setText(prefs.aiApiUrl != null ? prefs.aiApiUrl : "https://openrouter.ai/api/v1");
		input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
		LinearLayout container = createDialogContainer(input);

		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.mo_settings_ai_api_url)
				.setView(container)
				.setPositiveButton(R.string.ok, (d, w) -> {
					String value = input.getText().toString().trim();
					if (!value.isEmpty()) {
						prefs.aiApiUrl = value;
						prefs.save();
						apiUrlItem.subtitle = value;
						rebindItem(apiUrlItem);
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void onApiKeyClick(ListItem<?> item) {
		EditText input = new EditText(getActivity());
		if (prefs.aiApiKey != null) {
			input.setText(prefs.aiApiKey);
		}
		input.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
		LinearLayout container = createDialogContainer(input);

		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.mo_settings_ai_api_key)
				.setView(container)
				.setPositiveButton(R.string.ok, (d, w) -> {
					String value = input.getText().toString().trim();
					prefs.aiApiKey = value.isEmpty() ? null : value;
					prefs.save();
					if (value.isEmpty()) {
						apiKeyItem.subtitle = getString(R.string.mo_settings_ai_no_api_key);
					} else {
						apiKeyItem.subtitle = "\u2022".repeat(Math.min(value.length(), 20));
					}
					rebindItem(apiKeyItem);
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void onModelClick(ListItem<?> item) {
		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.mo_settings_ai_model)
				.setPositiveButton(R.string.mo_settings_ai_select_model, (d, w) -> openModelPicker())
				.setNegativeButton(R.string.mo_settings_ai_enter_model_manually, (d, w) -> openManualModelEntry())
				.show();
	}

	private void openModelPicker() {
		Toast.makeText(getActivity(), R.string.mo_settings_ai_loading_models, Toast.LENGTH_SHORT).show();

		AIPersonalizationManager.fetchModels(accountID,
				models -> {
					if (getActivity() == null) return;
					if (models.isEmpty()) {
						Toast.makeText(getActivity(), "No models found", Toast.LENGTH_SHORT).show();
						return;
					}
					showModelPage(models, 0);
				},
				error -> {
					if (getActivity() == null) return;
					Toast.makeText(getActivity(),
							getString(R.string.mo_settings_ai_loading_models_error, error),
							Toast.LENGTH_LONG).show();
					openManualModelEntry();
				});
	}

	private void showModelPage(List<AIPersonalizationManager.ModelInfo> models, int page) {
		int pageSize = 20;
		int start = page * pageSize;
		int end = Math.min(start + pageSize, models.size());

		if (start >= models.size()) return;

		List<AIPersonalizationManager.ModelInfo> pageModels = models.subList(start, end);
		String[] labels = new String[pageModels.size()];
		int selected = -1;
		for (int i = 0; i < pageModels.size(); i++) {
			AIPersonalizationManager.ModelInfo m = pageModels.get(i);
			labels[i] = m.name != null && !m.name.equals(m.id) ? m.name + " (" + m.id + ")" : m.id;
			if (m.id.equals(prefs.aiModel)) selected = i;
		}

		AlertDialog.Builder builder = new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.mo_settings_ai_select_model)
				.setSingleChoiceItems(labels, selected, (dlg, which) -> {
					AIPersonalizationManager.ModelInfo chosen = pageModels.get(which);
					prefs.aiModel = chosen.id;
					prefs.save();
					modelItem.subtitle = chosen.id;
					rebindItem(modelItem);
					dlg.dismiss();
				})
				.setNegativeButton(R.string.cancel, null);

		if (end < models.size()) {
			builder.setNeutralButton("More", (dlg, which) -> {
				dlg.dismiss();
				showModelPage(models, page + 1);
			});
		}

		if (page == 0) {
			builder.setPositiveButton(R.string.mo_settings_ai_enter_model_manually, (dlg, which) -> {
				dlg.dismiss();
				openManualModelEntry();
			});
		}

		builder.show();
	}

	private void openManualModelEntry() {
		EditText input = new EditText(getActivity());
		if (prefs.aiModel != null) {
			input.setText(prefs.aiModel);
		}
		input.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
		LinearLayout container = createDialogContainer(input);

		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.mo_settings_ai_model_id)
				.setView(container)
				.setPositiveButton(R.string.ok, (d, w) -> {
					String value = input.getText().toString().trim();
					if (!value.isEmpty()) {
						prefs.aiModel = value;
						prefs.save();
						modelItem.subtitle = value;
						rebindItem(modelItem);
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void onAddTopicClick(ListItem<?> item) {
		EditText input = new EditText(getActivity());
		input.setInputType(InputType.TYPE_CLASS_TEXT);
		LinearLayout container = createDialogContainer(input);

		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.mo_settings_ai_add_topic)
				.setView(container)
				.setPositiveButton(R.string.ok, (d, w) -> {
					String value = input.getText().toString().trim();
					if (!value.isEmpty()) {
						AITopic newTopic = new AITopic(value, true);
						newTopic.enabled = true;
						boolean exists = topics.stream().anyMatch(t -> t.label.equalsIgnoreCase(value));
						if (!exists) {
							topics.add(newTopic);
							saveTopics();
							rebuildItems();
						}
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void onReinferClick(ListItem<?> item) {
		Toast.makeText(getActivity(), R.string.mo_settings_ai_inferring, Toast.LENGTH_SHORT).show();

		AIPersonalizationManager.inferTopics(accountID,
				newTopics -> {
					if (getActivity() == null) return;
					topics = new ArrayList<>(newTopics);
					saveTopics();
					rebuildItems();
					Toast.makeText(getActivity(), "Inferred " + topics.size() + " topics", Toast.LENGTH_SHORT).show();
				},
				error -> {
					if (getActivity() == null) return;
					Toast.makeText(getActivity(),
							getString(R.string.mo_settings_ai_inferring_error, error),
							Toast.LENGTH_LONG).show();
				});
	}

	// ========== Helpers ==========

	private void updatePersonalTimelineInAllAccounts(boolean enabled) {
		TimelineDefinition personal = TimelineDefinition.PERSONAL_TIMELINE;
		for (AccountSession session : AccountSessionManager.getInstance().getLoggedInAccounts()) {
			AccountLocalPreferences prefs = session.getLocalPreferences();
			ArrayList<TimelineDefinition> timelines = prefs.timelines;
			if (timelines == null) {
				timelines = TimelineDefinition.getDefaultTimelines(session.getID());
				prefs.timelines = timelines;
			}
			boolean contains = false;
			for (TimelineDefinition tl : timelines) {
				if (tl.equals(personal)) {
					contains = true;
					break;
				}
			}
			if (enabled && !contains) {
				timelines.add(personal.copy());
				prefs.save();
			} else if (!enabled && contains) {
				timelines.removeIf(tl -> tl.equals(personal));
				if (timelines.isEmpty()) {
					timelines.add(TimelineDefinition.HOME_TIMELINE.copy());
				}
				prefs.save();
			}
		}
	}

	private void saveTopics() {
		prefs.aiTopics = new ArrayList<>(topics);
		prefs.save();
	}

	private LinearLayout createDialogContainer(View child) {
		LinearLayout container = new LinearLayout(getActivity());
		container.setOrientation(LinearLayout.VERTICAL);
		container.setPadding(V.dp(24), V.dp(16), V.dp(24), V.dp(8));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		child.setLayoutParams(lp);
		container.addView(child);
		return container;
	}

	@Override
	protected void doLoadData(int offset, int count) {}

	@Override
	protected void onHidden() {
		super.onHidden();
		boolean wasEnabled = GlobalUserPreferences.aiPersonalizationEnabled;
		boolean nowEnabled = enableItem.checked;
		GlobalUserPreferences.aiPersonalizationEnabled = nowEnabled;
		GlobalUserPreferences.save();
		if (wasEnabled != nowEnabled) {
			updatePersonalTimelineInAllAccounts(nowEnabled);
			new M3AlertDialogBuilder(getActivity())
					.setTitle(R.string.mo_settings_ai_personalization)
					.setMessage("The app needs to restart to apply this change.")
					.setPositiveButton(R.string.ok, (d, w) -> UiUtils.restartApp())
					.setNegativeButton(R.string.cancel, null)
					.setCancelable(true)
					.show();
		}
		saveTopics();
	}
}
