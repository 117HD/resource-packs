package ci.services

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.kohsuke.github.GitHub

object GitHubClientService {
    fun createAuthenticatedClient(token: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "token $token")
                        .header("Accept", "application/vnd.github.v3+json")
                        .build()
                )
            })
            .build()

    fun connect(token: String): GitHub = GitHub.connectUsingOAuth(token)
}

