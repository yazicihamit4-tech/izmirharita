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
import com.google.firebase.auth.FirebaseAuth
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
import com.google.android.gms.maps.model.LatLngBounds
import android.provider.Settings
import com.google.android.gms.location.LocationServices
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

data class SorunModel(
    val id: String,
    val kullaniciId: String,
    val lat: Double,
    val lng: Double,
    val aciklama: String,
    val adres: String,
    val fotografUrl: String,
    val durum: Int, // 0: Bekliyor, 1: İletildi, 2: Çözüldü
    val silindi: Boolean,
    val adminMesaji: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            FirebaseApp.initializeApp(this)
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously()
                    .addOnCompleteListener(this) { task ->
                        if (!task.isSuccessful) {
                            Log.w("FirebaseAuth", "Anonim giriş başarısız", task.exception)
                        } else {
                            Log.d("FirebaseAuth", "Anonim giriş başarılı: ${auth.currentUser?.uid}")
                        }
                    }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            // Ferah, doğa dostu Karşıyaka Teması
            val karsiyakaColorScheme = lightColorScheme(
                primary = Color(0xFF2E7D32), // Koyu Yeşil (Doğa)
                onPrimary = Color.White,
                secondary = Color(0xFF0288D1), // Mavi (Körfez)
                onSecondary = Color.White,
                background = Color(0xFFF1F8E9), // Çok Açık Yeşil (Ferah Arka Plan)
                surface = Color.White
            )

            MaterialTheme(colorScheme = karsiyakaColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

    // Fotoğraf Seçimi (Galeri ve Kamera)
    var secilenFotografUri by remember { mutableStateOf<Uri?>(null) }
    var kameraIcinUri by remember { mutableStateOf<Uri?>(null) }

    val coroutineScope = rememberCoroutineScope()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> if (uri != null) secilenFotografUri = uri }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                secilenFotografUri = kameraIcinUri
            }
        }
    )

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // --- O Anki Konum ve Adres (Form için) ---
    var bulunanAdres by remember { mutableStateOf("Adres tespit ediliyor...") }
    var anlikLat by remember { mutableStateOf(0.0) }
    var anlikLng by remember { mutableStateOf(0.0) }
    var formAciliyor by remember { mutableStateOf(false) }

    // --- Profil Ekranı ---
    var profilAcik by remember { mutableStateOf(false) }

    // --- Ekran Yönetimi (Lobi, Harita, Takip) ---
    var aktifEkran by remember { mutableStateOf("LOBI") }

    // --- Admin Ekranı ---
    var adminGirisEkraniAcik by remember { mutableStateOf(false) }
    var adminGirisiYapildi by remember {
        val prefs = context.getSharedPreferences("AdminPrefs", android.content.Context.MODE_PRIVATE)
        mutableStateOf(prefs.getBoolean("admin_loggedin", false))
    }

    // --- Haritadaki Sorunlar ---
    var sorunlarListesi by remember { mutableStateOf<List<SorunModel>>(emptyList()) }

    // --- Cihaz ID (Benzersiz Profil İçin) ---
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "BilinmeyenKullanici"

    // --- Karşıyaka Sınırları ve Harita Ayarları ---
    val karsiyakaMerkez = LatLng(38.4550, 27.1140)
    val karsiyakaBounds = LatLngBounds(
        LatLng(38.4350, 27.0800), // Güneybatı (SW) (Daha dar Karşıyaka limitleri)
        LatLng(38.4750, 27.1500)  // Kuzeydoğu (NE)
    )

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(karsiyakaMerkez, 15f)
    }

    // --- SORUN KAYDETME İŞLEVİ (FIREBASE) ---
    fun sorunKaydet() {
        if (yorum.isBlank() || anlikLat == 0.0) {
            Toast.makeText(context, "Lütfen açıklama girin ve konumun bulunmasını bekleyin.", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                var photoUrl = ""
                // 1. Eğer fotoğraf seçildiyse Firebase Storage'a yükle
                if (secilenFotografUri != null) {
                    val uri = secilenFotografUri!!
                    val storageRef = FirebaseStorage.getInstance().reference
                    val photoRef = storageRef.child("sorunlar/${System.currentTimeMillis()}.jpg")
                    try {
                        photoRef.putFile(uri).await()
                        val downloadUrl = photoRef.downloadUrl.await()
                        photoUrl = downloadUrl.toString()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Fotoğraf yüklenemedi: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // 2. Firestore'a veriyi kaydet (Formda bulunan anlık koordinatlar ve adres ile)
                val sorunVerisi = hashMapOf(
                    "kullaniciId" to androidId,
                    "adres" to bulunanAdres,
                    "ilce" to "Karşıyaka", // Sabit Karşıyaka (İsteğe bağlı silebiliriz)
                    "mahalle" to "",
                    "sokak" to "",
                    "aciklama" to yorum,
                    "fotografUrl" to photoUrl,
                    "durum" to 0,
                    "silindi" to false,
                    "adminMesaji" to "",
                    "lat" to anlikLat,
                    "lng" to anlikLng,
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

    // Cihazın anlık konumunu bulma ve kamerayı yakınlaştırma işlevi
    fun anlikKonumBulVeFormAc() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            formAciliyor = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val latLng = LatLng(location.latitude, location.longitude)
                    anlikLat = location.latitude
                    anlikLng = location.longitude

                    // Kamerayı o anki konuma zoom yap
                    coroutineScope.launch {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
                    }

                    // Adresi bul
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            val addressList = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            if (!addressList.isNullOrEmpty()) {
                                bulunanAdres = addressList[0].getAddressLine(0) ?: "Bilinmeyen Adres"
                            } else {
                                bulunanAdres = "Adres bulunamadı (Enlem: ${location.latitude}, Boylam: ${location.longitude})"
                            }
                        } catch (e: Exception) {
                            bulunanAdres = "Adres çözülemedi."
                        }
                    }
                } else {
                    Toast.makeText(context, "Konum alınamadı, GPS'in açık olduğundan emin olun.", Toast.LENGTH_SHORT).show()
                }
                formAciliyor = false
                showSheet = true // Formu aç
            }.addOnFailureListener {
                Toast.makeText(context, "Konum hatası: ${it.message}", Toast.LENGTH_SHORT).show()
                formAciliyor = false
            }
        } else {
            Toast.makeText(context, "Konum izni reddedildi.", Toast.LENGTH_SHORT).show()
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
                                kullaniciId = doc.getString("kullaniciId") ?: "",
                                lat = doc.getDouble("lat") ?: 0.0,
                                lng = doc.getDouble("lng") ?: 0.0,
                                aciklama = doc.getString("aciklama") ?: "",
                                adres = doc.getString("adres") ?: "",
                                fotografUrl = doc.getString("fotografUrl") ?: "",
                                durum = doc.getLong("durum")?.toInt() ?: (if (doc.getBoolean("cozuldu") == true) 2 else 0),
                                silindi = doc.getBoolean("silindi") ?: false,
                                adminMesaji = doc.getString("adminMesaji") ?: ""
                            )
                        )
                    }
                    sorunlarListesi = liste
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            anlikKonumBulVeFormAc()
        }
    }

    // --- Admin Durum Güncelleme ---
    fun durumuGuncelle(id: String, yeniDurum: Int) {
        val db = FirebaseFirestore.getInstance()
        db.collection("sorunlar").document(id).update("durum", yeniDurum, "cozuldu", yeniDurum == 2)
            .addOnSuccessListener { Toast.makeText(context, "Durum güncellendi", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(context, "Hata: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    // --- Admin Mesaj Güncelleme ---
    fun mesajGuncelle(id: String, mesaj: String) {
        val db = FirebaseFirestore.getInstance()
        db.collection("sorunlar").document(id).update("adminMesaji", mesaj)
            .addOnSuccessListener { Toast.makeText(context, "Mesaj gönderildi", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(context, "Hata: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    // --- Admin Sorun Silme (Soft Delete) ---
    fun sorunuSil(id: String) {
        val db = FirebaseFirestore.getInstance()
        db.collection("sorunlar").document(id).update("silindi", true)
            .addOnSuccessListener { Toast.makeText(context, "Sorun silindi", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(context, "Hata: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    // --- LOBİ EKRANI ---
    if (aktifEkran == "LOBI" && !adminGirisiYapildi) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🌳", style = MaterialTheme.typography.displayLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Karşıyaka Çözüm Merkezi",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Kentimizi birlikte güzelleştiriyoruz.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )

            Button(
                onClick = { aktifEkran = "HARITA" },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Sorun Bildir", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { aktifEkran = "TAKIP" },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Bildirimlerimi Takip Et", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { adminGirisEkraniAcik = true }) {
                Text("Yönetici Girişi", color = Color.LightGray)
            }
        }
    }

    // --- EKRAN YÖNLENDİRMELERİ ---
    if (adminGirisiYapildi) {
        val istatistikToplam = sorunlarListesi.size
        val istatistikCozulen = sorunlarListesi.count { it.durum == 2 }
        val istatistikSilinen = sorunlarListesi.count { it.silindi }
        val aktifSorunlar = sorunlarListesi.filter { !it.silindi }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Admin Paneli", style = MaterialTheme.typography.headlineMedium)
                Button(onClick = {
                    val prefs = context.getSharedPreferences("AdminPrefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("admin_loggedin", false).apply()
                    adminGirisiYapildi = false
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                    Text("Çıkış")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // --- İstatistik Panosu ---
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Toplam", style = MaterialTheme.typography.labelMedium)
                        Text("$istatistikToplam", style = MaterialTheme.typography.headlineSmall, color = Color.Blue)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Çözülen", style = MaterialTheme.typography.labelMedium)
                        Text("$istatistikCozulen", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF4CAF50))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Silinen", style = MaterialTheme.typography.labelMedium)
                        Text("$istatistikSilinen", style = MaterialTheme.typography.headlineSmall, color = Color.Red)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(aktifSorunlar) { sorun ->
                    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (sorun.fotografUrl.isNotEmpty()) {
                                AsyncImage(
                                    model = sorun.fotografUrl,
                                    contentDescription = "Sorun Fotoğrafı",
                                    modifier = Modifier.fillMaxWidth().height(200.dp).padding(bottom = 8.dp)
                                )
                            }
                            Text("Adres: ${sorun.adres}", style = MaterialTheme.typography.titleMedium)
                            Text("Açıklama: ${sorun.aciklama}", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Durum Güncelle:", style = MaterialTheme.typography.labelLarge)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { durumuGuncelle(sorun.id, 0) }, enabled = sorun.durum != 0) {
                                    Text("Bekliyor")
                                }
                                OutlinedButton(onClick = { durumuGuncelle(sorun.id, 1) }, enabled = sorun.durum != 1) {
                                    Text("İletildi")
                                }
                                Button(onClick = { durumuGuncelle(sorun.id, 2) }, enabled = sorun.durum != 2, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                                    Text("Çözüldü")
                                }
                                IconButton(onClick = { sorunuSil(sorun.id) }) {
                                    Text("🗑️", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Kullanıcıya Mesaj Gönder:", style = MaterialTheme.typography.labelLarge)
                            var mesajMetni by remember { mutableStateOf(sorun.adminMesaji) }
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = mesajMetni,
                                    onValueChange = { mesajMetni = it },
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    placeholder = { Text("Açıklama veya çözüm notu yazın...") },
                                    maxLines = 2
                                )
                                Button(onClick = { mesajGuncelle(sorun.id, mesajMetni) }) {
                                    Text("Gönder")
                                }
                            }
                        }
                    }
                }
            }
        }
        return // Ana ekranı gösterme, sadece admin panelini göster
    }

    if (aktifEkran == "HARITA" && !adminGirisiYapildi) {
    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                latLngBoundsForCameraTarget = karsiyakaBounds,
                minZoomPreference = 14f
            ),
            onMapLongClick = { latLng ->
                anlikLat = latLng.latitude
                anlikLng = latLng.longitude
                formAciliyor = true
                coroutineScope.launch {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
                }
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val addressList = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                        if (!addressList.isNullOrEmpty()) {
                            bulunanAdres = addressList[0].getAddressLine(0) ?: "Bilinmeyen Adres"
                        } else {
                            bulunanAdres = "Adres bulunamadı (Enlem: ${latLng.latitude}, Boylam: ${latLng.longitude})"
                        }
                    } catch (e: Exception) {
                        bulunanAdres = "Adres çözülemedi."
                    }
                    withContext(Dispatchers.Main) {
                        formAciliyor = false
                        showSheet = true
                    }
                }
            }
        ) {
            // Haritada Aktif Sorunları Göster
            val aktifSorunlar = sorunlarListesi.filter { !it.silindi }
            aktifSorunlar.forEach { sorun ->
                val konum = LatLng(sorun.lat, sorun.lng)

                // Karakter/Renk: Bekliyor (Kırmızı), İletildi (Sarı), Çözüldü (Yeşil)
                val (iconColor, durumYazisi) = when (sorun.durum) {
                    2 -> Pair(BitmapDescriptorFactory.HUE_GREEN, "✅ Çözüldü")
                    1 -> Pair(BitmapDescriptorFactory.HUE_ORANGE, "🟠 İletildi")
                    else -> Pair(BitmapDescriptorFactory.HUE_RED, "🔴 Bekliyor")
                }

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
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    anlikKonumBulVeFormAc()
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA))
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).height(56.dp).fillMaxWidth(0.6f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0055)),
            enabled = !formAciliyor
        ) {
            if (formAciliyor) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            } else {
                Text("SORUN BİLDİR")
            }
        }

        Column(modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)) {
            Button(
                onClick = { profilAcik = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("Profilim")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { adminGirisEkraniAcik = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("Admin")
            }
        }

        if (profilAcik) {
            val benimSorunlarim = sorunlarListesi.filter { it.kullaniciId == androidId && !it.silindi }
            AlertDialog(
                onDismissRequest = { profilAcik = false },
                title = { Text("Bildirdiğim Sorunlar") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        if (benimSorunlarim.isEmpty()) {
                            Text("Henüz bir sorun bildirmediniz.")
                        } else {
                            benimSorunlarim.forEach { sorun ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(text = "Adres: ${sorun.adres}", style = MaterialTheme.typography.bodySmall)
                                        Text(text = "Açıklama: ${sorun.aciklama}", style = MaterialTheme.typography.bodyMedium)
                                        val (durumYazisi, durumRengi) = when (sorun.durum) {
                                            2 -> Pair("✅ Çözüldü", Color(0xFF4CAF50))
                                            1 -> Pair("🟠 İlgili Birime İletildi", Color(0xFFFF9800))
                                            else -> Pair("🔴 Bekliyor", Color.Red)
                                        }
                                        Text(
                                            text = durumYazisi,
                                            color = durumRengi,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { profilAcik = false }) {
                        Text("Kapat")
                    }
                }
            )
        }

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Sorun Bildir", style = MaterialTheme.typography.titleLarge)

                    // Otomatik Bulunan Konum
                    OutlinedTextField(
                        value = bulunanAdres,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Bulunan Konum") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = yorum, onValueChange = { yorum = it },
                        label = { Text("Sorun Açıklaması (Örn: Çukur var)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val resimDosyasi = File(context.cacheDir, "images")
                                resimDosyasi.mkdirs()
                                val dosya = File(resimDosyasi, "kamera_${System.currentTimeMillis()}.jpg")
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", dosya)
                                kameraIcinUri = uri
                                cameraLauncher.launch(uri)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("Kamerayla Çek")
                        }

                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                        ) {
                            Text("Galeriden Seç")
                        }
                    }

                    if (secilenFotografUri != null) {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = secilenFotografUri,
                                contentDescription = "Seçilen Fotoğraf",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .padding(vertical = 8.dp)
                            )
                            TextButton(onClick = {
                                secilenFotografUri = null
                                kameraIcinUri = null
                            }) {
                                Text("Fotoğrafı Sil", color = Color.Red)
                            }
                        }
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

        // Harita Ekranı Geri Dön Butonu
        Button(
            onClick = { aktifEkran = "LOBI" },
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            Text("← Lobiye Dön")
        }
    } // End of HARITA
    }

    // --- TAKİP EKRANI ---
    if (aktifEkran == "TAKIP" && !adminGirisiYapildi) {
        val benimSorunlarim = sorunlarListesi.filter { it.kullaniciId == androidId && !it.silindi }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Button(
                onClick = { aktifEkran = "LOBI" },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("← Geri Dön")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Bildirimlerim", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))

            if (benimSorunlarim.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Henüz bir sorun bildirmediniz.", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(benimSorunlarim) { sorun ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = sorun.adres, style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = sorun.aciklama, style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.height(12.dp))

                                val (durumYazisi, durumRengi, icon) = when (sorun.durum) {
                                    2 -> Triple("Çözüldü", Color(0xFF4CAF50), "✅")
                                    1 -> Triple("İlgili Birime İletildi", Color(0xFFFFA000), "🟠")
                                    else -> Triple("İnceleniyor (Bekliyor)", Color.Red, "🔴")
                                }

                                Surface(
                                    color = durumRengi.copy(alpha = 0.1f),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = icon, modifier = Modifier.padding(end = 8.dp))
                                        Text(text = durumYazisi, color = durumRengi, style = MaterialTheme.typography.labelLarge)
                                    }
                                }

                                if (sorun.adminMesaji.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("Admin Yanıtı:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(sorun.adminMesaji, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } // End of TAKIP

    if (adminGirisEkraniAcik) {
        var adminKullanici by remember { mutableStateOf("") }
        var adminSifre by remember { mutableStateOf("") }
        var hataMesaji by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { adminGirisEkraniAcik = false },
            title = { Text("Admin Girişi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = adminKullanici,
                        onValueChange = { adminKullanici = it },
                        label = { Text("Kullanıcı Adı") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adminSifre,
                        onValueChange = { adminSifre = it },
                        label = { Text("Şifre") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (hataMesaji.isNotEmpty()) {
                        Text(text = hataMesaji, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val beklenenKullaniciHash = "5596c93f77709e9c12fc4f8633c712221002652f66241e675c1046475497f426"
                    val beklenenSifreHash = "6ad04b02e45051fc74fec6748bc60bf26fb30fb7a202d6719ebd138287d2def8"

                    fun String.toSHA256(): String {
                        val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
                        return bytes.joinToString("") { "%02x".format(it) }
                    }

                    if (adminKullanici.toSHA256() == beklenenKullaniciHash && adminSifre.toSHA256() == beklenenSifreHash) {
                        val prefs = context.getSharedPreferences("AdminPrefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("admin_loggedin", true).apply()
                        adminGirisiYapildi = true
                        adminGirisEkraniAcik = false
                    } else {
                        hataMesaji = "Hatalı kullanıcı adı veya şifre"
                    }
                }) {
                    Text("Giriş")
                }
            },
            dismissButton = {
                TextButton(onClick = { adminGirisEkraniAcik = false }) { Text("İptal") }
            }
        )
    }

}