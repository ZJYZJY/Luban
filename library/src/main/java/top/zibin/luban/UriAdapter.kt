package top.zibin.luban

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/**
 * @author zhengjy
 * @since 2020/08/18
 * Description:
 */
class UriAdapter(
    private val context: Context,
    private val uri: Uri
) : InputStreamAdapter() {

    @Throws(IOException::class)
    override fun openInternal(): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    override fun getPath(): String? {
        return uri.path
    }

    override fun getUri(): Uri {
        return uri
    }

}