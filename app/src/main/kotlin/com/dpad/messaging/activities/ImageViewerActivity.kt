package com.dpad.messaging.activities

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.RequestListener
import com.dpad.messaging.R
import kotlin.math.max
import com.dpad.messaging.BuildConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageViewerActivity : BaseActivity() {
    private lateinit var imageView: ImageView
    private lateinit var zoomLabel: TextView
    private lateinit var saveButton: View
    private lateinit var imageUri: Uri

    private val matrixValues = Matrix()
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val zoomLevels = floatArrayOf(1f, 2f, 3f, 4f)
    private val panStepPx = 96f
    private val keyTag = "ImageViewerKeys"
    private var pendingSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        imageView = findViewById(R.id.iv_full_image)
        zoomLabel = findViewById(R.id.tv_zoom_level)
        saveButton = findViewById(R.id.btn_save_image)
        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)

        if (uriString.isNullOrBlank()) {
            finish()
            return
        }
        imageUri = Uri.parse(uriString)

        Glide.with(this)
            .load(imageUri)
            .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    imageView.post { resetTransform() }
                    return false
                }
            })
            .into(imageView)

        imageView.setOnClickListener { finish() }
        saveButton.setOnClickListener { saveToGallery() }
        imageView.requestFocus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            pendingSave
        ) {
            pendingSave = false
            saveToGallery()
        } else if (requestCode == REQUEST_WRITE_STORAGE) {
            pendingSave = false
            showToast(R.string.photo_save_failed)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (BuildConfig.DEBUG && event != null) {
            Log.d(
                keyTag,
                "keyCode=$keyCode action=${event.action} source=${event.source} device=${event.deviceId} repeat=${event.repeatCount}"
            )
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                resetTransform()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                zoomIn()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (saveButton.hasFocus() || scaleFactor > 1f) {
                    if (saveButton.hasFocus()) imageView.requestFocus()
                    else zoomOut()
                } else {
                    saveButton.requestFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (saveButton.hasFocus()) {
                    imageView.requestFocus()
                } else {
                    zoomIn()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (saveButton.hasFocus()) {
                    saveToGallery()
                    return true
                }
            }
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_NUMPAD_4 -> {
                panBy(panStepPx, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                panBy(panStepPx, 0f)
                return true
            }
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_NUMPAD_6 -> {
                panBy(-panStepPx, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                panBy(-panStepPx, 0f)
                return true
            }
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_NUMPAD_2 -> {
                panBy(0f, panStepPx)
                return true
            }
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_NUMPAD_8 -> {
                panBy(0f, -panStepPx)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun zoomIn() {
        val currentIndex = zoomLevels.indexOfFirst { it >= scaleFactor - 0.01f }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1).coerceAtMost(zoomLevels.lastIndex)
        scaleFactor = zoomLevels[nextIndex]
        applyTransform()
    }

    private fun zoomOut() {
        val currentIndex = zoomLevels.indexOfLast { it <= scaleFactor + 0.01f }
        val prevIndex = if (currentIndex <= 0) 0 else currentIndex - 1
        scaleFactor = zoomLevels[prevIndex]
        if (scaleFactor <= 1f) {
            offsetX = 0f
            offsetY = 0f
        }
        applyTransform()
    }

    private fun panBy(dx: Float, dy: Float) {
        if (scaleFactor <= 1f) return
        offsetX += dx
        offsetY += dy
        applyTransform()
    }

    private fun resetTransform() {
        scaleFactor = 1f
        offsetX = 0f
        offsetY = 0f
        applyTransform()
    }

    private fun applyTransform() {
        val drawable = imageView.drawable ?: return
        val viewW = imageView.width.toFloat()
        val viewH = imageView.height.toFloat()
        val drawW = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val drawH = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        if (viewW <= 0f || viewH <= 0f) return

        val baseScale = minOf(viewW / drawW, viewH / drawH)
        val actualScale = baseScale * scaleFactor

        val scaledW = drawW * actualScale
        val scaledH = drawH * actualScale

        val maxOffsetX = max(0f, (scaledW - viewW) / 2f)
        val maxOffsetY = max(0f, (scaledH - viewH) / 2f)

        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)

        val translateX = (viewW - scaledW) / 2f + offsetX
        val translateY = (viewH - scaledH) / 2f + offsetY

        matrixValues.reset()
        matrixValues.postScale(actualScale, actualScale)
        matrixValues.postTranslate(translateX, translateY)
        imageView.imageMatrix = matrixValues

        zoomLabel.text = getString(R.string.image_viewer_zoom_percent, (scaleFactor * 100).roundToInt())
    }

    private fun saveToGallery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = true
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_STORAGE
            )
            return
        }

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { copyImageToGallery() }
            showToast(if (saved) R.string.photo_saved_to_gallery else R.string.photo_save_failed)
        }
    }

    private fun copyImageToGallery(): Boolean {
        val mimeType = contentResolver.getType(imageUri)?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val displayName = "DPAD_SMS_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + extension

        return runCatching {
            contentResolver.openInputStream(imageUri)?.use { input ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/DPAD Messaging"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val outputUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: return@runCatching false

                    try {
                        contentResolver.openOutputStream(outputUri)?.use { output ->
                            input.copyTo(output)
                        } ?: throw IllegalStateException("Unable to open gallery output")
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(outputUri, values, null, null)
                        true
                    } catch (error: Exception) {
                        contentResolver.delete(outputUri, null, null)
                        throw error
                    }
                } else {
                    val directory = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "DPAD Messaging"
                    )
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw IllegalStateException("Unable to create gallery directory")
                    }
                    val outputFile = File(directory, displayName)
                    FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                    android.media.MediaScannerConnection.scanFile(
                        this,
                        arrayOf(outputFile.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                    true
                }
            } ?: false
        }.getOrElse { error ->
            if (BuildConfig.DEBUG) Log.e(TAG, "Unable to save image to gallery", error)
            false
        }
    }

    private fun showToast(messageResId: Int) {
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        private const val REQUEST_WRITE_STORAGE = 1001
        private const val TAG = "ImageViewer"
    }
}
