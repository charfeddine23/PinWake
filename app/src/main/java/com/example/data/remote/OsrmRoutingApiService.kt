package com.example.data.remote

import com.example.BuildConfig
import com.example.data.remote.dto.OsrmRouteResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface OsrmRoutingApiService {

    @GET("route/v1/{profile}/{coordinates}")
    suspend fun getRoute(
        @Path("profile") profile: String,
        @Path(value = "coordinates", encoded = true) coordinates: String,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "geojson",
        @Query("steps") steps: Boolean = true,
        @Query("annotations") annotations: Boolean = false
    ): OsrmRouteResponseDto

    companion object {
        private const val BASE_URL = "https://router.project-osrm.org/"

        fun create(): OsrmRoutingApiService {
            val userAgentInterceptor = Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", NominatimApiService.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }

            val loggingInterceptor = HttpLoggingInterceptor { message ->
                if (BuildConfig.DEBUG) {
                    val sanitized = message.replace(
                        Regex("/route/v1/[^/]+/[^?\\s]+"),
                        "/route/v1/<profile>/<coords>"
                    )
                    android.util.Log.d("OsrmHttp", sanitized)
                }
            }.apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(userAgentInterceptor)
                .addInterceptor(loggingInterceptor)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(OsrmRoutingApiService::class.java)
        }
    }
}
