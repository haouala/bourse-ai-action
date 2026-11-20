package lu.bourse.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lu.bourse.mobile.ui.BourseHome
import lu.bourse.mobile.ui.theme.BourseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BourseTheme {
                BourseHome()
            }
        }
    }
}
