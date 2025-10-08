package com.example.icm_papus_taller2

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.ListView
import android.widget.SimpleCursorAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ContactosActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var cursor: Cursor? = null
    private var adapter: SimpleCursorAdapter? = null

    // Launcher para pedir READ_CONTACTS en runtime
    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cargarContactos()
            } else {
                Toast.makeText(
                    this,
                    "Permiso de contactos denegado. No se pueden mostrar contactos.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contactos)

        listView = findViewById(R.id.listViewContactos)
        asegurarPermisoYListar()
    }

    private fun asegurarPermisoYListar() {
        val permiso = Manifest.permission.READ_CONTACTS
        when {
            ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED -> {
                cargarContactos()
            }
            shouldShowRequestPermissionRationale(permiso) -> {
                mostrarJustificacion {
                    requestContactsPermission.launch(permiso)
                }
            }
            else -> {
                requestContactsPermission.launch(permiso)
            }
        }
    }

    private fun mostrarJustificacion(onContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Acceso a contactos")
            .setMessage("Necesitamos leer tus contactos para listarlos en pantalla.")
            .setPositiveButton("Continuar") { d, _ -> d.dismiss(); onContinue() }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .show()
    }

    private fun cargarContactos() {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )

        val sortOrder = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"

        cursor?.close()
        cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        if (cursor == null) {
            Toast.makeText(this, "No se pudieron leer los contactos.", Toast.LENGTH_SHORT).show()
            return
        }


        adapter = SimpleCursorAdapter(
            this,
            R.layout.item_contacto,
            cursor,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            intArrayOf(R.id.tvNombre),
            0
        )

        listView.adapter = adapter
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter?.changeCursor(null)
        cursor?.close()
    }
}
