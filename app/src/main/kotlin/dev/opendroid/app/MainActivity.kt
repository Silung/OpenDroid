package dev.opendroid.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.opendroid.app.ui.ChatScreen
import dev.opendroid.app.ui.OpenDroidTheme
import dev.opendroid.app.ui.SettingsScreen
import dev.opendroid.app.ui.skills.SkillEditorScreen
import dev.opendroid.app.ui.skills.SkillListScreen
import dev.opendroid.app.overlay.OpenDroidOverlayBridge
import dev.opendroid.app.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenDroidOverlayBridge.refreshWithCurrentLocale(this)
        enableEdgeToEdge()
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent {
            OpenDroidTheme {
                OpenDroidNav(appVersionLabel = versionName)
            }
        }
    }
}

@Composable
private fun OpenDroidNav(appVersionLabel: String) {
    val nav = rememberNavController()
    val versionLabel = if (appVersionLabel.isEmpty()) {
        ""
    } else {
        stringResource(R.string.version_label, appVersionLabel)
    }
    NavHost(navController = nav, startDestination = "chat") {
        composable("chat") {
            val vm: ChatViewModel = viewModel()
            ChatScreen(
                vm = vm,
                appVersionLabel = versionLabel,
                onOpenSettings = { nav.navigate("settings") },
                onOpenSkills = { nav.navigate("skills") },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable("skills") {
            SkillListScreen(
                onBack = { nav.popBackStack() },
                onNewSkill = { nav.navigate("skill_new") },
                onEditSkill = { id -> nav.navigate("skill_edit/$id") },
            )
        }
        composable("skill_new") {
            SkillEditorScreen(
                existingSkillId = null,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "skill_edit/{skillId}",
            arguments = listOf(navArgument("skillId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("skillId").orEmpty()
            SkillEditorScreen(
                existingSkillId = id,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
