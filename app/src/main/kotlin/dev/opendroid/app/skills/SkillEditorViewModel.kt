package dev.opendroid.app.skills

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SkillEditorViewModel(
    application: Application,
    /** 已有 skill 的目录 id；null 表示新建 */
    private val existingSkillId: String?,
) : AndroidViewModel(application) {

    private val repo = OpenDroidSkillRepository(application)

    private val _skillIdDraft = MutableStateFlow(existingSkillId ?: "")
    val skillIdDraft: StateFlow<String> = _skillIdDraft.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    val isNew: Boolean get() = existingSkillId == null

    init {
        if (existingSkillId != null) {
            viewModelScope.launch {
                val text = repo.readSkillMarkdown(existingSkillId)
                if (text == null) {
                    _loadError.value = "无法读取技能：$existingSkillId"
                } else {
                    _content.value = text
                }
            }
        } else {
            _content.value = DEFAULT_NEW_SKILL_TEMPLATE
        }
    }

    fun updateSkillIdDraft(v: String) {
        _skillIdDraft.value = v
    }

    fun updateContent(v: String) {
        _content.value = v
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    /** @return 最终保存的 skillId，失败为 null */
    fun save(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _saveError.value = null
            val id = if (existingSkillId != null) {
                existingSkillId
            } else {
                _skillIdDraft.value.trim()
            }
            if (!OpenDroidSkillPaths.isValidSkillId(id)) {
                _saveError.value = "skill id 须为非空字母数字及 ._-，且不能含路径。"
                onResult(null)
                return@launch
            }
            val ok = repo.saveSkillMarkdown(id, _content.value)
            if (!ok) {
                _saveError.value = "保存失败"
                onResult(null)
            } else {
                onResult(id)
            }
        }
    }

    companion object {
        private val DEFAULT_NEW_SKILL_TEMPLATE = """
            |---
            |name: my-skill
            |description: "一句话说明何时使用该技能"
            |allowed-tools: get_ui_tree tap
            |---
            |
            |## 步骤
            |
            |- …
            |
        """.trimMargin()

        fun factory(
            app: Application,
            existingSkillId: String?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SkillEditorViewModel(app, existingSkillId) as T
        }
    }
}
