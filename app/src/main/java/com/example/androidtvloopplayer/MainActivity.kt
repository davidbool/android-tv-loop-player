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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var overlayText: TextView
    private lateinit var debugOverlayText: TextView

    private var imageLoopJob: Job? = null
    private var currentImageIndex = 0

    private data class PlaybackItem(
        val file: File,
        val durationMs: Long
    )

    private data class ImageLookupResult(
        val playbackItems: List<PlaybackItem>,
        val sourceLabel: String,
        val directoryPath: String,
        val directoryError: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.mainImageView)
        overlayText = findViewById(R.id.overlayText)
        debugOverlayText = findViewById(R.id.debugOverlayText)

        val startupDir = File(getExternalFilesDir(null), IMAGE_DIRECTORY_NAME)
        Log.d(TAG, "Image directory path: ${startupDir.absolutePath}")

        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        hideSystemUi()
        startImageLoop()
    }

    override fun onStop() {
        imageLoopJob?.cancel()
        imageLoopJob = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    private fun startImageLoop() {
        imageLoopJob?.cancel()
        imageLoopJob = lifecycleScope.launch {
            val lookupResult = withContext(Dispatchers.IO) {
                val imageDir = File(getExternalFilesDir(null), IMAGE_DIRECTORY_NAME)
                if (!imageDir.exists() && !imageDir.mkdirs()) {
                    return@withContext ImageLookupResult(
                        playbackItems = emptyList(),
                        sourceLabel = PLAYBACK_SOURCE_FOLDER,
                        directoryPath = imageDir.absolutePath,
                        directoryError = true
                    )
                }

                if (!imageDir.isDirectory) {
                    return@withContext ImageLookupResult(
                        playbackItems = emptyList(),
                        sourceLabel = PLAYBACK_SOURCE_FOLDER,
                        directoryPath = imageDir.absolutePath,
                        directoryError = true
                    )
                }

                val playlistFile = File(imageDir, PLAYLIST_FILE_NAME)
                val playlistItems = parsePlaylistFile(playlistFile, imageDir)
                if (playlistItems != null) {
                    return@withContext ImageLookupResult(
                        playbackItems = playlistItems,
                        sourceLabel = PLAYBACK_SOURCE_PLAYLIST,
                        directoryPath = imageDir.absolutePath,
                        directoryError = false
                    )
                }

                val imageFiles = imageDir
                    .listFiles { file ->
                        file.isFile && (file.extension.equals("jpg", ignoreCase = true) ||
                            file.extension.equals("png", ignoreCase = true))
                    }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()

                val folderItems = imageFiles.map { imageFile ->
                    PlaybackItem(file = imageFile, durationMs = DEFAULT_ITEM_DURATION_MS)
                }

                ImageLookupResult(
                    playbackItems = folderItems,
                    sourceLabel = PLAYBACK_SOURCE_FOLDER,
                    directoryPath = imageDir.absolutePath,
                    directoryError = false
                )
            }

            if (lookupResult.directoryError) {
                imageView.setImageDrawable(null)
                debugOverlayText.visibility = View.GONE
                overlayText.text = getString(
                    R.string.image_directory_error,
                    lookupResult.directoryPath,
                    lookupResult.directoryPath
                )
                overlayText.visibility = View.VISIBLE
                return@launch
            }

            if (lookupResult.playbackItems.isEmpty()) {
                imageView.setImageDrawable(null)
                debugOverlayText.visibility = View.GONE
                overlayText.text = getString(R.string.no_images_found_with_path, lookupResult.directoryPath)
                overlayText.visibility = View.VISIBLE
                return@launch
            }

            overlayText.visibility = View.GONE
            val playbackItems = lookupResult.playbackItems
            if (currentImageIndex >= playbackItems.size) {
                currentImageIndex = 0
            }

            while (isActive) {
                val currentItem = playbackItems[currentImageIndex]
                val decodedBitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(currentItem.file, imageView.width, imageView.height)
                }

                if (decodedBitmap != null) {
                    imageView.setImageBitmap(decodedBitmap)
                    debugOverlayText.text = getString(
                        R.string.image_debug_status,
                        lookupResult.sourceLabel,
                        currentImageIndex + 1,
                        playbackItems.size
                    )
                    debugOverlayText.visibility = View.VISIBLE
                }

                currentImageIndex = (currentImageIndex + 1) % playbackItems.size
                delay(currentItem.durationMs)
            }
        }
    }

    private fun parsePlaylistFile(playlistFile: File, imageDir: File): List<PlaybackItem>? {
        if (!playlistFile.exists()) {
            return null
        }

        return try {
            val rawText = playlistFile.readText()
            val jsonArray = JSONArray(rawText)
            val items = mutableListOf<PlaybackItem>()

            for (index in 0 until jsonArray.length()) {
                val entry = jsonArray.optJSONObject(index)
                if (entry == null) {
                    Log.w(TAG, "Skipping playlist entry $index: not a JSON object")
                    continue
                }

                val fileName = entry.optString("file", "").trim()
                if (fileName.isEmpty()) {
                    Log.w(TAG, "Skipping playlist entry $index: missing file")
                    continue
                }

                val imageFile = File(imageDir, fileName)
                if (!imageFile.exists() || !imageFile.isFile) {
                    Log.w(TAG, "Skipping playlist entry $index: file does not exist (${imageFile.absolutePath})")
                    continue
                }

                val durationMs = if (entry.has("durationMs")) {
                    entry.optLong("durationMs", DEFAULT_ITEM_DURATION_MS)
                } else {
                    DEFAULT_ITEM_DURATION_MS
                }.coerceIn(MIN_ITEM_DURATION_MS, MAX_ITEM_DURATION_MS)

                items.add(PlaybackItem(file = imageFile, durationMs = durationMs))
            }

            items
        } catch (e: Exception) {
            Log.w(TAG, "Invalid playlist.json. Falling back to folder scan.", e)
            null
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
            val halfHeight = height / 2
            val halfWidth = width / 2

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
        private const val PLAYLIST_FILE_NAME = "playlist.json"
        private const val PLAYBACK_SOURCE_PLAYLIST = "playlist"
        private const val PLAYBACK_SOURCE_FOLDER = "folder"
        private const val DEFAULT_ITEM_DURATION_MS = 5_000L
        private const val MIN_ITEM_DURATION_MS = 1_000L
        private const val MAX_ITEM_DURATION_MS = 60_000L
    }
}
