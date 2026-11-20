package lu.bourse.mobile

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import lu.bourse.mobile.ui.BourseApp
import lu.bourse.mobile.ui.theme.BourseTheme

class MainActivity : ComponentActivity() {
    private var alertRequests by mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_alerts", false)) alertRequests++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("open_alerts", false)) alertRequests++
        enableEdgeToEdge()
        setContent {
            BourseTheme {
                val store: AppStore = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppStore(PhoneConfig(applicationContext)) as T
                })
                BourseApp(store, alertRequests)
            }
        }
    }
}
