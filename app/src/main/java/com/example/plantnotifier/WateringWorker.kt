package com.example.plantnotifier

import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class WateringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = PlantDatabase.getDatabase(applicationContext)
        // Usiamo la versione snapshot che restituisce la lista completa, poi filtriamo le attive
        val allPlants = db.plantDao().getAllPlantsSnapshot()
        val activePlants = allPlants.filter { !it.isArchived }

        // 1. Filtra le piante attive che hanno sete
        val thirstyPlants = activePlants.filter {
            getDaysRemaining(it.lastWatered, it.wateringDays) <= 0
        }

        // 2. Filtra le piante attive che hanno bisogno di concime
        val hungryPlants = activePlants.filter {
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
            performSilentBackup(applicationContext, allPlants)
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
        plants.forEachIndexed { index, plant ->
            val imageName = plant.imagePath?.let { File(it).name }
            
            jsonString.append("  {\n")
            jsonString.append("    \"name\": \"${plant.name}\",\n")
            jsonString.append("    \"days\": ${plant.wateringDays},\n")
            jsonString.append("    \"lastWatered\": ${plant.lastWatered},\n")
            jsonString.append("    \"fertDays\": ${plant.fertilizationDays},\n")
            jsonString.append("    \"lastFert\": ${plant.lastFertilized},\n")
            jsonString.append("    \"isArchived\": ${plant.isArchived},\n")
            jsonString.append("    \"imageName\": ${if (imageName != null) "\"$imageName\"" else "null"}\n")
            jsonString.append("  }${if (index < plants.size - 1) "," else ""}\n")
        }
        jsonString.append("]")

        val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsFolder.absolutePath, "plant_notifier_auto_backup.json")
        file.writeText(jsonString.toString())
    }
}