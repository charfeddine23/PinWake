package com.example.data.remote

import com.example.data.remote.dto.GitHubReleaseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface GitHubReleaseApiService {

    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String = REPO_OWNER,
        @Path("repo") repo: String = REPO_NAME
    ): GitHubReleaseDto

    companion object {
        private const val BASE_URL = "https://api.github.com/"
        const val REPO_OWNER = "charfeddine23"
        const val REPO_NAME = "PinWake"
        const val REPO_RELEASES_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

        fun create(): GitHubReleaseApiService {
            val headerInterceptor = Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", NominatimApiService.USER_AGENT)
                    .build()
                chain.proceed(request)
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(headerInterceptor)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(GitHubReleaseApiService::class.java)
        }
    }
}
