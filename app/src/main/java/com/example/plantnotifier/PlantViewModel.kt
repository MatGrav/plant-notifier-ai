package com.example.plantnotifier

// Backup
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlantViewModel(application: Application) : AndroidViewModel(application) {
    private val db = PlantDatabase.getDatabase(application)
    private val dao = db.plantDao()

    fun runManualCheckAndTest() {
        val context = getApplication<Application>().applicationContext

        // 1. Questa arriva sempre perché è fuori dalla coroutine "sospettata"
        sendNotification(context, "Il sistema di notifiche è attivo! 🛠️", isTest = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // USIAMO LA NUOVA FUNZIONE SNAPSHOT (Senza .first())
                val plants = dao.getAllPlantsSnapshot()

                // Filtriamo solo le piante NON archiviate
                val thirstyPlants = plants.filter { plant ->
                    !plant.isArchived && getDaysRemaining(plant.lastWatered, plant.wateringDays) <= 0
                }

                // Torniamo sul thread principale per mostrare il risultato
                withContext(Dispatchers.Main) {
                    if (thirstyPlants.isNotEmpty()) {
                        sendNotification(context, "Hai ${thirstyPlants.size} piante che hanno sete! 💧", isTest = false)
                    } else {
                        // SE ARRIVA QUESTA, IL DATABASE È STATO LETTO MA NON CI SONO PIANTE IN SCADENZA
                        sendNotification(context, "Check completato: nessuna pianta ha sete.", isTest = false)
                    }
                }
            } catch (e: Exception) {
                // Se c'è un errore, lo vedrai nella notifica invece che nel log silenzioso
                withContext(Dispatchers.Main) {
                    sendNotification(context, "Errore DB: ${e.message}", isTest = false)
                }
            }
        }
    }

    // Funzione privata per creare la notifica (da mettere nel ViewModel o in un Helper)
    private fun sendNotification(context: Context, message: String, isTest: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "watering_channel"

        // Usiamo ID diversi per non sovrascrivere la notifica di test con quella reale
        val notificationId = if (isTest) 999 else 1

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_plant)
            .setContentTitle(if (isTest) "Test Notifica" else "Plant Notifier 🌿")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#4CAF50"))

        notificationManager.notify(notificationId, builder.build())
    }

    object NotificationHelper {
        fun showWateringNotification(context: Context, plantName: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "watering_channel"

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification_plant) // La tua nuova icona!
                .setContentTitle("Cura Piante 🌿")
                .setContentText("È ora di bagnare $plantName!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setColor(Color.parseColor("#4CAF50"))

            notificationManager.notify(plantName.hashCode(), builder.build())
        }
    }

    val allPlants: Flow<List<Plant>> = dao.getActivePlants().map { listaPiante ->
        listaPiante.sortedWith(
            compareBy<Plant> { it.lastWatered + (it.wateringDays * 24 * 60 * 60 * 1000L) }
                .thenBy { it.name }
        )
    }

    val archivedPlants: Flow<List<Plant>> = dao.getArchivedPlants().map { listaPiante ->
        listaPiante.sortedBy { it.name }
    }

    private fun rotateImageIfRequired(context: Context, img: android.graphics.Bitmap, selectedImage: Uri): android.graphics.Bitmap {
        val input = context.contentResolver.openInputStream(selectedImage)
        val ei = androidx.exifinterface.media.ExifInterface(input!!)
        val orientation = ei.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)

        return when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(img, 90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(img, 180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(img, 270f)
            else -> img
        }
    }

    private fun rotateBitmap(bitmap: android.graphics.Bitmap, degrees: Float): android.graphics.Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun saveImageToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            var bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)

            // --- NUOVO: CORREZIONE ROTAZIONE ---
            bitmap = rotateImageIfRequired(context, bitmap, uri)
            // ------------------------------------

            // 1. Ridimensionamento (rimane uguale a prima)
            val maxSize = 1024f
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val finalWidth = if (bitmap.width > bitmap.height) maxSize.toInt() else (maxSize * ratio).toInt()
            val finalHeight = if (bitmap.width > bitmap.height) (maxSize / ratio).toInt() else maxSize.toInt()

            val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)

            // 2. Creazione file e 3. Compressione (rimane uguale)
            val fileName = "PLANT_${System.currentTimeMillis()}.jpg"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)

            val outputStream = java.io.FileOutputStream(file)
            resizedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
            outputStream.close()

            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    fun testNotification() {
        val context = getApplication<Application>().applicationContext

        // Proviamo a prendere il nome della prima pianta se disponibile,
        // altrimenti usiamo un nome generico per il test.
        // Usiamo 'allPlants.value' solo se allPlants è un MutableStateFlow.
        // Se ti dà ancora errore su .value, usa direttamente un nome fisso per il test:
        val plantNameForTest = "Pianta di Test 🌿"

        // Chiamiamo il nostro helper (o il codice della notifica)
        showNotificationInternal(context, plantNameForTest)
    }

    // Funzione di supporto interna per non ripetere il codice
    private fun showNotificationInternal(context: Context, name: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channelId = "watering_channel"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_plant)
            .setContentTitle("Cura Piante")
            .setContentText("È ora di bagnare $name!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#4CAF50"))

        notificationManager?.notify(999, builder.build())
    }

    fun addPlant(context: Context, name: String, waterDays: Int, fertDays: Int, lastW: Long, lastF: Long, imageUriString: String?) {
        viewModelScope.launch {
            var finalPath: String? = imageUriString

            // Se l'immagine viene dalla galleria (inizia con content://), la copiamo
            imageUriString?.let { uriStr ->
                if (uriStr.startsWith("content://")) {
                    finalPath = saveImageToInternalStorage(context, Uri.parse(uriStr))
                }
            }

            val newPlant = Plant(
                name = name,
                wateringDays = waterDays,
                fertilizationDays = fertDays,
                lastWatered = lastW,
                lastFertilized = lastF,
                imagePath = finalPath // Ora salviamo il percorso del file locale
            )
            dao.insertPlant(newPlant)
        }
    }
    fun waterPlant(plantId: Int) {
        viewModelScope.launch {
            dao.updateLastWatered(plantId, System.currentTimeMillis())
        }
    }

    fun postponeWatering(plantId: Int, days: Int) {
        viewModelScope.launch {
            val currentPlants = dao.getActivePlants().first()
            val plant = currentPlants.find { it.id == plantId }
            plant?.let {
                // Spostiamo l'ultimo "annaffiamento" in avanti,
                // così la prossima scadenza si allontana
                val newTime = it.lastWatered + (days * 24 * 60 * 60 * 1000L)
                dao.insertPlant(it.copy(lastWatered = newTime))
            }
        }
    }

    fun deletePlant(plant: Plant) {
        viewModelScope.launch {
            dao.deletePlant(plant)
        }
    }

    fun archivePlant(plantId: Int) {
        viewModelScope.launch {
            dao.updateArchivedStatus(plantId, true)
        }
    }

    fun unarchivePlant(plantId: Int) {
        viewModelScope.launch {
            dao.updateArchivedStatus(plantId, false)
        }
    }

    fun updatePlant(context: Context, plant: Plant, newImageUriString: String?) {
        viewModelScope.launch {
            var finalPath: String? = newImageUriString

            // Controlliamo se la nuova immagine è un URI della galleria (temporaneo)
            newImageUriString?.let { uriStr ->
                if (uriStr.startsWith("content://")) {
                    // Copiamo l'immagine e otteniamo il percorso permanente
                    finalPath = saveImageToInternalStorage(context, uriStr.toUri())
                }
            }

            // Creiamo la copia aggiornata della pianta
            val updatedPlant = plant.copy(imagePath = finalPath)

            // Salviamo nel database
            dao.insertPlant(updatedPlant)
        }
    }
    fun exportDataToJSON(context: Context) {
        viewModelScope.launch {
            // 1. Prendi tutte le piante
            val plants = dao.getAllPlantsSnapshot()

            // 2. Crea il contenuto JSON (molto semplificato)
            val jsonString = StringBuilder().append("[\n")
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
                jsonString.append("  }")
                if (index < plants.size - 1) jsonString.append(",")
                jsonString.append("\n")
            }
            jsonString.append("]")

            // 3. Salva il file nella cartella Downloads
            try {
                val fileName = "piante_backup_${System.currentTimeMillis()}.json"
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                file.writeText(jsonString.toString())

                android.widget.Toast.makeText(context, "Backup salvato in Download!", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Errore nel salvataggio", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun importDataFromJSON(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                android.util.Log.d("PLANT_DEBUG", "Contenuto file: $jsonString") // Vedi questo nel Logcat

                if (jsonString.isNullOrBlank()) return@launch

                val plantsList = mutableListOf<Plant>()

                // Regex aggiornata per includere l'opzionale imageName
                val regex = Regex("""name":\s*"(.*?)".*?days":\s*(\d+).*?lastWatered":\s*(\d+).*?fertDays":\s*(\d+).*?lastFert":\s*(\d+)(?:.*?isArchived":\s*(true|false))?(?:.*?imageName":\s*(?:"(.*?)"|null))?""", RegexOption.DOT_MATCHES_ALL)
                val matches = regex.findAll(jsonString).toList()
                android.util.Log.d("PLANT_DEBUG", "Piante trovate nella Regex: ${matches.size}")

                matches.forEach { match ->
                    val imageName = match.groupValues.getOrNull(7)
                    val fullPath = if (!imageName.isNullOrBlank()) {
                        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), imageName)
                        if (file.exists()) file.absolutePath else null
                    } else null

                    plantsList.add(Plant(
                        name = match.groupValues[1],
                        wateringDays = match.groupValues[2].toInt(),
                        lastWatered = match.groupValues[3].toLong(),
                        imagePath = fullPath,
                        fertilizationDays = match.groupValues[4].toInt(),
                        lastFertilized = match.groupValues[5].toLong(),
                        isArchived = match.groupValues[6].toBoolean()
                    ))
                }

                if (plantsList.isNotEmpty()) {
                    plantsList.forEach { dao.insertPlant(it) }
                    // Forza un piccolo feedback visivo
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Ripristinate ${plantsList.size} piante! 🌱", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    android.util.Log.e("PLANT_DEBUG", "La Regex non ha trovato corrispondenze nel file")
                }
            } catch (e: Exception) {
                android.util.Log.e("PLANT_DEBUG", "Errore import: ${e.message}")
            }
        }
    }
    fun fertilizePlant(plantId: Int) {
        viewModelScope.launch {
            // Usa getAllPlantsSnapshot() per essere sicuri di avere i dati
            val plants = dao.getAllPlantsSnapshot()
            val plant = plants.find { it.id == plantId }

            plant?.let {
                val updatedPlant = it.copy(lastFertilized = System.currentTimeMillis())
                dao.insertPlant(updatedPlant)
                // Opzionale: un log per conferma
                android.util.Log.d("PLANT_DEBUG", "Pianta ${it.name} concimata!")
            }
        }
    }
    fun autoBackup(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val plants = dao.getAllPlantsSnapshot()
                if (plants.isEmpty()) return@launch

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

                // Nome file fisso per sovrascriverlo
                val fileName = "plant_notifier_auto_backup.json"
                val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(folder, fileName)

                file.writeText(jsonString.toString())
                android.util.Log.d("BACKUP", "Auto-backup eseguito in Download")
            } catch (e: Exception) {
                android.util.Log.e("BACKUP", "Errore auto-backup: ${e.message}")
            }
        }
    }

    // Variabili per memorizzare l'ultimo stato prima della modifica (per l'annulla)
    private var lastPlantState: Plant? = null

    fun waterPlantWithUndo(plantId: Int) {
        viewModelScope.launch {
            // Dato che allPlants è un Flow, dobbiamo estrarre la lista attuale
            val currentPlants = dao.getAllPlantsSnapshot()
            val plant = currentPlants.find { it.id == plantId }

            if (plant != null) {
                lastPlantState = plant.copy() // Salviamo una copia fedele del passato
                waterPlant(plantId) // Esegue l'aggiornamento
            }
        }
    }

    fun fertilizePlantWithUndo(plantId: Int) {
        viewModelScope.launch {
            val currentPlants = dao.getAllPlantsSnapshot()
            val plant = currentPlants.find { it.id == plantId }

            if (plant != null) {
                lastPlantState = plant.copy()
                fertilizePlant(plantId) // Esegue l'aggiornamento concime
            }
        }
    }

    fun undoLastAction() {
        val plantToRestore = lastPlantState
        if (plantToRestore != null) {
            viewModelScope.launch {
                dao.insertPlant(plantToRestore) // Ripristina lo stato salvato
                lastPlantState = null
            }
        }
    }
}