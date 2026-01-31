package com.wlturnapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject

data class Preset(val name: String, val peer: String, val link: String, val listen: String)

class ConfigFragment : Fragment() {

    private lateinit var etPeer: EditText
    private lateinit var etLink: EditText
    private lateinit var etListen: EditText
    private lateinit var btnToggle: Button
    private lateinit var spinnerPresets: Spinner
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView

    private var isRunning = false
    private val presets = mutableListOf<Preset>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_config, container, false)

        etPeer = view.findViewById(R.id.et_peer)
        etLink = view.findViewById(R.id.et_link)
        etListen = view.findViewById(R.id.et_listen)
        btnToggle = view.findViewById(R.id.btn_toggle)
        spinnerPresets = view.findViewById(R.id.spinner_presets)
        btnSave = view.findViewById(R.id.btn_save_preset)
        tvStatus = view.findViewById(R.id.tv_status)

        loadPresets()
        setupSpinner()

        btnToggle.setOnClickListener { toggleProxy() }
        btnSave.setOnClickListener { savePreset() }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(statusReceiver, IntentFilter("STATUS_UPDATE"))

        return view
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            tvStatus.text = "Статус: $status"
            isRunning = status == "Запущен"
            btnToggle.text = if (isRunning) "Остановить" else "Запустить"
        }
    }

    private fun toggleProxy() {
        val intent = Intent(requireContext(), ProxyService::class.java)
        if (isRunning) {
            requireContext().stopService(intent)
        } else {
            intent.apply {
                putExtra("peer", etPeer.text.toString())
                putExtra("link", etLink.text.toString())
                putExtra("listen", etListen.text.toString())
            }
            requireContext().startForegroundService(intent)
        }
    }

    private fun savePreset() {
        val name = "Пресет ${presets.size + 1}"
        val preset = Preset(name, etPeer.text.toString(), etLink.text.toString(), etListen.text.toString())
        presets.add(preset)
        savePresets()
        setupSpinner()
        Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
    }

    private fun loadPresets() {
        val prefs = requireContext().getSharedPreferences("presets", Context.MODE_PRIVATE)
        val json = prefs.getString("presets", "[]") ?: "[]"
        JSONArray(json).let { array ->
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                presets.add(Preset(obj.getString("name"), obj.getString("peer"), obj.getString("link"), obj.getString("listen")))
            }
        }
    }

    private fun savePresets() {
        val prefs = requireContext().getSharedPreferences("presets", Context.MODE_PRIVATE)
        val array = JSONArray()
        presets.forEach {
            array.put(JSONObject().apply {
                put("name", it.name)
                put("peer", it.peer)
                put("link", it.link)
                put("listen", it.listen)
            })
        }
        prefs.edit().putString("presets", array.toString()).apply()
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presets.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPresets.adapter = adapter
        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val p = presets[position]
                etPeer.setText(p.peer)
                etLink.setText(p.link)
                etListen.setText(p.listen)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(statusReceiver)
        super.onDestroyView()
    }
}
