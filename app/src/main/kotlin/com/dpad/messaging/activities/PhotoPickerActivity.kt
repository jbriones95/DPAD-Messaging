package com.dpad.messaging.activities

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.dpad.messaging.R
import com.dpad.messaging.adapters.PhotoPickerAdapter
import com.dpad.messaging.databinding.ActivityPhotoPickerBinding
import com.dpad.messaging.helpers.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Built-in photo grid backed by the device media database.
 *
 * Some ROMs ship a document picker that shows empty folders even when the
 * gallery has photos. This screen avoids that picker entirely and offers a
 * "Browse files" fallback for anything the media database does not index.
 */
class PhotoPickerActivity : BaseActivity() {

    private lateinit var binding: ActivityPhotoPickerBinding
    private val photoAdapter = PhotoPickerAdapter { uri -> returnPhoto(uri) }

    private val browseFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            returnPhoto(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityPhotoPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvPhotos.layoutManager = GridLayoutManager(this, 3)
        binding.rvPhotos.adapter = photoAdapter
        binding.btnBrowse.setOnClickListener { browseForFiles() }

        loadPhotos()
    }

    private fun loadPhotos() {
        if (!hasPhotoAccess()) {
            showMessage(R.string.photo_picker_permission)
            return
        }
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) { queryPhotos() }
            photoAdapter.submit(photos)
            if (photos.isEmpty()) {
                showMessage(R.string.no_photos_found)
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.rvPhotos.visibility = View.VISIBLE
                binding.rvPhotos.post { binding.rvPhotos.requestFocus() }
            }
        }
    }

    private fun showMessage(resId: Int) {
        binding.rvPhotos.visibility = View.GONE
        binding.tvEmpty.setText(resId)
        binding.tvEmpty.visibility = View.VISIBLE
        binding.btnBrowse.requestFocus()
    }

    private fun hasPhotoAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun queryPhotos(): List<Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val photos = ArrayList<Uri>(MAX_PHOTOS)
        runCatching {
            contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && photos.size < MAX_PHOTOS) {
                    photos.add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
                }
            }
        }
        return photos
    }

    private fun browseForFiles() {
        runCatching {
            browseFilesLauncher.launch(arrayOf("image/*"))
        }.onFailure {
            android.widget.Toast.makeText(
                this,
                R.string.attachment_open_unavailable,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun returnPhoto(uri: Uri) {
        setResult(Activity.RESULT_OK, Intent().setData(uri))
        finish()
    }

    private companion object {
        const val MAX_PHOTOS = 500
    }
}
