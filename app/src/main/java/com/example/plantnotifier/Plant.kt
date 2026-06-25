package com.example.plantnotifier

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plants")
data class Plant(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val species: String = "",
    val imagePath: String? = null, // Salveremo il percorso del file, non l'immagine intera
    val wateringDays: Int, // Ogni quanti giorni dare acqua
    val lastWatered: Long, // Data dell'ultima volta (timestamp)
    val fertilizationDays: Int, // Ogni quanti giorni concimare
    val lastFertilized: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false // Nuova proprietà per l'archivio
)