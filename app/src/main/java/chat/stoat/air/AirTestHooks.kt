package chat.stoat.air

import androidx.navigation.NavController

/** Lets the automated screen tests move around the interface like a person would. */
object AirTestHooks {
    @Volatile
    var navController: NavController? = null
}
