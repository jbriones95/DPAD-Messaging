# Changelog

## 0.5.9.1

- Fixes a crash for anyone who installed an experimental build that used a newer message database layout. The app can read the newer layout again and keeps existing messages and app data.
- This update is only needed if the app closes immediately after opening; it does not change normal messaging behavior.

## 0.5.9

- Reply directly to a message from its notification, without opening the app. Watches and companion apps that support notification actions can offer Reply, voice replies and canned replies.
- Checking for updates now shows download progress, so a slow or failed download is visible instead of appearing to hang.
- If a message cannot be sent, the text is put back in the message box and the error is shown, so it is not lost.
- Release builds are verified against the expected signing certificate before publishing.

## 0.5.8

- Read status and message counts refresh more reliably when you open or read a conversation, including when a new message arrives while it is open.
- Contact names can still load when a device's Contacts app does not support photo lookups.
- D-pad users can move from the conversation name field to Save when renaming.
- Search stays filtered when the conversation list refreshes.
- Failed retries no longer remove the original failed message unless a replacement send starts. Scheduled-send failures are recorded more reliably.
- Recycle Bin keeps conversations and MMS attachments available until you permanently delete them. Restored SMS keeps its direction, thread and read state.
- Saving attachments will not overwrite files with the same name, and sharing one attachment will not interrupt another share.

## 0.5.7

- Conversations appear before contact names finish loading, so you can open messages sooner.
- New message updates no longer keep restarting the conversation list while it loads.
- Incoming texts update the message list before contact lookups and notification work finish.

Reports of missed or delayed messages are still being investigated. This update improves how messages appear in the app; it does not confirm a fix for every delivery problem.

## 0.5.6

- New messages can appear as a banner with the sender and message preview. Choose a quieter notification style in Settings if you prefer.
- Choose one long buzz or two short buzzes for new message alerts.
- D-pad navigation moves through messages with Up and Down. From the message box, Up returns to the newest message; Left and Right reach the top buttons.
- Amber focus highlighting is easier to see in both light and dark themes, including menus.
- Dark theme now uses a black background to save power on AMOLED screens.
