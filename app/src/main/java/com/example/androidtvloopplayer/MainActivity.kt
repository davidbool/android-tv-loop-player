package com.example.androidtvloopplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var overlayText: TextView

    private data class ImageLookupResult(
        val firstImage: File?,
        val directoryPath: String,
        val directoryError: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.mainImageView)
        overlayText = findViewById(R.id.overlayText)

        val startupDir = File(getExternalFilesDir(null), IMAGE_DIRECTORY_NAME)
        Log.d(TAG, "Image directory path: ${startupDir.absolutePath}")

        hideSystemUi()
        loadFirstImage()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    private fun loadFirstImage() {
        lifecycleScope.launch {
            val lookupResult = withContext(Dispatchers.IO) {
                val imageDir = File(getExternalFilesDir(null), IMAGE_DIRECTORY_NAME)
                if (!imageDir.exists() && !imageDir.mkdirs()) {
                    return@withContext ImageLookupResult(
                        firstImage = null,
                        directoryPath = imageDir.absolutePath,
                        directoryError = true
                    )
                }

                if (!imageDir.isDirectory) {
                    return@withContext ImageLookupResult(
                        firstImage = null,
                        directoryPath = imageDir.absolutePath,
                        directoryError = true
                    )
                }

                val firstImage = imageDir
                    .listFiles { file ->
                        file.isFile && (file.extension.equals("jpg", ignoreCase = true) ||
                            file.extension.equals("png", ignoreCase = true))
                    }
                    ?.sortedBy { it.name.lowercase() }
                    ?.firstOrNull()

                ImageLookupResult(
                    firstImage = firstImage,
                    directoryPath = imageDir.absolutePath,
                    directoryError = false
                )
            }

            if (lookupResult.directoryError) {
                imageView.setImageDrawable(null)
                overlayText.text = getString(
                    R.string.image_directory_error,
                    lookupResult.directoryPath,
                    lookupResult.directoryPath
                )
                overlayText.visibility = View.VISIBLE
                return@launch
            }

            if (lookupResult.firstImage == null) {
                imageView.setImageDrawable(null)
                overlayText.text = getString(R.string.no_images_found_with_path, lookupResult.directoryPath)
                overlayText.visibility = View.VISIBLE
                return@launch
            }

            val decodedBitmap = withContext(Dispatchers.IO) {
                decodeSampledBitmap(lookupResult.firstImage, imageView.width, imageView.height)
            }

            if (decodedBitmap != null) {
                imageView.setImageBitmap(decodedBitmap)
                overlayText.visibility = View.GONE
            } else {
                imageView.setImageDrawable(null)
                overlayText.text = getString(R.string.no_images_found_with_path, lookupResult.directoryPath)
                overlayText.visibility = View.VISIBLE
            }
        }
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val targetWidth = if (reqWidth > 0) reqWidth else resources.displayMetrics.widthPixels
        val targetHeight = if (reqHeight > 0) reqHeight else resources.displayMetrics.heightPixels

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOptions, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun hideSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val IMAGE_DIRECTORY_NAME = "advision_demo"
    }
}
