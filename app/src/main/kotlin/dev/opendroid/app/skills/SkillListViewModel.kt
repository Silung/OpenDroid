package dev.opendroid.app.skills

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SkillListViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = OpenDroidSkillRepository(application)

    private val _items = MutableStateFlow<List<OpenDroidSkillInfo>>(emptyList())
    val items: StateFlow<List<OpenDroidSkillInfo>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            try {
                _items.value = repo.listSkills()
            } finally {
                _busy.value = false
            }
        }
    }

    fun delete(skillId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repo.deleteSkill(skillId)
            if (ok) refresh()
            onDone(ok)
        }
    }
}
