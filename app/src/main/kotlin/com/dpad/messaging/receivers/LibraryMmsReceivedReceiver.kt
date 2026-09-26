package com.dpad.messaging.receivers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.klinker.android.send_message.MmsReceivedReceiver

/**
 * Handles MMS download-complete callbacks from Klinker DownloadManager.
 *
 * This is the concrete receiver required by BroadcastUtils routing
 * (taskAffinity == MmsReceivedReceiver.MMS_RECEIVED).
 */
class LibraryMmsReceivedReceiver : MmsReceivedReceiver() {

    override fun onMessageReceived(context: Context, messageUri: Uri) {
        MmsReceiveWorker.enqueue(context, messageUri.lastPathSegment?.toLongOrNull() ?: -1L)
    }

    override fun onError(context: Context, error: String) {
        Log.e(TAG, "LibraryMmsReceivedReceiver error: $error")
        // On some ROMs the system MmsService persists the downloaded MMS into the
        // provider itself instead of writing klinker's temp cache file, so the
        // file-path handoff above fails. Fall back to scanning the provider for
        // the freshly-arrived inbox MMS so the notification/refresh still fires.
        MmsReceiveWorker.enqueueFallback(context)
    }

    companion object {
        private const val TAG = "DPAD_MSG"
    }
}
