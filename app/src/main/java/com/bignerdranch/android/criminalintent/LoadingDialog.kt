package com.bignerdranch.android.criminalintent

import android.app.Activity
import android.app.AlertDialog

class LoadingDialog  // constructor of dialog class
// with parameter activity
internal constructor(  // 2 objects activity and dialog
    private val activity: Activity
) {
    private var dialog: AlertDialog? = null
    fun show() {
        val builder = AlertDialog.Builder(activity)
        val inflater = activity.layoutInflater
        builder.setView(inflater.inflate(R.layout.alert_loading, null))
        builder.setCancelable(false)
        dialog = builder.create()
        dialog?.show()
    }

    fun dismiss() {
        dialog!!.dismiss()
    }
}