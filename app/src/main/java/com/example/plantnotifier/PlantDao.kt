package com.example.plantnotifier

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantDao {
    @Query("SELECT * FROM plants WHERE isArchived = 0 ORDER BY name ASC")
    fun getActivePlants(): Flow<List<Plant>>

    @Query("SELECT * FROM plants WHERE isArchived = 1 ORDER BY name ASC")
    fun getArchivedPlants(): Flow<List<Plant>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlant(plant: Plant)

    @Delete
    suspend fun deletePlant(plant: Plant)

    @Query("UPDATE plants SET lastWatered = :timestamp WHERE id = :plantId")
    suspend fun updateLastWatered(plantId: Int, timestamp: Long)

    @Query("SELECT * FROM plants")
    suspend fun getAllPlantsSnapshot(): List<Plant>

    @Query("UPDATE plants SET isArchived = :isArchived WHERE id = :plantId")
    suspend fun updateArchivedStatus(plantId: Int, isArchived: Boolean)
}