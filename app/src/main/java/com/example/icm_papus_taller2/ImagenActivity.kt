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


    private var photoUri: Uri? = null


    private enum class PendingAction { NONE, OPEN_GALLERY, OPEN_CAMERA }
    private var pendingAction: PendingAction = PendingAction.NONE


    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                imgPreview.setImageURI(uri)
                mostrarPropiedades(uri)
            } else {
                Toast.makeText(this, "No se seleccionó imagen", Toast.LENGTH_SHORT).show()
            }
        }


    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved: Boolean ->

            val uri: Uri? = photoUri
            if (saved && uri != null) {
                imgPreview.setImageURI(uri)
                mostrarPropiedades(uri)
                Toast.makeText(this, "Foto guardada en galería", Toast.LENGTH_SHORT).show()
            } else {

                uri?.let { contentResolver.delete(it, null, null) }
                Toast.makeText(this, "Foto cancelada", Toast.LENGTH_SHORT).show()
            }

            photoUri = null
        }


    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Permiso denegado", Toast.LENGTH_LONG).show()
                pendingAction = PendingAction.NONE
            } else {

                when (val action = pendingAction) {
                    PendingAction.OPEN_GALLERY -> {
                        pendingAction = PendingAction.NONE
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    PendingAction.OPEN_CAMERA -> {
                        pendingAction = PendingAction.NONE
                        lanzarCamaraFullRes()
                    }
                    else -> Unit
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_imagen)

        imgPreview = findViewById(R.id.imgPreview)
        tvProps = findViewById(R.id.tvProps)

        findViewById<MaterialButton>(R.id.btnGaleria).setOnClickListener {
            asegurarPermisoGaleria {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }

        findViewById<MaterialButton>(R.id.btnCamara).setOnClickListener {
            asegurarPermisoCamara {
                lanzarCamaraFullRes()
            }
        }
    }

    // Permisos

    private fun asegurarPermisoGaleria(onOk: () -> Unit) {
        val permiso = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED -> onOk()
            shouldShowRequestPermissionRationale(permiso) -> {
                pendingAction = PendingAction.OPEN_GALLERY
                mostrarJustificacion(
                    "Acceso a imágenes",
                    "Necesitamos leer tus imágenes para mostrarlas en la app."
                ) { requestPermission.launch(permiso) }
            }
            else -> {
                pendingAction = PendingAction.OPEN_GALLERY
                requestPermission.launch(permiso)
            }
        }
    }

    private fun asegurarPermisoCamara(onOk: () -> Unit) {
        val permiso = Manifest.permission.CAMERA
        when {
            ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED -> onOk()
            shouldShowRequestPermissionRationale(permiso) -> {
                pendingAction = PendingAction.OPEN_CAMERA
                mostrarJustificacion(
                    "Uso de la cámara",
                    "Necesitamos el permiso para tomar una foto a resolución completa y guardarla."
                ) { requestPermission.launch(permiso) }
            }
            else -> {
                pendingAction = PendingAction.OPEN_CAMERA
                requestPermission.launch(permiso)
            }
        }
    }

    private fun mostrarJustificacion(titulo: String, mensaje: String, continuar: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Continuar") { d, _ -> d.dismiss(); continuar() }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .show()
    }

    // Cámara FULL RES

    private fun lanzarCamaraFullRes() {

        val name = "IMG_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

        }
        photoUri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )
        val target = photoUri
        if (target == null) {
            Toast.makeText(this, "No se pudo crear destino para la foto", Toast.LENGTH_LONG).show()
            return
        }
        takePicture.launch(target) // escribe FULL RES en esa URI (persistente)
    }

    //propiedades de imagen


    private fun obtenerDimensiones(uri: Uri): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var input: InputStream? = null
        try {
            input = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(input, null, opts)
        } finally {
            input?.close()
        }
        return opts.outWidth to opts.outHeight
    }

    private fun consultarTamanioBytes(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                if (c.moveToFirst()) return c.getLong(idx)
            }
        return -1L
    }

    private fun mostrarPropiedades(uri: Uri) {
        val (w, h) = obtenerDimensiones(uri)
        val sizeBytes = consultarTamanioBytes(uri)
        tvProps.text = buildString {
            appendLine("URI: $uri")
            appendLine("Resolución: ${w}×${h} px")
            append("Tamaño: ${if (sizeBytes >= 0) "$sizeBytes bytes" else "N/D"}")
        }
    }
}
