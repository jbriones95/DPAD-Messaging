package com.dpad.messaging.dumber

import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import chat.dumber.sdk.Conversation
import chat.dumber.sdk.Membership
import com.dpad.messaging.R
import com.dpad.messaging.activities.ConversationDetailsActivity
import com.dpad.messaging.databinding.ActivityConversationDetailsBinding
import com.dpad.messaging.helpers.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Encryption-specific actions attached to the ordinary conversation details screen. */
class DumberGroupDetails(
    private val activity: ConversationDetailsActivity,
    private val binding: ActivityConversationDetailsBinding,
    private val ref: GroupReference,
    private val participants: (Conversation) -> Unit
) {
    private var info = Conversation(ref.room)
    private var busy = false
    private var generation = 0

    init {
        binding.btnGroupActions.visibility = View.VISIBLE
        binding.btnGroupActions.setTextColor(ThemeManager.focusAwareTextColor(activity, activity.getColor(R.color.colorOnBackground)))
        binding.btnGroupActions.setOnClickListener { if (!busy) details() }
        binding.btnRename.setOnClickListener {
            prompt("Group name", info.name.orEmpty()) { name -> perform { DumberGroups.action(activity, ref, DumberGroups.Action.RENAME, name) } }
        }
        binding.btnBlock.text = activity.getString(R.string.dumber_group_leave)
        binding.btnBlock.setOnClickListener { if (!busy) leave() }
    }

    fun reload() {
        val current = ++generation
        activity.lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { DumberGroupStore(activity).use { it.info(ref) } }
            if (current != generation) return@launch
            info = latest ?: Conversation(ref.room)
            binding.tvContactName.text = info.name ?: activity.getString(R.string.dumber_group_details)
            binding.tvIdentityAvatar.text = "🔒"
            binding.tvIdentitySummary.text = activity.getString(R.string.dumber_group_encryption_info)
            participants(info)
            val connected = DumberAccount.enabled(activity, ref.account)
            binding.btnRename.isEnabled = !busy && connected && info.capabilities.manage
            binding.btnBlock.isEnabled = !busy && connected && info.membership in listOf(Membership.JOINED, Membership.INVITED)
            binding.btnGroupActions.nextFocusDownId = when {
                binding.btnRename.isEnabled -> binding.btnRename.id
                binding.btnBlock.isEnabled -> binding.btnBlock.id
                else -> binding.btnBack.id
            }
            binding.btnRename.nextFocusUpId = binding.btnGroupActions.id
            binding.btnBlock.nextFocusUpId = if (binding.btnRename.isEnabled) binding.btnRename.id else binding.btnGroupActions.id
        }
    }

    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        activity.lifecycleScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                AlertDialog.Builder(activity).setMessage(e.message ?: "Operation failed").setPositiveButton(android.R.string.ok, null).show()
            } finally { busy = false; reload() }
        }
    }
    private fun prompt(title: String, initial: String = "", action: (String) -> Unit) {
        val input=EditText(activity).apply { setText(initial) }
        AlertDialog.Builder(activity).setTitle(title).setView(input).setPositiveButton("Save") { _,_ -> action(input.text.toString()) }.setNegativeButton("Cancel",null).show()
    }
    private fun details() {
        val target=ref
        val options=mutableListOf("Participants", "Mute / unmute", "Archive / unarchive", "Pin / unpin", "Delete local history", "Refresh")
        if(info.membership == Membership.JOINED) options.add("Leave group")
        if(info.capabilities.manage) options.addAll(listOf("Rename group", "Invite participant", "Remove participant", "Appoint admin"))
        AlertDialog.Builder(activity).setTitle(info.name ?: "Group").setItems(options.toTypedArray()) { _, index ->
            when(options[index]) {
                "Refresh" -> perform { DumberGroups.refresh(activity, ref) }
                "Participants" -> AlertDialog.Builder(activity).setTitle("Participants").setItems(info.members.map { "${DumberGroups.label(it)} · ${it.membership.name.lowercase()}${if(it.admin) " · admin" else ""}" }.toTypedArray(),null).setPositiveButton("OK",null).show()
                "Mute / unmute" -> toggle("muted")
                "Archive / unarchive" -> toggle("archived")
                "Pin / unpin" -> toggle("pinned")
                "Delete local history" -> AlertDialog.Builder(activity).setMessage("Delete this device’s group history and pending messages? This does not leave the group.").setPositiveButton("Delete") { _,_ -> DumberGroupStore(activity).use { it.deleteHistory(target) }; DumberGroups.changed(); activity.finish() }.setNegativeButton("Cancel",null).show()
                "Rename group" -> prompt("Group name",info.name.orEmpty()) { perform { DumberGroups.action(activity,target,DumberGroups.Action.RENAME,it) } }
                "Invite participant" -> prompt("International phone number") { number -> perform {
                    val phone=DumberCore.international(activity,number,DumberAccount.subscription(activity,target.account)) ?: error("Use an international phone number")
                    DumberGroups.action(activity,target,DumberGroups.Action.INVITE,phone)
                } }
                "Remove participant" -> selectMember(false) { perform { DumberGroups.action(activity,target,DumberGroups.Action.REMOVE,it) } }
                "Appoint admin" -> selectMember(true) { perform { DumberGroups.action(activity,target,DumberGroups.Action.GRANT_ADMIN,it) } }
                "Leave group" -> leave()
            }
        }.show()
    }
    private fun toggle(key: String) {
        DumberGroupStore(activity).use { store -> store.flag(ref,key,store.room(ref)?.optBoolean(key) != true) }; DumberGroups.changed(); reload()
    }
    private fun selectMember(joinedOnly: Boolean, action: (String) -> Unit) {
        val people = DumberGroups.active(info).filter { it.contact.id != ref.account && (!joinedOnly || it.membership == Membership.JOINED) }
        AlertDialog.Builder(activity).setTitle("Choose participant").setItems(people.map { DumberGroups.label(it) }.toTypedArray()) { _,i -> action(people[i].contact.id) }.setNegativeButton("Cancel",null).show()
    }
    private fun leave() {
        val target = ref
        val lastAdmin = info.capabilities.manage && info.members.none { it.contact.id != target.account && it.membership == Membership.JOINED && it.admin }
        val dialog=AlertDialog.Builder(activity).setMessage(if(lastAdmin) "You are the last admin. Leaving without a successor means remaining members can chat but cannot manage the group." else "Leave this group? Local history will remain.")
            .setPositiveButton(if(lastAdmin) "Leave without assigning" else "Leave") { _,_ -> perform { DumberGroups.action(activity,target,DumberGroups.Action.LEAVE) } }
            .setNegativeButton("Cancel",null)
        if(lastAdmin) dialog.setNeutralButton("Choose new admin") { _,_ -> selectMember(true) { user -> perform {
            DumberGroups.action(activity,target,DumberGroups.Action.GRANT_ADMIN,user)
            DumberGroups.action(activity,target,DumberGroups.Action.LEAVE)
        } } }
        dialog.show()
    }
}
