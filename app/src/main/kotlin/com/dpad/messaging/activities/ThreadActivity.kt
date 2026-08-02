package com.dpad.messaging.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.R
import com.dpad.messaging.adapters.ThreadAdapter
import com.dpad.messaging.databinding.ActivityThreadBinding
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.extensions.getMessagesForThread
import com.dpad.messaging.extensions.markThreadAsReadInTelephony
import com.dpad.messaging.helpers.MessageCache
import com.dpad.messaging.helpers.MmsSender
import com.dpad.messaging.helpers.NotificationHelper
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ScheduledMessageScheduler
import com.dpad.messaging.helpers.SendingMode
import com.dpad.messaging.helpers.SendingRouter
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.helpers.MessageSenders
import com.dpad.messaging.helpers.SmsWhitelistManager
import com.dpad.messaging.models.Message
import com.dpad.messaging.models.RecycleBinMessage
import com.dpad.messaging.models.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

class ThreadActivity : BaseActivity() {

    private lateinit var binding: ActivityThreadBinding
    private lateinit var threadAdapter: ThreadAdapter

    private var threadId: Long = -1L
    private var threadTitle: String = ""
    private var phoneNumber: String = ""
    /** All participant numbers for this thread (size > 1 = group). */
    private var participants: List<String> = emptyList()

    /** Active subscriptionId to use when sending (-1 = system default). */
    private var selectedSubId: Int = -1

    /**
     * Cached list of (subscriptionId, displayName) for the SIM picker.
     * Populated in loadSimInfo(); empty on single-SIM or no READ_PHONE_STATE.
     */
    private var simEntries: List<Pair<Int, String>> = emptyList()

    /** URI of the image the user has selected but not yet sent. Null when no pending attachment. */
    private var pendingAttachmentUri: Uri? = null
    private val pendingAttachmentUris = mutableListOf<Uri>()
    private var pendingCameraUri: Uri? = null
    private var pendingScheduledAtMillis: Long? = null

    private lateinit var attachmentPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var contactPickerLauncher: ActivityResultLauncher<Void?>
    private lateinit var cameraCaptureLauncher: ActivityResultLauncher<Uri>
    private var hasInitializedList = false

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        extractThreadExtras(intent)

        if (threadId == -1L) { finish(); return }

