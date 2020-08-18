package top.zibin.luban

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * @author zhengjy
 * @since 2020/08/18
 * Description:
 */
internal class FileAdapter(
    private val context: Context,
    private val file: File
) : InputStreamAdapter() {

    @Throws(IOException::class)
    override fun openInternal(): InputStream? {
        return FileInputStream(file)
    }

    override fun getPath(): String? {
        return file.absolutePath
    }

    override fun getUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, LubanProvider.AUTHORITY, file)
        } else {
            Uri.fromFile(file)
        }
    }

}