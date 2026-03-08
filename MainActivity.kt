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
import android.location.Geocoder
import android.net.Uri
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import coil.compose.AsyncImage
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale
import com.google.android.gms.maps.model.BitmapDescriptorFactory

data class SorunModel(
    val id: String,
    val lat: Double,
    val lng: Double,
    val aciklama: String,
    val adres: String,
    val fotografUrl: String,
    val cozuldu: Boolean
)

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

    // Fotoğraf Seçimi
    var secilenFotografUri by remember { mutableStateOf<Uri?>(null) }

    val coroutineScope = rememberCoroutineScope()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> secilenFotografUri = uri }
    )

    // Menü Açık/Kapalı Kontrolleri
    var ilceMenuAcik by remember { mutableStateOf(false) }
    var mahalleMenuAcik by remember { mutableStateOf(false) }
    var sokakMenuAcik by remember { mutableStateOf(false) }

    // --- JSON'dan Okunan Veriler ---
    var mahallelerMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var sokaklarMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    // --- Haritadaki Sorunlar ---
    var sorunlarListesi by remember { mutableStateOf<List<SorunModel>>(emptyList()) }

    val konakMerkez = LatLng(38.4192, 27.1287)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(konakMerkez, 12f)
    }

    // --- SORUN KAYDETME İŞLEVİ (FIREBASE) ---
    fun sorunKaydet() {
        if (secilenIlce == "İlçe Seçin" || secilenMahalle == "Mahalle Seçin" || secilenSokak == "Sokak Seçin" || yorum.isBlank()) {
            Toast.makeText(context, "Lütfen tüm alanları doldurun.", Toast.LENGTH_SHORT).show()
            return
        }

        // Aslında burada kullanıcının konumu alınabilir veya adres Geocoder ile çevrilip LatLng elde edilebilir
        // Biz adres stringi olarak ekliyoruz
        val adres = "İzmir, $secilenIlce, $secilenMahalle, $secilenSokak"

        coroutineScope.launch(Dispatchers.IO) {
            try {
                var photoUrl = ""
                // 1. Eğer fotoğraf seçildiyse Firebase Storage'a yükle
                secilenFotografUri?.let { uri ->
                    val storageRef = FirebaseStorage.getInstance().reference
                    val photoRef = storageRef.child("sorunlar/${System.currentTimeMillis()}.jpg")
                    // uri.lastPathSegment yerine timestamp verildi
                    photoRef.putFile(uri).await()
                    val downloadUrl = photoRef.downloadUrl.await()
                    photoUrl = downloadUrl.toString()
                }

                // 2. Firestore'a veriyi kaydet
                val geocoder = Geocoder(context, Locale.getDefault())
                val addressList = geocoder.getFromLocationName(adres, 1)
                var lat = 38.4192
                var lng = 27.1287
                if (!addressList.isNullOrEmpty()) {
                    lat = addressList[0].latitude
                    lng = addressList[0].longitude
                }

                val sorunVerisi = hashMapOf(
                    "adres" to adres,
                    "ilce" to secilenIlce,
                    "mahalle" to secilenMahalle,
                    "sokak" to secilenSokak,
                    "aciklama" to yorum,
                    "fotografUrl" to photoUrl,
                    "cozuldu" to false,
                    "lat" to lat,
                    "lng" to lng,
                    "timestamp" to System.currentTimeMillis()
                )

                // Firestore'a kaydet (Simülasyon/Gerçek)
                val db = FirebaseFirestore.getInstance()
                db.collection("sorunlar").add(sorunVerisi)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sorun başarıyla bildirildi!", Toast.LENGTH_LONG).show()
                    showSheet = false
                    // Reset fields
                    yorum = ""
                    secilenFotografUri = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Kamera Yakınlaşma İşlevi
    fun yaklas(adres: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addressList = geocoder.getFromLocationName(adres, 1)
                if (!addressList.isNullOrEmpty()) {
                    val location = addressList[0]
                    val latLng = LatLng(location.latitude, location.longitude)
                    withContext(Dispatchers.Main) {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            // Firebase'den Verileri Çek ve Dinle
            val db = FirebaseFirestore.getInstance()
            db.collection("sorunlar").addSnapshotListener { snapshot, e ->
                if (e != null) {
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val liste = mutableListOf<SorunModel>()
                    for (doc in snapshot.documents) {
                        liste.add(
                            SorunModel(
                                id = doc.id,
                                lat = doc.getDouble("lat") ?: 0.0,
                                lng = doc.getDouble("lng") ?: 0.0,
                                aciklama = doc.getString("aciklama") ?: "",
                                adres = doc.getString("adres") ?: "",
                                fotografUrl = doc.getString("fotografUrl") ?: "",
                                cozuldu = doc.getBoolean("cozuldu") ?: false
                            )
                        )
                    }
                    sorunlarListesi = liste
                }
            }

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
                Pair<Map<String, List<String>>, Map<String, List<String>>>(tempMahallelerMap, tempSokaklarMap)
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

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState
        ) {
            // Haritada Sorunları Göster
            sorunlarListesi.forEach { sorun ->
                val konum = LatLng(sorun.lat, sorun.lng)

                // Karakter/Renk: Çözüldü ise Yeşil (başarı), Çözülmedi ise Kırmızı (sorun)
                val iconColor = if (sorun.cozuldu) BitmapDescriptorFactory.HUE_GREEN else BitmapDescriptorFactory.HUE_RED
                val durumYazisi = if (sorun.cozuldu) "✅ Çözüldü" else "❌ Bekliyor"

                Marker(
                    state = MarkerState(position = konum),
                    title = "Sorun: ${sorun.aciklama.take(20)}...",
                    snippet = "Durum: $durumYazisi\nAdres: ${sorun.adres}",
                    icon = BitmapDescriptorFactory.defaultMarker(iconColor)
                )
            }
        }

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
                                    yaklas("İzmir, $ilce")
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
                                    yaklas("İzmir, $secilenIlce, $mahalle")
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
                                    yaklas("İzmir, $secilenIlce, $secilenMahalle, $sokak")
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
                        onClick = {
                            photoPickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) {
                        Text("Fotoğraf Ekle (Sadece 1)")
                    }

                    secilenFotografUri?.let { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = "Seçilen Fotoğraf",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(vertical = 8.dp)
                        )
                    }

                    Button(
                        onClick = { sorunKaydet() },
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