        // Permission requester: request READ_MEDIA_* (Android 13+) or READ_EXTERNAL_STORAGE (older)
        permissionRequestLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result.values.all { it }
            if (granted) {
                launchAttachmentPickerChooser()
            } else {
                android.widget.Toast.makeText(this, R.string.permission_denied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Register before setupComposeBar() (must be called before onStart)
        attachmentPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Not all providers offer persistable permissions.
                }
                pendingAttachmentUri = uri
                pendingAttachmentUris.clear()
                pendingAttachmentUris.add(uri)
                showAttachmentPreview(uri)
            }
        }

        // Dedicated contact picker — contacts are not files so OpenDocument can't reach them.
        contactPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickContact()
        ) { contactUri ->
            if (contactUri != null) {
                // Build a vCard URI from the lookup key so openInputStream() works.
                contentResolver.query(
                    contactUri,
                    arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val lookupKey = cursor.getString(0)
                        val vCardUri = Uri.withAppendedPath(
                            ContactsContract.Contacts.CONTENT_VCARD_URI,
                            Uri.encode(lookupKey)
                        )
                        pendingAttachmentUri = vCardUri
                        pendingAttachmentUris.clear()
                        pendingAttachmentUris.add(vCardUri)
                        showAttachmentPreview(vCardUri)
                    }
                }
            }
        }

        cameraCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            val uri = pendingCameraUri
            if (success && uri != null) {
                // Some camera apps (e.g. the Kyocera ROM) ignore EXTRA_OUTPUT and
                // write the photo to their own gallery instead of our FileProvider
                // target. If the target is unreadable, attach the latest photo taken.
                val target = if (isAttachmentReadable(uri)) uri else findLatestCameraImage()
                if (target != null) {
                    pendingAttachmentUri = target
                    pendingAttachmentUris.clear()
                    pendingAttachmentUris.add(target)
                    showAttachmentPreview(target)
                } else {
                    runCatching { contentResolver.delete(uri, null, null) }
                }
            } else if (uri != null) {
                runCatching { contentResolver.delete(uri, null, null) }
            }
            pendingCameraUri = null
        }

        setupToolbar()
        setupMessageList()
        setupComposeBar()
        applyPrefillAttachmentFromIntent(intent)
        loadSimInfo()
        loadMessages()
        markThreadRead()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractThreadExtras(intent)
        hasInitializedList = false
        applyPrefillAttachmentFromIntent(intent)
        setupToolbar()
        loadMessages()
        markThreadRead()
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        applyAccent()
        markThreadRead()
    }

    override fun onPause() {
        markThreadRead()
        saveDraft()
        EventBus.getDefault().unregister(this)
        super.onPause()
    }

    // ─── Setup ─────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        val titleForToolbar = if (participants.size > 1) {
            participants.joinToString(", ") { App.get().contactHelper.getDisplayName(it) }
        } else {
            threadTitle
        }
        binding.tvContactName.text = titleForToolbar

        binding.btnBack.setOnClickListener { finish() }

        binding.btnCall.setOnClickListener {
            handleCallAction()
        }

        binding.btnDetails.setOnClickListener {
            val intent = Intent(this, ConversationDetailsActivity::class.java).apply {
                putExtra(EXTRA_THREAD_ID, threadId)
                putExtra(EXTRA_THREAD_TITLE, threadTitle)
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_PARTICIPANTS, participants.joinToString(","))
            }
            startActivity(intent)
        }

        // D-Pad DOWN from any toolbar button → focus the message list (or compose bar when empty)
        val goToMessages = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                if (threadAdapter.itemCount > 0) binding.rvMessages.focusLastItem()
                else binding.etMessage.requestFocus()
                true
            } else false
        }
        binding.btnBack.setOnKeyListener(goToMessages)
        binding.btnCall.setOnKeyListener(goToMessages)
        binding.btnDetails.setOnKeyListener(goToMessages)

        applyAccent()
    }

    private fun applyAccent() {
        val accent = ThemeManager.accentColor(this)
        val tint = ColorStateList.valueOf(accent)

        binding.btnBack.imageTintList = tint
        binding.btnCall.imageTintList = tint
        binding.btnDetails.imageTintList = tint
        binding.btnAttach.imageTintList = tint
        binding.btnSchedule.imageTintList = tint
        binding.btnSim.setTextColor(accent)
        // btnRemoveAttachment uses its XML fill color — no tinting needed, keeps icon always visible.

        binding.btnBack.backgroundTintList = tint
        binding.btnCall.backgroundTintList = tint
        binding.btnDetails.backgroundTintList = tint
        binding.btnAttach.backgroundTintList = tint
        binding.btnSchedule.backgroundTintList = tint
        binding.btnSend.backgroundTintList = tint
        binding.btnSim.backgroundTintList = tint

        updateSendButtonState()
    }

    private fun setupMessageList() {
        threadAdapter = ThreadAdapter(
            onMessageLongClick = { message -> showMessageContextMenu(message) }
        )

        binding.rvMessages.apply {
            adapter = threadAdapter
            layoutManager = LinearLayoutManager(this@ThreadActivity).apply {
                stackFromEnd = true   // newest messages at the bottom
            }
            // D-Pad UP from first message → toolbar
            onTopEdgeReached = {
                binding.btnBack.requestFocus()
            }
            // D-Pad DOWN from last message → compose bar
            onBottomEdgeReached = {
                binding.etMessage.requestFocus()
            }
        }
    }

    private fun setupComposeBar() {
        // D-Pad UP from compose:
        // 1) If chips are visible, go to the chips container.
        // 2) If an attachment is visible, go to the remove-attachment button.
        // 3) Otherwise go directly to the toolbar (bypasses message list so
        //    the user doesn't have to scroll through every message).
        val goUpFromCompose = { ->
            if (binding.chipsContainerScroll.visibility == View.VISIBLE) {
                binding.chipsContainer.getChildAt(binding.chipsContainer.childCount - 1)?.requestFocus()
                    ?: binding.chipsContainerScroll.requestFocus()
            } else if (binding.attachmentPreviewBar.visibility == View.VISIBLE) {
                binding.btnRemoveAttachment.requestFocus()
            } else {
                binding.btnBack.requestFocus()
            }
        }
        binding.etMessage.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP    -> { goUpFromCompose(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { binding.etMessage.focusSearch(View.FOCUS_RIGHT)?.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_LEFT  -> { binding.btnAttach.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_CENTER -> { insertNewLine(); true }
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> { insertNewLine(); true }
                else -> false
            }
        }
        binding.btnAttach.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                goUpFromCompose(); true
            } else false
        }
        binding.btnSend.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                    goUpFromCompose(); true
                }
                // Wrap right → SIM button if visible, else wrap to attach
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN &&
                        binding.btnSim.visibility != View.VISIBLE -> {
                    binding.btnAttach.requestFocus(); true
                }
                else -> false
            }
        }
        binding.btnSchedule.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                    goUpFromCompose(); true
                }
                else -> false
            }
        }
        binding.btnSim.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                goUpFromCompose(); true
            } else false
        }
        binding.btnSim.setOnClickListener { showSimPicker() }
        binding.btnSchedule.setOnClickListener { showScheduleOptions() }

        // Attachment preview strip
        binding.btnRemoveAttachment.setOnClickListener { clearAttachment() }
        binding.btnRemoveAttachment.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                binding.etMessage.requestFocus()
                true
            } else false
        }

        // Enable/disable send button and update character counter based on text
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateSendButtonState() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // IME_ACTION_SEND always sends; ENTER key only sends when sendOnEnter is enabled
        binding.etMessage.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else if (event != null
                && event.keyCode == KeyEvent.KEYCODE_ENTER
                && event.action == KeyEvent.ACTION_DOWN
                && !event.isShiftPressed
                && Prefs.get().sendOnEnter
            ) {
                sendMessage()
                true
            } else false
        }

        binding.btnSend.setOnClickListener { sendMessage() }

        // Attach button — show menu to choose between media or contact.
        binding.btnAttach.setOnClickListener { anchor ->
            val popup = PopupMenu(ThemeManager.popupMenuContext(this), anchor)
            popup.menu.add(0, 1, 0, getString(R.string.attach_image_audio))
            popup.menu.add(0, 2, 0, getString(R.string.attach_contact))
            popup.menu.add(0, 3, 0, getString(R.string.attach_camera))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        // Ensure we have runtime permission to read external media (Android 13+)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            permissionRequestLauncher.launch(arrayOf(
                                android.Manifest.permission.READ_MEDIA_IMAGES,
                                android.Manifest.permission.READ_MEDIA_AUDIO
                            ))
                        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            permissionRequestLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
                        } else {
                            launchAttachmentPickerChooser()
                        }
                    }
                    2 -> contactPickerLauncher.launch(null)
                    3 -> launchCameraAttachment()
                }
                true
            }
            popup.show()
        }

        // Restore any saved draft
        lifecycleScope.launch {
            val draft = withContext(Dispatchers.IO) {
                App.get().database.draftsDao().getDraft(threadId)
            }
            if (draft != null) {
                binding.etMessage.setText(draft.body)
                binding.etMessage.setSelection(draft.body.length)
            }
        }

        // Compose bar gets initial focus
        binding.etMessage.requestFocus()
        updateScheduledUi()
    }

    // ─── Attachment preview ─────────────────────────────────────────────────

    private fun showAttachmentPreview(uri: Uri) {
        try {
            binding.attachmentPreviewBar.visibility = View.VISIBLE
            val mimeType = try { contentResolver.getType(uri)?.lowercase().orEmpty() } catch (_: Exception) { "" }
            if (mimeType.startsWith("image/")) {
                try {
                    // Avoid requesting original-size bitmaps (can OOM on very large images).
                    Glide.with(this)
                        .load(uri)
                        .centerCrop()
                        .override(800, 800)
                        .into(binding.ivAttachmentPreview)
                } catch (e: Exception) {
                    // Glide or provider may throw — fallback to generic icon
                    if (BuildConfig.DEBUG) Log.w("DPAD_MSG", "showAttachmentPreview: failed to load image preview", e)
                    binding.ivAttachmentPreview.setImageResource(R.drawable.ic_attach)
                }
            } else {
                binding.ivAttachmentPreview.setImageResource(R.drawable.ic_attach)
            }
            binding.btnRemoveAttachment.requestFocus()
            updateSendButtonState()
        } catch (e: SecurityException) {
            // Missing permission to read the URI — show a friendly fallback and log
            if (BuildConfig.DEBUG) Log.w("DPAD_MSG", "showAttachmentPreview: security error for uri=$uri", e)
            binding.attachmentPreviewBar.visibility = View.VISIBLE
            binding.ivAttachmentPreview.setImageResource(R.drawable.ic_attach)
            android.widget.Toast.makeText(this, R.string.error_picking_contact, android.widget.Toast.LENGTH_SHORT).show()
            updateSendButtonState()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("DPAD_MSG", "showAttachmentPreview: unexpected error for uri=$uri", e)
            binding.attachmentPreviewBar.visibility = View.VISIBLE
            binding.ivAttachmentPreview.setImageResource(R.drawable.ic_attach)
            updateSendButtonState()
        }
    }

    private fun launchAttachmentPickerChooser() {
        val mimeTypes = arrayOf("image/*", "audio/*")

        val openDocumentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        val imageIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val audioIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(
            openDocumentIntent,
            getString(R.string.choose_attachment_app)
        ).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(imageIntent, audioIntent))
        }

        attachmentPickerLauncher.launch(chooser)
    }

    private fun clearAttachment() {
        pendingAttachmentUri = null
        pendingAttachmentUris.clear()
        binding.attachmentPreviewBar.visibility = View.GONE
        Glide.with(this).clear(binding.ivAttachmentPreview)
        deleteCameraTempFile()
        updateSendButtonState()
    }

    private fun deleteCameraTempFile() {
        val cameraDir = File(cacheDir, "camera_capture")
        if (cameraDir.isDirectory) {
            cameraDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun applyPrefillAttachmentFromIntent(intent: Intent?) {
        val uriList = intent
            ?.getStringArrayListExtra(EXTRA_PREFILL_ATTACHMENT_URIS)
            ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .orEmpty()
        if (uriList.isNotEmpty()) {
            pendingAttachmentUris.clear()
            pendingAttachmentUris.addAll(uriList)
            pendingAttachmentUri = uriList.first()
            showAttachmentPreview(uriList.first())
            return
        }

        val uriString = intent?.getStringExtra(EXTRA_PREFILL_ATTACHMENT_URI)
        if (uriString.isNullOrBlank()) return
        val uri = Uri.parse(uriString)
        pendingAttachmentUri = uri
        pendingAttachmentUris.clear()
        pendingAttachmentUris.add(uri)
        showAttachmentPreview(uri)
    }

    private fun launchCameraAttachment() {
        val imageFile = File(cacheDir, "camera_capture/thread_${threadId}_${System.currentTimeMillis()}.jpg")
        imageFile.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
        pendingCameraUri = uri
        cameraCaptureLauncher.launch(uri)
    }

    /**
     * Returns true if the given URI can actually be opened and read.
     * Used to detect camera apps that ignore EXTRA_OUTPUT and never write the
     * requested FileProvider target.
     */
    private fun isAttachmentReadable(uri: Uri): Boolean = try {
        contentResolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w("DPAD_MSG", "ThreadActivity: attachment unreadable: $uri", e)
        false
    }

    /**
     * Fallback for camera apps that ignore EXTRA_OUTPUT: returns the most recent
     * photo in the media store, restricted to the last few minutes so we don't
     * pick up an unrelated older image.
     */
    private fun findLatestCameraImage(): Uri? {
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val cutoffSeconds = System.currentTimeMillis() / 1000L - 5 * 60
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(cutoffSeconds.toString())
        return runCatching {
            contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
        }.getOrNull()
    }

    // ─── Compose input helpers ──────────────────────────────────────────────

    /**
     * Inserts a newline at the cursor position. Used for the DPAD center key on
     * keypad devices, where the center key adds a line break in a text field.
     */
    private fun insertNewLine() {
        val editable = binding.etMessage.text ?: return
        val selStart = binding.etMessage.selectionStart
        val selEnd   = binding.etMessage.selectionEnd
        if (selStart in 0..editable.length && selEnd in 0..editable.length) {
            if (selStart == selEnd) editable.insert(selStart, "\n")
            else editable.replace(selStart, selEnd, "\n")
        } else {
            editable.append("\n")
        }
    }

    // ─── Send button state ──────────────────────────────────────────────────

    /**
     * Enables the send button when there is text OR a pending attachment OR chips.
     * Also updates the character counter for SMS segment tracking.
     */
    private fun updateSendButtonState() {
        val hasText       = binding.etMessage.text?.isNotBlank() == true
        val hasAttachment = pendingAttachmentUri != null
        val hasChips      = binding.chipsContainer.childCount > 0
        val enabled       = hasText || hasAttachment || hasChips
        val accentColor   = ThemeManager.accentColor(this)

        binding.btnSend.isEnabled = enabled
        binding.btnSend.setColorFilter(
            if (enabled) accentColor
            else         getColor(R.color.sendButtonDisabled)
        )

        // Character counter (only meaningful for plain SMS, no attachment)
        if (Prefs.get().characterCounter && hasText && !hasAttachment) {
            val text = binding.etMessage.text?.toString() ?: ""
            @Suppress("DEPRECATION")
            val result = android.telephony.SmsMessage.calculateLength(text, false)
            // result[0] = segments, result[2] = remaining chars in last segment
            binding.tvCharCount.text = getString(R.string.sms_char_counter_format, result[2], result[0])
            binding.tvCharCount.visibility = View.VISIBLE
        } else {
            binding.tvCharCount.visibility = View.GONE
        }

        updateScheduledUi()
    }

    private fun updateScheduledUi() {
        val millis = pendingScheduledAtMillis
        if (millis == null) {
            binding.tvScheduledTime.visibility = View.GONE
            binding.btnSchedule.alpha = 0.85f
            return
        }
        val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(millis)
        binding.tvScheduledTime.text = getString(R.string.scheduled_for, formatted)
        binding.tvScheduledTime.visibility = View.VISIBLE
        binding.btnSchedule.alpha = 1f
    }

    private fun showScheduleOptions() {
        val popup = PopupMenu(ThemeManager.popupMenuContext(this), binding.btnSchedule)
        if (pendingScheduledAtMillis == null) {
            popup.menu.add(0, 1, 0, getString(R.string.schedule_set))
        } else {
            popup.menu.add(0, 2, 0, getString(R.string.schedule_change))
            popup.menu.add(0, 3, 1, getString(R.string.schedule_cancel))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1, 2 -> openSchedulePicker()
                3 -> {
                    pendingScheduledAtMillis = null
                    updateScheduledUi()
                }
            }
            true
        }
        popup.show()
    }

    private fun openSchedulePicker() {
        val seed = Calendar.getInstance().apply {
            timeInMillis = pendingScheduledAtMillis ?: System.currentTimeMillis()
            add(Calendar.MINUTE, if (pendingScheduledAtMillis == null) 5 else 0)
        }

        val pickerView = layoutInflater.inflate(R.layout.dialog_schedule_picker, null)
        val calendar = (seed.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val tvYear = pickerView.findViewById<android.widget.TextView>(R.id.tv_year)
        val tvMonth = pickerView.findViewById<android.widget.TextView>(R.id.tv_month)
        val tvDay = pickerView.findViewById<android.widget.TextView>(R.id.tv_day)
        val tvHour = pickerView.findViewById<android.widget.TextView>(R.id.tv_hour)
        val tvMinute = pickerView.findViewById<android.widget.TextView>(R.id.tv_minute)
        val tvPreview = pickerView.findViewById<android.widget.TextView>(R.id.tv_schedule_preview)

        fun maxDayForCurrentMonth(): Int {
            return calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        }

        fun refreshFields() {
            val use24h = Prefs.get().timeFormat == Prefs.TIME_FORMAT_24H
            val monthName = java.text.DateFormatSymbols.getInstance().months[calendar.get(Calendar.MONTH)]
            tvYear.text = calendar.get(Calendar.YEAR).toString()
            tvMonth.text = monthName
            tvDay.text = calendar.get(Calendar.DAY_OF_MONTH).toString()
            tvHour.text = if (use24h) {
                String.format(java.util.Locale.getDefault(), "%02d", calendar.get(Calendar.HOUR_OF_DAY))
            } else {
                val h = calendar.get(Calendar.HOUR)
                val hour12 = if (h == 0) 12 else h
                String.format(
                    java.util.Locale.getDefault(),
                    "%02d %s",
                    hour12,
                    if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
                )
            }
            tvMinute.text = String.format(java.util.Locale.getDefault(), "%02d", calendar.get(Calendar.MINUTE))
            tvPreview.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(calendar.timeInMillis)
        }

        fun adjust(field: Int, delta: Int) {
            when (field) {
                Calendar.YEAR -> {
                    calendar.add(Calendar.YEAR, delta)
                    calendar.set(Calendar.DAY_OF_MONTH, min(calendar.get(Calendar.DAY_OF_MONTH), maxDayForCurrentMonth()))
                }
                Calendar.MONTH -> {
                    calendar.add(Calendar.MONTH, delta)
                    calendar.set(Calendar.DAY_OF_MONTH, min(calendar.get(Calendar.DAY_OF_MONTH), maxDayForCurrentMonth()))
                }
                Calendar.DAY_OF_MONTH -> {
                    calendar.add(Calendar.DAY_OF_MONTH, delta)
                }
                Calendar.HOUR_OF_DAY -> {
                    calendar.add(Calendar.HOUR_OF_DAY, delta)
                }
                Calendar.MINUTE -> {
                    calendar.add(Calendar.MINUTE, delta)
                }
            }
            refreshFields()
        }

        pickerView.findViewById<android.widget.Button>(R.id.btn_year_minus).setOnClickListener { adjust(Calendar.YEAR, -1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_year_plus).setOnClickListener { adjust(Calendar.YEAR, 1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_month_minus).setOnClickListener { adjust(Calendar.MONTH, -1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_month_plus).setOnClickListener { adjust(Calendar.MONTH, 1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_day_minus).setOnClickListener { adjust(Calendar.DAY_OF_MONTH, -1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_day_plus).setOnClickListener { adjust(Calendar.DAY_OF_MONTH, 1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_hour_minus).setOnClickListener { adjust(Calendar.HOUR_OF_DAY, -1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_hour_plus).setOnClickListener { adjust(Calendar.HOUR_OF_DAY, 1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_minute_minus).setOnClickListener { adjust(Calendar.MINUTE, -1) }
        pickerView.findViewById<android.widget.Button>(R.id.btn_minute_plus).setOnClickListener { adjust(Calendar.MINUTE, 1) }

        refreshFields()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.schedule_picker_title)
            .setView(pickerView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            pickerView.findViewById<android.widget.Button>(R.id.btn_year_minus).requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minimum = System.currentTimeMillis() + 60_000L
                pendingScheduledAtMillis = max(calendar.timeInMillis, minimum)
                updateScheduledUi()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ─── Data ───────────────────────────────────────────────────────────────

    private fun loadMessages() {
        if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.loadMessages() called for threadId=$threadId")

        // Show cached data immediately for instant warm loads
        val cached = MessageCache.get(threadId)
        if (cached != null) {
            if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.loadMessages() cache hit: ${cached.size} messages")
            displayMessages(cached, fromCache = true)
        }

        // Always refresh from the real source of truth (Telephony ContentProvider)
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                getMessagesForThread(threadId, App.get().contactHelper)
            }
            if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.loadMessages() got ${messages.size} messages for threadId=$threadId")
            MessageCache.put(threadId, messages)
            displayMessages(messages, fromCache = false)
        }
    }

    private fun displayMessages(messages: List<Message>, fromCache: Boolean) {
        val items = ThreadItem.fromMessages(messages)
        threadAdapter.submitList(items) {
            // Keep initial auto-scroll behavior, but avoid stealing D-pad focus on every refresh
            if (!hasInitializedList) {
                binding.rvMessages.scrollToPosition(threadAdapter.itemCount - 1)
                binding.rvMessages.post {
                    binding.rvMessages.scrollToPosition(threadAdapter.itemCount - 1)
                }
                if (!fromCache) {
                    hasInitializedList = true
                }
            }
        }
    }

    private fun extractThreadExtras(intent: Intent?) {
        threadId = intent?.getLongExtra(EXTRA_THREAD_ID, -1L) ?: -1L
        threadTitle = intent?.getStringExtra(EXTRA_THREAD_TITLE) ?: ""
        phoneNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
        participants = intent?.getStringExtra(EXTRA_PARTICIPANTS)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: listOf(phoneNumber).filter { it.isNotBlank() }
    }

    private fun handleCallAction() {
        val candidates = (participants + phoneNumber)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        when {
            candidates.isEmpty() -> return
            candidates.size == 1 -> startDialIntent(candidates.first())
            else -> showCallContactChooser(candidates)
        }
    }

    private fun showCallContactChooser(numbers: List<String>) {
        val labels = numbers.map { number ->
            val display = App.get().contactHelper.getDisplayName(number)
            if (display.equals(number, ignoreCase = true)) number else "$display ($number)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.call)
            .setItems(labels) { _, which ->
                numbers.getOrNull(which)?.let { startDialIntent(it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDialIntent(number: String) {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    private fun markThreadRead() {
        if (threadId <= 0L) return

        NotificationHelper.cancelNotification(this, threadId.toInt())
        lifecycleScope.launch(Dispatchers.IO) {
            App.get().database.messagesDao().markThreadRead(threadId)
            App.get().database.conversationsDao().markAsRead(threadId)
            markThreadAsReadInTelephony(threadId)
        }
    }

    private fun saveDraft() {
        var body = binding.etMessage.text?.toString() ?: ""
        
        // Include chips in the draft
        val chipNumbers = mutableListOf<String>()
        for (i in 0 until binding.chipsContainer.childCount) {
            val chipButton = binding.chipsContainer.getChildAt(i) as? android.widget.Button
            chipButton?.text?.toString()?.let { chipNumbers.add(it) }
        }
        if (chipNumbers.isNotEmpty()) {
            body = if (body.isNotBlank()) {
                "$body ${chipNumbers.joinToString(" ")}"
            } else {
                chipNumbers.joinToString(" ")
            }
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = App.get().database.draftsDao()
            if (body.isBlank()) {
                dao.deleteDraft(threadId)
            } else {
                dao.insertDraft(
                    com.dpad.messaging.models.Draft(threadId = threadId, body = body)
                )
            }
        }
    }

    // ─── Send ────────────────────────────────────────────────────────────────

    private fun sendMessage() {
        // Disable send button to prevent double-tap
        binding.btnSend.isEnabled = false

        var body       = binding.etMessage.text?.toString()?.trim() ?: ""
        val attachment = pendingAttachmentUri
        val scheduledAtMillis = pendingScheduledAtMillis
        val attachments = LinkedHashSet<Uri>().apply {
            addAll(pendingAttachmentUris)
            if (attachment != null) add(attachment)
        }.toList()

        // Collect numbers from chips and append to message
        val chipNumbers = mutableListOf<String>()
        for (i in 0 until binding.chipsContainer.childCount) {
            val chipButton = binding.chipsContainer.getChildAt(i) as? android.widget.Button
            chipButton?.text?.toString()?.let { chipNumbers.add(it) }
        }
        if (chipNumbers.isNotEmpty()) {
            body = if (body.isNotBlank()) {
                "$body ${chipNumbers.joinToString(" ")}"
            } else {
                chipNumbers.joinToString(" ")
            }
        }

        if (body.isBlank() && attachments.isEmpty()) {
            binding.btnSend.isEnabled = true
            return
        }
        if (phoneNumber.isBlank() && participants.isEmpty()) {
            binding.btnSend.isEnabled = true
            return
        }

        // ── MDM outgoing filter ───────────────────────────────────────────────
        // Check phoneNumber (1-on-1) AND participants (group), so direct messages
        // aren't bypassed. Run synchronously before clearing the UI.
        val recipientsToCheck = buildList {
            if (phoneNumber.isNotBlank()) add(phoneNumber)
            addAll(participants)
        }.distinct()
        val blockedRecipients = recipientsToCheck.filter { recipient ->
            !SmsWhitelistManager.check(this, recipient).allowed
        }
        if (blockedRecipients.isNotEmpty()) {
            Log.i("DPAD_MSG", "ThreadActivity: outgoing message blocked — recipients not permitted: $blockedRecipients")
            android.widget.Toast.makeText(
                this,
                getString(R.string.message_blocked_by_policy),
                android.widget.Toast.LENGTH_LONG
            ).show()
            binding.btnSend.isEnabled = true
            return
        }
        // ─────────────────────────────────────────────────────────────────────

        if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.sendMessage() body='${body.take(20)}' attachments=${attachments.size} participants=$participants isGroup=${participants.size > 1}")

        // Clear UI only after the filter has passed
        binding.etMessage.text?.clear()
        binding.chipsContainer.removeAllViews()
        binding.chipsContainerScroll.visibility = View.GONE
        clearAttachment()
        pendingScheduledAtMillis = null
        updateScheduledUi()
        binding.etMessage.requestFocus()

        lifecycleScope.launch(Dispatchers.IO) {
            val hasAttachment = attachments.isNotEmpty()
            val mode = SendingRouter.decideSendingMode(
                hasAttachment = hasAttachment,
                recipientCount = participants.size,
                sendGroupMessageMms = Prefs.get().sendGroupMessageMms
            )
            
            when (mode) {
                SendingMode.MMS_GROUP -> {
                    if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.sendMessage() routing: MMS_GROUP")
                    if (scheduledAtMillis != null) {
                        MessageSenders.scheduleMms(
                            context = this@ThreadActivity,
                            recipients = participants,
                            body = body,
                            attachmentUris = attachments,
                            threadId = threadId,
                            scheduledDate = scheduledAtMillis,
                            subscriptionId = selectedSubId
                        )
                    } else {
                        MessageSenders.unified.sendMms(
                            context        = this@ThreadActivity,
                            recipients     = participants,
                            body           = body,
                            attachmentUri  = attachment,
                            attachmentUris = attachments,
                            threadId       = threadId,
                            subscriptionId = selectedSubId
                        )
                    }
                }
                SendingMode.MMS_SINGLE -> {
                    if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.sendMessage() routing: MMS_SINGLE")
                    if (scheduledAtMillis != null) {
                        MessageSenders.scheduleMms(
                            context = this@ThreadActivity,
                            recipients = listOf(phoneNumber),
                            body = body,
                            attachmentUris = attachments,
                            threadId = threadId,
                            scheduledDate = scheduledAtMillis,
                            subscriptionId = selectedSubId
                        )
                    } else {
                        MessageSenders.unified.sendMms(
                            context        = this@ThreadActivity,
                            recipients     = listOf(phoneNumber),
                            body           = body,
                            attachmentUri  = attachment,
                            attachmentUris = attachments,
                            threadId       = threadId,
                            subscriptionId = selectedSubId
                        )
                    }
                }
                SendingMode.SMS_FANOUT_GROUP -> {
                    if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.sendMessage() routing: SMS_FANOUT_GROUP to ${participants.size} recipients")
                    if (scheduledAtMillis != null) {
                        participants.forEach { recipient ->
                            val recipientThreadId = runCatching {
                                Telephony.Threads.getOrCreateThreadId(this@ThreadActivity, recipient)
                            }.getOrDefault(threadId)
                            MessageSenders.scheduleSms(
                                context = this@ThreadActivity,
                                phoneNumber = recipient,
                                body = body,
                                threadId = recipientThreadId,
                                scheduledDate = scheduledAtMillis,
                                subscriptionId = selectedSubId
                            )
                        }
                    } else {
                        MessageSenders.unified.sendGroupSmsFanout(
                            context = this@ThreadActivity,
                            recipients = participants,
                            body = body,
                            fallbackThreadId = threadId,
                            subscriptionId = selectedSubId
                        )
                    }
                }
                SendingMode.SMS_SINGLE -> {
                    if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.sendMessage() routing: SMS_SINGLE")
                    if (scheduledAtMillis != null) {
                        MessageSenders.scheduleSms(
                            context = this@ThreadActivity,
                            phoneNumber = phoneNumber,
                            body = body,
                            threadId = threadId,
                            scheduledDate = scheduledAtMillis,
                            subscriptionId = selectedSubId
                        )
                    } else {
                        MessageSenders.unified.sendSms(
                            context        = this@ThreadActivity,
                            phoneNumber    = phoneNumber,
                            body           = body,
                            threadId       = threadId,
                            subscriptionId = selectedSubId
                        )
                    }
                }
            }
            deleteCameraTempFile()
            withContext(Dispatchers.Main) { loadMessages() }
        }
    }

    // ─── Context menus ──────────────────────────────────────────────────────

    private fun showMessageContextMenu(message: Message) {
        val options = buildList {
            add(getString(R.string.copy_text))
            if (!message.isIncoming) {
                if (message.isScheduled && message.type == Message.TYPE_QUEUED) {
                    add(getString(R.string.cancel_scheduled_message))
                }
                if (message.type == Message.TYPE_FAILED) add(getString(R.string.retry_send))
            }
            add(getString(R.string.forward))
            add(getString(R.string.move_to_recycle_bin))
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.copy_text) -> copyMessageText(message.body)
                    getString(R.string.cancel_scheduled_message) -> cancelScheduledMessage(message)
                    getString(R.string.retry_send) -> retryMessage(message)
                    getString(R.string.forward) -> forwardMessage(message)
                    getString(R.string.move_to_recycle_bin) -> deleteMessage(message)
                }
            }
            .create()
            .show()
    }

    private fun copyMessageText(body: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("message", body))
        android.widget.Toast.makeText(this, R.string.message_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun forwardMessage(message: Message) {
        val intent = Intent(this, NewConversationActivity::class.java).apply {
            putExtra(NewConversationActivity.EXTRA_PREFILL_BODY, message.body)
        }
        startActivity(intent)
    }

    private fun deleteMessage(message: Message) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (message.isScheduled && message.type == Message.TYPE_QUEUED) {
                ScheduledMessageScheduler.cancelMessage(this@ThreadActivity, message.id)
                App.get().database.messagesDao().deleteMessage(message.id)
                EventBus.getDefault().post(RefreshMessages(threadId))
                EventBus.getDefault().post(com.dpad.messaging.events.RefreshConversations())
                withContext(Dispatchers.Main) { loadMessages() }
                return@launch
            }

            if (Prefs.get().recycleBinEnabled) {
                App.get().database.messagesDao()
                    .insertRecycleBinMessage(RecycleBinMessage(id = message.id))
            } else {
                // Hard-delete directly from the Telephony SMS Content Provider.
                try {
                    contentResolver.delete(
                        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message.id),
                        null, null
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) { loadMessages() }
        }
    }

    private fun cancelScheduledMessage(message: Message) {
        lifecycleScope.launch(Dispatchers.IO) {
            ScheduledMessageScheduler.cancelMessage(this@ThreadActivity, message.id)
            App.get().database.messagesDao().deleteMessage(message.id)
            EventBus.getDefault().post(RefreshMessages(threadId))
            EventBus.getDefault().post(com.dpad.messaging.events.RefreshConversations())
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    this@ThreadActivity,
                    R.string.scheduled_message_canceled,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                loadMessages()
            }
        }
    }

    /**
     * Retry a failed message. Sends the new OUTBOX row first, then deletes the
     * old FAILED row — never leaving the message invisible if re-send fails.
     */
    private fun retryMessage(message: Message) {
        // ── MDM outgoing filter (same check as sendMessage()) ────────────────
        val recipientsToCheck = buildList {
            if (phoneNumber.isNotBlank()) add(phoneNumber)
            addAll(participants)
        }.distinct()
        val blockedRecipients = recipientsToCheck.filter { recipient ->
            !SmsWhitelistManager.check(this, recipient).allowed
        }
        if (blockedRecipients.isNotEmpty()) {
            Log.i("DPAD_MSG", "ThreadActivity.retryMessage: blocked — recipients not permitted: $blockedRecipients")
            android.widget.Toast.makeText(this, R.string.message_blocked_by_policy, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // ─────────────────────────────────────────────────────────────────────

        lifecycleScope.launch(Dispatchers.IO) {
            // Re-send first — creates a fresh OUTBOX row in the CP
            MessageSenders.unified.sendSms(
                context        = this@ThreadActivity,
                phoneNumber    = phoneNumber,
                body           = message.body,
                threadId       = threadId,
                subscriptionId = message.subscriptionId
            )
            // Only now delete the stale FAILED row (new one already in provider)
            try {
                contentResolver.delete(
                    ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message.id),
                    null, null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) { loadMessages() }
        }
    }

    // ─── SIM picker ──────────────────────────────────────────────────────────

    /**
     * Reads active SIM subscriptions. On single-SIM devices (or when permission is
     * absent) the SIM button stays hidden. On multi-SIM devices the button appears
     * and shows the currently-selected SIM slot label.
     */
    @SuppressLint("MissingPermission")
    private fun loadSimInfo() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            // No permission — use default SMS subscription ID
            selectedSubId = getDefaultSmsSubscriptionId()
            return
        }

        val sm = getSystemService(SubscriptionManager::class.java) ?: return
        val subs = try { sm.activeSubscriptionInfoList } catch (_: Exception) { null }
        if (subs == null || subs.isEmpty()) {
            // No active subscriptions — use system default
            selectedSubId = getDefaultSmsSubscriptionId()
            return
        }

        if (subs.size < 2) {
            // Single-SIM — use that subscription's ID instead of -1
            selectedSubId = subs[0].subscriptionId
            if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.loadSimInfo() single-SIM, selectedSubId=$selectedSubId")
            return   // single-SIM: hide button
        }

        // Multi-SIM: show picker and default to first SIM
        simEntries = subs.mapIndexed { idx, info ->
            val label = info.displayName?.toString()?.ifBlank { null }
                ?: getString(R.string.sim_label, idx + 1)
            Pair(info.subscriptionId, label)
        }

        // Default to the first (lowest index) SIM
        selectedSubId = simEntries.first().first
        binding.btnSim.text = simEntries.first().second
        binding.btnSim.visibility = View.VISIBLE
        if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.loadSimInfo() multi-SIM count=${subs.size}, selectedSubId=$selectedSubId")
    }

    private fun getDefaultSmsSubscriptionId(): Int {
        return try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("DPAD_MSG", "Failed to get default SMS subscription ID", e)
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }

    private fun showSimPicker() {
        if (simEntries.isEmpty()) return
        val labels = simEntries.map { it.second }.toTypedArray()
        val currentIdx = simEntries.indexOfFirst { it.first == selectedSubId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.select_sim)
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                selectedSubId = simEntries[which].first
                binding.btnSim.text = simEntries[which].second
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ─── EventBus ──────────────────────────────────────────────────────────

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshMessages(event: RefreshMessages) {
        if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "ThreadActivity.onRefreshMessages() event.threadId=${event.threadId} local threadId=$threadId match=${event.threadId == threadId}")
        if (event.threadId == threadId) loadMessages()
    }

    // ─── Key handling ───────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_STAR -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        const val EXTRA_THREAD_ID    = "extra_thread_id"
        const val EXTRA_THREAD_TITLE = "extra_thread_title"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_PREFILL_ATTACHMENT_URI = "extra_prefill_attachment_uri"
        const val EXTRA_PREFILL_ATTACHMENT_URIS = "extra_prefill_attachment_uris"
        /** Comma-separated participant numbers; present for group threads. */
        const val EXTRA_PARTICIPANTS = "extra_participants"
    }
}
