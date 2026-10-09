package com.tradequest.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tradequest.app.R
import com.tradequest.data.CatchUpClose
import com.tradequest.data.CatchUpEvent
import com.tradequest.data.CatchUpFill
import com.tradequest.data.CatchUpResult

/** Posts the trade alerts produced by a catch-up run. */
object Notifier {

    const val CHANNEL_FILLS = "fills"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_MARGIN = "margin"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FILLS, "Order fills", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Stop / target hits", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MARGIN, "Margin & stop-outs", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun postCatchUp(context: Context, result: CatchUpResult) {
        if (!canNotify(context)) return
        result.fills.forEach { postFill(context, it) }
        result.closes.forEach { postClose(context, it) }
        result.events.forEach { postEvent(context, it) }
    }

    private fun postFill(context: Context, fill: CatchUpFill) {
        notify(
            context,
            id = ("fill-${fill.orderId}").hashCode(),
            channel = CHANNEL_FILLS,
            title = "Order filled",
            text = "${fill.side} ${fill.lots} lots @ ${"%.2f".format(fill.price)} (${fill.reason})",
        )
    }

    private fun postClose(context: Context, close: CatchUpClose) {
        val sign = if (close.netPnl >= 0) "+" else ""
        notify(
            context,
            id = ("close-${close.positionId}").hashCode(),
            channel = CHANNEL_ALERTS,
            title = "${close.reason} hit",
            text = "Position ${close.positionId} closed  $sign${"%.2f".format(close.netPnl)}",
        )
    }

    private fun postEvent(context: Context, event: CatchUpEvent) {
        val channel = if (event.type == "MARGIN_WARNING" || event.type == "STOP_OUT") CHANNEL_MARGIN else CHANNEL_ALERTS
        notify(
            context,
            id = ("event-${event.ts}-${event.type}").hashCode(),
            channel = channel,
            title = event.type.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            text = event.message,
        )
    }

    private fun notify(context: Context, id: Int, channel: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_trade)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
