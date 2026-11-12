package chat.stoat.air.glide

import android.content.Context
import chat.stoat.air.AirLocalServer
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import okhttp3.OkHttpClient
import java.io.InputStream

/**
 * AIR Chat: every image (avatars, server icons, attachments, emojis) is served by the built-in
 * peer-to-peer backend, so Glide loads them through the same interceptor as the rest of the app.
 */
@GlideModule
class AirGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val client = OkHttpClient.Builder()
            .addInterceptor(AirLocalServer.interceptor)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(client))
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
