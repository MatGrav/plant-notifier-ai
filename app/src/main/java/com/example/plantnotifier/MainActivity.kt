package com.example.plantnotifier

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.example.plantnotifier.ui.theme.PlantNotifierTheme
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: PlantViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup Notifiche
        val channelId = "watering_channel"
        val channelName = "Cura Piante"
        val importance = android.app.NotificationManager.IMPORTANCE_HIGH
        val channel = android.app.NotificationChannel(channelId, channelName, importance).apply {
            description = "Notifiche per acqua e concime"
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.createNotificationChannel(channel)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        val wateringRequest = PeriodicWorkRequestBuilder<WateringWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("WateringCheck", androidx.work.ExistingPeriodicWorkPolicy.KEEP, wateringRequest)

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            PlantNotifierTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var editingPlant by remember { mutableStateOf<Plant?>(null) }
                    var selectedTab by remember { mutableIntStateOf(0) }
                    var showDialog by remember { mutableStateOf(false) }

                    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri?.let { viewModel.importDataFromJSON(context, it) }
                    }

                    val activePlants by viewModel.allPlants.collectAsState(initial = emptyList())
                    val archivedPlants by viewModel.archivedPlants.collectAsState(initial = emptyList())

                    Scaffold(
                        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                        topBar = {
                            Column {
                                TopAppBar(
                                    title = { Text("Plant Notifier 🌿") },
                                    actions = {
                                        IconButton(onClick = { viewModel.exportDataToJSON(context) }) { Icon(Icons.Default.Share, "Esporta") }
                                        IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Icon(Icons.Default.Refresh, "Importa") }
                                        IconButton(onClick = { viewModel.runManualCheckAndTest() }) { Icon(Icons.Default.Notifications, "Test") }
                                    }
                                )
                                TabRow(selectedTabIndex = selectedTab) {
                                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) { Text("Attive", modifier = Modifier.padding(16.dp)) }
                                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) { Text("Archivio", modifier = Modifier.padding(16.dp)) }
                                }
                            }
                        },
                        floatingActionButton = {
                            FloatingActionButton(onClick = { showDialog = true }) { Icon(Icons.Default.Add, "Aggiungi") }
                        }
                    ) { innerPadding ->
                        val currentPlants = if (selectedTab == 0) activePlants else archivedPlants

                        PlantListScreen(
                            plants = currentPlants,
                            modifier = Modifier.padding(innerPadding),
                            isArchive = selectedTab == 1,
                            viewModel = viewModel,
                            onPlantClick = { editingPlant = it },
                            onWaterClick = { plantId ->
                                viewModel.waterPlantWithUndo(plantId)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar("Hai annaffiato! 💧", "ANNULLA", duration = SnackbarDuration.Short)
                                    if (result == SnackbarResult.ActionPerformed) viewModel.undoLastAction()
                                }
                            },
                            onDeleteClick = { plant ->
                                viewModel.deletePlant(plant)
                                scope.launch { snackbarHostState.showSnackbar("${plant.name} eliminata") }
                            },
                            onArchiveClick = { plant ->
                                viewModel.archivePlant(plant.id)
                                scope.launch { snackbarHostState.showSnackbar("${plant.name} archiviata") }
                            },
                            onUnarchiveClick = { plant ->
                                viewModel.unarchivePlant(plant.id)
                                scope.launch { snackbarHostState.showSnackbar("${plant.name} ripristinata") }
                            },
                            onFertilizeClick = { plantId ->
                                viewModel.fertilizePlantWithUndo(plantId)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar("Hai concimato! ✨", "ANNULLA", duration = SnackbarDuration.Short)
                                    if (result == SnackbarResult.ActionPerformed) viewModel.undoLastAction()
                                }
                            }
                        )

                        editingPlant?.let { plant ->
                            EditPlantScreen(
                                plant = plant,
                                onPlantUpdated = { updatedPlant ->
                                    viewModel.updatePlant(context, updatedPlant, updatedPlant.imagePath)
                                    editingPlant = null
                                },
                                onDismiss = { editingPlant = null }
                            )
                        }

                        if (showDialog) {
                            AddPlantScreen(
                                onPlantAdded = { n, w, f, img, lw, lf ->
                                    viewModel.addPlant(context, n, w, f, lw, lf, img)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantListScreen(
    plants: List<Plant>,
    modifier: Modifier = Modifier,
    isArchive: Boolean = false,
    viewModel: PlantViewModel,
    onWaterClick: (Int) -> Unit,
    onDeleteClick: (Plant) -> Unit,
    onArchiveClick: (Plant) -> Unit,
    onUnarchiveClick: (Plant) -> Unit,
    onFertilizeClick: (Int) -> Unit,
    onPlantClick: (Plant) -> Unit
) {
    var plantToDelete by remember { mutableStateOf<Plant?>(null) }
    var plantToArchive by remember { mutableStateOf<Plant?>(null) }
    var plantToPostpone by remember { mutableStateOf<Plant?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (plants.isEmpty()) {
            Text(if (isArchive) "Archivio vuoto" else "Lista vuota. Clicca +", modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(plants, key = { it.id }) { plant ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            when (value) {
                                SwipeToDismissBoxValue.EndToStart -> {
                                    if (isArchive) plantToDelete = plant else plantToArchive = plant
                                    false
                                }
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    if (isArchive) onUnarchiveClick(plant) else plantToPostpone = plant
                                    false
                                }
                                else -> false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val direction = dismissState.dismissDirection
                            val color = when (direction) {
                                SwipeToDismissBoxValue.EndToStart -> if (isArchive) Color(0xFFFF5252) else Color(0xFFFFB74D)
                                SwipeToDismissBoxValue.StartToEnd -> Color(0xFF4CAF50)
                                else -> Color.Transparent
                            }
                            Box(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(color)) {
                                if (direction == SwipeToDismissBoxValue.EndToStart) {
                                    Icon(if (isArchive) Icons.Default.Delete else Icons.Default.Archive, null, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), tint = Color.White)
                                } else if (direction == SwipeToDismissBoxValue.StartToEnd) {
                                    Icon(if (isArchive) Icons.Default.Unarchive else Icons.Default.Schedule, null, modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp), tint = Color.White)
                                }
                            }
                        }
                    ) {
                        PlantItem(plant, isArchive, onWaterClick, onFertilizeClick, onPlantClick)
                    }
                }
            }
        }

        if (plantToArchive != null) {
            AlertDialog(
                onDismissRequest = { plantToArchive = null },
                title = { Text("Archivia") },
                text = { Text("Vuoi archiviare ${plantToArchive?.name}?") },
                confirmButton = { Button(onClick = { plantToArchive?.let { onArchiveClick(it) }; plantToArchive = null }) { Text("Archivia") } },
                dismissButton = { TextButton(onClick = { plantToArchive = null }) { Text("Annulla") } }
            )
        }

        if (plantToPostpone != null) {
            var days by remember { mutableFloatStateOf(3f) }
            AlertDialog(
                onDismissRequest = { plantToPostpone = null },
                title = { Text("Posticipa") },
                text = {
                    Column {
                        Text("${days.toInt()} giorni")
                        Slider(value = days, onValueChange = { days = it }, valueRange = 1f..14f, steps = 12)
                    }
                },
                confirmButton = { Button(onClick = { plantToPostpone?.let { viewModel.postponeWatering(it.id, days.toInt()) }; plantToPostpone = null }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { plantToPostpone = null }) { Text("Annulla") } }
            )
        }

        if (plantToDelete != null) {
            AlertDialog(
                onDismissRequest = { plantToDelete = null },
                title = { Text("Elimina") },
                text = { Text("Eliminare definitivamente ${plantToDelete?.name}?") },
                confirmButton = { Button(onClick = { plantToDelete?.let { onDeleteClick(it) }; plantToDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Elimina") } },
                dismissButton = { TextButton(onClick = { plantToDelete = null }) { Text("Annulla") } }
            )
        }
    }
}

@Composable
fun PlantItem(plant: Plant, isArchived: Boolean, onWaterClick: (Int) -> Unit, onFertilizeClick: (Int) -> Unit, onPlantClick: (Plant) -> Unit) {
    val dayMillis = 24 * 60 * 60 * 1000L
    val nextWater = plant.lastWatered + (plant.wateringDays * dayMillis)
    val waterDiff = ((nextWater - System.currentTimeMillis()) / dayMillis).toInt()
    
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).alpha(if (isArchived) 0.6f else 1f).clickable { onPlantClick(plant) }) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (plant.imagePath != null) {
                AsyncImage(model = plant.imagePath, contentDescription = null, modifier = Modifier.size(60.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(plant.name, style = MaterialTheme.typography.titleLarge)
                if (!isArchived) {
                    val color = if (waterDiff <= 0) Color.Red else Color(0xFF4CAF50)
                    Text(if (waterDiff < 0) "RITARDO!" else if (waterDiff == 0) "Oggi" else "Tra $waterDiff gg", color = color, fontWeight = FontWeight.Bold)
                } else Text("In archivio", color = Color.Gray)
            }
            if (!isArchived) {
                IconButton(onClick = { onWaterClick(plant.id) }) { Icon(Icons.Default.WaterDrop, null, tint = Color(0xFF2196F3)) }
                IconButton(onClick = { onFertilizeClick(plant.id) }) { Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF9C27B0)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlantScreen(onPlantAdded: (String, Int, Int, String?, Long, Long) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var water by remember { mutableStateOf("7") }
    var fert by remember { mutableStateOf("30") }
    var imagePath by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> 
        if (!success) imagePath = null 
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> 
        imagePath = uri?.toString() 
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuova Pianta") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Button(onClick = {
                    val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    val file = File(storageDir, "PLANT_${System.currentTimeMillis()}.jpg")
                    imagePath = file.absolutePath
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    cameraLauncher.launch(uri)
                }, modifier = Modifier.fillMaxWidth()) { Text("Foto") }
                Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Galleria") }
                TextField(value = name, onValueChange = { name = it }, label = { Text("Nome") })
                TextField(value = water, onValueChange = { water = it }, label = { Text("Giorni acqua") })
                TextField(value = fert, onValueChange = { fert = it }, label = { Text("Giorni concime") })
            }
        },
        confirmButton = { Button(onClick = { onPlantAdded(name, water.toIntOrNull() ?: 7, fert.toIntOrNull() ?: 30, imagePath, System.currentTimeMillis(), System.currentTimeMillis()) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPlantScreen(plant: Plant, onPlantUpdated: (Plant) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(plant.name) }
    var water by remember { mutableStateOf(plant.wateringDays.toString()) }
    var fert by remember { mutableStateOf(plant.fertilizationDays.toString()) }
    var imagePath by remember { mutableStateOf(plant.imagePath) }
    var isZoomed by remember { mutableStateOf(false) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> 
        imagePath = uri?.toString() 
    }

    if (isZoomed && imagePath != null) {
        Dialog(onDismissRequest = { isZoomed = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { isZoomed = false }, contentAlignment = Alignment.Center) {
                AsyncImage(model = imagePath, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (imagePath != null) {
                    AsyncImage(model = imagePath, contentDescription = null, modifier = Modifier.height(150.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { isZoomed = true }, contentScale = ContentScale.Crop)
                }
                Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Cambia Foto") }
                TextField(value = name, onValueChange = { name = it }, label = { Text("Nome") })
                TextField(value = water, onValueChange = { water = it }, label = { Text("Giorni acqua") })
                TextField(value = fert, onValueChange = { fert = it }, label = { Text("Giorni concime") })
            }
        },
        confirmButton = { Button(onClick = { onPlantUpdated(plant.copy(name = name, wateringDays = water.toIntOrNull() ?: plant.wateringDays, fertilizationDays = fert.toIntOrNull() ?: plant.fertilizationDays, imagePath = imagePath)) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
