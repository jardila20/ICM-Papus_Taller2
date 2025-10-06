package com.example.icm_papus_taller2

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.ListView
import android.widget.SimpleCursorAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ContactosActivity : AppCompatActivity() {

    private lateinit var tvEstado: TextView
    private lateinit var listView: ListView
    private var cursor: Cursor? = null
    private var adapter: SimpleCursorAdapter? = null

    // 1) Activity Result API para pedir el permiso en runtime (recomendado)
    private val requestContactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Permiso de acceso a contactos concedido", Toast.LENGTH_SHORT).show()
            cargarContactos()
        } else {
            Toast.makeText(this, "No se puede acceder a contactos sin permiso", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_contactos)

        listView = findViewById(R.id.lvContactos)

        asegurarPermisoYListar()
    }

    private fun asegurarPermisoYListar() {
        val permiso = Manifest.permission.READ_CONTACTS
        when {
            // 2) Ya hay permiso
            ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED -> {
                tvEstado.text = "Permiso: CONCEDIDO"
                cargarContactos()
            }
            // 3) Mostrar justificación si el usuario ya negó antes
            shouldShowRequestPermissionRationale(permiso) -> {
                mostrarJustificacion {
                    requestContactsPermission.launch(permiso)
                }
            }
            // 4) Solicitar directamente
            else -> requestContactsPermission.launch(permiso)
        }
    }

    private fun mostrarJustificacion(onContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Acceso a contactos")
            .setMessage("Necesitamos leer tus contactos para listarlos en pantalla. Sin este permiso, no podremos mostrar nada.")
            .setPositiveButton("Continuar") { d, _ ->
                d.dismiss()
                onContinue()
            }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .show()
    }

    private fun cargarContactos() {
        // 5) Consultar el proveedor de contactos (después de que el permiso esté concedido)
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )

        cursor?.close()
        cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        )

        if (cursor == null) {
            Toast.makeText(this, "No se pudieron leer los contactos.", Toast.LENGTH_SHORT).show()
            return
        }

        // 6) Vincular el cursor a la lista con un adapter sencillo
        adapter?.changeCursor(cursor) ?: run {
            adapter = SimpleCursorAdapter(
                this,
                android.R.layout.simple_list_item_2,
                cursor,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                intArrayOf(android.R.id.text1, android.R.id.text2),
                0
            )
            listView.adapter = adapter
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter?.changeCursor(null)
        cursor?.close()
    }
}
