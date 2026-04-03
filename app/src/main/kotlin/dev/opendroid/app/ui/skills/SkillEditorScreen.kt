package dev.opendroid.app.ui.skills

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opendroid.app.R
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opendroid.app.skills.SkillEditorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
    existingSkillId: String?,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SkillEditorViewModel = viewModel(factory = SkillEditorViewModel.factory(app, existingSkillId))
    val skillIdDraft by vm.skillIdDraft.collectAsState()
    val content by vm.content.collectAsState()
    val loadError by vm.loadError.collectAsState()
    val saveError by vm.saveError.collectAsState()
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (vm.isNew) {
                            stringResource(R.string.skill_editor_new_title)
                        } else {
                            stringResource(R.string.skill_editor_edit_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            vm.clearSaveError()
                            vm.save { if (it != null) onBack() }
                        },
                    ) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.cd_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(scroll),
        ) {
            loadError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            saveError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (vm.isNew) {
                OutlinedTextField(
                    value = skillIdDraft,
                    onValueChange = vm::updateSkillIdDraft,
                    label = { Text(stringResource(R.string.skill_id_field_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    supportingText = {
                        Text(stringResource(R.string.skill_id_field_supporting))
                    },
                )
            } else {
                Text(
                    text = stringResource(R.string.skill_id_readonly, existingSkillId.orEmpty()),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            OutlinedTextField(
                value = content,
                onValueChange = vm::updateContent,
                label = { Text(stringResource(R.string.skill_md_full_source)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 18,
                maxLines = 40,
            )
        }
    }
}
