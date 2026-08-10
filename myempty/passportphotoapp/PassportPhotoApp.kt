package com.example.myempty.passportphotoapp

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.example.passportphotomaker.data.repository.PrintProjectRepositoryImpl
import com.example.passportphotomaker.data.repository.ProjectRepositoryImpl
import com.example.passportphotomaker.domain.repository.PrintProjectRepository
import com.example.passportphotomaker.domain.repository.ProjectRepository
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


interface AppContainer {
    val projectRepository: ProjectRepository
    val printProjectRepository: PrintProjectRepository
}


class DefaultAppContainer(
    private val context: Context
) : AppContainer {

    override val projectRepository: ProjectRepository by lazy {
        ProjectRepositoryImpl(context)
    }

    override val printProjectRepository: PrintProjectRepository by lazy {
        PrintProjectRepositoryImpl(context)
    }
}


class PassportPhotoApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        installCrashReporter()

        container = DefaultAppContainer(applicationContext)

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    // Fires when the app leaves the foreground — the OS guarantees
                    // this runs before the process can be reclaimed from RAM in all
                    // normal close paths (Recents swipe, Home, background kill).
                    // Any still-staged edit at this point was abandoned without an
                    // explicit "leave My Studio" navigation, so treat it as discarded.
                    appScope.launch { container.projectRepository.clearAllStagingEdits() }
                }
            }
        )
    }

    /**
     * Custom Coil ImageLoader.
     *
     * Limits the in-memory cache to 25% of available application RAM.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .build()
    }

    /**
     * Installs CrashReporter as the global uncaught-exception handler.
     *
     * The previous system handler is preserved and called after
     * CrashReporter has recorded the crash.
     */
    private fun installCrashReporter() {
        val defaultHandler =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler(
            CrashReporter(
                context = applicationContext,
                defaultHandler = defaultHandler
            )
        )
    }
}