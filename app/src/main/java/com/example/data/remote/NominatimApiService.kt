package com.example.data.remote

import com.example.data.remote.dto.NominatimPlaceDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface NominatimApiService {

    @GET("search")
    suspend fun searchPlaces(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("limit") limit: Int = 8,
        @Query("accept-language") acceptLanguage: String = "en"
    ): List<NominatimPlaceDto>

    @GET("reverse")
    suspend fun reverseGeocode(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("format") format: String = "jsonv2",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("zoom") zoom: Int = 18,
        @Query("accept-language") acceptLanguage: String = "en"
    ): NominatimPlaceDto

    companion object {
        private const val BASE_URL = "https://nominatim.openstreetmap.org/"
        const val USER_AGENT = "PinWake-Android/1.0 (https://pinwake.app; contact@pinwake.app)"

        fun create(): NominatimApiService {
            val userAgentInterceptor = Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }

            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
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
                .create(NominatimApiService::class.java)
        }
    }
}
