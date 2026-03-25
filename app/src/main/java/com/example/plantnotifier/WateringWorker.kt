package com.example.plantnotifier

import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.io.File

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
        if (thirstyPlants.isNotEmpty()) {
            val title = "Cure necessarie per le tue piante! 🌿"

            // Costruiamo un messaggio dinamico
            val message = when {
                hungryPlants.isNotEmpty() ->
                    "Hai ${thirstyPlants.size} piante da bagnare e ${hungryPlants.size} da concimare!"
                else ->
                    "Hai ${thirstyPlants.size} piante che hanno sete! 💧"
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
        val context = applicationContext
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "watering_channel"

        // Creiamo l'intent per aprire l'app al click
        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_plant) // L'icona stilizzata che abbiamo creato
            .setContentTitle("Plant Notifier 🌿")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent) // <--- AGGIUNTO: ora la notifica è cliccabile!
            .setAutoCancel(true) // Scompare dopo il click
            .setColor(android.graphics.Color.parseColor("#4CAF50")) // Verde natura
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