package com.dpad.messaging.receivers

import com.android.mms.transaction.PushReceiver

/**
 * Delegates MMS WAP push handling to the proven Klinker/Android transaction flow.
 *
 * This path handles carrier quirks like transaction-id appending, duplicate
 * detection, and network download orchestration across OEM ROM variants.
 */
class MmsReceiver : PushReceiver()
