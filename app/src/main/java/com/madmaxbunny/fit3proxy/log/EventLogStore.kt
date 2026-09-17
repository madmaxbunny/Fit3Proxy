package com.madmaxbunny.fit3proxy.log

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Process-wide event bus for MediaSession / Notification Action logs.
 * BroadcastReceivers and services post here; MainActivity observes.
 */
class EventLogStore {

    private val _events = MutableLiveData<String>()
    val events: LiveData<String> = _events

    fun emit(message: String) {
        Log.i(TAG, message)
        _events.postValue(message)
    }

    companion object {
        private const val TAG = "Fit3EventLog"
    }
}
