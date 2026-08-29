package com.ebookreader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ebookreader.di.Injector
import okhttp3.OkHttpClient
import timber.log.Timber

class EBookReaderApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Injector.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        // Pixiv 图片服务器（i.pximg.net / s.pximg.net）需要 Referer 头，否则 403 加载不出封面
                        if (request.url.host.endsWith("pximg.net")) {
                            chain.proceed(
                                request.newBuilder()
                                    .header("Referer", "https://www.pixiv.net/")
                                    .build()
                            )
                        } else {
                            chain.proceed(request)
                        }
                    }
                    .build()
            )
            .build()
}
