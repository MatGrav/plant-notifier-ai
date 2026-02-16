package com.example.plantnotifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.plantnotifier.ui.theme.PlantNotifierTheme
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import coil.compose.AsyncImage // Serve per mostrare la foto nella lista
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Check // Import specifico per la spunta
import androidx.compose.material.icons.filled.Add   // Quella che usavi prima
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import java.util.*

import androidx.compose.foundation.background

import androidx.compose.ui.draw.clip
// Se non l'hai già fatto, aggiungi questo per far funzionare le immagini nella modifica
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.Schedule


fun createImageFile(context: android.content.Context): File {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    return File.createTempFile("PLANT_${timeStamp}_", ".jpg", storageDir)
}

class MainActivity : ComponentActivity() {

    private val viewModel: PlantViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class) // 1. Rimuove l'avviso "Experimental"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inserisci questo in onCreate
        val channelId: String = "watering_channel"
        val channelName: String = "Cura Piante"
        val importance: Int = android.app.NotificationManager.IMPORTANCE_HIGH

        val channel = android.app.NotificationChannel(channelId, channelName, importance).apply {
            description = "Notifiche per acqua e concime"
        }

        val notificationManager =
            getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.createNotificationChannel(channel)

        // Permessi per notifiche
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        }

        // Configurazione WorkManager
        val wateringRequest = PeriodicWorkRequestBuilder<WateringWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "WateringCheck",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            wateringRequest
        )

        setContent {
            val snackbarHostState =
                remember { SnackbarHostState() } // Gestisce la comparsa della barra
            val scope =
                rememberCoroutineScope() // Permette di lanciare la snackbar in modo asincrono

            var showDialog by remember { mutableStateOf(false) }
            val context =
                androidx.compose.ui.platform.LocalContext.current // 2. Context definito all'inizio

            PlantNotifierTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background // Questo userà il nero
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var editingPlant by remember { mutableStateOf<Plant?>(null) }

                    // 1. Il Launcher va dentro setContent!
                    val importLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        uri?.let { viewModel.importDataFromJSON(context, it) }
                    }

                    var showDialog by remember { mutableStateOf(false) }
                    val plants by viewModel.allPlants.collectAsState(initial = emptyList())

                    Scaffold(
                        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },

                        topBar = {
                            TopAppBar(
                                title = { Text("Plant Notifier 🌿") },
                                actions = {
                                    // TASTO ESPORTA
                                    IconButton(onClick = { viewModel.exportDataToJSON(context) }) {
                                        Icon(Icons.Default.Share, "Esporta")
                                    }
                                    // 2. TASTO RIPRISTINA (Chiama il launcher)
                                    IconButton(onClick = {
                                        importLauncher.launch(arrayOf("application/json"))
                                    }) {
                                        Icon(Icons.Default.Refresh, "Importa")
                                    }
                                    // TASTO TEST
                                    IconButton(onClick = { viewModel.testNotification() }) {
                                        Icon(Icons.Default.Notifications, "Test")
                                    }
                                }
                            )
                        },
                        floatingActionButton = {
                            FloatingActionButton(onClick = { showDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Aggiungi")
                            }
                        }
                    ) { innerPadding ->
                        val plants by viewModel.allPlants.collectAsState(initial = emptyList())

                        PlantListScreen(
                            plants = plants,
                            modifier = Modifier.padding(innerPadding),
                            onPlantClick = {
                                editingPlant = it
                            }, // Quando clicchi, "salvi" la pianta qui
                            onWaterClick = { plantId ->
                                viewModel.waterPlantWithUndo(plantId)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Hai annaffiato! 💧",
                                        actionLabel = "ANNULLA",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoLastAction()
                                    }
                                }
                            },
                            onDeleteClick = { plant ->
                                viewModel.deletePlant(plant)
                                android.widget.Toast.makeText(
                                    context,
                                    "${plant.name} eliminata",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            onFertilizeClick = { plantId ->
                                viewModel.fertilizePlantWithUndo(plantId)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Hai concimato! ✨",
                                        actionLabel = "ANNULLA",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoLastAction()
                                    }
                                }
                            }
                        )

                        editingPlant?.let { plant ->
                            EditPlantScreen(
                                plant = plant,
                                onPlantUpdated = { updatedPlant ->
                                    // Passiamo il contesto e il (possibile nuovo) percorso immagine
                                    viewModel.updatePlant(
                                        context = context,
                                        plant = updatedPlant,
                                        newImageUriString = updatedPlant.imagePath
                                    )
                                    editingPlant = null
                                },
                                onDismiss = { editingPlant = null }
                            )
                        }

                        if (showDialog) {
                            AddPlantScreen(
                                onPlantAdded = { n, w, f, img, lw, lf ->
                                    viewModel.addPlant(
                                        context = context, // <--- Passa il context qui
                                        name = n,
                                        waterDays = w,
                                        fertDays = f,
                                        lastW = lw,
                                        lastF = lf,
                                        imageUriString = img
                                    )
                                    showDialog = false
                                },
                                onDismiss = { showDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }

    fun createImageFile(context: android.content.Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("PLANT_${timeStamp}_", ".jpg", storageDir)
    }

    @OptIn(ExperimentalMaterial3Api::class) // <--- Aggiungi questa!
    @Composable
    fun PlantListScreen(
        plants: List<Plant>,
        modifier: Modifier = Modifier,
        onWaterClick: (Int) -> Unit,
        onDeleteClick: (Plant) -> Unit,
        onFertilizeClick: (Int) -> Unit,
        onPlantClick: (Plant) -> Unit
    ) {
        val snackbarHostState = remember { SnackbarHostState() } // Gestisce la comparsa della barra
        val scope = rememberCoroutineScope() // Permette di lanciare la snackbar in modo asincrono

        var plantToDelete by remember { mutableStateOf<Plant?>(null) }
        var plantToPostpone by remember { mutableStateOf<Plant?>(null) }

        Box(modifier = modifier.fillMaxSize()) {
            if (plants.isEmpty()) {
                Text("Lista vuota. Clicca +", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        plants,
                        key = { it.id }) { plant -> // Importante aggiungere la key per animazioni fluide
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                when (value) {
                                    SwipeToDismissBoxValue.EndToStart -> { // Swipe a Sinistra: ELIMINA
                                        plantToDelete = plant
                                        false
                                    }

                                    SwipeToDismissBoxValue.StartToEnd -> { // Destra: Posticipa
                                        plantToPostpone = plant // <--- Salviamo la pianta per il dialog
                                        false
                                    }

                                    else -> false
                                }
                            },
                            positionalThreshold = { it * 0.4f }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = true,
                            backgroundContent = {
                                val direction = dismissState.dismissDirection
                                val isSwipingLeft = direction == SwipeToDismissBoxValue.EndToStart
                                val isSwipingRight = direction == SwipeToDismissBoxValue.StartToEnd

                                val backgroundColor = when (direction) {
                                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFFF5252) // Rosso per eliminare
                                    SwipeToDismissBoxValue.StartToEnd -> Color(0xFF4CAF50) // Verde per posticipare
                                    else -> Color.Transparent
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 8.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(backgroundColor),
                                    contentAlignment = if (isSwipingLeft) Alignment.CenterEnd else Alignment.CenterStart
                                ) {
                                    if (isSwipingLeft) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Elimina",
                                            tint = Color.White,
                                            modifier = Modifier.padding(end = 24.dp)
                                        )
                                    } else if (isSwipingRight) {
                                        // Icona Orologio per il posticipo
                                        Icon(
                                            Icons.Default.Schedule, // Richiede import androidx.compose.material.icons.filled.Schedule
                                            contentDescription = "Posticipa",
                                            tint = Color.White,
                                            modifier = Modifier.padding(start = 24.dp)
                                        )
                                    }
                                }
                            }
                        ) {
                            // La tua Card
                            PlantItem(
                                plant = plant,
                                onWaterClick = onWaterClick,
                                onFertilizeClick = onFertilizeClick,
                                onPlantClick = onPlantClick,
                                onDeleteClick = { plantToDelete = it }
                            )
                        }
                    }
                }
            }

            // DIALOG DI CONFERMA PER POSTICIPO (+3 giorni)
            if (plantToPostpone != null) {
                // Stato locale per i giorni scelti (da 1 a 14, default 3)
                var daysToPostpone by remember { mutableFloatStateOf(3f) }

                AlertDialog(
                    onDismissRequest = { plantToPostpone = null },
                    title = { Text("Posticipa di quanti giorni?") },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${daysToPostpone.toInt()} giorni",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Slider(
                                value = daysToPostpone,
                                onValueChange = { daysToPostpone = it },
                                valueRange = 1f..14f, // Puoi scegliere da 1 a 14 giorni
                                steps = 12,           // Crea i "pallini" per ogni giorno intermedio
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Text(
                                "Sposta la scadenza per ${plantToPostpone?.name}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                plantToPostpone?.let {
                                    viewModel.postponeWatering(it.id, days = daysToPostpone.toInt())
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Posticipata di ${daysToPostpone.toInt()} giorni ⏳")
                                    }
                                }
                                plantToPostpone = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) { Text("Conferma") }
                    },
                    dismissButton = {
                        TextButton(onClick = { plantToPostpone = null }) { Text("Annulla") }
                    }
                )
            }

            // DIALOG DI CONFERMA (Resta qui fuori)
            if (plantToDelete != null) {
                AlertDialog(
                    onDismissRequest = { plantToDelete = null },
                    title = { Text("Elimina Pianta") },
                    text = { Text("Sei sicuro di voler eliminare ${plantToDelete?.name}?") },
                    confirmButton = {
                        Button(
                            onClick = {
                                plantToDelete?.let { onDeleteClick(it) }
                                plantToDelete = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) { Text("Elimina", color = Color.White) }
                    },
                    dismissButton = {
                        TextButton(onClick = { plantToDelete = null }) { Text("Annulla") }
                    }
                )
            }
        }
    }

    // DEFINISCI PLANTITEM QUI FUORI, COME FUNZIONE SEPARATA
    @Composable
    fun PlantItem(
        plant: Plant,
        onWaterClick: (Int) -> Unit,
        onFertilizeClick: (Int) -> Unit,
        onPlantClick: (Plant) -> Unit,
        onDeleteClick: (Plant) -> Unit
    ) {
        val currentTime = System.currentTimeMillis()
        val dayMillis = 24 * 60 * 60 * 1000L
        val dateFormatter = SimpleDateFormat("EEE d MMM", Locale.getDefault())

        // --- LOGICA ACQUA ---
        val nextWateringMillis = plant.lastWatered + (plant.wateringDays * dayMillis)
        val waterDaysLeft = ((nextWateringMillis - currentTime) / dayMillis).toInt()
        val waterDateFormatted = dateFormatter.format(Date(nextWateringMillis))

        val waterStatusText = when {
            waterDaysLeft < 0 -> "IN RITARDO! ⚠️"
            waterDaysLeft == 0 -> "Bagnare oggi 💧"
            else -> "Prossima: $waterDateFormatted"
        }
        val waterColor = if (waterDaysLeft <= 0) Color(0xFFFF5252) else Color(0xFF81C784)

        // --- LOGICA CONCIME ---
        val nextFertMillis = plant.lastFertilized + (plant.fertilizationDays * dayMillis)
        val fertDaysLeft = ((nextFertMillis - currentTime) / dayMillis).toInt()
        val fertDateFormatted = dateFormatter.format(Date(nextFertMillis))

        val fertStatusText = when {
            fertDaysLeft < 0 -> "CONCIME SCADUTO! 🧪"
            fertDaysLeft == 0 -> "Concime oggi"
            else -> "Concime: $fertDateFormatted"
        }
        val fertColor = if (fertDaysLeft <= 0) Color(0xFF9C27B0) else Color.Gray

        // --- DISEGNO DELLA CARD (Mancava questo pezzo!) ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clickable { onPlantClick(plant) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (plant.imagePath != null) {
                    AsyncImage(
                        model = plant.imagePath,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }

                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(plant.name, style = MaterialTheme.typography.titleLarge)
                    Text(text = waterStatusText, color = waterColor, fontWeight = FontWeight.Bold)
                    Text(
                        text = fertStatusText,
                        color = fertColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                IconButton(onClick = { onWaterClick(plant.id) }) {
                    Icon(
                        Icons.Default.WaterDrop,
                        contentDescription = "Bagna",
                        tint = Color(0xFF2196F3)
                    )
                }
                IconButton(onClick = { onFertilizeClick(plant.id) }) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Concima",
                        tint = Color(0xFF9C27B0)
                    )
                }
                //IconButton(onClick = { onDeleteClick(plant) }) {
                //    Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = Color.Gray)
                // }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AddPlantScreen(
        onPlantAdded: (String, Int, Int, String?, Long, Long) -> Unit, // Aggiunti gli ultimi due Long
        onDismiss: () -> Unit
    ) {
        var name by remember { mutableStateOf("") }
        var waterDays by remember { mutableStateOf("7") }
        var fertDays by remember { mutableStateOf("30") }
        var imagePath by remember { mutableStateOf<String?>(null) }

        // STATI PER LE DATE
        var selectedWateringDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var selectedFertilizedDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var showDatePicker by remember { mutableStateOf(false) }
        var pickingFor by remember { mutableStateOf("water") }

        val datePickerState =
            rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val context = androidx.compose.ui.platform.LocalContext.current

        // Launcher (Camera e Galleria) rimangono uguali...
        val cameraLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (!success) imagePath = null
            }
        val galleryLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let { imagePath = it.toString() }
            }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nuova Pianta") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // ... (Parte anteprima foto e pulsanti camera/galleria uguale) ...

                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome pianta") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = waterDays,
                        onValueChange = { waterDays = it },
                        label = { Text("Giorni acqua") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = fertDays,
                        onValueChange = { fertDays = it },
                        label = { Text("Giorni concime") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Date ultime cure (opzionale):",
                        style = MaterialTheme.typography.labelMedium
                    )

                    // Pulsante Data Acqua
                    OutlinedButton(
                        onClick = { pickingFor = "water"; showDatePicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.WaterDrop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Acqua: ${formatter.format(Date(selectedWateringDate))}")
                    }

                    // Pulsante Data Concime
                    OutlinedButton(
                        onClick = { pickingFor = "fert"; showDatePicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Concime: ${formatter.format(Date(selectedFertilizedDate))}")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        onPlantAdded(
                            name,
                            waterDays.toIntOrNull() ?: 7,
                            fertDays.toIntOrNull() ?: 30,
                            imagePath,
                            selectedWateringDate,
                            selectedFertilizedDate
                        )
                    }
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
        )

        if (showDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val date = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
                        if (pickingFor == "water") selectedWateringDate = date
                        else selectedFertilizedDate = date
                        showDatePicker = false
                    }) { Text("OK") }
                }
            ) { DatePicker(state = datePickerState) }
        }
    }




    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun EditPlantScreen(
        plant: Plant,
        onPlantUpdated: (Plant) -> Unit,
        onDismiss: () -> Unit
    ) {
        var name by remember { mutableStateOf(plant.name) }
        var waterDays by remember { mutableStateOf(plant.wateringDays.toString()) }
        var fertDays by remember { mutableStateOf(plant.fertilizationDays.toString()) }
        var imagePath by remember { mutableStateOf(plant.imagePath) }

        // STATI PER LE DATE
        var selectedWateringDate by remember { mutableLongStateOf(plant.lastWatered) }
        var selectedFertilizedDate by remember { mutableLongStateOf(plant.lastFertilized) }
        var showDatePicker by remember { mutableStateOf(false) }
        var pickingFor by remember { mutableStateOf("water") }

        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = plant.lastWatered)
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val context = androidx.compose.ui.platform.LocalContext.current

        // Launchers per Foto
        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            // Se lo scatto ha successo, imagePath contiene già il percorso corretto impostato al click
        }
        val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { imagePath = it.toString() }
        }
        var isImageZoomed by remember { mutableStateOf(false) } // Stato per il "tutto schermo"
        // Se l'immagine è zoomata, mostriamo un overlay a tutto schermo
        if (isImageZoomed && imagePath != null) {
            Dialog(
                onDismissRequest = { isImageZoomed = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false // Questo permette all'immagine di espandersi di più
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f)) // Sfondo scuro per far risaltare la pianta
                        .clickable { isImageZoomed = false },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = imagePath,
                        contentDescription = "Full Screen View",
                        modifier = Modifier.fillMaxWidth(0.95f), // Leggero margine dai bordi
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Modifica ${plant.name}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                    // --- 1. ANTEPRIMA FOTO ---
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp) // L'abbiamo alzata un po' per vederla meglio
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.1f))
                            .clickable { isImageZoomed = true }, // <--- CLICCA QUI PER INGRANDIRE
                        contentAlignment = Alignment.Center
                    ) {
                        if (imagePath != null) {
                            AsyncImage(
                                model = imagePath,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop // "Crop" riempie il box, rendendo tutto più pulito
                            )
                            // Un piccolo suggerimento visivo per l'utente
                            Icon(
                                Icons.Default.Refresh, // O Icons.Default.ZoomIn se l'hai importata
                                contentDescription = null,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        } else {
                            Text("Nessuna foto", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // --- 2. PULSANTI CAMBIA FOTO ---
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = {
                            val file = createImageFile(context)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            imagePath = file.absolutePath
                            cameraLauncher.launch(uri)
                        }) { Text("📸 Foto") }

                        Button(onClick = { galleryLauncher.launch("image/*") }) { Text("🖼️ Galleria") }
                    }

                    // --- 3. CAMPI TESTO (Nome e Frequenze) ---
                    TextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(value = waterDays, onValueChange = { waterDays = it }, label = { Text("Giorni Acqua") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(value = fertDays, onValueChange = { fertDays = it }, label = { Text("Giorni Concime") }, modifier = Modifier.fillMaxWidth())

                    // --- 4. SELEZIONE DATE ---
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Modifica date ultime cure:", style = MaterialTheme.typography.labelMedium)

                    OutlinedButton(
                        onClick = { pickingFor = "water"; showDatePicker = true },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text("Acqua: ${formatter.format(Date(selectedWateringDate))}")
                    }

                    OutlinedButton(
                        onClick = { pickingFor = "fert"; showDatePicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Concime: ${formatter.format(Date(selectedFertilizedDate))}")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onPlantUpdated(plant.copy(
                        name = name,
                        wateringDays = waterDays.toIntOrNull() ?: plant.wateringDays,
                        fertilizationDays = fertDays.toIntOrNull() ?: plant.fertilizationDays,
                        imagePath = imagePath,
                        lastWatered = selectedWateringDate,
                        lastFertilized = selectedFertilizedDate
                    ))
                }) { Text("Aggiorna") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
        )

        // Logica del DatePicker
        if (showDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val date = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
                        if (pickingFor == "water") selectedWateringDate = date
                        else selectedFertilizedDate = date
                        showDatePicker = false
                    }) { Text("OK") }
                }
            ) { DatePicker(state = datePickerState) }
        }
    }
}

fun getDaysRemaining(lastWatered: Long, wateringDays: Int): Int {
    val nextWatering = lastWatered + (wateringDays.toLong() * 24 * 60 * 60 * 1000)
    val remainingMillis = nextWatering - System.currentTimeMillis()
    return (remainingMillis / (24 * 60 * 60 * 1000)).toInt()
}