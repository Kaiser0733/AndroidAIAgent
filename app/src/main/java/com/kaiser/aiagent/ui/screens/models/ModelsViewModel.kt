package com.kaiser.aiagent.ui.screens.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.aiagent.data.ai.AiBackend
import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.data.localai.ModelCatalog
import com.kaiser.aiagent.data.localai.ModelInfo
import com.kaiser.aiagent.data.localai.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Backs the Model Management screen. Lets the user:
 *   - See device RAM and the recommended model
 *   - Download a model (with progress)
 *   - Select an active model
 *   - Delete a downloaded model
 *   - Switch between Cloud and Local backends
 */
class ModelsViewModel(
    private val modelManager: ModelManager,
    private val aiRepository: AiRepository
) : ViewModel() {

    data class UiState(
        val deviceRamMb: Int = 0,
        val recommendedModelId: String = "",
        val models: List<ModelUiState> = emptyList(),
        val activeBackend: AiBackend = AiBackend.CLOUD,
        val activeLocalModelId: String? = null,
        val isLocalAiSupported: Boolean = false,
        val downloadInProgress: String? = null,
        val downloadPercent: Int = 0,
        val toast: String? = null
    )

    data class ModelUiState(
        val info: ModelInfo,
        val isDownloaded: Boolean,
        val isActive: Boolean,
        val isDownloading: Boolean,
        val downloadPercent: Int,
        val hasPartialDownload: Boolean = false,
        val partialDownloadBytes: Long = 0L
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        // Watch download progress
        viewModelScope.launch {
            modelManager.downloadProgress.collect { dlState ->
                when (dlState) {
                    is ModelManager.DownloadState.Downloading -> {
                        _state.value = _state.value.copy(
                            downloadInProgress = dlState.modelId,
                            downloadPercent = dlState.percent
                        )
                        updateModelStates()
                    }
                    is ModelManager.DownloadState.Completed -> {
                        _state.value = _state.value.copy(
                            downloadInProgress = null,
                            downloadPercent = 0,
                            toast = "Model downloaded successfully"
                        )
                        refresh()
                    }
                    is ModelManager.DownloadState.Failed -> {
                        _state.value = _state.value.copy(
                            downloadInProgress = null,
                            downloadPercent = 0,
                            toast = "Download failed: ${dlState.error}"
                        )
                    }
                    is ModelManager.DownloadState.Idle -> {
                        _state.value = _state.value.copy(downloadInProgress = null, downloadPercent = 0)
                    }
                }
            }
        }
        // Watch AI config for backend changes
        viewModelScope.launch {
            aiRepository.configFlow.collect { cfg ->
                _state.value = _state.value.copy(
                    activeBackend = cfg.backend,
                    activeLocalModelId = modelManager.activeModelId.value
                )
                updateModelStates()
            }
        }
    }

    fun refresh() {
        val ram = modelManager.getDeviceRamMb()
        val recommended = ModelCatalog.recommendedForRam(ram)
        _state.value = _state.value.copy(
            deviceRamMb = ram,
            recommendedModelId = recommended.id,
            isLocalAiSupported = aiRepository.isLocalAiSupported(),
            models = ModelCatalog.all.map { info ->
                ModelUiState(
                    info = info,
                    isDownloaded = modelManager.isDownloaded(info.id),
                    isActive = modelManager.activeModelId.value == info.id,
                    isDownloading = _state.value.downloadInProgress == info.id,
                    downloadPercent = if (_state.value.downloadInProgress == info.id)
                        _state.value.downloadPercent else 0
                )
            }
        )
    }

    private fun updateModelStates() {
        _state.value = _state.value.copy(
            models = ModelCatalog.all.map { info ->
                val partialBytes = modelManager.getPartialDownloadBytes(info.id)
                ModelUiState(
                    info = info,
                    isDownloaded = modelManager.isDownloaded(info.id),
                    isActive = modelManager.activeModelId.value == info.id,
                    isDownloading = _state.value.downloadInProgress == info.id,
                    downloadPercent = if (_state.value.downloadInProgress == info.id)
                        _state.value.downloadPercent else 0,
                    hasPartialDownload = partialBytes > 0 && !modelManager.isDownloaded(info.id),
                    partialDownloadBytes = partialBytes
                )
            }
        )
    }

    fun downloadModel(model: ModelInfo) {
        if (_state.value.downloadInProgress != null) return  // already downloading
        viewModelScope.launch {
            val path = modelManager.download(model)
            if (path != null) {
                // Auto-select the downloaded model
                selectModel(model.id)
            }
        }
    }

    fun selectModel(modelId: String) {
        val path = modelManager.getModelPath(modelId) ?: return
        modelManager.setActiveModel(modelId)
        viewModelScope.launch {
            aiRepository.updateConfig { it.copy(backend = AiBackend.LOCAL, localModelPath = path) }
            _state.value = _state.value.copy(
                activeBackend = AiBackend.LOCAL,
                activeLocalModelId = modelId,
                toast = "Switched to on-device model: $modelId"
            )
            updateModelStates()
        }
    }

    fun deleteModel(modelId: String) {
        modelManager.deleteModel(modelId)
        viewModelScope.launch {
            // If we just deleted the active model, switch back to cloud
            if (_state.value.activeLocalModelId == modelId) {
                aiRepository.updateConfig { it.copy(backend = AiBackend.CLOUD, localModelPath = null) }
                _state.value = _state.value.copy(
                    activeBackend = AiBackend.CLOUD,
                    activeLocalModelId = null,
                    toast = "Deleted model and switched to cloud backend"
                )
            }
            refresh()
        }
    }

    fun switchToCloud() {
        viewModelScope.launch {
            aiRepository.updateConfig { it.copy(backend = AiBackend.CLOUD) }
            _state.value = _state.value.copy(
                activeBackend = AiBackend.CLOUD,
                toast = "Switched to cloud API"
            )
        }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }
}
