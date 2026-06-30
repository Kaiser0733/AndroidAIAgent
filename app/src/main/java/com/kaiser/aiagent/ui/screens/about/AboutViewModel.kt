package com.kaiser.aiagent.ui.screens.about

import android.content.Context
import androidx.lifecycle.ViewModel
import com.kaiser.aiagent.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only view model for the About screen. Pulls version info straight
 * from BuildConfig rather than via a repository — the values are static
 * at runtime.
 */
class AboutViewModel(context: Context) : ViewModel() {

    data class UiState(
        val versionName: String,
        val versionCode: Int,
        val applicationId: String,
        val buildType: String
    )

    private val _state = MutableStateFlow(
        UiState(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            applicationId = BuildConfig.APPLICATION_ID,
            buildType = BuildConfig.BUILD_TYPE
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
}
