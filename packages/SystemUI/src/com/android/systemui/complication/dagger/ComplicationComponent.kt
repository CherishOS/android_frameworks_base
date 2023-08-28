package com.android.systemui.complication.dagger

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import com.android.systemui.complication.Complication
import com.android.systemui.complication.ComplicationHostViewController
import com.android.systemui.complication.ComplicationLayoutEngine
import com.android.systemui.touch.TouchInsetManager
import dagger.BindsInstance
import dagger.Subcomponent

@Subcomponent(modules = [ComplicationModule::class])
@ComplicationModule.ComplicationScope
interface ComplicationComponent {
    /** Factory for generating [ComplicationComponent]. */
    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance lifecycleOwner: LifecycleOwner,
            @BindsInstance host: Complication.Host,
            @BindsInstance viewModelStore: ViewModelStore,
            @BindsInstance touchInsetManager: TouchInsetManager
        ): ComplicationComponent
    }

    fun getComplicationHostViewController(): ComplicationHostViewController

    fun getVisibilityController(): ComplicationLayoutEngine
}
