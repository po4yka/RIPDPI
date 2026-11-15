package com.poyka.ripdpi.data.diagnostics

import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns DoH HTTP calls because the upstream DNS cache keeps queries running after subscriber cancellation. */
internal class OwnedDohDns(
    private val bootstrapClient: OkHttpClient,
    private val createDns: (OkHttpClient) -> Dns,
) : Dns {
    private val legacyDns = createDns(bootstrapClient)

    override fun lookup(hostname: String) = legacyDns.lookup(hostname)

    override fun newCall(request: Dns.Request): Dns.Call {
        val cancelled = AtomicBoolean(false)
        val dispatcher = Dispatcher(bootstrapClient.dispatcher.executorService)
        val client =
            bootstrapClient
                .newBuilder()
                .dispatcher(dispatcher)
                .addInterceptor { chain ->
                    if (cancelled.get()) throw IOException("DoH query cancelled")
                    chain.proceed(chain.request())
                }.build()
        val delegate = createDns(client).newCall(request)
        return object : Dns.Call by delegate {
            override fun cancel() {
                cancelled.set(true)
                try {
                    delegate.cancel()
                } finally {
                    dispatcher.cancelAll()
                }
            }
        }
    }
}
