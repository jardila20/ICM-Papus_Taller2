package com.example.icm_papus_taller2

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnContactos: ImageButton = findViewById(R.id.btnContactos)
        val btnImagen: ImageButton = findViewById(R.id.btnImagen)
        val btnMapa: ImageButton = findViewById(R.id.btnMapa)

        // Abrir actividad de Contactos
        btnContactos.setOnClickListener {
            val intent = Intent(this, ContactosActivity::class.java)
            startActivity(intent)
        }

        // Abrir actividad de Imagen
        btnImagen.setOnClickListener {
            val intent = Intent(this, ImagenActivity::class.java)
            startActivity(intent)
        }

        // Abrir actividad de Mapa
        btnMapa.setOnClickListener {
            val intent = Intent(this, MapaActivity::class.java)
            startActivity(intent)
        }
    }
}
