package com.tradequest.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tradequest.app.clock.LiveClock
import com.tradequest.app.ui.TradeQuestScreen
import com.tradequest.app.ui.TradingViewModel
import com.tradequest.app.ui.theme.TradeQuestTheme
import com.tradequest.app.ui.theme.tradeColors
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var liveClock: LiveClock

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        liveClock.start(lifecycleScope)

        setContent {
            val vm: TradingViewModel = hiltViewModel()
            val theme = vm.controller.state.theme
            TradeQuestTheme(theme) {
                Surface(color = tradeColors.surface) {
                    TradeQuest(vm)
                }
            }
        }
    }

    @Composable
    private fun TradeQuest(vm: TradingViewModel) {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        TradeQuestScreen(vm)
    }
}
