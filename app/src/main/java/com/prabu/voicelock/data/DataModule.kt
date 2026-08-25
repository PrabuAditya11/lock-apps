package com.prabu.voicelock.data

import android.content.Context
import androidx.room.Room
import com.prabu.voicelock.data.local.LockedAppDao
import com.prabu.voicelock.data.local.VoiceLockDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VoiceLockDatabase =
        Room.databaseBuilder(context, VoiceLockDatabase::class.java, "voicelock.db").build()

    @Provides
    fun provideLockedAppDao(database: VoiceLockDatabase): LockedAppDao = database.lockedAppDao()
}
