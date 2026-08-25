package com.prabu.voicelock.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [LockedAppEntity::class], version = 1, exportSchema = false)
abstract class VoiceLockDatabase : RoomDatabase() {
    abstract fun lockedAppDao(): LockedAppDao
}
