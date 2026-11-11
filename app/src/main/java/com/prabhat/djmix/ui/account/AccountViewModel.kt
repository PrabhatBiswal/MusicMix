package com.prabhat.djmix.ui.account

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabhat.djmix.auth.AuthRepository
import com.prabhat.djmix.library.CloudRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The account panel's state holder. The screen only draws [state] and calls the functions; the ViewModel outlives
 * redraws, and Hilt passes in the two repositories (@HiltViewModel + @Inject constructor).
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val cloud: CloudRepository,
) : ViewModel() {

    data class UiState(val user: AuthRepository.User? = null, val autoBackup: Boolean = true, val busy: Boolean = false, val error: String? = null)

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(auth.user, cloud.autoBackup, busy, error, ::UiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(auth.user.value, cloud.autoBackup.value))

    /**
     * Sign-in in two steps: [getToken] shows Google's account picker (it needs the Activity, so the screen supplies
     * it), then Firebase turns the token into a user. Closing the picker is not an error.
     */
    fun signIn(getToken: suspend () -> String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; error.value = null
            try {
                auth.signIn(getToken())
            } catch (e: GetCredentialCancellationException) {
                // the user closed the picker
            } catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
            } finally {
                busy.value = false
            }
        }
    }

    fun signOut() { viewModelScope.launch { auth.signOut() } }

    fun setAutoBackup(on: Boolean) = cloud.setAutoBackup(on)
}
