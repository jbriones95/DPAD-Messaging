# Translating DPAD Messaging

DPAD Messaging is a community project, and translations are welcome. The app ships with English and automatically uses your phone's language when a translation is available, so adding a language folder is enough to make the app usable in that language.

**No Git experience is required.** You can contribute by opening an issue with your translations; a maintainer will add the file for you.

## Current status

| Language | Folder | Status |
| --- | --- | --- |
| English | `app/src/main/res/values/` | Source of truth (295 strings) |
| German | `app/src/main/res/values-de/` | Complete (295/295) |
| Spanish | `app/src/main/res/values-es/` | Complete (295/295) |

German and Spanish are complete. More languages are welcome — [open an issue](https://github.com/jbriones95/DPAD-Messaging/issues/new?template=translation.yml) to start one.

## What needs translating

All user-visible text is in [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml). The app currently has **295 strings**, no plurals and no string arrays. The [full list](#full-list-of-english-strings) is at the bottom of this document, grouped by screen.

## How to contribute

### Option 1: Open an issue (easiest)

1. [Open a translation issue](https://github.com/jbriones95/DPAD-Messaging/issues/new?template=translation.yml) — or any issue titled `Translation: <language> (<code>)`, for example `Translation: German (de)`.
2. Paste the strings you translated as `key = text` lines, or attach a `strings.xml` file.
3. Say how you would like to be credited.
4. A maintainer will validate and add the file.

You do not need to translate every string. Untranslated strings fall back to English, so a partial translation is still useful.

### Option 2: Open a pull request

```bash
git clone https://github.com/jbriones95/DPAD-Messaging.git
cd DPAD-Messaging
git checkout -b translation-de
mkdir -p app/src/main/res/values-de
```

Create `app/src/main/res/values-de/strings.xml` with only the strings you translated:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="settings">Einstellungen</string>
    <string name="delete">Löschen</string>
    <string name="new_conversation">Neu</string>
</resources>
```

Then validate and build:

```bash
python3 scripts/check-translations.py
python3 scripts/check-translations.py --list-missing de
./gradlew assembleDebug
```

Commit with a message such as `Add German translation`, and open a pull request. Mention the language code and your credit name in the description.

## Language folder names

| Translation | Folder |
| --- | --- |
| Language only (most common) | `values-de`, `values-fr`, `values-es` |
| Regional variant | `values-pt-rBR`, `values-zh-rCN`, `values-es-rMX` |
| Script variant | `values-b+sr+Latn`, `values-zh-rTW` |

Use the [Android-supported language and region codes](https://developer.android.com/guide/topics/resources/providing-resources#AlternativeResources). If you are unsure, open an issue with the language name and the maintainers will pick the code.

## Translation rules

1. **Keep placeholders exactly.** `%1$s`, `%2$d`, `%d`, `%%` and similar are replaced with values at runtime. You may reorder them if the grammar needs it, but do not remove, rename, or add them.
2. **Keep line breaks.** `\n` starts a new line. You may move it for natural reading.
3. **Escape XML characters.** Write `&amp;` for `&`, `&lt;` for `<`, and `\'` for an apostrophe (or wrap the text in double quotes).
4. **Do not translate `app_name`.** The app is called DPAD SMS in every language.
5. **Keep short labels short.** Buttons, menus and tabs have limited space, especially on small screens. Prefer the shortest natural wording.
6. **Match the app's tone.** It is plain, direct, and avoids jargon.
7. **Right-to-left languages are supported.** Arabic, Hebrew, Persian and Urdu mirror the layout automatically. Check long labels when contributing one of these.
8. **Do not rename or add keys.** Unknown keys fail the check, because they would never be displayed.
9. **Save as UTF-8.** No machine-translation dumps without a review; a reviewed partial translation is better.

## Review checklist

Before opening a pull request, check that:

- `python3 scripts/check-translations.py` passes.
- The XML is well-formed and the file starts with `<?xml version="1.0" encoding="utf-8"?>`.
- Placeholders and `\n` match the English source.
- Buttons and menu labels are short enough on a small screen.
- `./gradlew assembleDebug` succeeds.
- The translation reads naturally and uses consistent terminology.

## Current limitations

- The app follows the phone's language. There is no in-app language picker yet, and Android's per-app language selector requires a locale configuration file; both can be added once the first translations land.
- Dates, times and numbers follow the phone's locale settings.

## Full list of English strings

This list mirrors `app/src/main/res/values/strings.xml`. Keys in `backticks` are the resource names contributors need to use.

### Getting started

| Key | English | Notes |
| --- | --- | --- |
| `app_name` | DPAD SMS | Do not translate (app name) |

### Navigation / Actions

| Key | English | Notes |
| --- | --- | --- |
| `new_conversation` | New | — |
| `new_conversation_long` | New conversation | — |
| `search` | Search | — |
| `settings` | Settings | — |
| `archived` | Archived | — |
| `recycle_bin` | Recycle Bin | — |
| `back` | Back | — |
| `call` | Call | — |
| `send_message` | Send message | — |
| `add_to_contacts` | Add to contacts | — |
| `details` | Details | — |
| `conversation_options` | Conversation options | — |
| `conversation_options_for` | Options for %1$s | Keep placeholders: `%1$s` |
| `unread_messages` | %1$d unread messages | Keep placeholders: `%1$d` |
| `no_unread_messages` | No unread messages | — |
| `pinned_conversation` | Pinned | — |
| `muted_conversation` | Muted | — |
| `ok` | OK | — |
| `cancel` | Cancel | — |
| `save` | Save | — |
| `delete` | Delete | — |
| `yes` | Yes | — |
| `no` | No | — |
| `more` | More | — |

### Conversation List

| Key | English | Notes |
| --- | --- | --- |
| `no_conversations` | No messages yet.\nPress New to start a conversation. | Contains a line break (`\n`) |
| `no_search_results` | No conversations match your search. | — |
| `attachments_selected` | %1$d attachments selected | Keep placeholders: `%1$d` |
| `selection_count` | %1$d selected | Keep placeholders: `%1$d` |
| `select_conversation` | Select conversation | — |
| `loading_conversations` | Loading conversations… | — |
| `conversations_load_failed` | Could not load conversations. | — |
| `retry` | Retry | — |
| `conversation_archived` | Conversation archived | — |
| `undo` | Undo | — |

### Thread / Compose

| Key | English | Notes |
| --- | --- | --- |
| `type_message` | Type a message… | — |
| `send` | Send | — |
| `next` | Next | — |
| `attach` | Attach | — |
| `empty_message` | Empty message | — |
| `message_description_sent` | Sent message: %1$s | Keep placeholders: `%1$s` |
| `message_description_received` | Received message: %1$s | Keep placeholders: `%1$s` |
| `message_context_position` | Message %1$d of %2$d | Keep placeholders: `%1$d`, `%2$d` |
| `schedule` | Schedule | — |
| `schedule_set` | Set time | — |
| `schedule_change` | Change time | — |
| `schedule_cancel` | Cancel schedule | — |
| `scheduled_for` | Send later: %1$s | Keep placeholders: `%1$s` |
| `schedule_picker_title` | Choose send time | — |
| `sending` | Sending… | — |
| `sending_later` | Scheduled | — |
| `message_failed` | Failed to send. Tap to retry. | — |
| `delivered` | Delivered | — |
| `message_copied` | Message copied | — |
| `message_blocked_by_policy` | Message blocked: Contact your admin. | — |

### Context Menu Actions

| Key | English | Notes |
| --- | --- | --- |
| `mark_as_read` | Mark as Read | — |
| `mark_as_unread` | Mark as Unread | — |
| `archive` | Archive | — |
| `unarchive` | Unarchive | — |
| `pin` | Pin | — |
| `unpin` | Unpin | — |
| `block` | Block | — |
| `copy_number` | Copy Number | — |
| `mute_conversation` | Mute Conversation | — |
| `unmute_conversation` | Unmute Conversation | — |
| `move_to_recycle_bin` | Move to Recycle Bin | — |
| `restore` | Restore | — |
| `forward` | Forward | — |
| `copy_text` | Copy Text | — |
| `retry_send` | Retry Send | — |
| `cancel_scheduled_message` | Cancel Scheduled Message | — |
| `reply` | Reply | — |

### Conversation Details

| Key | English | Notes |
| --- | --- | --- |
| `conversation_details` | Conversation Details | — |
| `participants` | Participants | — |
| `participant_count` | %1$d participants | Keep placeholders: `%1$d` |
| `rename_conversation` | Rename Conversation | — |
| `contact_color` | Contact Color | — |
| `contact_color_current` | Current color: %1$s | Keep placeholders: `%1$s` |
| `contact_color_automatic` | Automatic | — |
| `contact_color_option` | Contact color: %1$s | Keep placeholders: `%1$s` |
| `contact_color_option_selected` | Contact color: %1$s, selected | Keep placeholders: `%1$s` |
| `appearance` | Appearance | — |
| `conversation_actions` | Conversation | — |
| `danger_zone` | Danger zone | — |
| `choose_contact_color` | Choose a color | — |
| `enter_recipients_hint` | Add recipients with + or type a number | — |
| `new_thread_hint` | Type number and click center or use the + to add contacts | — |
| `to_label` | To: | — |
| `add_recipient` | Add recipient | — |
| `remove_recipient` | Remove recipient | — |
| `today` | Today | — |
| `yesterday` | Yesterday | — |

### Permissions

| Key | English | Notes |
| --- | --- | --- |
| `permission_denied` | Permission denied. Some features may not work. | — |

### Default SMS App

| Key | English | Notes |
| --- | --- | --- |
| `search_hint` | Search messages… | — |

### Settings

| Key | English | Notes |
| --- | --- | --- |
| `general` | General | — |
| `app_theme` | Theme | — |
| `app_theme_summary` | Choose Light, Dark, or follow System | — |
| `theme_system` | System | — |
| `theme_light` | Light | — |
| `theme_dark` | Dark | — |
| `accent_color` | Accent Color | — |
| `accent_color_summary` | Customize highlight color across the app | — |
| `accent_blue` | Blue | — |
| `accent_green` | Green | — |
| `accent_orange` | Orange | — |
| `accent_rose` | Rose | — |
| `date_format` | Date Format | — |
| `date_format_summary` | Order in which date parts are displayed | — |
| `date_mdy` | MM/DD/YY (American) | — |
| `date_dmy` | DD.MM.YY (European) | — |
| `time_format` | Time Format | — |
| `time_format_summary` | 12-hour or 24-hour clock | — |
| `time_12h` | 12-hour (1:30 PM) | — |
| `time_24h` | 24-hour (13:30) | — |
| `ui_scale` | UI Text Size | — |
| `ui_scale_summary` | Adjust text size for your screen | — |
| `ui_scale_compact` | Compact (smaller) | — |
| `ui_scale_normal` | Normal | — |
| `ui_scale_large` | Large | — |
| `ui_scale_xlarge` | Extra Large | — |
| `attachment_preview_size` | Image Preview Size | — |
| `attachment_preview_size_summary` | Choose the size of images shown in messages | — |
| `attachment_preview_small` | Small | — |
| `attachment_preview_normal` | Normal | — |
| `attachment_preview_large` | Large | — |
| `attach_audio` | Audio | — |
| `attach_voice_message` | Voice message | — |
| `attach_contact` | Contact | — |
| `attach_camera` | Camera | — |
| `photo_from_gallery` | Photo | — |
| `choose_photo` | Choose photo | — |
| `no_photos_found` | No photos found. | — |
| `browse_files` | Browse files | — |
| `photo_picker_permission` | Allow photo access to show your pictures. | — |
| `messaging` | Messaging | — |
| `privacy` | Privacy | — |
| `storage` | Storage | — |
| `other` | Other | — |
| `notifications` | Notifications | — |
| `notifications_summary` | Configure notification channels | — |
| `message_notification_style` | Message Alert Style | — |
| `message_notification_style_summary` | Choose whether new messages appear as top-of-screen banners | — |
| `notification_style_heads_up` | Heads-up banner | — |
| `notification_style_quiet` | Quiet notification | — |
| `message_vibration_style` | Message Vibration | — |
| `message_vibration_style_summary` | Choose the vibration pattern for new message alerts | — |
| `vibration_style_long` | One long buzz | — |
| `vibration_style_double` | Two short buzzes | — |
| `message_notifications` | Message notifications | — |
| `message_notifications_summary` | Incoming SMS and MMS alerts | — |
| `failure_notifications` | Send failure notifications | — |
| `failure_notifications_summary` | Alerts when a message cannot be sent | — |
| `mms_settings` | MMS Settings | — |
| `delivery_reports` | Delivery Reports | — |
| `delivery_reports_summary` | Show when SMS messages are delivered | — |
| `send_on_enter` | Send on Enter | — |
| `send_on_enter_summary` | Press Enter key to send message | — |
| `character_counter` | Character Counter | — |
| `character_counter_summary` | Show SMS length and segment count | — |
| `send_group_message_mms` | Group Text via MMS | — |
| `send_group_message_mms_summary` | Keep text-only group replies in one thread. Turn off to send individual SMS instead. | — |
| `auto_download_mms` | Automatically Download MMS | — |
| `auto_download_mms_summary` | Download incoming pictures and files automatically | — |
| `lock_screen_privacy` | Lock Screen Privacy | — |
| `lock_screen_privacy_summary` | Control what appears in lock screen notifications | — |
| `show_sender_and_message` | Show sender and message | — |
| `show_sender_only` | Show sender only | — |
| `show_no_message_details` | Show no message details | — |
| `silent_unknown_senders` | Silent unknown senders | — |
| `silent_unknown_senders_summary` | Do not play sound or show heads-up alerts for numbers not in contacts | — |
| `manage_blocked_keywords` | Blocked Keywords | — |
| `blocked_keywords_summary` | Block notifications from messages containing keywords | — |
| `manage_blocked_numbers` | Blocked Numbers | — |
| `blocked_numbers_summary` | Block messages from these numbers | — |
| `blocked_numbers` | Blocked Numbers | — |
| `recycle_bin_enabled` | Use Recycle Bin | — |
| `recycle_bin_enabled_summary` | Move deleted messages to recycle bin instead of deleting immediately | — |
| `about` | About | — |
| `version` | Version | — |
| `new_message` | New message | — |
| `mms_download_pending_title` | MMS waiting to download | — |
| `mms_download_pending_text` | Tap Download to get this message. | — |
| `download_mms` | Download | — |

### Blocked Keywords

| Key | English | Notes |
| --- | --- | --- |
| `add_keyword` | Add Keyword | — |
| `enter_keyword` | Enter keyword… | — |
| `no_blocked_keywords` | No blocked keywords. | — |
| `delete_keyword_confirm` | Delete this keyword? | — |
| `add_number` | Add Number | — |
| `enter_number` | Enter number… | — |
| `no_blocked_numbers` | No blocked numbers. | — |
| `delete_number_confirm` | Delete this number? | — |

### Empty States

| Key | English | Notes |
| --- | --- | --- |
| `no_archived` | No archived conversations. | — |
| `no_recycle_bin` | Recycle bin is empty. | — |

### Errors

| Key | English | Notes |
| --- | --- | --- |
| `error_sending_message` | Failed to send message. | — |
| `error_picking_contact` | Could not read selected contact. | — |
| `sms_send_error_generic_failure` | SMS failed: generic network error. | — |
| `sms_send_error_radio_off` | SMS failed: cellular radio is off. | — |
| `sms_send_error_null_pdu` | SMS failed: invalid message payload. | — |
| `sms_send_error_no_service` | SMS failed: no cellular service. | — |
| `sms_send_error_unknown` | SMS failed (code %1$d). | Keep placeholders: `%1$d` |
| `mms_send_error_http` | MMS rejected by carrier (HTTP %1$d). | Keep placeholders: `%1$d` |
| `mms_send_error_unknown` | MMS failed (code %1$d). | Keep placeholders: `%1$d` |
| `scheduled_send_failed_title` | Scheduled message failed | — |
| `scheduled_send_failed_body` | A scheduled message could not be sent. | — |
| `scheduled_send_failed_exact_alarm_body` | Scheduled send failed because exact alarms are unavailable. | — |
| `scheduled_send_inexact_title` | Scheduled send may be delayed | — |
| `scheduled_send_inexact_body` | Exact alarms are unavailable; scheduled send will run approximately on time. | — |

### SIM / Dual-SIM

| Key | English | Notes |
| --- | --- | --- |
| `select_sim` | Select SIM | — |
| `sim_label` | SIM %d | Keep placeholders: `%d` |

### Attachments / MMS Sending

| Key | English | Notes |
| --- | --- | --- |
| `remove_attachment` | Remove attachment | — |
| `image_attached` | Image attached | — |
| `image_attachment_preview` | Image attachment preview | — |
| `scheduled_indicator` | Scheduled indicator | — |
| `message_attachment_preview` | Message attachment preview | — |
| `schedule_step_decrement` | - | Format or symbol only |
| `schedule_step_increment` | + | Format or symbol only |
| `recipient_chip_remove` | %1$s × | Keep placeholders: `%1$s` |
| `sms_char_counter_format` | %1$d/%2$d | Keep placeholders: `%1$d`, `%2$d` |
| `image_viewer_zoom_percent` | %1$d%% | Keep placeholders: `%%`, `%1$d` |
| `image_viewer_content_description` | Image viewer | — |
| `image_viewer_zoom_default` | 100%% | Keep placeholders: `%%`; Format or symbol only |
| `save_photo_to_gallery` | Save to gallery | — |
| `photo_saved_to_gallery` | Photo saved to gallery | — |
| `photo_save_failed` | Could not save photo to gallery | — |
| `choose_attachment_app` | Choose app | — |
| `scheduled_message_canceled` | Scheduled message canceled | — |
| `voice_input` | Voice input | — |
| `voice_record` | Record voice message | — |
| `voice_record_stop` | Stop recording | — |
| `voice_input_unavailable` | Voice input is unavailable on this device. | — |
| `voice_input_empty_result` | No speech was detected. | — |
| `voice_recording_started` | Recording voice message… | — |
| `voice_recording_stopped` | Voice message attached | — |
| `voice_recording_error` | Could not record voice message. | — |
| `voice_recording_too_large` | Voice message is too large for MMS. | — |
| `voice_replay` | Replay voice message | — |
| `voice_replay_stop` | Stop voice replay | — |
| `voice_replay_unavailable` | Could not play the recorded voice message. | — |
| `attachment_too_large_generic` | Attachment is too large for MMS. | — |
| `audio_attachment_preview` | Audio attachment preview | — |
| `attachment_audio_preview` | Audio attachment | — |
| `attachment_generic_preview` | Attachment ready | — |
| `attachment_permission_needed` | Storage permission is required to attach files. | — |
| `audio_playback_unavailable` | No app can play this audio attachment. | — |
| `attachment_open_unavailable` | No app can open this attachment. | — |
| `save_attachments` | Save attachments | — |
| `share_attachment` | Share attachment | — |
| `open_attachment` | Open attachment | — |
| `attachments_saved_count` | Saved %1$d attachment(s). | Keep placeholders: `%1$d` |
| `attachments_save_failed` | Could not save the attachments. | — |
| `attachment_share_unavailable` | Could not share this attachment. | — |
| `experimental_updates` | Experimental updates | — |
| `experimental_updates_summary` | Receive early builds before the general release. | — |
| `schedule_year` | Year | — |
| `schedule_month` | Month | — |
| `schedule_day` | Day | — |
| `schedule_hour` | Hour | — |
| `schedule_minute` | Minute | — |
| `backup` | Backup & Restore | — |
| `backup_create` | Backup to file | — |
| `backup_create_summary` | Save scheduled messages, drafts, recycle bin, blocked lists, and settings to a JSON file | — |
| `backup_restore` | Restore from file | — |
| `backup_restore_summary` | Replace current data with a previously saved backup | — |
| `backup_success` | Backup saved successfully | — |
| `backup_failed` | Backup failed: | — |
| `restore_success` | Restore completed: | — |
| `restore_failed` | Restore failed: | — |
| `recycle_bin_delete_failed` | Could not permanently delete this item. | — |
| `restore_invalid` | Backup file is empty | — |
| `backup_warning_title` | Sensitive Data Warning | — |
| `backup_warning_message` | The backup file contains app-only data: scheduled messages, drafts, recycle-bin items, blocked lists, conversation settings, and app preferences. Your SMS/MMS history is stored by the system and is not included. The file is encrypted and can only be restored on this device, not on another device. Keep the file in a safe place. | — |
| `backup_confirm` | Create Backup | — |
| `backup_queued` | Backup started in the background | — |
| `backup_running` | Creating encrypted backup | — |
| `restore_running` | Restoring encrypted backup | — |
| `backup_complete` | Backup and restore | — |
| `restore_complete` | Restore completed | — |
| `backup_finished` | Operation completed | — |
| `restore_queued` | Restore started in the background | — |
| `restore_preview_title` | Review restore | — |
| `restore_preview_message` | This will replace app-local data with %1$d conversations, %2$d messages, and %3$d attachments. SMS/MMS history in the Telephony provider is not included. | Keep placeholders: `%1$d`, `%2$d`, `%3$d` |
| `restore_confirm` | Restore | — |
| `backup_scope_summary` | App-local data only. Telephony SMS/MMS history is not included. | — |
| `check_for_updates` | Check for updates | — |
| `check_for_updates_summary` | Download and install the latest signed release | — |
| `checking_for_updates` | Checking for updates… | — |
| `downloading_update` | Downloading update… | — |
| `update_download_progress` | Downloaded %1$s of %2$s (%3$d%%) | Keep placeholders: `%%`, `%1$s`, `%2$s`, `%3$d` |
| `update_download_unknown_size` | Downloaded %1$s | Keep placeholders: `%1$s` |
| `app_up_to_date` | You are up to date | — |
| `update_available` | Update available: %1$s | Keep placeholders: `%1$s` |
| `update_available_summary` | A newer release is ready to install. | — |
| `install_update` | Install update | — |
| `update_check_failed` | Update check failed: %1$s | Keep placeholders: `%1$s` |
| `update_installer_unavailable` | Android could not open the package installer | — |

<!-- 295 strings -->

## Keeping this list current

When English strings are added, renamed, or changed, regenerate the inventory:

```bash
python3 scripts/check-translations.py --inventory
```

Replace the generated section (everything from `## Full list of English strings` onward) with the output, and keep the contributor guidance above it.
