package com.example.plantnotifier

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
// Backup
import android.os.Environment
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import androidx.core.net.toUri
import androidx.core.graphics.scale

class PlantViewModel(application: Application) : AndroidViewModel(application) {
    private val db = PlantDatabase.getDatabase(application)
    private val dao = db.plantDao()

    val allPlants: Flow<List<Plant>> = dao.getAllPlants().map { listaPiante ->
        listaPiante.sortedWith(
            compareBy<Plant> { it.lastWatered + (it.wateringDays * 24 * 60 * 60 * 1000L) }
                .thenBy { it.name }
        )
    }

    private fun rotateImageIfRequired(context: android.content.Context, img: android.graphics.Bitmap, selectedImage: Uri): android.graphics.Bitmap {
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

    private fun saveImageToInternalStorage(context: android.content.Context, uri: Uri): String? {
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
        val context = getApplication<android.app.Application>().applicationContext
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "watering_channel"

        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Icona di sistema sicura
            .setContentTitle("Test Notifica 🌿")
            .setContentText("Il canale ora è attivo! Le notifiche funzionano.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(99, notification)
    }

    fun addPlant(context: android.content.Context, name: String, waterDays: Int, fertDays: Int, lastW: Long, lastF: Long, imageUriString: String?) {
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
            val currentPlants = dao.getAllPlants().first()
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

    fun updatePlant(context: android.content.Context, plant: Plant, newImageUriString: String?) {
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
    fun exportDataToJSON(context: android.content.Context) {
        viewModelScope.launch {
            // 1. Prendi tutte le piante
            val plants = dao.getAllPlants().first()

            // 2. Crea il contenuto JSON (molto semplificato)
            val jsonString = StringBuilder().append("[\n")
            plants.forEachIndexed { index, plant ->
                // Modifica la parte dentro il ciclo forEach:
                jsonString.append("  {\n")
                jsonString.append("    \"name\": \"${plant.name}\",\n")
                jsonString.append("    \"days\": ${plant.wateringDays},\n")
                jsonString.append("    \"lastWatered\": ${plant.lastWatered},\n")
                jsonString.append("    \"fertDays\": ${plant.fertilizationDays},\n") // AGGIUNTO
                jsonString.append("    \"lastFert\": ${plant.lastFertilized}\n")    // AGGIUNTO
                jsonString.append("  }")
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
    fun importDataFromJSON(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                android.util.Log.d("PLANT_DEBUG", "Contenuto file: $jsonString") // Vedi questo nel Logcat

                if (jsonString.isNullOrBlank()) return@launch

                val plantsList = mutableListOf<Plant>()

                // Regex più flessibile: gestisce spazi, a capo e campi extra
// Versione super-semplice per catturare tutto ciò che somiglia a una pianta
                val regex = Regex("""name":\s*"(.*?)".*?days":\s*(\d+).*?lastWatered":\s*(\d+).*?fertDays":\s*(\d+).*?lastFert":\s*(\d+)""", RegexOption.DOT_MATCHES_ALL)
                val matches = regex.findAll(jsonString).toList()
                android.util.Log.d("PLANT_DEBUG", "Piante trovate nella Regex: ${matches.size}")

                matches.forEach { match ->
                    plantsList.add(Plant(
                        name = match.groupValues[1],
                        wateringDays = match.groupValues[2].toInt(),
                        lastWatered = match.groupValues[3].toLong(),
                        imagePath = null,
                        fertilizationDays = match.groupValues[4].toInt(), // LEGGE DAL FILE
                        lastFertilized = match.groupValues[5].toLong()    // LEGGE DAL FILE
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
            // Usa getAllPlants() (con le parentesi) se è così che l'hai chiamata nel DAO
            val plants = dao.getAllPlants().first()
            val plant = plants.find { it.id == plantId }

            plant?.let {
                val updatedPlant = it.copy(lastFertilized = System.currentTimeMillis())
                dao.insertPlant(updatedPlant)
                // Opzionale: un log per conferma
                android.util.Log.d("PLANT_DEBUG", "Pianta ${it.name} concimata!")
            }
        }
    }
    fun autoBackup(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val plants = dao.getAllPlants().first()
                if (plants.isEmpty()) return@launch

                val jsonString = StringBuilder("[\n")
                plants.forEachIndexed { index, plant ->
                    jsonString.append("  {\n")
                    jsonString.append("    \"name\": \"${plant.name}\",\n")
                    jsonString.append("    \"days\": ${plant.wateringDays},\n")
                    jsonString.append("    \"lastWatered\": ${plant.lastWatered},\n")
                    jsonString.append("    \"fertDays\": ${plant.fertilizationDays},\n")
                    jsonString.append("    \"lastFert\": ${plant.lastFertilized}\n")
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
    // Variabile per memorizzare l'ultimo stato prima della modifica
    private var lastPlantState: Plant? = null

    fun waterPlantWithUndo(plantId: Int) {
        viewModelScope.launch {
            // Dato che allPlants è un Flow, dobbiamo estrarre la lista attuale
            val currentPlants = dao.getAllPlants().first()
            val plant = currentPlants.find { it.id == plantId }

            if (plant != null) {
                lastPlantState = plant.copy() // Salviamo una copia fedele del passato
                waterPlant(plantId) // Esegue l'aggiornamento
            }
        }
    }

    fun fertilizePlantWithUndo(plantId: Int) {
        viewModelScope.launch {
            val currentPlants = dao.getAllPlants().first()
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