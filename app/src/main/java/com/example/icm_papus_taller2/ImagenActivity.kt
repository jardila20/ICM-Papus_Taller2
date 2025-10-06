package com.example.icm_papus_taller2

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.InputStream

class ImagenActivity : AppCompatActivity() {

    private lateinit var imgPreview: ImageView
    private lateinit var tvProps: TextView

    // Donde guardaremos la URI destino para foto a resolución completa
    private var photoUri: Uri? = null

    // --- Gallery picker (API moderno) ---
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                imgPreview.setImageURI(uri)
                mostrarPropiedades(uri)
            } else {
                Toast.makeText(this, "No se seleccionó imagen", Toast.LENGTH_SHORT).show()
            }
        }

    // --- Tomar foto con salida en URI (FULL RES y persistente en galería) ---
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved: Boolean ->
            if (saved && photoUri != null) {
                imgPreview.setImageURI(photoUri)
                mostrarPropiedades(photoUri!!)
                Toast.makeText(this, "Foto guardada en galería", Toast.LENGTH_SHORT).show()
            } else {
                // Si el usuario canceló, borrar el placeholder insertado
                photoUri?.let { contentResolver.delete(it, null, null) }
                photoUri = null
                Toast.makeText(this, "Foto cancelada", Toast.LENGTH_SHORT).show()
            }
        }

    // --- Request de permisos en runtime (cámara / lectura imágenes) ---
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Permiso denegado", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_imagen)

        imgPreview = findViewById(R.id.imgPreview)
        tvProps = findViewById(R.id.tvProps)

        findViewById<MaterialButton>(R.id.btnGaleria).setOnClickListener {
            asegurarPermisoGaleria {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        findViewById<MaterialButton>(R.id.btnCamara).setOnClickListener {
            asegurarPermisoCamara {
                // Crear entrada en MediaStore para guardar la foto a resolución completa
                val name = "IMG_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    // En API 29+ no necesitas WRITE_EXTERNAL_STORAGE para MediaStore
                }
                photoUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (photoUri == null) {
                    Toast.makeText(this, "No se pudo crear destino para la foto", Toast.LENGTH_LONG).show()
                    return@asegurarPermisoCamara
                }
                takePicture.launch(photoUri)
            }
        }
    }

    // ---------- Permisos ----------
    private fun asegurarPermisoGaleria(onOk: () -> Unit) {
        val permiso = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED -> onOk()
            shouldShowRequestPermissionRationale(permiso) -> mostrarJustificacion("Acceso a imágenes") {
                requestPermission.launch(permiso)
            }
            else -> requestPermission.launch(permiso)
        }
    }

    private fun asegurarPermisoCamara(onOk: () -> Unit) {
        val permiso = Manifest.permission.CAMERA
        when {
            ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED -> onOk()
            shouldShowRequestPermissionRationale(permiso) -> mostrarJustificacion("Uso de la cámara") {
                requestPermission.launch(permiso)
            }
            else -> requestPermission.launch(permiso)
        }
    }

    private fun mostrarJustificacion(titulo: String, continuar: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage("Se requiere este permiso para seleccionar/tomar imágenes para mostrarlas en la aplicación.")
            .setPositiveButton("Continuar") { d, _ -> d.dismiss(); continuar() }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .show()
    }

    // ---------- Utilidad: mostrar propiedades para verificar resolución óptima ----------
    private fun mostrarPropiedades(uri: Uri) {
        try {
            val props = obtenerDimensiones(uri)
            val tam = consultarTamanioBytes(uri)
            tvProps.text = "URI: $uri\nResolución: ${props.first}×${props.second}px\nTamaño: ${tam} bytes"
        } catch (e: Exception) {
            tvProps.text = "URI: $uri"
        }
    }

    private fun obtenerDimensiones(uri: Uri): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var input: InputStream? = null
        try {
            input = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(input, null, opts)
        } finally {
            input?.close()
        }
        return Pair(opts.outWidth, opts.outHeight)
    }

    private fun consultarTamanioBytes(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                if (c.moveToFirst()) return c.getLong(idx)
            }
        return -1L
    }
}
