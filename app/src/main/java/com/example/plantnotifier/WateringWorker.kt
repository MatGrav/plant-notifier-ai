package com.example.plantnotifier

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.flow.first
import java.io.File
import android.os.Environment

class WateringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = PlantDatabase.getDatabase(applicationContext)
        val plants = db.plantDao().getAllPlants().first()

        // 1. Filtra le piante che hanno sete
        val thirstyPlants = plants.filter {
            getDaysRemaining(it.lastWatered, it.wateringDays) <= 0
        }

        // 2. Filtra le piante che hanno bisogno di concime
        val hungryPlants = plants.filter {
            getDaysRemaining(it.lastFertilized, it.fertilizationDays) <= 0
        }

        // 3. Se c'è almeno una pianta che ha bisogno di cure, invia la notifica
        if (thirstyPlants.isNotEmpty() || hungryPlants.isNotEmpty()) {
            val title = "Cure necessarie per le tue piante! 🌿"

            // Costruiamo un messaggio dinamico
            val message = when {
                thirstyPlants.isNotEmpty() && hungryPlants.isNotEmpty() ->
                    "Hai ${thirstyPlants.size} piante da bagnare e ${hungryPlants.size} da concimare!"
                thirstyPlants.isNotEmpty() ->
                    "Hai ${thirstyPlants.size} piante che hanno sete! 💧"
                else ->
                    "È ora di concimare ${hungryPlants.size} piante! 🧪"
            }

            sendNotification(message) // Passiamo la stringa alla funzione di notifica
        }
        try {
            performSilentBackup(applicationContext, plants)
        } catch (e: Exception) {
            // Fallimento silenzioso del backup
        }

        return Result.success()
    }

    private fun sendNotification(message: String) {
        val notificationManager = applicationContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "watering_channel"

        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces) // Un'icona a forma di fogliolina/mappa
            .setContentTitle("Plant Notifier")
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
    }
    private fun performSilentBackup(context: Context, plants: List<Plant>) {
        val jsonString = StringBuilder("[\n")
        // ... (stessa logica di costruzione stringa vista sopra) ...
        jsonString.append("]")

        val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsFolder.absolutePath, "plant_notifier_auto_backup.json")
        file.writeText(jsonString.toString())
    }
}