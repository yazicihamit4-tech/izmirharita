package com.yazhamit.izmirharita

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.FirebaseApp
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IzmirHaritaEkrani()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IzmirHaritaEkrani() {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }

    // --- SEÇİM DURUMLARI (STATE) ---
    var secilenIlce by remember { mutableStateOf("İlçe Seçin") }
    var secilenMahalle by remember { mutableStateOf("Mahalle Seçin") }
    var secilenSokak by remember { mutableStateOf("Sokak Seçin") }
    var yorum by remember { mutableStateOf("") }

    // Menü Açık/Kapalı Kontrolleri
    var ilceMenuAcik by remember { mutableStateOf(false) }
    var mahalleMenuAcik by remember { mutableStateOf(false) }
    var sokakMenuAcik by remember { mutableStateOf(false) }

    // --- JSON'dan Okunan Veriler ---
    var mahallelerMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var sokaklarMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    LaunchedEffect(Unit) {
        try {
            val (parsedMahallelerMap, parsedSokaklarMap) = withContext(Dispatchers.IO) {
                val inputStream: InputStream = context.assets.open("izmir_rehberi.json")
                val size = inputStream.available()
                val buffer = ByteArray(size)
                inputStream.read(buffer)
                inputStream.close()
                val jsonString = String(buffer, Charsets.UTF_8)
                val jsonObject = JSONObject(jsonString)

                val tempMahallelerMap = mutableMapOf<String, List<String>>()
                if (jsonObject.has("mahalleler")) {
                    val mahallelerObj = jsonObject.getJSONObject("mahalleler")
                    mahallelerObj.keys().forEach { ilce ->
                        val mahallelerArray = mahallelerObj.getJSONArray(ilce)
                        val mahallelerList = mutableListOf<String>()
                        for (i in 0 until mahallelerArray.length()) {
                            mahallelerList.add(mahallelerArray.getString(i))
                        }
                        tempMahallelerMap[ilce] = mahallelerList
                    }
                }

                val tempSokaklarMap = mutableMapOf<String, List<String>>()
                if (jsonObject.has("sokaklar")) {
                    val sokaklarObj = jsonObject.getJSONObject("sokaklar")
                    sokaklarObj.keys().forEach { mahalle ->
                        val sokaklarArray = sokaklarObj.getJSONArray(mahalle)
                        val sokaklarList = mutableListOf<String>()
                        for (i in 0 until sokaklarArray.length()) {
                            sokaklarList.add(sokaklarArray.getString(i))
                        }
                        tempSokaklarMap[mahalle] = sokaklarList
                    }
                }
                Pair(tempMahallelerMap, tempSokaklarMap)
            }
            mahallelerMap = parsedMahallelerMap
            sokaklarMap = parsedSokaklarMap
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val guncelMahalleler = mahallelerMap[secilenIlce] ?: listOf("Önce İlçe Seçin")
    val guncelSokaklar = sokaklarMap[secilenMahalle] ?: listOf("Önce Mahalle Seçin")

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) showSheet = true
    }

    val konakMerkez = LatLng(38.4192, 27.1287)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(konakMerkez, 12f)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState
        )

        Button(
            onClick = {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA))
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).height(56.dp).fillMaxWidth(0.6f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0055))
        ) {
            Text("SORUN BİLDİR")
        }

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Konum Bilgileri", style = MaterialTheme.typography.titleLarge)

                    // 1. İLÇE SEÇİMİ
                    ExposedDropdownMenuBox(expanded = ilceMenuAcik, onExpandedChange = { ilceMenuAcik = !ilceMenuAcik }) {
                        OutlinedTextField(
                            value = secilenIlce, onValueChange = {}, readOnly = true, label = { Text("İlçe") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ilceMenuAcik) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = ilceMenuAcik, onDismissRequest = { ilceMenuAcik = false }) {
                            mahallelerMap.keys.forEach { ilce ->
                                DropdownMenuItem(text = { Text(ilce) }, onClick = {
                                    secilenIlce = ilce
                                    secilenMahalle = "Mahalle Seçin"
                                    secilenSokak = "Sokak Seçin"
                                    ilceMenuAcik = false
                                })
                            }
                        }
                    }

                    // 2. MAHALLE SEÇİMİ
                    ExposedDropdownMenuBox(expanded = mahalleMenuAcik, onExpandedChange = { mahalleMenuAcik = !mahalleMenuAcik }) {
                        OutlinedTextField(
                            value = secilenMahalle, onValueChange = {}, readOnly = true, label = { Text("Mahalle") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mahalleMenuAcik) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            enabled = secilenIlce != "İlçe Seçin"
                        )
                        ExposedDropdownMenu(expanded = mahalleMenuAcik, onDismissRequest = { mahalleMenuAcik = false }) {
                            guncelMahalleler.forEach { mahalle ->
                                DropdownMenuItem(text = { Text(mahalle) }, onClick = {
                                    secilenMahalle = mahalle
                                    secilenSokak = "Sokak Seçin"
                                    mahalleMenuAcik = false
                                })
                            }
                        }
                    }

                    // 3. SOKAK SEÇİMİ
                    ExposedDropdownMenuBox(expanded = sokakMenuAcik, onExpandedChange = { sokakMenuAcik = !sokakMenuAcik }) {
                        OutlinedTextField(
                            value = secilenSokak, onValueChange = {}, readOnly = true, label = { Text("Sokak") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sokakMenuAcik) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            enabled = secilenMahalle != "Mahalle Seçin"
                        )
                        ExposedDropdownMenu(expanded = sokakMenuAcik, onDismissRequest = { sokakMenuAcik = false }) {
                            guncelSokaklar.forEach { sokak ->
                                DropdownMenuItem(text = { Text(sokak) }, onClick = {
                                    secilenSokak = sokak
                                    sokakMenuAcik = false
                                })
                            }
                        }
                    }

                    OutlinedTextField(
                        value = yorum, onValueChange = { yorum = it },
                        label = { Text("Sorun Açıklaması") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3
                    )

                    Button(
                        onClick = { Toast.makeText(context, "Kaydedildi!", Toast.LENGTH_SHORT).show() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("BİLDİRİMİ GÖNDER")
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}