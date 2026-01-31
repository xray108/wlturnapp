package com.wlturnapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class LogsFragment : Fragment() {

    private lateinit var tvLogs: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_logs, container, false)
        tvLogs = view.findViewById(R.id.tv_logs)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msg = intent?.getStringExtra("log") ?: return
                tvLogs.append("$msg\n")
                (view.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(receiver, IntentFilter("LOG_UPDATE"))

        view.tag = receiver
        return view
    }

    override fun onDestroyView() {
        (view?.tag as? BroadcastReceiver)?.let {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
        }
        super.onDestroyView()
    }
}
