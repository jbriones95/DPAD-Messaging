package com.dpad.messaging.dumber

import android.content.Context

data class RemovalPrompt(val account: String, val nonce: String, val phone: String)

internal object SimRemovalChoices {
    fun pending(context: Context) = DumberAccount.pendingRemovals(context)
    fun resolve(context: Context, prompt: RemovalPrompt, keep: Boolean) {
        DumberAccount.chooseRemoval(context, prompt, keep)
        DumberDisconnect.enqueue(context)
    }
}
