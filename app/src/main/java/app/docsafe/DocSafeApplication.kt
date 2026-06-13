package app.docsafe

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.docsafe.security.SecurityRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DocSafeApplication : Application() {

    @Inject
    lateinit var securityRepository: SecurityRepository

    override fun onCreate() {
        super.onCreate()
        // Lock the vault when the app goes to the background (subject to a short grace period
        // and suppression while we launch our own pickers/scanners), and re-evaluate on return.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                securityRepository.onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                securityRepository.onAppBackgrounded()
            }
        })
    }
}
