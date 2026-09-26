package com.dpad.messaging.models

import kotlinx.serialization.Serializable

@Serializable
data class BackupPreferences(
    val deliveryReports: Boolean,
    val sendOnEnter: Boolean,
    val characterCounter: Boolean,
    val sendGroupMessageMms: Boolean,
    val useLibrarySmsSending: Boolean,
    val recycleBinEnabled: Boolean,
    val lockScreenPrivacy: String,
    val appThemeMode: String,
    val appAccent: String,
    val dateFormat: String,
    val timeFormat: String,
    val uiScale: String,
    // Retained only so backups written by older versions still parse. MMS transport
    // is delegated to the platform, which resolves the MMSC and proxy itself.
    val mmsProxyHost: String = "",
    val mmsProxyPort: Int = 0,
    val mutedThreads: Set<String>,
    val pinnedThreads: Set<String>,
    val archivedThreads: Set<String>
)

@Serializable
data class BackupData(
    val version: Int = 1,
    val timestamp: Long,
    val conversations: List<Conversation>,
    val messages: List<Message>,
    val attachments: List<Attachment>,
    val drafts: List<Draft>,
    val recycleBinMessages: List<RecycleBinMessage>,
    val blockedKeywords: List<BlockedKeyword>,
    val blockedNumbers: List<BlockedNumber>,
    val preferences: BackupPreferences
)
