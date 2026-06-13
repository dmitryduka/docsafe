package app.docsafe.ui.vault

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private const val ROOT = "root"

/** Navigation graph for the unlocked document UI: folder browser ⇄ document detail. */
@Composable
fun VaultNavHost() {
    val navController = rememberNavController()
    val viewModel: VaultViewModel = hiltViewModel()

    NavHost(navController = navController, startDestination = "browser/$ROOT") {
        composable(
            route = "browser/{folderId}",
            arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString("folderId")
            val folderId = raw?.takeUnless { it == ROOT }
            FolderBrowserScreen(
                folderId = folderId,
                viewModel = viewModel,
                onOpenFolder = { navController.navigate("browser/$it") },
                onOpenDocument = { navController.navigate("document/$it") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenBatchExtract = { navController.navigate("batch/${folderId ?: ROOT}") },
                onNavigateUp = { navController.popBackStack() },
            )
        }
        composable(
            route = "batch/{folderId}",
            arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString("folderId")
            val folderId = raw?.takeUnless { it == ROOT }
            BatchExtractScreen(
                folderId = folderId,
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
            )
        }
        composable(route = "settings") {
            app.docsafe.ui.settings.SettingsScreen(onNavigateUp = { navController.popBackStack() })
        }
        composable(
            route = "document/{documentId}",
            arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
        ) { entry ->
            val documentId = entry.arguments?.getString("documentId") ?: return@composable
            DocumentDetailScreen(
                documentId = documentId,
                viewModel = viewModel,
                onNavigateUp = { navController.popBackStack() },
                onOpenImage = { attachmentId -> navController.navigate("viewer/$documentId/$attachmentId") },
            )
        }
        composable(
            route = "viewer/{documentId}/{attachmentId}",
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType },
                navArgument("attachmentId") { type = NavType.StringType },
            ),
        ) { entry ->
            val documentId = entry.arguments?.getString("documentId") ?: return@composable
            val attachmentId = entry.arguments?.getString("attachmentId") ?: return@composable
            ImageViewerScreen(
                documentId = documentId,
                startAttachmentId = attachmentId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
