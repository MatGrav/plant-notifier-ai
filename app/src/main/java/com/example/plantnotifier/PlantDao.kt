package com.example.plantnotifier

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantDao {
    @Query("SELECT * FROM plants ORDER BY name ASC")
    fun getAllPlants(): Flow<List<Plant>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlant(plant: Plant)

    @Delete
    suspend fun deletePlant(plant: Plant)

    @Query("UPDATE plants SET lastWatered = :timestamp WHERE id = :plantId")
    suspend fun updateLastWatered(plantId: Int, timestamp: Long)

    // Nel file PlantDao.kt
    @Query("SELECT * FROM plants")
    suspend fun getAllPlantsSnapshot(): List<Plant> // <--- Questa restituisce i dati subito!
}