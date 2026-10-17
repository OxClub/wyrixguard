package com.wyrix.guard.di

import android.content.Context
import androidx.room.Room
import com.wyrix.guard.data.CommandProcessor
import com.wyrix.guard.data.DeviceRepository
import com.wyrix.guard.data.EvidenceRepository
import com.wyrix.guard.data.SecureStore
import com.wyrix.guard.data.TrackingRepository
import com.wyrix.guard.data.crypto.DeviceKeys
import com.wyrix.guard.data.local.AppDatabase
import com.wyrix.guard.data.remote.SupabaseApi
import com.wyrix.guard.service.GeofenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "wyrix.db").fallbackToDestructiveMigration().build()
}

/** For Services/Receivers/Workers that Hilt does not construct. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun api(): SupabaseApi
    fun store(): SecureStore
    fun keys(): DeviceKeys
    fun db(): AppDatabase
    fun tracking(): TrackingRepository
    fun commands(): CommandProcessor
    fun geofences(): GeofenceManager
    fun devices(): DeviceRepository
    fun evidence(): EvidenceRepository
}

fun Context.entry(): AppEntryPoint = EntryPointAccessors.fromApplication(applicationContext, AppEntryPoint::class.java)
