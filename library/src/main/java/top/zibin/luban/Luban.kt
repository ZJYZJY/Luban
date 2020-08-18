package top.zibin.luban

import android.content.Context
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import android.os.Message
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class Luban private constructor(builder: Builder) : Handler.Callback {

    private val focusAlpha = false
    private val mLeastCompressSize: Int
    private val mRenameListener: OnRenameListener?
    private val mCompressListener: OnCompressListener?
    private val mCompressionPredicate: CompressionPredicate?
    private val mStreamProviders: MutableList<InputStreamProvider>
    private val mHandler: Handler

    /**
     * Returns a file with a cache image name in the private cache directory.
     *
     * @param context A context.
     */
    private fun getImageCacheFile(context: Context, suffix: String): File {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val targetDir = File(cacheDir, DEFAULT_DISK_CACHE_DIR)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return File(targetDir, "${System.currentTimeMillis()}${(Math.random() * 1000).toInt()}" +
            if (suffix.isEmpty()) ".jpg" else suffix)
    }

    private fun getImageCustomFile(context: Context, filename: String): File {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val targetDir = File(cacheDir, DEFAULT_DISK_CACHE_DIR)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return File(targetDir, filename)
    }

    /**
     * start asynchronous compress thread
     */
    private fun launch(context: Context) {
        if (mStreamProviders.size == 0 && mCompressListener != null) {
            mCompressListener.onError(NullPointerException("image file cannot be null"))
        }
        val iterator = mStreamProviders.iterator()
        while (iterator.hasNext()) {
            val path = iterator.next()
            AsyncTask.SERIAL_EXECUTOR.execute {
                try {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_COMPRESS_START))
                    val result = compress(context, path)
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_COMPRESS_SUCCESS, result))
                } catch (e: IOException) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_COMPRESS_ERROR, e))
                }
            }
            iterator.remove()
        }
    }

    /**
     * start compress and return the file
     */
    @Throws(IOException::class)
    private fun get(input: InputStreamProvider, context: Context): File {
        return try {
            Engine(input, getImageCacheFile(context, Checker.SINGLE.extSuffix(input)), focusAlpha).compress()
        } finally {
            input.close()
        }
    }

    @Throws(IOException::class)
    private fun get(context: Context): List<File> {
        val results: MutableList<File> = ArrayList()
        val iterator = mStreamProviders.iterator()
        while (iterator.hasNext()) {
            results.add(compress(context, iterator.next()))
            iterator.remove()
        }
        return results
    }

    @Throws(IOException::class)
    private fun compress(context: Context, path: InputStreamProvider): File {
        return try {
            compressReal(context, path)
        } finally {
            path.close()
        }
    }

    @Throws(IOException::class)
    private fun compressReal(context: Context, provider: InputStreamProvider): File {
        val result: File
        var outFile = getImageCacheFile(context, Checker.SINGLE.extSuffix(provider))
        if (mRenameListener != null) {
            val filename = mRenameListener.rename(provider.getPath())
            outFile = getImageCustomFile(context, filename)
        }
        result = if (mCompressionPredicate != null) {
            if (mCompressionPredicate.apply(provider.getPath())
                && Checker.SINGLE.needCompress(context, mLeastCompressSize, provider)) {
                Engine(provider, outFile, focusAlpha).compress()
            } else {
                FileOutputStream(outFile).use {
                    provider.open()?.copyTo(it)
                    it.flush()
                }
                outFile
            }
        } else {
            if (Checker.SINGLE.needCompress(context, mLeastCompressSize, provider)) {
                Engine(provider, outFile, focusAlpha).compress()
            } else {
                FileOutputStream(outFile).use {
                    provider.open()?.copyTo(it)
                    it.flush()
                }
                outFile
            }
        }
        return result
    }

    override fun handleMessage(msg: Message): Boolean {
        if (mCompressListener == null) return false
        when (msg.what) {
            MSG_COMPRESS_START -> mCompressListener.onStart()
            MSG_COMPRESS_SUCCESS -> mCompressListener.onSuccess(msg.obj as File)
            MSG_COMPRESS_ERROR -> mCompressListener.onError(msg.obj as Throwable)
        }
        return false
    }

    class Builder internal constructor(private val context: Context) {
        var mTargetDir: String? = null
        var focusAlpha = false
        var mLeastCompressSize = 500
        var mRenameListener: OnRenameListener? = null
        var mCompressListener: OnCompressListener? = null
        var mCompressionPredicate: CompressionPredicate? = null
        val mStreamProviders: MutableList<InputStreamProvider>
        private fun build(): Luban {
            return Luban(this)
        }

        fun load(inputStreamProvider: InputStreamProvider): Builder {
            mStreamProviders.add(inputStreamProvider)
            return this
        }

        fun load(path: String): Builder {
            mStreamProviders.add(FileAdapter(context, File(path)))
            return this
        }

        fun load(file: File): Builder {
            mStreamProviders.add(FileAdapter(context, file))
            return this
        }

        fun load(uri: Uri): Builder {
            mStreamProviders.add(UriAdapter(context, uri))
            return this
        }

        fun <T> load(list: List<T>): Builder {
            for (src in list) {
                when (src) {
                    is String -> load(src as String)
                    is File -> load(src as File)
                    is Uri -> load(src as Uri)
                    else -> throw IllegalArgumentException("Incoming data type exception, " +
                        "it must be String, File, Uri or Bitmap")
                }
            }
            return this
        }

        fun setRenameListener(listener: OnRenameListener?): Builder {
            mRenameListener = listener
            return this
        }

        fun setCompressListener(listener: OnCompressListener?): Builder {
            mCompressListener = listener
            return this
        }

        fun setTargetDir(targetDir: String?): Builder {
            mTargetDir = targetDir
            return this
        }

        /**
         * Do I need to keep the image's alpha channel
         *
         * @param focusAlpha
         *
         * true - to keep alpha channel, the compress speed will be slow.
         *
         *  false - don't keep alpha channel, it might have a black background.
         */
        fun setFocusAlpha(focusAlpha: Boolean): Builder {
            this.focusAlpha = focusAlpha
            return this
        }

        /**
         * do not compress when the origin image file size less than one value
         *
         * @param size the value of file size, unit KB, default 500K
         */
        fun ignoreBy(size: Int): Builder {
            mLeastCompressSize = size
            return this
        }

        /**
         * do compress image when return value was true, otherwise, do not compress the image file
         *
         * @param compressionPredicate A predicate callback that returns true or false for the given input path should be compressed.
         */
        fun filter(compressionPredicate: CompressionPredicate?): Builder {
            mCompressionPredicate = compressionPredicate
            return this
        }

        /**
         * begin compress image with asynchronous
         */
        fun launch() {
            build().launch(context)
        }

        @Throws(IOException::class)
        operator fun get(path: String): File {
            return build().get(FileAdapter(context, File(path)), context)
        }

        /**
         * begin compress image with synchronize
         *
         * @return the thumb image file list
         */
        @Throws(IOException::class)
        fun get(): List<File> {
            return build().get(context)
        }

        init {
            mStreamProviders = ArrayList()
        }
    }

    companion object {
        private const val TAG = "Luban"
        private const val DEFAULT_DISK_CACHE_DIR = "luban_disk_cache"
        private const val MSG_COMPRESS_SUCCESS = 0
        private const val MSG_COMPRESS_START = 1
        private const val MSG_COMPRESS_ERROR = 2

        @JvmStatic
        fun with(context: Context): Builder {
            return Builder(context)
        }
    }

    init {
        mRenameListener = builder.mRenameListener
        mStreamProviders = builder.mStreamProviders
        mCompressListener = builder.mCompressListener
        mLeastCompressSize = builder.mLeastCompressSize
        mCompressionPredicate = builder.mCompressionPredicate
        mHandler = Handler(Looper.getMainLooper(), this)
    }
}